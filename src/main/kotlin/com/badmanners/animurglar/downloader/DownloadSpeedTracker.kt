package com.badmanners.animurglar.downloader

import java.util.concurrent.atomic.AtomicLong

class DownloadSpeedTracker(
    private val sampleIntervalMillis: Long = 100L,
) {
    private val lastTimeMillis = AtomicLong(0L)
    private val lastBytes = AtomicLong(0L)
    private val bytesPerSecondSpeed = AtomicLong(0L)

    fun update(downloadedBytes: Long, currentTimeMillis: Long = System.currentTimeMillis()): Long {
        while (true) {
            val previousTimeMillis = lastTimeMillis.get()
            if (currentTimeMillis - previousTimeMillis < sampleIntervalMillis) {
                return bytesPerSecondSpeed.get()
            }

            val previousBytes = lastBytes.get()
            val nextSpeed = calculateBytesPerSecondSpeed(
                downloadedBytes = downloadedBytes,
                previousBytes = previousBytes,
                currentTimeMillis = currentTimeMillis,
                previousTimeMillis = previousTimeMillis,
            )

            if (lastTimeMillis.compareAndSet(previousTimeMillis, currentTimeMillis)) {
                lastBytes.set(downloadedBytes)
                bytesPerSecondSpeed.set(nextSpeed)
                return nextSpeed
            }
        }
    }

    private fun calculateBytesPerSecondSpeed(
        downloadedBytes: Long,
        previousBytes: Long,
        currentTimeMillis: Long,
        previousTimeMillis: Long,
    ): Long {
        if (previousTimeMillis <= 0L) {
            return 0L
        }
        val millisDiff = (currentTimeMillis - previousTimeMillis).coerceAtLeast(1L).toDouble()
        val bytesDiff = (downloadedBytes - previousBytes).coerceAtLeast(0L).toDouble()
        return (1000.0 / millisDiff * bytesDiff).toLong()
    }
}
