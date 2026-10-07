package com.github.damontecres.wholphin.data.model

val JellyfinUserPreferences.streamingProviderIdSet: Set<Int>
    get() =
        streamingProviderIds
            .split(',')
            .asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .mapNotNull(String::toIntOrNull)
            .filter { it > 0 }
            .toSortedSet()

fun JellyfinUserPreferences.withStreamingProviderIds(providerIds: Collection<Int>): JellyfinUserPreferences =
    copy(
        streamingProviderIds =
            providerIds
                .asSequence()
                .filter { it > 0 }
                .distinct()
                .sorted()
                .joinToString(","),
    )

fun JellyfinUserPreferences.resolvedStreamingRegion(fallbackRegion: String): String =
    streamingRegion
        .trim()
        .uppercase()
        .takeIf { it.length == 2 }
        ?: fallbackRegion.trim().uppercase().takeIf { it.length == 2 }
        ?: "SE"
