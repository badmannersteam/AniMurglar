package com.badmanners.animurglar.ui.episodes

import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.coroutineExecutorFactory
import com.badmanners.animurglar.ui.episodes.EpisodeMappingStore.Intent
import com.badmanners.animurglar.ui.episodes.EpisodeMappingStore.Label
import com.badmanners.animurglar.ui.episodes.EpisodeMappingStore.Message
import com.badmanners.animurglar.ui.episodes.EpisodeMappingStore.State


interface EpisodeMappingStore : Store<Intent, State, Label> {

    sealed interface Intent {
        data class DubStartChanged(val dubStartEpisode: Int) : Intent
        data object Reset : Intent
    }

    data class State(
        val dubStartOverride: Int? = null,
    )

    sealed interface Message {
        data class DubStartChanged(val dubStartEpisode: Int) : Message
        data object ResetApplied : Message
    }

    sealed interface Label
}

class EpisodeMappingStoreFactory(private val storeFactory: StoreFactory) {

    fun create(): EpisodeMappingStore = object : EpisodeMappingStore, Store<Intent, State, Label>
    by storeFactory.create<Intent, Nothing, Message, State, Label>(
        name = "EpisodeMappingStore",
        initialState = State(),
        executorFactory = coroutineExecutorFactory {
            onIntent<Intent.DubStartChanged> { intent ->
                dispatch(Message.DubStartChanged(dubStartEpisode = intent.dubStartEpisode))
            }

            onIntent<Intent.Reset> {
                dispatch(Message.ResetApplied)
            }
        },
        reducer = { message ->
            when (message) {
                is Message.DubStartChanged -> copy(dubStartOverride = message.dubStartEpisode)
                Message.ResetApplied -> State()
            }
        },
    ) {}
}