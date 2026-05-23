package com.badmanners.animurglar.subtitles


data class SubtitleTeamInfo(
    val sourceId: String,
    val teamName: String,
    val languageTag: String,
    val episodes: List<SubtitleEpisode>,
) {
    val key = "$sourceId:$teamName"
}

data class SubtitleEpisode(
    val episodeNumber: Int,
    val translationId: String,
    val pageUrl: String,
)

data class ResolvedSubtitleEpisode(
    val episode: SubtitleEpisode,
    val subtitlesUrl: String,
    val fonts: List<SubtitleFont>,
    val embedUrl: String,
)

data class SubtitleFont(
    val url: String,
    val fileName: String,
)

data class DownloadedSubtitleEpisode(
    val sourceId: String,
    val teamName: String,
    val languageTag: String,
    val episodeNumber: Int,
    val fullPath: java.nio.file.Path,
    val captionsOnlyPath: java.nio.file.Path,
    val fontPaths: List<java.nio.file.Path>,
)

const val ANIME365_SUBTITLES_SOURCE_ID = "Anime365"
const val RUSSIAN_SUBTITLES_LANGUAGE_TAG = "rus"