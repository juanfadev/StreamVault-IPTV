package com.streamvault.domain.model

import com.streamvault.domain.util.ChannelNormalizer
import java.util.Locale

/** Stable identity shared by combined-source category generation and portable settings backups. */
object CombinedCategoryIdentity {
    private const val ID_BASE = -2_000_000_000L

    /**
     * Provider-independent key: `ES | DEPORTES`, `ES DEPORTES` and `es: Deportes` share `es deportes`.
     * The explicit region (if any) stays part of the key so a combined category id can always be
     * derived again from the name of its representative category.
     */
    fun keyForName(name: String): String {
        val identity = ChannelNormalizer.crossProviderCategoryIdentity(name)
        return listOfNotNull(identity.region?.lowercase(Locale.ROOT), identity.nameKey).joinToString(" ")
    }

    fun idForKey(key: String): Long = ID_BASE - kotlin.math.abs(key.hashCode().toLong())

    fun idForName(name: String): Long = idForKey(keyForName(name))
}
