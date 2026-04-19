package com.badmanners.animurglar.ffmpeg

import com.badmanners.animurglar.pipeline.EpisodeMergeInput
import com.badmanners.animurglar.pipeline.SyncedDubAudio
import java.nio.file.Path

data class SyncAnalyzeRequest(
    val originalMedia: Path,
    val dubMedia: Path,
    val normalizedOriginalWavPath: Path,
    val normalizedDubWavPath: Path,
)

data class SyncDefaults(
    val analysisSampleRate: Int = 16_000,
    val envelopeStepMs: Int = 5,
    val envelopeSmoothingMs: Int = 160,
    val coarseDivider: Int = 4,
    val fineRefineRangeSec: Int = 1,
    val anchorScoreThreshold: Double = 0.58,
    val anchorStrongScoreThreshold: Double = 0.75,
    val anchorDriftToleranceSec: Int = 5,
    val maxShiftPercent: Double = 0.07,
    val pairRateDeviationThreshold: Double = 0.2,
)

data class SyncAnchor(
    val origCenterSec: Double,
    val dubCenterSec: Double,
    val score: Double,
)

data class SyncMatchedSegment(
    val origStartSec: Double,
    val origEndSec: Double,
    val dubStartSec: Double,
    val dubEndSec: Double,
    val rate: Double,
    val offsetSec: Double,
    val score: Double,
)

data class SyncGapSegment(
    val origStartSec: Double,
    val origEndSec: Double,
)

data class SyncDiagnostics(
    val anchorCandidates: Int,
    val anchorsAccepted: Int,
    val anchorsRejectedByScore: Int,
    val anchorsRejectedByOrder: Int,
    val anchorsRejectedByModel: Int,
    val anchorsRejectedByShift: Int,
    val anchorsRejectedByGlobalModel: Int,
    val globalAnchorsInliers: Int,
    val globalRate: Double,
    val globalOffsetSec: Double,
    val globalModelFallback: Boolean,
    val anchorScoreThreshold: Double,
    val anchorScoreMin: Double,
    val anchorScoreAvg: Double,
    val detectedEventsOriginal: Int,
    val detectedEventsDub: Int,
    val eventMatchesCandidates: Int,
    val eventMatchesMutual: Int,
    val modelResidualRmsSec: Double,
    val modelResidualMaxSec: Double,
    val modelMidpointResidualSec: Double,
    val anchorsCoverStart: Boolean,
    val anchorsCoverMiddle: Boolean,
    val anchorsCoverEnd: Boolean,
    val lowConfidence: Boolean,
)

class SyncEnvelopeSeries(
    val fineHz: Int,
    val coarseHz: Int,
    val originalFine: DoubleArray,
    val dubFine: DoubleArray,
    val originalCoarse: DoubleArray,
    val dubCoarse: DoubleArray,
)

data class SyncTrackPlan(
    val originalMediaPath: Path,
    val dubMediaPath: Path,
    val originalDurationSec: Double,
    val dubDurationSec: Double,
    val defaults: SyncDefaults,
    val anchors: List<SyncAnchor>,
    val segments: List<SyncMatchedSegment>,
    val gaps: List<SyncGapSegment>,
    val diagnostics: SyncDiagnostics,
)

data class SyncAnalyzeResult(
    val plan: SyncTrackPlan,
    val normalizedOriginalWavPath: Path,
    val normalizedDubWavPath: Path,
)

data class ApplySyncRequest(
    val episodeNumber: Int,
    val sourceId: String,
    val teamName: String,
    val languageTag: String,
    val inputDubPath: Path,
    val outputPath: Path,
    val plan: SyncTrackPlan,
)

data class ApplySyncResult(
    val syncedTrack: SyncedDubAudio,
    val command: List<String>,
)

data class MergeRequest(
    val input: EpisodeMergeInput,
)

data class MergeResult(
    val episodeNumber: Int,
    val outputPath: Path,
)
