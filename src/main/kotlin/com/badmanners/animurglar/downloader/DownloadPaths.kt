package com.badmanners.animurglar.downloader

import com.badmanners.animurglar.dubs.DubInfo
import com.badmanners.animurglar.subtitles.SubtitleFont
import com.badmanners.animurglar.subtitles.SubtitleTeamInfo
import com.badmanners.animurglar.utils.episodeTag
import com.badmanners.animurglar.utils.normalizePathSegment
import java.nio.file.Path

data class DownloadPaths(
    val titleSegment: String,
    val titleRoot: Path,
    val downloadsRoot: Path,
    val torrentRoot: Path,
    val dubsRoot: Path,
    val subtitlesRoot: Path,
    val processingRoot: Path,
) {
    fun episodePaths(episodeNumber: Int): EpisodePaths {
        val episodeTag = episodeTag(episodeNumber)
        return EpisodePaths(
            episodeNumber = episodeNumber,
            episodeTag = episodeTag,
            dubsRoot = dubsRoot,
            subtitlesRoot = subtitlesRoot,
        )
    }

    data class EpisodePaths(
        val episodeNumber: Int,
        val episodeTag: String,
        val dubsRoot: Path,
        val subtitlesRoot: Path,
    ) {
        fun dubTeamDir(dubInfo: DubInfo): Path = dubsRoot.resolve(teamSegment(dubInfo))

        fun dubRawRoot(dubInfo: DubInfo): Path = dubTeamDir(dubInfo).resolve("raw")

        fun dubAudioRoot(dubInfo: DubInfo): Path = dubTeamDir(dubInfo).resolve("audio")

        fun dubEpisodeRawVideoPath(dubInfo: DubInfo): Path = dubRawRoot(dubInfo).resolve("$episodeTag.mp4")

        fun dubEpisodeChunksDir(dubInfo: DubInfo): Path = dubRawRoot(dubInfo).resolve("$episodeTag-chunks")

        fun dubEpisodeAudioPath(dubInfo: DubInfo): Path = dubAudioRoot(dubInfo).resolve("$episodeTag.m4a")

        fun subtitlesTeamDir(subtitleTeam: SubtitleTeamInfo): Path = subtitlesRoot.resolve(teamSegment(subtitleTeam))

        fun subtitlesFontDir(subtitleTeam: SubtitleTeamInfo): Path = subtitlesTeamDir(subtitleTeam).resolve("fonts")

        fun subtitleFontPath(subtitleTeam: SubtitleTeamInfo, font: SubtitleFont): Path =
            subtitlesFontDir(subtitleTeam).resolve(font.fileName)

        fun fullSubtitlePath(subtitleTeam: SubtitleTeamInfo): Path =
            subtitlesTeamDir(subtitleTeam).resolve("$episodeTag.ass")

        fun captionsOnlySubtitlePath(subtitleTeam: SubtitleTeamInfo): Path =
            subtitlesTeamDir(subtitleTeam).resolve("$episodeTag-captions.ass")

        private fun teamSegment(dubInfo: DubInfo): String =
            normalizePathSegment("${dubInfo.sourceId}-${dubInfo.teamName}")

        private fun teamSegment(subtitleTeam: SubtitleTeamInfo): String =
            normalizePathSegment("${subtitleTeam.sourceId}-${subtitleTeam.teamName}")
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
                subtitlesRoot = downloadsRoot.resolve("subtitles"),
                processingRoot = titleRoot.resolve("processing"),
            )
        }
    }
}