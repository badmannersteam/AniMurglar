package com.badmanners.animurglar.local

import com.badmanners.animurglar.media.EpisodeFileParser.containsSpecialsMarker
import com.badmanners.animurglar.media.EpisodeFileParser.extractEpisodeNumber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path
import kotlin.io.path.absolute
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isReadable
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

class LocalRawFilesService {
    companion object {
        private val mediaExtensions = setOf("mkv", "mp4", "avi", "mov", "m4v", "webm", "ts", "m2ts")
    }

    suspend fun scan(folder: Path, isMovie: Boolean): Map<Int, Path> = withContext(Dispatchers.IO) {
        val directory = folder.absolute().normalize()
        require(directory.exists()) { "Папка с локальными равками не существует: $directory" }
        require(directory.isDirectory()) { "Путь к локальным равкам не является папкой: $directory" }
        require(directory.isReadable()) { "Нет доступа для чтения папки с локальными равками: $directory" }

        val mediaFiles = directory.listDirectoryEntries()
            .filter { file ->
                file.isRegularFile() && file.extension.lowercase() in mediaExtensions &&
                    !file.name.containsSpecialsMarker()
            }
            .sortedBy { it.name }
        mediaFiles.forEach { file ->
            require(file.isReadable()) { "Нет доступа для чтения видеофайла: $file" }
        }

        val filesByEpisode = mediaFiles.mapNotNull { file ->
            file.name.extractEpisodeNumber()?.let { episodeNumber -> episodeNumber to file }
        }.groupBy({ it.first }, { it.second }).toSortedMap()
        val conflicts = filesByEpisode.filterValues { it.size > 1 }
        require(conflicts.isEmpty()) {
            conflicts.entries.joinToString(prefix = "Конфликт номеров серий: ", separator = "; ") { (episodeNumber, files) ->
                "серия $episodeNumber — ${files.joinToString { "«${it.name}»" }}"
            }
        }

        when {
            filesByEpisode.isNotEmpty() -> filesByEpisode.mapValuesTo(sortedMapOf()) { (_, files) -> files.single() }
            isMovie && mediaFiles.size == 1 -> sortedMapOf(1 to mediaFiles.single())
            isMovie -> error(
                "В папке «$directory» не удалось определить фильм: найдено ${mediaFiles.size} " +
                    "подходящих видеофайлов без номера серии, требуется ровно один."
            )
            else -> error(
                "В папке «$directory» не найдены видео с номерами серий. " +
                    "Используйте имена вида «Show - 01.mkv» или «S01E01.mkv»."
            )
        }
    }
}
