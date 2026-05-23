package com.badmanners.animurglar.ui.downloader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.arkivanov.mvikotlin.extensions.coroutines.stateFlow
import com.badmanners.animurglar.ui.downloader.DownloaderStore.Intent
import com.badmanners.animurglar.utils.toReadableSize
import kotlinx.coroutines.ExperimentalCoroutinesApi

@OptIn(ExperimentalCoroutinesApi::class)
@Composable
fun DownloaderScreen(
    downloaderStore: DownloaderStore,
    modifier: Modifier = Modifier,
) {
    val state by downloaderStore.stateFlow.collectAsState()
    val isVisible = state.isRunning
        || state.error != null
        || state.torrentProgress.isNotEmpty()
        || state.dubProgress.isNotEmpty()
        || state.subtitleProgress.isNotEmpty()

    if (!isVisible) {
        return
    }

    ElevatedCard(modifier = modifier) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Загрузчик",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )

                if (state.isRunning) {
                    TextButton(onClick = { downloaderStore.accept(Intent.CancelAll) }) {
                        Text("Отмена")
                    }
                }

                if (!state.isRunning && state.error != null) {
                    TextButton(onClick = { downloaderStore.accept(Intent.RetryLast) }) {
                        Text("Повторить")
                    }
                }
            }

            if (state.isRunning) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
            }

            state.error?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }

            if (state.torrentProgress.isNotEmpty()) {
                ProgressGroup(
                    title = "Торренты",
                    items = state.torrentProgress,
                    modifier = Modifier.fillMaxWidth()
                        .padding(top = 24.dp)
                        .weight(state.torrentProgress.size.toFloat())
                )
            }

            if (state.dubProgress.isNotEmpty()) {
                ProgressGroup(
                    title = "Озвучки",
                    items = state.dubProgress,
                    modifier = Modifier.fillMaxWidth()
                        .padding(top = 24.dp)
                        .weight(state.dubProgress.size.coerceIn(0..state.torrentProgress.size * 3).toFloat().coerceAtLeast(0.1f))
                )
            }

            if (state.subtitleProgress.isNotEmpty()) {
                ProgressGroup(
                    title = "Субтитры",
                    items = state.subtitleProgress,
                    modifier = Modifier.fillMaxWidth()
                        .padding(top = 24.dp)
                        .weight(state.subtitleProgress.size.coerceIn(0..state.torrentProgress.size * 3).toFloat().coerceAtLeast(0.1f))
                )
            }
        }
    }
}

@Composable
private fun ProgressGroup(
    title: String,
    items: List<DownloaderStore.ProgressItem>,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
        )
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) {
            items(items = items, key = { it.label }) { item ->
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        Text(
                            text = item.label,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )

                        progressMeta(item)?.let { details ->
                            Text(
                                text = details,
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = TextAlign.End,
                            )
                        }
                    }

                    LinearProgressIndicator(
                        progress = { item.progress.toFloat() },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

private fun progressMeta(item: DownloaderStore.ProgressItem): String? {
    val downloaded = item.downloadedBytes?.let(::readableSize)
    val total = item.totalBytes?.let(::readableSize)
    val speed = item.speedBytesPerSecond?.let { "${readableSize(it)}/s" }

    val sizePart = when {
        downloaded != null && total != null -> "$downloaded / $total"
        downloaded != null -> downloaded
        total != null -> "? / $total"
        else -> null
    }

    return listOfNotNull(sizePart, speed).takeIf { it.isNotEmpty() }?.joinToString(" • ")
}

private fun readableSize(bytes: Long): String {
    return bytes.toReadableSize()
}
