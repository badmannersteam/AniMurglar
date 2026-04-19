import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.3.10"
    kotlin("plugin.compose") version "2.3.10"
    id("org.jetbrains.compose") version "1.10.3"
}

group = "com.badmanners"
version = "1.2"

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

dependencies {
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

tasks.register<Zip>("zipReleaseAppImage") {
    dependsOn("packageReleaseAppImage")
    from(layout.buildDirectory.dir("compose/binaries/main-release/app/AniMurglar"))
    archiveFileName = "AniMurglar-Desktop-$version-${os.classifier}-${arch.classifier}.zip"
    destinationDirectory = layout.buildDirectory.dir("artifacts")
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
            targetFormats(TargetFormat.AppImage)
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