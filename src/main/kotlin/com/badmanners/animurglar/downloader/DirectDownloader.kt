package com.badmanners.animurglar.downloader

import com.badmanners.animurglar.downloader.DownloadProgressEvent.DownloadProgressGroup
import com.badmanners.animurglar.dubs.DubInfo
import com.badmanners.animurglar.ffmpeg.FfmpegService
import io.ktor.client.HttpClient
import io.ktor.client.plugins.onDownload
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.util.cio.writeChannel
import io.ktor.utils.io.copyAndClose
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.fileSize


class DirectDownloader(
    private val client: HttpClient,
    private val ffmpegService: FfmpegService,
) {
    suspend fun download(
        itemKey: String,
        itemLabel: String,
        dubInfo: DubInfo,
        progressEpisodeNumber: Int,
        sourceUrl: String,
        rawVideoDestination: Path,
        destination: Path,
        onProgress: (DownloadProgressEvent) -> Unit,
    ) {
        val speedTracker = DownloadSpeedTracker()
        rawVideoDestination.parent.createDirectories()
        destination.parent.createDirectories()

        if (!rawVideoDestination.exists() || rawVideoDestination.fileSize() == 0L) {
            rawVideoDestination.deleteIfExists()

            client.prepareGet(sourceUrl) {
                onDownload { bytesSentTotal, contentLength ->
                    val downloaded = bytesSentTotal.coerceAtLeast(0L)
                    val totalBytes = contentLength?.takeIf { it > 0 }
                    val speedBytesPerSecond = speedTracker.update(downloadedBytes = downloaded)
                    val progress = totalBytes
                        ?.let { (downloaded.toDouble() / it.toDouble()).coerceIn(0.0, 1.0) }
                        ?: 0.0

                    onProgress(
                        DownloadProgressEvent(
                            group = DownloadProgressGroup.DUB_FILES,
                            itemKey = itemKey,
                            label = itemLabel,
                            progress = progress,
                            episodeNumber = progressEpisodeNumber,
                            sourceId = dubInfo.sourceId,
                            teamName = dubInfo.teamName,
                            downloadedBytes = downloaded,
                            totalBytes = totalBytes,
                            speedBytesPerSecond = speedBytesPerSecond,
                        )
                    )
                }
            }.execute { response ->
                check(response.status.isSuccess()) {
                    "Unexpected HTTP status ${response.status.value} for $sourceUrl"
                }

                response.bodyAsChannel().copyAndClose(rawVideoDestination.toFile().writeChannel())
            }
        }

        withContext(Dispatchers.IO) {
            ffmpegService.extractAudioFromMp4(
                inputMp4 = rawVideoDestination,
                outputAudio = destination,
                workDir = destination.parent,
            )
        }

        val downloadedBytes = destination.fileSize()

        onProgress(
            DownloadProgressEvent(
                group = DownloadProgressGroup.DUB_FILES,
                itemKey = itemKey,
                label = itemLabel,
                progress = 1.0,
                episodeNumber = progressEpisodeNumber,
                sourceId = dubInfo.sourceId,
                teamName = dubInfo.teamName,
                downloadedBytes = downloadedBytes,
                totalBytes = downloadedBytes,
                speedBytesPerSecond = 0,
            )
        )
    }
}
