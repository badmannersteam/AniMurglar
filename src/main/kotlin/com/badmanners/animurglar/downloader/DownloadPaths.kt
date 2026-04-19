package com.badmanners.animurglar.downloader

import com.badmanners.animurglar.dubs.DubInfo
import com.badmanners.animurglar.utils.episodeTag
import com.badmanners.animurglar.utils.normalizePathSegment
import java.nio.file.Path

data class DownloadPaths(
    val titleSegment: String,
    val titleRoot: Path,
    val downloadsRoot: Path,
    val torrentRoot: Path,
    val dubsRoot: Path,
    val processingRoot: Path,
) {
    fun episodePaths(episodeNumber: Int): EpisodePaths {
        val episodeTag = episodeTag(episodeNumber)
        return EpisodePaths(
            episodeNumber = episodeNumber,
            episodeTag = episodeTag,
            dubsRoot = dubsRoot,
        )
    }

    data class EpisodePaths(
        val episodeNumber: Int,
        val episodeTag: String,
        val dubsRoot: Path,
    ) {
        fun dubTeamDir(dubInfo: DubInfo): Path = dubsRoot.resolve(teamSegment(dubInfo))

        fun dubRawRoot(dubInfo: DubInfo): Path = dubTeamDir(dubInfo).resolve("raw")

        fun dubAudioRoot(dubInfo: DubInfo): Path = dubTeamDir(dubInfo).resolve("audio")

        fun dubEpisodeRawVideoPath(dubInfo: DubInfo): Path = dubRawRoot(dubInfo).resolve("$episodeTag.mp4")

        fun dubEpisodeChunksDir(dubInfo: DubInfo): Path = dubRawRoot(dubInfo).resolve("$episodeTag-chunks")

        fun dubEpisodeAudioPath(dubInfo: DubInfo): Path = dubAudioRoot(dubInfo).resolve("$episodeTag.m4a")

        private fun teamSegment(dubInfo: DubInfo): String =
            normalizePathSegment("${dubInfo.sourceId}-${dubInfo.teamName}")
    }

    companion object {
        fun fromRequest(request: DownloadRequest, tempDir: Path): DownloadPaths {
            require(request.title.isNotBlank()) { "Download request title is empty." }

            val titleSegment = normalizePathSegment(request.title)
            val titleRoot = tempDir.resolve(titleSegment)
            val downloadsRoot = titleRoot.resolve("downloads")
            return DownloadPaths(
                titleSegment = titleSegment,
                titleRoot = titleRoot,
                downloadsRoot = downloadsRoot,
                torrentRoot = downloadsRoot.resolve("torrent"),
                dubsRoot = downloadsRoot.resolve("dubs"),
                processingRoot = titleRoot.resolve("processing"),
            )
        }
    }
}