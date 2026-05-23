package com.badmanners.animurglar.ui.root

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.arkivanov.mvikotlin.extensions.coroutines.stateFlow
import com.badmanners.animurglar.ui.downloader.DownloaderScreen
import com.badmanners.animurglar.ui.downloader.DownloaderStore
import com.badmanners.animurglar.ui.dubs.DubsPickerScreen
import com.badmanners.animurglar.ui.dubs.DubsPickerStore
import com.badmanners.animurglar.ui.episodes.EpisodeMappingScreen
import com.badmanners.animurglar.ui.episodes.EpisodeMappingStore
import com.badmanners.animurglar.ui.nyaa.NyaaPickerScreen
import com.badmanners.animurglar.ui.nyaa.NyaaPickerStore
import com.badmanners.animurglar.ui.processing.ProcessingScreen
import com.badmanners.animurglar.ui.processing.ProcessingStore
import com.badmanners.animurglar.ui.shikimori.ShikimoriScreen
import com.badmanners.animurglar.ui.shikimori.ShikimoriStore
import com.badmanners.animurglar.ui.subtitles.SubtitlesPickerScreen
import com.badmanners.animurglar.ui.subtitles.SubtitlesPickerStore
import kotlinx.coroutines.ExperimentalCoroutinesApi


@OptIn(ExperimentalCoroutinesApi::class)
@Composable
fun RootScreen(
    rootStore: RootStore,
    shikimoriStore: ShikimoriStore,
    nyaaPickerStore: NyaaPickerStore,
    dubsPickerStore: DubsPickerStore,
    subtitlesPickerStore: SubtitlesPickerStore,
    episodeMappingStore: EpisodeMappingStore,
    downloaderStore: DownloaderStore,
    processingStore: ProcessingStore,
) {
    val rootState by rootStore.stateFlow.collectAsState()
    val shikimoriState by shikimoriStore.stateFlow.collectAsState()
    val nyaaState by nyaaPickerStore.stateFlow.collectAsState()
    val dubsState by dubsPickerStore.stateFlow.collectAsState()
    val subtitlesState by subtitlesPickerStore.stateFlow.collectAsState()
    val downloaderState by downloaderStore.stateFlow.collectAsState()
    val processingState by processingStore.stateFlow.collectAsState()
    val isExecutionRunning = downloaderState.isRunning || processingState.isRunning
    val isStartVisible = !shikimoriState.isLoading
        && !nyaaState.isLoading
        && !dubsState.isLoading
        && !subtitlesState.isLoading
        && !isExecutionRunning
        && rootState.selectedAnime != null
        && nyaaState.selectedCandidateKey != null
        && nyaaState.selectedEpisodes.isNotEmpty()
        && dubsState.selectedDubKeys.isNotEmpty()

    var selectedTab by remember { mutableStateOf(0) }
    LaunchedEffect(shikimoriState.selectedAnimeId) {
        selectedTab = 0
    }
    LaunchedEffect(isExecutionRunning) {
        if (isExecutionRunning) {
            selectedTab = 1
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                Column(modifier = Modifier.fillMaxWidth().weight(1f, fill = true)) {
                    ShikimoriScreen(
                        shikimoriStore = shikimoriStore,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    if (shikimoriState.selectedAnimeId != null) {
                        PrimaryTabRow(containerColor = Color.Transparent, selectedTabIndex = selectedTab) {
                            Tab(
                                selected = selectedTab == 0,
                                onClick = { selectedTab = 0 },
                                text = { Text("Выбор", maxLines = 1) }
                            )
                            Tab(
                                selected = selectedTab == 1,
                                onClick = { selectedTab = 1 },
                                text = { Text("Загрузка и обработка", maxLines = 1) },
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (selectedTab == 0) {
                                Column(modifier = Modifier.fillMaxHeight().weight(1f)) {
                                    NyaaPickerScreen(
                                        nyaaPickerStore = nyaaPickerStore,
                                        modifier = Modifier.weight(1f, fill = false),
                                    )

                                    EpisodeMappingScreen(
                                        episodeMappingStore = episodeMappingStore,
                                        selectedTorrentEpisodes = nyaaState.selectedEpisodes,
                                        modifier = Modifier.padding(top = 12.dp),
                                    )
                                }

                                Column(modifier = Modifier.fillMaxHeight().weight(1f)) {
                                    DubsPickerScreen(
                                        dubsPickerStore = dubsPickerStore,
                                        modifier = Modifier.weight(0.7f, fill = false),
                                    )
                                    Spacer(Modifier.height(12.dp))
                                    SubtitlesPickerScreen(
                                        subtitlesPickerStore = subtitlesPickerStore,
                                        modifier = Modifier.weight(0.3f, fill = false),
                                    )
                                }
                            } else {
                                DownloaderScreen(
                                    downloaderStore = downloaderStore,
                                    modifier = Modifier.fillMaxHeight().weight(1f),
                                )

                                ProcessingScreen(
                                    processingStore = processingStore,
                                    modifier = Modifier.fillMaxHeight().weight(1f),
                                )
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.align(Alignment.End).padding(top = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (isStartVisible)
                        FloatingActionButton({ rootStore.accept(RootStore.Intent.StartPressed) }) {
                            Text("Скачать, синхронизировать звук и объединить", Modifier.padding(horizontal = 16.dp))
                        }

                    FloatingActionButton({ rootStore.accept(RootStore.Intent.OpenOutputFolderPressed) }) {
                        Text("Открыть папку вывода", Modifier.padding(horizontal = 16.dp))
                    }

                    FloatingActionButton({ rootStore.accept(RootStore.Intent.PurgeTempFolderPressed) }) {
                        Text("Очистить временную папку", Modifier.padding(horizontal = 16.dp))
                    }
                }
            }

            rootState.pendingAnimeSelection?.let {
                AlertDialog(
                    onDismissRequest = { rootStore.accept(RootStore.Intent.PendingSelectionDismissed) },
                    title = { Text("Отменить текущие операции?") },
                    text = { Text("Если продолжить, текущая загрузка/обработка будет отменена.") },
                    confirmButton = {
                        Button(onClick = { rootStore.accept(RootStore.Intent.PendingSelectionConfirmed) }) {
                            Text("Да")
                        }
                    },
                    dismissButton = {
                        OutlinedButton(onClick = { rootStore.accept(RootStore.Intent.PendingSelectionDismissed) }) {
                            Text("Нет")
                        }
                    },
                )
            }
        }
    }
}
