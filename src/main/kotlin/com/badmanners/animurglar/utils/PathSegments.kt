package com.badmanners.animurglar.utils

private val FORBIDDEN_SEGMENT_CHARS = Regex("[\\x00-\\x1F]+")

private val REPLACEMENTS = mapOf(
    '"' to '\'',
    '<' to '[',
    '>' to ']',
    ':' to '-',
    '*' to '+',
    '?' to '_',
    '/' to '_',
    '\\' to '_',
    '|' to '_',
)

fun normalizePathSegment(value: String): String {
    val value = value.replace(FORBIDDEN_SEGMENT_CHARS, " ").trim()

    val builder = StringBuilder(value.length)
    for (char in value.toCharArray())
        builder.append(REPLACEMENTS[char] ?: char)

    while (builder.isNotEmpty() && builder[0] == '.')
        builder.deleteCharAt(0)
    while (builder.isNotEmpty() && builder[builder.lastIndex] == '.')
        builder.deleteCharAt(builder.lastIndex)

    return builder.toString()
        .replace("_+".toRegex(), "_")
        .also { require(it.isNotBlank()) { "Path segment is empty after normalization." } }
}

fun episodeTag(episodeNumber: Int): String {
    require(episodeNumber > 0) { "Episode number must be positive: $episodeNumber" }
    return episodeNumber.toString().padStart(3, '0')
}
