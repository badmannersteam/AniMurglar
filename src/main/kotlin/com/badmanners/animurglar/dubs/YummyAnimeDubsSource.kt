package com.badmanners.animurglar.dubs

import com.badmanners.animurglar.utils.BROWSER_USER_AGENT
import com.badmanners.animurglar.utils.int
import com.badmanners.animurglar.utils.json
import com.badmanners.animurglar.utils.long
import com.badmanners.animurglar.utils.normalizeUrl
import com.badmanners.animurglar.utils.string
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.statement.bodyAsText
import io.ktor.http.URLBuilder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject


class YummyAnimeDubsSource(
    private val client: HttpClient,
    private val kodikGateway: KodikGateway,
) : DubsSource {

    override val sourceId = "YummyAnime"

    override suspend fun searchAnime(query: String): List<DubAnimeCandidate> = buildList {
        var page = 0
        do {
            val results = loadSearchPage(query = query, page = page++)
            addAll(parseSearchResults(results))
        } while (results.size == YUMMY_SEARCH_LIMIT)
    }.distinctBy(DubAnimeCandidate::id)

    override suspend fun loadDubs(candidate: DubAnimeCandidate): List<DubInfo> {
        require(candidate.sourceId == sourceId) {
            "Unsupported dubs candidate source: ${candidate.sourceId}"
        }

        val videos = loadApi(path = "anime/${candidate.id}/videos", referer = candidate.webUrl)

        return videos.mapNotNull { parseDubVideo(it.jsonObject) }
            .groupBy(DubVideo::teamName)
            .map { (teamName, teamVideos) ->
                val episodes = teamVideos.distinctBy { it.episode.episodeNumber }
                    .sortedBy { it.episode.episodeNumber }

                DubInfo(
                    sourceId = sourceId,
                    teamName = teamName,
                    languageTag = "rus",
                    views = episodes.sumOf(DubVideo::views),
                    episodes = episodes.map(DubVideo::episode),
                )
            }
            .sortedWith(compareByDescending<DubInfo> { it.episodes.size }.thenBy { it.teamName.lowercase() })
    }

    override suspend fun resolve(episode: DubEpisode) = ResolvedDubEpisode(
        episodeNumber = episode.episodeNumber,
        resolvedUrl = kodikGateway.resolveManifest(seriaUrl = episode.sourceUrl),
        format = episode.format,
    )

    private suspend fun loadSearchPage(query: String, page: Int) = loadApi(
        path = "search",
        referer = URLBuilder("$YUMMY_ORIGIN/search").apply {
            parameters.append("word", query)
        }.buildString(),
    ) {
        url {
            parameters.append("q", query)
            parameters.append("limit", YUMMY_SEARCH_LIMIT.toString())
            parameters.append("offset", (page * YUMMY_SEARCH_LIMIT).toString())
        }
    }

    private suspend fun loadApi(
        path: String,
        referer: String,
        configure: HttpRequestBuilder.() -> Unit = {},
    ): JsonArray {
        val body = client.get("$YUMMY_ORIGIN/api/$path") {
            configure()
            headers {
                append("referer", referer)
                append("user-agent", BROWSER_USER_AGENT)
                append("accept", "application/json")
                append("Lang", "ru")
                append("Vary", "json")
                append("X-Application", YUMMY_APPLICATION)
            }
        }.bodyAsText()

        return json.decodeFromString<JsonObject>(body).getValue("response").jsonArray
    }

    private fun parseSearchResults(document: JsonArray) = document.map { item ->
        val anime = item.jsonObject
        val title = requireNotNull(anime.string("title"))

        DubAnimeCandidate(
            sourceId = sourceId,
            id = requireNotNull(anime.string("anime_id")),
            webUrl = "$YUMMY_ORIGIN/catalog/item/${requireNotNull(anime.string("anime_url"))}",
            name = title,
            russianName = title,
            description = null,
            releaseDate = when (val year = anime.int("year")) {
                null, 0 -> null
                else -> year.toString()
            },
        )
    }

    private fun parseDubVideo(video: JsonObject): DubVideo? {
        val data = video.getValue("data").jsonObject
        if (data.int("player_id") != YUMMY_KODIK_PLAYER_ID) return null

        val dubbing = requireNotNull(data.string("dubbing"))
        if (!dubbing.startsWith(YUMMY_DUBBING_PREFIX)) return null

        val episodeNumber = requireNotNull(video.string("number")).toIntOrNull() ?: return null
        if (episodeNumber <= 0) return null

        return DubVideo(
            teamName = dubbing.removePrefix(YUMMY_DUBBING_PREFIX),
            episode = DubEpisode(
                episodeNumber = episodeNumber,
                sourceUrl = requireNotNull(normalizeUrl(requireNotNull(video.string("iframe_url")))),
                format = DubEpisodeFormat.HLS,
            ),
            views = requireNotNull(video.long("views")),
        )
    }

    private data class DubVideo(
        val teamName: String,
        val episode: DubEpisode,
        val views: Long,
    )

    private companion object {
        private const val YUMMY_ORIGIN = "https://old.yummyani.me"
        private const val YUMMY_SEARCH_LIMIT = 20
        private const val YUMMY_APPLICATION = "wawegr8j13it4rdw"
        private const val YUMMY_KODIK_PLAYER_ID = 4
        private const val YUMMY_DUBBING_PREFIX = "Озвучка "
    }
}