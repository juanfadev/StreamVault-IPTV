package com.streamvault.domain.util

import java.util.Locale

/** Account state reported by the Xtream `player_api.php` `user_info` block. */
data class XtreamAccountStatus(
    val authenticated: Boolean,
    val status: String,
    val maxConnections: Int?,
    val activeConnections: Int?,
    val expirationEpochSeconds: Long?
)

enum class XtreamSlotDecision {
    /** Active account with at least one free connection (or unlimited connections). */
    FREE_SLOT,
    /** Active account whose connections are all in use. */
    NO_SLOTS,
    /** Disabled, expired, banned or otherwise not active. */
    INACTIVE,
    /** The server rejected the credentials. */
    INVALID
}

object XtreamSlotPolicy {
    fun decide(status: XtreamAccountStatus, nowEpochSeconds: Long): XtreamSlotDecision {
        if (!status.authenticated) return XtreamSlotDecision.INVALID
        if (status.status.trim().lowercase(Locale.ROOT) != "active") return XtreamSlotDecision.INACTIVE
        val expiration = status.expirationEpochSeconds
        if (expiration != null && expiration > 0L && expiration <= nowEpochSeconds) return XtreamSlotDecision.INACTIVE
        val maxConnections = status.maxConnections
        // max_connections == 0 (or missing) means the panel does not limit connections.
        if (maxConnections == null || maxConnections <= 0) return XtreamSlotDecision.FREE_SLOT
        val activeConnections = status.activeConnections?.coerceAtLeast(0) ?: 0
        return if (activeConnections < maxConnections) XtreamSlotDecision.FREE_SLOT else XtreamSlotDecision.NO_SLOTS
    }
}
