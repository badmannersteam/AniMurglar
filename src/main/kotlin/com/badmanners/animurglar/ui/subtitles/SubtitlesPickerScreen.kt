package com.badmanners.animurglar.ui.subtitles

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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.arkivanov.mvikotlin.extensions.coroutines.stateFlow
import com.badmanners.animurglar.subtitles.SubtitleTeamInfo
import com.badmanners.animurglar.ui.subtitles.SubtitlesPickerStore.Intent
import kotlinx.coroutines.ExperimentalCoroutinesApi


@OptIn(ExperimentalCoroutinesApi::class, ExperimentalLayoutApi::class)
@Composable
fun SubtitlesPickerScreen(
    subtitlesPickerStore: SubtitlesPickerStore,
    modifier: Modifier = Modifier,
) {
    val state by subtitlesPickerStore.stateFlow.collectAsState()
    val isVisible = state.isLoading || state.error != null || state.teams.isNotEmpty()

    if (isVisible) {
        SubtitleTeamSelectionCard(
            isLoading = state.isLoading,
            error = state.error,
            teams = state.teams,
            selectedTeamKeys = state.selectedTeamKeys,
            onSelectionChanged = { teamKey, selected ->
                subtitlesPickerStore.accept(Intent.TeamSelectionChanged(teamKey, selected))
            },
            onSelectAll = { subtitlesPickerStore.accept(Intent.SelectAll) },
            onClearAll = { subtitlesPickerStore.accept(Intent.ClearAll) },
            onCancelSearch = { subtitlesPickerStore.accept(Intent.CancelSearch) },
            modifier = modifier,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SubtitleTeamSelectionCard(
    isLoading: Boolean,
    error: String?,
    teams: List<SubtitleTeamInfo>,
    selectedTeamKeys: Set<String>,
    onSelectionChanged: (String, Boolean) -> Unit,
    onSelectAll: () -> Unit,
    onClearAll: () -> Unit,
    onCancelSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Субтитры (${selectedTeamKeys.size}/${teams.size})",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )

                when {
                    isLoading -> TextButton(onClick = onCancelSearch) {
                        Text("Отмена")
                    }

                    teams.isNotEmpty() -> {
                        TextButton(onClick = onSelectAll) {
                            Text("Выбрать все")
                        }
                        TextButton(onClick = onClearAll) {
                            Text("Снять все")
                        }
                    }
                }
            }

            if (isLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
            }

            error?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }

            when {
                teams.isEmpty() && !isLoading && error == null -> Text(
                    text = "Для выбранного тайтла субтитры Anime365 не найдены",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 12.dp),
                )

                teams.isNotEmpty() -> FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                        .padding(top = 8.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    teams.forEach { team ->
                        val selected = team.key in selectedTeamKeys
                        FilterChip(
                            selected = selected,
                            onClick = { onSelectionChanged(team.key, !selected) },
                            label = {
                                Column(
                                    modifier = Modifier.padding(vertical = 2.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Text(team.teamName, style = MaterialTheme.typography.labelLarge)
                                    Text(
                                        "${team.episodes.size} сер.",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}