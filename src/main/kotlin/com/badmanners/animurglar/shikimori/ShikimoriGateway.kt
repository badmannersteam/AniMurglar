package com.badmanners.animurglar.shikimori

import com.badmanners.animurglar.app.config.AppConfig
import com.badmanners.animurglar.utils.RequestLogger
import com.badmanners.animurglar.utils.SearchCache
import com.badmanners.animurglar.utils.executeWithProxyFallback
import com.badmanners.animurglar.utils.json
import com.badmanners.animurglar.utils.int
import com.badmanners.animurglar.utils.string
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.apache.logging.log4j.LogManager


class ShikimoriGateway(private val client: HttpClient, private val directClient: HttpClient, private val config: AppConfig, private val searchCache: SearchCache) {

    private val logger = LogManager.getLogger(ShikimoriGateway::class.java)

    private fun proxyInfo(): String {
        val proxy = config.proxy
        return if (proxy.enabled && proxy.host.isNotBlank()) "${proxy.type}://${proxy.host}:${proxy.port}" else "direct"
    }

    suspend fun search(query: String, limit: Int = DEFAULT_LIMIT): List<ShikimoriAnime> {
        return searchCache.getOrPut(
            key = "shikimori:$query:$limit",
            ttlDays = config.cache.searchCacheTtlDays,
            serializer = { results ->
                json.encodeToString(kotlinx.serialization.builtins.ListSerializer(ShikimoriAnime.serializer()), results)
            },
            deserializer = { data ->
                json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(ShikimoriAnime.serializer()), data)
            },
        ) {
            searchInternal(query, limit)
        }
    }

    private suspend fun searchInternal(query: String, limit: Int): List<ShikimoriAnime> {
        val payload = buildJsonObject {
            put("query", GRAPHQL_QUERY)
            putJsonObject("variables") {
                put("search", query)
                put("limit", limit.coerceAtLeast(1))
            }
        }

        val body = client.executeWithProxyFallback(directClient) {
            RequestLogger.logRequest(API_URL, via = proxyInfo())
            post(API_URL) {
                header(HttpHeaders.ContentType, "application/json")
                header(HttpHeaders.Accept, "application/json")
                header(HttpHeaders.Origin, ORIGIN)
                setBody(payload.toString())
            }.bodyAsText()
        }

        val root = json.decodeFromString<JsonObject>(body)
        val data = root["data"]?.jsonObject ?: return emptyList()
        val animes = data["animes"] as? JsonArray ?: return emptyList()
        return animes.mapNotNull { item ->
            val anime = item.jsonObject
            ShikimoriAnime(
                id = anime.string("id") ?: return@mapNotNull null,
                name = anime.string("name")?.trim()?.takeIf(String::isNotBlank) ?: return@mapNotNull null,
                russian = anime.string("russian")?.trim()?.takeIf(String::isNotBlank) ?: return@mapNotNull null,
                licenseNameRu = anime.string("licenseNameRu")?.trim()?.takeIf(String::isNotBlank),
                english = anime.string("english")?.trim()?.takeIf(String::isNotBlank),
                japanese = anime.string("japanese")?.trim()?.takeIf(String::isNotBlank),
                episodes = anime.int("episodes")?.takeIf { it > 0 },
                episodesAired = when {
                    anime.string("status") == "released" -> anime.int("episodes")?.takeIf { it > 0 }
                    else -> anime.int("episodesAired")
                },
                airedOnDate = anime["airedOn"]?.jsonObject?.string("date")?.replace('-', '.'),
                releasedOnDate = anime["releasedOn"]?.jsonObject?.string("date")?.replace('-', '.'),
                synonyms = (anime["synonyms"] as? JsonArray).orEmpty().mapNotNull { synonym ->
                    synonym.jsonPrimitive.contentOrNull?.trim()?.takeIf(String::isNotBlank)
                },
                url = anime.string("url") ?: return@mapNotNull null,
                isMovie = anime.string("kind") == "movie",
            )
        }
    }

    private companion object {
        private const val API_URL = "https://shikimori.io/api/graphql"
        private const val ORIGIN = "https://shikimori.io"
        private const val DEFAULT_LIMIT = 10

        private const val GRAPHQL_QUERY =
            $$"query($search: String!, $limit: Int!) { animes(search: $search, limit: $limit) " +
                "{ id name russian licenseNameRu english japanese synonyms episodes episodesAired " +
                "airedOn { date } releasedOn { date } url status kind } }"
    }
}
