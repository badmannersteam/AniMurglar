package com.badmanners.animurglar.shikimori


data class ShikimoriAnime(
    val id: String,
    val name: String,
    val russian: String,
    val licenseNameRu: String? = null,
    val english: String? = null,
    val japanese: String? = null,
    val episodes: Int? = null,
    val episodesAired: Int? = null,
    val airedOnDate: String? = null,
    val releasedOnDate: String? = null,
    val synonyms: List<String>,
    val url: String,
    val isMovie: Boolean
) {
    val nyaaQueries = buildQueries(
        name,
        english,
        japanese,
        *synonyms.toTypedArray(),
    )

    val dubsQueries = buildQueries(
        russian,
        licenseNameRu,
        name,
        english,
        japanese,
        *synonyms.toTypedArray(),
    )

    private fun buildQueries(vararg names: String?) = names.filterNotNull()
        .map { it.replace(Regex("[^\\p{L}\\p{N}\\s]"), " ").lowercase().replace("\\s+".toRegex(), " ") }
        .filter { it.length > 3 }
}
