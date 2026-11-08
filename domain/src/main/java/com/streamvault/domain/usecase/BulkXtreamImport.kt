package com.streamvault.domain.usecase

import com.streamvault.domain.model.LegacyProvider as Provider
import com.streamvault.domain.model.ProviderEpgSyncMode
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.Result
import com.streamvault.domain.repository.CombinedM3uRepository
import com.streamvault.domain.repository.ProviderRepository
import com.streamvault.domain.repository.XtreamAccountProbe
import com.streamvault.domain.repository.XtreamProbeResult
import com.streamvault.domain.util.XtreamAccountRef
import com.streamvault.domain.util.XtreamBulkImportLine
import com.streamvault.domain.util.XtreamBulkImportParser
import com.streamvault.domain.util.XtreamSlotDecision
import com.streamvault.domain.util.XtreamSlotPolicy
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

enum class BulkXtreamImportOutcome {
    /** New provider created and enabled in the combined source. */
    ADDED,
    /** New provider saved as a disabled reserve member (active limit reached); its catalog is not downloaded. */
    ADDED_RESERVE,
    /** Already imported provider re-checked: it has a free slot and is enabled. */
    UPDATED,
    /** Already imported provider re-checked: free slot, kept as a disabled reserve member. */
    UPDATED_RESERVE,
    NO_SLOTS,
    INACTIVE,
    UNREACHABLE,
    INVALID,
    DUPLICATE,
    /** Free slot, but creating the provider or updating the combined source failed. */
    FAILED
}

enum class BulkXtreamImportPhase { CHECKING, ADDING, DONE }

data class BulkXtreamImportLineResult(
    val lineNumber: Int,
    val hostLabel: String?,
    val username: String?,
    /** Null while the line is still being processed. */
    val outcome: BulkXtreamImportOutcome?
)

data class BulkXtreamImportState(
    val phase: BulkXtreamImportPhase,
    val lines: List<BulkXtreamImportLineResult>,
    val profileName: String? = null
)

/**
 * Imports many Xtream Codes accounts at once as backups for a combined source.
 *
 * Every account is probed through `player_api.php` (bounded concurrency). Every active account with a
 * free connection is saved as a provider and joins the target combined source; accounts already
 * configured (same host + username) are not duplicated but re-checked. Only the first `activeBackupLimit`
 * members stay enabled (existing enabled members count first), because every enabled member's catalog
 * is downloaded and indexed for failover. The rest become disabled reserve members that are saved
 * without downloading a catalog and are skipped by automatic sync until they are enabled.
 */
class BulkXtreamImport @Inject constructor(
    private val accountProbe: XtreamAccountProbe,
    private val providerRepository: ProviderRepository,
    private val validateAndAddProvider: ValidateAndAddProvider,
    private val combinedM3uRepository: CombinedM3uRepository
) {
    companion object {
        const val PROBE_CONCURRENCY = 6
        /** Reserve providers are only authenticated and saved, so several are created at once. */
        const val RESERVE_CREATE_CONCURRENCY = 6
        /** Default enabled-member limit of the target combined source. */
        const val DEFAULT_ACTIVE_BACKUP_LIMIT = 8
        /** Active-backup limit value that enables every account with a free connection. */
        const val UNLIMITED_ACTIVE_BACKUPS = 0
        const val MAX_NAME_USERNAME_LENGTH = 16

        /** Outcome of a probed account that is not imported, or null when it has a free slot. */
        fun rejectedOutcome(decision: XtreamSlotDecision): BulkXtreamImportOutcome? = when (decision) {
            XtreamSlotDecision.FREE_SLOT -> null
            XtreamSlotDecision.NO_SLOTS -> BulkXtreamImportOutcome.NO_SLOTS
            XtreamSlotDecision.INACTIVE -> BulkXtreamImportOutcome.INACTIVE
            XtreamSlotDecision.INVALID -> BulkXtreamImportOutcome.INVALID
        }
    }

    private data class ProbeTarget(val index: Int, val account: XtreamAccountRef)

    /**
     * @param activeBackupLimit most enabled members of the target combined source after the import;
     * [UNLIMITED_ACTIVE_BACKUPS] (or less) enables every account with a free connection.
     */
    fun run(
        text: String,
        defaultProfileName: String,
        activeBackupLimit: Int = DEFAULT_ACTIVE_BACKUP_LIMIT,
        nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000L }
    ): Flow<BulkXtreamImportState> = channelFlow {
        val parsed = XtreamBulkImportParser.parse(text)
        val results = parsed.map { line ->
            when (line) {
                is XtreamBulkImportLine.Account ->
                    BulkXtreamImportLineResult(line.lineNumber, line.account.hostLabel, line.account.username, null)
                is XtreamBulkImportLine.Invalid ->
                    BulkXtreamImportLineResult(line.lineNumber, null, null, BulkXtreamImportOutcome.INVALID)
            }
        }.toMutableList()
        val lock = Mutex()
        var phase = BulkXtreamImportPhase.CHECKING
        var profileName: String? = null
        suspend fun publish() = send(BulkXtreamImportState(phase, results.toList(), profileName))
        suspend fun setOutcome(index: Int, outcome: BulkXtreamImportOutcome) = lock.withLock {
            results[index] = results[index].copy(outcome = outcome)
            publish()
        }

        val seenKeys = HashSet<String>()
        val targets = parsed.mapIndexedNotNull { index, line ->
            val account = (line as? XtreamBulkImportLine.Account)?.account ?: return@mapIndexedNotNull null
            if (seenKeys.add(account.identityKey)) {
                ProbeTarget(index, account)
            } else {
                results[index] = results[index].copy(outcome = BulkXtreamImportOutcome.DUPLICATE)
                null
            }
        }
        publish()

        val decisions = arrayOfNulls<XtreamSlotDecision>(results.size)
        val semaphore = Semaphore(PROBE_CONCURRENCY)
        coroutineScope {
            targets.forEach { target ->
                launch {
                    semaphore.withPermit {
                        val decision = when (val probe = accountProbe.probe(target.account)) {
                            is XtreamProbeResult.Reached -> XtreamSlotPolicy.decide(probe.status, nowEpochSeconds())
                            XtreamProbeResult.Rejected -> XtreamSlotDecision.INVALID
                            XtreamProbeResult.Unreachable -> null
                        }
                        lock.withLock { decisions[target.index] = decision }
                        if (decision == null) setOutcome(target.index, BulkXtreamImportOutcome.UNREACHABLE)
                    }
                }
            }
        }

        lock.withLock {
            phase = BulkXtreamImportPhase.ADDING
            publish()
        }
        val existingByKey: Map<String, Provider> = providerRepository.getProviders().first()
            .filter { it.type == ProviderType.XTREAM_CODES }
            .mapNotNull { provider ->
                XtreamBulkImportParser.identityKeyForServer(provider.serverUrl, provider.username)?.let { it to provider }
            }
            .toMap()
        // Creating or refreshing a provider activates it; the user's active provider is restored afterwards.
        val previousActiveProviderId = providerRepository.getActiveProvider().first()?.id
        val profiles = combinedM3uRepository.getProfiles().first()
        val initialProfile = profiles.singleOrNull()
            ?: profiles.firstOrNull { it.name.equals(defaultProfileName, ignoreCase = true) }
        var profileId: Long? = initialProfile?.id
        profileName = initialProfile?.name
        val memberEnabled = HashMap<Long, Boolean>()
        initialProfile?.members?.forEach { memberEnabled[it.providerId] = it.enabled }
        // Members re-checked by this import count again when processed; unreachable ones keep their state.
        val recheckedIds = targets
            .filter { decisions[it.index] != null }
            .mapNotNull { existingByKey[it.account.identityKey]?.id }
            .toSet()
        var enabledCount = memberEnabled.count { (providerId, enabled) -> enabled && providerId !in recheckedIds }
        fun hasActiveCapacity() = activeBackupLimit <= UNLIMITED_ACTIVE_BACKUPS || enabledCount < activeBackupLimit

        val membershipLock = Mutex()
        /** Sets one member's state; [addIfMissing] makes a non-member join (creating the profile if needed). */
        suspend fun setMembership(providerId: Long, enabled: Boolean, addIfMissing: Boolean): Boolean =
            membershipLock.withLock {
                val currentProfileId = profileId
                val current = memberEnabled[providerId]
                when {
                    current == null && !enabled && !addIfMissing -> return@withLock true
                    current == null && currentProfileId == null -> {
                        val created = combinedM3uRepository.createProfile(defaultProfileName, listOf(providerId))
                            .getOrNull() ?: return@withLock false
                        profileId = created.id
                        lock.withLock { profileName = created.name }
                        memberEnabled[providerId] = true
                    }
                    current == null -> {
                        if (combinedM3uRepository.addProvider(checkNotNull(currentProfileId), providerId) is Result.Error) {
                            return@withLock false
                        }
                        memberEnabled[providerId] = true
                    }
                }
                if (memberEnabled[providerId] != enabled) {
                    val targetProfileId = checkNotNull(profileId)
                    if (combinedM3uRepository.setMemberEnabled(targetProfileId, providerId, enabled) is Result.Error) {
                        return@withLock false
                    }
                    memberEnabled[providerId] = enabled
                }
                true
            }

        suspend fun guarded(block: suspend () -> BulkXtreamImportOutcome): BulkXtreamImportOutcome = try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            BulkXtreamImportOutcome.FAILED
        }

        suspend fun enable(target: ProbeTarget, existing: Provider?): BulkXtreamImportOutcome = when {
            existing != null -> if (setMembership(existing.id, enabled = true, addIfMissing = true)) {
                // A former reserve was saved without a catalog; enabled members need one for failover.
                if (existing.lastSyncedAt == 0L) providerRepository.refreshProviderData(existing.id)
                BulkXtreamImportOutcome.UPDATED
            } else {
                BulkXtreamImportOutcome.FAILED
            }
            else -> {
                val providerId = createProvider(target.account, deferInitialSync = false)
                if (providerId != null && setMembership(providerId, enabled = true, addIfMissing = true)) {
                    BulkXtreamImportOutcome.ADDED
                } else {
                    BulkXtreamImportOutcome.FAILED
                }
            }
        }

        suspend fun reserve(target: ProbeTarget, existing: Provider?): BulkXtreamImportOutcome = when {
            existing != null -> if (setMembership(existing.id, enabled = false, addIfMissing = true)) {
                BulkXtreamImportOutcome.UPDATED_RESERVE
            } else {
                BulkXtreamImportOutcome.FAILED
            }
            else -> {
                val providerId = createProvider(target.account, deferInitialSync = true)
                if (providerId != null && setMembership(providerId, enabled = false, addIfMissing = true)) {
                    BulkXtreamImportOutcome.ADDED_RESERVE
                } else {
                    BulkXtreamImportOutcome.FAILED
                }
            }
        }

        // Re-checked existing providers first so they keep their place before new accounts are added.
        val (existingTargets, newTargets) = targets.partition { existingByKey.containsKey(it.account.identityKey) }
        val reserveSemaphore = Semaphore(RESERVE_CREATE_CONCURRENCY)
        coroutineScope {
            (existingTargets + newTargets).forEach { target ->
                val decision = decisions[target.index] ?: return@forEach
                val existing = existingByKey[target.account.identityKey]
                val rejected = rejectedOutcome(decision)
                when {
                    rejected != null -> setOutcome(target.index, guarded {
                        existing?.let { setMembership(it.id, enabled = false, addIfMissing = false) }
                        rejected
                    })
                    // Enabled members are handled in order: each one may download a full catalog.
                    hasActiveCapacity() -> {
                        val outcome = guarded { enable(target, existing) }
                        if (outcome != BulkXtreamImportOutcome.FAILED) enabledCount++
                        setOutcome(target.index, outcome)
                    }
                    // The limit only ever fills up, so reserves never take a slot from a later account.
                    else -> launch {
                        reserveSemaphore.withPermit { setOutcome(target.index, guarded { reserve(target, existing) }) }
                    }
                }
            }
        }

        if (previousActiveProviderId != null &&
            providerRepository.getActiveProvider().first()?.id != previousActiveProviderId
        ) {
            providerRepository.setActiveProvider(previousActiveProviderId)
        }
            publish()
        }
    }

    private suspend fun createProvider(account: XtreamAccountRef, deferInitialSync: Boolean): Long? {
        val result = validateAndAddProvider.loginXtream(
            XtreamProviderSetupCommand(
                serverUrl = account.serverUrl,
                username = account.username,
                password = account.password,
                name = "${account.hostLabel} #${account.username.take(MAX_NAME_USERNAME_LENGTH)}",
                // Backups only need channels for failover; the primary provider supplies the guide.
                epgSyncMode = ProviderEpgSyncMode.SKIP,
                deferInitialSync = deferInitialSync
            )
        )
        return when (result) {
            is ValidateAndAddProviderResult.Success -> result.provider.id
            is ValidateAndAddProviderResult.SavedWithWarning -> result.provider.id
            else -> null
        }
    }
}
