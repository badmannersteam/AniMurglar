package com.badmanners.animurglar.ui.processing

import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutorScope
import com.arkivanov.mvikotlin.extensions.coroutines.coroutineExecutorFactory
import com.badmanners.animurglar.app.config.AppConfig
import com.badmanners.animurglar.ffmpeg.ApplySyncRequest
import com.badmanners.animurglar.ffmpeg.FfmpegService
import com.badmanners.animurglar.ffmpeg.MergeRequest
import com.badmanners.animurglar.ffmpeg.SyncAnalyzeRequest
import com.badmanners.animurglar.ffmpeg.SyncAnalyzeService
import com.badmanners.animurglar.ffmpeg.SyncChartService
import com.badmanners.animurglar.ffmpeg.SyncTrackPlan
import com.badmanners.animurglar.pipeline.DownloadBatch
import com.badmanners.animurglar.pipeline.EpisodeMergeInput
import com.badmanners.animurglar.pipeline.MergeDubTrackInput
import com.badmanners.animurglar.pipeline.ProcessingDubTrackInput
import com.badmanners.animurglar.pipeline.ProcessingEpisodeInput
import com.badmanners.animurglar.pipeline.ProcessingErrorEnvelope
import com.badmanners.animurglar.pipeline.ProcessingPaths
import com.badmanners.animurglar.pipeline.ProcessingRequest
import com.badmanners.animurglar.pipeline.ProcessingStage
import com.badmanners.animurglar.pipeline.toProcessingRequest
import com.badmanners.animurglar.ui.processing.ProcessingStore.Intent
import com.badmanners.animurglar.ui.processing.ProcessingStore.Label
import com.badmanners.animurglar.ui.processing.ProcessingStore.Message
import com.badmanners.animurglar.ui.processing.ProcessingStore.ProgressItem
import com.badmanners.animurglar.ui.processing.ProcessingStore.State
import com.badmanners.animurglar.utils.suspendRunCatching
import com.badmanners.animurglar.utils.upsert
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.apache.logging.log4j.LogManager
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.Collections.synchronizedList
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText


interface ProcessingStore : Store<Intent, State, Label> {

    sealed interface Intent {
        data class Start(val batch: DownloadBatch) : Intent
        data object CancelAll : Intent
        data object RetryLast : Intent
        data object Reset : Intent
    }

    data class ProgressItem(
        val key: String,
        val label: String,
        val progress: Double?,
        val stageName: String? = null,
        val episodeNumber: Int? = null,
        val sourceId: String? = null,
        val teamName: String? = null,
    )

    data class State(
        val isRunning: Boolean = false,
        val error: ProcessingErrorEnvelope? = null,
        val syncProgress: List<ProgressItem> = emptyList(),
        val mergeProgress: List<ProgressItem> = emptyList(),
        val analyzeProgress: List<ProgressItem> = emptyList(),
        val applyProgress: List<ProgressItem> = emptyList(),
        val mergeStageProgress: List<ProgressItem> = emptyList(),
        val activeRunRoot: String? = null,
    )

    sealed interface Message {
        data class ProcessingStarted(val request: ProcessingRequest) : Message
        data class StageProgressReported(
            val stage: ProcessingStage,
            val item: ProgressItem,
        ) : Message

        data object ProcessingFinished : Message
        data class ProcessingFailed(val error: ProcessingErrorEnvelope) : Message
        data object ProcessingCancelled : Message
        data object ResetApplied : Message
    }

    sealed interface Label {
        data class ProcessingCompleted(val request: ProcessingRequest) : Label
        data class ProcessingFailed(val error: ProcessingErrorEnvelope) : Label
        data object ProcessingCancelled : Label
    }
}

class ProcessingStoreFactory(
    private val storeFactory: StoreFactory,
    private val appConfig: AppConfig,
    private val ffmpegService: FfmpegService,
    private val syncAnalyzeService: SyncAnalyzeService,
    private val syncChartService: SyncChartService,
) {
    private val logger = LogManager.getLogger(ProcessingStore::class.java)

    fun create(): ProcessingStore = object : ProcessingStore, Store<Intent, State, Label>
    by storeFactory.create<Intent, Nothing, Message, State, Label>(
        name = "ProcessingStore",
        initialState = State(),
        executorFactory = coroutineExecutorFactory {
            var activeProcessing: Job? = null
            var lastBatch: DownloadBatch? = null

            onIntent<Intent.Start> { intent ->
                if (activeProcessing?.isActive == true) {
                    return@onIntent
                }

                lastBatch = intent.batch
                activeProcessing = launchProcessing(lastBatch)
            }

            onIntent<Intent.CancelAll> {
                if (activeProcessing?.isActive == true) {
                    activeProcessing?.cancel(CancellationException("Cancelled by user action"))
                }
            }

            onIntent<Intent.RetryLast> {
                val batch = lastBatch ?: return@onIntent
                if (activeProcessing?.isActive == true) {
                    return@onIntent
                }

                activeProcessing = launchProcessing(batch)
            }

            onIntent<Intent.Reset> {
                activeProcessing?.cancel(CancellationException("Processing state reset."))
                dispatch(Message.ResetApplied)
            }
        },
        reducer = { message ->
            when (message) {
                is Message.ProcessingStarted -> copy(
                    isRunning = true,
                    error = null,
                    syncProgress = emptyList(),
                    mergeProgress = emptyList(),
                    analyzeProgress = emptyList(),
                    applyProgress = emptyList(),
                    mergeStageProgress = emptyList(),
                    activeRunRoot = message.request.paths.processingRoot.toString(),
                )

                is Message.StageProgressReported -> {
                    val nextAnalyze = if (message.stage == ProcessingStage.ANALYZE) {
                        analyzeProgress.upsert(message.item) { it.key == message.item.key }
                    } else {
                        analyzeProgress
                    }

                    val nextApply = if (message.stage == ProcessingStage.APPLY) {
                        applyProgress.upsert(message.item) { it.key == message.item.key }
                    } else {
                        applyProgress
                    }

                    val nextMergeStage = if (message.stage == ProcessingStage.MERGE) {
                        mergeStageProgress.upsert(message.item) { it.key == message.item.key }
                    } else {
                        mergeStageProgress
                    }

                    copy(
                        analyzeProgress = nextAnalyze,
                        applyProgress = nextApply,
                        mergeStageProgress = nextMergeStage,
                        syncProgress = aggregateSyncProgress(
                            analyzeProgress = nextAnalyze,
                            applyProgress = nextApply,
                        ),
                        mergeProgress = nextMergeStage,
                    )
                }

                Message.ProcessingFinished -> copy(isRunning = false, error = null)
                is Message.ProcessingFailed -> copy(isRunning = false, error = message.error)
                Message.ProcessingCancelled -> copy(isRunning = false)
                Message.ResetApplied -> State()
            }
        },
    ) {}

    private fun CoroutineExecutorScope<State, Message, Nothing, Label>.launchProcessing(batch: DownloadBatch): Job {
        val request = batch.toProcessingRequest(
            tempDir = appConfig.tempDir,
            outputDir = appConfig.outputDir,
        )
        dispatch(Message.ProcessingStarted(request = request))
        return launch {
            val onFailed = { error: ProcessingErrorEnvelope ->
                dispatch(Message.ProcessingFailed(error = error))
                publish(Label.ProcessingFailed(error = error))
            }
            try {
                runProcessingPipeline(
                    request = request,
                    reportProgress = { stage: ProcessingStage, item: ProgressItem ->
                        dispatch(Message.StageProgressReported(stage = stage, item = item))
                    },
                )
                dispatch(Message.ProcessingFinished)
                publish(Label.ProcessingCompleted(request = request))
            } catch (_: CancellationException) {
                dispatch(Message.ProcessingCancelled)
                publish(Label.ProcessingCancelled)
            } catch (exception: ProcessingPipelineException) {
                logger.warn("Processing failed at stage {}", exception.error.stage, exception)
                onFailed(exception.error)
            } catch (throwable: Throwable) {
                logger.warn("Processing failed", throwable)
                val error = ProcessingErrorEnvelope(
                    stage = ProcessingStage.MERGE,
                    message = throwable.message ?: "Processing failed",
                    details = throwable::class.qualifiedName,
                )
                onFailed(error)
            }
        }
    }

    private suspend fun runProcessingPipeline(
        request: ProcessingRequest,
        reportProgress: (ProcessingStage, ProgressItem) -> Unit,
    ) {
        withContext(Dispatchers.IO) {
            request.paths.processingRoot.createDirectories()
            request.paths.torrentRoot.createDirectories()
            request.paths.dubsRoot.createDirectories()
            request.paths.outputRoot.createDirectories()
        }

        val episodes = request.episodes.sortedBy { it.episodeNumber }

        val semaphore = Semaphore(MAX_CONCURRENT_EPISODES)
        coroutineScope {
            episodes.map { episode ->
                launch(Dispatchers.IO) {
                    processEpisode(
                        episode = episode,
                        request = request,
                        reportProgress = reportProgress,
                        semaphore = semaphore,
                    )
                }
            }.joinAll()
        }
    }

    private suspend fun processEpisode(
        request: ProcessingRequest,
        episode: ProcessingEpisodeInput,
        reportProgress: (ProcessingStage, ProgressItem) -> Unit,
        semaphore: Semaphore,
    ) {
        val episodePaths = request.paths.episodePaths(episode.episodeNumber)
        val originalAnalyzeWavPath = episodePaths.originalAnalyzeWavPath()
        val tracks = episode.dubbedTracks.sortedWith(compareBy({ it.sourceId }, { it.teamName.lowercase() }))
        val appliedDubTracks = synchronizedList(mutableListOf<MergeDubTrackInput>())
        coroutineScope {
            tracks.map { track ->
                launch {
                    processTrack(
                        episode = episode,
                        track = track,
                        episodePaths = episodePaths,
                        originalAnalyzeWavPath = originalAnalyzeWavPath,
                        reportProgress = reportProgress,
                        appliedDubTracks = appliedDubTracks,
                        semaphore = semaphore,
                    )
                }
            }.joinAll()
        }

        runStageWithEnvelope(
            stage = ProcessingStage.MERGE,
            episodeNumber = episode.episodeNumber,
        ) {
            val mergeItem = ProgressItem(
                key = mergeProgressKey(episode.episodeNumber),
                label = "Серия ${episode.episodeNumber}",
                progress = null,
                stageName = ProcessingStage.MERGE.displayName(),
                episodeNumber = episode.episodeNumber,
            )

            reportProgress(ProcessingStage.MERGE, mergeItem)
            semaphore.withPermit {
                ffmpegService.mergeEpisode(
                    request = MergeRequest(
                        input = EpisodeMergeInput(
                            episodeNumber = episode.episodeNumber,
                            videoPath = episode.rawVideoPath,
                            dubTracks = appliedDubTracks,
                            outputPath = episodePaths.outputPath,
                        )
                    ),
                )
            }

            reportProgress(ProcessingStage.MERGE, mergeItem.copy(progress = 1.0, stageName = null))
        }
    }

    private suspend fun processTrack(
        episode: ProcessingEpisodeInput,
        track: ProcessingDubTrackInput,
        episodePaths: ProcessingPaths.EpisodePaths,
        originalAnalyzeWavPath: Path,
        reportProgress: (ProcessingStage, ProgressItem) -> Unit,
        appliedDubTracks: MutableList<MergeDubTrackInput>,
        semaphore: Semaphore,
    ) {
        val progressKey = syncProgressKey(
            episodeNumber = episode.episodeNumber,
            sourceId = track.sourceId,
            teamName = track.teamName,
        )
        val item = ProgressItem(
            key = progressKey,
            label = "Серия ${episode.episodeNumber} • ${track.teamName}",
            progress = null,
            stageName = ProcessingStage.ANALYZE.displayName(),
            episodeNumber = episode.episodeNumber,
            sourceId = track.sourceId,
            teamName = track.teamName,
        )

        var syncPlan: SyncTrackPlan? = null
        runStageWithEnvelope(
            stage = ProcessingStage.ANALYZE,
            episodeNumber = episode.episodeNumber,
            sourceId = track.sourceId,
            teamName = track.teamName,
        ) {
            reportProgress(
                ProcessingStage.ANALYZE,
                item.copy(progress = null, stageName = ProcessingStage.ANALYZE.displayName())
            )
            semaphore.withPermit {
                val analyzeResult = syncAnalyzeService.analyze(
                    request = SyncAnalyzeRequest(
                        originalMedia = episode.rawVideoPath,
                        dubMedia = track.mediaPath,
                        normalizedOriginalWavPath = originalAnalyzeWavPath,
                        normalizedDubWavPath = episodePaths.dubAnalyzeWavPath(track),
                    ),
                    log = { message ->
                        logger.info(
                            "[sync][episode=${episode.episodeNumber}][source=${track.sourceId}][team=${track.teamName}] $message"
                        )
                    },
                )
                val envelopeSeries = syncAnalyzeService.buildEnvelopeSeries(
                    normalizedOriginalWavPath = analyzeResult.normalizedOriginalWavPath,
                    normalizedDubWavPath = analyzeResult.normalizedDubWavPath,
                    defaults = analyzeResult.plan.defaults,
                )
                syncChartService.saveFineCharts(
                    fineHz = envelopeSeries.fineHz,
                    originalFine = envelopeSeries.originalFine,
                    dubFine = envelopeSeries.dubFine,
                    estimatedOffsetSec = analyzeResult.plan.diagnostics.globalOffsetSec,
                    rawPath = episodePaths.analyzeRawChartPath(track),
                    alignedPath = episodePaths.analyzeAlignedChartPath(track),
                )
                val planPath = episodePaths.analyzePlanPath(track)
                writeTextFile(
                    path = planPath,
                    content = syncAnalyzeService.encodePlanToJson(analyzeResult.plan),
                )
                syncPlan = analyzeResult.plan
            }
            reportProgress(ProcessingStage.ANALYZE, item.copy(progress = 1.0, stageName = null))
        }

        runStageWithEnvelope(
            stage = ProcessingStage.APPLY,
            episodeNumber = episode.episodeNumber,
            sourceId = track.sourceId,
            teamName = track.teamName,
        ) {
            reportProgress(
                ProcessingStage.APPLY,
                item.copy(progress = null, stageName = ProcessingStage.APPLY.displayName())
            )
            val plan = checkNotNull(syncPlan) {
                "Sync plan is missing before apply stage for episode ${episode.episodeNumber}, source=${track.sourceId}, team=${track.teamName}"
            }
            val applyResult = semaphore.withPermit {
                ffmpegService.applySyncPlan(
                    request = ApplySyncRequest(
                        episodeNumber = episode.episodeNumber,
                        sourceId = track.sourceId,
                        teamName = track.teamName,
                        languageTag = track.languageTag,
                        inputDubPath = track.mediaPath,
                        outputPath = episodePaths.syncedDubPath(track),
                        plan = plan,
                    ),
                    workDir = episodePaths.analyzeWorkDir(track),
                )
            }

            logger.info(
                "[sync][apply][episode=${episode.episodeNumber}][source=${track.sourceId}]" +
                    "[team=${track.teamName}] output=${episodePaths.syncedDubPath(track)}"
            )
            if (logger.isDebugEnabled) {
                logger.debug("[sync][apply] command=${applyResult.command.joinToString(separator = " ")}")
            }

            reportProgress(ProcessingStage.APPLY, item.copy(progress = 1.0, stageName = null))
            appliedDubTracks += MergeDubTrackInput(
                sourceId = track.sourceId,
                teamName = track.teamName,
                languageTag = track.languageTag,
                audioPath = applyResult.syncedTrack.syncedPath,
            )
        }
    }

    private suspend fun runStageWithEnvelope(
        stage: ProcessingStage,
        episodeNumber: Int,
        sourceId: String? = null,
        teamName: String? = null,
        block: suspend () -> Unit,
    ) {
        suspendRunCatching {
            block()
        }.getOrElse { throwable ->
            throw ProcessingPipelineException(
                error = ProcessingErrorEnvelope(
                    stage = stage,
                    message = throwable.message ?: "${stage.name.lowercase()} stage failed",
                    episodeNumber = episodeNumber,
                    sourceId = sourceId,
                    teamName = teamName,
                    details = buildString {
                        append(throwable::class.qualifiedName)
                        throwable.message?.takeIf { it.isNotBlank() }?.let { msg ->
                            append(": ")
                            append(msg)
                        }
                    },
                ),
                cause = throwable,
            )
        }
    }

    private suspend fun writeTextFile(path: Path, content: String) = withContext(Dispatchers.IO) {
        path.parent.createDirectories()
        path.writeText(content, StandardCharsets.UTF_8)
    }

    private fun aggregateSyncProgress(
        analyzeProgress: List<ProgressItem>,
        applyProgress: List<ProgressItem>,
    ): List<ProgressItem> {
        val analyzeByKey = analyzeProgress.associateBy { it.key }
        val applyByKey = applyProgress.associateBy { it.key }
        val keys = (analyzeByKey.keys + applyByKey.keys).sorted()

        return keys.map { key ->
            val analyze = analyzeByKey[key]
            val apply = applyByKey[key]
            val base = apply ?: analyzeByKey.getValue(key)

            when {
                apply?.progress == 1.0 -> base.copy(progress = 1.0, stageName = null)
                apply != null -> base.copy(progress = null, stageName = ProcessingStage.APPLY.displayName())
                analyze?.progress == 1.0 -> base.copy(progress = 0.5, stageName = ProcessingStage.APPLY.displayName())
                analyze != null -> base.copy(progress = null, stageName = ProcessingStage.ANALYZE.displayName())
                else -> base
            }
        }
    }

    private fun ProcessingStage.displayName(): String = when (this) {
        ProcessingStage.ANALYZE -> "Анализ"
        ProcessingStage.APPLY -> "Применение"
        ProcessingStage.MERGE -> "Объединение"
    }

    private fun syncProgressKey(episodeNumber: Int, sourceId: String, teamName: String): String {
        return "sync:$episodeNumber:${sourceId.lowercase()}:${teamName.lowercase()}"
    }

    private fun mergeProgressKey(episodeNumber: Int): String = "merge:$episodeNumber"

    private class ProcessingPipelineException(
        val error: ProcessingErrorEnvelope,
        cause: Throwable,
    ) : RuntimeException(error.message, cause)

    private companion object {
        private const val MAX_CONCURRENT_EPISODES = 8
    }
}
