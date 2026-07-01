package com.badmanners.animurglar.ui.shikimori

import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.coroutineExecutorFactory
import com.badmanners.animurglar.shikimori.ShikimoriAnime
import com.badmanners.animurglar.shikimori.ShikimoriGateway
import com.badmanners.animurglar.ui.shikimori.ShikimoriStore.Intent
import com.badmanners.animurglar.ui.shikimori.ShikimoriStore.Label
import com.badmanners.animurglar.ui.shikimori.ShikimoriStore.Message
import com.badmanners.animurglar.ui.shikimori.ShikimoriStore.State
import com.badmanners.animurglar.utils.SearchHistory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.apache.logging.log4j.LogManager


interface ShikimoriStore : Store<Intent, State, Label> {

    sealed interface Intent {
        data class QueryChanged(val value: String) : Intent
        data class SearchPressed(val query: String) : Intent
        data class AnimePicked(val animeId: String) : Intent
        data class SearchBarActiveChanged(val active: Boolean) : Intent
        data class RestoreSelection(val animeId: String?) : Intent
        data object SelectionCleared : Intent
    }

    data class State(
        val query: String = "",
        val isLoading: Boolean = false,
        val error: String? = null,
        val searchResults: List<ShikimoriAnime> = emptyList(),
        val selectedAnimeId: String? = null,
        val isSearchBarActive: Boolean = false,
        val searchSuggestions: List<String> = emptyList(),
    )

    sealed interface Message {
        data class QueryUpdated(val value: String) : Message
        data object SearchStarted : Message
        data class SearchSuccess(val results: List<ShikimoriAnime>) : Message
        data class SearchFailure(val errorMessage: String) : Message
        data class AnimePicked(val anime: ShikimoriAnime) : Message
        data class SearchBarActiveChanged(val active: Boolean) : Message
        data class SelectionRestored(val animeId: String?) : Message
        data class SuggestionsUpdated(val suggestions: List<String>) : Message
        data object SelectionCleared : Message
    }

    sealed interface Label {
        data class AnimePicked(val anime: ShikimoriAnime) : Label
    }
}

class ShikimoriStoreFactory(
    private val storeFactory: StoreFactory,
    private val shikimoriGateway: ShikimoriGateway,
    private val searchHistory: SearchHistory,
) {

    fun create(): ShikimoriStore = object : ShikimoriStore, Store<Intent, State, Label>
    by storeFactory.create<Intent, Nothing, Message, State, Label>(
        name = "ShikimoriStore",
        initialState = State(),
        executorFactory = coroutineExecutorFactory {
            val logger = LogManager.getLogger(ShikimoriStore::class.java)

            var activeSearch: ActiveSearch? = null
            var nextSearchId = 0L

            onIntent<Intent.QueryChanged> { intent ->
                dispatch(Message.QueryUpdated(value = intent.value))
                val suggestions = searchHistory.suggestions(intent.value)
                dispatch(Message.SuggestionsUpdated(suggestions = suggestions))
            }

            onIntent<Intent.SearchPressed> { intent ->
                val query = intent.query.trim()
                if (query.length < MIN_QUERY_LENGTH) {
                    dispatch(Message.SearchFailure(errorMessage = "Введите минимум $MIN_QUERY_LENGTH символа."))
                    return@onIntent
                }

                searchHistory.record(query)

                activeSearch?.job?.cancel(CancellationException("Superseded by a new Shikimori search."))
                dispatch(Message.SearchStarted)

                val searchId = ++nextSearchId
                val job = launch {
                    try {
                        val results = shikimoriGateway.search(query = query)
                        if (activeSearch?.id != searchId) {
                            return@launch
                        }

                        dispatch(Message.SearchSuccess(results = results))
                    } catch (_: CancellationException) {
                        // no-op
                    } catch (throwable: Throwable) {
                        if (activeSearch?.id != searchId) {
                            return@launch
                        }

                        logger.warn("Shikimori search failed", throwable)
                        dispatch(
                            Message.SearchFailure(
                                errorMessage = throwable.message ?: "Не удалось найти аниме на Shikimori",
                            )
                        )
                    }
                }

                activeSearch = ActiveSearch(id = searchId, job = job)
            }

            onIntent<Intent.AnimePicked> { intent ->
                val anime = state().searchResults.firstOrNull { it.id == intent.animeId } ?: return@onIntent
                dispatch(Message.AnimePicked(anime = anime))
                publish(Label.AnimePicked(anime = anime))
            }

            onIntent<Intent.SearchBarActiveChanged> { intent ->
                dispatch(Message.SearchBarActiveChanged(active = intent.active))
                if (intent.active) {
                    dispatch(Message.SuggestionsUpdated(suggestions = searchHistory.recent()))
                } else {
                    dispatch(Message.SuggestionsUpdated(suggestions = emptyList()))
                }
            }

            onIntent<Intent.RestoreSelection> { intent ->
                dispatch(Message.SelectionRestored(animeId = intent.animeId))
            }

            onIntent<Intent.SelectionCleared> {
                dispatch(Message.SelectionCleared)
            }
        },
        reducer = { message ->
            when (message) {
                is Message.QueryUpdated -> copy(
                    query = message.value,
                    error = null,
                )

                Message.SearchStarted -> copy(
                    isLoading = true,
                    error = null,
                    searchResults = emptyList(),
                    selectedAnimeId = null,
                    isSearchBarActive = true,
                    searchSuggestions = emptyList(),
                )

                is Message.SearchSuccess -> copy(
                    isLoading = false,
                    error = null,
                    searchResults = message.results,
                    selectedAnimeId = selectedAnimeId?.takeIf { selectedId ->
                        message.results.any { it.id == selectedId }
                    },
                    isSearchBarActive = true,
                    searchSuggestions = emptyList(),
                )

                is Message.SearchFailure -> copy(
                    isLoading = false,
                    error = message.errorMessage,
                    searchResults = emptyList(),
                    selectedAnimeId = null,
                )

                is Message.AnimePicked -> copy(
                    selectedAnimeId = message.anime.id,
                    isSearchBarActive = false,
                    error = null,
                    searchSuggestions = emptyList(),
                )

                is Message.SearchBarActiveChanged -> copy(
                    isSearchBarActive = message.active,
                )

                is Message.SelectionRestored -> {
                    val anime = message.animeId?.let { animeId ->
                        searchResults.firstOrNull { it.id == animeId }
                    }

                    copy(
                        query = anime?.russian ?: query,
                        selectedAnimeId = anime?.id,
                        isSearchBarActive = false,
                        searchSuggestions = emptyList(),
                    )
                }

                is Message.SuggestionsUpdated -> copy(
                    searchSuggestions = message.suggestions,
                )

                Message.SelectionCleared -> copy(
                    selectedAnimeId = null,
                    searchResults = emptyList(),
                    query = "",
                    error = null,
                )
            }
        },
    ) {}

    private data class ActiveSearch(
        val id: Long,
        val job: Job,
    )

    private companion object {
        private const val MIN_QUERY_LENGTH = 3
    }
}
