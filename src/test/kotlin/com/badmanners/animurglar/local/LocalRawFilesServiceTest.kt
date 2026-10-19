package com.badmanners.animurglar.local

import com.badmanners.animurglar.media.RawSeason
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteExisting
import kotlin.io.path.listDirectoryEntries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LocalRawFilesServiceTest {
    @Test
    fun differentSeasonsDoNotConflict() = runBlocking {
        withVideoFiles("Show S01E01.mkv", "Show S02E01.mkv") { directory ->
            val seasons = LocalRawFilesService().scan(directory, isMovie = false)
            assertEquals(2, seasons.size)
            assertEquals(
                directory.resolve("Show S01E01.mkv").toAbsolutePath(),
                seasons.getValue(RawSeason(1)).getValue(1)
            )
            assertEquals(
                directory.resolve("Show S02E01.mkv").toAbsolutePath(),
                seasons.getValue(RawSeason(2)).getValue(1)
            )
        }
    }

    @Test
    fun duplicateFilesWithinSeasonAreReported() = runBlocking {
        withVideoFiles("Show S02E01.mkv", "Show S02E01v2.mkv") { directory ->
            val failure = assertFailsWith<IllegalArgumentException> { LocalRawFilesService().scan(directory, false) }
            assertTrue(failure.message!!.contains("Сезон 2, серия 1"))
            assertTrue(failure.message!!.contains("S02E01v2"))
        }
    }

    @Test
    fun singleUnnumberedMovieIsStillSupported() = runBlocking {
        withVideoFiles("Movie [1080p].mkv") { directory ->
            val seasons = LocalRawFilesService().scan(directory, true)
            assertEquals(setOf(1), seasons.getValue(RawSeason(null)).keys)
        }
    }

    @Test
    fun scansOnlyImmediateFilesAndIgnoresExtras() = runBlocking {
        withVideoFiles("Show S02E01.mkv", "Show NCOP01.mkv") { directory ->
            val nested = directory.resolve("Season 3").createDirectories()
            val nestedFile = nested.resolve("Show S03E01.mkv").createFile()
            try {
                val seasons = LocalRawFilesService().scan(directory, false)
                assertEquals(setOf(RawSeason(2)), seasons.keys)
                assertEquals(setOf(1), seasons.getValue(RawSeason(2)).keys)
            } finally {
                nestedFile.deleteExisting()
                nested.deleteExisting()
            }
        }
    }

    private suspend fun withVideoFiles(vararg names: String, action: suspend (Path) -> Unit) {
        val directory = createTempDirectory(Path.of("build").createDirectories(), "animurglar-seasons-")
        try {
            names.forEach { directory.resolve(it).createFile() }
            action(directory)
        } finally {
            directory.listDirectoryEntries().forEach { it.deleteExisting() }
            directory.deleteExisting()
        }
    }
}
