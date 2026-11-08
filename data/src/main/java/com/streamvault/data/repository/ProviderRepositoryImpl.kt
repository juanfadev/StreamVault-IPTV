package com.streamvault.data.repository

import com.streamvault.domain.model.Provider as StableProvider

import android.content.Context
import com.google.gson.Gson
import com.streamvault.data.local.dao.ProviderDeletionCleanupDao
import com.streamvault.data.local.dao.ProviderConfigRevisionDao
import com.streamvault.data.local.entity.ProviderDeletionCleanupEntity
import com.streamvault.data.local.entity.ProviderConfigRevisionEntity
import com.streamvault.data.local.entity.ProviderConfigRevisionState
import com.streamvault.data.local.DatabaseTransactionRunner
import com.streamvault.data.local.dao.*
import com.streamvault.data.local.entity.ProviderEntity
import com.streamvault.data.local.entity.ProviderConfigEntity
import com.streamvault.data.local.entity.ProviderAccountRuntimeEntity
import com.streamvault.data.local.entity.StalkerDiscoveryStageEntity
import com.streamvault.data.local.entity.StalkerPortalStateEntity
import com.streamvault.data.local.entity.StalkerIndexJobEntity
import com.streamvault.data.manager.recording.RecordingAlarmScheduler
import com.streamvault.data.manager.reminder.ProgramReminderAlarmScheduler
import com.streamvault.data.mapper.*
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.data.provider.ProviderConfigurationCodec
import com.streamvault.data.provider.ProviderConfigRevisionCodec
import com.streamvault.data.provider.ProviderCapabilityResolver
import com.streamvault.data.provider.DefaultProviderPublicProjection
import com.streamvault.data.provider.ProviderObservationStreams
import com.streamvault.data.provider.StalkerClientOptions
import com.streamvault.data.provider.TypedProviderClientFactory
import com.streamvault.data.provider.toAccountRuntime
import com.streamvault.data.provider.toTypedConfiguration
import com.streamvault.data.provider.toLegacyProvider
import com.streamvault.data.provider.guidePolicy
import com.streamvault.data.provider.logoPolicy
import com.streamvault.data.remote.jellyfin.JellyfinProvider
import com.streamvault.data.remote.stalker.StalkerApiService
import com.streamvault.data.remote.stalker.StalkerResolvedCategoryHandoff
import com.streamvault.data.remote.stalker.StalkerPlaybackMode
import com.streamvault.data.remote.stalker.StalkerProvider
import com.streamvault.data.remote.stalker.StalkerPortalStateStore
import com.streamvault.domain.model.StalkerCompatibilityRegistry
import com.streamvault.domain.model.StalkerIdentityStrategy
import com.streamvault.data.remote.stalker.StalkerApiError
import com.streamvault.data.remote.xtream.XtreamProvider
import com.streamvault.data.security.CredentialCrypto
import com.streamvault.data.security.CredentialDecryptionException
import com.streamvault.data.util.ProviderUrlProtocolResolver
import com.streamvault.data.sync.ProviderSyncCommands
import com.streamvault.data.sync.ProviderSyncWorker
import com.streamvault.data.sync.ProviderWorkflowDisposition
import com.streamvault.data.sync.ProviderWorkflowOutcome
import com.streamvault.data.sync.ProviderWorkflowRunner
import com.streamvault.data.sync.ProviderWorkflowCommitFence
import com.streamvault.data.sync.hasUsableLiveCatalogForActivation
import com.streamvault.data.local.entity.ProviderWorkflowPhase
import com.streamvault.data.local.entity.ProviderWorkflowReason
import com.streamvault.domain.util.ProviderInputSanitizer
import com.streamvault.data.util.UrlSecurityPolicy
import com.streamvault.domain.manager.ProviderCredentials
import com.streamvault.domain.model.*
import com.streamvault.domain.model.LegacyProvider as Provider
import com.streamvault.domain.repository.LiveStreamProgramRequest
import com.streamvault.domain.repository.ProviderDeleteOutcome
import com.streamvault.domain.repository.ProviderDeleteProgress
import com.streamvault.domain.repository.ProviderRepository
import com.streamvault.domain.repository.ProviderSetupRequest
import com.streamvault.domain.repository.SyncMetadataRepository
import com.streamvault.domain.provider.CapabilityResolution
import com.streamvault.domain.provider.CatchUpRequest
import com.streamvault.domain.provider.GuideRequest
import com.streamvault.domain.provider.GuideSource
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.URI
import java.util.UUID
import java.util.logging.Logger
import javax.inject.Inject
import javax.inject.Singleton
import dagger.hilt.android.qualifiers.ApplicationContext

@Singleton
class ProviderRepositoryImpl @Inject constructor(
    private val providerDao: ProviderDao,
    private val providerSnapshotDao: ProviderSnapshotDao,
    private val categoryDao: CategoryDao,
    private val channelDao: ChannelDao,
    private val movieDao: MovieDao,
    private val seriesDao: SeriesDao,
    private val programDao: ProgramDao,
    private val recordingRunDao: RecordingRunDao,
    private val programReminderDao: ProgramReminderDao,
    private val stalkerApiService: StalkerApiService,
    private val credentialCrypto: CredentialCrypto,
    private val providerConfigurationCodec: ProviderConfigurationCodec,
    private val preferencesRepository: PreferencesRepository,
    private val syncManager: ProviderSyncCommands,
    private val syncMetadataRepository: SyncMetadataRepository,
    private val transactionRunner: DatabaseTransactionRunner,
    private val recordingAlarmScheduler: RecordingAlarmScheduler,
    private val programReminderAlarmScheduler: ProgramReminderAlarmScheduler,
    private val jellyfinProvider: JellyfinProvider,
    private val stalkerIndexJobDao: StalkerIndexJobDao,
    private val stalkerPortalStateStore: StalkerPortalStateStore,
    private val movieCategoryHydrationDao: MovieCategoryHydrationDao,
    private val seriesCategoryHydrationDao: SeriesCategoryHydrationDao,
    private val stalkerDiscoveryStageDao: StalkerDiscoveryStageDao? = null,
    private val providerDeletionCleanupDao: ProviderDeletionCleanupDao,
    private val providerDeletionCleanupEnqueuer: ProviderDeletionCleanupEnqueuer,
    private val providerConfigRevisionDao: ProviderConfigRevisionDao,
    private val gson: Gson,
    private val providerWorkflowRunner: ProviderWorkflowRunner,
    private val providerWorkflowCommitFence: ProviderWorkflowCommitFence,
    private val providerCapabilityResolver: ProviderCapabilityResolver,
    private val typedProviderClientFactory: TypedProviderClientFactory,
    @param:ApplicationContext private val appContext: Context
) : ProviderRepository {
    private companion object {
        const val XTREAM_GUIDE_BATCH_CONCURRENCY = 4
        const val BACKGROUND_EPG_START_DELAY_MS = 15_000L
        // Row-equivalent weights for non-row delete steps so the progress bar still moves
        // meaningfully on providers with tiny (or empty) catalogs.
        const val ALARM_STEP_WEIGHT = 5
        const val PROVIDER_ROW_STEP_WEIGHT = 200
        const val FINALIZE_STEP_WEIGHT = 200
        const val STALKER_DISCOVERY_STAGE_TTL_MILLIS = 60L * 60L * 1000L
        const val MANUAL_REFRESH_PRIORITY = 50
        val logger: Logger = Logger.getLogger(ProviderRepositoryImpl::class.java.name)
        val STALKER_URL_SCHEME_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")
    }

    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val observationStreams by lazy {
        ProviderObservationStreams(
            providerDao = providerDao,
            providerSnapshotDao = providerSnapshotDao,
            projection = DefaultProviderPublicProjection(providerConfigurationCodec, gson)
        )
    }

    private data class PendingProviderEdit(
        val revision: Long,
        val candidate: Provider,
        val secureCandidate: ProviderEntity
    )

    private data class InitialOnboardingTarget(
        val providerData: Provider,
        val providerForSync: Provider,
        val pendingEdit: PendingProviderEdit? = null
    )

    override fun getProviders(): Flow<List<Provider>> = observationStreams.providers

    override fun getActiveProvider(): Flow<Provider?> = observationStreams.activeProvider

    override suspend fun getProvider(id: Long): Provider? {
        if (providerSnapshotDao.getConfig(id) == null) return null
        return observationStreams.providers.first().firstOrNull { it.id == id }
    }

    override suspend fun addProvider(provider: Provider): Result<Long> = try {
        val id = transactionRunner.inTransaction {
            val insertedId = providerDao.insert(provider.toSecureEntity())
            persistTypedSnapshot(insertedId, provider, currentGeneration = null)
            insertedId
        }
        Result.success(id)
    } catch (e: Exception) {
        Result.error("Failed to add provider: ${e.message}", e)
    }

    override suspend fun updateProvider(provider: Provider): Result<Unit> = try {
        transactionRunner.inTransaction {
            val current = providerSnapshotDao.getConfig(provider.id)
            val currentConfiguration = current?.let { stored ->
                providerConfigurationCodec.decode(stored.type, stored.encryptedConfigJson)
            }
            val persistedProvider = provider.withPersistedCredential(currentConfiguration)
            providerDao.update(persistedProvider.toSecureEntity())
            persistTypedSnapshot(provider.id, persistedProvider, current?.configurationGeneration)
        }
        if (provider.type == ProviderType.STALKER_PORTAL) {
            stalkerPortalStateStore.invalidate(provider.id)
        }
        if (provider.type == ProviderType.STALKER_PORTAL &&
            provider.stalkerCatalogMode == StalkerCatalogMode.ON_DEMAND
        ) {
            stalkerIndexJobDao.disableForProvider(provider.id, System.currentTimeMillis())
            syncManager.cancelStalkerIndexSync(provider.id)
        }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.error("Failed to update provider: ${e.message}", e)
    }

    override suspend fun buildStalkerSearchIndexOnce(providerId: Long): Result<Unit> {
        return try {
            val provider = providerDao.getById(providerId)
                ?: return Result.error("Provider not found")
            if (provider.type != ProviderType.STALKER_PORTAL) {
                return Result.error("Complete search indexing is only available for Stalker providers")
            }
            val now = System.currentTimeMillis()
            val sections = if (
                providerSnapshotDao.getRuntime(providerId)?.catalogLayout == CatalogLayout.UNIFIED_VOD
            ) {
                listOf(ContentType.MOVIE)
            } else {
                listOf(ContentType.MOVIE, ContentType.SERIES)
            }
            transactionRunner.inTransaction {
                // A manual rebuild is an explicit retry of every category, including rows
                // previously marked COMPLETE, TRUNCATED, or terminally failed. Keep the
                // current searchable content visible while resetting only crawl progress.
                movieCategoryHydrationDao.deleteByProvider(providerId)
                seriesCategoryHydrationDao.deleteByProvider(providerId)
                sections.forEach { section ->
                    stalkerIndexJobDao.upsert(
                        StalkerIndexJobEntity(
                            providerId = providerId,
                            section = section,
                            state = StalkerIndexState.QUEUED,
                            updatedAt = now
                        )
                    )
                }
            }
            syncManager.scheduleStalkerIndexSync(providerId, force = false)
            Result.success(Unit)
        } catch (error: Exception) {
            Result.error("Failed to queue the complete Stalker search index", error)
        }
    }

    override suspend fun getAllProviderCredentials(): List<ProviderCredentials> {
        return providerDao.getAllSync()
            .mapNotNull { entity -> loadLegacyProvider(entity.id) }
            .map { provider ->
                ProviderCredentials(
                    serverUrl = provider.serverUrl,
                    username = provider.username,
                    password = provider.password,
                )
            }
            .filter { it.username.isNotBlank() && it.password.isNotBlank() }
    }

    override suspend fun updateProviderPassword(
        serverUrl: String,
        username: String,
        cleartextPassword: String,
    ): Boolean {
        var matchedSnapshot: ProviderSnapshot? = null
        for (identity in providerDao.getAllSync()) {
            val candidate = providerCapabilityResolver.snapshot(identity.id) ?: continue
            val matches =
                when (val config = candidate.configuration) {
                    is XtreamConfig -> config.serverUrl == serverUrl && config.username == username
                    is StalkerConfig -> config.portalUrl == serverUrl && config.username == username
                    is JellyfinConfig -> config.serverUrl == serverUrl && config.username == username
                    is M3uConfig -> false
                }
            if (matches) {
                matchedSnapshot = candidate
                break
            }
        }
        val snapshot = matchedSnapshot ?: return false
        val updatedConfiguration = when (val config = snapshot.configuration) {
            is XtreamConfig -> config.copy(password = cleartextPassword)
            is StalkerConfig -> config.copy(password = cleartextPassword)
            is JellyfinConfig -> config.copy(credential = cleartextPassword)
            is M3uConfig -> return false
        }
        val committed = providerSnapshotDao.commitConfiguration(
            ProviderConfigEntity(
                providerId = snapshot.provider.id,
                type = updatedConfiguration.type,
                schemaVersion = updatedConfiguration.schemaVersion,
                configurationGeneration = snapshot.configurationGeneration + 1L,
                identityKey = providerConfigurationCodec.identityKey(updatedConfiguration),
                encryptedConfigJson = providerConfigurationCodec.encode(updatedConfiguration),
                guideSourcePolicy = updatedConfiguration.guidePolicy(),
                channelLogoSourcePolicy = updatedConfiguration.logoPolicy(),
                updatedAt = System.currentTimeMillis()
            )
        )
        if (committed && snapshot.provider.type == ProviderType.STALKER_PORTAL) {
            stalkerPortalStateStore.invalidate(snapshot.provider.id)
        }
        return committed
    }

    override suspend fun deleteProvider(
        id: Long,
        onProgress: ((ProviderDeleteProgress) -> Unit)?
    ): Result<ProviderDeleteOutcome> = try {
        val estimatedRecordingRunIds = recordingRunDao.getIdsByProvider(id)
        val estimatedReminderIds = programReminderDao.getIdsByProvider(id)

        // Weight progress by the real row counts of the large child tables so the bar
        // advances proportionally to the work being done instead of in two big jumps.
        val programCount = programDao.countByProvider(id)
        val channelCount = channelDao.countByProvider(id)
        val movieCount = movieDao.countByProvider(id)
        val seriesCount = seriesDao.countByProvider(id)

        val totalWeight = (
                programCount + channelCount + movieCount + seriesCount +
                (estimatedRecordingRunIds.size + estimatedReminderIds.size) * ALARM_STEP_WEIGHT +
                PROVIDER_ROW_STEP_WEIGHT + FINALIZE_STEP_WEIGHT
            ).coerceAtLeast(1)
        var completedWeight = 0
        var pendingCleanupActions = 0

        fun reportProgress(message: String) {
            onProgress?.invoke(
                ProviderDeleteProgress(
                    message = message,
                    fraction = (completedWeight.toFloat() / totalWeight.toFloat()).coerceIn(0f, 1f)
                )
            )
        }

        reportProgress("Preparing to remove provider...")
        transactionRunner.inTransaction {
            // Re-read alarm identities under the same Room transaction that removes
            // their rows. This closes the window where a newly committed alarm could
            // be cascade-deleted without receiving a durable cancellation tombstone.
            val recordingRunIds = recordingRunDao.getIdsByProvider(id)
            val reminderIds = programReminderDao.getIdsByProvider(id)
            providerDeletionCleanupDao.insertAll(
                recordingRunIds.map { ProviderDeletionCleanupEntity(id = 0, providerId = id, action = ProviderDeletionCleanupWorker.RECORDING_ALARM, targetId = it) } +
                    reminderIds.map { ProviderDeletionCleanupEntity(id = 0, providerId = id, action = ProviderDeletionCleanupWorker.REMINDER_ALARM, targetId = it.toString()) } +
                    ProviderDeletionCleanupEntity(id = 0, providerId = id, action = ProviderDeletionCleanupWorker.SYNC_RUNTIME)
            )
            // ProgramEntity still has no provider FK, so it requires explicit cleanup.
            if (programCount > 0) reportProgress("Removing $programCount guide entries...")
            programDao.deleteByProvider(id)
            completedWeight += programCount

            if (channelCount > 0) reportProgress("Removing $channelCount channels...")
            channelDao.deleteByProvider(id)
            completedWeight += channelCount

            if (movieCount > 0) reportProgress("Removing $movieCount movies...")
            movieDao.deleteByProvider(id)
            completedWeight += movieCount

            if (seriesCount > 0) reportProgress("Removing $seriesCount series...")
            seriesDao.deleteByProvider(id)
            completedWeight += seriesCount

            reportProgress("Removing provider record...")
            providerDao.delete(id)
            completedWeight += PROVIDER_ROW_STEP_WEIGHT
            pendingCleanupActions = providerDeletionCleanupDao.countByProvider(id)
        }
        try {
            if (preferencesRepository.activeLiveSource.first() == ActiveLiveSource.ProviderSource(id)) {
                preferencesRepository.setActiveLiveSource(null)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The provider is already deleted. Keep deletion successful; the live-source
            // repository also filters out references to missing providers.
            logger.warning("Could not clear the saved live source for deleted provider $id")
        }
        stalkerApiService.invalidateSessionScopes(id)
        reportProgress("Provider library removed.")
        reportProgress("Finalizing provider cleanup...")
        val reconciliationRequested = runCatching {
            providerDeletionCleanupEnqueuer.enqueue()
            true
        }.getOrElse {
            logger.warning("Provider cleanup is pending but WorkManager enqueue failed: ${it.message}")
            false
        }
        completedWeight = totalWeight
        reportProgress(
            if (reconciliationRequested) {
                "Provider library deleted; final cleanup continues."
            } else {
                "Provider library deleted; cleanup is pending and will retry on startup."
            }
        )
        Result.success(
            ProviderDeleteOutcome(
                providerId = id,
                pendingCleanupActions = pendingCleanupActions,
                reconciliationRequested = reconciliationRequested
            )
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (e: Exception) {
        Result.error("Failed to delete provider: ${e.message}", e)
    }

    override suspend fun setActiveProvider(id: Long): Result<Unit> {
        return try {
            val provider = providerDao.getById(id)
                ?: return Result.error("Provider not found")
            if (!hasUsableLiveCatalogForActivation(id, provider.type, channelDao, categoryDao, syncMetadataRepository)) {
                syncManager.scheduleProviderSyncResume(id)
                return Result.error("Provider is saved but no content has been committed yet. Sync will resume in background.")
            }
            providerDao.setActive(id)
            syncManager.scheduleAutomaticSyncCheck()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.error("Failed to set active provider: ${e.message}", e)
        }
    }

    override suspend fun setupProvider(
        request: ProviderSetupRequest,
        onProgress: ((String) -> Unit)?,
        onCode: ((String) -> Unit)?
    ): Result<Provider> = when (request) {
        is ProviderSetupRequest.Configured -> when (val config = request.configuration) {
            is XtreamConfig -> loginXtream(
                serverUrl = config.serverUrl,
                username = config.username,
                password = config.password,
                name = request.name,
                httpUserAgent = config.httpUserAgent,
                httpHeaders = config.httpHeaders,
                xtreamFastSyncEnabled = config.fastSyncEnabled,
                epgSyncMode = config.epgSyncMode,
                xtreamLiveSyncMode = config.liveSyncMode,
                guideSourcePolicy = config.guideSourcePolicy,
                channelLogoSourcePolicy = config.channelLogoSourcePolicy,
                preferredLiveStreamFormat = config.preferredLiveStreamFormat,
                onProgress = onProgress,
                id = request.existingProviderId,
                deferInitialSync = request.deferInitialSync
            )
            is M3uConfig -> validateM3u(
                url = config.playlistUrl,
                name = request.name,
                httpUserAgent = config.httpUserAgent,
                httpHeaders = config.httpHeaders,
                epgSyncMode = config.epgSyncMode,
                m3uVodClassificationEnabled = config.vodClassificationEnabled,
                guideSourcePolicy = config.guideSourcePolicy,
                channelLogoSourcePolicy = config.channelLogoSourcePolicy,
                onProgress = onProgress,
                id = request.existingProviderId
            )
            is StalkerConfig -> loginStalker(
                portalUrl = config.portalUrl,
                macAddress = config.device.macAddress,
                name = request.name,
                authMode = config.authMode,
                username = config.username,
                password = config.password,
                httpUserAgent = config.httpUserAgent,
                httpHeaders = config.httpHeaders,
                deviceProfile = config.device.deviceProfile,
                timezone = config.device.timezone,
                locale = config.device.locale,
                serialNumber = config.device.serialNumber,
                deviceId = config.device.deviceId,
                deviceId2 = config.device.deviceId2,
                signature = config.device.signature,
                stalkerAdvancedOptionsJson = config.advancedOptionsJson,
                protocolPreference = config.protocolPreference,
                transportGrant = config.transportGrant,
                saveWithoutVerification = request.saveWithoutVerification,
                repairConnection = request.repairConnection,
                requestedProfileId = config.requestedProfileId,
                epgSyncMode = config.epgSyncMode,
                catalogMode = config.catalogMode,
                guideSourcePolicy = config.guideSourcePolicy,
                channelLogoSourcePolicy = config.channelLogoSourcePolicy,
                onProgress = onProgress,
                id = request.existingProviderId
            )
            is JellyfinConfig -> loginJellyfin(
                serverUrl = config.serverUrl,
                username = config.username,
                password = config.credential,
                name = request.name,
                onProgress = onProgress,
                id = request.existingProviderId
            )
        }
        is ProviderSetupRequest.JellyfinQuickConnect -> loginJellyfinQuickConnect(
            serverUrl = request.serverUrl,
            name = request.name,
            onCode = onCode,
            onProgress = onProgress,
            id = request.existingProviderId
        )
    }

    internal suspend fun loginXtream(
        serverUrl: String,
        username: String,
        password: String,
        name: String,
        httpUserAgent: String = "",
        httpHeaders: String = "",
        xtreamFastSyncEnabled: Boolean,
        epgSyncMode: ProviderEpgSyncMode = ProviderEpgSyncMode.BACKGROUND,
        xtreamLiveSyncMode: com.streamvault.domain.model.ProviderXtreamLiveSyncMode = ProviderXtreamLiveSyncMode.AUTO,
        guideSourcePolicy: GuideSourcePolicy = GuideSourcePolicy.AUTO,
        channelLogoSourcePolicy: ChannelLogoSourcePolicy = ChannelLogoSourcePolicy.SUPPLIER_PREFERRED,
        preferredLiveStreamFormat: LiveStreamFormatMode? = null,
        onProgress: ((String) -> Unit)? = null,
        id: Long? = null,
        deferInitialSync: Boolean = false
    ): Result<Provider> {
        return try {
        val normalizedServerUrl = ProviderInputSanitizer.normalizeUrl(serverUrl)
        val normalizedUsername = ProviderInputSanitizer.normalizeUsername(username)
        val normalizedName = ProviderInputSanitizer.normalizeProviderName(name)
        val resolvedServerUrl = ProviderUrlProtocolResolver.resolve(normalizedServerUrl)

        ProviderInputSanitizer.validateUrl(resolvedServerUrl)?.let { message ->
            return Result.error(message)
        }
        UrlSecurityPolicy.validateXtreamServerUrl(resolvedServerUrl)?.let { message ->
            return Result.error(message)
        }
        onProgress?.invoke("Authenticating...")
        val existingProvider = if (id != null) {
            // Edit path: check that the new normalized identity does not collide with a
            // different provider before we commit the update.
            val collision = findProviderByConfigurationIdentity(
                XtreamConfig(resolvedServerUrl, normalizedUsername, "")
            )
            if (collision != null && collision.id != id) {
                return Result.error("A provider with this server URL and username already exists.")
            }
            loadLegacyProvider(id)
        } else {
            findProviderByConfigurationIdentity(XtreamConfig(resolvedServerUrl, normalizedUsername, ""))
                ?.let { loadLegacyProvider(it.id) }
        if (deferInitialSync && existingProvider != null) {
            // Deferred setup only creates reserve backups; it never edits or re-syncs a configured provider.
            return Result.success(existingProvider.copy(password = ""))
        }
    }
    }