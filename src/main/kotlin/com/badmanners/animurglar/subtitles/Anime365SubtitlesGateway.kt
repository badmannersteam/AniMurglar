package com.badmanners.animurglar.subtitles

import com.badmanners.animurglar.app.config.AppConfig
import com.badmanners.animurglar.utils.BROWSER_USER_AGENT
import com.badmanners.animurglar.utils.SearchCache
import com.badmanners.animurglar.utils.executeWithProxyFallback
import com.badmanners.animurglar.utils.json
import com.badmanners.animurglar.utils.normalizeUrl
import com.badmanners.animurglar.utils.suspendRunCatching
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.apache.logging.log4j.LogManager
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element


class Anime365SubtitlesGateway(
    private val client: HttpClient,
    private val directClient: HttpClient,
    private val config: AppConfig,
    private val searchCache: SearchCache,
) {

    private val logger = LogManager.getLogger(Anime365SubtitlesGateway::class.java)
    private val noRedirectClient = client.config {
        followRedirects = false
    }
    private val noRedirectDirectClient = directClient.config {
        followRedirects = false
    }

    private fun proxyInfo(): String {
        val proxy = config.proxy
        return if (proxy.enabled && proxy.host.isNotBlank()) "${proxy.type}://${proxy.host}:${proxy.port}" else "direct"
    }

    suspend fun loadTeams(shikimoriUrl: String): List<SubtitleTeamInfo> {
        return searchCache.getOrPut(
            key = "anime365:$shikimoriUrl",
            ttlDays = config.cache.searchCacheTtlDays,
            serializer = { results ->
                json.encodeToString(ListSerializer(SubtitleTeamInfo.serializer()), results)
            },
            deserializer = { data ->
                json.decodeFromString(ListSerializer(SubtitleTeamInfo.serializer()), data)
            },
        ) {
            loadTeamsInternal(shikimoriUrl)
        }
    }

    private suspend fun loadTeamsInternal(shikimoriUrl: String): List<SubtitleTeamInfo> {
        val catalogPage = loadCatalogPage(shikimoriUrl)
        val episodePages = parseEpisodePages(catalogPage)
        val episodesByTeam = mutableMapOf<String, MutableMap<Int, SubtitleEpisode>>()

        for (episodePage in episodePages) {
            val subtitles = suspendRunCatching {
                loadEpisodeSubtitles(episodePage)
            }.getOrElse {
                logger.warn("Failed to load Anime365 subtitles for episode ${episodePage.episodeNumber}: ${it.message}")
                continue
            }

            for (subtitle in subtitles) {
                episodesByTeam.getOrPut(subtitle.teamName) { mutableMapOf() }
                    .putIfAbsent(episodePage.episodeNumber, subtitle.episode)
            }
        }

        return episodesByTeam.map { (teamName, episodes) ->
            SubtitleTeamInfo(
                sourceId = ANIME365_SUBTITLES_SOURCE_ID,
                teamName = teamName,
                languageTag = RUSSIAN_SUBTITLES_LANGUAGE_TAG,
                episodes = episodes.toSortedMap().values.toList(),
            )
        }
            .filter { it.episodes.isNotEmpty() }
            .sortedWith(compareByDescending<SubtitleTeamInfo> { it.episodes.size }.thenBy { it.teamName.lowercase() })
    }

    suspend fun resolveEpisode(episode: SubtitleEpisode): ResolvedSubtitleEpisode? {
        val embedUrl = "$ANIME365_ORIGIN/translations/embed/${episode.translationId}"
        val html = client.executeWithProxyFallback(directClient) {
            get(embedUrl) {
                anime365Headers(referer = episode.pageUrl)
            }.bodyAsText()
        }
        val video = Jsoup.parse(html, embedUrl).selectFirst("video#main-video") ?: return null
        val subtitlesUrl = video.attr("data-subtitles")
            .let(::absoluteAnime365Url)
            ?: return null
        val fonts = parseFonts(video.attr("data-fonts"))

        return ResolvedSubtitleEpisode(
            episode = episode,
            subtitlesUrl = subtitlesUrl,
            fonts = fonts,
            embedUrl = embedUrl,
        )
    }

    private suspend fun loadCatalogPage(shikimoriUrl: String): Document {
        val html = client.executeWithProxyFallback(directClient) {
            get("$ANIME365_ORIGIN/catalog/search") {
                url {
                    parameters.append("q", shikimoriUrl)
                    parameters.append("dynpage", "1")
                }
                anime365Headers()
            }.bodyAsText()
        }

        logger.debug("Anime365 catalog response via {} ({} chars)", proxyInfo(), html.length)
        return Jsoup.parse(html, ANIME365_ORIGIN)
    }

    private suspend fun loadEpisodeSubtitles(episodePage: Anime365EpisodePage): List<Anime365EpisodeSubtitle> {
        val subtitlesPageUrl = loadEpisodeSubtitlesPageUrl(episodePage) ?: return emptyList()
        val html = client.executeWithProxyFallback(directClient) {
            get(subtitlesPageUrl) {
                anime365Headers(referer = episodePage.pageUrl)
            }.bodyAsText()
        }

        return parseEpisodeSubtitles(Jsoup.parse(html, subtitlesPageUrl), episodePage)
    }

    private suspend fun loadEpisodeSubtitlesPageUrl(episodePage: Anime365EpisodePage): String? {
        val noRedirect = if (proxyEnabled()) noRedirectClient else noRedirectDirectClient
        val response = noRedirect.get(episodePage.russianSubtitlesUrl) {
            url {
                parameters.append("dynpage", "1")
            }
            anime365Headers(referer = episodePage.pageUrl)
        }
        val redirectUrl = response.headers[HttpHeaders.Location]?.let(::absoluteAnime365Url)
        response.bodyAsText()

        return when {
            redirectUrl != null && SUBTITLE_ID_REGEX.containsMatchIn(redirectUrl) -> redirectUrl
            else -> null
        }
    }

    private fun proxyEnabled(): Boolean = config.proxy.enabled && config.proxy.host.isNotBlank()

    private fun parseEpisodePages(document: Document) = document.select("a.m-episode-item")
        .mapNotNull { link ->
            val title = link.ownText().ifBlank { link.text().removePrefix("play_circle_filled") }
            val episodeNumber = when {
                title.equals("Фильм", ignoreCase = true) -> 1
                else -> EPISODE_TITLE_REGEX.find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()
            } ?: return@mapNotNull null
            val pageUrl = link.absoluteAnime365Url("href") ?: return@mapNotNull null

            Anime365EpisodePage(episodeNumber, pageUrl)
        }
        .distinctBy { it.episodeNumber }
        .sortedBy { it.episodeNumber }

    private fun parseEpisodeSubtitles(
        document: Document,
        episodePage: Anime365EpisodePage,
    ) = document.select(".m-select-translation-variant a[href*=/russkie-subtitry-]")
        .mapNotNull { link ->
            val teamName = link.text().replace(Regex("\\s+"), " ").trim()
            val translationId = SUBTITLE_ID_REGEX.find(link.attr("href"))?.groupValues?.getOrNull(1)
            val pageUrl = link.absoluteAnime365Url("href")
            if (teamName.isEmpty() || translationId == null || pageUrl == null) {
                return@mapNotNull null
            }

            Anime365EpisodeSubtitle(
                teamName = teamName,
                episode = SubtitleEpisode(
                    episodeNumber = episodePage.episodeNumber,
                    translationId = translationId,
                    pageUrl = pageUrl,
                )
            )
        }

    private fun Element.absoluteAnime365Url(attribute: String): String? {
        val rawUrl = absUrl(attribute).ifEmpty { attr(attribute) }
        return this@Anime365SubtitlesGateway.absoluteAnime365Url(rawUrl)
    }

    private fun absoluteAnime365Url(rawUrl: String): String? {
        return when {
            rawUrl.startsWith("/") -> "$ANIME365_ORIGIN$rawUrl"
            else -> normalizeUrl(rawUrl)
        }
    }

    private fun parseFonts(rawFonts: String): List<SubtitleFont> {
        if (rawFonts.isEmpty()) {
            return emptyList()
        }

        return (json.parseToJsonElement(rawFonts) as? JsonArray)
            ?.mapNotNull { element ->
                val fontUrl = element.jsonPrimitive.contentOrNull
                    ?.let(::absoluteAnime365Url)
                    ?: return@mapNotNull null
                val fileName = fontUrl.substringBefore('?').substringAfterLast('/').takeIf { it.isNotEmpty() }
                    ?: return@mapNotNull null
                when (fileName.substringAfterLast('.', missingDelimiterValue = "").lowercase()) {
                    "ttf", "otf" -> SubtitleFont(url = fontUrl, fileName = fileName)
                    else -> null
                }
            }
            .orEmpty()
            .distinctBy { it.fileName }
    }

    private fun HttpRequestBuilder.anime365Headers(referer: String = ANIME365_ORIGIN) {
        headers {
            append("referer", referer)
            append("user-agent", BROWSER_USER_AGENT)
            append("x-requested-with", "XMLHttpRequest")
        }
    }

    private data class Anime365EpisodePage(
        val episodeNumber: Int,
        val pageUrl: String,
    ) {
        val russianSubtitlesUrl = "${pageUrl.substringBefore('?').trimEnd('/')}/russkie-subtitry"
    }

    private data class Anime365EpisodeSubtitle(
        val teamName: String,
        val episode: SubtitleEpisode,
    )

    companion object {
        private const val ANIME365_ORIGIN = "https://smotret-anime.org"
        private val EPISODE_TITLE_REGEX = Regex("(\\d+)\\s*серия", RegexOption.IGNORE_CASE)
        private val SUBTITLE_ID_REGEX = Regex("russkie-subtitry-(\\d+)")
    }
}