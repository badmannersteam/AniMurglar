import org.gradle.kotlin.dsl.support.serviceOf
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpClient.Redirect
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers

plugins {
    kotlin("jvm") version "2.3.10"
    kotlin("plugin.compose") version "2.3.10"
    id("org.jetbrains.compose") version "1.10.3"
}

group = "com.badmanners"
version = "2.0"

repositories {
    mavenCentral()
    google()
    maven("https://jitpack.io")
}

enum class OS(val classifier: String) {
    MACOS("macosx"), WINDOWS("windows"), LINUX("linux")
}

enum class Arch(val classifier: String) {
    X86_64("x86_64"), ARM64("arm64")
}

val arch = when (val osArch = System.getProperty("os.arch")) {
    "x64", "x86_64", "amd64" -> Arch.X86_64
    "arm64", "aarch64" -> Arch.ARM64
    else -> error("Unknown OS arch: $osArch")
}

val os = run {
    val os = System.getProperty("os.name")
    when {
        os.startsWith("Mac OS X", ignoreCase = true) -> OS.MACOS
        os.startsWith("Win", ignoreCase = true) -> OS.WINDOWS
        os.startsWith("Linux", ignoreCase = true) -> OS.LINUX
        else -> error("Unknown OS name: $os")
    }
}

val isUberJarBuild = System.getenv("UBERJAR_BUILD") == "true"

dependencies {
    if (isUberJarBuild) {
        implementation(compose.desktop.windows_x64)
        implementation(compose.desktop.linux_x64)
        implementation(compose.desktop.linux_arm64)
        implementation(compose.desktop.macos_x64)
        implementation(compose.desktop.macos_arm64)
    } else
        implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.compose.components:components-resources:1.10.3")
    implementation("org.jetbrains.compose.material3:material3:1.10.0-alpha05")
    implementation("com.materialkolor:material-kolor:4.1.1")
    implementation("io.github.kdroidfilter:platformtools.darkmodedetector:0.7.5")

    val coroutines = "1.10.2"
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutines")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:$coroutines")

    implementation("io.insert-koin:koin-core:4.2.1")

    val mvikotlin = "4.3.0"
    implementation("com.arkivanov.mvikotlin:mvikotlin:$mvikotlin")
    implementation("com.arkivanov.mvikotlin:mvikotlin-main:$mvikotlin")
    implementation("com.arkivanov.mvikotlin:mvikotlin-extensions-coroutines:$mvikotlin")

    val ktor = "3.4.2"
    implementation("io.ktor:ktor-client-core:$ktor")
    implementation("io.ktor:ktor-client-okhttp:$ktor")
    implementation("io.ktor:ktor-client-logging:$ktor")
    implementation("io.github.lpicanco:krate-core:1.0.3")

    implementation("org.jsoup:jsoup:1.22.2")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.knowm.xchart:xchart:3.8.8")

    implementation("org.drewcarlson:qbittorrent-client:1.1.0-alpha02")
    implementation("com.github.0xboobface:open-m3u8:33c8f8e")

    val log4j = "2.25.4"
    implementation("org.apache.logging.log4j:log4j-api:$log4j")
    implementation("org.apache.logging.log4j:log4j-core:$log4j")
    implementation("org.apache.logging.log4j:log4j-slf4j2-impl:$log4j")

    val ffmpeg = "8.0.1-1.5.13"
    implementation("org.bytedeco:ffmpeg:$ffmpeg")
    implementation("org.bytedeco:ffmpeg:$ffmpeg:${os.classifier}-${arch.classifier}")

    testImplementation(kotlin("test-junit5"))
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}

compose.desktop {
    application {
        mainClass = "com.badmanners.animurglar.MainKt"

        buildTypes.release.proguard {
            version = "7.9.1"
            configurationFiles.from(project.file("proguard-rules.pro"))
            obfuscate = true
            joinOutputJars = true
        }

        nativeDistributions {
            packageName = "AniMurglar"
            description = "AniMurglar"
            vendor = "badmannersteam"

            targetFormats(if (os == OS.MACOS) TargetFormat.Dmg else TargetFormat.AppImage)

            modules("java.management")

            windows {
                iconFile.set(project.file("icons/icon.ico"))
            }
            linux {
                iconFile.set(project.file("icons/icon.png"))
            }
            macOS {
                bundleID = "com.badmanners.animurglar"
                iconFile.set(project.file("icons/icon.icns"))
            }
        }
    }
}

tasks.register<Zip>("zipReleaseAppImage") {
    dependsOn("packageReleaseAppImage")
    from(layout.buildDirectory.dir("compose/binaries/main-release/app/AniMurglar"))
    archiveFileName = "AniMurglar-$version-${os.classifier}-${arch.classifier}.zip"
    destinationDirectory = layout.buildDirectory.dir("artifacts")
}

tasks.register<Copy>("packageReleaseUberJar") {
    dependsOn("packageReleaseUberJarForCurrentOS")
    from(layout.buildDirectory.dir("compose/jars"))
    include("*-release.jar")
    into(layout.buildDirectory.dir("artifacts"))
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    rename { "AniMurglar-$version-all-platforms.jar" }
}

if (os == OS.MACOS) tasks.register<Copy>("copyReleaseDmg") {
    dependsOn("packageReleaseDmg")
    from(layout.buildDirectory.dir("compose/binaries/main-release/dmg"))
    include("*.dmg")
    into(layout.buildDirectory.dir("artifacts"))
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    rename { "AniMurglar-$version-${os.classifier}-${arch.classifier}.dmg" }
}

if (os == OS.LINUX) tasks.register("packageReleaseLinuxAppImage") {
    dependsOn("packageReleaseAppImage")
    doLast {
        val appImageTmpDir = layout.buildDirectory.dir("tmp/appimage").get().asFile
        val appDir = appImageTmpDir.resolve("AniMurglar.AppDir")

        println("Copying application files...")
        val usrDir = appDir.resolve("usr")
        usrDir.mkdirs()
        copy {
            from(layout.buildDirectory.dir("compose/binaries/main-release/app/AniMurglar"))
            into(usrDir)
        }

        println("Copying AppRun script...")
        copy {
            from(layout.projectDirectory.file("appimage/AppRun"))
            into(appDir)
            filePermissions { unix("755") }
        }

        println("Copying .desktop file...")
        val desktopFile = layout.projectDirectory.file("appimage/AniMurglar.desktop")
        copy {
            from(desktopFile)
            into(appDir)
        }
        val applicationsDir = appDir.resolve("usr/share/applications")
        applicationsDir.mkdirs()
        copy {
            from(desktopFile)
            into(applicationsDir)
        }

        println("Copying icon file...")
        val iconFile = layout.projectDirectory.file("icons/icon.png")
        copy {
            from(iconFile)
            into(appDir)
        }
        val iconsDir = appDir.resolve("usr/share/icons/hicolor/512x512/apps")
        iconsDir.mkdirs()
        copy {
            from(iconFile)
            into(iconsDir)
        }

        val appImageTool = appImageTmpDir.resolve("appimagetool-x86_64.AppImage")
        if (!appImageTool.exists()) {
            println("Downloading appimagetool...")
            HttpClient.newBuilder().followRedirects(Redirect.ALWAYS).build().use { client ->
                val request = HttpRequest.newBuilder(
                    URI.create(
                        "https://github.com/AppImage/AppImageKit/releases/download/continuous/appimagetool-x86_64.AppImage"
                    )
                ).build()
                client.send(request, BodyHandlers.ofFile(appImageTool.toPath())).body()
            }
            appImageTool.setExecutable(true)
        }

        println("Building AppImage...")
        serviceOf<ExecOperations>().exec {
            environment("ARCH", "x86_64")
            environment("APPIMAGE_EXTRACT_AND_RUN", "1")
            workingDir = appImageTmpDir
            commandLine = listOf(
                appImageTool.absolutePath,
                "--verbose",
                appDir.absolutePath
            )
        }

        println("AppImage created successfully")
        copy {
            from(appImageTmpDir.resolve("AniMurglar-x86_64.AppImage"))
            into(layout.buildDirectory.dir("artifacts"))
            rename { "AniMurglar-$version-${os.classifier}-${arch.classifier}.appimage" }
        }
    }
}

tasks.register("release") {
    dependsOn(
        when {
            isUberJarBuild -> "packageReleaseUberJar"
            os == OS.MACOS -> "copyReleaseDmg"
            os == OS.LINUX -> "packageReleaseLinuxAppImage"
            else -> "zipReleaseAppImage"
        }
    )
}
