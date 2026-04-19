package com.badmanners.animurglar.dubs

import com.badmanners.animurglar.ui.dubs.DubsCandidateSimilarityPicker
import com.badmanners.animurglar.utils.suspendRunCatching
import org.apache.logging.log4j.LogManager


class DubsGateway(private val sources: List<DubsSource>) {

    private val logger = LogManager.getLogger(DubsGateway::class.java)

    private val candidateSimilarityPicker = DubsCandidateSimilarityPicker()

    private val sourcesById = sources.associateBy { it.sourceId }

    suspend fun searchAnime(queries: List<String>): List<DubAnimeCandidate> = buildList {
        for (source in sources) {
            for (query in queries) {
                suspendRunCatching {
                    addAll(source.searchAnime(query))
                }.getOrElse { throwable ->
                    logger.warn("Dubs source ${source.sourceId} failed for query '$query'", throwable)
                }
            }
        }
    }.distinctBy { it.sourceId + it.id }.let { candidateSimilarityPicker.sortCandidatesBySimilarity(queries, it) }

    suspend fun loadDubs(candidate: DubAnimeCandidate): List<DubInfo> {
        val source = requireNotNull(sourcesById[candidate.sourceId]) { "Unknown dubs source: ${candidate.sourceId}" }
        return source.loadDubs(candidate).sortedWith(
            compareByDescending<DubInfo> { it.episodes.size }
                .thenByDescending { it.views }
                .thenBy { it.teamName.lowercase() }
        )
    }

    suspend fun resolve(sourceId: String, episode: DubEpisode): ResolvedDubEpisode {
        val source = requireNotNull(sourcesById[sourceId]) { "Unknown dubs source: $sourceId" }
        return source.resolve(episode)
    }
}