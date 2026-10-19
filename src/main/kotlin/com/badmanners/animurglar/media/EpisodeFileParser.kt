package com.badmanners.animurglar.media

import kotlin.text.RegexOption.IGNORE_CASE

object EpisodeFileParser {
    data class EpisodeIdentity(val season: RawSeason, val episodeNumber: Int)

    private val mediaExtensions = setOf("mkv", "mp4")
    private val seasonEpisodePatterns = listOf(
        Regex("""(?i)(?<![\p{L}\d])s(\d{1,2})[\s._-]*e(\d{1,3})(?:v\d+)?\b"""),
        Regex("""(?i)(?<![\p{L}\d])(\d{1,2})x(\d{1,3})(?:v\d+)?\b"""),
    )
    private val seasonPatterns = listOf(
        Regex("""(?i)(?<![\p{L}\d])s(\d{1,2})(?=$|[\s._+&~\[\]()/-])"""),
        Regex("""(?i)\b(\d{1,2})(?:st|nd|rd|th)?\s+season\b"""),
        Regex("""(?i)\bseason[\s._-]*(\d{1,2})(?!\d)"""),
        Regex("""第\s*(\d{1,2})\s*季"""),
    )
    private val multipleSeasonsRegex = Regex(
        """(?i)(?:\bs|\bseasons\s*)\d{1,2}\s*[-+&~]\s*(?:s|seasons?\s*)?\d{1,2}\b|\bseason\s*\d{1,2}\s*(?:[+&~]\s*(?:s|season\s*)?|-\s*(?:s|season\s*))\d{1,2}\b|\ball\s+seasons\b"""
    )
    private val extractEpisodePatterns = listOf(
        Regex("""(?i)\b(?:EP|E|EPISODE)\s*[-_. ]?0*(\d{1,3})(?:v\d+)?\b"""),
        Regex("""第\s*0*(\d{1,3})\s*[話话]"""),
        Regex("""(?i)\[(\d{1,3})](?=[^0-9]|$)"""),
        Regex("""(?i)-\s*0*(\d{1,3})(?:v\d+)?\b"""),
        Regex("""(?i)^\s*0*(\d{1,3})(?:v\d+)?(?=[\s._-]|$)"""),
        Regex("""(?i)(?:^|[\s\[(._-])0*(\d{1,3})(?:v\d+)?(?=$|[])\s._-]|\.m(?:kv|p4)\b)"""),
    )
    private val technicalNumbersRegex = Regex(
        """(?i)\b(?:\d{3,5}\s*[x×]\s*\d{3,5}|\d{3,4}p|[xh][ .-]?26[45]|(?:8|10|12)[ .-]*bits?|\d{2,3}(?:[.,]\d+)?\s*fps|(?:19|20)\d{2}|(?:CRC(?:32)?[ :=.-]*)?[0-9a-f]{8})\b"""
    )
    private val fractionalSuffixRegex = Regex("""^[.,]\d""")
    private val nonAlphaNumericRegex = Regex("""[^A-Z0-9]+""")
    private val extraMarkerRegex = Regex(
        """(?:^|[/\\\[(]|\s+-\s+)\s*EXTRAS?(?:\d+[A-Z]?)?(?:V\d+)?\b""",
        IGNORE_CASE,
    )
    private val specialsBlacklist = listOf(
        Regex("""\bNC(?:OP|ED)(?:\d+[A-Z]?)?(?:V\d+)?\b""", IGNORE_CASE),
        Regex("""\b(?:OP|ED)(?:\d+[A-Z]?)?(?:V\d+)?\b""", IGNORE_CASE),
        Regex("""\b(?:OPENING|ENDING)(?:\d+[A-Z]?)?(?:V\d+)?\b""", IGNORE_CASE),
        Regex("""\bSPECIALS?\b""", IGNORE_CASE),
        Regex("""\b(?:OVA|OAD|SP)(?:S|E?\d+[A-Z]?)?(?:V\d+)?\b""", IGNORE_CASE),
        Regex("""\bSAMPLES?(?:\d+[A-Z]?)?(?:V\d+)?\b""", IGNORE_CASE),
        Regex("""\b(?:CREDITLESS|NONCREDIT(?:ED|LESS)?)\b""", IGNORE_CASE),
        Regex("""\bTRAILERS?\b""", IGNORE_CASE),
        Regex("""\bPV\d*[A-Z]?\b""", IGNORE_CASE),
        Regex("""\bCM\d*[A-Z]?\b""", IGNORE_CASE),
        Regex("""\bMENU(?:\d+|(?:\s+NEW)?)\b""", IGNORE_CASE),
        Regex("""\b(?:TOKUTEN|OUTTAKES?|SHORTS?|OMAKE|BONUS)\b""", IGNORE_CASE),
    )

    fun parse(path: String): EpisodeIdentity? {
        if (path.containsSpecialsMarker()) return null
        val components = path.replace('\\', '/').split('/')
        val episodeNumber = components.last().extractEpisodeNumber() ?: return null
        val season = components.asReversed().firstNotNullOfOrNull { it.extractSeason() }
        if (season == 0) return null
        return EpisodeIdentity(RawSeason(season), episodeNumber)
    }

    fun <T> indexFiles(
        files: List<T>,
        isMovie: Boolean,
        path: (T) -> String,
    ): Map<RawSeason, Map<Int, T>> {
        val parsedFiles = files.mapNotNull { file -> parse(path(file))?.let { it to file } }
        val unknownFolders = parsedFiles.filter { it.first.season.number == null }
            .map { path(it.second).replace('\\', '/').substringBeforeLast('/', "") }.distinct()
        val separateFolders = unknownFolders.size > 1 || parsedFiles.any { it.first.season.number != null }
        val commonPrefix =
            parsedFiles.map { path(it.second).replace('\\', '/').substringBeforeLast('/', "").split('/') }
                .reduceOrNull { prefix, components ->
                    prefix.zip(components).takeWhile { it.first == it.second }.map { it.first }
                }.orEmpty()
        val numberedFiles = parsedFiles.map { (identity, file) ->
            val season = when {
                identity.season.number != null || !separateFolders -> identity.season
                else -> {
                    val folder = path(file).replace('\\', '/').substringBeforeLast('/', "")
                        .split('/').drop(commonPrefix.size).joinToString("/")
                    when {
                        folder.isEmpty() -> RawSeason(null)
                        else -> RawSeason(null, folder)
                    }
                }
            }
            identity.copy(season = season) to file
        }
        val conflicts = numberedFiles.groupBy({ it.first }, { it.second }).filterValues { it.size > 1 }
        require(conflicts.isEmpty()) {
            conflicts.entries.joinToString(
                prefix = "Конфликт номеров серий: ",
                separator = "; "
            ) { (identity, matches) ->
                "${identity.season.label}, серия ${identity.episodeNumber} — ${matches.joinToString { "«${path(it)}»" }}"
            }
        }
        return when {
            numberedFiles.isNotEmpty() -> numberedFiles.groupBy { it.first.season }.toSortedMap()
                .mapValues { (_, entries) -> entries.associate { it.first.episodeNumber to it.second }.toSortedMap() }

            isMovie && files.size == 1 && !path(files.single()).containsSpecialsMarker() ->
                mapOf(RawSeason(path(files.single()).extractSeason()) to sortedMapOf(1 to files.single()))

            else -> emptyMap()
        }
    }

    fun String.extractSeason(): Int? {
        val normalized = replace('_', ' ')
        val explicitSeasons = seasonEpisodePatterns.flatMap { pattern ->
            pattern.findAll(normalized).map { it.groupValues[1].toInt() }.toList()
        }.distinct()
        if (explicitSeasons.isNotEmpty()) return explicitSeasons.singleOrNull()
        if (multipleSeasonsRegex.containsMatchIn(normalized)) return null
        return normalized.seasonNumbers().singleOrNull()
    }

    private fun String.seasonNumbers(): List<Int> {
        val matches = mutableListOf<MatchResult>()
        (seasonEpisodePatterns + seasonPatterns).forEach { pattern ->
            pattern.findAll(this).forEach { match ->
                if (matches.none { it.range.first <= match.range.last && match.range.first <= it.range.last }) {
                    matches += match
                }
            }
        }
        return matches.map { it.groupValues[1].toInt() }.distinct()
    }

    fun String.isMediaFile() = substringAfterLast('.').lowercase() in mediaExtensions

    fun String.extractEpisodeNumber(): Int? {
        val fileName = replace('\\', '/').substringAfterLast('/').replace('_', ' ')
        seasonEpisodePatterns.firstNotNullOfOrNull { it.find(fileName) }?.let { match ->
            return match.episodeNumber(fileName, group = 2)
        }
        val normalized = seasonPatterns.fold(fileName.replace(technicalNumbersRegex, " ")) { value, pattern ->
            value.replace(pattern, " ")
        }
        val match = extractEpisodePatterns.asSequence()
            .flatMap { pattern -> pattern.findAll(normalized) }
            .firstOrNull { it.groupValues[1].toInt() in 1..999 } ?: return null
        return match.episodeNumber(normalized, group = 1)
    }

    private fun MatchResult.episodeNumber(value: String, group: Int) = when {
        fractionalSuffixRegex.containsMatchIn(value.substring(groups[group]!!.range.last + 1)) -> null
        else -> groupValues[group].toInt().takeIf { it in 1..999 }
    }

    fun String.containsSpecialsMarker(): Boolean {
        return replace('\\', '/').split('/').any { component ->
            val episode = seasonEpisodePatterns.firstNotNullOfOrNull { it.find(component.replace('_', ' ')) }
            val value = when (episode) {
                null -> component
                else -> component.take(episode.range.last + 1) + component.drop(episode.range.last + 1)
                    .substringBefore(" - ")
            }.replace('_', ' ')
            val normalized = value.uppercase().replace(nonAlphaNumericRegex, " ")
            !multipleSeasonsRegex.containsMatchIn(value) && value.seasonNumbers().size < 2 &&
                    (extraMarkerRegex.containsMatchIn(value) || specialsBlacklist.any { it.containsMatchIn(normalized) })
        }
    }
}
