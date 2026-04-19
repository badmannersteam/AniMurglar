package com.badmanners.animurglar.ui.dubs

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.arkivanov.mvikotlin.extensions.coroutines.stateFlow
import com.badmanners.animurglar.dubs.DubAnimeCandidate
import com.badmanners.animurglar.dubs.DubInfo
import com.badmanners.animurglar.ui.dubs.DubsPickerStore.Intent
import com.badmanners.animurglar.utils.openInBrowser
import kotlinx.coroutines.ExperimentalCoroutinesApi


@OptIn(ExperimentalCoroutinesApi::class, ExperimentalLayoutApi::class)
@Composable
fun DubsPickerScreen(
    dubsPickerStore: DubsPickerStore,
    modifier: Modifier = Modifier,
) {
    val state by dubsPickerStore.stateFlow.collectAsState()
    val selectedAnime = state.animeCandidates.firstOrNull { it.key == state.selectedAnimeKey }
    val isVisible = state.isLoading
        || state.error != null
        || state.animeCandidates.isNotEmpty()
        || state.dubs.isNotEmpty()
        || selectedAnime != null

    if (isVisible) {
        Column(modifier = modifier.fillMaxWidth()) {
            DubsAnimePickerCard(
                selectedAnime = selectedAnime,
                candidatesCount = state.animeCandidates.size,
                isLoading = state.isLoading,
                error = state.error,
                onOpenDialog = { dubsPickerStore.accept(Intent.AnimePickerDialogOpened) },
                onCancelSearch = { dubsPickerStore.accept(Intent.CancelSearch) },
            )

            DubSelectionSection(
                dubs = state.dubs,
                selectedDubKeys = state.selectedDubKeys,
                onSelectionChanged = { dubKey, selected ->
                    dubsPickerStore.accept(
                        Intent.DubSelectionChanged(
                            dubKey = dubKey,
                            selected = selected,
                        )
                    )
                },
                onSelectAll = {
                    state.dubs.forEach { dub ->
                        dubsPickerStore.accept(
                            Intent.DubSelectionChanged(
                                dubKey = dub.key,
                                selected = true,
                            )
                        )
                    }
                },
                onClearAll = {
                    state.dubs.forEach { dub ->
                        dubsPickerStore.accept(
                            Intent.DubSelectionChanged(
                                dubKey = dub.key,
                                selected = false,
                            )
                        )
                    }
                },
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }

    if (state.isAnimePickerDialogOpen) {
        DubsAnimePickerDialog(
            candidates = state.animeCandidates,
            selectedAnimeKey = state.selectedAnimeKey,
            onDismissRequest = {
                dubsPickerStore.accept(Intent.AnimePickerDialogDismissed)
            },
            onAnimeSelected = { candidateKey ->
                dubsPickerStore.accept(Intent.AnimeSelected(candidateKey = candidateKey))
            },
        )
    }
}

@Composable
private fun DubsAnimePickerCard(
    selectedAnime: DubAnimeCandidate?,
    candidatesCount: Int,
    isLoading: Boolean,
    error: String?,
    onOpenDialog: () -> Unit,
    onCancelSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        onClick = { if (candidatesCount > 0) onOpenDialog() },
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Тайтлы с озвучкой",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )

                if (candidatesCount > 0) {
                    Text(
                        text = "$candidatesCount тайтлов",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                if (isLoading) {
                    TextButton(onClick = onCancelSearch) {
                        Text("Отмена")
                    }
                }
            }

            if (isLoading) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
            }

            error?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }

            selectedAnime?.let {
                AnimeCandidateSummary(
                    candidate = it,
                    modifier = Modifier.padding(top = 6.dp),
                    onClick = onOpenDialog
                )
            }
        }
    }
}

@Composable
private fun DubsAnimePickerDialog(
    candidates: List<DubAnimeCandidate>,
    selectedAnimeKey: String?,
    onDismissRequest: () -> Unit,
    onAnimeSelected: (String) -> Unit,
) {
    val listState = rememberLazyListState()

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text("Тайтлы с озвучкой") },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 220.dp, max = 560.dp),
            ) {
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(end = 12.dp),
                ) {
                    items(
                        items = candidates,
                        key = { it.key },
                    ) { candidate ->
                        val selected = candidate.key == selectedAnimeKey
                        AnimeCandidateSummary(
                            candidate = candidate,
                            selected = selected,
                            onClick = { onAnimeSelected(candidate.key) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                VerticalScrollbar(
                    adapter = rememberScrollbarAdapter(listState),
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight(),
                )
            }
        },
        confirmButton = {
            Button(onClick = onDismissRequest) {
                Text("Закрыть")
            }
        },
        modifier = Modifier.widthIn(min = 760.dp, max = 1100.dp),
        properties = DialogProperties(usePlatformDefaultWidth = false),
    )
}

@Composable
private fun DubSelectionSection(
    dubs: List<DubInfo>,
    selectedDubKeys: Set<String>,
    onSelectionChanged: (String, Boolean) -> Unit,
    onSelectAll: () -> Unit,
    onClearAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Озвучки (${selectedDubKeys.size}/${dubs.size})",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )

                if (dubs.isNotEmpty()) {
                    TextButton(onClick = onSelectAll) {
                        Text("Выбрать все")
                    }

                    TextButton(onClick = onClearAll) {
                        Text("Снять все")
                    }
                }
            }

            if (dubs.isEmpty()) {
                Text(
                    text = "Для выбранного тайтла озвучки не найдены",
                    style = MaterialTheme.typography.bodyMedium
                )
                return@ElevatedCard
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
                    .padding(top = 8.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                dubs.forEach { dub ->
                    val selected = dub.key in selectedDubKeys
                    FilterChip(
                        selected = selected,
                        onClick = { onSelectionChanged(dub.key, !selected) },
                        label = {
                            Column(
                                modifier = Modifier.padding(vertical = 2.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(dub.teamName, style = MaterialTheme.typography.labelLarge)
                                Text(
                                    "${dub.episodes.size} сер." + dub.views?.let { " • $it просм." }.orEmpty(),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun AnimeCandidateSummary(
    candidate: DubAnimeCandidate,
    selected: Boolean? = null,
    onClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    ElevatedCard(
        modifier = modifier,
        onClick = onClick,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (selected != null) {
                    RadioButton(selected = selected, onClick = onClick)
                }

                Text(
                    text = candidate.russianName ?: candidate.name,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )

                Text(
                    text = candidate.releaseDate ?: "?",
                    style = MaterialTheme.typography.labelLarge,
                    textAlign = TextAlign.End,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = candidate.name,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = candidate.description.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                Text(
                    text = candidate.sourceId,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.End
                )

                TextButton(onClick = { openInBrowser(candidate.webUrl) }) {
                    Text("Открыть")
                }
            }
        }
    }
}
