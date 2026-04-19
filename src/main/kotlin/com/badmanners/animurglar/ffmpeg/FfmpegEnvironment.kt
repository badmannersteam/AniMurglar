package com.badmanners.animurglar.ffmpeg

import org.apache.logging.log4j.LogManager
import org.bytedeco.ffmpeg.global.avutil


fun initializeFfmpeg() {
    //org.bytedeco.javacv.FFmpegLogCallback.set() //needs javacv dep
    avutil.av_log_set_level(avutil.AV_LOG_INFO)

    val logger = LogManager.getLogger("FfmpegEnvironment")
    logger.info("Using bundled ffmpeg natives from JavaCV platform artifacts.")
}
