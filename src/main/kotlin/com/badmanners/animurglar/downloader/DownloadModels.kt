package com.badmanners.animurglar.downloader

import com.badmanners.animurglar.dubs.DubInfo
import com.badmanners.animurglar.nyaa.TorrentCandidate
import com.badmanners.animurglar.subtitles.SubtitleTeamInfo

data class DownloadRequest(
    val title: String,
    val torrent: TorrentCandidate,
    val selectedEpisodes: Set<Int>,
    val selectedDubs: List<DubInfo>,
    val episodeMapping: EpisodeMapping,
    val selectedSubtitles: List<SubtitleTeamInfo>
)

data class DownloadProgressEvent(
    val group: DownloadProgressGroup,
    val itemKey: String,
    val label: String,
    val progress: Double,
    val episodeNumber: Int? = null,
    val sourceId: String? = null,
    val teamName: String? = null,
    val downloadedBytes: Long? = null,
    val totalBytes: Long? = null,
    val speedBytesPerSecond: Long? = null,
) {
    enum class DownloadProgressGroup {
        TORRENT_FILES,
        DUB_FILES,
        SUBTITLE_FILES,
    }
}
