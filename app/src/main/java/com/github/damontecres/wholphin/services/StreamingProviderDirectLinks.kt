package com.github.damontecres.wholphin.services

import java.util.Locale

data class StreamingProviderDirectLinks(
    val providerName: String,
    val providerIconUrl: String? = null,
    val androidTv: String? = null,
    val fireTv: String? = null,
    val android: String? = null,
    val rawWeb: String? = null,
    val standardWeb: String? = null,
    val presentationType: String = "",
    val monetizationType: String = "",
) {
    fun preferredAppLinks(isFireTv: Boolean): List<String> =
        if (isFireTv) {
            listOfNotNull(fireTv, androidTv, android, rawWeb).distinct()
        } else {
            listOfNotNull(androidTv, android, fireTv, rawWeb).distinct()
        }
}

data class StreamingProviderLinks(
    val region: String,
    val linksByProvider: Map<String, StreamingProviderDirectLinks>,
) {
    fun forProvider(providerName: String?): StreamingProviderDirectLinks? =
        linksByProvider[canonicalStreamingProviderName(providerName)]
}

internal fun canonicalStreamingProviderName(value: String?): String {
    val normalized =
        value
            .orEmpty()
            .lowercase(Locale.ROOT)
            .replace("+", " plus ")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")

    return when {
        normalized.startsWith("netflix") -> "netflix"
        "prime video" in normalized || "amazon video" in normalized -> "primevideo"
        normalized == "disney" || normalized.startsWith("disney plus") -> "disneyplus"
        normalized == "max" || normalized.startsWith("hbo max") -> "max"
        normalized.startsWith("viaplay") -> "viaplay"
        normalized == "svt" || normalized.startsWith("svt play") -> "svtplay"
        normalized.startsWith("apple tv") -> "appletvplus"
        normalized.startsWith("skyshowtime") -> "skyshowtime"
        normalized.startsWith("paramount plus") || normalized == "paramount" -> "paramountplus"
        else -> normalized
    }
}

internal fun normalizeStreamingRegion(region: String): String =
    region
        .trim()
        .uppercase(Locale.ROOT)
        .takeIf { it.length == 2 && it.all(Char::isLetter) }
        ?: "SE"
