package com.badmanners.animurglar.nyaa

import java.time.Instant


data class NyaaSearchProgress(
    val currentRequest: Int,
    val totalRequests: Int,
)

data class TorrentEntry(
    val id: String,
    val displayName: String,
    val nyaaCategory: String,
    val detailsLink: String,
    val magnetLink: String,
    val metadata: ParsedTorrentMetadata,
    val totalSizeBytes: Long,
    val seeders: Int,
    val totalDownloads: Int,
    val uploadedAt: Instant,
    val episodeMediaFiles: Map<Int, EpisodeMediaFile> = emptyMap(),
) {
    data class ParsedTorrentMetadata(
        val normalizedTitle: String,
        val season: Int? = null,
        val episodeNumber: Int? = null,
        val resolution: String? = null,
        val source: String? = null,
        val videoCodec: String? = null,
        val audioCodec: String? = null,
        val category: TorrentCategory,
    ) {
        enum class TorrentCategory {
            SEASON_PACK,
            SINGLE_EPISODE,
        }
    }

    data class EpisodeMediaFile(
        val episodeNumber: Int,
        val fileName: String,
        val fullPathInTorrent: String,
        val sizeBytes: Long,
    )
}

data class TorrentEpisodeGroup(
    val groupKey: String,
    val displayName: String,
    val profile: TorrentGroupProfile,
    val episodes: Map<Int, TorrentEntry>,
) {
    data class TorrentGroupProfile(
        val normalizedTitle: String,
        val season: Int? = null,
        val resolution: String? = null,
        val source: String? = null,
        val videoCodec: String? = null,
        val audioCodec: String? = null,
    )
}

sealed interface TorrentCandidate {
    val key: String
    val episodes: List<Int>

    data class SeasonPack(
        val entry: TorrentEntry,
        override val episodes: List<Int> = entry.episodeMediaFiles.keys.sorted(),
    ) : TorrentCandidate {
        override val key: String
            get() = "pack:${entry.id}"
    }

    data class EpisodeGroup(
        val group: TorrentEpisodeGroup,
        override val episodes: List<Int> = group.episodes.keys.sorted(),
    ) : TorrentCandidate {
        override val key: String
            get() = "group:${group.groupKey}"
    }
}
