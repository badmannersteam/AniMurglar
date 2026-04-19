package com.badmanners.animurglar.downloader


data class EpisodeMapping(
    val torrentStartEpisode: Int,
    val dubStartEpisode: Int,
) {
    val offset: Int
        get() = dubStartEpisode - torrentStartEpisode

    fun dubEpisodeNumber(torrentEpisodeNumber: Int): Int = torrentEpisodeNumber + offset
}

fun suggestEpisodeMapping(selectedEpisodes: Set<Int>, dubStartOverride: Int?) =
    EpisodeMapping(selectedEpisodes.min(), dubStartOverride ?: 1)