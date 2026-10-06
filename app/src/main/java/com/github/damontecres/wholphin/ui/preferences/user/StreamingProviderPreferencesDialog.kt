package com.github.damontecres.wholphin.ui.preferences.user

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ListItem
import androidx.tv.material3.Switch
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.api.seerr.model.WatchProviderDetails
import com.github.damontecres.wholphin.api.seerr.model.WatchProviderRegion
import com.github.damontecres.wholphin.ui.components.BasicDialog
import com.github.damontecres.wholphin.ui.main.settings.TitleText
import com.github.damontecres.wholphin.ui.preferences.SwitchColors

@Composable
fun StreamingRegionDialog(
    regions: List<WatchProviderRegion>,
    selectedRegion: String,
    onSelect: (String) -> Unit,
    onDismissRequest: () -> Unit,
) {
    BasicDialog(onDismissRequest = onDismissRequest, elevation = 3.dp) {
        Column(Modifier.padding(16.dp)) {
            TitleText("Streaming region")
            LazyColumn {
                items(
                    items = regions,
                    key = { it.iso31661 ?: it.englishName ?: it.nativeName.orEmpty() },
                ) { region ->
                    val code = region.iso31661?.uppercase() ?: return@items
                    ListItem(
                        enabled = true,
                        selected = code == selectedRegion,
                        onClick = { onSelect(code) },
                        headlineContent = {
                            Text(region.nativeName ?: region.englishName ?: code)
                        },
                        supportingContent = {
                            val englishName = region.englishName
                            if (!englishName.isNullOrBlank() && englishName != region.nativeName) {
                                Text("$englishName · $code")
                            } else {
                                Text(code)
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
fun StreamingProviderDialog(
    providers: List<WatchProviderDetails>,
    selectedProviderIds: Set<Int>,
    onToggle: (Int) -> Unit,
    onDismissRequest: () -> Unit,
) {
    BasicDialog(onDismissRequest = onDismissRequest, elevation = 3.dp) {
        Column(Modifier.padding(16.dp)) {
            TitleText("Streaming services")
            LazyColumn {
                items(
                    items = providers,
                    key = { it.id ?: it.name.orEmpty() },
                ) { provider ->
                    val id = provider.id ?: return@items
                    val name = provider.name ?: return@items
                    val checked = id in selectedProviderIds
                    ListItem(
                        enabled = true,
                        selected = false,
                        onClick = { onToggle(id) },
                        headlineContent = { Text(name) },
                        trailingContent = {
                            Switch(
                                checked = checked,
                                onCheckedChange = {},
                                colors = SwitchColors(),
                            )
                        },
                    )
                }
            }
        }
    }
}
