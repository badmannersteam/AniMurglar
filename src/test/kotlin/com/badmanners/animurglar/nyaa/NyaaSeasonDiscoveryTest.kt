package com.badmanners.animurglar.nyaa

import com.badmanners.animurglar.media.RawSeason
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.URLBuilder
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NyaaSeasonDiscoveryTest {
    @Test
    fun inspectMultiSeasonSearchResults() = runBlocking {
        HttpClient(OkHttp) {
            expectSuccess = true
            install(HttpTimeout) { requestTimeoutMillis = 30_000 }
        }.use { client ->
            val gateway = NyaaGateway(client)
            val reportDirectory = Path.of("build", "reports", "nyaa-seasons").createDirectories()
            val links = linkedSetOf<String>()
            for (query in listOf("s1 s2", "all seasons")) {
                val url = URLBuilder("https://nyaa.si/").apply {
                    parameters.append("f", "0")
                    parameters.append("c", "1_2")
                    parameters.append("q", query)
                    parameters.append("s", "seeders")
                    parameters.append("o", "desc")
                }.buildString()
                val html = client.get(url).bodyAsText()
                reportDirectory.resolve("search-${query.replace(' ', '-')}.html").writeText(html)
                val document = Jsoup.parse(html, url)
                val results = document.select("table.torrent-list tbody tr").mapNotNull { row ->
                    row.select("a[href^=/view/]").lastOrNull()?.absUrl("href")?.substringBefore('#')
                }.distinct()
                assertTrue(results.isNotEmpty(), "Поиск nyaa не вернул раздач: $url")
                links.addAll(results.take(5))
                println("Поиск $url: ${results.size} раздач, исследуем первые 5")
            }
            links += listOf("https://nyaa.si/view/1322540", "https://nyaa.si/view/1509305")
            var multiSeasonCount = 0
            val summaries = mutableListOf<String>()
            for (url in links) {
                delay(300)
                val html = client.get(url).bodyAsText()
                reportDirectory.resolve("${url.substringAfterLast('/')}.html").writeText(html)
                val document = Jsoup.parse(html, url)
                assertTrue(document.select("div.torrent-file-list i.fa-file").isNotEmpty(), "Нет списка файлов: $url")
                reportDirectory.resolve("${url.substringAfterLast('/')}-files.txt").writeText(
                    document.select("div.torrent-file-list i.fa-file").joinToString("\n") { it.parent()!!.ownText() }
                )
                println("Разбор $url")
                val seasons = try {
                    gateway.parseEpisodeMediaFiles(document, isMovie = false)
                } catch (exception: IllegalArgumentException) {
                    println(exception.message!!.replace("; ", "\n"))
                    throw exception
                }
                if (seasons.size > 1) multiSeasonCount++
                val summary = "$url: ${seasons.entries.joinToString { "${it.key.label}: ${it.value.size} серий" }}"
                println(summary)
                summaries += summary
                seasons.forEach { (season, episodes) ->
                    episodes.values.take(2).forEach { println("  ${season.label}: ${it.fullPathInTorrent}") }
                }
                when (url.substringAfterLast('/')) {
                    "1741251" -> {
                        // В самой раздаче отсутствует S01E05; S01E13.5 — recap.
                        val expectedEpisodes = mapOf(
                            1 to ((1..25).toSet() - 5),
                            2 to (1..12).toSet(),
                            3 to (1..22).toSet(),
                            4 to (1..28).toSet()
                        )
                        assertEquals(
                            expectedEpisodes,
                            seasons.mapKeys { checkNotNull(it.key.number) }.mapValues { it.value.keys },
                            url
                        )
                    }

                    "1322540" -> assertEquals(
                        listOf(51, 49, 50, 51),
                        (1..4).map { seasons.getValue(RawSeason(it)).size },
                        url
                    )

                    "1509305" -> assertEquals(listOf(14, 27), seasons.values.map { it.size }.sorted(), url)
                }
            }
            assertTrue(multiSeasonCount > 0, "В исследованных раздачах не распознано несколько сезонов")
            reportDirectory.resolve("summary.txt").writeText(summaries.joinToString("\n"))
        }
    }
}
