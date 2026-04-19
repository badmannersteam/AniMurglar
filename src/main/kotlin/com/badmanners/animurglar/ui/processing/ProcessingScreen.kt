package com.badmanners.animurglar.ui.processing

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
import com.badmanners.animurglar.pipeline.ProcessingStage
import com.badmanners.animurglar.ui.processing.ProcessingStore.Intent
import kotlinx.coroutines.ExperimentalCoroutinesApi


@OptIn(ExperimentalCoroutinesApi::class)
@Composable
fun ProcessingScreen(
    processingStore: ProcessingStore,
    modifier: Modifier = Modifier,
) {
    val state by processingStore.stateFlow.collectAsState()
    val isVisible = state.isRunning
        || state.error != null
        || state.activeRunRoot != null
        || state.syncProgress.isNotEmpty()
        || state.mergeProgress.isNotEmpty()

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
                    text = "Обработка",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )

                if (state.isRunning) {
                    TextButton(onClick = { processingStore.accept(Intent.CancelAll) }) {
                        Text("Отмена")
                    }
                }

                if (!state.isRunning && state.error != null) {
                    TextButton(onClick = { processingStore.accept(Intent.RetryLast) }) {
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
                    text = buildString {
                        append("${error.stage.displayName()}: ${error.message}")
                        error.episodeNumber?.let { append(" | серия=$it") }
                        error.sourceId?.let { append(" | источник=$it") }
                        error.teamName?.let { append(" | команда=$it") }
                    },
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }

            if (state.syncProgress.isNotEmpty()) {
                ProgressGroup(
                    title = "Синхронизация звука",
                    items = state.syncProgress,
                    modifier = Modifier.fillMaxWidth()
                        .padding(top = 24.dp)
                        .weight(state.syncProgress.size.coerceIn(0..state.mergeProgress.size * 3).toFloat().coerceAtLeast(0.1f))
                )
            }

            if (state.mergeProgress.isNotEmpty()) {
                ProgressGroup(
                    title = "Объединение",
                    items = state.mergeProgress,
                    modifier = Modifier.fillMaxWidth()
                        .padding(top = 24.dp)
                        .weight(state.mergeProgress.size.toFloat())
                )
            }
        }
    }
}

private fun ProcessingStage.displayName(): String = when (this) {
    ProcessingStage.ANALYZE -> "Анализ"
    ProcessingStage.APPLY -> "Применение"
    ProcessingStage.MERGE -> "Объединение"
}

@Composable
private fun ProgressGroup(
    title: String,
    items: List<ProcessingStore.ProgressItem>,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
        )
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        ) {
            items(items, key = { it.label }) { item ->
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
                        Text(
                            text = item.stageName ?: "${((item.progress ?: 0.0) * 100).toInt()}%",
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.End,
                        )
                    }

                    if (item.progress == null) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        )
                    } else {
                        LinearProgressIndicator(
                            progress = { item.progress.toFloat() },
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        )
                    }
                }
            }
        }
    }
}
