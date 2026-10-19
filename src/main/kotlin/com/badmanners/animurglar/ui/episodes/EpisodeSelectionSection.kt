package com.badmanners.animurglar.ui.episodes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EpisodeSelectionSection(
    episodes: List<Int>,
    selectedEpisodes: Set<Int>,
    onEpisodeSelectionChanged: (Int, Boolean) -> Unit,
    onSelectAll: () -> Unit,
    onClearAll: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    headerContent: @Composable () -> Unit = {},
) {
    ElevatedCard(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            headerContent()
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Серии (${selectedEpisodes.size}/${episodes.size})",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )

                if (episodes.isNotEmpty()) {
                    TextButton(onClick = onSelectAll, enabled = enabled) {
                        Text("Выбрать все")
                    }

                    TextButton(onClick = onClearAll, enabled = enabled) {
                        Text("Снять все")
                    }
                }
            }

            if (episodes.isEmpty()) {
                Text(
                    text = "Серии не найдены",
                    style = MaterialTheme.typography.bodyMedium,
                )
                return@ElevatedCard
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                episodes.sorted().forEach { episodeNumber ->
                    val selected = episodeNumber in selectedEpisodes
                    FilterChip(
                        selected = selected,
                        onClick = { onEpisodeSelectionChanged(episodeNumber, !selected) },
                        enabled = enabled,
                        label = { Text("Серия $episodeNumber") },
                    )
                }
            }
        }
    }
}
