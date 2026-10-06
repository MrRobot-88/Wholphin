package com.github.damontecres.wholphin.services

import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
): Boolean {
    val packageManager = context.packageManager

    for (packageName in streamingProviderPackages(providerName)) {
        if (fallbackLink?.isNotBlank() == true) {
            val appLink =
                Intent(Intent.ACTION_VIEW, fallbackLink.toUri())
                    .setPackage(packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (canResolve(packageManager, appLink) && tryStart(context, appLink)) return true
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

    if (!fallbackLink.isNullOrBlank()) {
        val browserIntent =
            Intent(Intent.ACTION_VIEW, fallbackLink.toUri())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (tryStart(context, browserIntent)) return true
    }

    return false
}

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
