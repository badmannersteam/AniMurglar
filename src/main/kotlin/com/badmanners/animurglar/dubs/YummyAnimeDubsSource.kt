package com.badmanners.animurglar.dubs

import com.badmanners.animurglar.app.config.AppConfig
import com.badmanners.animurglar.dubs.KodikGateway.Companion.KODIK_DEFAULT_QUALITY
import com.badmanners.animurglar.dubs.KodikGateway.Companion.KODIK_ORIGIN
import com.badmanners.animurglar.dubs.KodikGateway.Companion.KODIK_REFERER
import com.badmanners.animurglar.utils.BROWSER_USER_AGENT
import com.badmanners.animurglar.utils.RequestLogger
import com.badmanners.animurglar.utils.SearchCache
import com.badmanners.animurglar.utils.executeWithProxyFallback
import com.badmanners.animurglar.utils.json
import com.badmanners.animurglar.utils.normalizeUrl
import com.badmanners.animurglar.utils.string
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import org.apache.logging.log4j.LogManager
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element


class YummyAnimeDubsSource(
    private val client: HttpClient,
    private val directClient: HttpClient,
    private val kodikGateway: KodikGateway,
    private val config: AppConfig,
    private val searchCache: SearchCache,
) : DubsSource {

    private val logger = LogManager.getLogger(YummyAnimeDubsSource::class.java)

    private fun proxyInfo(): String {
        val proxy = config.proxy
        return if (proxy.enabled && proxy.host.isNotBlank()) "${proxy.type}://${proxy.host}:${proxy.port}" else "direct"
    }

    override val sourceId = "YummyAnime"

    override suspend fun searchAnime(query: String): List<DubAnimeCandidate> {
        return searchCache.getOrPut(
            key = "yummy:$query",
            ttlDays = config.cache.searchCacheTtlDays,
            serializer = { results ->
                json.encodeToString(ListSerializer(DubAnimeCandidate.serializer()), results)
            },
            deserializer = { data ->
                json.decodeFromString(ListSerializer(DubAnimeCandidate.serializer()), data)
            },
        ) {
            searchAnimeInternal(query)
        }
    }

    private suspend fun searchAnimeInternal(query: String): List<DubAnimeCandidate> {
        val firstPage = loadSearchPage(query = query, page = 1)
        val totalPages = parseTotalPages(firstPage)

        return buildList {
            addAll(parseSearchResults(firstPage))
            for (page in 2..totalPages) {
                addAll(parseSearchResults(loadSearchPage(query = query, page = page)))
            }
        }.distinctBy(DubAnimeCandidate::id)
    }

    override suspend fun loadDubs(candidate: DubAnimeCandidate): List<DubInfo> {
        require(candidate.sourceId == sourceId) {
            "Unsupported dubs candidate source: ${candidate.sourceId}"
        }

        return searchCache.getOrPut(
            key = "yummy:dubs:${candidate.id}",
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
        val initialPlayerUrl = loadInitialPlayerUrl(candidate.id)
        val initialPlayerDocument = loadPlayer(initialPlayerUrl, YUMMY_REFERER)

        return initialPlayerDocument.select(".serial-translations-box option")
            .mapNotNull(::parseTranslationOption)
            .mapNotNull { translation -> buildDubInfo(translation) }
            .filter { it.episodes.isNotEmpty() }
            .sortedWith(compareByDescending<DubInfo> { it.episodes.size }.thenBy { it.teamName.lowercase() })
    }

    override suspend fun resolve(episode: DubEpisode) = ResolvedDubEpisode(
        episodeNumber = episode.episodeNumber,
        resolvedUrl = kodikGateway.resolveManifest(seriaUrl = episode.sourceUrl),
        format = episode.format,
    )

    private suspend fun loadSearchPage(query: String, page: Int): Document {
        val searchUrl = "$YUMMY_SEARCH_URL?do=search&subaction=search&search_start=$page&full_search=0&story=$query"
        val html = client.executeWithProxyFallback(directClient) {
            RequestLogger.logRequest(searchUrl, via = proxyInfo())
            get(searchUrl) {
                headers {
                    append("referer", YUMMY_REFERER)
                    append("user-agent", BROWSER_USER_AGENT)
                }
            }.bodyAsText()
        }

        return Jsoup.parse(html, YUMMY_ORIGIN)
    }

    private fun parseTotalPages(document: Document) = document.select("#pagination a, #pagination span")
        .mapNotNull { it.text().toIntOrNull() }
        .maxOrNull()
        ?: 1

    private fun parseSearchResults(document: Document) = document.select("div.movie-item").mapNotNull { item ->
        val link = item.selectFirst("a.movie-item__link") ?: return@mapNotNull null
        val webUrl = normalizeUrl(link.absUrl("href").ifBlank { link.attr("href") }) ?: return@mapNotNull null
        val id = ANIME_ID_REGEX.find(webUrl)?.groupValues?.getOrNull(1) ?: return@mapNotNull null
        val titleElement = item.selectFirst("div.movie-item__title") ?: return@mapNotNull null
        val title = titleElement.attr("title").ifBlank { titleElement.text() }
        if (title.isBlank()) {
            return@mapNotNull null
        }

        DubAnimeCandidate(
            sourceId = sourceId,
            id = id,
            webUrl = webUrl,
            name = title,
            russianName = title,
            description = item.selectFirst("div.movie-item__label")?.text(),
            releaseDate = item.selectFirst("div.movie-item__meta span")
                ?.text()
                ?.removePrefix("(")
                ?.removeSuffix(")"),
        )
    }

    private suspend fun loadInitialPlayerUrl(animeId: String): String {
        val ajaxUrl = "$YUMMY_AJAX_URL?mod=kodik-player&url=1&action=iframe&id=$animeId"
        val body = client.executeWithProxyFallback(directClient) {
            RequestLogger.logRequest(ajaxUrl, via = proxyInfo())
            get(ajaxUrl) {
                headers {
                    append("referer", YUMMY_AJAX_REFERER)
                    append("user-agent", BROWSER_USER_AGENT)
                }
            }.bodyAsText()
        }

        val root = json.decodeFromString<JsonObject>(body)
        return root.string("data")?.let(::normalizeUrl)
            ?: error("YummyAnime iframe response does not contain Kodik player URL.")
    }

    private suspend fun loadPlayer(url: String, referer: String): Document {
        val html = client.executeWithProxyFallback(directClient) {
            RequestLogger.logRequest(url, via = proxyInfo())
            get(url) {
                headers {
                    append("referer", referer)
                    append("user-agent", BROWSER_USER_AGENT)
                }
            }.bodyAsText()
        }.replace("<script id=\"wappalyzerEnvDetection\"/>", "")

        return Jsoup.parse(html, url)
    }

    private fun parseTranslationOption(option: Element): TranslationOption? {
        val title = option.attr("data-title")
        val mediaId = option.attr("data-media-id")
        val mediaHash = option.attr("data-media-hash")
        val mediaType = option.attr("data-media-type")
        val type = option.attr("data-translation-type")
        if (type != "voice")
            return null

        return TranslationOption(
            type = type,
            title = title,
            mediaId = mediaId,
            mediaHash = mediaHash,
            mediaType = mediaType
        )
    }

    private suspend fun buildDubInfo(translation: TranslationOption): DubInfo? {
        val episodes = parseDubEpisodes(loadPlayer(translation.playerUrl, KODIK_REFERER))
        if (episodes.isEmpty()) {
            return null
        }

        return DubInfo(
            sourceId = sourceId,
            teamName = translation.title,
            languageTag = "rus",
            views = null,
            episodes = episodes
        )
    }

    private fun parseDubEpisodes(document: Document) =
        document.select(".serial-series-box option").mapNotNull { option ->
            if (option.attr("data-other-translation").toBoolean()) {
                return@mapNotNull null
            }

            val episodeNumber = option.attr("value").toIntOrNull() ?: return@mapNotNull null
            val episodeId = option.attr("data-id")
            val episodeHash = option.attr("data-hash")
            if (episodeId.isBlank() || episodeHash.isBlank()) {
                return@mapNotNull null
            }

            DubEpisode(
                episodeNumber = episodeNumber,
                sourceUrl = "$KODIK_ORIGIN/seria/$episodeId/$episodeHash/$KODIK_DEFAULT_QUALITY",
                format = DubEpisodeFormat.HLS,
            )
        }

    private data class TranslationOption(
        val type: String,
        val title: String,
        val mediaId: String,
        val mediaHash: String,
        val mediaType: String
    ) {
        val playerUrl = "${KODIK_ORIGIN}/$mediaType/$mediaId/$mediaHash/$KODIK_DEFAULT_QUALITY"
    }

    private companion object {
        private const val YUMMY_ORIGIN = "https://yummyanime.tv"
        private const val YUMMY_REFERER = "$YUMMY_ORIGIN/"
        private const val YUMMY_SEARCH_URL = "$YUMMY_ORIGIN/index.php"
        private const val YUMMY_AJAX_URL = "$YUMMY_ORIGIN/engine/ajax/controller.php"
        private const val YUMMY_AJAX_REFERER = "$YUMMY_ORIGIN/engine/ajax/controller.php?mod=xfp"

        private val ANIME_ID_REGEX = Regex("""/(\d+)[^/]*\.html(?:$|\?)""")
    }
}