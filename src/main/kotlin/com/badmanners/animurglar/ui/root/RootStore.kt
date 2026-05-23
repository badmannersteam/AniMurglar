package com.badmanners.animurglar.ui.root

import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.coroutineBootstrapper
import com.arkivanov.mvikotlin.extensions.coroutines.coroutineExecutorFactory
import com.arkivanov.mvikotlin.extensions.coroutines.labels
import com.badmanners.animurglar.app.config.AppConfig
import com.badmanners.animurglar.downloader.DownloadRequest
import com.badmanners.animurglar.downloader.suggestEpisodeMapping
import com.badmanners.animurglar.dubs.DubInfo
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import java.awt.Desktop
import kotlin.io.path.createDirectories


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
    }

    data class State(
        val selectedAnime: ShikimoriAnime? = null,
        val pendingAnimeSelection: ShikimoriAnime? = null,
    )

    sealed interface Message {
        data class AnimeSelectionApplied(val anime: ShikimoriAnime) : Message
        data class AnimeSelectionPending(val anime: ShikimoriAnime) : Message
        data object PendingSelectionCleared : Message
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
            onAction<Action.DownloadCompleted> { action ->
                processingStore.accept(ProcessingStore.Intent.Start(batch = action.batch))
            }

            onAction<Action.ShikimoriAnimePicked> { action ->
                if (downloaderStore.state.isRunning || processingStore.state.isRunning) {
                    dispatch(Message.AnimeSelectionPending(anime = action.anime))
                } else {
                    applyAnimeSelection(anime = action.anime, cancelRunningExecution = false)
                }
            }

            onIntent<Intent.PendingSelectionConfirmed> {
                val pendingAnime = state().pendingAnimeSelection ?: return@onIntent
                applyAnimeSelection(anime = pendingAnime, cancelRunningExecution = true)
            }

            onIntent<Intent.PendingSelectionDismissed> {
                val selectedAnimeId = state().selectedAnime?.id
                shikimoriStore.accept(ShikimoriStore.Intent.RestoreSelection(animeId = selectedAnimeId))
                dispatch(Message.PendingSelectionCleared)
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
                )

                is Message.AnimeSelectionPending -> copy(
                    pendingAnimeSelection = message.anime,
                )

                Message.PendingSelectionCleared -> copy(
                    pendingAnimeSelection = null,
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
        nyaaPickerStore.accept(
            NyaaPickerStore.Intent.Search(
                queries = anime.nyaaQueries,
                episodesAired = anime.episodesAired ?: 0,
                isMovie = anime.isMovie,
            )
        )
        dubsPickerStore.accept(DubsPickerStore.Intent.Search(queries = anime.dubsQueries))
        subtitlesPickerStore.accept(SubtitlesPickerStore.Intent.Search(shikimoriUrl = anime.url))
    }

    private fun prepareDownloadRequest(
        rootState: State,
        nyaaState: NyaaPickerStore.State,
        dubsState: DubsPickerStore.State,
        subtitlesState: SubtitlesPickerStore.State,
        episodeMappingState: EpisodeMappingStore.State,
    ): DownloadRequest {
        val selectedAnime = checkNotNull(rootState.selectedAnime) { "Anime is not selected." }

        val selectedCandidate = checkNotNull(nyaaState.selectedCandidate()) { "Torrent candidate is not selected." }
        val selectedEpisodes = nyaaState.selectedEpisodes
        require(selectedEpisodes.isNotEmpty()) { "No episodes selected." }

        val selectedDubs = dubsState.selectedDubs()
        require(selectedDubs.isNotEmpty()) { "No dubs selected." }
        val episodeMapping = suggestEpisodeMapping(selectedEpisodes, episodeMappingState.dubStartOverride)

        return DownloadRequest(
            title = selectedAnime.russian,
            torrent = selectedCandidate,
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
        if (rootState.selectedAnime == null || nyaaState.isLoading || dubsState.isLoading || subtitlesState.isLoading || downloaderState.isRunning || processingState.isRunning) {
            return false
        }

        val hasCandidate = nyaaState.selectedCandidate() != null
        val hasEpisodes = nyaaState.selectedEpisodes.isNotEmpty()
        val hasDubs = dubsState.selectedDubs().isNotEmpty()
        return hasCandidate && hasEpisodes && hasDubs
    }
}
