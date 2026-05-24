package com.badmanners.animurglar.app.config

import com.badmanners.animurglar.utils.OS
import com.badmanners.animurglar.utils.os
import java.io.File
import java.nio.file.Path
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
        fun load(workDir: String? = null): AppConfig {
            val appHome = workDir?.let { Path.of(it) }
                ?: run {
                    val jpackagePath = System.getProperty("jpackage.app-path")
                    val executablePath = when {
                        jpackagePath == null -> File(object {}::class.java.protectionDomain.codeSource.location.toURI())
                        os == OS.WINDOWS -> File(jpackagePath)
                        os == OS.LINUX -> File(System.getenv("APPIMAGE"))
                        os == OS.MACOS -> File(jpackagePath).parentFile.parentFile.parentFile
                        else -> null
                    }

                    (executablePath?.parentFile ?: File(".")).toPath()
                }


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
