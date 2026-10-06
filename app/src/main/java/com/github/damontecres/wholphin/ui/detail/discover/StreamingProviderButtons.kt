package com.github.damontecres.wholphin.ui.detail.discover

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.services.StreamingAvailability
import com.github.damontecres.wholphin.services.launchStreamingProvider

@Composable
fun StreamingProviderButtons(
    availability: StreamingAvailability,
    title: String,
    modifier: Modifier = Modifier,
) {
    if (availability.subscribedProviders.isEmpty()) return

    val context = LocalContext.current
    Column(modifier = modifier.fillMaxWidth()) {
        Text(stringResource(R.string.stream_on))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            items(
                items = availability.subscribedProviders,
                key = { it.id ?: it.name.orEmpty() },
            ) { provider ->
                val name = provider.name ?: return@items
                Button(
                    onClick = {
                        val launched =
                            launchStreamingProvider(
                                context = context,
                                providerName = name,
                                title = title,
                                fallbackLink = availability.link,
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
                    Text(name)
                }
            }
        }
    }
}
