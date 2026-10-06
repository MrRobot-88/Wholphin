package com.github.damontecres.wholphin.services

import com.github.damontecres.wholphin.api.seerr.model.WatchProviderDetails
import com.github.damontecres.wholphin.api.seerr.model.WatchProviders
import com.github.damontecres.wholphin.data.model.JellyfinUserPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamingProviderResolverTest {
    @Test
    fun resolvesRequestedRegionAndOnlySubscribedFlatrateProviders() {
        val prefs = JellyfinUserPreferences(streamingRegion = "SE", streamingProviderIds = "8,337")
        val result =
            resolveStreamingAvailability(
                watchProviders =
                    listOf(
                        region("DK", provider(8, "Netflix", 5)),
                        region(
                            "SE",
                            provider(337, "Disney Plus", 3),
                            provider(9, "Prime Video", 2),
                            provider(8, "Netflix", 1),
                        ),
                    ),
                preferences = prefs,
                fallbackRegion = "DK",
            )!!

        assertEquals("SE", result.region)
        assertEquals(listOf(8, 9, 337), result.allProviders.map { it.id })
        assertEquals(listOf(8, 337), result.subscribedProviders.map { it.id })
        assertEquals("https://example.test/SE", result.link)
    }

    @Test
    fun blankPreferenceUsesFallbackRegion() {
        val result =
            resolveStreamingAvailability(
                watchProviders = listOf(region("SE", provider(8, "Netflix", 1)), region("DK", provider(9, "Prime", 1))),
                preferences = JellyfinUserPreferences(streamingProviderIds = "9"),
                fallbackRegion = "dk",
            )!!

        assertEquals("DK", result.region)
        assertEquals(listOf(9), result.subscribedProviders.map { it.id })
    }

    @Test
    fun noConfiguredSubscriptionsKeepsAvailabilityButReturnsNoSubscribedProviders() {
        val result =
            resolveStreamingAvailability(
                watchProviders = listOf(region("SE", provider(8, "Netflix", 1))),
                preferences = JellyfinUserPreferences(streamingRegion = "SE"),
                fallbackRegion = "DK",
            )!!

        assertEquals(listOf(8), result.allProviders.map { it.id })
        assertEquals(emptyList<Int>(), result.subscribedProviders.mapNotNull { it.id })
    }

    @Test
    fun missingRegionReturnsNullInsteadOfUsingWrongCountry() {
        assertNull(
            resolveStreamingAvailability(
                watchProviders = listOf(region("US", provider(8, "Netflix", 1))),
                preferences = JellyfinUserPreferences(streamingRegion = "SE", streamingProviderIds = "8"),
                fallbackRegion = "SE",
            ),
        )
    }

    @Test
    fun catalogMergeDeduplicatesByIdAndSortsByPriority() {
        val result =
            mergeWatchProviderCatalog(
                movieProviders = listOf(provider(8, "Netflix", 8), provider(337, "Disney Plus", 3)),
                seriesProviders = listOf(provider(8, "Netflix", 1), provider(9, "Prime Video", 2)),
            )

        assertEquals(listOf(8, 9, 337), result.map { it.id })
        assertEquals(1, result.first().displayPriority)
    }

    private fun region(code: String, vararg providers: WatchProviderDetails) =
        WatchProviders(
            iso31661 = code,
            link = "https://example.test/$code",
            flatrate = providers.toList(),
        )

    private fun provider(id: Int, name: String, priority: Int) =
        WatchProviderDetails(
            id = id,
            name = name,
            displayPriority = priority,
            logoPath = "/$id.png",
        )
}
