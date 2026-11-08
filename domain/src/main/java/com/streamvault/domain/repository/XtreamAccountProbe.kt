package com.streamvault.domain.repository

import com.streamvault.domain.util.XtreamAccountRef
import com.streamvault.domain.util.XtreamAccountStatus

sealed interface XtreamProbeResult {
    data class Reached(val status: XtreamAccountStatus) : XtreamProbeResult
    /** Server answered but rejected the credentials or returned no usable account data. */
    data object Rejected : XtreamProbeResult
    /** Network error or timeout. */
    data object Unreachable : XtreamProbeResult
}

/** Lightweight `player_api.php` account check that never persists anything. */
interface XtreamAccountProbe {
    suspend fun probe(account: XtreamAccountRef): XtreamProbeResult
}
