package com.github.damontecres.wholphin.ui.detail.discover

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.services.JustWatchContentPartnerClient
import com.github.damontecres.wholphin.services.JustWatchStreamingLinks
import com.github.damontecres.wholphin.services.StreamingAvailability
import com.github.damontecres.wholphin.services.launchExternalLink
import com.github.damontecres.wholphin.services.launchStreamingProvider

@Composable
fun StreamingProviderButtons(
    availability: StreamingAvailability,
    title: String,
    tmdbId: Int?,
    objectType: String,
    modifier: Modifier = Modifier,
) {
    if (availability.subscribedProviders.isEmpty()) return

    val context = LocalContext.current
    var justWatchLinks by
        remember(tmdbId, objectType, availability.region) {
            mutableStateOf<JustWatchStreamingLinks?>(null)
        }

    LaunchedEffect(tmdbId, objectType, availability.region) {
        justWatchLinks =
            tmdbId?.takeIf { it > 0 }?.let {
                JustWatchContentPartnerClient.getStreamingLinks(
                    objectType = objectType,
                    tmdbId = it,
                    region = availability.region,
                )
            }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.stream_on))
            justWatchLinks?.attributionUrl?.let { attributionUrl ->
                Button(onClick = { launchExternalLink(context, attributionUrl) }) {
                    Text("JustWatch")
                }
            }
        }

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            items(
                items = availability.subscribedProviders,
                key = { it.id ?: it.name.orEmpty() },
            ) { provider ->
                val name = provider.name ?: return@items
                val directLinks = justWatchLinks?.forProvider(name)
                val logoUrl = directLinks?.providerIconUrl ?: providerLogoUrl(provider.logoPath)
                Button(
                    onClick = {
                        val launched =
                            launchStreamingProvider(
                                context = context,
                                providerName = name,
                                title = title,
                                fallbackLink = availability.link,
                                directLinks = directLinks,
                            )
                        if (!launched) {
                            Toast.makeText(
                                context,
                                R.string.streaming_link_unavailable,
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    },
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        logoUrl?.let {
                            AsyncImage(
                                model = it,
                                contentDescription = name,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.size(30.dp),
                            )
                        }
                        Text(name)
                    }
                }
            }
        }
    }
}

private fun providerLogoUrl(path: String?): String? =
    path?.takeIf { it.isNotBlank() }?.let { "https://image.tmdb.org/t/p/w92$it" }
