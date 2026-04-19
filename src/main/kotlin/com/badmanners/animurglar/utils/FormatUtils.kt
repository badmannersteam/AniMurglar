package com.badmanners.animurglar.utils

import java.util.Locale

fun Long.toReadableSize(): String {
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = toDouble()
    var unitIndex = 0
    while (value >= 1024.0 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex++
    }

    return if (unitIndex == 0) {
        "$this B"
    } else {
        String.format(Locale.US, "%.1f %s", value, units[unitIndex])
    }
}

fun Long?.toReadableSizeOrUnknown(): String = this?.toReadableSize() ?: "?"