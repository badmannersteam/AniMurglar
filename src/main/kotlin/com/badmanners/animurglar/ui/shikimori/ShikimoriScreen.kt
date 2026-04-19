package com.badmanners.animurglar.ui.shikimori

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.arkivanov.mvikotlin.extensions.coroutines.stateFlow
import com.badmanners.animurglar.shikimori.ShikimoriAnime
import com.badmanners.animurglar.ui.shikimori.ShikimoriStore.Intent
import com.badmanners.animurglar.utils.openInBrowser
import kotlinx.coroutines.ExperimentalCoroutinesApi


@OptIn(ExperimentalMaterial3Api::class, ExperimentalCoroutinesApi::class)
@Composable
fun ShikimoriScreen(
    shikimoriStore: ShikimoriStore,
    modifier: Modifier = Modifier,
) {
    val state by shikimoriStore.stateFlow.collectAsState()
    val resultListState = rememberLazyListState()

    Column(modifier = modifier.fillMaxWidth()) {
        SearchBar(
            inputField = {
                SearchBarDefaults.InputField(
                    query = state.query,
                    onQueryChange = {
                        shikimoriStore.accept(Intent.QueryChanged(value = it))
                    },
                    onSearch = {
                        shikimoriStore.accept(Intent.SearchPressed(query = it))
                    },
                    expanded = state.isSearchBarActive,
                    onExpandedChange = {
                        shikimoriStore.accept(Intent.SearchBarActiveChanged(active = it))
                    },
                    placeholder = {
                        Text("Название аниме")
                    },
                    trailingIcon = {
                        if (state.isSearchBarActive)
                            TextButton(onClick = { shikimoriStore.accept(Intent.SearchPressed(query = state.query)) }) {
                                Text("Найти")
                            }
                    },
                )
            },
            expanded = state.isSearchBarActive,
            onExpandedChange = { shikimoriStore.accept(Intent.SearchBarActiveChanged(active = it)) },
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp)),
        ) {
            if (state.isLoading) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }

            if (state.searchResults.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight()
                        .padding(top = 8.dp),
                ) {
                    LazyColumn(
                        state = resultListState,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.fillMaxWidth().padding(end = 4.dp),
                    ) {
                        items(
                            items = state.searchResults,
                            key = { it.id },
                        ) { anime ->
                            ShikimoriResultEntry(
                                anime = anime,
                                selected = anime.id == state.selectedAnimeId,
                                onClick = {
                                    shikimoriStore.accept(Intent.AnimePicked(animeId = anime.id))
                                },
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                    }

                    VerticalScrollbar(
                        adapter = rememberScrollbarAdapter(resultListState),
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .fillMaxHeight(),
                    )
                }
            }
        }

        state.error?.let { error ->
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        state.searchResults.firstOrNull { it.id == state.selectedAnimeId }?.let {
            ShikimoriResultEntry(
                anime = it,
                onClick = { shikimoriStore.accept(Intent.SearchBarActiveChanged(active = true)) },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
        }
    }
}

@Composable
private fun ShikimoriResultEntry(
    anime: ShikimoriAnime,
    selected: Boolean? = null,
    onClick: () -> Unit = {},
    modifier: Modifier,
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
                if (selected != null)
                    RadioButton(selected = selected, onClick = onClick)
                Text(
                    text = anime.russian,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${anime.episodesAired ?: "?"}/${anime.episodes ?: "?"} сер.\n" +
                        listOf(anime.airedOnDate ?: "?", anime.releasedOnDate ?: "?").joinToString(" - "),
                    style = MaterialTheme.typography.labelLarge,
                    textAlign = TextAlign.End,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = listOfNotNull(anime.licenseNameRu, anime.name, anime.english).joinToString(" • "),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = listOfNotNull(anime.japanese, *anime.synonyms.toTypedArray()).joinToString(" • "),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                TextButton(onClick = { openInBrowser(anime.url) }) {
                    Text("Открыть")
                }
            }
        }
    }
}

