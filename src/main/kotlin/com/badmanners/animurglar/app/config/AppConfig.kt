package com.badmanners.animurglar.app.config

import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.absolute
import kotlin.io.path.createDirectories

data class AppConfig(
    val tempDir: Path,
    val outputDir: Path,
    val logsDir: Path,
    val networkTimeouts: NetworkTimeouts,
    val qBittorrent: QBittorrentConfig,
    val downloader: DownloaderConfig,
) {
    fun prepareWorkDirectories() {
        tempDir.createDirectories()
        outputDir.createDirectories()
        logsDir.createDirectories()
    }

    companion object {
        fun load(): AppConfig {
            val appHome = (System.getProperty("jpackage.app-path")?.let { Paths.get(it) }
                ?: Paths.get(".").absolute()).parent

            return AppConfig(
                tempDir = appHome.resolve("temp"),
                outputDir = appHome.resolve("output"),
                logsDir = appHome.resolve("logs"),
                networkTimeouts = NetworkTimeouts(),
                qBittorrent = QBittorrentConfig(),
                downloader = DownloaderConfig(),
            )
        }
    }
}

data class NetworkTimeouts(
    val connectMillis: Long = 30_000,
    val socketMillis: Long = 30_000,
)

data class QBittorrentConfig(
    val baseUrl: String = "http://localhost:8080",
    val username: String = "admin",
    val password: String = "adminadmin"
)

data class DownloaderConfig(
    val maxParallelDubTasks: Int = 8,
    val maxParallelHlsChunks: Int = 10,
    val retries: Int = 2,
)
