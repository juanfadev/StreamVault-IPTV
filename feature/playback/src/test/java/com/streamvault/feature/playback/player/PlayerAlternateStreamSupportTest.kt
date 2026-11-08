package com.streamvault.feature.playback.player

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.LiveChannelVariant
import com.streamvault.domain.provider.ProviderInternalStreamKind
import com.streamvault.domain.provider.ProviderInternalStreamUrl
import org.junit.Test

class PlayerAlternateStreamSupportTest {
    @Test
    fun buildXtreamLiveTsFallbackUrl_convertsLiveHlsInternalUrlToTs() {
        val channel = Channel(id = 10, name = "Live", providerId = 7, streamId = 61351)
        val currentUrl = ProviderInternalStreamUrl.build(
            providerId = 7,
            kind = ProviderInternalStreamKind.LIVE,
            streamId = 61351,
            containerExtension = "m3u8"
        )

        val fallbackUrl = buildXtreamLiveTsFallbackUrl(
            channel = channel,
            currentStreamUrl = currentUrl,
            currentResolvedPlaybackUrl = "http://example.test/live/user/pass/61351.m3u8"
        )

        assertThat(fallbackUrl).isEqualTo(
            ProviderInternalStreamUrl.build(
                providerId = 7,
                kind = ProviderInternalStreamKind.LIVE,
                streamId = 61351,
                containerExtension = "ts"
            )
        )
    }

    @Test
    fun buildXtreamLiveTsFallbackUrl_usesChannelIdentifiersForResolvedHlsUrl() {
        val channel = Channel(id = 10, name = "Live", providerId = 7, streamId = 61351)

        val fallbackUrl = buildXtreamLiveTsFallbackUrl(
            channel = channel,
            currentStreamUrl = "http://example.test/live/user/pass/61351.m3u8",
            currentResolvedPlaybackUrl = "http://example.test/live/user/pass/61351.m3u8?token=abc"
        )

        assertThat(fallbackUrl).isEqualTo(
            ProviderInternalStreamUrl.build(
                providerId = 7,
                kind = ProviderInternalStreamKind.LIVE,
                streamId = 61351,
                containerExtension = "ts"
            )
        )
    }

    @Test
    fun buildXtreamLiveTsFallbackUrl_extractsStreamIdFromResolvedLivePath() {
        val channel = Channel(id = 10, name = "Live", providerId = 7, streamId = 0)

        val fallbackUrl = buildXtreamLiveTsFallbackUrl(
            channel = channel,
            currentStreamUrl = "https://example.test/live/user/pass/61351.m3u8",
            currentResolvedPlaybackUrl = "https://example.test/proxy/live/user/pass/61351.m3u8?token=abc"
        )

        assertThat(fallbackUrl).isEqualTo(
            ProviderInternalStreamUrl.build(
                providerId = 7,
                kind = ProviderInternalStreamKind.LIVE,
                streamId = 61351,
                containerExtension = "ts"
            )
        )
    }

    @Test
    fun buildXtreamLiveTsFallbackUrl_ignoresNonLiveInternalUrl() {
        val channel = Channel(id = 10, name = "Movie", providerId = 7, streamId = 61351)
        val currentUrl = ProviderInternalStreamUrl.build(
            providerId = 7,
            kind = ProviderInternalStreamKind.MOVIE,
            streamId = 61351,
            containerExtension = "m3u8"
        )

        assertThat(
            buildXtreamLiveTsFallbackUrl(
                channel = channel,
                currentStreamUrl = currentUrl,
                currentResolvedPlaybackUrl = "http://example.test/movie/user/pass/61351.m3u8"
            )
        ).isNull()
    }

    @Test
    fun buildXtreamLiveTsFallbackUrl_ignoresUppercaseNonLiveInternalUrl() {
        val channel = Channel(id = 10, name = "Movie", providerId = 7, streamId = 61351)

        assertThat(
            buildXtreamLiveTsFallbackUrl(
                channel = channel,
                currentStreamUrl = "xtream://7/MOVIE/61351?ext=m3u8",
                currentResolvedPlaybackUrl = "http://example.test/movie/user/pass/61351.m3u8"
            )
        ).isNull()
    }

    @Test
    fun buildXtreamLiveTsFallbackUrl_ignoresAlreadyTsLiveUrl() {
        val channel = Channel(id = 10, name = "Live", providerId = 7, streamId = 61351)
        val currentUrl = ProviderInternalStreamUrl.build(
            providerId = 7,
            kind = ProviderInternalStreamKind.LIVE,
            streamId = 61351,
            containerExtension = "ts"
        )

        assertThat(
            buildXtreamLiveTsFallbackUrl(
                channel = channel,
                currentStreamUrl = currentUrl,
                currentResolvedPlaybackUrl = "http://example.test/live/user/pass/61351.ts"
            )
        ).isNull()
    }

    @Test
    fun selectXtreamLiveTsFallbackUrl_skipsTriedFallback() {
        val channel = Channel(id = 10, name = "Live", providerId = 7, streamId = 61351)
        val fallbackUrl = ProviderInternalStreamUrl.build(
            providerId = 7,
            kind = ProviderInternalStreamKind.LIVE,
            streamId = 61351,
            containerExtension = "ts"
        )

        assertThat(
            selectXtreamLiveTsFallbackUrl(
                channel = channel,
                currentStreamUrl = "xtream://7/live/61351?ext=m3u8",
                currentResolvedPlaybackUrl = "http://example.test/live/user/pass/61351.m3u8",
                triedAlternativeStreams = setOf(fallbackUrl),
                failedStreamsThisSession = emptyMap()
            )
        ).isNull()
    }

    @Test
    fun selectXtreamLiveTsFallbackUrl_skipsFailedFallback() {
        val channel = Channel(id = 10, name = "Live", providerId = 7, streamId = 61351)
        val fallbackUrl = ProviderInternalStreamUrl.build(
            providerId = 7,
            kind = ProviderInternalStreamKind.LIVE,
            streamId = 61351,
            containerExtension = "ts"
        )

        assertThat(
            selectXtreamLiveTsFallbackUrl(
                channel = channel,
                currentStreamUrl = "xtream://7/live/61351?ext=m3u8",
                currentResolvedPlaybackUrl = "http://example.test/live/user/pass/61351.m3u8",
                triedAlternativeStreams = emptySet(),
                failedStreamsThisSession = mapOf(fallbackUrl to 1)
            )
        ).isNull()
    }

    @Test
    fun selectNextLiveRecoveryCandidate_prefersTsFallbackBeforeVariantsWhenRequested() {
        val currentUrl = ProviderInternalStreamUrl.build(
            providerId = 7,
            kind = ProviderInternalStreamKind.LIVE,
            streamId = 61351,
            containerExtension = "m3u8"
        )
        val variantUrl = ProviderInternalStreamUrl.build(
            providerId = 7,
            kind = ProviderInternalStreamKind.LIVE,
            streamId = 61352,
            containerExtension = "m3u8"
        )
        val channel = Channel(
            id = 10,
            name = "Live",
            providerId = 7,
            streamId = 61351,
            streamUrl = currentUrl,
            selectedVariantId = 10,
            variants = listOf(
                LiveChannelVariant(
                    rawChannelId = 11,
                    logicalGroupId = "live",
                    providerId = 7,
                    originalName = "Live HD",
                    canonicalName = "Live",
                    streamUrl = variantUrl,
                    streamId = 61352
                )
            )
        )

        val candidate = selectNextLiveRecoveryCandidate(
            channel = channel,
            currentVariantId = 10,
            currentStreamUrl = currentUrl,
            currentResolvedPlaybackUrl = "http://example.test/live/user/pass/61351.m3u8",
            triedAlternativeStreams = emptySet(),
            failedStreamsThisSession = emptyMap(),
            preferXtreamTsFallback = true
        )

        assertThat(candidate?.kind).isEqualTo(LiveRecoveryCandidateKind.XTREAM_TS_FALLBACK)
        assertThat(candidate?.url).isEqualTo(
            ProviderInternalStreamUrl.build(
                providerId = 7,
                kind = ProviderInternalStreamKind.LIVE,
                streamId = 61351,
                containerExtension = "ts"
            )
        )
    }

    @Test
    fun selectNextLiveRecoveryCandidate_skipsTsFallbackWhenDisallowed() {
        val currentUrl = ProviderInternalStreamUrl.build(
            providerId = 7,
            kind = ProviderInternalStreamKind.LIVE,
            streamId = 61351,
            containerExtension = "m3u8"
        )
        val channel = Channel(
            id = 10,
            name = "Live",
            providerId = 7,
            streamId = 61351,
            streamUrl = currentUrl,
            selectedVariantId = 10
        )

        val candidate = selectNextLiveRecoveryCandidate(
            channel = channel,
            currentVariantId = 10,
            currentStreamUrl = currentUrl,
            currentResolvedPlaybackUrl = "http://example.test/live/user/pass/61351.m3u8",
            triedAlternativeStreams = emptySet(),
            failedStreamsThisSession = emptyMap(),
            preferXtreamTsFallback = true,
            allowXtreamTsFallback = false
        )

        assertThat(candidate).isNull()
    }

    @Test
    fun selectNextLiveRecoveryCandidate_keepsVariantPreferenceByDefault() {
        val currentUrl = ProviderInternalStreamUrl.build(
            providerId = 7,
            kind = ProviderInternalStreamKind.LIVE,
            streamId = 61351,
            containerExtension = "m3u8"
        )
        val variantUrl = ProviderInternalStreamUrl.build(
            providerId = 7,
            kind = ProviderInternalStreamKind.LIVE,
            streamId = 61352,
            containerExtension = "m3u8"
        )
        val channel = Channel(
            id = 10,
            name = "Live",
            providerId = 7,
            streamId = 61351,
            streamUrl = currentUrl,
            selectedVariantId = 10,
            variants = listOf(
                LiveChannelVariant(
                    rawChannelId = 11,
                    logicalGroupId = "live",
                    providerId = 7,
                    originalName = "Live HD",
                    canonicalName = "Live",
                    streamUrl = variantUrl,
                    streamId = 61352
                )
            )
        )

        val candidate = selectNextLiveRecoveryCandidate(
            channel = channel,
            currentVariantId = 10,
            currentStreamUrl = currentUrl,
            currentResolvedPlaybackUrl = "http://example.test/live/user/pass/61351.m3u8",
            triedAlternativeStreams = emptySet(),
            failedStreamsThisSession = emptyMap(),
            preferXtreamTsFallback = false
        )

        assertThat(candidate?.kind).isEqualTo(LiveRecoveryCandidateKind.VARIANT)
        assertThat(candidate?.url).isEqualTo(variantUrl)
    }

    private fun crossProviderVariant(rawId: Long, providerId: Long, name: String = "LA 1 HD") = LiveChannelVariant(
        rawChannelId = rawId,
        logicalGroupId = "${providerId}_la1",
        providerId = providerId,
        originalName = name,
        canonicalName = "LA 1",
        streamUrl = "xtream://$providerId/live/$rawId?ext=m3u8",
        streamId = rawId
    )

    /** Merged combined-source channel: provider 1 (two variants), provider 2, provider 3. */
    private val mergedVariants = listOf(
        crossProviderVariant(11, 1),
        crossProviderVariant(12, 1, "LA 1 SD"),
        crossProviderVariant(21, 2),
        crossProviderVariant(31, 3)
    )

    @Test
    fun selectNextLiveVariant_movesToNextProviderAfterFailure() {
        val next = selectNextLiveVariant(
            variants = mergedVariants,
            currentVariantId = 11,
            currentStreamUrl = mergedVariants[0].streamUrl,
            triedAlternativeStreams = setOf(mergedVariants[0].streamUrl),
            failedStreamsThisSession = mapOf(mergedVariants[0].streamUrl to 1)
        )

        assertThat(next?.rawChannelId).isEqualTo(21)
        assertThat(next?.providerId).isEqualTo(2)
    }

    @Test
    fun selectNextLiveVariant_rotatesThroughProvidersBeforeRetryingSameProvider() {
        val visited = mutableListOf(11L)
        val tried = mutableSetOf(mergedVariants[0].streamUrl)
        var current = mergedVariants[0]
        repeat(3) {
            val next = selectNextLiveVariant(
                variants = mergedVariants,
                currentVariantId = current.rawChannelId,
                currentStreamUrl = current.streamUrl,
                triedAlternativeStreams = tried,
                failedStreamsThisSession = tried.associateWith { 1 }
            )!!
            visited += next.rawChannelId
            tried += next.streamUrl
            current = next
        }

        assertThat(visited).containsExactly(11L, 21L, 31L, 12L).inOrder()
        assertThat(
            selectNextLiveVariant(
                variants = mergedVariants,
                currentVariantId = current.rawChannelId,
                currentStreamUrl = current.streamUrl,
                triedAlternativeStreams = tried,
                failedStreamsThisSession = tried.associateWith { 1 }
            )
        ).isNull()
    }

    @Test
    fun selectNextLiveVariant_wrapsToHigherPriorityProviderWhenStartedOnBackup() {
        val next = selectNextLiveVariant(
            variants = mergedVariants,
            currentVariantId = 31,
            currentStreamUrl = mergedVariants[3].streamUrl,
            triedAlternativeStreams = setOf(mergedVariants[3].streamUrl),
            failedStreamsThisSession = emptyMap()
        )

        assertThat(next?.rawChannelId).isEqualTo(11)
    }

    @Test
    fun orderLiveVariantsForRecovery_keepsSingleProviderOrder() {
        val singleProvider = listOf(crossProviderVariant(11, 1), crossProviderVariant(12, 1), crossProviderVariant(13, 1))

        assertThat(orderLiveVariantsForRecovery(singleProvider, currentVariantId = 12)).isEqualTo(singleProvider)
    }

    @Test
    fun selectNextLiveRecoveryCandidate_returnsBackupProviderVariantForMergedChannel() {
        val channel = Channel(
            id = 11,
            name = "LA 1",
            providerId = 1,
            streamUrl = mergedVariants[0].streamUrl,
            selectedVariantId = 11,
            variants = mergedVariants
        ).withSelectedVariant(11)!!

        val candidate = selectNextLiveRecoveryCandidate(
            channel = channel,
            currentVariantId = 11,
            currentStreamUrl = channel.streamUrl,
            currentResolvedPlaybackUrl = "http://provider-one.test/live/u/p/11.ts",
            triedAlternativeStreams = setOf(channel.streamUrl),
            failedStreamsThisSession = mapOf(channel.streamUrl to 1),
            preferXtreamTsFallback = false
        )

        assertThat(candidate?.kind).isEqualTo(LiveRecoveryCandidateKind.VARIANT)
        assertThat(candidate?.variant?.providerId).isEqualTo(2)
        val switched = channel.withSelectedVariant(candidate!!.variant!!.rawChannelId)!!
        assertThat(switched.providerId).isEqualTo(2)
        assertThat(switched.streamUrl).isEqualTo("xtream://2/live/21?ext=m3u8")
    }
}
