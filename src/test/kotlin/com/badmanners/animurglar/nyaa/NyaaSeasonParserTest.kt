package com.badmanners.animurglar.nyaa

import com.badmanners.animurglar.media.RawSeason
import com.badmanners.animurglar.nyaa.TorrentEntry.ParsedTorrentMetadata
import com.badmanners.animurglar.nyaa.TorrentEntry.ParsedTorrentMetadata.TorrentCategory
import com.badmanners.animurglar.ui.nyaa.NyaaPickerStore
import com.badmanners.animurglar.ui.nyaa.selectedCandidate
import com.badmanners.animurglar.ui.root.RawSourceKind
import com.badmanners.animurglar.ui.root.RootStore
import com.badmanners.animurglar.ui.root.isRawSelectionReady
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.time.Instant
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NyaaSeasonParserTest {
    @Test
    fun multiSeasonSelectionIsRequiredBeforeStarting() {
        val first = torrent(RawSeason(1), mapOf(1 to TorrentEntry.EpisodeMediaFile(1, "01.mkv", "S1/01.mkv", 1)))
        val second = torrent(RawSeason(2), mapOf(1 to TorrentEntry.EpisodeMediaFile(1, "01.mkv", "S2/01.mkv", 1)))
        val candidate =
            TorrentCandidate.SeasonPack(first, seasonEntries = mapOf(RawSeason(1) to first, RawSeason(2) to second))
        val state = NyaaPickerStore.State(
            candidates = listOf(candidate),
            selectedCandidateKey = candidate.key,
            selectedEpisodes = setOf(1)
        )
        val root = RootStore.State()
        assertNull(state.selectedCandidate())
        assertFalse(root.isRawSelectionReady(state))
        assertEquals(
            "S2/01.mkv",
            assertIs<TorrentCandidate.SeasonPack>(
                state.copy(selectedSeason = RawSeason(2)).selectedCandidate()
            ).entry.episodeMediaFiles.getValue(1).fullPathInTorrent
        )
        assertTrue(root.isRawSelectionReady(state.copy(selectedSeason = RawSeason(2))))
        assertNull(state.copy(selectedSeason = RawSeason(99)).selectedCandidate())
    }

    @Test
    fun localSelectionExposesOnlyChosenSeasonFiles() {
        val first = mapOf(1 to Path.of("S1E1.mkv"))
        val second = mapOf(1 to Path.of("S2E1.mkv"), 2 to Path.of("S2E2.mkv"))
        val state = RootStore.State(
            rawSourceKind = RawSourceKind.LOCAL,
            localSeasons = mapOf(RawSeason(1) to first, RawSeason(2) to second)
        )
        assertTrue(state.localFiles.isEmpty())
        assertFalse(state.isRawSelectionReady(NyaaPickerStore.State()))
        assertEquals(second, state.copy(selectedLocalSeason = RawSeason(2)).localFiles)
    }

    @Test
    fun nestedFileListKeepsSeasonAndFullPath() {
        HttpClient(OkHttp).use { client ->
            val files = NyaaGateway(client).parseEpisodeMediaFiles(
                fileList("Complete/Season 1/01.mkv", "Complete/Season 2/01.mp4", "Complete/Specials/Show S00E01.mkv"),
                isMovie = false,
                context = "Show S1+S2+Specials",
            )
            assertEquals(setOf(RawSeason(1), RawSeason(2)), files.keys)
            assertEquals("Complete/Season 1/01.mkv", files.getValue(RawSeason(1)).getValue(1).fullPathInTorrent)
            assertEquals("Complete/Season 2/01.mp4", files.getValue(RawSeason(2)).getValue(1).fullPathInTorrent)
        }
    }

    @Test
    fun unnumberedFoldersAreNotCollapsedBackIntoOneSeason() {
        HttpClient(OkHttp).use { client ->
            val gateway = NyaaGateway(client)
            val files = gateway.parseEpisodeMediaFiles(fileList("K-ON!/01.mkv", "K-ON!!/01.mkv"), false)
            val entries = files.map { (season, episodes) -> torrent(season, episodes) }
            val candidate = assertIs<TorrentCandidate.SeasonPack>(gateway.buildCandidates(entries, 1).single())
            assertEquals(2, candidate.seasonEntries.size)
            assertEquals(
                "K-ON!!/01.mkv",
                candidate.selectSeason(RawSeason(null, "K-ON!!")).entry.episodeMediaFiles.getValue(1).fullPathInTorrent
            )
        }
    }

    @Test
    fun selectingSeasonScopesEpisodesAndDownloadFilePaths() {
        HttpClient(OkHttp).use { client ->
            val gateway = NyaaGateway(client)
            val files = gateway.parseEpisodeMediaFiles(
                fileList(
                    "S1/Show S01E01.mkv",
                    "S2/Show S02E01.mkv",
                    "S2/Show S02E02.mkv"
                ), false
            )
            val candidate =
                assertIs<TorrentCandidate.SeasonPack>(gateway.buildCandidates(files.map { (season, episodes) ->
                    torrent(
                        season,
                        episodes
                    )
                }, 2).single())
            val seasonTwo = candidate.selectSeason(RawSeason(2))
            assertEquals(candidate.key, seasonTwo.key)
            assertEquals(listOf(1, 2), seasonTwo.episodes)
            assertEquals(listOf(1), candidate.selectSeason(RawSeason(1)).episodes)
            assertTrue(seasonTwo.entry.episodeMediaFiles.values.all { "/Show S02" in it.fullPathInTorrent })
            assertEquals(2, seasonTwo.seasonEntries.size)
            assertFailsWith<NoSuchElementException> { candidate.selectSeason(RawSeason(3)) }
        }
    }

    private fun torrent(season: RawSeason, episodes: Map<Int, TorrentEntry.EpisodeMediaFile>) = TorrentEntry(
        id = "123",
        displayName = "Show S1+S2",
        nyaaCategory = "English-translated",
        detailsLink = "https://nyaa.si/view/123",
        magnetLink = "magnet:?xt=urn:btih:0123456789012345678901234567890123456789",
        metadata = ParsedTorrentMetadata("show", season.number, category = TorrentCategory.SEASON_PACK),
        totalSizeBytes = episodes.values.sumOf { it.sizeBytes },
        seeders = 1,
        totalDownloads = 1,
        uploadedAt = Instant.EPOCH,
        episodeMediaFiles = episodes,
        rawSeason = season,
    )

    private fun fileList(vararg paths: String): Document {
        val document = Jsoup.parse("<div class='torrent-file-list'><ul></ul></div>")
        val root = document.selectFirst("div.torrent-file-list > ul")!!
        paths.forEach { path ->
            val components = path.split('/')
            var list = root
            components.dropLast(1).forEach { folder ->
                val existing = list.children().firstOrNull { it.selectFirst("> a.folder")?.ownText() == folder }
                list = when (existing) {
                    null -> list.appendElement("li").apply {
                        appendElement("a").addClass("folder").text(folder)
                    }.appendElement("ul")

                    else -> existing.selectFirst("> ul")!!
                }
            }
            list.appendElement("li").apply {
                appendElement("i").addClass("fa-file")
                appendText(components.last())
                appendElement("span").addClass("file-size").text("(1 GiB)")
            }
        }
        return document
    }
}
