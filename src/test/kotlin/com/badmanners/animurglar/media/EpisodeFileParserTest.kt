package com.badmanners.animurglar.media

import com.badmanners.animurglar.media.EpisodeFileParser.extractEpisodeNumber
import com.badmanners.animurglar.media.EpisodeFileParser.extractSeason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class EpisodeFileParserTest {
    @Test
    fun parsesRealNyaaFileNames() {
        val examples = listOf(
            Triple("1350495", "Season 1/Sword Art Online - S01E01 - The World Of Swords.mkv", 1 to 1),
            Triple("1350495", "Season 4/Sword Art Online - S04E23 - New World.mkv", 4 to 23),
            Triple(
                "1899950",
                "Season 3/[Sav1or] Uma Musume Pretty Derby - S03E13 - And Yours [BD][1080p][AV1][OPUS].mkv",
                3 to 13
            ),
            Triple(
                "1232567",
                "Kono Subarashii Sekai ni Shukufuku wo! 2/[Cerberus] Kono Subarashii Sekai ni Shukufuku wo! 2 - S02E02 - [AD087CA6].mkv",
                2 to 2
            ),
            Triple(
                "1741251",
                "The Final Season - Season 4/Shingeki no Kyojin - The Final Season - S04E01 - The Other Side of the Sea.mkv",
                4 to 1
            ),
            Triple(
                "2153003",
                "[2004] [Season 3] Initial D Fourth Stage/Initial D [2004] - S03E01 - Project D.mkv",
                3 to 1
            ),
            Triple("1322540", "S1 Ojamajo Doremi/[Doremi].Ojamajo.Doremi.01.[640x480].[7A1AB22A].v2.1.mkv", 1 to 1),
            Triple(
                "1322540",
                "S2 Ojamajo Doremi #/[Doremi].Ojamajo.Doremi.Sharp.01.[640x480].[D77B6D26].v2.mkv",
                2 to 1
            ),
            Triple("1253773", "2. Yuru Yuri S2/[Rom & Rem] Yuru Yuri 2 - 06 [H265][10bits][1080p][FLAC].mkv", 2 to 6),
        )
        examples.forEach { (id, path, expected) ->
            assertEquals(
                EpisodeFileParser.EpisodeIdentity(RawSeason(expected.first), expected.second),
                EpisodeFileParser.parse(path),
                "https://nyaa.si/view/$id: $path",
            )
        }
    }

    @Test
    fun recognizesSeasonFormatsAndNearestContext() {
        listOf("S02E03", "s02_e03", "2x03", "Season 2 - 03", "2nd Season - 03", "第2季 第03話").forEach {
            assertEquals(EpisodeFileParser.EpisodeIdentity(RawSeason(2), 3), EpisodeFileParser.parse("$it.mkv"), it)
        }
        assertEquals(RawSeason(2), EpisodeFileParser.parse("Season 1/Season 2/03.mkv")?.season)
        assertEquals(RawSeason(3), EpisodeFileParser.parse("Season 2/Show S03E04.mkv")?.season)
        assertEquals(3, "Show Season 2 03.mkv".extractEpisodeNumber())
        assertEquals(1, "[Group] 86 - 01 [1080p].mkv".extractEpisodeNumber())
        assertNull("Season 2.mkv".extractEpisodeNumber())
        assertNull("[s4s]".extractSeason())
        assertNull("S3∞ Ojamajo Doremi Na・i・sho".extractSeason())
        assertEquals(
            RawSeason(null),
            EpisodeFileParser.parse("S3∞ Ojamajo Doremi Na・i・sho/[s4s] Ojamajo Doremi Na-i-sho 01.mkv")?.season
        )
        assertEquals(RawSeason(1), EpisodeFileParser.parse("Show S01E01 - Season 2 begins.mkv")?.season)
        assertEquals(1, EpisodeFileParser.parse("Show S01E01 - 13.5 Reasons [AAC 5.1].mkv")?.episodeNumber)
    }

    @Test
    fun episodeTitlesAreNotSpecialsMarkers() {
        listOf("Special Operations Squad", "Opening", "The OVA incident").forEach { title ->
            assertEquals(1, EpisodeFileParser.parse("Show S01E01 - $title.mkv")?.episodeNumber)
        }
        assertNull(EpisodeFileParser.parse("Show S01E01 OVA - Extra.mkv"))
    }

    @Test
    fun collectionTitleDoesNotBecomeSeasonOneOrExcludeRegularEpisodes() {
        listOf("Show S1+S2+OVA", "Show S01-S04 + Specials", "Show S1 S2 Specials", "Show All Seasons + OADs").forEach {
            assertNull(it.extractSeason(), it)
            assertEquals(RawSeason(2), EpisodeFileParser.parse("$it/Season 2/01.mkv")?.season, it)
            assertEquals(RawSeason(null), EpisodeFileParser.parse("$it/01.mkv")?.season, it)
        }
    }

    @Test
    fun filtersSpecialsAndTechnicalNumbers() {
        listOf(
            "Specials/Sword Art Online - S00E01 - Sword Art Offline 1.mkv",
            "OVA/Show S00E01.mkv",
            "Season 2/Show S02E11 OVA [9EBB01E4].mkv",
            "OADs/Shingeki no Kyojin - OADE01.mkv",
            "SPs/Show [SP03].mkv",
            "Extras/4KIDS Dub/Magical DoReMi - 01.mp4",
            "Season 1/Show NCOP01.mkv",
            "Season 1/Show S00E01.mkv",
            "Season 1/Shingeki no Kyojin - S01E13.5 - Since That Day.mkv",
            "Season 1/Show - 13.5.mkv",
        ).forEach { assertNull(EpisodeFileParser.parse(it), it) }
        listOf("Show [1080p][x265][10bit][12345678].mkv", "Show [2004] [1920x1080] [A99E87D0].mkv").forEach {
            assertNull(EpisodeFileParser.parse(it), it)
        }
        assertEquals(1, EpisodeFileParser.parse("Peter Grill to Kenja no Jikan Super Extra - 01.mkv")?.episodeNumber)
    }

    @Test
    fun unnumberedSeasonsRemainSeparateByFolderWithoutGuessingNumbers() {
        val paths = listOf(
            "Complete/[VCB-Studio] K-ON! [Ma10p_1080p]/[VCB-Studio] K-ON! [01][Ma10p_1080p][x265_flac_2aac].mkv",
            "Complete/[VCB-Studio] K-ON!! [Ma10p_1080p]/[VCB-Studio] K-ON!! [01][Ma10p_1080p][x265_flac_2aac].mkv",
        )
        val seasons = EpisodeFileParser.indexFiles(paths, false) { it }
        assertEquals(2, seasons.size)
        assertEquals(
            setOf("[VCB-Studio] K-ON! [Ma10p_1080p]", "[VCB-Studio] K-ON!! [Ma10p_1080p]"),
            seasons.keys.map { it.label }.toSet()
        )
        assertEquals(listOf(listOf(1), listOf(1)), seasons.values.map { it.keys.toList() })
    }

    @Test
    fun duplicateEpisodesWithinSeasonFailInsteadOfOverwritingFiles() {
        assertFailsWith<IllegalArgumentException> {
            EpisodeFileParser.indexFiles(listOf("Season 1/Show S01E01.mkv", "Season 1/Show S01E01v2.mkv"), false) { it }
        }
    }

    @Test
    fun keepsUnknownSeasonSeparateFromExplicitSeasons() {
        val paths = listOf("Show - 01.mkv", "Show S02E01.mkv")
        val seasons = EpisodeFileParser.indexFiles(paths, false) { it }
        assertEquals(setOf(RawSeason(null), RawSeason(2)), seasons.keys)
        assertEquals(paths[0], seasons.getValue(RawSeason(null)).getValue(1))
        assertEquals(paths[1], seasons.getValue(RawSeason(2)).getValue(1))
    }
}
