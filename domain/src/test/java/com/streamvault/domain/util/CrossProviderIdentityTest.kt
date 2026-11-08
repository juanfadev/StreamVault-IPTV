package com.streamvault.domain.util

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.CombinedCategoryIdentity
import org.junit.Test

class CrossProviderIdentityTest {

    private fun key(name: String) = ChannelNormalizer.crossProviderIdentity(name).nameKey

    @Test
    fun `same channel from different providers shares one name key`() {
        val names = listOf(
            "ES - LA 1 FHD",
            "ES| La 1 HD",
            "LA 1 HEVC",
            "LA 1 ᴿᴬᵂ ᴴᴰ ◉",
            "ES: LA1 1080p",
            "|ES| La 1 H.265",
            "[ES] LA 1 (Backup)",
            "La 1 ʜᴅ",
            "Lá 1 SD",
            "LA 1 50 fps",
            "LA 1ᴴᴰ"
        )
        assertThat(names.map(::key).toSet()).containsExactly("la1")
    }

    @Test
    fun `different channels never share a name key`() {
        val pairs = listOf(
            "LA 1" to "LA 2",
            "ES - LA 1 FHD" to "ES - LA 2 FHD",
            "DAZN 1" to "DAZN 2",
            "DAZN 1 HD" to "DAZN LALIGA HD",
            "M+ LALIGA" to "M+ LALIGA 2",
            "M+ LALIGA FHD" to "M LALIGA FHD",
            "LA 1" to "LA 11",
            "LA 1" to "LA 1 CATALUNYA",
            "Antena 3" to "Atreseries"
        )
        pairs.forEach { (first, second) ->
            assertThat(key(first)).isNotEqualTo(key(second))
        }
    }

    @Test
    fun `explicit country prefixes are captured as region`() {
        assertThat(ChannelNormalizer.crossProviderIdentity("ES - LA 1 FHD").region).isEqualTo("ES")
        assertThat(ChannelNormalizer.crossProviderIdentity("ES| La 1 HD").region).isEqualTo("ES")
        assertThat(ChannelNormalizer.crossProviderIdentity("SPAIN: La 1").region).isEqualTo("ES")
        assertThat(ChannelNormalizer.crossProviderIdentity("UK: Sky Sports 1").region).isEqualTo("UK")
        assertThat(ChannelNormalizer.crossProviderIdentity("LA 1 HEVC").region).isNull()
        assertThat(ChannelNormalizer.crossProviderIdentity("VIP: LA 1").region).isNull()
    }

    @Test
    fun `country prefixed names do not lose words that look like codes`() {
        assertThat(key("LA 1")).isEqualTo("la1")
        assertThat(key("TV3")).isEqualTo("tv3")
        assertThat(key("DAZN: LALIGA")).isEqualTo("daznlaliga")
    }

    @Test
    fun `category keys merge prefixed spellings and keep distinct categories apart`() {
        val merged = listOf("ES | DEPORTES", "ES DEPORTES", "ES: Deportes", "es-deportes", "ES ● DEPORTES")
        assertThat(merged.map(CombinedCategoryIdentity::keyForName).toSet()).containsExactly("es deportes")
        assertThat(merged.map(CombinedCategoryIdentity::idForName).toSet()).hasSize(1)

        assertThat(CombinedCategoryIdentity.keyForName("ES | DEPORTES"))
            .isNotEqualTo(CombinedCategoryIdentity.keyForName("ES | DEPORTES 2"))
        assertThat(CombinedCategoryIdentity.keyForName("ES | 4K"))
            .isNotEqualTo(CombinedCategoryIdentity.keyForName("ES | HD"))
        assertThat(CombinedCategoryIdentity.keyForName("UK | SPORTS"))
            .isNotEqualTo(CombinedCategoryIdentity.keyForName("US | SPORTS"))
    }

    @Test
    fun `grouping joins region-less names to the regional group and splits conflicting regions`() {
        val groups = CrossProviderGrouping.group(
            listOf("ES - Sky Sports", "Sky Sports HD", "UK: Sky Sports", "ES| Sky Sports FHD")
        ) { ChannelNormalizer.crossProviderIdentity(it) }

        assertThat(groups.map { it.key }).containsExactly("skysports@ES", "skysports@UK").inOrder()
        assertThat(groups[0].items).containsExactly("ES - Sky Sports", "Sky Sports HD", "ES| Sky Sports FHD").inOrder()
        assertThat(groups[1].items).containsExactly("UK: Sky Sports")
    }

    @Test
    fun `category grouping merges deportes across providers`() {
        val groups = CrossProviderGrouping.group(listOf("ES | DEPORTES", "DEPORTES", "ES DEPORTES", "ES | CINE")) {
            ChannelNormalizer.crossProviderCategoryIdentity(it)
        }
        assertThat(groups.map { it.items }).containsExactly(
            listOf("ES | DEPORTES", "DEPORTES", "ES DEPORTES"),
            listOf("ES | CINE")
        ).inOrder()
    }
}
