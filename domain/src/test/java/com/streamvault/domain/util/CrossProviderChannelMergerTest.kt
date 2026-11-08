package com.streamvault.domain.util

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.LiveChannelVariant
import org.junit.Test

class CrossProviderChannelMergerTest {

    private val providerA = 1L
    private val providerB = 2L
    private val providerC = 3L

    private fun variant(rawId: Long, providerId: Long, name: String) = LiveChannelVariant(
        rawChannelId = rawId,
        logicalGroupId = "${providerId}_$name",
        providerId = providerId,
        originalName = name,
        canonicalName = name,
        streamUrl = "xtream://$providerId/live/$rawId?ext=m3u8",
        streamId = rawId,
        epgChannelId = "epg-$providerId-$rawId"
    )

    /** A provider-local channel as ChannelRepository presents it: selected variant first. */
    private fun channel(providerId: Long, vararg variants: LiveChannelVariant): Channel {
        val selected = variants.first()
        return Channel(
            id = selected.rawChannelId,
            name = selected.originalName,
            streamUrl = selected.streamUrl,
            providerId = providerId,
            logicalGroupId = selected.logicalGroupId,
            selectedVariantId = selected.rawChannelId,
            epgChannelId = selected.epgChannelId,
            streamId = selected.streamId,
            categoryId = providerId * 100,
            variants = variants.toList()
        )
    }

    @Test
    fun `same channel from several providers is listed once with variants in priority order`() {
        val b = channel(providerB, variant(21, providerB, "ES| La 1 HD"))
        val a = channel(providerA, variant(11, providerA, "ES - LA 1 FHD"), variant(12, providerA, "ES - LA 1 SD"))
        val c = channel(providerC, variant(31, providerC, "LA 1 HEVC"))
        val la2 = channel(providerA, variant(13, providerA, "ES - LA 2 FHD"))

        // Input arrives in priority order (A, C, B) as the combined repository supplies it.
        val merged = CrossProviderChannelMerger.merge(
            channels = listOf(a, la2, c, b),
            providerPriority = mapOf(providerA to 0, providerC to 1, providerB to 2)
        )

        assertThat(merged.map { it.name }).containsExactly("ES - LA 1 FHD", "ES - LA 2 FHD").inOrder()
        val la1 = merged.first()
        assertThat(la1.variants.map { it.rawChannelId }).containsExactly(11L, 12L, 31L, 21L).inOrder()
        assertThat(la1.id).isEqualTo(11L)
        assertThat(la1.providerId).isEqualTo(providerA)
        assertThat(la1.alternativeStreams).containsExactly(
            "xtream://1/live/12?ext=m3u8",
            "xtream://3/live/31?ext=m3u8",
            "xtream://2/live/21?ext=m3u8"
        ).inOrder()
        assertThat(merged[1]).isSameInstanceAs(la2)
    }

    @Test
    fun `priority order wins even when a lower priority provider is listed first`() {
        val b = channel(providerB, variant(21, providerB, "DAZN 1 FHD"))
        val a = channel(providerA, variant(11, providerA, "ES: DAZN 1"))

        val merged = CrossProviderChannelMerger.merge(
            channels = listOf(b, a),
            providerPriority = mapOf(providerA to 0, providerB to 1)
        )

        assertThat(merged).hasSize(1)
        assertThat(merged.single().variants.map { it.providerId }).containsExactly(providerA, providerB).inOrder()
        assertThat(merged.single().selectedVariantId).isEqualTo(11L)
    }

    @Test
    fun `distinct channels are not merged`() {
        val channels = listOf(
            channel(providerA, variant(11, providerA, "DAZN 1")),
            channel(providerB, variant(21, providerB, "DAZN 2")),
            channel(providerA, variant(12, providerA, "M+ LALIGA")),
            channel(providerB, variant(22, providerB, "M+ LALIGA 2"))
        )
        val merged = CrossProviderChannelMerger.merge(channels, mapOf(providerA to 0, providerB to 1))
        assertThat(merged).hasSize(4)
    }

    @Test
    fun `index attaches backups from other categories`() {
        val inCategory = channel(providerA, variant(11, providerA, "ES - LA 1 FHD"))
        val otherCategoryB = channel(providerB, variant(21, providerB, "La 1 HD"))
        val unrelated = channel(providerB, variant(22, providerB, "Cuatro HD"))
        val priorities = mapOf(providerA to 0, providerB to 1)
        val index = CrossProviderChannelMerger.buildIndex(listOf(inCategory, otherCategoryB, unrelated))

        val merged = CrossProviderChannelMerger.merge(listOf(inCategory), priorities, index)

        assertThat(merged.single().variants.map { it.rawChannelId }).containsExactly(11L, 21L).inOrder()
        assertThat(merged.single().categoryId).isEqualTo(inCategory.categoryId)
    }

    @Test
    fun `attached backups keep the entry's own variant selected`() {
        val favoriteFromB = channel(providerB, variant(21, providerB, "La 1 HD"))
        val a = channel(providerA, variant(11, providerA, "ES - LA 1 FHD"))
        val index = CrossProviderChannelMerger.buildIndex(listOf(a, favoriteFromB))

        val result = CrossProviderChannelMerger.attachBackups(
            listOf(favoriteFromB),
            mapOf(providerA to 0, providerB to 1),
            index
        ).single()

        assertThat(result.id).isEqualTo(21L)
        assertThat(result.providerId).isEqualTo(providerB)
        assertThat(result.variants.map { it.rawChannelId }).containsExactly(21L, 11L).inOrder()
    }

    @Test
    fun `selecting a backup variant moves channel identity to that provider`() {
        val merged = CrossProviderChannelMerger.merge(
            listOf(
                channel(providerA, variant(11, providerA, "LA 1")),
                channel(providerB, variant(21, providerB, "LA 1 HD"))
            ),
            mapOf(providerA to 0, providerB to 1)
        ).single()

        val switched = merged.withSelectedVariant(21L)!!

        assertThat(switched.id).isEqualTo(21L)
        assertThat(switched.providerId).isEqualTo(providerB)
        assertThat(switched.logicalGroupId).isEqualTo("${providerB}_LA 1 HD")
        assertThat(switched.epgChannelId).isEqualTo("epg-2-21")
        assertThat(switched.streamUrl).isEqualTo("xtream://2/live/21?ext=m3u8")
        assertThat(switched.restrictedToProvider(providerA)?.variants?.map { it.rawChannelId }).containsExactly(11L)
        assertThat(switched.restrictedToProvider(providerC)).isNull()
    }
}
