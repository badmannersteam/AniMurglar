package com.badmanners.animurglar.pipeline

import com.badmanners.animurglar.dubs.DubEpisodeFormat
import java.nio.file.Path

data class DownloadedDubEpisode(
    val sourceId: String,
    val teamName: String,
    val languageTag: String,
    val episodeNumber: Int,
    val mediaPath: Path,
    val format: DubEpisodeFormat,
)

data class DownloadedEpisodeAssets(
    val episodeNumber: Int,
    val rawVideoPath: Path,
    val dubbedTracks: List<DownloadedDubEpisode> = emptyList(),
)

data class DownloadBatch(
    val title: String,
    val episodes: List<DownloadedEpisodeAssets>,
)

enum class ExtractedAudioOrigin {
    ORIGINAL,
    DUB,
}

data class ExtractedAudioAsset(
    val episodeNumber: Int,
    val origin: ExtractedAudioOrigin,
    val sourceLabel: String,
    val audioPath: Path,
)

data class SyncedDubAudio(
    val episodeNumber: Int,
    val sourceId: String,
    val teamName: String,
    val inputPath: Path,
    val syncedPath: Path,
)

data class MergeDubTrackInput(
    val sourceId: String,
    val teamName: String,
    val languageTag: String,
    val audioPath: Path,
)

data class EpisodeMergeInput(
    val episodeNumber: Int,
    val videoPath: Path,
    val dubTracks: List<MergeDubTrackInput>,
    val outputPath: Path,
)
