package com.badmanners.animurglar.subtitles

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readLines
import kotlin.io.path.writeLines


class SubtitleCaptionFilterService {

    suspend fun writeCaptionsOnlySubtitle(fullPath: Path, captionsOnlyPath: Path) {
        withContext(Dispatchers.IO) {
            captionsOnlyPath.parent.createDirectories()
            captionsOnlyPath.writeLines(filterCaptionsOnly(fullPath.readLines()))
        }
    }

    fun filterCaptionsOnly(lines: List<String>): List<String> {
        val script = AssScript.parse(lines)
        val captionLineIndexes = script.captionLineIndexes()
        return lines.filterIndexed { index, line ->
            !script.isDialogueLine(index, line) || index in captionLineIndexes
        }
    }

    private data class AssScript(
        val lines: List<String>,
        val events: List<AssEvent>,
        val styles: Map<String, AssStyle>,
    ) {
        private val eventLineIndexes = events.mapTo(mutableSetOf()) { it.lineIndex }

        fun isDialogueLine(index: Int, line: String) =
            index in eventLineIndexes && line.startsWithAssPrefix("Dialogue:")

        fun captionLineIndexes(): Set<Int> {
            val styleStats = events
                .groupBy { it.styleKey }
                .mapValues { (styleKey, styleEvents) ->
                    AssStyleStats(
                        style = styles[styleKey],
                        events = styleEvents,
                    )
                }

            val directCaptionIndexes = events
                .filter { event -> isCaptionEvent(event, styleStats[event.styleKey]) }
                .mapTo(mutableSetOf()) { it.lineIndex }

            events.groupBy { it.timeGroupKey }.values.forEach { group ->
                when {
                    group.none { it.lineIndex in directCaptionIndexes } -> Unit
                    else -> group
                        .filter { event -> isRelatedVisualEvent(event, styleStats[event.styleKey]) }
                        .forEach { event -> directCaptionIndexes += event.lineIndex }
                }
            }

            return directCaptionIndexes
        }

        private fun isCaptionEvent(event: AssEvent, styleStats: AssStyleStats?): Boolean {
            val stats = styleStats ?: return event.isStrongVisualCaptionCandidate()
            return when {
                event.hasExplicitCaptionMarker() -> true
                stats.isCaptionStyle -> true
                event.isStrongVisualCaptionCandidate() -> true
                else -> false
            }
        }

        private fun isRelatedVisualEvent(event: AssEvent, styleStats: AssStyleStats?): Boolean {
            val stats = styleStats ?: return event.isVisualEvent
            return when {
                event.hasExplicitCaptionMarker() -> true
                stats.isCaptionStyle -> true
                !stats.isDialogueStyle && event.isVisualEvent -> true
                event.isDrawingEvent -> true
                else -> false
            }
        }

        companion object {
            fun parse(lines: List<String>): AssScript {
                var section = AssSection.OTHER
                var styleFormat: AssFormat? = null
                var eventsFormat: AssFormat? = null
                val styles = mutableMapOf<String, AssStyle>()
                val events = mutableListOf<AssEvent>()

                lines.forEachIndexed { index, line ->
                    when {
                        line.isSectionHeader() -> section = AssSection.fromLine(line)
                        section == AssSection.STYLES && line.startsWithAssPrefix("Format:") -> {
                            styleFormat = AssFormat.fromLine(line)
                        }

                        section == AssSection.STYLES && line.startsWithAssPrefix("Style:") -> {
                            val format = styleFormat
                            check(format != null) { "ASS style line found before style format: $line" }
                            val style = AssStyle.fromLine(line, format)
                            styles[style.key] = style
                        }

                        section == AssSection.EVENTS && line.startsWithAssPrefix("Format:") -> {
                            eventsFormat = AssFormat.fromLine(line)
                        }

                        section == AssSection.EVENTS && line.startsWithAssPrefix("Dialogue:") -> {
                            val format = eventsFormat
                            check(format != null) { "ASS dialogue line found before events format: $line" }
                            events += AssEvent.fromLine(index, line, format)
                        }
                    }
                }

                return AssScript(
                    lines = lines,
                    events = events,
                    styles = styles,
                )
            }
        }
    }

    private data class AssStyleStats(
        val style: AssStyle?,
        val events: List<AssEvent>,
    ) {
        val isDialogueStyle = style?.name?.isKnownDialogueStyleName() == true
        val isCaptionStyle = when {
            style == null -> false
            style.name.hasExplicitCaptionMarker() -> true
            isDialogueStyle -> false
            style.name.hasTypesetStyleMarker() -> true
            events.isEmpty() -> false
            else -> events.count { it.isVisualEvent }.toDouble() / events.size >= VISUAL_STYLE_RATIO
        }
    }

    private data class AssStyle(
        val name: String,
    ) {
        val key = name.assKey()

        companion object {
            fun fromLine(line: String, format: AssFormat): AssStyle {
                val fields = line.assFields(format.fieldCount)
                return AssStyle(name = format.field(fields, "Name"))
            }
        }
    }

    private data class AssEvent(
        val lineIndex: Int,
        val line: String,
        val style: String,
        val actor: String,
        val start: String,
        val end: String,
        val text: String,
    ) {
        val styleKey = style.assKey()
        val timeGroupKey = AssTimeGroupKey(start = start, end = end)
        val visualScore = calculateVisualScore(text)
        val isVisualEvent = visualScore >= VISUAL_EVENT_SCORE
        val isDrawingEvent = ASS_DRAWING_MODE_REGEX.containsMatchIn(text)

        fun hasExplicitCaptionMarker() = style.hasExplicitCaptionMarker() || actor.hasExplicitCaptionMarker()

        fun isStrongVisualCaptionCandidate() = when {
            hasExplicitCaptionMarker() -> true
            style.isKnownDialogueStyleName() && visualScore >= STRONG_VISUAL_EVENT_SCORE -> true
            style.isKnownDialogueStyleName() -> false
            style.hasTypesetStyleMarker() -> true
            else -> visualScore >= STRONG_VISUAL_EVENT_SCORE
        }

        companion object {
            fun fromLine(lineIndex: Int, line: String, format: AssFormat): AssEvent {
                val fields = line.assFields(format.fieldCount)
                return AssEvent(
                    lineIndex = lineIndex,
                    line = line,
                    style = format.field(fields, "Style"),
                    actor = format.field(fields, "Name", "Actor"),
                    start = format.field(fields, "Start"),
                    end = format.field(fields, "End"),
                    text = format.field(fields, "Text", "T"),
                )
            }
        }
    }

    private data class AssTimeGroupKey(
        val start: String,
        val end: String,
    )

    private data class AssFormat(
        val fields: List<String>,
    ) {
        val fieldCount = fields.size

        fun field(values: List<String>, vararg names: String): String {
            val index = names
                .asSequence()
                .map { name -> fields.indexOfFirst { it.equals(name, ignoreCase = true) } }
                .firstOrNull { it >= 0 }

            return when {
                index == null -> ""
                index >= values.size -> ""
                else -> values[index].trim()
            }
        }

        companion object {
            fun fromLine(line: String): AssFormat {
                val fields = line.substringAfter(':').split(',').map { it.trim() }
                return AssFormat(fields)
            }
        }
    }

    private enum class AssSection {
        STYLES,
        EVENTS,
        OTHER;

        companion object {
            fun fromLine(line: String): AssSection {
                val sectionName = line.trim().removePrefix("[").removeSuffix("]")
                return when {
                    sectionName.equals("V4+ Styles", ignoreCase = true) -> STYLES
                    sectionName.equals("Events", ignoreCase = true) -> EVENTS
                    else -> OTHER
                }
            }
        }
    }

    companion object {
        private const val VISUAL_STYLE_RATIO = 0.65
        private const val VISUAL_EVENT_SCORE = 3
        private const val STRONG_VISUAL_EVENT_SCORE = 4

        private val ASS_TOKEN_REGEX = Regex("[\\p{L}\\p{N}]+")
        private val ASS_CAMEL_TOKEN_REGEX = Regex("\\p{Lu}?\\p{Ll}+|\\p{Lu}+(?!\\p{Ll})|\\p{N}+")
        private val ASS_OVERRIDE_BLOCK_REGEX = Regex("\\{[^}]*}")
        private val ASS_DRAWING_MODE_REGEX = Regex("\\\\p\\d+", RegexOption.IGNORE_CASE)
        private val EXPLICIT_CAPTION_MARKERS = setOf(
            "sign",
            "signs",
            "song",
            "songs",
            "lyric",
            "op",
            "ed",
            "lyrics",
            "caption",
            "captions",
            "eptitle",
            "nextep",
            "opening",
            "ending",
            "karaoke",
            "надпис",
            "надпись",
            "надписи",
            "текст",
            "титр",
            "титры",
        )
        private val TYPESET_STYLE_MARKERS = setOf(
            "typeset",
            "typesetting",
            "text",
            "title",
            "titles",
            "card",
            "cards",
            "overlay",
            "overlays",
            "screen",
            "onscreen",
            "signboard",
            "notice",
            "notices",
            "note",
            "notes",
            "message",
            "messages",
            "phone",
            "letter",
            "letters",
            "news",
            "location",
            "place",
            "places",
            "episode",
            "episodes",
            "preview",
            "credit",
            "credits",
            "logo",
            "logos",
        )
        private val DIALOGUE_STYLE_NAMES = setOf(
            "default",
            "defaultitalics",
            "defaulttop",
            "defaultitalicstop",
            "defaultoverlap",
            "flashback",
            "flashbackitalics",
            "flashbacktop",
            "flashbackitalicstop",
            "narration",
            "основной",
            "основнойсверху",
            "курсив",
            "курсивсверху",
        )

        private fun String.startsWithAssPrefix(prefix: String) = trimStart().startsWith(prefix, ignoreCase = true)

        private fun String.isSectionHeader(): Boolean {
            val trimmed = trim()
            return trimmed.startsWith('[') && trimmed.endsWith(']')
        }

        private fun String.assFields(fieldCount: Int) = substringAfter(':', missingDelimiterValue = "")
            .split(',', limit = fieldCount)

        private fun String.assTokens() = ASS_TOKEN_REGEX.findAll(this)
            .flatMap { token -> ASS_CAMEL_TOKEN_REGEX.findAll(token.value).map { it.value.lowercase() } }
            .toSet()

        private fun String.assCompactKey() = ASS_TOKEN_REGEX.findAll(lowercase())
            .joinToString(separator = "") { it.value }

        private fun String.assKey() = trim().lowercase()

        private fun String.hasExplicitCaptionMarker() =
            assTokens().any { token -> token in EXPLICIT_CAPTION_MARKERS || token.startsWith("надпис") }

        private fun String.hasTypesetStyleMarker() =
            assTokens().any { it in TYPESET_STYLE_MARKERS } || assCompactKey() in TYPESET_STYLE_MARKERS

        private fun String.isKnownDialogueStyleName() = assCompactKey() in DIALOGUE_STYLE_NAMES

        private fun calculateVisualScore(text: String): Int {
            val overrideText = ASS_OVERRIDE_BLOCK_REGEX.findAll(text).joinToString(separator = "") { it.value }
            return listOf(
                3 to listOf("\\pos", "\\move"),
                3 to listOf("\\p1", "\\p2", "\\p3", "\\p4"),
                2 to listOf("\\clip", "\\iclip"),
                1 to listOf("\\fad", "\\fade"),
                1 to listOf("\\fs", "\\fn"),
                1 to listOf("\\c&", "\\1c", "\\2c", "\\3c", "\\4c"),
                1 to listOf("\\blur", "\\be"),
                1 to listOf("\\frz", "\\frx", "\\fry", "\\fscx", "\\fscy"),
                1 to listOf("\\k", "\\ko", "\\kf"),
            ).sumOf { (score, markers) ->
                when {
                    markers.any { overrideText.contains(it, ignoreCase = true) } -> score
                    else -> 0
                }
            }
        }
    }
}