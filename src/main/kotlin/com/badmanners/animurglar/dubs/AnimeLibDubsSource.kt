package com.badmanners.animurglar.dubs

import com.badmanners.animurglar.app.config.AppConfig
import com.badmanners.animurglar.utils.RequestLogger
import com.badmanners.animurglar.utils.SearchCache
import com.badmanners.animurglar.utils.executeWithProxyFallback
import com.badmanners.animurglar.utils.int
import com.badmanners.animurglar.utils.json
import com.badmanners.animurglar.utils.long
import com.badmanners.animurglar.utils.normalizeUrl
import com.badmanners.animurglar.utils.string
import com.badmanners.animurglar.utils.suspendRunCatching
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.apache.logging.log4j.LogManager


class AnimeLibDubsSource(
    private val client: HttpClient,
    private val directClient: HttpClient,
    private val kodikGateway: KodikGateway,
    private val config: AppConfig,
    private val searchCache: SearchCache,
) : DubsSource {

    override val sourceId = "AnimeLib"

    private val logger = LogManager.getLogger(AnimeLibDubsSource::class.java)

    private fun proxyInfo(): String {
        val proxy = config.proxy
        return if (proxy.enabled && proxy.host.isNotBlank()) "${proxy.type}://${proxy.host}:${proxy.port}" else "direct"
    }

    override suspend fun searchAnime(query: String): List<DubAnimeCandidate> {
        return searchCache.getOrPut(
            key = "animelib:$query",
            ttlDays = config.cache.searchCacheTtlDays,
            serializer = { results ->
                json.encodeToString(ListSerializer(DubAnimeCandidate.serializer()), results)
            },
            deserializer = { data ->
                json.decodeFromString(ListSerializer(DubAnimeCandidate.serializer()), data)
            },
        ) {
            search(query)
        }
    }

    override suspend fun loadDubs(candidate: DubAnimeCandidate): List<DubInfo> {
        require(candidate.sourceId == sourceId) {
            "Unsupported dubs candidate source: ${candidate.sourceId}"
        }

        return searchCache.getOrPut(
            key = "animelib:dubs:${candidate.id}",
            ttlDays = config.cache.searchCacheTtlDays,
            serializer = { results ->
                json.encodeToString(ListSerializer(DubInfo.serializer()), results)
            },
            deserializer = { data ->
                json.decodeFromString(ListSerializer(DubInfo.serializer()), data)
            },
        ) {
            loadDubsInternal(candidate)
        }
    }

    private suspend fun loadDubsInternal(candidate: DubAnimeCandidate): List<DubInfo> {

        val episodes = loadEpisodes(animeSlug = candidate.id)
        if (episodes.isEmpty()) {
            return emptyList()
        }

        val episodesByTeam = mutableMapOf<String, MutableMap<Int, DubEpisode>>()
        val viewsByTeam = mutableMapOf<String, Long>()

        for (episode in episodes) {
            val players = suspendRunCatching {
                loadPlayers(episodeId = episode.id)
            }.getOrElse {
                logger.warn("Failed to load players for episode ${episode.id}: ${it.message}")
                continue
            }

            for (player in players) {
                val teamName = player.teamName?.takeIf(String::isNotBlank) ?: continue
                val sourceUrl = player.sourceUrl?.let(::normalizeUrl) ?: continue

                val teamEpisodes = episodesByTeam.getOrPut(teamName) { mutableMapOf() }
                teamEpisodes.putIfAbsent(
                    episode.number,
                    DubEpisode(
                        episodeNumber = episode.number,
                        sourceUrl = sourceUrl,
                        format = DubEpisodeFormat.HLS
                    )
                )

                player.views?.let { views ->
                    viewsByTeam[teamName] = (viewsByTeam[teamName] ?: 0L) + views
                }
            }
        }

        return episodesByTeam
            .map { (teamName, teamEpisodes) ->
                DubInfo(
                    sourceId = sourceId,
                    teamName = teamName,
                    languageTag = RUSSIAN_LANGUAGE_TAG,
                    views = viewsByTeam[teamName],
                    episodes = teamEpisodes.toSortedMap().values.toList(),
                )
            }
            .filter { it.episodes.isNotEmpty() }
            .sortedWith(
                compareByDescending<DubInfo> { it.episodes.size }
                    .thenByDescending { it.views }
                    .thenBy { it.teamName.lowercase() }
            )
    }

    override suspend fun resolve(episode: DubEpisode): ResolvedDubEpisode {
        val preferredManifest = kodikGateway.resolveManifest(seriaUrl = episode.sourceUrl)

        return ResolvedDubEpisode(
            episodeNumber = episode.episodeNumber,
            resolvedUrl = preferredManifest,
            format = episode.format,
        )
    }

    private suspend fun search(query: String): List<DubAnimeCandidate> {
        val searchUrl = "$HAPI_BASE/anime?fields[]=releaseDate&q=$query"
        val body = client.executeWithProxyFallback(directClient) {
            RequestLogger.logRequest(searchUrl, via = proxyInfo())
            get(searchUrl) {
                hapiHeaders()
            }.bodyAsText()
        }

        val root = json.decodeFromString<JsonObject>(body)
        val data = root["data"] as? JsonArray ?: return emptyList()
        return data.mapNotNull { item ->
            val json = item.jsonObject
            val slugUrl = json.string("slug_url")!!
            DubAnimeCandidate(
                sourceId = sourceId,
                id = slugUrl,
                webUrl = "$ANIMELIB_ORIGIN/ru/anime/$slugUrl",
                name = json.string("name")!!,
                russianName = json.string("rus_name"),
                description = json.string("eng_name"),
                releaseDate = json.string("releaseDate")?.replace("-", "."),
            )
        }
    }

    private suspend fun loadEpisodes(animeSlug: String): List<EpisodeIndex> {
        val episodesUrl = "$HAPI_BASE/episodes?anime_id=$animeSlug"
        val body = client.executeWithProxyFallback(directClient) {
            RequestLogger.logRequest(episodesUrl, via = proxyInfo())
            get(episodesUrl) {
                hapiHeaders()
            }.bodyAsText()
        }

        val root = json.decodeFromString<JsonObject>(body)
        val data = root["data"] as? JsonArray ?: return emptyList()

        return data.mapNotNull { item ->
            val json = item.jsonObject
            val episodeId = json.string("id") ?: json.long("id")?.toString() ?: return@mapNotNull null
            val episodeNumber = json.int("number")
                ?: json.int("item_number")
                ?: return@mapNotNull null
            if (episodeNumber <= 0) {
                return@mapNotNull null
            }

            EpisodeIndex(
                id = episodeId,
                number = episodeNumber,
            )
        }.sortedBy { it.number }
    }

    private suspend fun loadPlayers(episodeId: String): List<PlayerEntry> {
        val playerUrl = "$HAPI_BASE/episodes/$episodeId"
        val body = client.executeWithProxyFallback(directClient) {
            RequestLogger.logRequest(playerUrl, via = proxyInfo())
            get(playerUrl) {
                hapiHeaders()
            }.bodyAsText()
        }

        val root = json.decodeFromString<JsonObject>(body)
        val data = root["data"]?.jsonObject ?: return emptyList()
        val players = data["players"] as? JsonArray ?: return emptyList()

        return players.mapNotNull { player ->
            val json = player.jsonObject
            val translationTypeId = json["translation_type"]?.jsonObject?.int("id") ?: return@mapNotNull null
            if (translationTypeId != 2) {
                return@mapNotNull null
            }

            PlayerEntry(
                teamName = json["team"]?.jsonObject?.string("name"),
                views = json.long("views"),
                sourceUrl = json.string("src"),
            )
        }
    }

    private data class EpisodeIndex(
        val id: String,
        val number: Int,
    )

    private data class PlayerEntry(
        val teamName: String?,
        val views: Long?,
        val sourceUrl: String?,
    )

    private companion object {
        private const val HAPI_BASE = "https://hapi.hentaicdn.org/api"
        private const val ANIMELIB_ORIGIN = "https://animelib.org"
        private const val ANIMELIB_REFERER = "https://animelib.org/"
        private const val RUSSIAN_LANGUAGE_TAG = "rus"

        @Suppress("UastIncorrectHttpHeaderInspection")
        private fun HttpRequestBuilder.hapiHeaders() {
            headers {
                append("origin", ANIMELIB_ORIGIN)
                append("referer", ANIMELIB_REFERER)
                append("site-id", "5")
            }
        }
    }
}