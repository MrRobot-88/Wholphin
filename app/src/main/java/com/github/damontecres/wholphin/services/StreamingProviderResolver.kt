package com.github.damontecres.wholphin.services

import com.github.damontecres.wholphin.api.seerr.model.WatchProviderDetails
import com.github.damontecres.wholphin.api.seerr.model.WatchProviders
import com.github.damontecres.wholphin.data.model.JellyfinUserPreferences
import com.github.damontecres.wholphin.data.model.resolvedStreamingRegion
import com.github.damontecres.wholphin.data.model.streamingProviderIdSet

data class StreamingAvailability(
    val region: String,
    val link: String?,
    val allProviders: List<WatchProviderDetails>,
    val subscribedProviders: List<WatchProviderDetails>,
)

fun resolveStreamingAvailability(
    watchProviders: List<WatchProviders>?,
    preferences: JellyfinUserPreferences,
    fallbackRegion: String,
): StreamingAvailability? {
    val region = preferences.resolvedStreamingRegion(fallbackRegion)
    val regional =
        watchProviders
            .orEmpty()
            .firstOrNull { it.iso31661.equals(region, ignoreCase = true) }
            ?: return null

    val allProviders = normalizeProviders(regional.flatrate.orEmpty())
    val selectedIds = preferences.streamingProviderIdSet
    val subscribed =
        if (selectedIds.isEmpty()) {
            emptyList()
        } else {
            allProviders.filter { it.id in selectedIds }
        }

    return StreamingAvailability(
        region = region,
        link = regional.link,
        allProviders = allProviders,
        subscribedProviders = subscribed,
    )
}

fun mergeWatchProviderCatalog(
    movieProviders: List<WatchProviderDetails>,
    seriesProviders: List<WatchProviderDetails>,
): List<WatchProviderDetails> = normalizeProviders(movieProviders + seriesProviders)

private fun normalizeProviders(providers: List<WatchProviderDetails>): List<WatchProviderDetails> =
    providers
        .asSequence()
        .filter { it.id != null && it.id > 0 && !it.name.isNullOrBlank() }
        .groupBy { it.id!! }
        .map { (_, sameId) ->
            sameId.minWithOrNull(
                compareBy<WatchProviderDetails> { it.displayPriority ?: Int.MAX_VALUE }
                    .thenBy { it.name.orEmpty() },
            )!!
        }
        .sortedWith(
            compareBy<WatchProviderDetails> { it.displayPriority ?: Int.MAX_VALUE }
                .thenBy { it.name.orEmpty() },
        )
