package com.badmanners.animurglar.downloader

import com.badmanners.animurglar.dubs.DubInfo
import com.badmanners.animurglar.nyaa.TorrentCandidate
import com.badmanners.animurglar.subtitles.SubtitleTeamInfo
import java.nio.file.Path

sealed interface RawVideoSource {
    data class Nyaa(val candidate: TorrentCandidate) : RawVideoSource
    data class Local(val files: Map<Int, Path>) : RawVideoSource
}

data class DownloadRequest(
    val title: String,
    val rawSource: RawVideoSource,
    val selectedEpisodes: Set<Int>,
    val selectedDubs: List<DubInfo>,
    val episodeMapping: EpisodeMapping,
    val selectedSubtitles: List<SubtitleTeamInfo>
) {
    val torrent: TorrentCandidate
        get() {
            check(rawSource is RawVideoSource.Nyaa) { "Raw source is not a torrent." }
            return rawSource.candidate
        }
}

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
