package com.badmanners.animurglar.downloader

import com.badmanners.animurglar.downloader.DownloadProgressEvent.DownloadProgressGroup
import com.badmanners.animurglar.subtitles.Anime365SubtitlesGateway
import com.badmanners.animurglar.subtitles.DownloadedSubtitleEpisode
import com.badmanners.animurglar.subtitles.ResolvedSubtitleEpisode
import com.badmanners.animurglar.subtitles.SubtitleCaptionFilterService
import com.badmanners.animurglar.subtitles.SubtitleEpisode
import com.badmanners.animurglar.subtitles.SubtitleFont
import com.badmanners.animurglar.subtitles.SubtitleTeamInfo
import com.badmanners.animurglar.utils.BROWSER_USER_AGENT
import com.badmanners.animurglar.utils.suspendRunCatching
import io.ktor.client.HttpClient
import io.ktor.client.plugins.onDownload
import io.ktor.client.request.headers
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.util.cio.writeChannel
import io.ktor.utils.io.copyAndClose
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.apache.logging.log4j.LogManager
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.fileSize


class SubtitleDownloadService(
    private val client: HttpClient,
    private val subtitlesGateway: Anime365SubtitlesGateway,
    private val subtitleCaptionFilterService: SubtitleCaptionFilterService,
) {

    private val logger = LogManager.getLogger(SubtitleDownloadService::class.java)

    suspend fun download(
        request: DownloadRequest,
        paths: DownloadPaths,
        onProgress: (DownloadProgressEvent) -> Unit,
    ): Map<Int, List<DownloadedSubtitleEpisode>> {
        if (request.selectedSubtitles.isEmpty()) {
            return emptyMap()
        }

        val jobs = buildDownloadJobs(request, paths)
        val results = coroutineScope {
            val semaphore = Semaphore(2)
            jobs.map { job ->
                async {
                    semaphore.withPermit {
                        downloadSubtitle(job, onProgress)
                    }
                }
            }.awaitAll()
        }

        return results.filterNotNull().groupBy { it.episodeNumber }
    }

    private fun buildDownloadJobs(request: DownloadRequest, paths: DownloadPaths): List<SubtitleDownloadJob> {
        return request.selectedSubtitles.flatMap { team ->
            val episodesByNumber = team.episodes.associateBy { it.episodeNumber }
            request.selectedEpisodes.mapNotNull { outputEpisodeNumber ->
                val sourceEpisodeNumber = request.episodeMapping.dubEpisodeNumber(outputEpisodeNumber)
                val episode = episodesByNumber[sourceEpisodeNumber] ?: return@mapNotNull null
                SubtitleDownloadJob(
                    outputEpisodeNumber = outputEpisodeNumber,
                    team = team,
                    episode = episode,
                    paths = paths.episodePaths(outputEpisodeNumber),
                )
            }
        }
    }

    private suspend fun downloadSubtitle(
        job: SubtitleDownloadJob,
        onProgress: (DownloadProgressEvent) -> Unit,
    ): DownloadedSubtitleEpisode? {
        val itemKey = "${job.team.key}:${job.outputEpisodeNumber}"
        val itemLabel = "${job.team.teamName} · ${job.paths.episodeTag}"

        val resolved = suspendRunCatching { subtitlesGateway.resolveEpisode(job.episode) }
            .getOrElse { throwable ->
                logger.warn("Failed to resolve Anime365 subtitle ${job.episode.translationId}: ${throwable.message}", throwable)
                return null
            } ?: return null

        val fullPath = job.paths.fullSubtitlePath(job.team)
        val captionsOnlyPath = job.paths.captionsOnlySubtitlePath(job.team)
        val fonts = downloadFonts(job, resolved)

        downloadFile(
            sourceUrl = resolved.subtitlesUrl,
            referer = resolved.embedUrl,
            destination = fullPath,
            itemKey = itemKey,
            itemLabel = itemLabel,
            progressEpisodeNumber = job.outputEpisodeNumber,
            team = job.team,
            onProgress = onProgress,
        )
        subtitleCaptionFilterService.writeCaptionsOnlySubtitle(fullPath = fullPath, captionsOnlyPath = captionsOnlyPath)

        onProgress(
            DownloadProgressEvent(
                group = DownloadProgressGroup.SUBTITLE_FILES,
                itemKey = itemKey,
                label = itemLabel,
                progress = 1.0,
                episodeNumber = job.outputEpisodeNumber,
                sourceId = job.team.sourceId,
                teamName = job.team.teamName,
                downloadedBytes = fullPath.fileSize() + captionsOnlyPath.fileSize() + fonts.sumOf { it.fileSize() },
            )
        )

        return DownloadedSubtitleEpisode(
            sourceId = job.team.sourceId,
            teamName = job.team.teamName,
            languageTag = job.team.languageTag,
            episodeNumber = job.outputEpisodeNumber,
            fullPath = fullPath,
            captionsOnlyPath = captionsOnlyPath,
            fontPaths = fonts,
        )
    }

    private suspend fun downloadFonts(
        job: SubtitleDownloadJob,
        resolved: ResolvedSubtitleEpisode,
    ): List<Path> {
        return resolved.fonts.map { font ->
            val destination = job.paths.subtitleFontPath(job.team, font)
            downloadFont(font = font, referer = resolved.embedUrl, destination = destination)
            destination
        }
    }

    private suspend fun downloadFont(font: SubtitleFont, referer: String, destination: Path) {
        downloadFile(
            sourceUrl = font.url,
            referer = referer,
            destination = destination,
            itemKey = "font:${font.fileName}",
            itemLabel = font.fileName,
            progressEpisodeNumber = null,
            team = null,
            onProgress = {},
        )
    }

    private suspend fun downloadFile(
        sourceUrl: String,
        referer: String,
        destination: Path,
        itemKey: String,
        itemLabel: String,
        progressEpisodeNumber: Int?,
        team: SubtitleTeamInfo?,
        onProgress: (DownloadProgressEvent) -> Unit,
    ) {
        destination.parent.createDirectories()
        if (destination.exists() && destination.fileSize() > 0L) {
            return
        }
        destination.deleteIfExists()

        val speedTracker = DownloadSpeedTracker()
        client.prepareGet(sourceUrl) {
            headers {
                append("referer", referer)
                append("user-agent", BROWSER_USER_AGENT)
            }
            onDownload { bytesSentTotal, contentLength ->
                val downloaded = bytesSentTotal.coerceAtLeast(0L)
                val totalBytes = contentLength?.takeIf { it > 0 }
                val speedBytesPerSecond = speedTracker.update(downloadedBytes = downloaded)
                val progress = totalBytes
                    ?.let { (downloaded.toDouble() / it.toDouble()).coerceIn(0.0, 1.0) }
                    ?: 0.0

                onProgress(
                    DownloadProgressEvent(
                        group = DownloadProgressGroup.SUBTITLE_FILES,
                        itemKey = itemKey,
                        label = itemLabel,
                        progress = progress,
                        episodeNumber = progressEpisodeNumber,
                        sourceId = team?.sourceId,
                        teamName = team?.teamName,
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

            response.bodyAsChannel().copyAndClose(destination.toFile().writeChannel())
        }
    }

    private data class SubtitleDownloadJob(
        val outputEpisodeNumber: Int,
        val team: SubtitleTeamInfo,
        val episode: SubtitleEpisode,
        val paths: DownloadPaths.EpisodePaths,
    )

}