package com.badmanners.animurglar.ui.dubs

import com.badmanners.animurglar.dubs.DubAnimeCandidate
import kotlin.math.abs


class DubsCandidateSimilarityPicker {

    fun sortCandidatesBySimilarity(
        queries: List<String>,
        candidates: List<DubAnimeCandidate>,
    ): List<DubAnimeCandidate> {
        if (candidates.isEmpty()) {
            return emptyList()
        }

        val normalizedQueries = queries
            .map(::normalizeForSimilarity)
            .filter(String::isNotEmpty)
        if (normalizedQueries.isEmpty()) {
            return candidates
        }

        return candidates.sortedWith(compareByDescending { similarityScore(it, normalizedQueries) })
    }

    private fun similarityScore(candidate: DubAnimeCandidate, normalizedQueries: List<String>): Int {
        val normalizedCandidateTitles = listOfNotNull(candidate.russianName ?: candidate.name)
            .map(::normalizeForSimilarity)
            .filter(String::isNotEmpty)

        if (normalizedCandidateTitles.isEmpty()) {
            return Int.MIN_VALUE
        }

        return normalizedCandidateTitles.maxOf { candidateTitle ->
            normalizedQueries.maxOf { query ->
                scoreNormalizedPair(query = query, candidateTitle = candidateTitle)
            }
        }
    }

    private fun scoreNormalizedPair(query: String, candidateTitle: String): Int {
        if (query == candidateTitle) {
            return 1_000_000
        }

        val containsBonus = when {
            candidateTitle.contains(query) || query.contains(candidateTitle) -> 150_000
            else -> 0
        }

        val queryTokens = query.split(' ')
        val candidateTokenSet = candidateTitle.split(' ').toSet()
        val matchingTokenCount = queryTokens.count { token -> token in candidateTokenSet }
        val tokenScore = matchingTokenCount * 100_000 / queryTokens.size.coerceAtLeast(1)

        val commonPrefixLength = query.commonPrefixWith(candidateTitle).length
        val lengthPenalty = abs(query.length - candidateTitle.length)

        return containsBonus + tokenScore + commonPrefixLength - lengthPenalty
    }

    private fun normalizeForSimilarity(value: String): String = value
        .lowercase()
        .replace(NON_ALPHANUMERIC_CHARS_REGEX, " ")
        .trim()
        .replace(MULTIPLE_SPACES_REGEX, " ")

    private companion object {
        private val NON_ALPHANUMERIC_CHARS_REGEX = Regex("[^\\p{L}\\p{N}]+")
        private val MULTIPLE_SPACES_REGEX = Regex("\\s+")
    }
}