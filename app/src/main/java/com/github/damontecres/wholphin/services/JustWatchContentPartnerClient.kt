package com.github.damontecres.wholphin.services

import com.github.damontecres.wholphin.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

@Serializable
private data class JustWatchOfferUrls(
    @SerialName("deeplink_android") val android: String? = null,
    @SerialName("deeplink_android_tv") val androidTv: String? = null,
    @SerialName("deeplink_fire_tv") val fireTv: String? = null,
    @SerialName("raw_web") val rawWeb: String? = null,
    @SerialName("standard_web") val standardWeb: String? = null,
)

@Serializable
private data class JustWatchOffer(
    @SerialName("monetization_type") val monetizationType: String,
    @SerialName("presentation_type") val presentationType: String,
    @SerialName("provider_id") val providerId: Long,
    val urls: JustWatchOfferUrls,
)

@Serializable
private data class JustWatchOffersResponse(
    @SerialName("full_path") val fullPath: String? = null,
    val offers: List<JustWatchOffer> = emptyList(),
)

@Serializable
private data class JustWatchProvider(
    val id: Long,
    @SerialName("clear_name") val clearName: String,
    @SerialName("icon_url") val iconUrl: String? = null,
)

data class StreamingProviderDirectLinks(
    val providerName: String,
    val providerIconUrl: String?,
    val androidTv: String?,
    val fireTv: String?,
    val android: String?,
    val rawWeb: String?,
    val standardWeb: String?,
    val presentationType: String,
    val monetizationType: String,
) {
    fun preferredAppLinks(isFireTv: Boolean): List<String> =
        if (isFireTv) {
            listOfNotNull(fireTv, androidTv, android, rawWeb).distinct()
        } else {
            listOfNotNull(androidTv, android, fireTv, rawWeb).distinct()
        }
}

data class JustWatchStreamingLinks(
    val region: String,
    val locale: String,
    val attributionUrl: String?,
    val linksByProvider: Map<String, StreamingProviderDirectLinks>,
) {
    fun forProvider(providerName: String?): StreamingProviderDirectLinks? =
        linksByProvider[canonicalStreamingProviderName(providerName)]
}

object JustWatchContentPartnerClient {
    private const val BASE_URL = "https://apis.justwatch.com/contentpartner/v2/content"

    private val json = Json { ignoreUnknownKeys = true }
    private val client =
        OkHttpClient
            .Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(7, TimeUnit.SECONDS)
            .callTimeout(9, TimeUnit.SECONDS)
            .build()

    private val providerCache = ConcurrentHashMap<String, List<JustWatchProvider>>()
    private val titleCache = ConcurrentHashMap<String, JustWatchStreamingLinks>()

    private val token: String
        get() = BuildConfig.JUSTWATCH_PARTNER_TOKEN.trim()

    val isConfigured: Boolean
        get() = token.isNotBlank()

    suspend fun getStreamingLinks(
        objectType: String,
        tmdbId: Int,
        region: String,
    ): JustWatchStreamingLinks? {
        if (!isConfigured || tmdbId <= 0) return null

        val normalizedObjectType =
            when (objectType.lowercase(Locale.ROOT)) {
                "movie" -> "movie"
                "show", "series", "tv" -> "show"
                else -> return null
            }
        val normalizedRegion = normalizeStreamingRegion(region)
        val locale = justWatchLocaleForRegion(normalizedRegion)
        val cacheKey = "$locale:$normalizedObjectType:$tmdbId"
        titleCache[cacheKey]?.let { return it }

        return try {
            val response = fetchOffers(normalizedObjectType, tmdbId, locale) ?: return null
            val providers = getProviders(locale).associateBy { it.id }
            val links =
                response.offers
                    .asSequence()
                    .filter { it.monetizationType.lowercase(Locale.ROOT) in STREAMING_MONETIZATION_TYPES }
                    .mapNotNull { offer ->
                        val provider = providers[offer.providerId] ?: return@mapNotNull null
                        val direct =
                            StreamingProviderDirectLinks(
                                providerName = provider.clearName,
                                providerIconUrl = provider.iconUrl,
                                androidTv = offer.urls.androidTv,
                                fireTv = offer.urls.fireTv,
                                android = offer.urls.android,
                                rawWeb = offer.urls.rawWeb,
                                standardWeb = offer.urls.standardWeb,
                                presentationType = offer.presentationType,
                                monetizationType = offer.monetizationType,
                            )
                        canonicalStreamingProviderName(provider.clearName) to direct
                    }.groupBy({ it.first }, { it.second })
                    .mapValues { (_, offers) -> offers.maxWithOrNull(DIRECT_LINK_COMPARATOR)!! }

            val result =
                JustWatchStreamingLinks(
                    region = normalizedRegion,
                    locale = locale,
                    attributionUrl =
                        response.fullPath
                            ?.takeIf { it.startsWith('/') }
                            ?.let { "https://www.justwatch.com$it" },
                    linksByProvider = links,
                )
            titleCache[cacheKey] = result
            result
        } catch (t: Throwable) {
            Timber.w(t, "JustWatch Content Partner lookup failed")
            null
        }
    }

    private suspend fun fetchOffers(
        objectType: String,
        tmdbId: Int,
        locale: String,
    ): JustWatchOffersResponse? {
        val url =
            "$BASE_URL/offers/object_type/$objectType/id_type/tmdb/id/$tmdbId/locale/$locale"
                .toHttpUrl()
                .newBuilder()
                .addQueryParameter("token", token)
                .build()
        return getJson(url)
    }

    private suspend fun getProviders(locale: String): List<JustWatchProvider> {
        providerCache[locale]?.let { return it }
        val url =
            "$BASE_URL/providers/all/locale/$locale"
                .toHttpUrl()
                .newBuilder()
                .addQueryParameter("token", token)
                .build()
        val providers = getJson<List<JustWatchProvider>>(url).orEmpty()
        if (providers.isNotEmpty()) providerCache[locale] = providers
        return providers
    }

    private suspend inline fun <reified T> getJson(url: HttpUrl): T? =
        withContext(Dispatchers.IO) {
            val request =
                Request
                    .Builder()
                    .url(url)
                    .header("Accept", "application/json")
                    .get()
                    .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.w("JustWatch Content Partner HTTP %d", response.code)
                    return@withContext null
                }
                val body = response.body.string()
                json.decodeFromString<T>(body)
            }
        }

    private val DIRECT_LINK_COMPARATOR =
        compareBy<StreamingProviderDirectLinks>(
            { monetizationScore(it.monetizationType) },
            { presentationScore(it.presentationType) },
            { appLinkScore(it) },
        )

    private fun monetizationScore(value: String): Int =
        when (value.lowercase(Locale.ROOT)) {
            "flatrate" -> 3
            "free" -> 2
            "ads" -> 1
            else -> 0
        }

    private fun presentationScore(value: String): Int =
        when (value.lowercase(Locale.ROOT)) {
            "4k" -> 3
            "hd" -> 2
            "sd" -> 1
            else -> 0
        }

    private fun appLinkScore(value: StreamingProviderDirectLinks): Int =
        when {
            !value.androidTv.isNullOrBlank() || !value.fireTv.isNullOrBlank() -> 3
            !value.android.isNullOrBlank() -> 2
            !value.rawWeb.isNullOrBlank() -> 1
            else -> 0
        }

    private val STREAMING_MONETIZATION_TYPES = setOf("flatrate", "free", "ads")
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
        normalized == "netflix" -> "netflix"
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
        .takeIf { it.length == 2 }
        ?: "SE"

private val JUSTWATCH_LOCALES_BY_REGION =
    mapOf(
        "US" to "en_US", "DE" to "de_DE", "BR" to "pt_BR", "AU" to "en_AU",
        "NZ" to "en_NZ", "CA" to "en_CA", "GB" to "en_GB", "ZA" to "en_ZA",
        "IE" to "en_IE", "MX" to "es_MX", "JP" to "ja_JP", "NL" to "en_NL",
        "LT" to "en_LT", "BE" to "fr_BE", "PE" to "es_PE", "SE" to "en_SE",
        "TH" to "en_TH", "PT" to "pt_PT", "CZ" to "cs_CZ", "NO" to "en_NO",
        "RU" to "ru_RU", "EE" to "en_EE", "LV" to "en_LV", "HK" to "zh_HK",
        "TW" to "zh_TW", "BG" to "bg_BG", "HN" to "es_HN", "IS" to "is_IS",
        "SK" to "sk_SK", "HR" to "hr_HR", "DZ" to "ar_DZ", "AG" to "en_AG",
        "BS" to "en_BS", "BH" to "ar_BH", "BB" to "en_BB", "BM" to "en_BM",
        "CV" to "pt_CV", "CU" to "es_CU", "DO" to "es_DO", "SV" to "es_SV",
        "GQ" to "es_GQ", "FJ" to "en_FJ", "GF" to "fr_GF", "PF" to "fr_PF",
        "GH" to "en_GH", "GI" to "en_GI", "GG" to "en_GG", "CI" to "fr_CI",
        "JM" to "en_JM", "JO" to "ar_JO", "KE" to "en_KE", "KW" to "ar_KW",
        "LY" to "ar_LY", "LI" to "de_LI", "AL" to "sq_AL", "AD" to "ca_AD",
        "BA" to "bs_BA", "IQ" to "ar_IQ", "IL" to "he_IL", "XK" to "sq_XK",
        "IN" to "en_IN", "CH" to "de_CH", "AT" to "de_AT", "MY" to "en_MY",
        "ID" to "en_ID", "PH" to "en_PH", "SG" to "en_SG", "PL" to "pl_PL",
        "FI" to "fi_FI", "HU" to "hu_HU", "GR" to "el_GR", "TR" to "tr_TR",
        "CO" to "es_CO", "VE" to "es_VE", "DK" to "en_DK", "RO" to "ro_RO",
        "AR" to "es_AR", "CL" to "es_CL", "EC" to "es_EC", "ES" to "es_ES",
        "FR" to "fr_FR", "KR" to "ko_KR", "IT" to "it_IT", "CR" to "es_CR",
        "GT" to "es_GT", "BO" to "es_BO", "PY" to "es_PY", "AE" to "ar_AE",
        "SA" to "ar_SA", "EG" to "ar_EG", "MU" to "fr_MU", "MD" to "ro_MD",
        "MC" to "fr_MC", "MA" to "ar_MA", "MZ" to "pt_MZ", "NE" to "fr_NE",
        "NG" to "en_NG", "OM" to "ar_OM", "PK" to "ur_PK", "PA" to "es_PA",
        "QA" to "ar_QA", "SM" to "it_SM", "SN" to "fr_SN", "SC" to "fr_SC",
        "TZ" to "sw_TZ", "TT" to "en_TT", "TN" to "ar_TN", "TC" to "en_TC",
        "UG" to "en_UG", "UY" to "es_UY", "YE" to "ar_YE", "ZM" to "en_ZM",
        "LB" to "ar_LB", "MK" to "mk_MK", "MT" to "mt_MT", "PS" to "ar_PS",
        "RS" to "sr_RS", "SI" to "sl_SI", "VA" to "it_VA",
    )

internal fun justWatchLocaleForRegion(region: String): String {
    val country = normalizeStreamingRegion(region)
    return JUSTWATCH_LOCALES_BY_REGION[country] ?: "en_SE"
}