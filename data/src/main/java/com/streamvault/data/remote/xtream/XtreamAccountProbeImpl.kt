package com.streamvault.data.remote.xtream

import com.streamvault.data.remote.dto.XtreamUserInfo
import com.streamvault.domain.repository.XtreamAccountProbe
import com.streamvault.domain.repository.XtreamProbeResult
import com.streamvault.domain.util.XtreamAccountRef
import com.streamvault.domain.util.XtreamAccountStatus
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/** Probes `player_api.php` with a short timeout; never logs credentials. */
@Singleton
class XtreamAccountProbeImpl @Inject constructor(
    private val xtreamApiService: XtreamApiService
) : XtreamAccountProbe {
    private companion object {
        const val PROBE_TIMEOUT_MS = 10_000L
    }

    override suspend fun probe(account: XtreamAccountRef): XtreamProbeResult = try {
        val response = withTimeout(PROBE_TIMEOUT_MS) {
            xtreamApiService.authenticate(
                XtreamUrlFactory.buildPlayerApiUrl(account.serverUrl, account.username, account.password)
            )
        }
        XtreamProbeResult.Reached(response.userInfo.toAccountStatus())
    } catch (_: TimeoutCancellationException) {
        XtreamProbeResult.Unreachable
    } catch (e: CancellationException) {
        throw e
    } catch (_: XtreamAuthenticationException) {
        XtreamProbeResult.Rejected
    } catch (_: XtreamParsingException) {
        // Panels answer rejected logins with `{"user_info":{"auth":0}}` and no server info.
        XtreamProbeResult.Rejected
    } catch (_: Exception) {
        XtreamProbeResult.Unreachable
    }
}

internal fun XtreamUserInfo.toAccountStatus(): XtreamAccountStatus = XtreamAccountStatus(
    authenticated = auth == 1,
    status = status,
    maxConnections = maxConnections.trim().toIntOrNull(),
    activeConnections = activeConnections.trim().toIntOrNull(),
    expirationEpochSeconds = expDate?.trim()?.toLongOrNull()
)
