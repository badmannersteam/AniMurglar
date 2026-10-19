package com.badmanners.animurglar.ui.raws

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.badmanners.animurglar.ui.episodes.EpisodeSelectionSection
import com.badmanners.animurglar.ui.episodes.SeasonSelection
import com.badmanners.animurglar.ui.root.RawSourceKind
import com.badmanners.animurglar.ui.root.RootStore
import com.badmanners.animurglar.ui.root.RootStore.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import javax.swing.JFileChooser

@Composable
fun RawSourceSelection(
    kind: RawSourceKind,
    onKindSelected: (RawSourceKind) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    SecondaryTabRow(
        selectedTabIndex = kind.ordinal,
        modifier = modifier.fillMaxWidth(),
        containerColor = Color.Transparent,
    ) {
        RawSourceKind.entries.forEach { sourceKind ->
            Tab(
                selected = kind == sourceKind,
                onClick = { onKindSelected(sourceKind) },
                enabled = enabled,
                text = {
                    Text(
                        text = when (sourceKind) {
                            RawSourceKind.NYAA -> "nyaa.si"
                            RawSourceKind.LOCAL -> "Локальная папка"
                        },
                        maxLines = 1,
                    )
                },
            )
        }
    }
}

@Composable
fun LocalRawFilesScreen(
    state: RootStore.State,
    onIntent: (Intent) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    Column(modifier = modifier.fillMaxWidth()) {
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Локальные равки", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = state.localFolder,
                    onValueChange = { onIntent(Intent.LocalFolderChanged(it)) },
                    label = { Text("Папка с готовыми видеофайлами") },
                    singleLine = true,
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = enabled,
                        onClick = {
                            scope.launch {
                                val folder = withContext(Dispatchers.Swing) {
                                    val chooser = JFileChooser(state.localFolder).apply {
                                        dialogTitle = "Папка с равками"
                                        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                                        isAcceptAllFileFilterUsed = false
                                    }
                                    when (chooser.showOpenDialog(null)) {
                                        JFileChooser.APPROVE_OPTION -> chooser.selectedFile.toPath().toString()
                                        else -> null
                                    }
                                }
                                folder?.let {
                                    onIntent(Intent.LocalFolderChanged(it))
                                    onIntent(Intent.ScanLocalFolder)
                                }
                            }
                        },
                    ) {
                        Text("Выбрать папку…")
                    }
                    TextButton(
                        enabled = enabled && !state.isScanningLocalFolder,
                        onClick = { onIntent(Intent.ScanLocalFolder) },
                    ) {
                        Text("Прочитать папку")
                    }
                }
                if (state.isScanningLocalFolder) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                state.localFolderError?.let { error ->
                    Text(error, color = MaterialTheme.colorScheme.error)
                }
            }
        }

        if (state.localFiles.isNotEmpty()) {
            EpisodeSelectionSection(
                episodes = state.localFiles.keys.toList(),
                selectedEpisodes = state.selectedLocalEpisodes,
                onEpisodeSelectionChanged = { episodeNumber, selected ->
                    onIntent(Intent.LocalEpisodeSelectionChanged(episodeNumber, selected))
                },
                onSelectAll = { onIntent(Intent.AllLocalEpisodesSelectionChanged(true)) },
                onClearAll = { onIntent(Intent.AllLocalEpisodesSelectionChanged(false)) },
                enabled = enabled,
                modifier = Modifier.padding(top = 12.dp).weight(1f, fill = false),
                headerContent = {
                    SeasonSelection(
                        seasons = state.localSeasons.keys.sorted(),
                        selectedSeason = state.selectedLocalSeason,
                        onSeasonSelected = { onIntent(Intent.LocalSeasonSelected(it)) },
                        enabled = enabled,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                },
            )
        }
    }
}