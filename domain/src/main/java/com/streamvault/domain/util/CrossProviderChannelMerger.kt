package com.streamvault.domain.util

import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.LiveChannelVariant
import java.util.concurrent.ConcurrentHashMap

/** Items that share one provider-independent identity, in input order. */
data class CrossProviderGroup<T>(
    val key: String,
    val items: List<T>
)

object CrossProviderGrouping {
    /**
     * Groups [items] by [CrossProviderIdentity.nameKey], preserving first-appearance order.
     *
     * Items carrying different explicit regions (`ES: Sky Sports` vs `UK: Sky Sports`) are split into
     * one group per region; items without a region join the first region seen for that name, so
     * `ES - LA 1` and `LA 1 HEVC` still merge. Split groups get the key `nameKey@REGION`.
     */
    fun <T> group(items: List<T>, identityOf: (T) -> CrossProviderIdentity): List<CrossProviderGroup<T>> {
        val byName = LinkedHashMap<String, MutableList<Pair<T, String?>>>()
        items.forEach { item ->
            val identity = identityOf(item)
            byName.getOrPut(identity.nameKey) { mutableListOf() }.add(item to identity.region)
        }
        val groups = ArrayList<CrossProviderGroup<T>>(byName.size)
        byName.forEach { (nameKey, entries) ->
            val regions = entries.mapNotNullTo(LinkedHashSet()) { it.second }
            if (regions.size <= 1) {
                groups += CrossProviderGroup(nameKey, entries.map { it.first })
            } else {
                val firstRegion = regions.first()
                val buckets = LinkedHashMap<String, MutableList<T>>()
                regions.forEach { region -> buckets[region] = mutableListOf() }
                entries.forEach { (item, region) -> buckets.getValue(region ?: firstRegion).add(item) }
                buckets.forEach { (region, bucket) -> groups += CrossProviderGroup("$nameKey@$region", bucket) }
            }
        }
        return groups
    }
}

/** Profile-wide lookup of the channels that represent the same logical channel across providers. */
class CrossProviderChannelIndex internal constructor(
    private val groupKeyByRawId: Map<Long, String>,
    private val channelsByGroupKey: Map<String, List<Channel>>
) {
    fun groupKeyOf(channel: Channel): String? =
        groupKeyByRawId[channel.id]
            ?: channel.variants.firstNotNullOfOrNull { variant -> groupKeyByRawId[variant.rawChannelId] }

    fun groupKeyFor(identity: CrossProviderIdentity): String? = when {
        channelsByGroupKey.containsKey(identity.nameKey) -> identity.nameKey
        identity.region != null && channelsByGroupKey.containsKey("${identity.nameKey}@${identity.region}") ->
            "${identity.nameKey}@${identity.region}"
        else -> null
    }

    fun channelsFor(groupKey: String): List<Channel> = channelsByGroupKey[groupKey].orEmpty()

    companion object {
        val EMPTY = CrossProviderChannelIndex(emptyMap(), emptyMap())
    }
}

/**
 * Merges the same live channel coming from several providers into one [Channel] whose variants
 * span every provider: variants are ordered by provider priority first (lower value wins) and keep
 * each provider's own quality ordering inside that block. Selecting a variant of another provider
 * through [Channel.withSelectedVariant] moves playback, EPG and favorites identity to that provider.
 */
object CrossProviderChannelMerger {
    private const val IDENTITY_CACHE_LIMIT = 100_000
    private const val LOCAL_GROUP_PREFIX = "local:"
    private val identityCache = ConcurrentHashMap<String, CrossProviderIdentity>()

    fun identityOf(channel: Channel): CrossProviderIdentity {
        val name = channel.currentVariant?.originalName?.takeIf(String::isNotBlank) ?: channel.name
        identityCache[name]?.let { return it }
        val identity = ChannelNormalizer.crossProviderIdentity(name)
        if (identityCache.size >= IDENTITY_CACHE_LIMIT) identityCache.clear()
        identityCache[name] = identity
        return identity
    }

    /** Builds the profile-wide index; [channels] must be ordered by provider priority. */
    fun buildIndex(channels: List<Channel>): CrossProviderChannelIndex {
        if (channels.isEmpty()) return CrossProviderChannelIndex.EMPTY
        val groups = CrossProviderGrouping.group(channels, ::identityOf)
        val groupKeyByRawId = HashMap<Long, String>(channels.size * 2)
        val channelsByGroupKey = HashMap<String, List<Channel>>(groups.size * 2)
        groups.forEach { group ->
            channelsByGroupKey[group.key] = group.items
            group.items.forEach { channel ->
                channel.allVariantRawIds().forEach { rawId -> groupKeyByRawId.putIfAbsent(rawId, group.key) }
            }
        }
        return CrossProviderChannelIndex(groupKeyByRawId, channelsByGroupKey)
    }

    /**
     * Collapses [channels] (ordered by provider priority) so every logical channel appears once.
     * When an [index] is available, variants of the same channel found elsewhere in the profile
     * (other categories of other providers) are attached as backups too.
     */
    fun merge(
        channels: List<Channel>,
        providerPriority: Map<Long, Int>,
        index: CrossProviderChannelIndex? = null
    ): List<Channel> {
        if (channels.isEmpty()) return channels
        val keys = arrayOfNulls<String>(channels.size)
        val unresolvedPositions = ArrayList<Int>()
        channels.forEachIndexed { position, channel ->
            val key = index?.let { it.groupKeyOf(channel) ?: it.groupKeyFor(identityOf(channel)) }
            if (key != null) keys[position] = key else unresolvedPositions += position
        }
        if (unresolvedPositions.isNotEmpty()) {
            CrossProviderGrouping.group(unresolvedPositions) { position -> identityOf(channels[position]) }
                .forEach { group -> group.items.forEach { position -> keys[position] = LOCAL_GROUP_PREFIX + group.key } }
        }
        val grouped = LinkedHashMap<String, MutableList<Channel>>()
        channels.forEachIndexed { position, channel ->
            grouped.getOrPut(keys[position]!!) { mutableListOf() }.add(channel)
        }
        return grouped.map { (key, members) ->
            mergeGroup(
                members = members,
                backups = index?.channelsFor(key).orEmpty(),
                providerPriority = providerPriority,
                keepSelectionOf = null
            )
        }
    }

    /**
     * Adds cross-provider backups to an explicit channel list (favorites, recents) without changing
     * which variant each entry starts on.
     */
    fun attachBackups(
        channels: List<Channel>,
        providerPriority: Map<Long, Int>,
        index: CrossProviderChannelIndex?
    ): List<Channel> {
        if (index == null || channels.isEmpty()) return channels
        return channels.map { channel ->
            val key = index.groupKeyOf(channel) ?: index.groupKeyFor(identityOf(channel)) ?: return@map channel
            mergeGroup(
                members = listOf(channel),
                backups = index.channelsFor(key),
                providerPriority = providerPriority,
                keepSelectionOf = channel
            )
        }.distinctBy { it.id }
    }

    private fun mergeGroup(
        members: List<Channel>,
        backups: List<Channel>,
        providerPriority: Map<Long, Int>,
        keepSelectionOf: Channel?
    ): Channel {
        val priorityOf = { providerId: Long -> providerPriority[providerId] ?: Int.MAX_VALUE }
        val representative = members.minByOrNull { priorityOf(it.providerId) } ?: members.first()
        val candidates = (backups + members).sortedBy { priorityOf(it.providerId) }
        val mergedVariants = LinkedHashMap<Long, LiveChannelVariant>()
        keepSelectionOf?.variantsOrSelf()?.forEach { variant -> mergedVariants.putIfAbsent(variant.rawChannelId, variant) }
        candidates.forEach { candidate ->
            candidate.variantsOrSelf().forEach { variant -> mergedVariants.putIfAbsent(variant.rawChannelId, variant) }
        }
        if (mergedVariants.size <= 1 ||
            (representative.variants.isNotEmpty() && mergedVariants.keys == representative.allVariantRawIds())
        ) {
            return representative
        }
        val variants = mergedVariants.values.toList()
        val selectedId = keepSelectionOf?.selectedVariantId?.takeIf(mergedVariants::containsKey)
            ?: variants.first().rawChannelId
        val merged = representative.copy(
            variants = variants,
            logoUrl = representative.logoUrl ?: candidates.firstNotNullOfOrNull { it.logoUrl }
        )
        return merged.withSelectedVariant(selectedId) ?: merged
    }

    private fun Channel.variantsOrSelf(): List<LiveChannelVariant> = variants.ifEmpty {
        listOf(
            LiveChannelVariant(
                rawChannelId = id,
                logicalGroupId = logicalGroupId,
                providerId = providerId,
                originalName = name,
                canonicalName = canonicalName,
                streamUrl = streamUrl,
                streamId = streamId,
                epgChannelId = epgChannelId,
                number = number,
                errorCount = errorCount,
                catchUpSupported = catchUpSupported,
                catchUpDays = catchUpDays,
                catchUpSource = catchUpSource,
                catchUpType = catchUpType,
                catchUpCorrectionSeconds = catchUpCorrectionSeconds
            )
        )
    }
}
