package com.badmanners.animurglar.ui.dubs

import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.coroutineExecutorFactory
import com.badmanners.animurglar.dubs.DubAnimeCandidate
import com.badmanners.animurglar.dubs.DubInfo
import com.badmanners.animurglar.dubs.DubsGateway
import com.badmanners.animurglar.ui.dubs.DubsPickerStore.Intent
import com.badmanners.animurglar.ui.dubs.DubsPickerStore.Label
import com.badmanners.animurglar.ui.dubs.DubsPickerStore.Message
import com.badmanners.animurglar.ui.dubs.DubsPickerStore.State
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.apache.logging.log4j.LogManager


interface DubsPickerStore : Store<Intent, State, Label> {

    sealed interface Intent {
        data class Search(val queries: List<String>) : Intent
        data object CancelSearch : Intent
        data object AnimePickerDialogOpened : Intent
        data object AnimePickerDialogDismissed : Intent
        data class AnimeSelected(val candidateKey: String) : Intent
        data class DubSelectionChanged(val dubKey: String, val selected: Boolean) : Intent
        data object Reset : Intent
    }

    data class State(
        val isLoading: Boolean = false,
        val error: String? = null,
        val animeCandidates: List<DubAnimeCandidate> = emptyList(),
        val selectedAnimeKey: String? = null,
        val isAnimePickerDialogOpen: Boolean = false,
        val dubs: List<DubInfo> = emptyList(),
        val selectedDubKeys: Set<String> = emptySet(),
    )

    sealed interface Message {
        data object SearchStarted : Message
        data object SearchCancelled : Message
        data class SearchSuccess(val candidates: List<DubAnimeCandidate>) : Message
        data class SearchFailure(val errorMessage: String) : Message
        data class AnimeSelected(val candidateKey: String) : Message
        data object DubsLoadingStarted : Message
        data class DubsLoadingSuccess(val dubs: List<DubInfo>) : Message
        data class DubsLoadingFailure(val errorMessage: String) : Message
        data object AnimePickerDialogOpened : Message
        data object AnimePickerDialogDismissed : Message
        data class DubSelectionChanged(val dubKey: String, val selected: Boolean) : Message
        data object ResetApplied : Message
    }

    sealed interface Label
}

class DubsPickerStoreFactory(
    private val storeFactory: StoreFactory,
    private val dubsGateway: DubsGateway
) {
    fun create(): DubsPickerStore = object : DubsPickerStore, Store<Intent, State, Label>
    by storeFactory.create<Intent, Nothing, Message, State, Label>(
        name = "DubsPickerStore",
        initialState = State(),
        executorFactory = coroutineExecutorFactory {
            val logger = LogManager.getLogger(DubsPickerStore::class.java)

            var activeAnimeSearch: ActiveTask? = null
            var activeDubsLoad: ActiveTask? = null
            var nextTaskId = 0L

            onIntent<Intent.Search> { intent ->
                val queries = intent.queries
                    .map(String::trim)
                    .filter(String::isNotBlank)
                    .distinctBy(String::lowercase)
                if (queries.isEmpty()) {
                    dispatch(Message.SearchFailure(errorMessage = "Введите название аниме."))
                    return@onIntent
                }

                activeAnimeSearch?.job?.cancel(CancellationException("Superseded by a new dubs search."))
                activeDubsLoad?.job?.cancel(CancellationException("Superseded by a new dubs search."))
                dispatch(Message.SearchStarted)

                val taskId = ++nextTaskId
                val job = launch {
                    try {
                        val candidates = dubsGateway.searchAnime(queries)
                        if (activeAnimeSearch?.id != taskId)
                            return@launch

                        dispatch(Message.SearchSuccess(candidates = candidates))

                        val topSimilarCandidate = candidates.firstOrNull() ?: return@launch
                        if (activeAnimeSearch?.id != taskId) {
                            return@launch
                        }

                        dispatch(Message.AnimeSelected(candidateKey = topSimilarCandidate.key))
                        dispatch(Message.DubsLoadingStarted)

                        activeDubsLoad?.job?.cancel(CancellationException("Superseded by a new dubs candidate selection."))
                        val loadTaskId = ++nextTaskId
                        val loadJob = launch {
                            try {
                                val dubs = dubsGateway.loadDubs(topSimilarCandidate)
                                if (activeDubsLoad?.id != loadTaskId) {
                                    return@launch
                                }

                                dispatch(Message.DubsLoadingSuccess(dubs = dubs))
                            } catch (_: CancellationException) {
                                if (activeDubsLoad?.id == loadTaskId) {
                                    dispatch(Message.SearchCancelled)
                                }
                            } catch (throwable: Throwable) {
                                if (activeDubsLoad?.id != loadTaskId) {
                                    return@launch
                                }

                                logger.warn("Dubs loading failed", throwable)
                                dispatch(
                                    Message.DubsLoadingFailure(
                                        errorMessage = throwable.message ?: "Не удалось загрузить озвучки",
                                    )
                                )
                            }
                        }

                        activeDubsLoad = ActiveTask(id = loadTaskId, job = loadJob)
                    } catch (_: CancellationException) {
                        if (activeAnimeSearch?.id == taskId) {
                            dispatch(Message.SearchCancelled)
                        }
                    } catch (throwable: Throwable) {
                        if (activeAnimeSearch?.id != taskId) {
                            return@launch
                        }

                        logger.warn("Dubs search failed", throwable)
                        dispatch(
                            Message.SearchFailure(
                                errorMessage = throwable.message ?: "Не удалось загрузить озвучки",
                            )
                        )
                    }
                }

                activeAnimeSearch = ActiveTask(id = taskId, job = job)
            }

            onIntent<Intent.CancelSearch> {
                activeAnimeSearch?.job?.cancel(CancellationException("Dubs search cancelled by user."))
                activeAnimeSearch = null
                activeDubsLoad?.job?.cancel(CancellationException("Dubs loading cancelled by user."))
                activeDubsLoad = null
                dispatch(Message.SearchCancelled)
            }

            onIntent<Intent.AnimePickerDialogOpened> {
                dispatch(Message.AnimePickerDialogOpened)
            }

            onIntent<Intent.AnimePickerDialogDismissed> {
                dispatch(Message.AnimePickerDialogDismissed)
            }

            onIntent<Intent.AnimeSelected> { intent ->
                val candidate = state().animeCandidates.firstOrNull { it.key == intent.candidateKey } ?: return@onIntent
                dispatch(Message.AnimeSelected(candidateKey = candidate.key))
                dispatch(Message.DubsLoadingStarted)

                activeDubsLoad?.job?.cancel(CancellationException("Superseded by a new dubs candidate selection."))
                val taskId = ++nextTaskId
                val job = launch {
                    try {
                        val dubs = dubsGateway.loadDubs(candidate)
                        if (activeDubsLoad?.id != taskId) {
                            return@launch
                        }

                        dispatch(Message.DubsLoadingSuccess(dubs = dubs))
                    } catch (_: CancellationException) {
                        if (activeDubsLoad?.id == taskId) {
                            dispatch(Message.SearchCancelled)
                        }
                    } catch (throwable: Throwable) {
                        if (activeDubsLoad?.id != taskId) {
                            return@launch
                        }

                        logger.warn("Dubs loading failed", throwable)
                        dispatch(
                            Message.DubsLoadingFailure(
                                errorMessage = throwable.message ?: "Не удалось загрузить озвучки",
                            )
                        )
                    }
                }

                activeDubsLoad = ActiveTask(id = taskId, job = job)
            }

            onIntent<Intent.DubSelectionChanged> { intent ->
                dispatch(
                    Message.DubSelectionChanged(
                        dubKey = intent.dubKey,
                        selected = intent.selected,
                    )
                )
            }

            onIntent<Intent.Reset> {
                activeAnimeSearch?.job?.cancel(CancellationException("Dubs state reset."))
                activeAnimeSearch = null
                activeDubsLoad?.job?.cancel(CancellationException("Dubs state reset."))
                activeDubsLoad = null
                dispatch(Message.ResetApplied)
            }
        },
        reducer = { message ->
            when (message) {
                Message.SearchStarted -> copy(
                    isLoading = true,
                    error = null,
                    animeCandidates = emptyList(),
                    selectedAnimeKey = null,
                    isAnimePickerDialogOpen = false,
                    dubs = emptyList(),
                    selectedDubKeys = emptySet(),
                )

                Message.SearchCancelled -> copy(
                    isLoading = false,
                )

                is Message.SearchSuccess -> copy(
                    isLoading = false,
                    error = null,
                    animeCandidates = message.candidates,
                    selectedAnimeKey = null,
                    isAnimePickerDialogOpen = false,
                    dubs = emptyList(),
                    selectedDubKeys = emptySet(),
                )

                is Message.SearchFailure -> copy(
                    isLoading = false,
                    error = message.errorMessage,
                    animeCandidates = emptyList(),
                    selectedAnimeKey = null,
                    isAnimePickerDialogOpen = false,
                    dubs = emptyList(),
                    selectedDubKeys = emptySet(),
                )

                is Message.AnimeSelected -> copy(
                    selectedAnimeKey = message.candidateKey,
                    isAnimePickerDialogOpen = false,
                    dubs = emptyList(),
                    selectedDubKeys = emptySet(),
                )

                Message.DubsLoadingStarted -> copy(
                    isLoading = true,
                    error = null,
                    dubs = emptyList(),
                    selectedDubKeys = emptySet(),
                )

                is Message.DubsLoadingSuccess -> copy(
                    isLoading = false,
                    error = null,
                    dubs = message.dubs,
                    selectedDubKeys = emptySet(),
                )

                is Message.DubsLoadingFailure -> copy(
                    isLoading = false,
                    error = message.errorMessage,
                    dubs = emptyList(),
                    selectedDubKeys = emptySet(),
                )

                Message.AnimePickerDialogOpened -> copy(
                    isAnimePickerDialogOpen = animeCandidates.isNotEmpty(),
                )

                Message.AnimePickerDialogDismissed -> copy(
                    isAnimePickerDialogOpen = false,
                )

                is Message.DubSelectionChanged -> {
                    if (dubs.none { it.key == message.dubKey }) {
                        return@create this
                    }

                    val nextSelection = selectedDubKeys.toMutableSet()
                    if (message.selected) {
                        nextSelection += message.dubKey
                    } else {
                        nextSelection -= message.dubKey
                    }
                    copy(selectedDubKeys = nextSelection)
                }

                Message.ResetApplied -> State()
            }
        },
    ) {}

    private data class ActiveTask(
        val id: Long,
        val job: Job,
    )
}