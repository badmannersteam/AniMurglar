package com.badmanners.animurglar.app.logging

import org.apache.logging.log4j.LogManager
import java.nio.file.Path

object AppLogging {
    private var initialized = false

    fun initialize(logsDir: Path) {
        if (initialized) {
            return
        }

        System.setProperty("animurglar.logs.dir", logsDir.toString())
        initialized = true

        val logger = LogManager.getLogger(AppLogging::class.java)
        logger.info("Logging initialized. logsDir={}", logsDir)
    }
}
