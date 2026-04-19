package com.badmanners.animurglar.ffmpeg

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.apache.logging.log4j.LogManager
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.readBytes
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt


class SyncAnalyzeService(private val ffmpegService: FfmpegService) {

    private val logger = LogManager.getLogger(SyncAnalyzeService::class.java)
    private val originalNormalizationLocks = ConcurrentHashMap<Path, Mutex>()

    suspend fun analyze(
        request: SyncAnalyzeRequest,
        defaults: SyncDefaults = SyncDefaults(),
        log: (String) -> Unit = {},
    ): SyncAnalyzeResult {
        require(defaults.envelopeStepMs > 0) { "Envelope step must be positive." }
        require(defaults.coarseDivider > 0) { "Coarse divider must be positive." }
        require(defaults.anchorDriftToleranceSec >= 0) { "Anchor drift tolerance must be non-negative." }
        require(defaults.pairRateDeviationThreshold > 0.0) { "Pair rate deviation threshold must be positive." }
        require(defaults.pairRateDeviationThreshold < 1.0) {
            "Pair rate deviation threshold must be lower than 1.0."
        }
        require(defaults.maxShiftPercent > 0.0 && defaults.maxShiftPercent < 1.0) {
            "Max shift percent must be in range (0.0, 1.0)."
        }

        val originalWav = request.normalizedOriginalWavPath
        val dubWav = request.normalizedDubWavPath

        val originalWavLock = originalNormalizationLocks
            .computeIfAbsent(originalWav.toAbsolutePath().normalize()) { Mutex() }
        originalWavLock.withLock {
            if (!originalWav.exists() || originalWav.fileSize() == 0L) {
                ffmpegService.normalizeAudioForAnalysis(
                    inputMedia = request.originalMedia,
                    outputWav = originalWav,
                    sampleRate = defaults.analysisSampleRate,
                )
            }
        }
        ffmpegService.normalizeAudioForAnalysis(
            inputMedia = request.dubMedia,
            outputWav = dubWav,
            sampleRate = defaults.analysisSampleRate,
        )

        val originalSamples = readMonoPcm16Wav(originalWav, defaults.analysisSampleRate)
        val dubSamples = readMonoPcm16Wav(dubWav, defaults.analysisSampleRate)

        val envelopeSeries = buildEnvelopeSeriesFromSamples(
            originalSamples = originalSamples,
            dubSamples = dubSamples,
            defaults = defaults,
        )
        val fineHz = envelopeSeries.fineHz
        val originalFine = envelopeSeries.originalFine
        val dubFine = envelopeSeries.dubFine
        val originalCoarse = envelopeSeries.originalCoarse
        val dubCoarse = envelopeSeries.dubCoarse

        val originalDurationSec = originalSamples.size.toDouble() / defaults.analysisSampleRate
        val dubDurationSec = dubSamples.size.toDouble() / defaults.analysisSampleRate
        val maxShiftSec = originalDurationSec * defaults.maxShiftPercent

        val anchors = detectAnchors(
            originalFine = originalFine,
            dubFine = dubFine,
            fineHz = fineHz,
            originalDurationSec = originalDurationSec,
            dubDurationSec = dubDurationSec,
            maxShiftSec = maxShiftSec,
            defaults = defaults,
        )

        val segmentBuildResult = buildSegments(
            anchors = anchors.accepted,
            preferredModel = anchors.preferredModel,
            maxShiftSec = maxShiftSec,
            originalDurationSec = originalDurationSec,
            dubDurationSec = dubDurationSec,
            defaults = defaults,
        )
        val segments = segmentBuildResult.segments

        val gaps = buildGapSegments(
            segments = segments,
            originalDurationSec = originalDurationSec,
        )

        val diagnostics = buildDiagnostics(
            defaults = defaults,
            anchors = anchors,
            segments = segments,
            globalModel = segmentBuildResult.globalModel,
        )

        log("[sync] normalized original: $originalWav")
        log("[sync] normalized dub: $dubWav")
        log("[sync] fine points original=${originalFine.size}, dub=${dubFine.size}, coarse points original=${originalCoarse.size}, dub=${dubCoarse.size}")
        log(
            "[sync] anchors accepted=${anchors.accepted.size}/${anchors.candidates}, " +
                "rejectedByScore=${anchors.rejectedByScore}, rejectedByOrder=${anchors.rejectedByOrder}, " +
                "rejectedByModel=${anchors.rejectedByModel}, rejectedByShift=${anchors.rejectedByShift}, " +
                "events orig=${anchors.detectedEventsOriginal}, dub=${anchors.detectedEventsDub}, " +
                "matches candidates=${anchors.eventMatchesCandidates}, mutual=${anchors.eventMatchesMutual}, " +
                "coverage start=${anchors.coverageStart}, mid=${anchors.coverageMiddle}, end=${anchors.coverageEnd}"
        )
        log(
            "[sync] global rate=${formatDouble(diagnostics.globalRate)}, offset=${formatDouble(diagnostics.globalOffsetSec)}, " +
                "inliers=${diagnostics.globalAnchorsInliers}, rejectedByGlobalModel=${diagnostics.anchorsRejectedByGlobalModel}, " +
                "fallback=${diagnostics.globalModelFallback}"
        )
        log(
            "[sync] residual rms=${formatDouble(diagnostics.modelResidualRmsSec)}, " +
                "max=${formatDouble(diagnostics.modelResidualMaxSec)}, " +
                "mid=${formatDouble(diagnostics.modelMidpointResidualSec)}"
        )
        log("[sync] segments=${segments.size}, gaps=${gaps.size}, lowConfidence=${diagnostics.lowConfidence}")

        if (logger.isDebugEnabled) {
            logger.debug("Sync analyze finished for orig=${request.originalMedia} dub=${request.dubMedia}")
            logger.debug("Sync defaults: $defaults")
            logger.debug("Sync diagnostics: $diagnostics")
        }

        val plan = SyncTrackPlan(
            originalMediaPath = request.originalMedia,
            dubMediaPath = request.dubMedia,
            originalDurationSec = originalDurationSec,
            dubDurationSec = dubDurationSec,
            defaults = defaults,
            anchors = anchors.accepted,
            segments = segments,
            gaps = gaps,
            diagnostics = diagnostics,
        )

        return SyncAnalyzeResult(
            plan = plan,
            normalizedOriginalWavPath = originalWav,
            normalizedDubWavPath = dubWav,
        )
    }

    fun encodePlanToJson(plan: SyncTrackPlan) = buildJsonObject {
        put("originalMediaPath", plan.originalMediaPath.toAbsolutePath().toString())
        put("dubMediaPath", plan.dubMediaPath.toAbsolutePath().toString())
        put("originalDurationSec", jsonNumber(plan.originalDurationSec))
        put("dubDurationSec", jsonNumber(plan.dubDurationSec))
        putJsonObject("defaults") {
            put("analysisSampleRate", plan.defaults.analysisSampleRate)
            put("envelopeStepMs", plan.defaults.envelopeStepMs)
            put("envelopeSmoothingMs", plan.defaults.envelopeSmoothingMs)
            put("coarseDivider", plan.defaults.coarseDivider)
            put("fineRefineRangeSec", plan.defaults.fineRefineRangeSec)
            put("anchorScoreThreshold", jsonNumber(plan.defaults.anchorScoreThreshold))
            put("anchorStrongScoreThreshold", jsonNumber(plan.defaults.anchorStrongScoreThreshold))
            put("anchorDriftToleranceSec", plan.defaults.anchorDriftToleranceSec)
            put("maxShiftPercent", jsonNumber(plan.defaults.maxShiftPercent))
            put("pairRateDeviationThreshold", jsonNumber(plan.defaults.pairRateDeviationThreshold))
        }
        putJsonArray("anchors") {
            plan.anchors.forEach { anchor ->
                add(
                    buildJsonObject {
                        put("origCenterSec", jsonNumber(anchor.origCenterSec))
                        put("dubCenterSec", jsonNumber(anchor.dubCenterSec))
                        put("score", jsonNumber(anchor.score))
                    }
                )
            }
        }
        putJsonArray("segments") {
            plan.segments.forEach { segment ->
                addJsonObject {
                    put("origStartSec", jsonNumber(segment.origStartSec))
                    put("origEndSec", jsonNumber(segment.origEndSec))
                    put("dubStartSec", jsonNumber(segment.dubStartSec))
                    put("dubEndSec", jsonNumber(segment.dubEndSec))
                    put("rate", jsonNumber(segment.rate))
                    put("offsetSec", jsonNumber(segment.offsetSec))
                    put("score", jsonNumber(segment.score))
                }
            }
        }
        putJsonArray("gaps") {
            plan.gaps.forEach { gap ->
                addJsonObject {
                    put("origStartSec", jsonNumber(gap.origStartSec))
                    put("origEndSec", jsonNumber(gap.origEndSec))
                }
            }
        }
        putJsonObject("diagnostics") {
            put("anchorCandidates", plan.diagnostics.anchorCandidates)
            put("anchorsAccepted", plan.diagnostics.anchorsAccepted)
            put("anchorsRejectedByScore", plan.diagnostics.anchorsRejectedByScore)
            put("anchorsRejectedByOrder", plan.diagnostics.anchorsRejectedByOrder)
            put("anchorsRejectedByModel", plan.diagnostics.anchorsRejectedByModel)
            put("anchorsRejectedByShift", plan.diagnostics.anchorsRejectedByShift)
            put("anchorsRejectedByGlobalModel", plan.diagnostics.anchorsRejectedByGlobalModel)
            put("globalAnchorsInliers", plan.diagnostics.globalAnchorsInliers)
            put("globalRate", jsonNumber(plan.diagnostics.globalRate))
            put("globalOffsetSec", jsonNumber(plan.diagnostics.globalOffsetSec))
            put("globalModelFallback", plan.diagnostics.globalModelFallback)
            put("anchorScoreThreshold", jsonNumber(plan.diagnostics.anchorScoreThreshold))
            put("anchorScoreMin", jsonNumber(plan.diagnostics.anchorScoreMin))
            put("anchorScoreAvg", jsonNumber(plan.diagnostics.anchorScoreAvg))
            put("detectedEventsOriginal", plan.diagnostics.detectedEventsOriginal)
            put("detectedEventsDub", plan.diagnostics.detectedEventsDub)
            put("eventMatchesCandidates", plan.diagnostics.eventMatchesCandidates)
            put("eventMatchesMutual", plan.diagnostics.eventMatchesMutual)
            put("modelResidualRmsSec", jsonNumber(plan.diagnostics.modelResidualRmsSec))
            put("modelResidualMaxSec", jsonNumber(plan.diagnostics.modelResidualMaxSec))
            put("modelMidpointResidualSec", jsonNumber(plan.diagnostics.modelMidpointResidualSec))
            put("anchorsCoverStart", plan.diagnostics.anchorsCoverStart)
            put("anchorsCoverMiddle", plan.diagnostics.anchorsCoverMiddle)
            put("anchorsCoverEnd", plan.diagnostics.anchorsCoverEnd)
            put("lowConfidence", plan.diagnostics.lowConfidence)
        }
    }.toString()

    fun buildEnvelopeSeries(
        normalizedOriginalWavPath: Path,
        normalizedDubWavPath: Path,
        defaults: SyncDefaults,
    ): SyncEnvelopeSeries {
        val originalSamples = readMonoPcm16Wav(normalizedOriginalWavPath, defaults.analysisSampleRate)
        val dubSamples = readMonoPcm16Wav(normalizedDubWavPath, defaults.analysisSampleRate)
        val series = buildEnvelopeSeriesFromSamples(
            originalSamples = originalSamples,
            dubSamples = dubSamples,
            defaults = defaults,
        )

        return SyncEnvelopeSeries(
            fineHz = series.fineHz,
            coarseHz = series.coarseHz,
            originalFine = series.originalFine,
            dubFine = series.dubFine,
            originalCoarse = series.originalCoarse,
            dubCoarse = series.dubCoarse,
        )
    }

    private fun buildEnvelopeSeriesFromSamples(
        originalSamples: ShortArray,
        dubSamples: ShortArray,
        defaults: SyncDefaults,
    ): EnvelopeSeriesData {
        val fineHz = 1_000 / defaults.envelopeStepMs
        check(fineHz > 0) { "Computed fine envelope rate must be positive." }
        val coarseHz = fineHz / defaults.coarseDivider
        check(coarseHz > 0) { "Computed coarse envelope rate must be positive." }

        val originalFine = buildFineEnvelope(
            samples = originalSamples,
            sampleRate = defaults.analysisSampleRate,
            defaults = defaults,
        )
        val dubFine = buildFineEnvelope(
            samples = dubSamples,
            sampleRate = defaults.analysisSampleRate,
            defaults = defaults,
        )
        check(originalFine.isNotEmpty()) { "Original fine envelope is empty." }
        check(dubFine.isNotEmpty()) { "Dub fine envelope is empty." }

        return EnvelopeSeriesData(
            fineHz = fineHz,
            coarseHz = coarseHz,
            originalFine = originalFine,
            dubFine = dubFine,
            originalCoarse = toCoarseEnvelope(originalFine, defaults.coarseDivider),
            dubCoarse = toCoarseEnvelope(dubFine, defaults.coarseDivider),
        )
    }

    private fun buildFineEnvelope(
        samples: ShortArray,
        sampleRate: Int,
        defaults: SyncDefaults,
    ): DoubleArray {
        val windowSize = sampleRate * defaults.envelopeStepMs / 1_000
        check(windowSize > 0) { "Envelope window size must be positive." }

        val values = ArrayList<Double>(samples.size / windowSize + 1)
        var offset = 0
        while (offset < samples.size) {
            val end = min(offset + windowSize, samples.size)
            var sumAbs = 0.0
            for (index in offset until end) {
                sumAbs += abs(samples[index].toInt()) / MAX_PCM_SAMPLE
            }
            val meanAbs = if (end > offset) sumAbs / (end - offset) else 0.0
            values += ln(1.0 + meanAbs)
            offset = end
        }

        val p95 = percentile95(values)
        val normalized = DoubleArray(values.size) { index ->
            if (p95 <= EPSILON) {
                0.0
            } else {
                (values[index] / p95).coerceIn(0.0, 1.0)
            }
        }

        val smoothingPoints = max(1, defaults.envelopeSmoothingMs / defaults.envelopeStepMs)
        return smoothMovingAverage(normalized, smoothingPoints)
    }

    private fun toCoarseEnvelope(fine: DoubleArray, divider: Int): DoubleArray {
        if (fine.isEmpty()) {
            return DoubleArray(0)
        }

        val coarse = ArrayList<Double>(fine.size / divider + 1)
        var offset = 0
        while (offset < fine.size) {
            val end = min(offset + divider, fine.size)
            var sum = 0.0
            for (index in offset until end) {
                sum += fine[index]
            }
            coarse += sum / (end - offset)
            offset = end
        }
        return coarse.toDoubleArray()
    }

    private fun detectAnchors(
        originalFine: DoubleArray,
        dubFine: DoubleArray,
        fineHz: Int,
        originalDurationSec: Double,
        dubDurationSec: Double,
        maxShiftSec: Double,
        defaults: SyncDefaults,
    ): AnchorDetectionResult {
        if (originalFine.isEmpty() || dubFine.isEmpty()) {
            return AnchorDetectionResult()
        }

        val originalEvents = detectEvents(originalFine, fineHz)
        val dubEvents = detectEvents(dubFine, fineHz)
        if (originalEvents.isEmpty() || dubEvents.isEmpty()) {
            return AnchorDetectionResult(
                candidates = originalEvents.size,
                rejectedByScore = originalEvents.size,
                detectedEventsOriginal = originalEvents.size,
                detectedEventsDub = dubEvents.size,
            )
        }

        val matchedEvents = matchEvents(
            originalEvents = originalEvents,
            dubEvents = dubEvents,
            originalFine = originalFine,
            dubFine = dubFine,
            fineHz = fineHz,
            originalDurationSec = originalDurationSec,
            dubDurationSec = dubDurationSec,
            maxShiftSec = maxShiftSec,
            defaults = defaults,
        )

        if (matchedEvents.isEmpty()) {
            return AnchorDetectionResult(
                candidates = originalEvents.size,
                rejectedByScore = originalEvents.size,
                detectedEventsOriginal = originalEvents.size,
                detectedEventsDub = dubEvents.size,
            )
        }

        val modelMatches = pruneMatchesForModelEstimation(
            matches = matchedEvents,
            originalDurationSec = originalDurationSec,
        )

        val estimatedModel = estimateEventModel(
            matches = modelMatches,
            maxShiftSec = maxShiftSec,
            defaults = defaults,
            originalDurationSec = originalDurationSec,
            dubDurationSec = dubDurationSec,
        )

        if (estimatedModel == null || estimatedModel.inliers.isEmpty()) {
            return AnchorDetectionResult(
                candidates = originalEvents.size,
                rejectedByScore = (originalEvents.size - matchedEvents.size).coerceAtLeast(0),
                rejectedByModel = modelMatches.size,
                detectedEventsOriginal = originalEvents.size,
                detectedEventsDub = dubEvents.size,
                eventMatchesCandidates = matchedEvents.size,
            )
        }

        val distinctInliers = selectDistinctInliersByTime(
            inliers = estimatedModel.inliers,
            originalDurationSec = originalDurationSec,
            dubDurationSec = dubDurationSec,
        )
        val orderedInliers = distinctInliers.sortedBy { it.original.timeSec }
        val accepted = mutableListOf<SyncAnchor>()
        var rejectedByOrder = 0
        var lastDub = Double.NEGATIVE_INFINITY

        orderedInliers.forEach { match ->
            if (match.dub.timeSec <= lastDub + EPSILON) {
                rejectedByOrder += 1
                return@forEach
            }

            accepted += SyncAnchor(
                origCenterSec = match.original.timeSec,
                dubCenterSec = match.dub.timeSec,
                score = match.score,
            )
            lastDub = match.dub.timeSec
        }

        val coverage = evaluateAnchorCoverage(
            anchors = accepted,
            originalDurationSec = originalDurationSec,
        )
        val preferredModel = if (accepted.isEmpty()) {
            null
        } else {
            GlobalSyncModel(
                rate = estimatedModel.rate,
                offsetSec = estimatedModel.offsetSec,
                inliers = accepted,
                outliers = (modelMatches.size - accepted.size).coerceAtLeast(0),
                usedFallbackRate = estimatedModel.usedFallbackRate,
            )
        }

        return AnchorDetectionResult(
            accepted = accepted,
            candidates = originalEvents.size,
            rejectedByScore = (originalEvents.size - matchedEvents.size).coerceAtLeast(0),
            rejectedByOrder = rejectedByOrder,
            rejectedByModel = (modelMatches.size - distinctInliers.size).coerceAtLeast(0),
            rejectedByShift = estimatedModel.rejectedByShift,
            detectedEventsOriginal = originalEvents.size,
            detectedEventsDub = dubEvents.size,
            eventMatchesCandidates = matchedEvents.size,
            eventMatchesMutual = distinctInliers.size,
            modelResidualRmsSec = estimatedModel.residualRmsSec,
            modelResidualMaxSec = estimatedModel.residualMaxSec,
            modelMidpointResidualSec = estimatedModel.midpointResidualSec,
            coverageStart = coverage.start,
            coverageMiddle = coverage.middle,
            coverageEnd = coverage.end,
            preferredModel = preferredModel,
        )
    }

    private fun detectEvents(envelope: DoubleArray, hz: Int): List<SyncEvent> {
        if (envelope.size < 3) {
            return emptyList()
        }

        val derivative = DoubleArray(envelope.size) { index ->
            if (index == 0) 0.0 else envelope[index] - envelope[index - 1]
        }
        val attackThreshold = max(
            MIN_ATTACK_DELTA,
            percentile(derivative.filter { value -> value > 0.0 }, ATTACK_PERCENTILE),
        )

        val silenceThreshold = percentile(envelope.toList(), SILENCE_PERCENTILE)
            .coerceIn(MIN_SILENCE_LEVEL, MAX_SILENCE_LEVEL)
        val minEventDistancePoints = max(1, (MIN_EVENT_DISTANCE_SEC * hz).roundToInt())
        val minStableSegmentPoints = max(1, (MIN_STABLE_SILENCE_SEC * hz).roundToInt())

        val events = mutableListOf<SyncEvent>()

        for (index in 1 until envelope.lastIndex) {
            val delta = derivative[index]
            if (
                delta >= attackThreshold &&
                delta >= derivative[index - 1] &&
                delta >= derivative[index + 1]
            ) {
                events += SyncEvent(
                    type = EventType.ATTACK,
                    index = index,
                    timeSec = index.toDouble() / hz,
                    saliency = delta,
                )
            }
        }

        val isSilent = BooleanArray(envelope.size) { index ->
            envelope[index] <= silenceThreshold
        }
        for (index in 1 until isSilent.size) {
            val enteredSilence = !isSilent[index - 1] && isSilent[index]
            if (enteredSilence && hasStableState(isSilent, index, minStableSegmentPoints, expectedSilent = true)) {
                events += SyncEvent(
                    type = EventType.SILENCE_ENTER,
                    index = index,
                    timeSec = index.toDouble() / hz,
                    saliency = abs(derivative[index]).coerceAtLeast(SILENCE_EDGE_SALIENCY),
                )
            }

            val exitedSilence = isSilent[index - 1] && !isSilent[index]
            if (exitedSilence && hasStableState(isSilent, index, minStableSegmentPoints, expectedSilent = false)) {
                events += SyncEvent(
                    type = EventType.SILENCE_EXIT,
                    index = index,
                    timeSec = index.toDouble() / hz,
                    saliency = abs(derivative[index]).coerceAtLeast(SILENCE_EDGE_SALIENCY),
                )
            }
        }

        val firstActive = envelope.indexOfFirst { value -> value > silenceThreshold }
        if (firstActive >= 0) {
            events += SyncEvent(
                type = EventType.ACTIVITY_START,
                index = firstActive,
                timeSec = firstActive.toDouble() / hz,
                saliency = ACTIVITY_EDGE_SALIENCY,
            )
        }

        val lastActive = envelope.indexOfLast { value -> value > silenceThreshold }
        if (lastActive >= 0) {
            events += SyncEvent(
                type = EventType.ACTIVITY_END,
                index = lastActive,
                timeSec = lastActive.toDouble() / hz,
                saliency = ACTIVITY_EDGE_SALIENCY,
            )
        }

        return selectDistinctEvents(events, minEventDistancePoints)
    }

    private fun hasStableState(
        isSilent: BooleanArray,
        startIndex: Int,
        minLength: Int,
        expectedSilent: Boolean,
    ): Boolean {
        if (startIndex < 0 || startIndex >= isSilent.size || minLength <= 0) {
            return false
        }

        val end = min(isSilent.size, startIndex + minLength)
        if (end - startIndex < minLength) {
            return false
        }

        for (index in startIndex until end) {
            if (isSilent[index] != expectedSilent) {
                return false
            }
        }
        return true
    }

    private fun selectDistinctEvents(
        events: List<SyncEvent>,
        minDistancePoints: Int,
    ): List<SyncEvent> {
        if (events.isEmpty()) {
            return emptyList()
        }

        val selected = mutableListOf<SyncEvent>()
        events
            .sortedByDescending { event -> event.saliency }
            .forEach { event ->
                val tooClose = selected.any { selectedEvent ->
                    abs(selectedEvent.index - event.index) < minDistancePoints
                }
                if (!tooClose) {
                    selected += event
                }
            }

        return selected.sortedBy { event -> event.index }
    }

    private fun matchEvents(
        originalEvents: List<SyncEvent>,
        dubEvents: List<SyncEvent>,
        originalFine: DoubleArray,
        dubFine: DoubleArray,
        fineHz: Int,
        originalDurationSec: Double,
        dubDurationSec: Double,
        maxShiftSec: Double,
        defaults: SyncDefaults,
    ): List<EventMatch> {
        if (originalEvents.isEmpty() || dubEvents.isEmpty()) {
            return emptyList()
        }

        val minAllowedRate = 1.0 - defaults.pairRateDeviationThreshold
        val maxAllowedRate = 1.0 + defaults.pairRateDeviationThreshold

        val provisionalMatches = mutableListOf<EventMatch>()
        originalEvents.forEachIndexed { originalIndex, originalEvent ->
            val dubStart = minAllowedRate * originalEvent.timeSec - maxShiftSec
            val dubEnd = maxAllowedRate * originalEvent.timeSec + maxShiftSec
            val scoredCandidates = mutableListOf<EventMatch>()

            dubEvents.forEachIndexed { dubIndex, dubEvent ->
                if (dubEvent.timeSec !in dubStart..dubEnd) {
                    return@forEachIndexed
                }

                val score = scoreEventMatch(
                    originalEvent = originalEvent,
                    dubEvent = dubEvent,
                    originalFine = originalFine,
                    dubFine = dubFine,
                    fineHz = fineHz,
                    originalDurationSec = originalDurationSec,
                    dubDurationSec = dubDurationSec,
                )

                if (!score.isFinite()) {
                    return@forEachIndexed
                }

                scoredCandidates += EventMatch(
                    original = originalEvent,
                    dub = dubEvent,
                    score = score,
                    originalEventIndex = originalIndex,
                    dubEventIndex = dubIndex,
                )
            }

            if (scoredCandidates.isEmpty()) {
                return@forEachIndexed
            }

            val sortedCandidates = scoredCandidates.sortedByDescending { candidate -> candidate.score }
            val strongest = sortedCandidates.first()
            if (strongest.score < defaults.anchorScoreThreshold) {
                return@forEachIndexed
            }

            val ambiguityMargin = if (strongest.score >= defaults.anchorStrongScoreThreshold) {
                EVENT_STRONG_AMBIGUITY_MARGIN
            } else {
                EVENT_AMBIGUITY_MARGIN
            }

            sortedCandidates.forEachIndexed { index, candidate ->
                if (index >= MAX_MATCHES_PER_ORIGINAL_EVENT) {
                    return@forEachIndexed
                }
                if (candidate.score + EPSILON < defaults.anchorScoreThreshold) {
                    return@forEachIndexed
                }
                if (index > 0 && strongest.score - candidate.score > ambiguityMargin) {
                    return@forEachIndexed
                }

                provisionalMatches += refineEventMatch(
                    match = candidate,
                    originalFine = originalFine,
                    dubFine = dubFine,
                    fineHz = fineHz,
                    defaults = defaults,
                )
            }
        }

        if (provisionalMatches.isEmpty()) {
            return emptyList()
        }

        return provisionalMatches
            .groupBy { match -> match.dubEventIndex }
            .values
            .asSequence()
            .flatMap { matchesByDub ->
                matchesByDub
                    .sortedByDescending { it.score }
                    .take(MAX_MATCHES_PER_DUB_EVENT)
                    .asSequence()
            }
            .distinctBy { match -> match.originalEventIndex to match.dubEventIndex }
            .sortedBy { match -> match.original.timeSec }
            .toList()
    }

    private fun pruneMatchesForModelEstimation(
        matches: List<EventMatch>,
        originalDurationSec: Double,
    ): List<EventMatch> {
        if (matches.size <= MAX_EVENT_MODEL_MATCHES) {
            return matches
        }

        val bucketLimit = max(1, MAX_EVENT_MODEL_MATCHES / MODEL_MATCH_BUCKETS)
        val perBucketCounts = IntArray(MODEL_MATCH_BUCKETS)
        val selected = LinkedHashSet<EventMatch>(MAX_EVENT_MODEL_MATCHES)

        matches
            .sortedByDescending { match -> match.score }
            .forEach { match ->
                val normalized = if (originalDurationSec <= EPSILON) {
                    0.0
                } else {
                    (match.original.timeSec / originalDurationSec).coerceIn(0.0, 1.0)
                }
                val bucket = min(MODEL_MATCH_BUCKETS - 1, (normalized * MODEL_MATCH_BUCKETS).toInt())
                if (perBucketCounts[bucket] >= bucketLimit) {
                    return@forEach
                }

                perBucketCounts[bucket] += 1
                selected += match
            }

        if (selected.size < min(MAX_EVENT_MODEL_MATCHES, matches.size)) {
            matches
                .sortedByDescending { match -> match.score }
                .forEach { match ->
                    if (selected.size >= MAX_EVENT_MODEL_MATCHES) {
                        return@forEach
                    }
                    selected += match
                }
        }

        return selected
            .sortedBy { match -> match.original.timeSec }
            .take(MAX_EVENT_MODEL_MATCHES)
    }

    private fun selectDistinctInliersByTime(
        inliers: List<EventMatch>,
        originalDurationSec: Double,
        dubDurationSec: Double,
    ): List<EventMatch> {
        if (inliers.size <= 1) {
            return inliers
        }

        val originalMinDistanceSec =
            max(MIN_ANCHOR_MATCH_DISTANCE_SEC, originalDurationSec * ANCHOR_MATCH_DISTANCE_SHARE)
        val dubMinDistanceSec = max(MIN_ANCHOR_MATCH_DISTANCE_SEC, dubDurationSec * ANCHOR_MATCH_DISTANCE_SHARE)
        val selected = mutableListOf<EventMatch>()

        inliers.sortedByDescending { it.score }.forEach { candidate ->
            val tooClose = selected.any { accepted ->
                abs(accepted.original.timeSec - candidate.original.timeSec) < originalMinDistanceSec &&
                    abs(accepted.dub.timeSec - candidate.dub.timeSec) < dubMinDistanceSec
            }
            if (!tooClose) {
                selected += candidate
            }
        }

        return selected.sortedBy { it.original.timeSec }
    }

    private fun evaluateAnchorCoverage(
        anchors: List<SyncAnchor>,
        originalDurationSec: Double,
    ): AnchorCoverage {
        if (anchors.isEmpty() || originalDurationSec <= EPSILON) {
            return AnchorCoverage(start = false, middle = false, end = false)
        }

        val start = anchors.any { it.origCenterSec <= originalDurationSec * COVERAGE_START_MAX_SHARE }
        val middle = anchors.any { anchor ->
            val normalizedTime = anchor.origCenterSec / originalDurationSec
            normalizedTime in COVERAGE_MIDDLE_MIN_SHARE..COVERAGE_MIDDLE_MAX_SHARE
        }
        val end = anchors.any { it.origCenterSec >= originalDurationSec * COVERAGE_END_MIN_SHARE }
        return AnchorCoverage(start = start, middle = middle, end = end)
    }

    private fun scoreEventMatch(
        originalEvent: SyncEvent,
        dubEvent: SyncEvent,
        originalFine: DoubleArray,
        dubFine: DoubleArray,
        fineHz: Int,
        originalDurationSec: Double,
        dubDurationSec: Double,
    ): Double {
        val descriptorNcc = descriptorNcc(
            original = originalFine,
            originalCenter = originalEvent.index,
            dub = dubFine,
            dubCenter = dubEvent.index,
            hz = fineHz,
        ) ?: return Double.NEGATIVE_INFINITY

        val saliencyRatio = min(originalEvent.saliency, dubEvent.saliency) /
            max(max(originalEvent.saliency, dubEvent.saliency), EPSILON)
        val descriptorScore = ((descriptorNcc + 1.0) / 2.0).coerceIn(0.0, 1.0)
        val typeScore = if (originalEvent.type == dubEvent.type) MATCH_TYPE_BONUS else 0.0
        val normalizedOriginalTime = originalEvent.timeSec / max(originalDurationSec, EPSILON)
        val normalizedDubTime = dubEvent.timeSec / max(dubDurationSec, EPSILON)
        val normalizedTimeDelta = abs(normalizedOriginalTime - normalizedDubTime)
        val positionPenalty = normalizedTimeDelta * MATCH_POSITION_PENALTY
        return (
            descriptorScore * MATCH_DESCRIPTOR_WEIGHT +
                saliencyRatio * MATCH_SALIENCY_WEIGHT +
                typeScore -
                positionPenalty
            )
            .coerceIn(0.0, 1.0)
    }

    private fun descriptorNcc(
        original: DoubleArray,
        originalCenter: Int,
        dub: DoubleArray,
        dubCenter: Int,
        hz: Int,
    ): Double? {
        if (
            originalCenter !in original.indices ||
            dubCenter !in dub.indices
        ) {
            return null
        }

        val halfWindow = max(MIN_DESCRIPTOR_HALF_WINDOW_POINTS, (EVENT_DESCRIPTOR_SEC * hz / 2.0).roundToInt())
        val left = min(halfWindow, min(originalCenter, dubCenter))
        val right = min(
            halfWindow,
            min(
                original.lastIndex - originalCenter,
                dub.lastIndex - dubCenter,
            ),
        )
        val size = left + right + 1
        if (size < MIN_DESCRIPTOR_POINTS) {
            return null
        }

        return nccAligned(
            left = original,
            leftStart = originalCenter - left,
            right = dub,
            rightStart = dubCenter - left,
            size = size,
        )
    }

    private fun nccAligned(
        left: DoubleArray,
        leftStart: Int,
        right: DoubleArray,
        rightStart: Int,
        size: Int,
    ): Double? {
        if (
            size <= 0 ||
            leftStart < 0 ||
            rightStart < 0 ||
            leftStart + size > left.size ||
            rightStart + size > right.size
        ) {
            return null
        }

        var leftSum = 0.0
        var rightSum = 0.0
        for (index in 0 until size) {
            leftSum += left[leftStart + index]
            rightSum += right[rightStart + index]
        }
        val leftMean = leftSum / size
        val rightMean = rightSum / size

        var numerator = 0.0
        var leftVariance = 0.0
        var rightVariance = 0.0

        for (index in 0 until size) {
            val leftCentered = left[leftStart + index] - leftMean
            val rightCentered = right[rightStart + index] - rightMean
            numerator += leftCentered * rightCentered
            leftVariance += leftCentered * leftCentered
            rightVariance += rightCentered * rightCentered
        }

        if (leftVariance <= EPSILON || rightVariance <= EPSILON) {
            return null
        }
        return numerator / sqrt(leftVariance * rightVariance)
    }

    private fun refineEventMatch(
        match: EventMatch,
        originalFine: DoubleArray,
        dubFine: DoubleArray,
        fineHz: Int,
        defaults: SyncDefaults,
    ): EventMatch {
        val halfWindow = max(MIN_DESCRIPTOR_HALF_WINDOW_POINTS, (EVENT_DESCRIPTOR_SEC * fineHz / 2.0).roundToInt())
        val queryStart = max(0, match.original.index - halfWindow)
        val queryEndExclusive = min(originalFine.size, match.original.index + halfWindow + 1)
        if (queryEndExclusive - queryStart < MIN_DESCRIPTOR_POINTS) {
            return match
        }

        val query = originalFine.copyOfRange(queryStart, queryEndExclusive)
        val queryHalf = query.size / 2
        val coarseStart = match.dub.index - queryHalf
        val refineRange = max(1, defaults.fineRefineRangeSec * fineHz)

        val best = findBestNcc(
            query = query,
            target = dubFine,
            candidateStartInclusive = coarseStart - refineRange,
            candidateStartExclusive = coarseStart + refineRange + 1,
        ) ?: return match

        val refinedCenter = (best.start + queryHalf).coerceIn(0, dubFine.lastIndex)
        return match.copy(
            dub = match.dub.copy(
                index = refinedCenter,
                timeSec = refinedCenter.toDouble() / fineHz,
            ),
            score = max(match.score, best.score),
        )
    }

    private fun estimateEventModel(
        matches: List<EventMatch>,
        maxShiftSec: Double,
        defaults: SyncDefaults,
        originalDurationSec: Double,
        dubDurationSec: Double,
    ): EventModelEstimation? {
        if (matches.isEmpty()) {
            return null
        }

        val minAllowedRate = 1.0 - defaults.pairRateDeviationThreshold
        val maxAllowedRate = 1.0 + defaults.pairRateDeviationThreshold
        val residualThresholdSec = max(
            MIN_EVENT_RESIDUAL_SEC,
            min(EVENT_MAX_RESIDUAL_SEC, defaults.anchorDriftToleranceSec * EVENT_RESIDUAL_SHARE),
        )

        var rejectedByShift = 0
        var bestModel: EventLinearModel? = null

        for (leftIndex in 0 until matches.lastIndex) {
            for (rightIndex in (leftIndex + 1) until matches.size) {
                val left = matches[leftIndex]
                val right = matches[rightIndex]

                val origDelta = right.original.timeSec - left.original.timeSec
                if (origDelta <= MIN_MODEL_PAIR_DISTANCE_SEC) {
                    continue
                }

                val candidateRate = (right.dub.timeSec - left.dub.timeSec) / origDelta
                if (
                    !candidateRate.isFinite() ||
                    candidateRate < minAllowedRate ||
                    candidateRate > maxAllowedRate
                ) {
                    continue
                }

                val candidateOffset = left.dub.timeSec - candidateRate * left.original.timeSec
                if (!isShiftWithinBounds(
                        candidateRate,
                        candidateOffset,
                        originalDurationSec,
                        dubDurationSec,
                        maxShiftSec
                    )
                ) {
                    rejectedByShift += 1
                    continue
                }

                val inliers = collectInlierMatches(matches, candidateRate, candidateOffset, residualThresholdSec)
                if (inliers.size < 2) {
                    continue
                }

                val inlierOrigStart = inliers.minOf { inlier -> inlier.original.timeSec }
                val inlierOrigEnd = inliers.maxOf { inlier -> inlier.original.timeSec }
                val inlierCoverage = (inlierOrigEnd - inlierOrigStart) / max(originalDurationSec, EPSILON)
                val meanResidual = inliers.sumOf { inlier ->
                    abs(inlier.dub.timeSec - (candidateRate * inlier.original.timeSec + candidateOffset))
                } / inliers.size
                val modelScore =
                    inliers.size * MODEL_INLIER_WEIGHT +
                        inliers.sumOf { inlier -> inlier.score } +
                        inlierCoverage * MODEL_COVERAGE_WEIGHT -
                        meanResidual * MODEL_RESIDUAL_WEIGHT
                val model = EventLinearModel(
                    rate = candidateRate,
                    offsetSec = candidateOffset,
                    inliers = inliers,
                    score = modelScore,
                )
                val currentBest = bestModel
                if (
                    currentBest == null ||
                    model.score > currentBest.score + EPSILON ||
                    (
                        abs(model.score - currentBest.score) <= EPSILON &&
                            model.inliers.size > currentBest.inliers.size
                        )
                ) {
                    bestModel = model
                }
            }
        }

        var usedFallbackRate = false
        var rate: Double
        var offsetSec: Double
        var inliers: List<EventMatch>

        if (bestModel == null) {
            usedFallbackRate = true
            val strongest = matches.maxByOrNull { match -> match.score } ?: return null
            rate = 1.0.coerceIn(minAllowedRate, maxAllowedRate)
            offsetSec = (strongest.dub.timeSec - rate * strongest.original.timeSec).coerceIn(-maxShiftSec, maxShiftSec)
            if (!isShiftWithinBounds(rate, offsetSec, originalDurationSec, dubDurationSec, maxShiftSec)) {
                return null
            }
            inliers = collectInlierMatches(matches, rate, offsetSec, residualThresholdSec)
            if (inliers.isEmpty()) {
                inliers = listOf(strongest)
            }
        } else {
            rate = bestModel.rate
            offsetSec = bestModel.offsetSec
            inliers = bestModel.inliers
        }

        repeat(2) {
            val refined = estimateWeightedModel(
                inliers = inliers,
                minAllowedRate = minAllowedRate,
                maxAllowedRate = maxAllowedRate,
                maxShiftSec = maxShiftSec,
                originalDurationSec = originalDurationSec,
                dubDurationSec = dubDurationSec,
            )

            if (refined == null) {
                usedFallbackRate = true
                return@repeat
            }

            rate = refined.rate
            offsetSec = refined.offsetSec
            val filtered = collectInlierMatches(matches, rate, offsetSec, residualThresholdSec)
            if (filtered.size >= 2) {
                inliers = filtered
            }
        }

        if (inliers.isEmpty()) {
            return null
        }

        val residualStats = buildResidualStats(inliers, rate, offsetSec, originalDurationSec)
        return EventModelEstimation(
            rate = rate,
            offsetSec = offsetSec,
            inliers = inliers,
            rejectedByShift = rejectedByShift,
            usedFallbackRate = usedFallbackRate,
            residualRmsSec = residualStats.rmsSec,
            residualMaxSec = residualStats.maxSec,
            midpointResidualSec = residualStats.midpointSec,
        )
    }

    private fun estimateWeightedModel(
        inliers: List<EventMatch>,
        minAllowedRate: Double,
        maxAllowedRate: Double,
        maxShiftSec: Double,
        originalDurationSec: Double,
        dubDurationSec: Double,
    ): WeightedModel? {
        if (inliers.size < 2) {
            return null
        }

        val weights = DoubleArray(inliers.size)
        var weightSum = 0.0
        for (index in inliers.indices) {
            val weight = max(inliers[index].score, MIN_MATCH_WEIGHT)
            weights[index] = weight
            weightSum += weight
        }
        if (weightSum <= EPSILON) {
            return null
        }

        var weightedOrigMean = 0.0
        var weightedDubMean = 0.0
        for (index in inliers.indices) {
            val match = inliers[index]
            val weight = weights[index]
            weightedOrigMean += match.original.timeSec * weight
            weightedDubMean += match.dub.timeSec * weight
        }
        weightedOrigMean /= weightSum
        weightedDubMean /= weightSum

        var numerator = 0.0
        var denominator = 0.0
        for (index in inliers.indices) {
            val match = inliers[index]
            val weight = weights[index]
            val centeredOrig = match.original.timeSec - weightedOrigMean
            numerator += weight * centeredOrig * (match.dub.timeSec - weightedDubMean)
            denominator += weight * centeredOrig * centeredOrig
        }

        if (denominator <= EPSILON) {
            return null
        }

        val rate = (numerator / denominator).coerceIn(minAllowedRate, maxAllowedRate)
        var weightedOffset = 0.0
        for (index in inliers.indices) {
            val match = inliers[index]
            weightedOffset += (match.dub.timeSec - rate * match.original.timeSec) * weights[index]
        }
        val offsetSec = weightedOffset / weightSum

        val boundedOffset = offsetSec.coerceIn(-maxShiftSec, maxShiftSec)
        if (!isShiftWithinBounds(rate, boundedOffset, originalDurationSec, dubDurationSec, maxShiftSec)) {
            return null
        }

        return WeightedModel(rate = rate, offsetSec = boundedOffset)
    }

    private fun collectInlierMatches(
        matches: List<EventMatch>,
        rate: Double,
        offsetSec: Double,
        residualThresholdSec: Double,
    ): List<EventMatch> =
        matches.filter { match ->
            abs(match.dub.timeSec - (rate * match.original.timeSec + offsetSec)) <= residualThresholdSec
        }

    private fun isShiftWithinBounds(
        rate: Double,
        offsetSec: Double,
        originalDurationSec: Double,
        dubDurationSec: Double,
        maxShiftSec: Double,
    ): Boolean {
        val startShiftSec = abs(offsetSec)
        val endShiftSec = abs(dubDurationSec - (rate * originalDurationSec + offsetSec))
        return startShiftSec <= maxShiftSec + EPSILON && endShiftSec <= maxShiftSec + EPSILON
    }

    private fun buildResidualStats(
        inliers: List<EventMatch>,
        rate: Double,
        offsetSec: Double,
        originalDurationSec: Double,
    ): ResidualStats {
        if (inliers.isEmpty()) {
            return ResidualStats(rmsSec = 0.0, maxSec = 0.0, midpointSec = 0.0)
        }

        var sumSquares = 0.0
        var maxResidual = 0.0
        val midpointTarget = originalDurationSec / 2.0

        val midpointResidual = inliers.minByOrNull { match ->
            abs(match.original.timeSec - midpointTarget)
        }?.let { nearest ->
            abs(nearest.dub.timeSec - (rate * nearest.original.timeSec + offsetSec))
        } ?: 0.0

        inliers.forEach { match ->
            val residual = abs(match.dub.timeSec - (rate * match.original.timeSec + offsetSec))
            sumSquares += residual * residual
            maxResidual = max(maxResidual, residual)
        }

        return ResidualStats(
            rmsSec = sqrt(sumSquares / inliers.size),
            maxSec = maxResidual,
            midpointSec = midpointResidual,
        )
    }

    private fun buildSegments(
        anchors: List<SyncAnchor>,
        preferredModel: GlobalSyncModel?,
        maxShiftSec: Double,
        originalDurationSec: Double,
        dubDurationSec: Double,
        defaults: SyncDefaults,
    ): SegmentBuildResult {
        if (anchors.isEmpty()) {
            return SegmentBuildResult()
        }

        val globalModel = preferredModel ?: estimateGlobalSyncModel(
            anchors = anchors,
            defaults = defaults,
            maxShiftSec = maxShiftSec,
        ) ?: return SegmentBuildResult()

        val rate = globalModel.rate
        val offset = globalModel.offsetSec

        val origStart = max(0.0, (-offset) / rate).coerceIn(0.0, originalDurationSec)
        val origEnd = min(originalDurationSec, (dubDurationSec - offset) / rate).coerceIn(0.0, originalDurationSec)
        val origLen = origEnd - origStart
        if (origLen <= MIN_SEGMENT_SEC) {
            return SegmentBuildResult(globalModel = globalModel)
        }

        val dubStart = rate * origStart + offset
        val dubEnd = rate * origEnd + offset
        val dubLen = dubEnd - dubStart
        if (dubLen <= MIN_SEGMENT_SEC) {
            return SegmentBuildResult(globalModel = globalModel)
        }

        if (
            abs(dubStart - origStart) > maxShiftSec ||
            abs(dubEnd - origEnd) > maxShiftSec
        ) {
            return SegmentBuildResult(globalModel = globalModel)
        }

        val score = globalModel.inliers.minOfOrNull { it.score } ?: 0.0
        return SegmentBuildResult(
            segments = listOf(
                SyncMatchedSegment(
                    origStartSec = origStart,
                    origEndSec = origEnd,
                    dubStartSec = dubStart,
                    dubEndSec = dubEnd,
                    rate = rate,
                    offsetSec = offset,
                    score = score,
                )
            ),
            globalModel = globalModel,
        )
    }

    private fun estimateGlobalSyncModel(
        anchors: List<SyncAnchor>,
        defaults: SyncDefaults,
        maxShiftSec: Double,
    ): GlobalSyncModel? {
        if (anchors.isEmpty()) {
            return null
        }

        val minAllowedRate = 1.0 - defaults.pairRateDeviationThreshold
        val maxAllowedRate = 1.0 + defaults.pairRateDeviationThreshold

        val pairRates = anchors.zipWithNext().mapNotNull { (left, right) ->
            val origLen = right.origCenterSec - left.origCenterSec
            if (origLen <= MIN_SEGMENT_SEC) {
                return@mapNotNull null
            }

            val rate = (right.dubCenterSec - left.dubCenterSec) / origLen
            if (rate.isFinite() && rate in minAllowedRate..maxAllowedRate) {
                rate
            } else {
                null
            }
        }

        var usedFallbackRate = false
        var rate = if (pairRates.isEmpty()) {
            usedFallbackRate = true
            1.0
        } else {
            median(pairRates)
        }.coerceIn(minAllowedRate, maxAllowedRate)

        val residualThresholdSec = max(
            MIN_MODEL_RESIDUAL_SEC,
            min(defaults.anchorDriftToleranceSec.toDouble(), maxShiftSec * MODEL_RESIDUAL_SHARE),
        )

        var inliers = anchors
        repeat(2) {
            val offset = medianOffsetForRate(inliers, rate)
            val filtered = anchors.filter { anchor ->
                abs(anchor.dubCenterSec - (rate * anchor.origCenterSec + offset)) <= residualThresholdSec
            }
            if (filtered.size >= 2) {
                inliers = filtered
            }
        }

        if (inliers.size >= 2) {
            val leastSquaresRate = estimateLeastSquaresRate(inliers)
            if (leastSquaresRate.isFinite()) {
                val clampedRate = leastSquaresRate.coerceIn(minAllowedRate, maxAllowedRate)
                usedFallbackRate = usedFallbackRate || abs(clampedRate - leastSquaresRate) > EPSILON
                rate = clampedRate
            } else {
                usedFallbackRate = true
            }
        } else {
            usedFallbackRate = true
            rate = 1.0.coerceIn(minAllowedRate, maxAllowedRate)
        }

        var offsetSec = medianOffsetForRate(inliers, rate).coerceIn(-maxShiftSec, maxShiftSec)
        val finalInliers = anchors.filter { anchor ->
            abs(anchor.dubCenterSec - (rate * anchor.origCenterSec + offsetSec)) <= residualThresholdSec
        }
        if (finalInliers.isNotEmpty()) {
            inliers = finalInliers
            offsetSec = medianOffsetForRate(inliers, rate).coerceIn(-maxShiftSec, maxShiftSec)
        }

        if (inliers.isEmpty()) {
            return null
        }

        if (inliers.size == 1) {
            usedFallbackRate = true
            rate = 1.0.coerceIn(minAllowedRate, maxAllowedRate)
            offsetSec =
                (inliers.first().dubCenterSec - inliers.first().origCenterSec).coerceIn(-maxShiftSec, maxShiftSec)
        }

        return GlobalSyncModel(
            rate = rate,
            offsetSec = offsetSec,
            inliers = inliers,
            outliers = (anchors.size - inliers.size).coerceAtLeast(0),
            usedFallbackRate = usedFallbackRate,
        )
    }

    private fun medianOffsetForRate(anchors: List<SyncAnchor>, rate: Double): Double {
        if (anchors.isEmpty()) {
            return 0.0
        }
        return median(anchors.map { anchor -> anchor.dubCenterSec - rate * anchor.origCenterSec })
    }

    private fun estimateLeastSquaresRate(anchors: List<SyncAnchor>): Double {
        if (anchors.size < 2) {
            return 1.0
        }

        val meanOrig = anchors.sumOf { anchor -> anchor.origCenterSec } / anchors.size
        val meanDub = anchors.sumOf { anchor -> anchor.dubCenterSec } / anchors.size
        var numerator = 0.0
        var denominator = 0.0

        anchors.forEach { anchor ->
            val centeredOrig = anchor.origCenterSec - meanOrig
            numerator += centeredOrig * (anchor.dubCenterSec - meanDub)
            denominator += centeredOrig * centeredOrig
        }

        return if (denominator <= EPSILON) {
            1.0
        } else {
            numerator / denominator
        }
    }

    private fun buildGapSegments(
        segments: List<SyncMatchedSegment>,
        originalDurationSec: Double,
    ): List<SyncGapSegment> {
        if (segments.isEmpty()) {
            return listOf(SyncGapSegment(origStartSec = 0.0, origEndSec = originalDurationSec.coerceAtLeast(0.0)))
        }

        val gaps = mutableListOf<SyncGapSegment>()
        var cursor = 0.0

        segments.sortedBy { it.origStartSec }.forEach { segment ->
            if (segment.origStartSec - cursor > MIN_SEGMENT_SEC) {
                gaps += SyncGapSegment(
                    origStartSec = cursor,
                    origEndSec = segment.origStartSec,
                )
            }
            cursor = max(cursor, segment.origEndSec)
        }

        if (originalDurationSec - cursor > MIN_SEGMENT_SEC) {
            gaps += SyncGapSegment(
                origStartSec = cursor,
                origEndSec = originalDurationSec,
            )
        }

        return gaps
    }

    private fun buildDiagnostics(
        defaults: SyncDefaults,
        anchors: AnchorDetectionResult,
        segments: List<SyncMatchedSegment>,
        globalModel: GlobalSyncModel?,
    ): SyncDiagnostics {
        val scores = anchors.accepted.map { it.score }
        val minScore = scores.minOrNull() ?: 0.0
        val avgScore = if (scores.isEmpty()) 0.0 else scores.sum() / scores.size
        val globalInliers = globalModel?.inliers?.size ?: 0
        val hasCoverage = anchors.coverageStart && anchors.coverageMiddle && anchors.coverageEnd
        val lowConfidence =
            anchors.accepted.size < MIN_CONFIDENT_ANCHORS ||
                segments.isEmpty() ||
                globalInliers < 2 ||
                avgScore < defaults.anchorScoreThreshold + 0.05 ||
                !hasCoverage ||
                anchors.modelResidualRmsSec > RMS_RESIDUAL_WARN_SEC ||
                anchors.modelResidualMaxSec > MAX_RESIDUAL_WARN_SEC ||
                anchors.modelMidpointResidualSec > MIDPOINT_RESIDUAL_WARN_SEC

        return SyncDiagnostics(
            anchorCandidates = anchors.candidates,
            anchorsAccepted = anchors.accepted.size,
            anchorsRejectedByScore = anchors.rejectedByScore,
            anchorsRejectedByOrder = anchors.rejectedByOrder,
            anchorsRejectedByModel = anchors.rejectedByModel,
            anchorsRejectedByShift = anchors.rejectedByShift,
            anchorsRejectedByGlobalModel = globalModel?.outliers ?: 0,
            globalAnchorsInliers = globalInliers,
            globalRate = globalModel?.rate ?: 1.0,
            globalOffsetSec = globalModel?.offsetSec ?: 0.0,
            globalModelFallback = globalModel?.usedFallbackRate ?: true,
            anchorScoreThreshold = defaults.anchorScoreThreshold,
            anchorScoreMin = minScore,
            anchorScoreAvg = avgScore,
            detectedEventsOriginal = anchors.detectedEventsOriginal,
            detectedEventsDub = anchors.detectedEventsDub,
            eventMatchesCandidates = anchors.eventMatchesCandidates,
            eventMatchesMutual = anchors.eventMatchesMutual,
            modelResidualRmsSec = anchors.modelResidualRmsSec,
            modelResidualMaxSec = anchors.modelResidualMaxSec,
            modelMidpointResidualSec = anchors.modelMidpointResidualSec,
            anchorsCoverStart = anchors.coverageStart,
            anchorsCoverMiddle = anchors.coverageMiddle,
            anchorsCoverEnd = anchors.coverageEnd,
            lowConfidence = lowConfidence,
        )
    }

    private fun readMonoPcm16Wav(path: Path, expectedSampleRate: Int): ShortArray {
        val bytes = path.readBytes()
        check(bytes.size >= 44) { "WAV is too small: $path" }
        check(leInt(bytes, 0) == RIFF_ID) { "WAV RIFF header missing: $path" }
        check(leInt(bytes, 8) == WAVE_ID) { "WAV WAVE header missing: $path" }

        var offset = 12
        var formatCode = -1
        var channels = -1
        var sampleRate = -1
        var bitsPerSample = -1
        var dataStart = -1
        var dataSize = -1

        while (offset + 8 <= bytes.size) {
            val chunkId = leInt(bytes, offset)
            val chunkSize = leInt(bytes, offset + 4)
            val chunkDataStart = offset + 8
            if (chunkDataStart + chunkSize > bytes.size) {
                break
            }

            when (chunkId) {
                FMT_ID -> {
                    check(chunkSize >= 16) { "Invalid fmt chunk in $path" }
                    formatCode = leShort(bytes, chunkDataStart).toInt() and 0xFFFF
                    channels = leShort(bytes, chunkDataStart + 2).toInt() and 0xFFFF
                    sampleRate = leInt(bytes, chunkDataStart + 4)
                    bitsPerSample = leShort(bytes, chunkDataStart + 14).toInt() and 0xFFFF
                }

                DATA_ID -> {
                    dataStart = chunkDataStart
                    dataSize = chunkSize
                }
            }

            offset = chunkDataStart + chunkSize + (chunkSize and 1)
        }

        check(formatCode == 1) { "Expected PCM WAV, got format=$formatCode for $path" }
        check(channels == 1) { "Expected mono WAV, got channels=$channels for $path" }
        check(bitsPerSample == 16) { "Expected 16-bit WAV, got bitsPerSample=$bitsPerSample for $path" }
        check(sampleRate == expectedSampleRate) {
            "Unexpected sample rate for $path. Expected $expectedSampleRate, got $sampleRate"
        }
        check(dataStart >= 0 && dataSize > 0) { "WAV data chunk not found in $path" }
        check(dataSize % 2 == 0) { "WAV data chunk has odd byte size in $path" }

        val sampleCount = dataSize / 2
        return ShortArray(sampleCount) { index ->
            leShort(bytes, dataStart + index * 2)
        }
    }

    private fun findBestNcc(
        query: DoubleArray,
        target: DoubleArray,
        candidateStartInclusive: Int,
        candidateStartExclusive: Int,
    ): NccMatch? {
        val querySize = query.size
        if (querySize == 0 || target.size < querySize) {
            return null
        }

        val minStart = max(0, candidateStartInclusive)
        val maxStart = min(target.size - querySize + 1, candidateStartExclusive)
        if (minStart >= maxStart) {
            return null
        }

        val queryMean = query.average()
        val centered = DoubleArray(querySize) { query[it] - queryMean }
        val queryVariance = centered.sumOf { it * it }
        if (queryVariance <= EPSILON) {
            return null
        }

        var bestStart = -1
        var bestScore = Double.NEGATIVE_INFINITY

        var candidateStart = minStart
        while (candidateStart < maxStart) {
            var sum = 0.0
            var sumSq = 0.0
            var dot = 0.0
            for (index in 0 until querySize) {
                val sample = target[candidateStart + index]
                sum += sample
                sumSq += sample * sample
                dot += centered[index] * sample
            }

            val mean = sum / querySize
            val variance = sumSq - querySize * mean * mean
            if (variance > EPSILON) {
                val score = dot / sqrt(queryVariance * variance)
                if (score > bestScore) {
                    bestScore = score
                    bestStart = candidateStart
                }
            }

            candidateStart += 1
        }

        return if (bestStart >= 0 && bestScore.isFinite()) NccMatch(bestStart, bestScore) else null
    }

    private fun smoothMovingAverage(values: DoubleArray, window: Int): DoubleArray {
        if (values.isEmpty() || window <= 1) {
            return values
        }

        val half = max(1, window / 2)
        val prefixSums = DoubleArray(values.size + 1)
        for (index in values.indices) {
            prefixSums[index + 1] = prefixSums[index] + values[index]
        }

        return DoubleArray(values.size) { index ->
            val start = max(0, index - half)
            val end = min(values.size, index + half + 1)
            val sum = prefixSums[end] - prefixSums[start]
            sum / (end - start)
        }
    }

    private fun percentile95(values: List<Double>): Double = percentile(values, 0.95)

    private fun percentile(values: List<Double>, level: Double): Double {
        if (values.isEmpty()) {
            return 0.0
        }

        val normalizedLevel = level.coerceIn(0.0, 1.0)
        val sorted = values.sorted()
        val index = (sorted.lastIndex * normalizedLevel).roundToInt().coerceIn(0, sorted.lastIndex)
        return sorted[index]
    }

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) {
            return 1.0
        }

        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[middle - 1] + sorted[middle]) / 2.0
        } else {
            sorted[middle]
        }
    }

    private fun leInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private fun leShort(bytes: ByteArray, offset: Int): Short =
        (
            (bytes[offset].toInt() and 0xFF) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 8)
            ).toShort()

    private fun formatDouble(value: Double): String =
        String.format(Locale.US, "%.6f", value)

    private fun jsonNumber(value: Double): Double =
        formatDouble(value).toDouble()

    private data class NccMatch(
        val start: Int,
        val score: Double,
    )

    private data class AnchorDetectionResult(
        val accepted: List<SyncAnchor> = emptyList(),
        val candidates: Int = 0,
        val rejectedByScore: Int = 0,
        val rejectedByOrder: Int = 0,
        val rejectedByModel: Int = 0,
        val rejectedByShift: Int = 0,
        val detectedEventsOriginal: Int = 0,
        val detectedEventsDub: Int = 0,
        val eventMatchesCandidates: Int = 0,
        val eventMatchesMutual: Int = 0,
        val modelResidualRmsSec: Double = 0.0,
        val modelResidualMaxSec: Double = 0.0,
        val modelMidpointResidualSec: Double = 0.0,
        val coverageStart: Boolean = false,
        val coverageMiddle: Boolean = false,
        val coverageEnd: Boolean = false,
        val preferredModel: GlobalSyncModel? = null,
    )

    private data class AnchorCoverage(
        val start: Boolean,
        val middle: Boolean,
        val end: Boolean,
    )

    private enum class EventType {
        ATTACK,
        SILENCE_ENTER,
        SILENCE_EXIT,
        ACTIVITY_START,
        ACTIVITY_END,
    }

    private data class SyncEvent(
        val type: EventType,
        val index: Int,
        val timeSec: Double,
        val saliency: Double,
    )

    private data class EventMatch(
        val original: SyncEvent,
        val dub: SyncEvent,
        val score: Double,
        val originalEventIndex: Int,
        val dubEventIndex: Int,
    )

    private data class EventLinearModel(
        val rate: Double,
        val offsetSec: Double,
        val inliers: List<EventMatch>,
        val score: Double,
    )

    private data class EventModelEstimation(
        val rate: Double,
        val offsetSec: Double,
        val inliers: List<EventMatch>,
        val rejectedByShift: Int,
        val usedFallbackRate: Boolean,
        val residualRmsSec: Double,
        val residualMaxSec: Double,
        val midpointResidualSec: Double,
    )

    private data class WeightedModel(
        val rate: Double,
        val offsetSec: Double,
    )

    private data class ResidualStats(
        val rmsSec: Double,
        val maxSec: Double,
        val midpointSec: Double,
    )

    private data class SegmentBuildResult(
        val segments: List<SyncMatchedSegment> = emptyList(),
        val globalModel: GlobalSyncModel? = null,
    )

    private class EnvelopeSeriesData(
        val fineHz: Int,
        val coarseHz: Int,
        val originalFine: DoubleArray,
        val dubFine: DoubleArray,
        val originalCoarse: DoubleArray,
        val dubCoarse: DoubleArray,
    )

    private data class GlobalSyncModel(
        val rate: Double,
        val offsetSec: Double,
        val inliers: List<SyncAnchor>,
        val outliers: Int,
        val usedFallbackRate: Boolean,
    )

    private companion object {
        const val MAX_PCM_SAMPLE = 32768.0
        const val MIN_SEGMENT_SEC = 0.08
        const val MIN_MODEL_RESIDUAL_SEC = 2.0
        const val MODEL_RESIDUAL_SHARE = 0.1
        const val MIN_EVENT_DISTANCE_SEC = 0.25
        const val MIN_STABLE_SILENCE_SEC = 0.25
        const val MIN_ATTACK_DELTA = 0.02
        const val SILENCE_PERCENTILE = 0.15
        const val ATTACK_PERCENTILE = 0.85
        const val MIN_SILENCE_LEVEL = 0.03
        const val MAX_SILENCE_LEVEL = 0.2
        const val SILENCE_EDGE_SALIENCY = 0.08
        const val ACTIVITY_EDGE_SALIENCY = 0.3
        const val EVENT_DESCRIPTOR_SEC = 1.8
        const val MIN_DESCRIPTOR_HALF_WINDOW_POINTS = 4
        const val MIN_DESCRIPTOR_POINTS = 10
        const val MATCH_TYPE_BONUS = 0.05
        const val EVENT_AMBIGUITY_MARGIN = 0.06
        const val EVENT_STRONG_AMBIGUITY_MARGIN = 0.12
        const val MAX_MATCHES_PER_ORIGINAL_EVENT = 3
        const val MAX_MATCHES_PER_DUB_EVENT = 4
        const val MATCH_DESCRIPTOR_WEIGHT = 0.82
        const val MATCH_SALIENCY_WEIGHT = 0.12
        const val MATCH_POSITION_PENALTY = 0.35
        const val MODEL_INLIER_WEIGHT = 2.0
        const val MODEL_COVERAGE_WEIGHT = 4.0
        const val MODEL_RESIDUAL_WEIGHT = 2.5
        const val ANCHOR_MATCH_DISTANCE_SHARE = 0.0008
        const val MIN_ANCHOR_MATCH_DISTANCE_SEC = 0.45
        const val MIN_CONFIDENT_ANCHORS = 5
        const val MIN_MODEL_PAIR_DISTANCE_SEC = 1.2
        const val MIN_EVENT_RESIDUAL_SEC = 0.3
        const val EVENT_MAX_RESIDUAL_SEC = 2.2
        const val EVENT_RESIDUAL_SHARE = 0.08
        const val MIN_MATCH_WEIGHT = 0.05
        const val RMS_RESIDUAL_WARN_SEC = 0.09
        const val MAX_RESIDUAL_WARN_SEC = 0.18
        const val MIDPOINT_RESIDUAL_WARN_SEC = 0.12
        const val COVERAGE_START_MAX_SHARE = 0.2
        const val COVERAGE_MIDDLE_MIN_SHARE = 0.35
        const val COVERAGE_MIDDLE_MAX_SHARE = 0.65
        const val COVERAGE_END_MIN_SHARE = 0.8
        const val MAX_EVENT_MODEL_MATCHES = 160
        const val MODEL_MATCH_BUCKETS = 12
        const val EPSILON = 1e-9
        const val RIFF_ID = 0x46464952
        const val WAVE_ID = 0x45564157
        const val FMT_ID = 0x20746d66
        const val DATA_ID = 0x61746164
    }
}
