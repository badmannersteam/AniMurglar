package com.badmanners.animurglar.ui.downloader

import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutorScope
import com.arkivanov.mvikotlin.extensions.coroutines.coroutineExecutorFactory
import com.badmanners.animurglar.downloader.DownloadCoordinator
import com.badmanners.animurglar.downloader.DownloadProgressEvent
import com.badmanners.animurglar.downloader.DownloadProgressEvent.DownloadProgressGroup
import com.badmanners.animurglar.downloader.DownloadRequest
import com.badmanners.animurglar.pipeline.DownloadBatch
import com.badmanners.animurglar.ui.downloader.DownloaderStore.Intent
import com.badmanners.animurglar.ui.downloader.DownloaderStore.Label
import com.badmanners.animurglar.ui.downloader.DownloaderStore.Message
import com.badmanners.animurglar.ui.downloader.DownloaderStore.State
import com.badmanners.animurglar.utils.upsert
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.apache.logging.log4j.LogManager

interface DownloaderStore : Store<Intent, State, Label> {

    sealed interface Intent {
        data class Start(val request: DownloadRequest) : Intent
        data object CancelAll : Intent
        data object RetryLast : Intent
        data object Reset : Intent
    }

    data class ProgressItem(
        val key: String,
        val label: String,
        val progress: Double,
        val downloadedBytes: Long? = null,
        val totalBytes: Long? = null,
        val speedBytesPerSecond: Long? = null,
    )

    data class State(
        val isRunning: Boolean = false,
        val error: String? = null,
        val torrentProgress: List<ProgressItem> = emptyList(),
        val dubProgress: List<ProgressItem> = emptyList(),
        val subtitleProgress: List<ProgressItem> = emptyList(),
    )

    sealed interface Message {
        data object DownloadStarted : Message
        data class ProgressReported(val event: DownloadProgressEvent) : Message
        data class DownloadFinished(val batch: DownloadBatch) : Message
        data class DownloadFailed(val errorMessage: String) : Message
        data object DownloadCancelled : Message
        data object ResetApplied : Message
    }

    sealed interface Label {
        data class DownloadCompleted(val batch: DownloadBatch) : Label
        data class DownloadFailed(val errorMessage: String) : Label
        data object DownloadCancelled : Label
    }
}

class DownloaderStoreFactory(
    private val storeFactory: StoreFactory,
    private val downloadCoordinator: DownloadCoordinator,
) {
    private val logger = LogManager.getLogger(DownloaderStore::class.java)

    fun create(): DownloaderStore = object : DownloaderStore, Store<Intent, State, Label>
    by storeFactory.create<Intent, Nothing, Message, State, Label>(
        name = "DownloaderStore",
        initialState = State(),
        executorFactory = coroutineExecutorFactory {
            var activeDownload: Job? = null
            var lastRequest: DownloadRequest? = null

            onIntent<Intent.Start> { intent ->
                if (activeDownload?.isActive == true) {
                    return@onIntent
                }

                lastRequest = intent.request
                activeDownload = launchDownload(lastRequest)
            }

            onIntent<Intent.CancelAll> {
                if (activeDownload?.isActive == true) {
                    activeDownload?.cancel(CancellationException("Cancelled by user action"))
                }
            }

            onIntent<Intent.RetryLast> {
                val request = lastRequest ?: return@onIntent
                if (activeDownload?.isActive == true) {
                    return@onIntent
                }

                activeDownload = launchDownload(request)
            }

            onIntent<Intent.Reset> {
                activeDownload?.cancel(CancellationException("Downloader state reset."))
                dispatch(Message.ResetApplied)
            }
        },
        reducer = { message ->
            when (message) {
                Message.DownloadStarted -> copy(
                    isRunning = true,
                    error = null,
                    torrentProgress = emptyList(),
                    dubProgress = emptyList(),
                    subtitleProgress = emptyList(),
                )

                is Message.ProgressReported -> {
                    val item = DownloaderStore.ProgressItem(
                        key = message.event.itemKey,
                        label = message.event.label,
                        progress = message.event.progress,
                        downloadedBytes = message.event.downloadedBytes,
                        totalBytes = message.event.totalBytes,
                        speedBytesPerSecond = message.event.speedBytesPerSecond,
                    )

                    when (message.event.group) {
                        DownloadProgressGroup.TORRENT_FILES -> copy(
                            torrentProgress = torrentProgress.upsert(item) { it.key == item.key },
                        )

                        DownloadProgressGroup.DUB_FILES -> copy(
                            dubProgress = dubProgress.upsert(item) { it.key == item.key },
                        )

                        DownloadProgressGroup.SUBTITLE_FILES -> copy(
                            subtitleProgress = subtitleProgress.upsert(item) { it.key == item.key },
                        )
                    }
                }

                is Message.DownloadFinished -> copy(
                    isRunning = false,
                    error = null,
                )

                is Message.DownloadFailed -> copy(
                    isRunning = false,
                    error = message.errorMessage,
                )

                Message.DownloadCancelled -> copy(
                    isRunning = false,
                )

                Message.ResetApplied -> State()
            }
        },
    ) {}

    private fun CoroutineExecutorScope<State, Message, Nothing, Label>.launchDownload(
        request: DownloadRequest
    ): Job {
        dispatch(Message.DownloadStarted)
        return launch {
            try {
                val batch = downloadCoordinator.download(
                    request = request,
                    onProgress = { event: DownloadProgressEvent ->
                        dispatch(Message.ProgressReported(event = event))
                    },
                )
                dispatch(Message.DownloadFinished(batch = batch))
                publish(Label.DownloadCompleted(batch = batch))
            } catch (_: CancellationException) {
                dispatch(Message.DownloadCancelled)
                publish(Label.DownloadCancelled)
            } catch (throwable: Throwable) {
                logger.warn("Download failed", throwable)
                val errorMessage = throwable.message ?: "Download failed"
                dispatch(Message.DownloadFailed(errorMessage = errorMessage))
                publish(Label.DownloadFailed(errorMessage = errorMessage))
            }
        }
    }
}
