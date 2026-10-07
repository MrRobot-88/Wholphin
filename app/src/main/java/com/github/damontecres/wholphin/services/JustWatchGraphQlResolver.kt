package com.github.damontecres.wholphin.services

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Lightweight, key-free JustWatch GraphQL resolver.
 *
 * It is deliberately demand-driven: callers invoke it only on a media detail page.
 * Nothing is prefetched from Home or while scrolling poster rows.
 */
object JustWatchGraphQlResolver {
    private const val ENDPOINT = "https://apis.justwatch.com/graphql"
    private const val SEARCH_RESULT_LIMIT = 5
    private const val MAX_CACHE_ENTRIES = 128
    private const val POSITIVE_TTL_MS = 12L * 60 * 60 * 1_000
    private const val MISS_TTL_MS = 30L * 60 * 1_000
    private val allowedMonetization = setOf("FLATRATE", "FREE", "ADS")
    private val supportedProviders =
        setOf(
            "netflix",
            "primevideo",
            "disneyplus",
            "max",
            "viaplay",
            "svtplay",
            "appletvplus",
            "skyshowtime",
            "paramountplus",
        )

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val mediaType = "application/json; charset=utf-8".toMediaType()
    private val networkGate = Semaphore(2)
    private val client =
        OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .callTimeout(3, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()

    private data class CacheEntry(
        val value: StreamingProviderLinks?,
        val expiresAt: Long,
    )

    private val cache =
        object : LinkedHashMap<String, CacheEntry>(MAX_CACHE_ENTRIES, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, CacheEntry>?,
            ): Boolean = size > MAX_CACHE_ENTRIES
        }

    suspend fun getStreamingLinks(
        objectType: String,
        tmdbId: Int,
        title: String,
        region: String,
    ): StreamingProviderLinks? {
        if (tmdbId <= 0 || title.isBlank()) return null
        val graphObjectType =
            when (objectType.lowercase(Locale.ROOT)) {
                "movie" -> "MOVIE"
                "show", "series", "tv" -> "SHOW"
                else -> return null
            }
        val country = normalizeStreamingRegion(region)
        val preferredPlatform = if (isAmazonFireTv()) "FIRE_TV" else "ANDROID_TV"
        val cacheKey = "$country:$graphObjectType:$tmdbId:$preferredPlatform"
        cached(cacheKey)?.let { return it.value }

        return try {
            networkGate.withPermit {
                val preferred = fetch(graphObjectType, tmdbId, title, country, preferredPlatform)
                val result =
                    if (preferred is FetchResult.Error && preferredPlatform == "FIRE_TV") {
                        // Keep Fire TV useful if JustWatch temporarily rejects FIRE_TV for a title.
                        fetch(graphObjectType, tmdbId, title, country, "ANDROID_TV")
                    } else {
                        preferred
                    }
                when (result) {
                    is FetchResult.Success -> {
                        putCache(cacheKey, result.links, POSITIVE_TTL_MS)
                        result.links
                    }
                    FetchResult.Miss -> {
                        putCache(cacheKey, null, MISS_TTL_MS)
                        null
                    }
                    is FetchResult.Error -> {
                        Timber.w("JustWatch GraphQL lookup failed: %s", result.message)
                        null
                    }
                }
            }
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            Timber.w(ex, "JustWatch GraphQL lookup failed")
            null
        }
    }

    private sealed interface FetchResult {
        data class Success(val links: StreamingProviderLinks) : FetchResult
        data object Miss : FetchResult
        data class Error(val message: String) : FetchResult
    }

    private suspend fun fetch(
        objectType: String,
        tmdbId: Int,
        title: String,
        country: String,
        platform: String,
    ): FetchResult = withContext(Dispatchers.IO) {
        val requestBody =
            GraphQlRequest(
                query = searchQuery(objectType, platform),
                variables = GraphQlVariables(title, country, "en", SEARCH_RESULT_LIMIT),
            )
        val request =
            Request.Builder()
                .url(ENDPOINT)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android TV) Cosmofin")
                .header("Accept", "application/json")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Origin", "https://www.justwatch.com")
                .header("Referer", "https://www.justwatch.com/")
                .post(json.encodeToString(requestBody).toRequestBody(mediaType))
                .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                return@withContext FetchResult.Error("HTTP ${response.code}")
            }
            val decoded =
                try {
                    json.decodeFromString<GraphQlResponse>(response.body.string())
                } catch (ex: Exception) {
                    return@withContext FetchResult.Error("Invalid response: ${ex.javaClass.simpleName}")
                }
            if (!decoded.errors.isNullOrEmpty()) {
                return@withContext FetchResult.Error("GraphQL errors returned")
            }
            val match =
                decoded.data
                    ?.popularTitles
                    ?.edges
                    .orEmpty()
                    .asSequence()
                    .map { it.node }
                    .firstOrNull { it.content?.externalIds?.tmdbId == tmdbId.toString() }
                    ?: return@withContext FetchResult.Miss

            val grouped =
                match.offers
                    .asSequence()
                    .filter { it.monetizationType.uppercase(Locale.ROOT) in allowedMonetization }
                    .mapNotNull { offer ->
                        val providerName = offer.`package`?.clearName?.takeIf(String::isNotBlank)
                            ?: return@mapNotNull null
                        // Channel add-ons (for example "HBO Max Amazon Channel") must not
                        // replace the native Max/Paramount/etc app link.
                        if (providerName.contains(" channel", ignoreCase = true)) return@mapNotNull null
                        val canonicalName = canonicalStreamingProviderName(providerName)
                        if (canonicalName !in supportedProviders) return@mapNotNull null
                        val deepLink = offer.deeplinkURL?.takeIf(String::isNotBlank)
                        val web = offer.standardWebURL?.takeIf(String::isNotBlank)
                        if (deepLink == null && web == null) return@mapNotNull null
                        val links =
                            StreamingProviderDirectLinks(
                                providerName = providerName,
                                androidTv = deepLink.takeIf { platform == "ANDROID_TV" },
                                fireTv = deepLink.takeIf { platform == "FIRE_TV" },
                                standardWeb = web,
                                monetizationType = offer.monetizationType,
                            )
                        canonicalName to links
                    }
                    .groupBy({ it.first }, { it.second })
                    .mapValues { (_, offers) ->
                        offers.maxByOrNull { monetizationScore(it.monetizationType) }!!
                    }

            if (grouped.isEmpty()) {
                FetchResult.Miss
            } else {
                FetchResult.Success(
                    StreamingProviderLinks(
                        region = country,
                        linksByProvider = grouped,
                    ),
                )
            }
        }
    }

    private fun cached(key: String): CacheEntry? =
        synchronized(cache) {
            val value = cache[key] ?: return@synchronized null
            if (value.expiresAt <= System.currentTimeMillis()) {
                cache.remove(key)
                null
            } else {
                value
            }
        }

    private fun putCache(
        key: String,
        value: StreamingProviderLinks?,
        ttlMs: Long,
    ) = synchronized(cache) {
        cache[key] = CacheEntry(value, System.currentTimeMillis() + ttlMs)
    }

    private fun monetizationScore(value: String): Int =
        when (value.uppercase(Locale.ROOT)) {
            "FLATRATE" -> 3
            "FREE" -> 2
            "ADS" -> 1
            else -> 0
        }

    private fun searchQuery(
        objectType: String,
        platform: String,
    ): String =
        """
        query SearchTitles(
          ${'$'}searchQuery: String!
          ${'$'}country: Country!
          ${'$'}language: Language!
          ${'$'}first: Int!
        ) {
          popularTitles(
            country: ${'$'}country
            first: ${'$'}first
            filter: { searchQuery: ${'$'}searchQuery, objectTypes: [$objectType] }
          ) {
            edges {
              node {
                content(country: ${'$'}country, language: ${'$'}language) {
                  externalIds { tmdbId }
                }
                offers(country: ${'$'}country, platform: $platform) {
                  monetizationType
                  package { clearName }
                  deeplinkURL(platform: $platform)
                  standardWebURL
                }
              }
            }
          }
        }
        """.trimIndent()

    @Serializable
    private data class GraphQlRequest(
        val query: String,
        val variables: GraphQlVariables,
    )

    @Serializable
    private data class GraphQlVariables(
        val searchQuery: String,
        val country: String,
        val language: String,
        val first: Int,
    )

    @Serializable
    private data class GraphQlResponse(
        val data: GraphQlData? = null,
        val errors: JsonArray? = null,
    )

    @Serializable
    private data class GraphQlData(
        val popularTitles: GraphQlConnection? = null,
    )

    @Serializable
    private data class GraphQlConnection(
        val edges: List<GraphQlEdge> = emptyList(),
    )

    @Serializable
    private data class GraphQlEdge(
        val node: GraphQlNode,
    )

    @Serializable
    private data class GraphQlNode(
        val content: GraphQlContent? = null,
        val offers: List<GraphQlOffer> = emptyList(),
    )

    @Serializable
    private data class GraphQlContent(
        val externalIds: GraphQlExternalIds? = null,
    )

    @Serializable
    private data class GraphQlExternalIds(
        val tmdbId: String? = null,
    )

    @Serializable
    private data class GraphQlOffer(
        val monetizationType: String = "",
        val `package`: GraphQlPackage? = null,
        val deeplinkURL: String? = null,
        val standardWebURL: String? = null,
    )

    @Serializable
    private data class GraphQlPackage(
        val clearName: String? = null,
    )
}
