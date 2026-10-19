package com.badmanners.animurglar.ui.root

import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.coroutineBootstrapper
import com.arkivanov.mvikotlin.extensions.coroutines.coroutineExecutorFactory
import com.arkivanov.mvikotlin.extensions.coroutines.labels
import com.badmanners.animurglar.app.config.AppConfig
import com.badmanners.animurglar.downloader.DownloadRequest
import com.badmanners.animurglar.downloader.RawVideoSource
import com.badmanners.animurglar.downloader.suggestEpisodeMapping
import com.badmanners.animurglar.dubs.DubInfo
import com.badmanners.animurglar.local.LocalRawFilesService
import com.badmanners.animurglar.nyaa.TorrentCandidate
import com.badmanners.animurglar.pipeline.DownloadBatch
import com.badmanners.animurglar.shikimori.ShikimoriAnime
import com.badmanners.animurglar.subtitles.SubtitleTeamInfo
import com.badmanners.animurglar.ui.downloader.DownloaderStore
import com.badmanners.animurglar.ui.dubs.DubsPickerStore
import com.badmanners.animurglar.ui.episodes.EpisodeMappingStore
import com.badmanners.animurglar.ui.nyaa.NyaaPickerStore
import com.badmanners.animurglar.ui.processing.ProcessingStore
import com.badmanners.animurglar.ui.root.RootStore.Action
import com.badmanners.animurglar.ui.root.RootStore.Intent
import com.badmanners.animurglar.ui.root.RootStore.Label
import com.badmanners.animurglar.ui.root.RootStore.Message
import com.badmanners.animurglar.ui.root.RootStore.State
import com.badmanners.animurglar.ui.shikimori.ShikimoriStore
import com.badmanners.animurglar.ui.subtitles.SubtitlesPickerStore
import com.badmanners.animurglar.utils.deleteRecursivelyIfExists
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.awt.Desktop
import java.nio.file.Path
import kotlin.io.path.createDirectories


enum class RawSourceKind {
    NYAA, LOCAL
}

interface RootStore : Store<Intent, State, Label> {

    sealed interface Action {
        data class DownloadCompleted(val batch: DownloadBatch) : Action
        data class ShikimoriAnimePicked(val anime: ShikimoriAnime) : Action
    }

    sealed interface Intent {
        data object StartPressed : Intent
        data object OpenOutputFolderPressed : Intent
        data object PurgeTempFolderPressed : Intent
        data object PendingSelectionConfirmed : Intent
        data object PendingSelectionDismissed : Intent
        data class RawSourceKindChanged(val kind: RawSourceKind) : Intent
        data class LocalFolderChanged(val folder: String) : Intent
        data object ScanLocalFolder : Intent
        data class LocalEpisodeSelectionChanged(val episodeNumber: Int, val selected: Boolean) : Intent
        data class AllLocalEpisodesSelectionChanged(val selected: Boolean) : Intent
    }

    data class State(
        val selectedAnime: ShikimoriAnime? = null,
        val pendingAnimeSelection: ShikimoriAnime? = null,
        val rawSourceKind: RawSourceKind = RawSourceKind.NYAA,
        val localFolder: String = "",
        val localFiles: Map<Int, Path> = emptyMap(),
        val selectedLocalEpisodes: Set<Int> = emptySet(),
        val isScanningLocalFolder: Boolean = false,
        val localFolderError: String? = null,
    )

    sealed interface Message {
        data class AnimeSelectionApplied(val anime: ShikimoriAnime) : Message
        data class AnimeSelectionPending(val anime: ShikimoriAnime) : Message
        data object PendingSelectionCleared : Message
        data class RawSourceKindChanged(val kind: RawSourceKind) : Message
        data class LocalFolderChanged(val folder: String) : Message
        data object LocalScanStarted : Message
        data class LocalScanSucceeded(val files: Map<Int, Path>) : Message
        data class LocalScanFailed(val error: String) : Message
        data class LocalEpisodeSelectionChanged(val episodeNumber: Int, val selected: Boolean) : Message
        data class AllLocalEpisodesSelectionChanged(val selected: Boolean) : Message
    }

    sealed interface Label
}

class RootStoreFactory(
    private val storeFactory: StoreFactory,
    private val appConfig: AppConfig,
    private val shikimoriStore: ShikimoriStore,
    private val nyaaPickerStore: NyaaPickerStore,
    private val dubsPickerStore: DubsPickerStore,
    private val subtitlesPickerStore: SubtitlesPickerStore,
    private val episodeMappingStore: EpisodeMappingStore,
    private val downloaderStore: DownloaderStore,
    private val processingStore: ProcessingStore,
    private val localRawFilesService: LocalRawFilesService,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    fun create(): RootStore = object : RootStore, Store<Intent, State, Label>
    by storeFactory.create<Intent, Action, Message, State, Label>(
        name = "RootStore",
        initialState = State(),
        bootstrapper = coroutineBootstrapper {
            launch {
                downloaderStore.labels.collect { label ->
                    when (label) {
                        is DownloaderStore.Label.DownloadCompleted -> {
                            dispatch(Action.DownloadCompleted(batch = label.batch))
                        }

                        else -> Unit
                    }
                }
            }

            launch {
                shikimoriStore.labels.collect { label ->
                    when (label) {
                        is ShikimoriStore.Label.AnimePicked -> {
                            dispatch(Action.ShikimoriAnimePicked(anime = label.anime))
                        }
                    }
                }
            }
        },
        executorFactory = coroutineExecutorFactory {
            var activeLocalScan: Job? = null

            fun isExecutionRunning() = downloaderStore.state.isRunning || processingStore.state.isRunning

            onAction<Action.DownloadCompleted> { action ->
                processingStore.accept(ProcessingStore.Intent.Start(batch = action.batch))
            }

            onAction<Action.ShikimoriAnimePicked> { action ->
                if (downloaderStore.state.isRunning || processingStore.state.isRunning) {
                    dispatch(Message.AnimeSelectionPending(anime = action.anime))
                } else {
                    activeLocalScan?.cancel()
                    applyAnimeSelection(anime = action.anime, cancelRunningExecution = false)
                }
            }

            onIntent<Intent.PendingSelectionConfirmed> {
                val pendingAnime = state().pendingAnimeSelection ?: return@onIntent
                activeLocalScan?.cancel()
                applyAnimeSelection(anime = pendingAnime, cancelRunningExecution = true)
            }

            onIntent<Intent.PendingSelectionDismissed> {
                val selectedAnimeId = state().selectedAnime?.id
                shikimoriStore.accept(ShikimoriStore.Intent.RestoreSelection(animeId = selectedAnimeId))
                dispatch(Message.PendingSelectionCleared)
            }

            onIntent<Intent.RawSourceKindChanged> { intent ->
                if (isExecutionRunning() || intent.kind == state().rawSourceKind) return@onIntent
                activeLocalScan?.cancel()
                dispatch(Message.RawSourceKindChanged(intent.kind))
                episodeMappingStore.accept(EpisodeMappingStore.Intent.Reset)
                when (intent.kind) {
                    RawSourceKind.LOCAL -> nyaaPickerStore.accept(NyaaPickerStore.Intent.CancelSearch)
                    RawSourceKind.NYAA -> state().selectedAnime?.let { anime ->
                        if (nyaaPickerStore.state.candidates.isEmpty()) {
                            searchNyaa(anime)
                        }
                    }
                }
            }

            onIntent<Intent.LocalFolderChanged> { intent ->
                if (isExecutionRunning()) return@onIntent
                activeLocalScan?.cancel()
                dispatch(Message.LocalFolderChanged(intent.folder))
                episodeMappingStore.accept(EpisodeMappingStore.Intent.Reset)
            }

            onIntent<Intent.ScanLocalFolder> {
                if (isExecutionRunning() || state().rawSourceKind != RawSourceKind.LOCAL) return@onIntent
                val anime = state().selectedAnime ?: return@onIntent
                val folder = state().localFolder
                activeLocalScan?.cancel()
                dispatch(Message.LocalScanStarted)
                activeLocalScan = launch {
                    try {
                        require(folder.isNotBlank()) { "Укажите папку с готовыми видеофайлами." }
                        val files = localRawFilesService.scan(Path.of(folder), isMovie = anime.isMovie)
                        dispatch(Message.LocalScanSucceeded(files))
                    } catch (exception: CancellationException) {
                        throw exception
                    } catch (exception: Exception) {
                        dispatch(Message.LocalScanFailed(exception.message ?: "Не удалось прочитать папку."))
                    }
                }
            }

            onIntent<Intent.LocalEpisodeSelectionChanged> { intent ->
                if (isExecutionRunning() || intent.episodeNumber !in state().localFiles) return@onIntent
                dispatch(Message.LocalEpisodeSelectionChanged(intent.episodeNumber, intent.selected))
            }

            onIntent<Intent.AllLocalEpisodesSelectionChanged> { intent ->
                if (isExecutionRunning()) return@onIntent
                dispatch(Message.AllLocalEpisodesSelectionChanged(intent.selected))
            }

            onIntent<Intent.StartPressed> {
                val rootState = state()
                val nyaaState = nyaaPickerStore.state
                val dubsState = dubsPickerStore.state
                val subtitlesState = subtitlesPickerStore.state
                val episodeMappingState = episodeMappingStore.state
                val downloaderState = downloaderStore.state
                val processingState = processingStore.state
                if (!isStartAvailable(rootState, nyaaState, dubsState, subtitlesState, downloaderState, processingState)) {
                    return@onIntent
                }

                val request = prepareDownloadRequest(
                    rootState = rootState,
                    nyaaState = nyaaState,
                    dubsState = dubsState,
                    subtitlesState = subtitlesState,
                    episodeMappingState = episodeMappingState,
                )

                downloaderStore.accept(DownloaderStore.Intent.Start(request = request))
            }

            onIntent<Intent.OpenOutputFolderPressed> {
                runCatching {
                    appConfig.outputDir.createDirectories()
                    if (Desktop.isDesktopSupported()) {
                        Desktop.getDesktop().open(appConfig.outputDir.toFile())
                    }
                }
            }

            onIntent<Intent.PurgeTempFolderPressed> {
                runCatching {
                    appConfig.tempDir.deleteRecursivelyIfExists()
                    appConfig.tempDir.createDirectories()
                }
            }
        },
        reducer = { message ->
            when (message) {
                is Message.AnimeSelectionApplied -> copy(
                    selectedAnime = message.anime,
                    pendingAnimeSelection = null,
                    localFolder = "",
                    localFiles = emptyMap(),
                    selectedLocalEpisodes = emptySet(),
                    isScanningLocalFolder = false,
                    localFolderError = null,
                )

                is Message.AnimeSelectionPending -> copy(
                    pendingAnimeSelection = message.anime,
                )

                Message.PendingSelectionCleared -> copy(
                    pendingAnimeSelection = null,
                )

                is Message.RawSourceKindChanged -> copy(
                    rawSourceKind = message.kind,
                    isScanningLocalFolder = false,
                )

                is Message.LocalFolderChanged -> copy(
                    localFolder = message.folder,
                    localFiles = emptyMap(),
                    selectedLocalEpisodes = emptySet(),
                    isScanningLocalFolder = false,
                    localFolderError = null,
                )

                Message.LocalScanStarted -> copy(
                    localFiles = emptyMap(),
                    selectedLocalEpisodes = emptySet(),
                    isScanningLocalFolder = true,
                    localFolderError = null,
                )

                is Message.LocalScanSucceeded -> copy(
                    localFiles = message.files,
                    selectedLocalEpisodes = message.files.keys.toSet(),
                    isScanningLocalFolder = false,
                )

                is Message.LocalScanFailed -> copy(
                    isScanningLocalFolder = false,
                    localFolderError = message.error,
                )

                is Message.LocalEpisodeSelectionChanged -> copy(
                    selectedLocalEpisodes = when {
                        message.selected -> selectedLocalEpisodes + message.episodeNumber
                        else -> selectedLocalEpisodes - message.episodeNumber
                    },
                )

                is Message.AllLocalEpisodesSelectionChanged -> copy(
                    selectedLocalEpisodes = when {
                        message.selected -> localFiles.keys.toSet()
                        else -> emptySet()
                    },
                )
            }
        },
    ) {}

    private fun com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutorScope<State, Message, Nothing, Label>.applyAnimeSelection(
        anime: ShikimoriAnime,
        cancelRunningExecution: Boolean,
    ) {
        nyaaPickerStore.accept(NyaaPickerStore.Intent.CancelSearch)
        dubsPickerStore.accept(DubsPickerStore.Intent.CancelSearch)
        subtitlesPickerStore.accept(SubtitlesPickerStore.Intent.CancelSearch)
        nyaaPickerStore.accept(NyaaPickerStore.Intent.Reset)
        dubsPickerStore.accept(DubsPickerStore.Intent.Reset)
        subtitlesPickerStore.accept(SubtitlesPickerStore.Intent.Reset)
        episodeMappingStore.accept(EpisodeMappingStore.Intent.Reset)

        if (cancelRunningExecution) {
            if (downloaderStore.state.isRunning) {
                downloaderStore.accept(DownloaderStore.Intent.CancelAll)
            }
            if (processingStore.state.isRunning) {
                processingStore.accept(ProcessingStore.Intent.CancelAll)
            }
        } else {
            downloaderStore.accept(DownloaderStore.Intent.Reset)
            processingStore.accept(ProcessingStore.Intent.Reset)
        }

        dispatch(Message.AnimeSelectionApplied(anime = anime))
        if (state().rawSourceKind == RawSourceKind.NYAA) {
            searchNyaa(anime)
        }
        dubsPickerStore.accept(DubsPickerStore.Intent.Search(queries = anime.dubsQueries))
        subtitlesPickerStore.accept(SubtitlesPickerStore.Intent.Search(shikimoriUrl = anime.url))
    }

    private fun searchNyaa(anime: ShikimoriAnime) = nyaaPickerStore.accept(
        NyaaPickerStore.Intent.Search(
            queries = anime.nyaaQueries,
            episodesAired = anime.episodesAired ?: 0,
            isMovie = anime.isMovie,
        )
    )

    private fun prepareDownloadRequest(
        rootState: State,
        nyaaState: NyaaPickerStore.State,
        dubsState: DubsPickerStore.State,
        subtitlesState: SubtitlesPickerStore.State,
        episodeMappingState: EpisodeMappingStore.State,
    ): DownloadRequest {
        val selectedAnime = checkNotNull(rootState.selectedAnime) { "Anime is not selected." }

        val rawSource = when (rootState.rawSourceKind) {
            RawSourceKind.NYAA -> RawVideoSource.Nyaa(
                checkNotNull(nyaaState.selectedCandidate()) { "Torrent candidate is not selected." }
            )
            RawSourceKind.LOCAL -> RawVideoSource.Local(rootState.localFiles)
        }
        val selectedEpisodes = rootState.selectedRawEpisodes(nyaaState)
        require(selectedEpisodes.isNotEmpty()) { "No episodes selected." }

        val selectedDubs = dubsState.selectedDubs()
        require(selectedDubs.isNotEmpty()) { "No dubs selected." }
        val episodeMapping = suggestEpisodeMapping(selectedEpisodes, episodeMappingState.dubStartOverride)

        return DownloadRequest(
            title = selectedAnime.russian,
            rawSource = rawSource,
            selectedEpisodes = selectedEpisodes,
            selectedDubs = selectedDubs,
            episodeMapping = episodeMapping,
            selectedSubtitles = subtitlesState.selectedSubtitles(),
        )
    }

    private fun NyaaPickerStore.State.selectedCandidate(): TorrentCandidate? {
        val key = selectedCandidateKey ?: return null
        return candidates.firstOrNull { it.key == key }
    }

    private fun DubsPickerStore.State.selectedDubs(): List<DubInfo> {
        return dubs.filter { it.key in selectedDubKeys }
    }

    private fun SubtitlesPickerStore.State.selectedSubtitles(): List<SubtitleTeamInfo> {
        return teams.filter { it.key in selectedTeamKeys }
    }

    private fun isStartAvailable(
        rootState: State,
        nyaaState: NyaaPickerStore.State,
        dubsState: DubsPickerStore.State,
        subtitlesState: SubtitlesPickerStore.State,
        downloaderState: DownloaderStore.State,
        processingState: ProcessingStore.State,
    ): Boolean {
        if (rootState.selectedAnime == null || dubsState.isLoading || subtitlesState.isLoading || downloaderState.isRunning || processingState.isRunning) {
            return false
        }

        val hasDubs = dubsState.selectedDubs().isNotEmpty()
        return rootState.isRawSelectionReady(nyaaState) && hasDubs
    }
}

fun State.selectedRawEpisodes(nyaaState: NyaaPickerStore.State) = when (rawSourceKind) {
    RawSourceKind.NYAA -> nyaaState.selectedEpisodes
    RawSourceKind.LOCAL -> selectedLocalEpisodes
}

fun State.isRawSelectionReady(nyaaState: NyaaPickerStore.State) = when (rawSourceKind) {
    RawSourceKind.NYAA -> !nyaaState.isLoading
        && nyaaState.candidates.any { it.key == nyaaState.selectedCandidateKey }
        && nyaaState.selectedEpisodes.isNotEmpty()

    RawSourceKind.LOCAL -> !isScanningLocalFolder && selectedLocalEpisodes.isNotEmpty()
}
