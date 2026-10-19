package com.badmanners.animurglar.downloader

import com.badmanners.animurglar.app.config.AppConfig
import com.badmanners.animurglar.pipeline.DownloadBatch
import com.badmanners.animurglar.pipeline.DownloadedEpisodeAssets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlin.io.path.createDirectories
import kotlin.io.path.isReadable
import kotlin.io.path.isRegularFile

class DownloadCoordinator(
    private val config: AppConfig,
    private val torrentDownloadService: TorrentDownloadService,
    private val dubDownloadService: DubDownloadService,
    private val subtitleDownloadService: SubtitleDownloadService,
) {
    suspend fun download(request: DownloadRequest, onProgress: (DownloadProgressEvent) -> Unit): DownloadBatch {
        require(request.selectedEpisodes.isNotEmpty()) { "No episodes selected for download." }
        require(request.selectedDubs.isNotEmpty()) { "No dubs selected for download." }

        val paths = DownloadPaths.fromRequest(request = request, tempDir = config.tempDir)
        paths.titleRoot.createDirectories()

        val torrentDeferred = coroutineScope {
            val torrentJob = async {
                when (val source = request.rawSource) {
                    is RawVideoSource.Nyaa -> torrentDownloadService.download(
                        request = request,
                        paths = paths,
                        onProgress = onProgress,
                    )

                    is RawVideoSource.Local -> withContext(Dispatchers.IO) {
                        request.selectedEpisodes.associateWith { episodeNumber ->
                            val path = source.files.getValue(episodeNumber)
                            require(path.isRegularFile() && path.isReadable()) {
                                "Локальный файл серии $episodeNumber недоступен: $path"
                            }
                            path
                        }
                    }
                }
            }
            val dubsJob = async {
                dubDownloadService.download(
                    request = request,
                    paths = paths,
                    onProgress = onProgress,
                )
            }
            val subtitlesJob = async {
                subtitleDownloadService.download(
                    request = request,
                    paths = paths,
                    onProgress = onProgress,
                )
            }

            val rawByEpisode = torrentJob.await()
            val dubsByEpisode = dubsJob.await()
            val subtitlesByEpisode = subtitlesJob.await()

            request.selectedEpisodes.toSortedSet().map { episodeNumber ->
                val rawVideoPath = rawByEpisode[episodeNumber]
                    ?: error("Raw file was not prepared for episode $episodeNumber")
                val subtitleTracks = subtitlesByEpisode[episodeNumber].orEmpty()
                DownloadedEpisodeAssets(
                    episodeNumber = episodeNumber,
                    rawVideoPath = rawVideoPath,
                    dubbedTracks = dubsByEpisode[episodeNumber].orEmpty(),
                    subtitleTracks = subtitleTracks,
                    subtitleFontPaths = subtitleTracks.flatMap { it.fontPaths }.distinctBy { it.fileName },
                )
            }
        }

        return DownloadBatch(
            title = request.title,
            episodes = torrentDeferred,
        )
    }
}
