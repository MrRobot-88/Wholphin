package com.github.damontecres.wholphin.services

import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.net.toUri
import java.util.Locale

private data class StreamingProviderApp(
    val matches: Set<String>,
    val packages: List<String>,
)

private val STREAMING_PROVIDER_APPS =
    listOf(
        StreamingProviderApp(
            matches = setOf("netflix"),
            packages = listOf("com.netflix.ninja"),
        ),
        StreamingProviderApp(
            matches = setOf("prime video", "amazon prime video", "amazon video"),
            packages = listOf(
                "com.amazon.amazonvideo.livingroom",
                "com.amazon.avod.thirdpartyclient",
            ),
        ),
        StreamingProviderApp(
            matches = setOf("disney plus", "disney"),
            packages = listOf("com.disney.disneyplus"),
        ),
        StreamingProviderApp(
            matches = setOf("max", "hbo max"),
            packages = listOf("com.wbd.stream", "com.wbd.hbomax"),
        ),
        StreamingProviderApp(
            matches = setOf("viaplay"),
            packages = listOf("com.viaplay.android"),
        ),
        StreamingProviderApp(
            matches = setOf("svt play", "svt"),
            packages = listOf("se.svt.android.svtplay"),
        ),
        StreamingProviderApp(
            matches = setOf("apple tv plus", "apple tv"),
            packages = listOf(
                "com.apple.atve.androidtv.appletv",
                "com.apple.atve.sony.appletv",
                "com.apple.atve.amazon.appletv",
            ),
        ),
        StreamingProviderApp(
            matches = setOf("skyshowtime"),
            packages = listOf("com.skyshowtime.skyshowtime.google"),
        ),
        StreamingProviderApp(
            matches = setOf("paramount plus", "paramount"),
            packages = listOf("com.cbs.ott"),
        ),
    )

internal fun streamingProviderPackages(providerName: String?): List<String> {
    val normalized = normalizeProviderName(providerName) ?: return emptyList()
    if (normalized.contains("amazon channel")) {
        return listOf("com.amazon.amazonvideo.livingroom", "com.amazon.avod.thirdpartyclient")
    }
    return STREAMING_PROVIDER_APPS
        .firstOrNull { app ->
            app.matches.any { match -> normalized == match || normalized.startsWith("$match ") }
        }
        ?.packages
        .orEmpty()
}

fun launchStreamingProvider(
    context: Context,
    providerName: String?,
    title: String,
    fallbackLink: String?,
    directLinks: StreamingProviderDirectLinks? = null,
): Boolean {
    val packageManager = context.packageManager
    val packages = streamingProviderPackages(providerName)
    val isFireTv = isAmazonFireTv()

    if (directLinks != null) {
        val appLinks = directLinks.preferredAppLinks(isFireTv)
        for (packageName in packages) {
            for (link in appLinks) {
                val intent = buildViewIntent(link, packageName) ?: continue
                if (canResolve(packageManager, intent) && tryStart(context, intent)) return true
            }
        }

        // Some provider deep links can resolve themselves even when the package name differs
        // by device/market. This is especially useful on Fire TV variants.
        for (link in appLinks) {
            if (launchExternalLink(context, link)) return true
        }
    }

    for (packageName in packages) {
        if (fallbackLink?.isNotBlank() == true) {
            val appLink = buildViewIntent(fallbackLink, packageName)
            if (appLink != null && canResolve(packageManager, appLink) && tryStart(context, appLink)) return true
        }

        if (title.isNotBlank()) {
            val searchIntent =
                Intent(Intent.ACTION_SEARCH)
                    .setPackage(packageName)
                    .putExtra(SearchManager.QUERY, title)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (canResolve(packageManager, searchIntent) && tryStart(context, searchIntent)) return true
        }

        val launchIntent =
            packageManager.getLeanbackLaunchIntentForPackage(packageName)
                ?: packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (tryStart(context, launchIntent)) return true
        }
    }

    directLinks?.standardWeb?.let { if (launchExternalLink(context, it)) return true }
    if (!fallbackLink.isNullOrBlank() && launchExternalLink(context, fallbackLink)) return true
    return false
}

fun launchExternalLink(
    context: Context,
    link: String,
): Boolean {
    if (link.isBlank()) return false
    val intent = buildViewIntent(link, null) ?: return false
    return tryStart(context, intent)
}

private fun buildViewIntent(
    link: String,
    packageName: String?,
): Intent? =
    try {
        val intent =
            if (link.startsWith("intent://", ignoreCase = true)) {
                Intent.parseUri(link, Intent.URI_INTENT_SCHEME)
            } else {
                Intent(Intent.ACTION_VIEW, link.toUri())
            }
        if (!packageName.isNullOrBlank() && intent.`package`.isNullOrBlank()) {
            intent.setPackage(packageName)
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    } catch (_: Exception) {
        null
    }

internal fun isAmazonFireTv(): Boolean =
    Build.MANUFACTURER.equals("Amazon", ignoreCase = true) ||
        Build.MODEL.orEmpty().startsWith("AFT", ignoreCase = true)

private fun canResolve(packageManager: PackageManager, intent: Intent): Boolean =
    intent.resolveActivity(packageManager) != null

private fun tryStart(context: Context, intent: Intent): Boolean =
    try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }

private fun normalizeProviderName(value: String?): String? {
    val normalized =
        value
            ?.lowercase(Locale.ROOT)
            ?.replace("+", " plus ")
            ?.replace(Regex("[^a-z0-9]+"), " ")
            ?.trim()
            ?.replace(Regex("\\s+"), " ")
    return normalized?.takeIf { it.isNotBlank() }
}
