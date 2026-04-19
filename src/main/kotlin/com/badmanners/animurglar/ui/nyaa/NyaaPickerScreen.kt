package com.badmanners.animurglar.ui.nyaa

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
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.arkivanov.mvikotlin.extensions.coroutines.stateFlow
import com.badmanners.animurglar.nyaa.TorrentCandidate
import com.badmanners.animurglar.nyaa.TorrentEntry
import com.badmanners.animurglar.nyaa.TorrentEntry.EpisodeMediaFile
import com.badmanners.animurglar.nyaa.TorrentEpisodeGroup
import com.badmanners.animurglar.ui.nyaa.NyaaPickerStore.Intent
import com.badmanners.animurglar.utils.openInBrowser
import com.badmanners.animurglar.utils.toReadableSize
import kotlinx.coroutines.ExperimentalCoroutinesApi
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter


@OptIn(ExperimentalCoroutinesApi::class, ExperimentalLayoutApi::class)
@Composable
fun NyaaPickerScreen(
    nyaaPickerStore: NyaaPickerStore,
    modifier: Modifier = Modifier,
) {
    val state by nyaaPickerStore.stateFlow.collectAsState()
    val selectedCandidate = state.candidates.firstOrNull { it.key == state.selectedCandidateKey }

    val isVisible = state.isLoading
        || state.error != null
        || state.candidates.isNotEmpty()
        || selectedCandidate != null

    if (isVisible) {
        Column(modifier = modifier.fillMaxWidth()) {
            NyaaPickerCard(
                selectedCandidate = selectedCandidate,
                candidatesCount = state.candidates.size,
                isLoading = state.isLoading,
                error = state.error,
                progressCurrent = state.progressCurrent,
                progressTotal = state.progressTotal,
                onOpenDialog = { nyaaPickerStore.accept(Intent.PickerDialogOpened) },
                onCancelSearch = { nyaaPickerStore.accept(Intent.CancelSearch) },
            )

            EpisodeSelectionSection(
                episodes = selectedCandidate?.episodes.orEmpty(),
                selectedEpisodes = state.selectedEpisodes,
                onEpisodeSelectionChanged = { episodeNumber, selected ->
                    nyaaPickerStore.accept(
                        Intent.EpisodeSelectionChanged(
                            episodeNumber = episodeNumber,
                            selected = selected,
                        )
                    )
                },
                onSelectAll = {
                    selectedCandidate?.episodes?.forEach { episodeNumber ->
                        nyaaPickerStore.accept(
                            Intent.EpisodeSelectionChanged(
                                episodeNumber = episodeNumber,
                                selected = true,
                            )
                        )
                    }
                },
                onClearAll = {
                    selectedCandidate?.episodes?.forEach { episodeNumber ->
                        nyaaPickerStore.accept(
                            Intent.EpisodeSelectionChanged(
                                episodeNumber = episodeNumber,
                                selected = false,
                            )
                        )
                    }
                },
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }

    if (state.isPickerDialogOpen) {
        NyaaPickerDialog(
            candidates = state.candidates,
            selectedCandidateKey = state.selectedCandidateKey,
            onDismissRequest = {
                nyaaPickerStore.accept(Intent.PickerDialogDismissed)
            },
            onCandidateSelected = { candidateKey ->
                nyaaPickerStore.accept(Intent.CandidateSelected(candidateKey = candidateKey))
                nyaaPickerStore.accept(Intent.PickerDialogDismissed)
            },
        )
    }
}

@Composable
private fun SearchProgressSection(
    currentRequest: Int,
    totalRequests: Int,
    modifier: Modifier = Modifier,
) {
    val progress = when {
        totalRequests > 0 -> currentRequest.toFloat() / totalRequests.toFloat()
        else -> 0f
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "Запросы: $currentRequest/$totalRequests",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(modifier = Modifier.height(8.dp))
        when {
            totalRequests > 0 -> LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            else -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun NyaaPickerCard(
    selectedCandidate: TorrentCandidate?,
    candidatesCount: Int,
    isLoading: Boolean,
    error: String?,
    progressCurrent: Int,
    progressTotal: Int,
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
                    text = "Торренты с равками",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )

                if (candidatesCount > 0) {
                    Text(
                        text = "$candidatesCount торрентов",
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
                SearchProgressSection(
                    currentRequest = progressCurrent,
                    totalRequests = progressTotal,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }

            error?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }

            selectedCandidate?.let {
                CandidateDialogEntryCard(
                    modifier = Modifier.padding(top = 6.dp),
                    onClick = onOpenDialog,
                    candidate = it,
                )
            }
        }
    }
}

@Composable
private fun NyaaPickerDialog(
    candidates: List<TorrentCandidate>,
    selectedCandidateKey: String?,
    onDismissRequest: () -> Unit,
    onCandidateSelected: (String) -> Unit,
) {
    val listState = rememberLazyListState()
    var hideZeroSeeders by remember { mutableStateOf(true) }
    val visibleCandidates = when {
        hideZeroSeeders -> candidates.filter { it.hasSeeders() }
        else -> candidates
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            Button(onClick = onDismissRequest) {
                Text("Закрыть")
            }
        },
        modifier = Modifier.widthIn(min = 960.dp, max = 1400.dp),
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Торренты с равками",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f),
                )

                Checkbox(
                    checked = hideZeroSeeders,
                    onCheckedChange = { hideZeroSeeders = it },
                )
                Text(
                    text = "Скрыть торренты без сидов",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        text = {
            Column {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 220.dp, max = 620.dp),
                ) {
                    LazyColumn(
                        state = listState,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(end = 12.dp),
                    ) {
                        items(
                            items = visibleCandidates,
                            key = { it.key },
                        ) { candidate ->
                            CandidateDialogEntryCard(
                                onClick = { onCandidateSelected(candidate.key) },
                                selected = selectedCandidateKey == candidate.key,
                                candidate = candidate,
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
            }
        },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    )
}

private fun TorrentCandidate.hasSeeders() = when (this) {
    is TorrentCandidate.SeasonPack -> entry.seeders > 0
    is TorrentCandidate.EpisodeGroup -> group.episodes.values.none { it.seeders == 0 }
}

@Composable
private fun CandidateDialogEntryCard(
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
    selected: Boolean? = null,
    candidate: TorrentCandidate,
) {
    when (candidate) {
        is TorrentCandidate.SeasonPack -> SeasonPackSnapshot(
            modifier = modifier,
            onClick = onClick,
            selected = selected,
            entry = candidate.entry
        )

        is TorrentCandidate.EpisodeGroup -> EpisodeGroupSnapshot(
            modifier = modifier,
            onClick = onClick,
            selected = selected,
            group = candidate.group
        )
    }
}

@Composable
private fun SeasonPackSnapshot(
    modifier: Modifier,
    onClick: () -> Unit,
    selected: Boolean?,
    entry: TorrentEntry
) {
    TorrentCard(
        modifier = modifier,
        onClick = onClick,
        selected = selected,
        displayName = entry.displayName,
        type = "Сезонный пак",
        category = entry.nyaaCategory,
        totalEpisodes = entry.episodeMediaFiles.size,
        episodeRange = entry.episodeMediaFiles.keys.sorted().toEpisodeRangeLabel(),
        totalSize = entry.totalSizeBytes.toReadableSize(),
        sizeRange = entry.episodeMediaFiles.values.map(EpisodeMediaFile::sizeBytes).toSizeRangeLabel(),
        seedersRange = entry.seeders.toString(),
        downloadsRange = entry.totalDownloads.toString(),
        dateRange = entry.uploadedAt.toDisplayDate(),
        metadata = listOfNotNull(
            entry.metadata.resolution,
            entry.metadata.source,
            entry.metadata.videoCodec,
            entry.metadata.audioCodec,
        ).joinToString(" • "),
        showFiles = selected != null,
        files = entry.episodeMediaFiles.values
            .sortedBy { it.episodeNumber }
            .map { file -> "Серия ${file.episodeNumber}: ${file.fullPathInTorrent} (${file.sizeBytes.toReadableSize()})" },
        detailsLink = entry.detailsLink
    )
}

@Composable
private fun EpisodeGroupSnapshot(
    modifier: Modifier,
    onClick: () -> Unit,
    selected: Boolean?,
    group: TorrentEpisodeGroup
) {
    val entries = group.episodes.toSortedMap().values.toList()
    TorrentCard(
        modifier = modifier,
        onClick = onClick,
        selected = selected,
        displayName = group.displayName,
        type = "Группа серий",
        category = entries.first().nyaaCategory,
        totalEpisodes = group.episodes.size,
        episodeRange = group.episodes.keys.sorted().toEpisodeRangeLabel(),
        totalSize = entries.mapNotNull(TorrentEntry::totalSizeBytes).sum().toReadableSize(),
        sizeRange = entries.mapNotNull(TorrentEntry::totalSizeBytes).toSizeRangeLabel(),
        seedersRange = entries.mapNotNull(TorrentEntry::seeders).toIntRangeLabel(),
        downloadsRange = entries.mapNotNull(TorrentEntry::totalDownloads).toIntRangeLabel(),
        dateRange = entries.mapNotNull(TorrentEntry::uploadedAt).toDateRangeLabel(),
        metadata = listOfNotNull(
            group.profile.resolution,
            group.profile.source,
            group.profile.videoCodec,
            group.profile.audioCodec,
        ).joinToString(" • "),
        showFiles = selected != null,
        files = entries.asSequence()
            .flatMap { it.episodeMediaFiles.values.asSequence() }
            .sortedWith(compareBy({ it.episodeNumber }, { it.fullPathInTorrent }))
            .map { file -> "Серия ${file.episodeNumber}: ${file.fullPathInTorrent} (${file.sizeBytes.toReadableSize()})" }
            .toList(),
        detailsLink = group.episodes.values.minBy { it.episodeMediaFiles.keys.min() }.detailsLink,
    )
}

@Composable
private fun TorrentCard(
    modifier: Modifier,
    onClick: () -> Unit,
    selected: Boolean?,
    displayName: String,
    type: String,
    category: String,
    totalEpisodes: Int,
    episodeRange: String,
    totalSize: String,
    sizeRange: String,
    seedersRange: String,
    downloadsRange: String,
    dateRange: String,
    metadata: String,
    showFiles: Boolean,
    files: List<String>,
    detailsLink: String
) {
    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        onClick = onClick,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (selected != null) {
                    RadioButton(selected = selected, onClick = onClick)
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = "$type • $category",
                        style = MaterialTheme.typography.titleSmall,
                    )
                }

                Text(
                    text = "$totalEpisodes серий ($episodeRange)\n$totalSize ($sizeRange)",
                    style = MaterialTheme.typography.labelLarge,
                    textAlign = TextAlign.End
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (metadata.isNotEmpty()) {
                        Text(
                            text = metadata,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }

                    Text(
                        text = "Сиды $seedersRange • Скачивания $downloadsRange • Залито $dateRange",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                TextButton(onClick = { openInBrowser(detailsLink) }) {
                    Text("Открыть")
                }
            }

            if (showFiles)
                ExpandableFilesSection(
                    files = files,
                    modifier = Modifier.padding(top = 2.dp),
                )
        }
    }
}

@Composable
private fun ExpandableFilesSection(
    files: List<String>,
    modifier: Modifier = Modifier,
) {
    if (files.isEmpty()) {
        return
    }

    var isExpanded by remember(files) { mutableStateOf(false) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Файлы (${files.size})",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )

            TextButton(onClick = { isExpanded = !isExpanded }) {
                Text(if (isExpanded) "Свернуть" else "Развернуть")
            }
        }

        if (isExpanded) {
            Text(
                text = files.joinToString(separator = "\n"),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun EpisodeSelectionSection(
    episodes: List<Int>,
    selectedEpisodes: Set<Int>,
    onEpisodeSelectionChanged: (Int, Boolean) -> Unit,
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
                    text = "Серии (${selectedEpisodes.size}/${episodes.size})",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )

                if (episodes.isNotEmpty()) {
                    TextButton(onClick = onSelectAll) {
                        Text("Выбрать все")
                    }

                    TextButton(onClick = onClearAll) {
                        Text("Снять все")
                    }
                }
            }

            if (episodes.isEmpty()) {
                Text(
                    text = "В выбранном торренте серии не найдены",
                    style = MaterialTheme.typography.bodyMedium
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
                        label = { Text("Серия $episodeNumber") },
                    )
                }
            }
        }
    }
}

private fun List<Int>.toEpisodeRangeLabel(): String {
    val sorted = sorted()
    val min = sorted.first()
    val max = sorted.last()
    return if (min == max) "$min" else "$min-$max"
}

private fun List<Long>.toSizeRangeLabel(): String {
    if (isEmpty()) {
        return "?"
    }

    val min = min()
    val max = max()
    return when {
        min == max -> min.toReadableSize()
        else -> "${min.toReadableSize()}-${max.toReadableSize()}"
    }
}

private fun List<Int>.toIntRangeLabel(): String {
    if (isEmpty()) {
        return "?"
    }

    val min = min()
    val max = max()
    return if (min == max) "$min" else "$min-$max"
}

private fun List<Instant>.toDateRangeLabel(): String {
    if (isEmpty()) {
        return "?"
    }

    val sorted = sorted()
    return "${sorted.first().toDisplayDate()} - ${sorted.last().toDisplayDate()}"
}

private fun Instant?.toDisplayDate(): String {
    val instant = this ?: return "?"
    return DateTimeFormatter.ofPattern("yyyy.MM.dd")
        .withZone(ZoneId.systemDefault())
        .format(instant)
}
