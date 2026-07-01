package com.badmanners.animurglar.dubs

import kotlinx.serialization.Serializable

enum class DubEpisodeFormat {
    MP4, HLS
}

@Serializable
data class DubEpisode(
    val episodeNumber: Int,
    val sourceUrl: String,
    val format: DubEpisodeFormat
)

@Serializable
data class ResolvedDubEpisode(
    val episodeNumber: Int,
    val resolvedUrl: String,
    val format: DubEpisodeFormat
)

@Serializable
data class DubAnimeCandidate(
    val sourceId: String,
    val id: String,
    val webUrl: String,
    val name: String,
    val russianName: String?,
    val description: String?,
    val releaseDate: String?,
) {
    val key: String
        get() = "$sourceId:$id"
}

@Serializable
data class DubInfo(
    val sourceId: String,
    val teamName: String,
    val languageTag: String,
    val views: Long?,
    val episodes: List<DubEpisode>,
) {
    val key: String
        get() = "${sourceId}:${teamName.lowercase()}"
}

interface DubsSource {
    val sourceId: String

    suspend fun searchAnime(query: String): List<DubAnimeCandidate>

    suspend fun loadDubs(candidate: DubAnimeCandidate): List<DubInfo>

    suspend fun resolve(episode: DubEpisode): ResolvedDubEpisode
}
