package com.streamvault.domain.usecase

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.manager.ProviderCredentials
import com.streamvault.domain.manager.ProviderSetupInputValidator
import com.streamvault.domain.manager.ValidatedJellyfinProviderInput
import com.streamvault.domain.manager.ValidatedJellyfinQuickConnectProviderInput
import com.streamvault.domain.manager.ValidatedM3uProviderInput
import com.streamvault.domain.manager.ValidatedStalkerProviderInput
import com.streamvault.domain.manager.ValidatedXtreamProviderInput
import com.streamvault.domain.model.ActiveLiveSource
import com.streamvault.domain.model.ActiveLiveSourceOption
import com.streamvault.domain.model.Category
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.CombinedCategory
import com.streamvault.domain.model.CombinedM3uProfile
import com.streamvault.domain.model.CombinedM3uProfileMember
import com.streamvault.domain.model.LegacyProvider as Provider
import com.streamvault.domain.model.ProviderEpgSyncMode
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.Program
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.StalkerAuthMode
import com.streamvault.domain.model.XtreamConfig
import com.streamvault.domain.repository.CombinedM3uRepository
import com.streamvault.domain.repository.ProviderDeleteOutcome
import com.streamvault.domain.repository.ProviderDeleteProgress
import com.streamvault.domain.repository.ProviderRepository
import com.streamvault.domain.repository.ProviderSetupRequest
import com.streamvault.domain.repository.XtreamAccountProbe
import com.streamvault.domain.repository.XtreamProbeResult
import com.streamvault.domain.util.XtreamAccountRef
import com.streamvault.domain.util.XtreamAccountStatus
import com.streamvault.domain.util.XtreamSlotDecision
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.test.runTest
import org.junit.Test

class BulkXtreamImportTest {
    private val providers = FakeProviderRepository()
    private val combined = FakeCombinedRepository()
    private val import = BulkXtreamImport(
        accountProbe = FakeAccountProbe(),
        providerRepository = providers,
        validateAndAddProvider = ValidateAndAddProvider(PassThroughXtreamValidator(), providers),
        combinedM3uRepository = combined
    )

    private suspend fun runImport(vararg usernames: String, limit: Int): BulkXtreamImportState =
        import.run(usernames.joinToString("\n") { line(it) }, PROFILE, activeBackupLimit = limit).last()

    private fun BulkXtreamImportState.outcomes() = lines.map { it.outcome }

    @Test
    fun `limit enables the first free accounts and saves the rest as reserves without a catalog sync`() = runTest {
        val state = runImport("free1", "free2", "free3", "free4", "free5", limit = 2)

        assertThat(state.phase).isEqualTo(BulkXtreamImportPhase.DONE)
        assertThat(state.outcomes()).containsExactly(
            BulkXtreamImportOutcome.ADDED,
            BulkXtreamImportOutcome.ADDED,
            BulkXtreamImportOutcome.ADDED_RESERVE,
            BulkXtreamImportOutcome.ADDED_RESERVE,
            BulkXtreamImportOutcome.ADDED_RESERVE
        ).inOrder()
        assertThat(providers.providers.map { it.username })
            .containsExactly("free1", "free2", "free3", "free4", "free5")
        assertThat(combined.memberStates(providers)).containsExactly(
            "free1", true, "free2", true, "free3", false, "free4", false, "free5", false
        )
        assertThat(providers.deferredByUsername).containsExactly(
            "free1", false, "free2", false, "free3", true, "free4", true, "free5", true
        )
        assertThat(state.profileName).isEqualTo(PROFILE)
    }

    @Test
    fun `unlimited enables every free account`() = runTest {
        val state = runImport("free1", "free2", "free3", "free4", limit = BulkXtreamImport.UNLIMITED_ACTIVE_BACKUPS)

        assertThat(state.outcomes().toSet()).containsExactly(BulkXtreamImportOutcome.ADDED)
        assertThat(combined.memberStates(providers).values.toSet()).containsExactly(true)
        assertThat(providers.deferredByUsername.values.toSet()).containsExactly(false)
    }

    @Test
    fun `existing enabled members count toward the limit`() = runTest {
        val primary = providers.seed("other")
        combined.createProfile(PROFILE, listOf(primary.id))

        val state = runImport("free1", "free2", "free3", limit = 2)

        assertThat(state.outcomes()).containsExactly(
            BulkXtreamImportOutcome.ADDED,
            BulkXtreamImportOutcome.ADDED_RESERVE,
            BulkXtreamImportOutcome.ADDED_RESERVE
        ).inOrder()
        assertThat(combined.memberStates(providers)).containsExactly(
            "other", true, "free1", true, "free2", false, "free3", false
        )
    }

    @Test
    fun `re-running with a lower limit disables surplus members without deleting or duplicating them`() = runTest {
        runImport("free1", "free2", "free3", "free4", limit = 4)

        val state = runImport("free1", "free2", "free3", "free4", limit = 1)

        assertThat(state.outcomes()).containsExactly(
            BulkXtreamImportOutcome.UPDATED,
            BulkXtreamImportOutcome.UPDATED_RESERVE,
            BulkXtreamImportOutcome.UPDATED_RESERVE,
            BulkXtreamImportOutcome.UPDATED_RESERVE
        ).inOrder()
        assertThat(providers.providers).hasSize(4)
        assertThat(combined.memberStates(providers)).containsExactly(
            "free1", true, "free2", false, "free3", false, "free4", false
        )
        assertThat(combined.removedProviderIds).isEmpty()
        assertThat(providers.deletedProviderIds).isEmpty()
    }

    @Test
    fun `re-running with a higher limit enables former reserves and downloads only their catalogs`() = runTest {
        runImport("free1", "free2", "free3", limit = 1)

        val state = runImport("free1", "free2", "free3", limit = BulkXtreamImport.UNLIMITED_ACTIVE_BACKUPS)

        assertThat(state.outcomes().toSet()).containsExactly(BulkXtreamImportOutcome.UPDATED)
        assertThat(combined.memberStates(providers).values.toSet()).containsExactly(true)
        assertThat(providers.refreshedProviderIds.map { id -> providers.providers.first { it.id == id }.username })
            .containsExactly("free2", "free3")
        assertThat(providers.providers).hasSize(3)
    }

    @Test
    fun `existing member without a free slot is disabled and keeps its rejection outcome`() = runTest {
        val existing = providers.seed("full1", serverUrl = HOST)
        combined.createProfile(PROFILE, listOf(existing.id))

        val state = runImport("full1", limit = 2)

        assertThat(state.outcomes()).containsExactly(BulkXtreamImportOutcome.NO_SLOTS)
        assertThat(combined.memberStates(providers)).containsExactly("full1", false)
    }

    @Test
    fun `slot decisions keep their outcome mapping`() = runTest {
        val text = listOf(line("free1"), line("full1"), line("expired1"), line("bad1"), line("down1"), "not a url", line("free1"))
            .joinToString("\n")

        val state = import.run(text, PROFILE, activeBackupLimit = 8).last()

        assertThat(state.outcomes()).containsExactly(
            BulkXtreamImportOutcome.ADDED,
            BulkXtreamImportOutcome.NO_SLOTS,
            BulkXtreamImportOutcome.INACTIVE,
            BulkXtreamImportOutcome.INVALID,
            BulkXtreamImportOutcome.UNREACHABLE,
            BulkXtreamImportOutcome.INVALID,
            BulkXtreamImportOutcome.DUPLICATE
        ).inOrder()
        assertThat(providers.providers.map { it.username }).containsExactly("free1")
        assertThat(BulkXtreamImport.rejectedOutcome(XtreamSlotDecision.FREE_SLOT)).isNull()
        assertThat(BulkXtreamImport.rejectedOutcome(XtreamSlotDecision.NO_SLOTS)).isEqualTo(BulkXtreamImportOutcome.NO_SLOTS)
        assertThat(BulkXtreamImport.rejectedOutcome(XtreamSlotDecision.INACTIVE)).isEqualTo(BulkXtreamImportOutcome.INACTIVE)
        assertThat(BulkXtreamImport.rejectedOutcome(XtreamSlotDecision.INVALID)).isEqualTo(BulkXtreamImportOutcome.INVALID)
    }

    @Test
    fun `restores the previously active provider`() = runTest {
        val primary = providers.seed("primary")
        providers.activeId = primary.id

        runImport("free1", "free2", limit = 2)

        assertThat(providers.activeId).isEqualTo(primary.id)
    }

    private companion object {
        const val PROFILE = "Backups"
        const val HOST = "http://host.test"

        fun line(username: String) = "$HOST/get.php?username=$username&password=pw"
    }
}

/** Username prefix decides the probe result: free, full, expired, bad (rejected) or down (unreachable). */
private class FakeAccountProbe : XtreamAccountProbe {
    override suspend fun probe(account: XtreamAccountRef): XtreamProbeResult = when {
        account.username.startsWith("free") -> XtreamProbeResult.Reached(status("Active", max = 2, active = 0))
        account.username.startsWith("full") -> XtreamProbeResult.Reached(status("Active", max = 1, active = 1))
        account.username.startsWith("expired") -> XtreamProbeResult.Reached(status("Expired", max = 2, active = 0))
        account.username.startsWith("bad") -> XtreamProbeResult.Rejected
        else -> XtreamProbeResult.Unreachable
    }

    private fun status(status: String, max: Int, active: Int) = XtreamAccountStatus(
        authenticated = true,
        status = status,
        maxConnections = max,
        activeConnections = active,
        expirationEpochSeconds = null
    )
}

private class PassThroughXtreamValidator : ProviderSetupInputValidator {
    override fun validateXtream(
        serverUrl: String,
        username: String,
        password: String,
        allowBlankPassword: Boolean,
        name: String,
        httpUserAgent: String,
        httpHeaders: String
    ): Result<ValidatedXtreamProviderInput> =
        Result.success(ValidatedXtreamProviderInput(serverUrl, username, password, name, httpUserAgent, httpHeaders))

    override fun validateM3u(url: String, name: String, httpUserAgent: String, httpHeaders: String): Result<ValidatedM3uProviderInput> =
        error("Not used in test")

    override fun validateStalker(
        portalUrl: String,
        macAddress: String,
        name: String,
        authMode: StalkerAuthMode,
        username: String,
        password: String,
        allowBlankPassword: Boolean,
        httpUserAgent: String,
        httpHeaders: String,
        deviceProfile: String,
        timezone: String,
        locale: String,
        serialNumber: String,
        deviceId: String,
        deviceId2: String,
        signature: String,
        stalkerAdvancedOptionsJson: String
    ): Result<ValidatedStalkerProviderInput> = error("Not used in test")

    override fun validateJellyfin(
        serverUrl: String,
        username: String,
        password: String,
        name: String,
        allowBlankPassword: Boolean
    ): Result<ValidatedJellyfinProviderInput> = error("Not used in test")

    override fun validateJellyfinQuickConnect(serverUrl: String, name: String): Result<ValidatedJellyfinQuickConnectProviderInput> =
        error("Not used in test")
}

/** Setting up a provider with a catalog sync activates it, as the real repository does. */
private class FakeProviderRepository : ProviderRepository {
    val providers = mutableListOf<Provider>()
    val deferredByUsername = linkedMapOf<String, Boolean>()
    val refreshedProviderIds = mutableListOf<Long>()
    val deletedProviderIds = mutableListOf<Long>()
    var activeId: Long? = null
    private var nextId = 100L

    fun seed(username: String, serverUrl: String = "http://seed.test"): Provider =
        Provider(id = nextId++, name = username, type = ProviderType.XTREAM_CODES, serverUrl = serverUrl, username = username)
            .also(providers::add)

    override fun getProviders(): Flow<List<Provider>> = flow { emit(providers.toList()) }

    override fun getActiveProvider(): Flow<Provider?> = flow { emit(providers.firstOrNull { it.id == activeId }) }

    override suspend fun getProvider(id: Long): Provider? = providers.firstOrNull { it.id == id }

    override suspend fun addProvider(provider: Provider): Result<Long> = error("Not used in test")

    override suspend fun updateProvider(provider: Provider): Result<Unit> = error("Not used in test")

    override suspend fun deleteProvider(
        id: Long,
        onProgress: ((ProviderDeleteProgress) -> Unit)?
    ): Result<ProviderDeleteOutcome> {
        deletedProviderIds += id
        return error("Not used in test")
    }

    override suspend fun getAllProviderCredentials(): List<ProviderCredentials> = emptyList()

    override suspend fun updateProviderPassword(serverUrl: String, username: String, cleartextPassword: String): Boolean = false

    override suspend fun setActiveProvider(id: Long): Result<Unit> {
        activeId = id
        return Result.success(Unit)
    }

    override suspend fun setupProvider(
        request: ProviderSetupRequest,
        onProgress: ((String) -> Unit)?,
        onCode: ((String) -> Unit)?
    ): Result<Provider> {
        val configured = request as ProviderSetupRequest.Configured
        val config = configured.configuration as XtreamConfig
        deferredByUsername[config.username] = configured.deferInitialSync
        val provider = Provider(
            id = nextId++,
            name = configured.name,
            type = ProviderType.XTREAM_CODES,
            serverUrl = config.serverUrl,
            username = config.username,
            epgSyncMode = config.epgSyncMode,
            lastSyncedAt = if (configured.deferInitialSync) 0L else 1L
        )
        providers += provider
        if (!configured.deferInitialSync) activeId = provider.id
        return Result.success(provider)
    }

    override suspend fun refreshProviderData(
        providerId: Long,
        force: Boolean,
        movieFastSyncOverride: Boolean?,
        epgSyncModeOverride: ProviderEpgSyncMode?,
        onProgress: ((String) -> Unit)?
    ): Result<Unit> {
        refreshedProviderIds += providerId
        val index = providers.indexOfFirst { it.id == providerId }
        providers[index] = providers[index].copy(lastSyncedAt = 2L)
        activeId = providerId
        return Result.success(Unit)
    }

    override suspend fun getProgramsForLiveStream(
        providerId: Long,
        streamId: Long,
        epgChannelId: String?,
        limit: Int
    ): Result<List<Program>> = error("Not used in test")

    override suspend fun buildCatchUpUrl(providerId: Long, streamId: Long, start: Long, end: Long): String? = null
}

private class FakeCombinedRepository : CombinedM3uRepository {
    private val profiles = mutableListOf<CombinedM3uProfile>()
    val removedProviderIds = mutableListOf<Long>()
    private var nextProfileId = 1L

    /** Member enabled state keyed by provider username, in member order. */
    fun memberStates(providers: FakeProviderRepository): Map<String, Boolean> =
        profiles.single().members.associate { member ->
            providers.providers.first { it.id == member.providerId }.username to member.enabled
        }

    private fun update(profileId: Long, transform: (List<CombinedM3uProfileMember>) -> List<CombinedM3uProfileMember>) {
        val index = profiles.indexOfFirst { it.id == profileId }
        profiles[index] = profiles[index].copy(members = transform(profiles[index].members))
    }

    override fun getProfiles(): Flow<List<CombinedM3uProfile>> = flow { emit(profiles.toList()) }

    override suspend fun getProfile(profileId: Long): CombinedM3uProfile? = profiles.firstOrNull { it.id == profileId }

    override suspend fun createProfile(name: String, providerIds: List<Long>): Result<CombinedM3uProfile> {
        val id = nextProfileId++
        val profile = CombinedM3uProfile(
            id = id,
            name = name,
            members = providerIds.mapIndexed { index, providerId -> CombinedM3uProfileMember(profileId = id, providerId = providerId, priority = index) }
        )
        profiles += profile
        return Result.success(profile)
    }

    override suspend fun addProvider(profileId: Long, providerId: Long): Result<Unit> {
        update(profileId) { members ->
            if (members.any { it.providerId == providerId }) members
            else members + CombinedM3uProfileMember(profileId = profileId, providerId = providerId, priority = members.size)
        }
        return Result.success(Unit)
    }

    override suspend fun removeProvider(profileId: Long, providerId: Long): Result<Unit> {
        removedProviderIds += providerId
        update(profileId) { members -> members.filterNot { it.providerId == providerId } }
        return Result.success(Unit)
    }

    override suspend fun setMemberEnabled(profileId: Long, providerId: Long, enabled: Boolean): Result<Unit> {
        update(profileId) { members -> members.map { if (it.providerId == providerId) it.copy(enabled = enabled) else it } }
        return Result.success(Unit)
    }

    override suspend fun updateProfileName(profileId: Long, name: String): Result<Unit> = error("Not used in test")
    override suspend fun deleteProfile(profileId: Long): Result<Unit> = error("Not used in test")
    override suspend fun reorderMembers(profileId: Long, orderedProviderIds: List<Long>): Result<Unit> = error("Not used in test")
    override fun getAvailableCombinableProviders(): Flow<List<Provider>> = error("Not used in test")
    override fun getActiveLiveSource(): Flow<ActiveLiveSource?> = error("Not used in test")
    override suspend fun setActiveLiveSource(source: ActiveLiveSource?): Result<Unit> = error("Not used in test")
    override fun getActiveLiveSourceOptions(): Flow<List<ActiveLiveSourceOption>> = error("Not used in test")
    override suspend fun getActiveCombinedProfile(): CombinedM3uProfile? = error("Not used in test")
    override fun getCombinedCategories(profileId: Long): Flow<List<CombinedCategory>> = error("Not used in test")
    override fun getCombinedChannels(profileId: Long, category: CombinedCategory): Flow<List<Channel>> = error("Not used in test")
    override fun searchCombinedChannels(profileId: Long, category: CombinedCategory, query: String): Flow<List<Channel>> =
        error("Not used in test")
    override fun withCombinedBackups(profileId: Long, channels: Flow<List<Channel>>): Flow<List<Channel>> = error("Not used in test")
}
