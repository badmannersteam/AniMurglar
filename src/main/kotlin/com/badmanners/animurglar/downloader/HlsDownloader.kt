package com.badmanners.animurglar.downloader

import com.badmanners.animurglar.app.config.AppConfig
import com.badmanners.animurglar.downloader.DownloadProgressEvent.DownloadProgressGroup
import com.badmanners.animurglar.dubs.DubInfo
import com.badmanners.animurglar.ffmpeg.FfmpegService
import com.badmanners.animurglar.utils.deleteRecursivelyIfExists
import com.badmanners.animurglar.utils.executeWithProxyFallback
import com.iheartradio.m3u8.Encoding.UTF_8
import com.iheartradio.m3u8.Format.EXT_M3U
import com.iheartradio.m3u8.PlaylistParser
import com.iheartradio.m3u8.data.MediaPlaylist
import com.iheartradio.m3u8.data.Playlist
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.onDownload
import io.ktor.client.request.get
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.util.cio.writeChannel
import io.ktor.utils.io.copyAndClose
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.apache.logging.log4j.LogManager
import java.io.ByteArrayInputStream
import java.net.URI
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.fileSize


class HlsDownloader(
    private val client: HttpClient,
    private val directClient: HttpClient,
    private val config: AppConfig,
    private val ffmpegService: FfmpegService,
) {
    private val logger = LogManager.getLogger(HlsDownloader::class.java)

    suspend fun download(
        itemKey: String,
        itemLabel: String,
        dubInfo: DubInfo,
        progressEpisodeNumber: Int,
        manifestUrl: String,
        rawVideoDestination: Path,
        chunksDir: Path,
        destination: Path,
        onProgress: (DownloadProgressEvent) -> Unit,
    ) = coroutineScope {
        val (mediaPlaylistUrl, mediaPlaylist) = resolveMediaPlaylist(manifestUrl)
        val segments = extractSegments(mediaPlaylistUrl, mediaPlaylist)
        check(segments.isNotEmpty()) {
            "No HLS segments found for $mediaPlaylistUrl"
        }

        val maxChunkConcurrency = config.downloader.maxParallelHlsChunks.coerceAtLeast(1)
        val segmentSemaphore = Semaphore(maxChunkConcurrency)
        val completed = AtomicInteger(0)
        val downloadedBytes = AtomicLong(0)
        val speedTracker = DownloadSpeedTracker()
        chunksDir.parent.createDirectories()
        destination.parent.createDirectories()

        logger.debug(
            "Starting HLS dub download {} | playlist={} | segments={} | chunkConcurrency={} | chunksDir={} | mp4={}",
            itemLabel, mediaPlaylistUrl, segments.size, maxChunkConcurrency, chunksDir, rawVideoDestination
        )

        if (!rawVideoDestination.exists() || rawVideoDestination.fileSize() == 0L) {
            chunksDir.deleteRecursivelyIfExists()
            chunksDir.createDirectories()

            val orderedSegments = segments.mapIndexed { index, segmentUrl ->
                async(Dispatchers.IO) {
                    segmentSemaphore.withPermit {
                        val segmentPath = chunksDir.resolve("${index.toString().padStart(5, '0')}.ts")
                        var reportedSegmentBytes = 0L

                        val activeClient = if (config.proxy.enabled && config.proxy.host.isNotBlank()) client else directClient
                        activeClient.prepareGet(segmentUrl) {
                            onDownload { bytesSentTotal, contentLength ->
                                val currentSegmentBytes = bytesSentTotal.coerceAtLeast(0L)
                                val delta = (currentSegmentBytes - reportedSegmentBytes).coerceAtLeast(0L)
                                if (delta > 0L) {
                                    downloadedBytes.addAndGet(delta)
                                }
                                if (currentSegmentBytes > reportedSegmentBytes) {
                                    reportedSegmentBytes = currentSegmentBytes
                                }

                                val bytes = downloadedBytes.get()
                                val speedBytesPerSecond = speedTracker.update(downloadedBytes = bytes)
                                val contentSize = contentLength?.takeIf { it > 0 }
                                val segmentProgress = contentSize
                                    ?.let { (currentSegmentBytes.toDouble() / it.toDouble()).coerceIn(0.0, 1.0) }
                                    ?: 0.0
                                val progress =
                                    ((completed.get().toDouble() + segmentProgress) / segments.size.toDouble())
                                        .coerceIn(0.0, 1.0)

                                onProgress(
                                    DownloadProgressEvent(
                                        group = DownloadProgressGroup.DUB_FILES,
                                        itemKey = itemKey,
                                        label = itemLabel,
                                        progress = progress,
                                        episodeNumber = progressEpisodeNumber,
                                        sourceId = dubInfo.sourceId,
                                        teamName = dubInfo.teamName,
                                        downloadedBytes = bytes,
                                        totalBytes = null,
                                        speedBytesPerSecond = speedBytesPerSecond,
                                    )
                                )
                            }
                        }.execute { response ->
                            check(response.status.isSuccess()) {
                                "Unexpected HTTP status ${response.status.value} for $segmentUrl"
                            }

                            response.bodyAsChannel().copyAndClose(segmentPath.toFile().writeChannel())
                        }

                        val actualSegmentSize = segmentPath.fileSize()
                        val missedBytes = (actualSegmentSize - reportedSegmentBytes).coerceAtLeast(0L)
                        val bytes = downloadedBytes.addAndGet(missedBytes)

                        val done = completed.incrementAndGet()
                        val speedBytesPerSecond = speedTracker.update(downloadedBytes = bytes)
                        onProgress(
                            DownloadProgressEvent(
                                group = DownloadProgressGroup.DUB_FILES,
                                itemKey = itemKey,
                                label = itemLabel,
                                progress = done.toDouble() / segments.size.toDouble(),
                                episodeNumber = progressEpisodeNumber,
                                sourceId = dubInfo.sourceId,
                                teamName = dubInfo.teamName,
                                downloadedBytes = bytes,
                                totalBytes = null,
                                speedBytesPerSecond = speedBytesPerSecond,
                            )
                        )

                        index to segmentPath
                    }
                }
            }.awaitAll()
                .sortedBy { (index, _) -> index }
                .map { (_, segmentPath) -> segmentPath }

            logger.debug(
                "Finished HLS segment downloads for {} | segments={} | bytes={}",
                itemLabel, segments.size, downloadedBytes.get()
            )
            logger.debug("Prepared ordered HLS segments for {} | count={}", itemLabel, orderedSegments.size)

            withContext(Dispatchers.IO) {
                logger.debug("Starting remuxHlsSegmentsToMp4 for {} | mp4={}", itemLabel, rawVideoDestination)
                ffmpegService.remuxHlsSegmentsToMp4(
                    segmentFiles = orderedSegments,
                    destinationMp4 = rawVideoDestination,
                    workDir = chunksDir,
                )
                logger.debug("Finished remuxHlsSegmentsToMp4 for {}", itemLabel)
            }

            chunksDir.deleteRecursivelyIfExists()
        }

        withContext(Dispatchers.IO) {
            logger.debug(
                "Starting extractAudioFromMp4 for {} | sourceMp4={} | destinationAudio={}",
                itemLabel, rawVideoDestination, destination
            )
            ffmpegService.extractAudioFromMp4(
                inputMp4 = rawVideoDestination,
                outputAudio = destination,
                workDir = destination.parent,
            )
            logger.debug("Finished extractAudioFromMp4 for {}", itemLabel)
        }

        val finalSize = destination.fileSize()

        logger.debug(
            "Completed HLS dub pipeline for {} | output={} | size={} bytes",
            itemLabel, destination, finalSize
        )

        onProgress(
            DownloadProgressEvent(
                group = DownloadProgressGroup.DUB_FILES,
                itemKey = itemKey,
                label = itemLabel,
                progress = 1.0,
                episodeNumber = progressEpisodeNumber,
                sourceId = dubInfo.sourceId,
                teamName = dubInfo.teamName,
                downloadedBytes = finalSize,
                totalBytes = finalSize,
                speedBytesPerSecond = 0,
            )
        )
    }

    private suspend fun resolveMediaPlaylist(manifestUrl: String): Pair<String, MediaPlaylist> {
        val playlist = parsePlaylist(manifestUrl)
        if (playlist.hasMediaPlaylist()) {
            return manifestUrl to playlist.mediaPlaylist
        }

        val masterPlaylist = playlist.masterPlaylist
            ?: error("Expected HLS media or master playlist at $manifestUrl")

        val selectedVariant = masterPlaylist.playlists.firstOrNull()
            ?: error("Master playlist at $manifestUrl does not contain variants")

        val mediaPlaylistUrl = resolveUri(manifestUrl, selectedVariant.uri)
        val mediaPlaylist = parsePlaylist(mediaPlaylistUrl).mediaPlaylist
            ?: error("Resolved playlist is not media playlist: $mediaPlaylistUrl")

        return mediaPlaylistUrl to mediaPlaylist
    }

    private suspend fun parsePlaylist(url: String): Playlist {
        val payload = client.executeWithProxyFallback(directClient) { get(url).body<ByteArray>() }
        return ByteArrayInputStream(payload).use { stream ->
            PlaylistParser(stream, EXT_M3U, UTF_8).parse()
        }
    }

    private fun extractSegments(playlistUrl: String, playlist: MediaPlaylist): List<String> {
        val segments = mutableListOf<String>()
        var mapAdded = false

        playlist.tracks.forEach { item ->
            val track = item ?: return@forEach
            if (!mapAdded && track.hasMapInfo()) {
                val mapUri = track.mapInfo?.uri
                if (!mapUri.isNullOrBlank()) {
                    segments += resolveUri(playlistUrl, mapUri)
                    mapAdded = true
                }
            }
            val segmentUri = track.uri
            if (!segmentUri.isNullOrBlank()) {
                segments += resolveUri(playlistUrl, segmentUri)
            }
        }

        return segments
    }

    private fun resolveUri(baseUrl: String, child: String): String {
        if (child.startsWith("http://") || child.startsWith("https://")) {
            return child
        }
        return URI(baseUrl).resolve(child).toString()
    }
}
