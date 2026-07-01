package com.badmanners.animurglar

import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.badmanners.animurglar.app.config.AppConfig
import com.badmanners.animurglar.app.config.ProxyConfig
import com.badmanners.animurglar.app.di.appModule
import com.badmanners.animurglar.app.logging.AppLogging
import com.badmanners.animurglar.ffmpeg.initializeFfmpeg
import com.badmanners.animurglar.generated.resources.Res
import com.badmanners.animurglar.generated.resources.icon
import com.badmanners.animurglar.ui.root.RootScreen
import com.badmanners.animurglar.utils.OS
import com.badmanners.animurglar.utils.os
import com.materialkolor.DynamicMaterialTheme
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import io.github.kdroidfilter.platformtools.darkmodedetector.windows.setWindowsAdaptiveTitleBar
import org.apache.logging.log4j.LogManager
import org.jetbrains.compose.resources.painterResource
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import java.nio.file.Path


fun main(args: Array<String>) {
    // Set logs dir before any class loading triggers log4j initialization
    run {
        val userHome = System.getProperty("user.home")
        val logsDir = when {
            os == OS.WINDOWS -> Path.of(System.getenv("APPDATA"), "AniMurglar", "data")
            os == OS.MACOS -> Path.of(userHome, "Library", "Application Support", "AniMurglar")
            else -> Path.of(userHome, ".local", "share", "AniMurglar")
        }
        System.setProperty("animurglar.logs.dir", logsDir.toString())
    }

    if (os != OS.MACOS)
        System.setProperty("skiko.renderApi", "OPENGL")

    val appConfig = AppConfig.load(args.lastOrNull())
    appConfig.prepareWorkDirectories()

    AppLogging.initialize(appConfig.logsDir)

    val logger = LogManager.getLogger("AniMurglarStartup")

    initializeFfmpeg()

    val currentConfig = mutableStateOf(appConfig)

    fun reinitializeKoin(newConfig: AppConfig) {
        stopKoin()
        currentConfig.value = newConfig
        startKoin {
            modules(appModule(newConfig))
        }

        val p = newConfig.proxy
        if (p.enabled && p.host.isNotBlank()) {
            logger.info("Proxy reconfigured: type={}, host={}, port={}, auth={}", p.type, p.host, p.port, p.username.isNotBlank())
        } else {
            logger.info("Proxy disabled")
        }
    }

    startKoin {
        modules(appModule(appConfig))
    }

    logger.info("AniMurglar started. tempDir={}, outputDir={}", appConfig.tempDir, appConfig.outputDir)

    val proxy = appConfig.proxy
    if (proxy.enabled && proxy.host.isNotBlank()) {
        logger.info("Proxy configured: type={}, host={}, port={}, auth={}", proxy.type, proxy.host, proxy.port, proxy.username.isNotBlank())
    } else {
        logger.info("Proxy not configured")
    }

    application {
        Window(
            state = rememberWindowState(width = 1500.dp, height = 1000.dp),
            title = "AniMurglar",
            icon = painterResource(Res.drawable.icon),
            onCloseRequest = ::exitApplication,
        ) {
            window.setWindowsAdaptiveTitleBar(true)
            DynamicMaterialTheme(
                seedColor = Color(-7798809),
                isDark = true,
                style = PaletteStyle.TonalSpot,
                specVersion = ColorSpec.SpecVersion.SPEC_2025
            ) {
                CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 36.dp) {
                    val koin = org.koin.core.context.GlobalContext.get()
                    with(koin) {
                        RootScreen(
                            rootStore = get(),
                            shikimoriStore = get(),
                            nyaaPickerStore = get(),
                            dubsPickerStore = get(),
                            subtitlesPickerStore = get(),
                            episodeMappingStore = get(),
                            downloaderStore = get(),
                            processingStore = get(),
                            appConfig = currentConfig.value,
                            onProxyConfigChanged = { newProxy ->
                                val updated = currentConfig.value.copy(proxy = newProxy)
                                AppConfig.saveConfig(updated)
                                reinitializeKoin(updated)
                            },
                            logsDir = appConfig.logsDir,
                        )
                    }
                }
            }
        }
    }
}
