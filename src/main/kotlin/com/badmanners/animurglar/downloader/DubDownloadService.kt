package com.badmanners.animurglar.downloader

import com.badmanners.animurglar.app.config.AppConfig
import com.badmanners.animurglar.downloader.DownloadProgressEvent.DownloadProgressGroup
import com.badmanners.animurglar.dubs.DubEpisode
import com.badmanners.animurglar.dubs.DubEpisodeFormat
import com.badmanners.animurglar.dubs.DubInfo
import com.badmanners.animurglar.dubs.DubsGateway
import com.badmanners.animurglar.pipeline.DownloadedDubEpisode
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.fileSize

class DubDownloadService(
    private val config: AppConfig,
    private val dubsGateway: DubsGateway,
    private val directDownloader: DirectDownloader,
    private val hlsDownloader: HlsDownloader,
) {
    suspend fun download(
        request: DownloadRequest,
        paths: DownloadPaths,
        onProgress: (DownloadProgressEvent) -> Unit,
    ): Map<Int, List<DownloadedDubEpisode>> = coroutineScope {
        val semaphore = Semaphore(config.downloader.maxParallelDubTasks.coerceAtLeast(1))
        val tasks = mutableListOf<Deferred<DownloadedDubEpisode>>()

        request.selectedDubs.forEach { dubInfo ->
            val episodesByNumber = dubInfo.episodes.associateBy { it.episodeNumber }
            request.selectedEpisodes.sorted().forEach { torrentEpisodeNumber ->
                val dubEpisodeNumber = request.episodeMapping.dubEpisodeNumber(torrentEpisodeNumber)
                val episode = episodesByNumber[dubEpisodeNumber] ?: return@forEach
                val itemKey = dubItemKey(dubInfo = dubInfo, episodeNumber = torrentEpisodeNumber)
                val itemLabel = dubItemLabel(dubInfo, torrentEpisodeNumber, dubEpisodeNumber)
                onProgress(
                    DownloadProgressEvent(
                        group = DownloadProgressGroup.DUB_FILES,
                        itemKey = itemKey,
                        label = itemLabel,
                        progress = 0.0,
                        episodeNumber = torrentEpisodeNumber,
                        sourceId = dubInfo.sourceId,
                        teamName = dubInfo.teamName,
                        downloadedBytes = 0,
                        speedBytesPerSecond = 0,
                    )
                )

                tasks += async(Dispatchers.IO) {
                    semaphore.withPermit {
                        downloadDubEpisode(
                            paths = paths,
                            dubInfo = dubInfo,
                            torrentEpisodeNumber = torrentEpisodeNumber,
                            episode = episode,
                            onProgress = onProgress,
                        )
                    }
                }
            }
        }

        tasks.awaitAll()
            .groupBy { it.episodeNumber }
            .toSortedMap()
    }

    private suspend fun downloadDubEpisode(
        paths: DownloadPaths,
        dubInfo: DubInfo,
        torrentEpisodeNumber: Int,
        episode: DubEpisode,
        onProgress: (DownloadProgressEvent) -> Unit,
    ): DownloadedDubEpisode {
        val itemKey = dubItemKey(dubInfo = dubInfo, episodeNumber = torrentEpisodeNumber)
        val itemLabel = dubItemLabel(dubInfo, torrentEpisodeNumber, episode.episodeNumber)

        val episodePaths = paths.episodePaths(torrentEpisodeNumber)
        val rawRoot = episodePaths.dubRawRoot(dubInfo)
        val audioRoot = episodePaths.dubAudioRoot(dubInfo)
        rawRoot.createDirectories()
        audioRoot.createDirectories()

        val resolvedEpisode = dubsGateway.resolve(dubInfo.sourceId, episode)

        val destination = episodePaths.dubEpisodeAudioPath(dubInfo)
        val rawVideoPath = episodePaths.dubEpisodeRawVideoPath(dubInfo)
        val chunksDir = episodePaths.dubEpisodeChunksDir(dubInfo)

        if (destination.exists() && destination.fileSize() > 0) {
            val existingSize = destination.fileSize()
            onProgress(
                DownloadProgressEvent(
                    group = DownloadProgressGroup.DUB_FILES,
                    itemKey = itemKey,
                    label = itemLabel,
                    progress = 1.0,
                    episodeNumber = torrentEpisodeNumber,
                    sourceId = dubInfo.sourceId,
                    teamName = dubInfo.teamName,
                    downloadedBytes = existingSize,
                    totalBytes = existingSize,
                    speedBytesPerSecond = 0,
                )
            )
            return DownloadedDubEpisode(
                sourceId = dubInfo.sourceId,
                teamName = dubInfo.teamName,
                languageTag = dubInfo.languageTag,
                episodeNumber = torrentEpisodeNumber,
                mediaPath = destination,
                format = resolvedEpisode.format,
            )
        }

        when (resolvedEpisode.format) {
            DubEpisodeFormat.MP4 -> directDownloader.download(
                itemKey = itemKey,
                itemLabel = itemLabel,
                dubInfo = dubInfo,
                progressEpisodeNumber = torrentEpisodeNumber,
                sourceUrl = resolvedEpisode.resolvedUrl,
                rawVideoDestination = rawVideoPath,
                destination = destination,
                onProgress = onProgress,
            )

            DubEpisodeFormat.HLS -> hlsDownloader.download(
                itemKey = itemKey,
                itemLabel = itemLabel,
                dubInfo = dubInfo,
                progressEpisodeNumber = torrentEpisodeNumber,
                manifestUrl = resolvedEpisode.resolvedUrl,
                rawVideoDestination = rawVideoPath,
                chunksDir = chunksDir,
                destination = destination,
                onProgress = onProgress,
            )
        }

        return DownloadedDubEpisode(
            sourceId = dubInfo.sourceId,
            teamName = dubInfo.teamName,
            languageTag = dubInfo.languageTag,
            episodeNumber = torrentEpisodeNumber,
            mediaPath = destination,
            format = resolvedEpisode.format,
        )
    }


    private fun dubItemKey(dubInfo: DubInfo, episodeNumber: Int): String =
        "dub:${dubInfo.sourceId}:${dubInfo.teamName.lowercase()}:$episodeNumber"

    private fun dubItemLabel(dubInfo: DubInfo, torrentEpisodeNumber: Int, dubEpisodeNumber: Int): String = when {
        torrentEpisodeNumber == dubEpisodeNumber -> "Серия $torrentEpisodeNumber: ${dubInfo.sourceId}/${dubInfo.teamName}"
        else -> "Серия $torrentEpisodeNumber ← dub $dubEpisodeNumber: ${dubInfo.sourceId}/${dubInfo.teamName}"
    }
}
