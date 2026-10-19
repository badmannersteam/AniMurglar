package com.badmanners.animurglar.media

import kotlin.text.RegexOption.IGNORE_CASE

object EpisodeFileParser {
    private val extractEpisodePatterns = listOf(
        Regex("""(?i)\bs\d{1,2}\s*[-_. ]?e(\d{1,3})(?:v\d+)?\b"""),
        Regex("""(?i)\b(?:EP|E|EPISODE)\s*[-_. ]?0*(\d{1,3})(?:v\d+)?\b"""),
        Regex("""(?i)第\s*0*(\d{1,3})\s*[話话]\b"""),
        Regex("""(?i)\[(\d{1,3})](?=[^0-9]|$)"""),
        Regex("""(?i)-\s*0*(\d{1,3})(?:v\d+)?\b"""),
        Regex("""(?i)(?:^|[\s\[(._-])0*(\d{1,3})(?:v\d+)?(?=$|[])\s._-]|\.m(?:kv|p4)\b)"""),
    )
    private val technicalNumbersRegex = Regex(
        """(?i)\b(?:\d{3,5}\s*[x×]\s*\d{3,5}|\d{3,4}p|[xh][ .-]?26[45]|(?:8|10|12)[ .-]*bits?|\d{2,3}(?:[.,]\d+)?\s*fps|(?:19|20)\d{2}|(?:CRC(?:32)?[ :=.-]*)?[0-9a-f]{8})\b"""
    )
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
        Regex("""\bSAMPLES?(?:\d+[A-Z]?)?(?:V\d+)?\b""", IGNORE_CASE),
        Regex("""\b(?:CREDITLESS|NONCREDIT(?:ED|LESS)?)\b""", IGNORE_CASE),
        Regex("""\bTRAILERS?\b""", IGNORE_CASE),
        Regex("""\bPV\d*[A-Z]?\b""", IGNORE_CASE),
        Regex("""\bCM\d*[A-Z]?\b""", IGNORE_CASE),
        Regex("""\bMENU(?:\d+|(?:\s+NEW)?)\b""", IGNORE_CASE),
        Regex("""\b(?:TOKUTEN|OUTTAKES?|SHORTS?|OMAKE|BONUS)\b""", IGNORE_CASE),
    )

    fun String.extractEpisodeNumber(): Int? {
        val normalized = replace('_', ' ').replace(technicalNumbersRegex, " ")
        return extractEpisodePatterns.asSequence()
            .flatMap { pattern -> pattern.findAll(normalized) }
            .map { match -> match.groupValues[1].toInt() }
            .firstOrNull { number -> number in 1..999 }
    }

    fun String.containsSpecialsMarker(): Boolean {
        val normalized = uppercase().replace(nonAlphaNumericRegex, " ")
        return extraMarkerRegex.containsMatchIn(replace('_', ' ')) ||
            specialsBlacklist.any { regex -> regex.containsMatchIn(normalized) }
    }
}
