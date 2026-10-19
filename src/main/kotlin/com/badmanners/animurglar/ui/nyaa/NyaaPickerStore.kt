package com.badmanners.animurglar.ui.nyaa

import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.coroutineExecutorFactory
import com.badmanners.animurglar.media.RawSeason
import com.badmanners.animurglar.nyaa.NyaaGateway
import com.badmanners.animurglar.nyaa.NyaaSearchProgress
import com.badmanners.animurglar.nyaa.TorrentCandidate
import com.badmanners.animurglar.ui.nyaa.NyaaPickerStore.Intent
import com.badmanners.animurglar.ui.nyaa.NyaaPickerStore.Label
import com.badmanners.animurglar.ui.nyaa.NyaaPickerStore.State
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.apache.logging.log4j.LogManager


interface NyaaPickerStore : Store<Intent, State, Label> {

    sealed interface Intent {
        data class Search(
            val queries: List<String>,
            val episodesAired: Int,
            val isMovie: Boolean,
        ) : Intent

        data object CancelSearch : Intent
        data class CandidateSelected(val candidateKey: String) : Intent
        data class SeasonSelected(val season: RawSeason) : Intent
        data class EpisodeSelectionChanged(val episodeNumber: Int, val selected: Boolean) : Intent
        data object PickerDialogOpened : Intent
        data object PickerDialogDismissed : Intent
        data object Reset : Intent
    }

    data class State(
        val isLoading: Boolean = false,
        val progressCurrent: Int = 0,
        val progressTotal: Int = 0,
        val error: String? = null,
        val candidates: List<TorrentCandidate> = emptyList(),
        val selectedCandidateKey: String? = null,
        val selectedSeason: RawSeason? = null,
        val selectedEpisodes: Set<Int> = emptySet(),
        val isPickerDialogOpen: Boolean = false,
    )

    sealed interface Message {
        data object SearchStarted : Message
        data object SearchCancelled : Message
        data object ResetApplied : Message
        data class ProgressUpdated(val progress: NyaaSearchProgress) : Message
        data class SearchSuccess(val candidates: List<TorrentCandidate>) : Message
        data class SearchFailure(val errorMessage: String) : Message
        data class CandidateSelected(val candidateKey: String) : Message
        data class SeasonSelected(val season: RawSeason) : Message
        data class EpisodeSelectionChanged(val episodeNumber: Int, val selected: Boolean) : Message
        data object PickerDialogOpened : Message
        data object PickerDialogDismissed : Message
    }

    sealed interface Label
}

class NyaaPickerStoreFactory(
    private val storeFactory: StoreFactory,
    private val nyaaGateway: NyaaGateway,
) {
    fun create(): NyaaPickerStore = object : NyaaPickerStore, Store<Intent, State, Label>
    by storeFactory.create<Intent, Nothing, NyaaPickerStore.Message, State, Label>(
        name = "NyaaPickerStore",
        initialState = State(),
        executorFactory = coroutineExecutorFactory {
            val logger = LogManager.getLogger(NyaaPickerStore::class.java)

            var activeSearch: ActiveSearch? = null
            var nextSearchId = 0L

            onIntent<Intent.Search> { intent ->
                val queries = intent.queries
                val episodesAired = intent.episodesAired
                val isMovie = intent.isMovie
                if (queries.isEmpty()) {
                    dispatch(NyaaPickerStore.Message.SearchFailure(errorMessage = "Введите название аниме."))
                    return@onIntent
                }

                activeSearch?.job?.cancel(CancellationException("Superseded by a new Nyaa search."))
                dispatch(NyaaPickerStore.Message.SearchStarted)

                val searchId = ++nextSearchId
                val job = launch {
                    try {
                        val candidates = nyaaGateway.search(
                            queries = queries,
                            episodesAired = episodesAired,
                            isMovie = isMovie,
                            onProgress = { progress ->
                                if (activeSearch?.id == searchId) {
                                    dispatch(NyaaPickerStore.Message.ProgressUpdated(progress = progress))
                                }
                            },
                        )

                        if (activeSearch?.id != searchId) {
                            return@launch
                        }

                        dispatch(NyaaPickerStore.Message.SearchSuccess(candidates = candidates))
                    } catch (_: CancellationException) {
                        if (activeSearch?.id == searchId) {
                            dispatch(NyaaPickerStore.Message.SearchCancelled)
                        }
                    } catch (throwable: Throwable) {
                        if (activeSearch?.id != searchId) {
                            return@launch
                        }

                        logger.warn("Nyaa search failed", throwable)
                        dispatch(
                            NyaaPickerStore.Message.SearchFailure(
                                errorMessage = throwable.message ?: "Не удалось загрузить торренты",
                            )
                        )
                    }
                }
                activeSearch = ActiveSearch(id = searchId, job = job)
            }

            onIntent<Intent.CancelSearch> {
                val runningSearch = activeSearch ?: return@onIntent
                activeSearch = null
                runningSearch.job.cancel(CancellationException("Nyaa search cancelled by user."))
                dispatch(NyaaPickerStore.Message.SearchCancelled)
            }

            onIntent<Intent.CandidateSelected> { intent ->
                dispatch(NyaaPickerStore.Message.CandidateSelected(candidateKey = intent.candidateKey))
            }

            onIntent<Intent.SeasonSelected> { intent ->
                dispatch(NyaaPickerStore.Message.SeasonSelected(season = intent.season))
            }

            onIntent<Intent.EpisodeSelectionChanged> { intent ->
                dispatch(
                    NyaaPickerStore.Message.EpisodeSelectionChanged(
                        episodeNumber = intent.episodeNumber,
                        selected = intent.selected,
                    )
                )
            }

            onIntent<Intent.PickerDialogOpened> {
                dispatch(NyaaPickerStore.Message.PickerDialogOpened)
            }

            onIntent<Intent.PickerDialogDismissed> {
                dispatch(NyaaPickerStore.Message.PickerDialogDismissed)
            }

            onIntent<Intent.Reset> {
                activeSearch?.job?.cancel(CancellationException("Nyaa state reset."))
                activeSearch = null
                dispatch(NyaaPickerStore.Message.ResetApplied)
            }
        },
        reducer = { message ->
            when (message) {
                NyaaPickerStore.Message.SearchStarted -> copy(
                    isLoading = true,
                    progressCurrent = 0,
                    progressTotal = 0,
                    error = null,
                    candidates = emptyList(),
                    selectedCandidateKey = null,
                    selectedSeason = null,
                    selectedEpisodes = emptySet(),
                    isPickerDialogOpen = false,
                )

                NyaaPickerStore.Message.SearchCancelled -> copy(
                    isLoading = false,
                    error = null,
                )

                is NyaaPickerStore.Message.ProgressUpdated -> {
                    val total = message.progress.totalRequests.coerceAtLeast(0)
                    val current = message.progress.currentRequest.coerceIn(0, total)
                    copy(
                        progressCurrent = current,
                        progressTotal = total,
                    )
                }

                is NyaaPickerStore.Message.SearchSuccess -> copy(
                    isLoading = false,
                    progressCurrent = progressTotal,
                    error = null,
                    candidates = message.candidates,
                    isPickerDialogOpen = false,
                ).withCandidate(message.candidates.firstOrNull())

                is NyaaPickerStore.Message.SearchFailure -> copy(
                    isLoading = false,
                    error = message.errorMessage,
                    candidates = emptyList(),
                    selectedCandidateKey = null,
                    selectedSeason = null,
                    selectedEpisodes = emptySet(),
                    isPickerDialogOpen = false,
                )

                is NyaaPickerStore.Message.CandidateSelected -> withCandidate(
                    candidates.firstOrNull { it.key == message.candidateKey }
                )

                is NyaaPickerStore.Message.SeasonSelected -> {
                    val candidate = candidates.firstOrNull { it.key == selectedCandidateKey }
                    when {
                        candidate !is TorrentCandidate.SeasonPack
                                || message.season !in candidate.seasons
                                || message.season == selectedSeason -> this

                        else -> copy(
                            selectedSeason = message.season,
                            selectedEpisodes = candidate.selectSeason(message.season).episodes.toSet(),
                        )
                    }
                }

                is NyaaPickerStore.Message.EpisodeSelectionChanged -> {
                    val availableEpisodes = selectedCandidate()?.episodes ?: emptyList()
                    if (message.episodeNumber !in availableEpisodes) {
                        return@create this
                    }

                    copy(
                        selectedEpisodes = when {
                            message.selected -> selectedEpisodes + message.episodeNumber
                            else -> selectedEpisodes - message.episodeNumber
                        },
                    )
                }

                NyaaPickerStore.Message.PickerDialogOpened -> {
                    copy(isPickerDialogOpen = candidates.isNotEmpty())
                }

                NyaaPickerStore.Message.PickerDialogDismissed -> copy(isPickerDialogOpen = false)

                NyaaPickerStore.Message.ResetApplied -> State()
            }
        },
    ) {}

    private fun State.withCandidate(candidate: TorrentCandidate?): State {
        val nextState = copy(
            selectedCandidateKey = candidate?.key,
            selectedSeason = (candidate as? TorrentCandidate.SeasonPack)?.seasons?.firstOrNull(),
            selectedEpisodes = emptySet(),
        )
        return nextState.copy(selectedEpisodes = nextState.selectedCandidate()?.episodes?.toSet() ?: emptySet())
    }

    private data class ActiveSearch(
        val id: Long,
        val job: Job,
    )
}

fun State.selectedCandidate(): TorrentCandidate? {
    val candidate = candidates.firstOrNull { it.key == selectedCandidateKey } ?: return null
    return when (candidate) {
        is TorrentCandidate.SeasonPack -> {
            val season = selectedSeason ?: candidate.seasons.singleOrNull() ?: return null
            when (season) {
                in candidate.seasons -> candidate.selectSeason(season)
                else -> null
            }
        }

        is TorrentCandidate.EpisodeGroup -> candidate
    }
}
