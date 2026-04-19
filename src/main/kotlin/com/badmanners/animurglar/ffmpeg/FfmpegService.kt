package com.badmanners.animurglar.ffmpeg

import com.badmanners.animurglar.pipeline.EpisodeMergeInput
import com.badmanners.animurglar.pipeline.SyncedDubAudio
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import org.apache.logging.log4j.LogManager
import org.bytedeco.ffmpeg.ffmpeg
import org.bytedeco.javacpp.Loader
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.writeText
import kotlin.math.abs


class FfmpegService {

    private val logger = LogManager.getLogger(FfmpegService::class.java)

    private val ffmpegPath = Loader.load(ffmpeg::class.java)

    suspend fun remuxHlsSegmentsToMp4(
        segmentFiles: List<Path>,
        destinationMp4: Path,
        workDir: Path,
    ) = withContext(Dispatchers.IO) {
        check(segmentFiles.isNotEmpty()) {
            "No HLS segments to remux"
        }

        destinationMp4.parent.createDirectories()

        val concatListPath = workDir.resolve("segments.txt")
        val concatListContent = segmentFiles.joinToString(separator = "\n") { segmentPath ->
            "file '${segmentPath.toAbsolutePath().toString().replace("\\", "/").replace("'", "\\'")}'"
        }
        concatListPath.writeText(concatListContent, StandardCharsets.UTF_8)

        executeFfmpeg(
            operationName = "remux_hls_segments_to_mp4",
            arguments = listOf(
                "-hide_banner", // Hide version/build banner in logs.
                "-loglevel", "error", // Print only errors.
                "-y", // Overwrite destination file if it already exists.
                "-f", "concat", // Read input using concat demuxer.
                "-safe", "0", // Allow absolute paths in concat list.
                "-i", concatListPath.toAbsolutePath().toString(), // Input concat list file.
                "-sn", // Drop subtitles.
                "-dn", // Drop data streams.
                "-c", "copy", // Remux without re-encoding.
                destinationMp4.toAbsolutePath().toString(), // Output MP4 path.
            ),
            workDir = workDir,
            outputPath = destinationMp4,
        )
    }

    suspend fun extractAudioFromMp4(
        inputMp4: Path,
        outputAudio: Path,
        workDir: Path,
    ) = withContext(Dispatchers.IO) {
        outputAudio.parent.createDirectories()
        executeFfmpeg(
            operationName = "extract_audio_from_mp4",
            arguments = listOf(
                "-hide_banner", // Hide version/build banner in logs.
                "-loglevel", "error", // Print only errors.
                "-y", // Overwrite destination file if it already exists.
                "-fflags", "+genpts",
                "-i", inputMp4.toAbsolutePath().toString(),     // Input MP4 path.
                "-vn", // Disable video output; keep only audio.
                "-map", DEFAULT_AUDIO_MAP, // Select first audio stream from first input.
                "-af", "asetpts=N/SR/TB",
                "-c:a", "aac",
                "-b:a", "192k",
                outputAudio.toAbsolutePath().toString(), // Output audio path.
            ),
            workDir = workDir,
            outputPath = outputAudio,
        )
    }

    suspend fun normalizeAudioForAnalysis(
        inputMedia: Path,
        outputWav: Path,
        mapAudioStream: String = DEFAULT_AUDIO_MAP,
        sampleRate: Int = ANALYSIS_SAMPLE_RATE,
    ) = withContext(Dispatchers.IO) {
        outputWav.parent.createDirectories()
        executeFfmpeg(
            operationName = "normalize_audio_for_analysis",
            arguments = listOf(
                "-hide_banner",
                "-loglevel", "error",
                "-y",
                "-i", inputMedia.toAbsolutePath().toString(),
                "-vn",
                "-map", mapAudioStream,
                "-ac", "1",
                "-ar", sampleRate.toString(),
                "-c:a", "pcm_s16le",
                outputWav.toAbsolutePath().toString(),
            ),
            workDir = outputWav.parent,
            outputPath = outputWav,
        )
    }

    suspend fun applySyncPlan(
        request: ApplySyncRequest,
        workDir: Path,
    ): ApplySyncResult = withContext(Dispatchers.IO) {
        val segments = request.plan.segments.sortedBy { it.origStartSec }
        val gaps = request.plan.gaps.sortedBy { it.origStartSec }
        require(segments.isNotEmpty() || gaps.isNotEmpty()) {
            "Sync plan has neither segments nor gaps for ${request.inputDubPath.fileName}"
        }

        val chunks = buildTimelineChunks(
            segments = segments,
            gaps = gaps,
        )
        require(chunks.isNotEmpty()) {
            "Sync plan timeline is empty for ${request.inputDubPath.fileName}"
        }

        request.outputPath.parent.createDirectories()
        val arguments = buildApplyArguments(
            request = request,
            chunks = chunks,
        )
        executeFfmpeg(
            operationName = "sync_apply",
            arguments = arguments,
            workDir = workDir,
            outputPath = request.outputPath,
        )

        ApplySyncResult(
            syncedTrack = SyncedDubAudio(
                episodeNumber = request.episodeNumber,
                sourceId = request.sourceId,
                teamName = request.teamName,
                inputPath = request.inputDubPath,
                syncedPath = request.outputPath,
            ),
            command = listOf(ffmpegPath) + arguments,
        )
    }

    suspend fun mergeEpisode(request: MergeRequest): MergeResult = withContext(Dispatchers.IO) {
        val orderedDubTracks = request.input.dubTracks.sortedWith(
            compareBy({ it.sourceId.lowercase() }, { it.teamName.lowercase() })
        )
        val mergeInput = request.input.copy(dubTracks = orderedDubTracks)

        mergeInput.outputPath.parent.createDirectories()
        executeFfmpeg(
            operationName = "merge_episode",
            arguments = buildMergeArguments(mergeInput),
            workDir = mergeInput.outputPath.parent,
            outputPath = mergeInput.outputPath,
        )

        MergeResult(
            episodeNumber = mergeInput.episodeNumber,
            outputPath = mergeInput.outputPath,
        )
    }

    private fun buildMergeArguments(input: EpisodeMergeInput): List<String> {
        val arguments = mutableListOf<String>()
        arguments += listOf(
            "-hide_banner", // Hide version/build banner in logs.
            "-loglevel", "error", // Print only errors.
            "-y", // Overwrite destination file if it already exists.
            "-i", input.videoPath.toAbsolutePath().toString(), // Primary video input.
        )

        input.dubTracks.forEach { dubTrack ->
            arguments += listOf(
                "-i", dubTrack.audioPath.toAbsolutePath().toString(), // Dub audio input.
            )
        }

        arguments += listOf(
            "-map", "0:v:0", // Keep the first video stream from the main input.
        )

        var nextInputIndex = 1
        input.dubTracks.forEach {
            arguments += listOf(
                "-map", "$nextInputIndex:a:0", // Map each dub track in deterministic input order.
            )
            nextInputIndex += 1
        }

        arguments += listOf(
            "-map", "0:a?", // Keep all embedded original audio streams from the main input.
        )

        input.dubTracks.forEachIndexed { index, dubTrack ->
            arguments += listOf(
                "-metadata:s:a:$index",
                "title=${dubTrack.teamName}", // Use dub team as track title.
                "-metadata:s:a:$index",
                "language=${dubTrack.languageTag}", // Use source-provided language tag for the dub track.
            )
        }

        arguments += listOf(
            "-disposition:a", "0", // Clear all audio dispositions (including any forced flags).
        )

        if (input.dubTracks.isNotEmpty()) {
            arguments += listOf(
                "-disposition:a:0", "default", // Make only the first Russian dub track default.
            )
        }

        arguments += listOf(
            "-c", "copy", // Stream copy all mapped tracks without re-encoding.
            "-metadata", "title=Episode ${input.episodeNumber}", // Container title metadata.
            input.outputPath.toAbsolutePath().toString(), // Final merged file path.
        )
        return arguments
    }

    private fun buildApplyArguments(
        request: ApplySyncRequest,
        chunks: List<TimelineChunk>,
    ): List<String> {
        val filters = mutableListOf<String>()
        val labels = mutableListOf<String>()

        chunks.forEachIndexed { index, chunk ->
            val outLabel = "c$index"
            labels += "[$outLabel]"

            when (chunk) {
                is TimelineChunk.Match -> {
                    val dubLen = chunk.dubEndSec - chunk.dubStartSec
                    val origLen = chunk.origEndSec - chunk.origStartSec
                    if (dubLen <= MIN_SEGMENT_SEC || origLen <= MIN_SEGMENT_SEC) {
                        filters += buildSilentChunkFilter(outLabel = outLabel, durationSec = origLen)
                    } else {
                        val tempo = dubLen / origLen
                        val tempoChain = when {
                            abs(tempo - 1.0) <= TEMPO_BYPASS_DELTA -> emptyList()
                            else -> buildTempoChain(tempo)
                        }
                        val chainFilters = buildList {
                            add("atrim=start=${formatSeconds(chunk.dubStartSec)}:end=${formatSeconds(chunk.dubEndSec)}")
                            add("asetpts=PTS-STARTPTS")
                            tempoChain.forEach { factor ->
                                add("atempo=${formatTempo(factor)}")
                            }
                        }
                        val chain = "[0:a]${chainFilters.joinToString(separator = ",")}[$outLabel]"
                        filters += chain
                    }
                }

                is TimelineChunk.Gap -> {
                    val gapLen = (chunk.origEndSec - chunk.origStartSec).coerceAtLeast(0.0)
                    filters += buildSilentChunkFilter(outLabel = outLabel, durationSec = gapLen)
                }
            }
        }

        val concatInput = labels.joinToString(separator = "")
        val finalDuration = request.plan.originalDurationSec.coerceAtLeast(0.0)
        filters += "$concatInput concat=n=${chunks.size}:v=0:a=1[cat]"
        filters += "[cat]apad=whole_dur=${formatSeconds(finalDuration)},atrim=end=${formatSeconds(finalDuration)},asetpts=PTS-STARTPTS[outa]"

        return listOf(
            "-hide_banner",
            "-loglevel", "error",
            "-y",
            "-i", request.inputDubPath.toAbsolutePath().toString(),
            "-filter_complex", filters.joinToString(separator = ";"),
            "-map", "[outa]",
            request.outputPath.toAbsolutePath().toString(),
        )
    }

    private fun buildSilentChunkFilter(outLabel: String, durationSec: Double): String {
        val duration = formatSeconds(durationSec.coerceAtLeast(0.0))
        return "[0:a]apad,atrim=start=0:end=$duration,asetpts=PTS-STARTPTS,volume=0[$outLabel]"
    }

    private fun buildTimelineChunks(
        segments: List<SyncMatchedSegment>,
        gaps: List<SyncGapSegment>,
    ): List<TimelineChunk> {
        val chunks = mutableListOf<TimelineChunk>()
        segments.forEach { chunks += TimelineChunk.Match(it.origStartSec, it.origEndSec, it.dubStartSec, it.dubEndSec) }
        gaps.forEach { chunks += TimelineChunk.Gap(it.origStartSec, it.origEndSec) }

        return chunks
            .sortedBy { it.origStartSec }
            .fold(mutableListOf()) { acc, chunk ->
                if (chunk.origEndSec - chunk.origStartSec <= MIN_SEGMENT_SEC) {
                    return@fold acc
                }

                if (acc.isEmpty()) {
                    acc += chunk
                    return@fold acc
                }

                val previous = acc.last()
                if (chunk.origStartSec < previous.origEndSec - EPSILON_SEC) {
                    if (chunk is TimelineChunk.Match && previous is TimelineChunk.Gap) {
                        acc[acc.lastIndex] = TimelineChunk.Gap(previous.origStartSec, chunk.origStartSec)
                        acc += chunk
                    } else {
                        logger.debug("Skipping overlapping sync chunk {} after {}", chunk, previous)
                    }
                    return@fold acc
                }

                acc += chunk
                acc
            }
    }

    private fun buildTempoChain(tempo: Double): List<Double> {
        require(tempo.isFinite() && tempo > 0.0) { "Invalid tempo factor: $tempo" }

        val chain = mutableListOf<Double>()
        var remaining = tempo
        while (remaining > MAX_ATEMPO) {
            chain += MAX_ATEMPO
            remaining /= MAX_ATEMPO
        }
        while (remaining < MIN_ATEMPO) {
            chain += MIN_ATEMPO
            remaining /= MIN_ATEMPO
        }
        if (abs(remaining - 1.0) > EPSILON_SEC) {
            chain += remaining
        }
        return chain
    }

    private fun formatSeconds(value: Double): String =
        String.format(Locale.US, "%.6f", value.coerceAtLeast(0.0))

    private fun formatTempo(value: Double): String =
        String.format(Locale.US, "%.6f", value)

    private suspend fun executeFfmpeg(
        operationName: String,
        arguments: List<String>,
        workDir: Path,
        outputPath: Path,
    ) = coroutineScope {
        val command = listOf(ffmpegPath) + arguments
        logger.debug(
            "Starting FFmpeg {} | workDir={} | output={} | command={}",
            operationName,
            workDir,
            outputPath,
            formatCommand(command),
        )

        val process = ProcessBuilder()
            .command(command)
            .directory(workDir.toFile())
            .redirectErrorStream(true)
            .start()

        val processOutputReader = async(Dispatchers.IO) {
            process.inputStream.bufferedReader(StandardCharsets.UTF_8).use { reader ->
                reader.readText().trim()
            }
        }

        val capturedOutput: String
        try {
            process.onExit().await()
            capturedOutput = processOutputReader.await()
        } catch (cancellation: CancellationException) {
            process.destroyForcibly()
            runCatching {
                process.waitFor(PROCESS_EXIT_WAIT_MS, TimeUnit.MILLISECONDS)
            }
            processOutputReader.cancel(cancellation)
            withContext(NonCancellable) { deleteOutputIfExistsSafely(outputPath, operationName) }
            logger.debug("FFmpeg {} cancelled | output={}", operationName, outputPath)
            throw cancellation
        }

        val exitCode = process.waitFor()
        if (exitCode != 0) {
            withContext(NonCancellable) { deleteOutputIfExistsSafely(outputPath, operationName) }
            logger.error(
                "FFmpeg {} failed (exit={}) | output={} | command={} | ffmpegOutput={}",
                operationName, exitCode, outputPath, formatCommand(command), truncateOutput(capturedOutput),
            )
            throw FfmpegExecutionException(
                exitCode = exitCode,
                command = command,
                outputPath = outputPath,
                processOutput = capturedOutput,
            )
        }

        check(outputPath.exists() && outputPath.fileSize() != 0L) {
            "FFmpeg produced empty output for ${outputPath.fileName}"
        }

        logger.info("FFmpeg {} produced {}", operationName, outputPath)
        if (capturedOutput.isNotBlank()) {
            logger.debug("FFmpeg {} output: {}", operationName, truncateOutput(capturedOutput))
        }
    }

    private suspend fun deleteOutputIfExistsSafely(outputPath: Path, operationName: String) {
        var lastError: Throwable? = null

        repeat(OUTPUT_DELETE_ATTEMPTS) { attempt ->
            try {
                outputPath.deleteIfExists()
            } catch (e: Throwable) {
                lastError = e
                if (attempt != OUTPUT_DELETE_ATTEMPTS - 1)
                    delay(OUTPUT_DELETE_RETRY_DELAY_MS)
            }
        }

        logger.debug(
            "Failed to cleanup FFmpeg output after {} | output={} | message={}",
            operationName, outputPath, lastError?.message,
        )
    }


    private fun formatCommand(command: List<String>): String = command.joinToString(separator = " ") { argument ->
        if (argument.contains(' ')) "\"$argument\"" else argument
    }

    private fun truncateOutput(output: String): String =
        output.take(MAX_OUTPUT_CHARS)

    class FfmpegExecutionException(
        val exitCode: Int,
        val command: List<String>,
        val outputPath: Path,
        val processOutput: String,
    ) : IllegalStateException(
        buildString {
            append("FFmpeg failed (exit=")
            append(exitCode)
            append(") for output ")
            append(outputPath)
            append(" | command=")
            append(command.joinToString(separator = " ") { argument ->
                if (argument.contains(' ')) "\"$argument\"" else argument
            })
            if (processOutput.isNotBlank()) {
                append(" | output=")
                append(processOutput.take(MAX_OUTPUT_CHARS))
            }
        },
    )

    private companion object {
        const val MAX_OUTPUT_CHARS = 3_000
        const val DEFAULT_AUDIO_MAP = "0:a:0"
        const val ANALYSIS_SAMPLE_RATE = 16_000
        const val MIN_ATEMPO = 0.5
        const val MAX_ATEMPO = 2.0
        const val TEMPO_BYPASS_DELTA = 0.0001
        const val MIN_SEGMENT_SEC = 0.03
        const val EPSILON_SEC = 1e-6
        const val PROCESS_EXIT_WAIT_MS = 2000L
        const val OUTPUT_DELETE_ATTEMPTS = 3
        const val OUTPUT_DELETE_RETRY_DELAY_MS = 1000L
    }

    private sealed class TimelineChunk(
        open val origStartSec: Double,
        open val origEndSec: Double,
    ) {
        data class Match(
            override val origStartSec: Double,
            override val origEndSec: Double,
            val dubStartSec: Double,
            val dubEndSec: Double,
        ) : TimelineChunk(origStartSec, origEndSec)

        data class Gap(
            override val origStartSec: Double,
            override val origEndSec: Double,
        ) : TimelineChunk(origStartSec, origEndSec)
    }
}
