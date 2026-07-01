package com.badmanners.animurglar.app.config

import com.badmanners.animurglar.utils.OS
import com.badmanners.animurglar.utils.os
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.apache.logging.log4j.LogManager
import java.io.File
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText


private fun platformConfigDir(): Path = when (os) {
    OS.WINDOWS -> Path.of(System.getenv("APPDATA"), "AniMurglar", "data")
    OS.LINUX -> Path.of(System.getProperty("user.home"), ".config", "AniMurglar")
    OS.MACOS -> Path.of(System.getProperty("user.home"), "Library", "Application Support", "AniMurglar")
}

fun platformLogsDir(): Path = when (os) {
    OS.WINDOWS -> Path.of(System.getenv("APPDATA"), "AniMurglar", "data")
    OS.LINUX -> Path.of(System.getProperty("user.home"), ".local", "share", "AniMurglar")
    OS.MACOS -> Path.of(System.getProperty("user.home"), "Library", "Application Support", "AniMurglar")
}

private val logger = LogManager.getLogger("AppConfig")

data class AppConfig(
    val tempDir: Path,
    val outputDir: Path,
    val logsDir: Path,
    val networkTimeouts: NetworkTimeouts,
    val qBittorrent: QBittorrentConfig,
    val downloader: DownloaderConfig,
    val proxy: ProxyConfig,
    val cache: CacheConfig,
) {
    fun prepareWorkDirectories() {
        tempDir.createDirectories()
        outputDir.createDirectories()
        logsDir.createDirectories()
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

        fun load(workDir: String? = null): AppConfig {
            val exeDir = workDir?.let { Path.of(it) }
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

            val configHome = platformConfigDir()
            configHome.createDirectories()
            val configFile = configHome.resolve("config.json")
            val saved = if (configFile.exists()) {
                runCatching {
                    json.decodeFromString<ConfigFileData>(configFile.readText())
                }.onFailure { e ->
                    logger.warn("Failed to parse config.json: {}", e.message)
                }.getOrNull()
            } else null

            val app = AppConfig(
                tempDir = saved?.tempDir?.let { Path.of(it) } ?: exeDir.resolve("temp"),
                outputDir = saved?.outputDir?.let { Path.of(it) } ?: exeDir.resolve("output"),
                logsDir = platformLogsDir(),
                networkTimeouts = saved?.networkTimeouts ?: NetworkTimeouts(),
                qBittorrent = saved?.qBittorrent ?: QBittorrentConfig(),
                downloader = saved?.downloader ?: DownloaderConfig(),
                proxy = saved?.proxy ?: ProxyConfig(),
                cache = saved?.cache ?: CacheConfig(),
            )

            logger.info("Config loaded from {}: proxy={}", configFile, app.proxy)

            saveConfig(app)

            return app
        }

        fun saveConfig(config: AppConfig) {
            val configHome = platformConfigDir()
            configHome.createDirectories()
            val data = ConfigFileData(
                tempDir = config.tempDir.toString(),
                outputDir = config.outputDir.toString(),
                logsDir = config.logsDir.toString(),
                networkTimeouts = config.networkTimeouts,
                qBittorrent = config.qBittorrent,
                downloader = config.downloader,
                proxy = config.proxy,
                cache = config.cache,
            )
            val configFile = configHome.resolve("config.json")
            runCatching {
                configFile.writeText(json.encodeToString(data))
                logger.info("Config saved to {}: proxy={}", configFile, config.proxy)
            }.onFailure { e ->
                logger.error("Failed to save config.json: {}", e.message, e)
            }
        }
    }
}

@Serializable
data class ConfigFileData(
    val tempDir: String? = null,
    val outputDir: String? = null,
    val logsDir: String? = null,
    val networkTimeouts: NetworkTimeouts? = null,
    val qBittorrent: QBittorrentConfig? = null,
    val downloader: DownloaderConfig? = null,
    val proxy: ProxyConfig? = null,
    val cache: CacheConfig? = null,
)

@Serializable
data class NetworkTimeouts(
    val connectMillis: Long = 30_000,
    val socketMillis: Long = 30_000,
)

@Serializable
data class QBittorrentConfig(
    val baseUrl: String = "http://localhost:8080",
    val username: String = "admin",
    val password: String = "adminadmin"
)

@Serializable
data class DownloaderConfig(
    val maxParallelDubTasks: Int = 8,
    val maxParallelHlsChunks: Int = 10,
    val retries: Int = 2,
)

@Serializable
data class ProxyConfig(
    val enabled: Boolean = false,
    val host: String = "",
    val port: Int = 8080,
    val type: ProxyType = ProxyType.HTTP,
    val username: String = "",
    val password: String = "",
)

@Serializable
enum class ProxyType {
    HTTP, SOCKS5
}

@Serializable
data class CacheConfig(
    val searchCacheTtlDays: Long = 3,
    val searchHistoryTtlDays: Long = 30,
    val nyaaCacheTtlDays: Long = 1,
)
