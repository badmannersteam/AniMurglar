package com.badmanners.animurglar.local

import com.badmanners.animurglar.media.EpisodeFileParser
import com.badmanners.animurglar.media.EpisodeFileParser.containsSpecialsMarker
import com.badmanners.animurglar.media.EpisodeFileParser.isMediaFile
import com.badmanners.animurglar.media.RawSeason
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path
import kotlin.io.path.absolute
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isReadable
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

class LocalRawFilesService {
    suspend fun scan(folder: Path, isMovie: Boolean): Map<RawSeason, Map<Int, Path>> = withContext(Dispatchers.IO) {
        val directory = folder.absolute().normalize()
        require(directory.exists()) { "Папка с локальными равками не существует: $directory" }
        require(directory.isDirectory()) { "Путь к локальным равкам не является папкой: $directory" }
        require(directory.isReadable()) { "Нет доступа для чтения папки с локальными равками: $directory" }

        val mediaFiles = directory.listDirectoryEntries()
            .filter { file ->
                file.isRegularFile() && file.name.isMediaFile() &&
                        !file.name.containsSpecialsMarker()
            }
            .sortedBy { it.name }
        mediaFiles.forEach { file ->
            require(file.isReadable()) { "Нет доступа для чтения видеофайла: $file" }
        }

        val filesBySeason = EpisodeFileParser.indexFiles(mediaFiles, isMovie) { file ->
            "${directory.name}/${file.name}"
        }

        when {
            filesBySeason.isNotEmpty() -> filesBySeason
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
