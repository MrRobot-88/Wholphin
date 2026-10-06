package com.github.damontecres.wholphin.ui.detail.discover

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
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
import androidx.core.net.toUri
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.services.StreamingAvailability

@Composable
fun StreamingProviderButtons(
    availability: StreamingAvailability,
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
                    enabled = !availability.link.isNullOrBlank(),
                    onClick = {
                        availability.link?.let { openStreamingProviderLink(context, it) }
                    },
                ) {
                    Text(name)
                }
            }
        }
    }
}

private fun openStreamingProviderLink(context: Context, link: String) {
    try {
        val intent = Intent(Intent.ACTION_VIEW, link.toUri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, R.string.streaming_link_unavailable, Toast.LENGTH_SHORT).show()
    }
}
