package com.badmanners.animurglar.downloader

import com.badmanners.animurglar.app.config.AppConfig
import com.badmanners.animurglar.downloader.DownloadProgressEvent.DownloadProgressGroup
import com.badmanners.animurglar.nyaa.TorrentCandidate
import com.badmanners.animurglar.nyaa.TorrentEntry
import com.badmanners.animurglar.nyaa.TorrentEntry.EpisodeMediaFile
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.apache.logging.log4j.LogManager
import qbittorrent.QBittorrentClient
import qbittorrent.models.TorrentFile
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.time.Duration.Companion.milliseconds

class TorrentDownloadService(
    private val client: HttpClient,
    private val config: AppConfig,
) {
    private val logger = LogManager.getLogger(TorrentDownloadService::class.java)

    private data class EpisodeTorrentSelection(
        val episodeNumber: Int,
        val mediaFile: EpisodeMediaFile,
        val magnet: String,
        val torrentHash: String,
    )

    private data class TorrentSelection(
        val torrentHash: String,
        val magnet: String,
        val episodes: List<EpisodeTorrentSelection>,
    )

    private data class SelectedTorrentFile(
        val selection: EpisodeTorrentSelection,
        val fileId: Int,
        val torrentFile: TorrentFile,
    )

    private data class IndexedTorrentFile(
        val index: Int,
        val normalizedPath: String,
        val normalizedFileName: String,
    )

    suspend fun download(
        request: DownloadRequest,
        paths: DownloadPaths,
        onProgress: (DownloadProgressEvent) -> Unit,
    ): Map<Int, Path> {
        val selectedEpisodes = request.selectedEpisodeSelections()
        val torrents = selectedEpisodes
            .groupBy(EpisodeTorrentSelection::torrentHash)
            .values
            .map { episodesForTorrent ->
                val first = episodesForTorrent.first()
                TorrentSelection(
                    torrentHash = first.torrentHash,
                    magnet = first.magnet,
                    episodes = episodesForTorrent.sortedBy(EpisodeTorrentSelection::episodeNumber),
                )
            }
            .sortedBy(TorrentSelection::torrentHash)
        check(torrents.isNotEmpty()) {
            "No torrents available for selected episodes."
        }

        val torrentDownloadDir = paths.torrentRoot
        torrentDownloadDir.createDirectories()

        val qConfig = config.qBittorrent
        val qbClient = QBittorrentClient(
            baseUrl = qConfig.baseUrl,
            username = qConfig.username,
            password = qConfig.password,
            syncInterval = 1500.milliseconds,
            httpClient = client,
            dispatcher = Dispatchers.IO,
        )

        val addedHashes = mutableSetOf<String>()

        try {
            torrents.forEach { torrent ->
                qbClient.addTorrent {
                    urls += torrent.magnet
                    savepath = torrentDownloadDir.toString()
                    paused = true
                }
                addedHashes += torrent.torrentHash
            }

            val selectedByTorrent = torrents.associate { torrent ->
                val selected = qbClient.awaitSelectedFiles(
                    torrent = torrent,
                )
                torrent.torrentHash to selected
            }
            val speedTrackers = mutableMapOf<String, DownloadSpeedTracker>()

            // косяк в либе, resume вместо start
            //qbClient.resumeTorrents(hashes = addedHashes.toList())

            while (true) {
                var allSelectedReady = true

                selectedByTorrent.values.flatten().forEach { selected ->
                    val files = qbClient.getTorrentFiles(hash = selected.selection.torrentHash)
                    val file = files.getOrNull(selected.fileId) ?: error(
                        "Selected file id=${selected.fileId} disappeared for episode ${selected.selection.episodeNumber}."
                    )

                    val itemKey = "torrent:${selected.selection.episodeNumber}"
                    val progress = file.progress.toDouble().coerceIn(0.0, 1.0)
                    val downloadedBytes = (file.size * progress).toLong()
                    val speedBytesPerSecond = speedTrackers
                        .getOrPut(itemKey) { DownloadSpeedTracker() }
                        .update(downloadedBytes = downloadedBytes)
                    if (progress < 0.999) {
                        allSelectedReady = false
                    }

                    onProgress(
                        DownloadProgressEvent(
                            group = DownloadProgressGroup.TORRENT_FILES,
                            itemKey = itemKey,
                            label = "Серия ${selected.selection.episodeNumber}: ${selected.selection.mediaFile.fileName}",
                            progress = progress,
                            episodeNumber = selected.selection.episodeNumber,
                            downloadedBytes = downloadedBytes,
                            totalBytes = file.size,
                            speedBytesPerSecond = speedBytesPerSecond,
                        )
                    )
                }

                if (allSelectedReady) {
                    break
                }

                delay(1500.milliseconds)
            }

            qbClient.deleteTorrents(addedHashes)
            delay(2000.milliseconds)

            val downloadedRaws = mutableMapOf<Int, Path>()
            selectedByTorrent.values.flatten().sortedBy { it.selection.episodeNumber }.forEach { selected ->
                val source = torrentDownloadDir.resolve(selected.torrentFile.name).normalize()
                check(source.exists()) {
                    "Downloaded torrent file does not exist: $source"
                }

                downloadedRaws[selected.selection.episodeNumber] = source

                val fileSize = source.fileSize()
                onProgress(
                    DownloadProgressEvent(
                        group = DownloadProgressGroup.TORRENT_FILES,
                        itemKey = "torrent:${selected.selection.episodeNumber}",
                        label = "Серия ${selected.selection.episodeNumber}: ${selected.selection.mediaFile.fileName}",
                        progress = 1.0,
                        episodeNumber = selected.selection.episodeNumber,
                        downloadedBytes = fileSize,
                        totalBytes = fileSize,
                        speedBytesPerSecond = 0,
                    )
                )
            }

            return downloadedRaws.toSortedMap()
        } finally {
            withContext(NonCancellable) {
                qbClient.deleteTorrents(addedHashes)
                runCatching { qbClient.logout() }
            }
        }
    }

    private suspend fun QBittorrentClient.awaitSelectedFiles(
        torrent: TorrentSelection,
    ): List<SelectedTorrentFile> {
        while (true) {
            val files = getTorrentFiles(hash = torrent.torrentHash)
            if (files.isNotEmpty()) {
                val indexedFiles = files.mapIndexed { index, file ->
                    val normalizedPath = normalizeTorrentPath(file.name)
                    IndexedTorrentFile(
                        index = index,
                        normalizedPath = normalizedPath,
                        normalizedFileName = normalizedPath.substringAfterLast('/'),
                    )
                }

                val selectedFiles = torrent.episodes.map { selection ->
                    val index = findMatchingTorrentFileIndex(indexedFiles, selection)
                    SelectedTorrentFile(
                        selection = selection,
                        fileId = index,
                        torrentFile = files[index],
                    )
                }

                val selectedIds = selectedFiles.map(SelectedTorrentFile::fileId).distinct()
                val unselectedIds = files.indices.filterNot { it in selectedIds }

                setFilePriority(
                    hash = torrent.torrentHash,
                    ids = selectedIds,
                    priority = 7,
                )
                if (unselectedIds.isNotEmpty()) {
                    setFilePriority(
                        hash = torrent.torrentHash,
                        ids = unselectedIds,
                        priority = 0,
                    )
                }

                return selectedFiles
            }

            delay(1500.milliseconds)
        }
    }

    private suspend fun QBittorrentClient.deleteTorrents(addedHashes: MutableSet<String>) {
        if (addedHashes.isEmpty())
            return

        runCatching {
            deleteTorrents(hashes = addedHashes.toList(), deleteFiles = false)
        }.onFailure { throwable ->
            logger.warn("Failed to remove added torrents from qBittorrent: {}", throwable.message)
        }

        addedHashes.clear()
    }

    private fun findMatchingTorrentFileIndex(files: List<IndexedTorrentFile>, selection: EpisodeTorrentSelection): Int {
        val mediaFile = selection.mediaFile
        val normalizedExpectedPath = normalizeTorrentPath(mediaFile.fullPathInTorrent)
        val normalizedExpectedFileName = normalizeTorrentPath(mediaFile.fileName).substringAfterLast('/')

        fun requireUniqueMatch(matches: List<IndexedTorrentFile>, strategy: String) = when {
            matches.isEmpty() -> null
            else -> {
                check(matches.size == 1) {
                    "Episode ${selection.episodeNumber} matched multiple torrent files by $strategy (${mediaFile.fileName})."
                }
                matches.single().index
            }
        }

        requireUniqueMatch(
            matches = files.filter { it.normalizedPath == normalizedExpectedPath },
            strategy = "exact path",
        )?.let { return it }

        requireUniqueMatch(
            matches = files.filter { it.normalizedPath.endsWith("/$normalizedExpectedPath") },
            strategy = "path suffix",
        )?.let { return it }

        requireUniqueMatch(
            matches = files.filter { it.normalizedFileName == normalizedExpectedFileName },
            strategy = "file name",
        )?.let { return it }

        error("Episode ${selection.episodeNumber} was not found in torrent file list (${mediaFile.fileName}).")
    }

    private fun normalizeTorrentPath(value: String) = value.replace('\\', '/').trim().trimStart('/').lowercase()

    private fun extractMagnetHash(magnet: String): String? {
        val regex = Regex("btih:([A-Za-z0-9]+)")
        return regex.find(magnet)?.groupValues?.getOrNull(1)?.lowercase()
    }

    private fun DownloadRequest.selectedEpisodeSelections(): List<EpisodeTorrentSelection> {
        val selectedEpisodes = selectedEpisodes
        val selections = mutableListOf<EpisodeTorrentSelection>()

        when (val candidate = torrent) {
            is TorrentCandidate.SeasonPack -> {
                val magnet = candidate.entry.magnetLink
                val hash = extractMagnetHash(magnet)
                    ?: error("Failed to parse torrent hash from magnet link.")

                selectedEpisodes.forEach { episodeNumber ->
                    candidate.entry.episodeMediaFiles[episodeNumber]?.let { mediaFile ->
                        selections += EpisodeTorrentSelection(
                            episodeNumber = episodeNumber,
                            mediaFile = mediaFile,
                            magnet = magnet,
                            torrentHash = hash,
                        )
                    }
                }
            }

            is TorrentCandidate.EpisodeGroup -> {
                selectedEpisodes.forEach { episodeNumber ->
                    val entry = candidate.group.episodes[episodeNumber] ?: return@forEach
                    val selection = buildEpisodeSelection(
                        episodeNumber = episodeNumber,
                        entry = entry,
                    ) ?: return@forEach
                    selections += selection
                }
            }
        }

        check(selections.isNotEmpty()) {
            "No torrent media files available for selected episodes."
        }

        val missingEpisodes = selectedEpisodes - selections.map(EpisodeTorrentSelection::episodeNumber).toSet()
        check(missingEpisodes.isEmpty()) {
            "No torrent media files available for selected episodes: ${missingEpisodes.sorted().joinToString(", ")}"
        }

        return selections.sortedBy(EpisodeTorrentSelection::episodeNumber)
    }

    private fun buildEpisodeSelection(
        episodeNumber: Int,
        entry: TorrentEntry,
    ): EpisodeTorrentSelection? {
        val mediaFile = entry.episodeMediaFiles[episodeNumber]
            ?: entry.episodeMediaFiles.values.firstOrNull()
            ?: return null
        val magnet = entry.magnetLink
        val hash = extractMagnetHash(magnet) ?: return null

        return EpisodeTorrentSelection(
            episodeNumber = episodeNumber,
            mediaFile = mediaFile,
            magnet = magnet,
            torrentHash = hash,
        )
    }
}
