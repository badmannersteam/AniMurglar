package com.badmanners.animurglar

import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.badmanners.animurglar.app.config.AppConfig
import com.badmanners.animurglar.app.di.appModule
import com.badmanners.animurglar.app.logging.AppLogging
import com.badmanners.animurglar.ffmpeg.initializeFfmpeg
import com.badmanners.animurglar.generated.resources.Res
import com.badmanners.animurglar.generated.resources.icon
import com.badmanners.animurglar.ui.root.RootScreen
import com.materialkolor.DynamicMaterialTheme
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import io.github.kdroidfilter.platformtools.darkmodedetector.windows.setWindowsAdaptiveTitleBar
import org.apache.logging.log4j.LogManager
import org.jetbrains.compose.resources.painterResource
import org.koin.core.context.startKoin


fun main() {
    if (!System.getProperty("os.name").startsWith("Mac", true))
        System.setProperty("skiko.renderApi", "OPENGL")

    val appConfig = AppConfig.load()
    appConfig.prepareWorkDirectories()

    AppLogging.initialize(appConfig.logsDir)

    val logger = LogManager.getLogger("AniMurglarStartup")

    initializeFfmpeg()

    val koin = startKoin {
        modules(appModule(appConfig))
    }.koin

    logger.info("AniMurglar started. tempDir={}, outputDir={}", appConfig.tempDir, appConfig.outputDir)

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
                    RootScreen(koin.get(), koin.get(), koin.get(), koin.get(), koin.get(), koin.get(), koin.get(), koin.get())
                }
            }
        }
    }
}
