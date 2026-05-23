package com.badmanners.animurglar

import com.badmanners.animurglar.app.config.AppConfig
import com.badmanners.animurglar.app.logging.AppLogging
import com.badmanners.animurglar.subtitles.SubtitleCaptionFilterService
import kotlinx.coroutines.runBlocking
import org.apache.logging.log4j.LogManager
import java.nio.file.Path


fun main(args: Array<String>) = runBlocking {
    require(args.size == 2) {
        "Usage: SubtitleFilterDebugCliKt <input-ass-path> <output-captions-ass-path>"
    }

    val inputPath = Path.of(args[0]).toAbsolutePath().normalize()
    val outputPath = Path.of(args[1]).toAbsolutePath().normalize()

    val appConfig = AppConfig.load()
    appConfig.prepareWorkDirectories()
    AppLogging.initialize(appConfig.logsDir)

    val logger = LogManager.getLogger("SubtitleFilterDebugCli")
    logger.info("[SUBTITLE_FILTER_CLI] input=$inputPath")
    logger.info("[SUBTITLE_FILTER_CLI] output=$outputPath")

    SubtitleCaptionFilterService().writeCaptionsOnlySubtitle(
        fullPath = inputPath,
        captionsOnlyPath = outputPath,
    )

    logger.info("[SUBTITLE_FILTER_CLI] captionsOnlyOutput=$outputPath")
}