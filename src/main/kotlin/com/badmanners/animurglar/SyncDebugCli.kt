package com.badmanners.animurglar

import com.badmanners.animurglar.app.config.AppConfig
import com.badmanners.animurglar.app.logging.AppLogging
import com.badmanners.animurglar.ffmpeg.ApplySyncRequest
import com.badmanners.animurglar.ffmpeg.FfmpegService
import com.badmanners.animurglar.ffmpeg.SyncAnalyzeRequest
import com.badmanners.animurglar.ffmpeg.SyncAnalyzeService
import com.badmanners.animurglar.ffmpeg.SyncChartService
import com.badmanners.animurglar.ffmpeg.initializeFfmpeg
import kotlinx.coroutines.runBlocking
import org.apache.logging.log4j.LogManager
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText


fun main(args: Array<String>) = runBlocking {
    require(args.size == 3) {
        "Usage: SyncDebugCliKt <original-audio-or-video-path> <dub-audio-path> <output-synced-audio-path>"
    }

    val originalMediaPath = Path.of(args[0]).toAbsolutePath().normalize()
    val dubMediaPath = Path.of(args[1]).toAbsolutePath().normalize()
    val outputAudioPath = Path.of(args[2]).toAbsolutePath().normalize()

    val appConfig = AppConfig.load()
    appConfig.prepareWorkDirectories()
    AppLogging.initialize(appConfig.logsDir)
    initializeFfmpeg()

    val logger = LogManager.getLogger("SyncDebugCli")
    val ffmpegService = FfmpegService()
    val syncAnalyzeService = SyncAnalyzeService(ffmpegService)
    val syncChartService = SyncChartService()

    val workDir = appConfig.tempDir
        .resolve("sync-cli")
        .resolve(System.currentTimeMillis().toString())
    workDir.createDirectories()

    logger.info("[SYNC_CLI] original=$originalMediaPath")
    logger.info("[SYNC_CLI] dub=$dubMediaPath")
    logger.info("[SYNC_CLI] output=$outputAudioPath")
    logger.info("[SYNC_CLI] workDir=$workDir")

    val analyzeResult = syncAnalyzeService.analyze(
        request = SyncAnalyzeRequest(
            originalMedia = originalMediaPath,
            dubMedia = dubMediaPath,
            normalizedOriginalWavPath = workDir.resolve("original.analysis.wav"),
            normalizedDubWavPath = workDir.resolve("dub.analysis.wav"),
        ),
        log = { message -> logger.info("[SYNC_CLI:analyze] $message") },
    )

    val planPath = workDir.resolve("sync-plan.json")
    planPath.parent.createDirectories()
    planPath.writeText(
        syncAnalyzeService.encodePlanToJson(analyzeResult.plan),
        StandardCharsets.UTF_8,
    )

    logger.info("[SYNC_CLI] planPath=$planPath")
    logger.info("[SYNC_CLI] diagnostics=${analyzeResult.plan.diagnostics}")
    analyzeResult.plan.anchors.forEachIndexed { index, anchor ->
        logger.info("[SYNC_CLI] anchor[$index]=$anchor")
    }
    analyzeResult.plan.segments.forEachIndexed { index, segment ->
        logger.info("[SYNC_CLI] segment[$index]=$segment")
    }
    analyzeResult.plan.gaps.forEachIndexed { index, gap ->
        logger.info("[SYNC_CLI] gap[$index]=$gap")
    }

    val envelopeSeries = syncAnalyzeService.buildEnvelopeSeries(
        normalizedOriginalWavPath = analyzeResult.normalizedOriginalWavPath,
        normalizedDubWavPath = analyzeResult.normalizedDubWavPath,
        defaults = analyzeResult.plan.defaults,
    )
    val estimatedOffsetSec = analyzeResult.plan.diagnostics.globalOffsetSec
    val estimatedRate = analyzeResult.plan.diagnostics.globalRate
    val chartsDir = workDir.resolve("charts")
    chartsDir.createDirectories()

    val fineRawChartPath = chartsDir.resolve("raw_fine.png")
    val fineAlignedChartPath = chartsDir.resolve("aligned_fine.png")
    val coarseRawChartPath = chartsDir.resolve("raw_coarse.png")
    val coarseAlignedChartPath = chartsDir.resolve("aligned_coarse.png")
    syncChartService.saveFineCharts(
        fineHz = envelopeSeries.fineHz,
        originalFine = envelopeSeries.originalFine,
        dubFine = envelopeSeries.dubFine,
        estimatedOffsetSec = estimatedOffsetSec,
        rawPath = fineRawChartPath,
        alignedPath = fineAlignedChartPath,
    )
    syncChartService.saveCoarseCharts(
        coarseHz = envelopeSeries.coarseHz,
        originalCoarse = envelopeSeries.originalCoarse,
        dubCoarse = envelopeSeries.dubCoarse,
        estimatedOffsetSec = estimatedOffsetSec,
        rawPath = coarseRawChartPath,
        alignedPath = coarseAlignedChartPath,
    )

    logger.info("[SYNC_CLI] estimatedGlobalOffsetSec=$estimatedOffsetSec")
    logger.info("[SYNC_CLI] estimatedGlobalRate=$estimatedRate")
    logger.info("[SYNC_CLI] chart fine raw=$fineRawChartPath")
    logger.info("[SYNC_CLI] chart fine aligned=$fineAlignedChartPath")
    logger.info("[SYNC_CLI] chart coarse raw=$coarseRawChartPath")
    logger.info("[SYNC_CLI] chart coarse aligned=$coarseAlignedChartPath")

    val applyResult = ffmpegService.applySyncPlan(
        request = ApplySyncRequest(
            episodeNumber = 1,
            sourceId = "cli",
            teamName = "cli",
            languageTag = "rus",
            inputDubPath = dubMediaPath,
            outputPath = outputAudioPath,
            plan = analyzeResult.plan,
        ),
        workDir = workDir,
    )

    logger.info("[SYNC_CLI] applyCommand=${applyResult.command.joinToString(separator = " ")}")
    logger.info("[SYNC_CLI] syncedPath=${applyResult.syncedTrack.syncedPath}")
}
