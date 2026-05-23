package com.badmanners.animurglar.pipeline

import com.badmanners.animurglar.subtitles.DownloadedSubtitleEpisode
import com.badmanners.animurglar.utils.episodeTag
import com.badmanners.animurglar.utils.normalizePathSegment
import java.nio.file.Path

enum class ProcessingStage {
    ANALYZE,
    APPLY,
    MERGE,
}

data class ProcessingErrorEnvelope(
    val stage: ProcessingStage,
    val message: String,
    val episodeNumber: Int? = null,
    val sourceId: String? = null,
    val teamName: String? = null,
    val details: String? = null,
)

data class ProcessingDubTrackInput(
    val sourceId: String,
    val teamName: String,
    val languageTag: String,
    val mediaPath: Path,
)

data class ProcessingEpisodeInput(
    val episodeNumber: Int,
    val rawVideoPath: Path,
    val dubbedTracks: List<ProcessingDubTrackInput>,
    val subtitleTracks: List<DownloadedSubtitleEpisode>,
    val subtitleFontPaths: List<Path>,
)

data class ProcessingPaths(
    val titleSegment: String,
    val titleRoot: Path,
    val processingRoot: Path,
    val torrentRoot: Path,
    val dubsRoot: Path,
    val outputRoot: Path,
) {
    fun episodePaths(episodeNumber: Int) = EpisodePaths(
        episodeTag = episodeTag(episodeNumber),
        torrentRoot = torrentRoot,
        dubsRoot = dubsRoot,
        outputPath = outputRoot.resolve("$titleSegment - ${episodeTag(episodeNumber)}.mkv"),
    )

    data class EpisodePaths(
        val episodeTag: String,
        val torrentRoot: Path,
        val dubsRoot: Path,
        val outputPath: Path,
    ) {
        fun originalAnalyzeWavPath(): Path = torrentRoot.resolve("$episodeTag.wav")

        fun analyzeWorkDir(track: ProcessingDubTrackInput): Path = dubsRoot
            .resolve(track.teamSegment())
            .resolve("analyze")

        fun analyzePlanPath(track: ProcessingDubTrackInput): Path = analyzeWorkDir(track).resolve("$episodeTag.json")

        fun dubAnalyzeWavPath(track: ProcessingDubTrackInput): Path = analyzeWorkDir(track).resolve("$episodeTag.wav")

        fun analyzeRawChartPath(track: ProcessingDubTrackInput): Path =
            analyzeWorkDir(track).resolve("$episodeTag.raw.png")

        fun analyzeAlignedChartPath(track: ProcessingDubTrackInput): Path =
            analyzeWorkDir(track).resolve("$episodeTag.aligned.png")

        fun syncedDubPath(track: ProcessingDubTrackInput): Path = dubsRoot
            .resolve(track.teamSegment())
            .resolve("synced")
            .resolve("$episodeTag.m4a")

        private fun ProcessingDubTrackInput.teamSegment() = normalizePathSegment("$sourceId-$teamName")
    }

    companion object {
        fun fromBatch(batch: DownloadBatch, tempDir: Path, outputDir: Path): ProcessingPaths {
            require(batch.title.isNotBlank()) { "Processing batch title is empty." }
            require(batch.episodes.isNotEmpty()) { "Processing batch has no episodes." }

            val titleSegment = normalizePathSegment(batch.title)
            val titleRoot = tempDir.resolve(titleSegment)
            val processingRoot = titleRoot.resolve("processing")
            val outputRoot = outputDir.resolve(titleSegment)
            return ProcessingPaths(
                titleSegment = titleSegment,
                titleRoot = titleRoot,
                processingRoot = processingRoot,
                torrentRoot = processingRoot.resolve("torrent"),
                dubsRoot = processingRoot.resolve("dubs"),
                outputRoot = outputRoot,
            )
        }
    }
}

data class ProcessingRequest(
    val title: String,
    val episodes: List<ProcessingEpisodeInput>,
    val paths: ProcessingPaths,
)

fun DownloadBatch.toProcessingRequest(tempDir: Path, outputDir: Path): ProcessingRequest {
    require(title.isNotBlank()) { "Download batch title is empty." }
    require(episodes.isNotEmpty()) { "Download batch has no episodes." }

    val paths = ProcessingPaths.fromBatch(batch = this, tempDir = tempDir, outputDir = outputDir)
    return ProcessingRequest(
        title = title,
        episodes = episodes.map { episode ->
            ProcessingEpisodeInput(
                episodeNumber = episode.episodeNumber,
                rawVideoPath = episode.rawVideoPath,
                dubbedTracks = episode.dubbedTracks.map { track ->
                    ProcessingDubTrackInput(
                        sourceId = track.sourceId,
                        teamName = track.teamName,
                        languageTag = track.languageTag,
                        mediaPath = track.mediaPath,
                    )
                },
                subtitleTracks = episode.subtitleTracks,
                subtitleFontPaths = episode.subtitleFontPaths,
            )
        },
        paths = paths,
    )
}
