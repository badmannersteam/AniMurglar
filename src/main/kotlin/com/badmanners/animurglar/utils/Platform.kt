package com.badmanners.animurglar.utils


enum class OS {
    WINDOWS, LINUX, MACOS
}

enum class Arch {
    X64, ARM64
}

val arch = when (val osArch = System.getProperty("os.arch")) {
    "x64", "x86_64", "amd64" -> Arch.X64
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