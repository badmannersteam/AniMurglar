package com.badmanners.animurglar.ui.subtitles

import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.coroutineExecutorFactory
import com.badmanners.animurglar.subtitles.Anime365SubtitlesGateway
import com.badmanners.animurglar.subtitles.SubtitleTeamInfo
import com.badmanners.animurglar.ui.subtitles.SubtitlesPickerStore.Intent
import com.badmanners.animurglar.ui.subtitles.SubtitlesPickerStore.Label
import com.badmanners.animurglar.ui.subtitles.SubtitlesPickerStore.Message
import com.badmanners.animurglar.ui.subtitles.SubtitlesPickerStore.State
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.apache.logging.log4j.LogManager


interface SubtitlesPickerStore : Store<Intent, State, Label> {

    sealed interface Intent {
        data class Search(val shikimoriUrl: String) : Intent
        data object CancelSearch : Intent
        data class TeamSelectionChanged(val teamKey: String, val selected: Boolean) : Intent
        data object SelectAll : Intent
        data object ClearAll : Intent
        data object Reset : Intent
    }

    data class State(
        val isLoading: Boolean = false,
        val error: String? = null,
        val teams: List<SubtitleTeamInfo> = emptyList(),
        val selectedTeamKeys: Set<String> = emptySet(),
    )

    sealed interface Message {
        data object SearchStarted : Message
        data object SearchCancelled : Message
        data class SearchSuccess(val teams: List<SubtitleTeamInfo>) : Message
        data class SearchFailure(val errorMessage: String) : Message
        data class TeamSelectionChanged(val teamKey: String, val selected: Boolean) : Message
        data object SelectAll : Message
        data object ClearAll : Message
        data object ResetApplied : Message
    }

    sealed interface Label
}

class SubtitlesPickerStoreFactory(
    private val storeFactory: StoreFactory,
    private val subtitlesGateway: Anime365SubtitlesGateway,
) {
    fun create(): SubtitlesPickerStore = object : SubtitlesPickerStore, Store<Intent, State, Label>
    by storeFactory.create<Intent, Nothing, Message, State, Label>(
        name = "SubtitlesPickerStore",
        initialState = State(),
        executorFactory = coroutineExecutorFactory {
            val logger = LogManager.getLogger(SubtitlesPickerStore::class.java)

            var activeSearch: ActiveTask? = null
            var nextTaskId = 0L

            onIntent<Intent.Search> { intent ->
                val shikimoriUrl = intent.shikimoriUrl.trim()
                if (shikimoriUrl.isEmpty()) {
                    dispatch(Message.SearchFailure(errorMessage = "Не найдена ссылка Shikimori для поиска субтитров."))
                    return@onIntent
                }

                activeSearch?.job?.cancel(CancellationException("Superseded by a new subtitles search."))
                dispatch(Message.SearchStarted)

                val taskId = ++nextTaskId
                val job = launch {
                    try {
                        val teams = subtitlesGateway.loadTeams(shikimoriUrl)
                        if (activeSearch?.id != taskId) {
                            return@launch
                        }

                        dispatch(Message.SearchSuccess(teams = teams))
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (throwable: Throwable) {
                        if (activeSearch?.id != taskId) {
                            return@launch
                        }

                        logger.warn("Failed to load Anime365 subtitles: ${throwable.message}", throwable)
                        dispatch(
                            Message.SearchFailure(
                                errorMessage = throwable.message ?: "Не удалось загрузить субтитры Anime365."
                            )
                        )
                    }
                }
                activeSearch = ActiveTask(id = taskId, job = job)
            }

            onIntent<Intent.CancelSearch> {
                activeSearch?.job?.cancel(CancellationException("Subtitles search cancelled by user."))
                activeSearch = null
                dispatch(Message.SearchCancelled)
            }

            onIntent<Intent.TeamSelectionChanged> { intent ->
                dispatch(Message.TeamSelectionChanged(intent.teamKey, intent.selected))
            }

            onIntent<Intent.SelectAll> {
                dispatch(Message.SelectAll)
            }

            onIntent<Intent.ClearAll> {
                dispatch(Message.ClearAll)
            }

            onIntent<Intent.Reset> {
                activeSearch?.job?.cancel(CancellationException("Subtitles state reset."))
                activeSearch = null
                dispatch(Message.ResetApplied)
            }
        },
        reducer = { message ->
            when (message) {
                Message.SearchStarted -> copy(
                    isLoading = true,
                    error = null,
                    teams = emptyList(),
                    selectedTeamKeys = emptySet(),
                )

                Message.SearchCancelled -> copy(
                    isLoading = false,
                )

                is Message.SearchSuccess -> copy(
                    isLoading = false,
                    error = null,
                    teams = message.teams,
                    selectedTeamKeys = emptySet(),
                )

                is Message.SearchFailure -> copy(
                    isLoading = false,
                    error = message.errorMessage,
                    teams = emptyList(),
                    selectedTeamKeys = emptySet(),
                )

                is Message.TeamSelectionChanged -> {
                    if (teams.none { it.key == message.teamKey }) {
                        return@create this
                    }

                    val nextSelection = selectedTeamKeys.toMutableSet()
                    if (message.selected) {
                        nextSelection += message.teamKey
                    } else {
                        nextSelection -= message.teamKey
                    }
                    copy(selectedTeamKeys = nextSelection)
                }

                Message.SelectAll -> copy(
                    selectedTeamKeys = teams.mapTo(mutableSetOf()) { it.key },
                )

                Message.ClearAll -> copy(
                    selectedTeamKeys = emptySet(),
                )

                Message.ResetApplied -> State()
            }
        },
    ) {}

    private data class ActiveTask(
        val id: Long,
        val job: Job,
    )
}