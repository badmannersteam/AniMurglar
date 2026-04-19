package com.badmanners.animurglar.ui.episodes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.arkivanov.mvikotlin.extensions.coroutines.stateFlow
import com.badmanners.animurglar.downloader.suggestEpisodeMapping
import com.badmanners.animurglar.ui.episodes.EpisodeMappingStore.Intent
import kotlinx.coroutines.ExperimentalCoroutinesApi


@OptIn(ExperimentalCoroutinesApi::class)
@Composable
fun EpisodeMappingScreen(
    episodeMappingStore: EpisodeMappingStore,
    selectedTorrentEpisodes: Set<Int>,
    modifier: Modifier = Modifier,
) {
    val state by episodeMappingStore.stateFlow.collectAsState()
    EpisodeMappingSection(
        selectedTorrentEpisodes = selectedTorrentEpisodes,
        dubStartOverride = state.dubStartOverride,
        onDubStartChanged = { dubStartEpisode ->
            episodeMappingStore.accept(Intent.DubStartChanged(dubStartEpisode = dubStartEpisode))
        },
        modifier = modifier,
    )
}

@Composable
private fun EpisodeMappingSection(
    selectedTorrentEpisodes: Set<Int>,
    dubStartOverride: Int?,
    onDubStartChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (selectedTorrentEpisodes.isEmpty()) {
        return
    }
    val mapping = suggestEpisodeMapping(
        selectedEpisodes = selectedTorrentEpisodes,
        dubStartOverride = dubStartOverride,
    )

    val sortedTorrentEpisodes = selectedTorrentEpisodes.sorted()

    fun previewText(episodeNumber: Int) = "$episodeNumber → ${mapping.dubEpisodeNumber(episodeNumber)}"
    val preview = when {
        sortedTorrentEpisodes.size > 6 -> sortedTorrentEpisodes.take(3).joinToString(transform = ::previewText) +
            ", ..., " +
            sortedTorrentEpisodes.takeLast(3).joinToString(transform = ::previewText)

        else -> sortedTorrentEpisodes.joinToString(transform = ::previewText)
    }

    ElevatedCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Маппинг серий",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )

                Text(
                    text = when {
                        mapping.offset == 0 -> "Та же нумерация"
                        else -> "Сдвиг ${mapping.offset}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Text(
                text = when {
                    mapping.offset == 0 -> "Серии в торренте и озвучке нумеруются одинаково."
                    else -> "Серия ${mapping.torrentStartEpisode} из торрента соответствует серии ${mapping.dubStartEpisode} из озвучки."
                },
                style = MaterialTheme.typography.bodySmall,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = mapping.torrentStartEpisode.toString(),
                    onValueChange = {},
                    readOnly = true,
                    singleLine = true,
                    label = { Text("Торрент начинается с") },
                    modifier = Modifier.weight(1f),
                )

                OutlinedTextField(
                    value = mapping.dubStartEpisode.toString(),
                    onValueChange = { value ->
                        value.toIntOrNull()?.let { dubStartEpisode ->
                            if (dubStartEpisode > 0) {
                                onDubStartChanged(dubStartEpisode)
                            }
                        }
                    },
                    singleLine = true,
                    label = { Text("Озвучка начинается с") },
                    modifier = Modifier.weight(1f),
                )
            }

            Text(
                text = "Превью: $preview",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}