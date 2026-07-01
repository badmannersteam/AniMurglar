package com.badmanners.animurglar.utils

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.apache.logging.log4j.LogManager
import java.nio.file.Path
import java.time.temporal.ChronoUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

class SearchHistory(historyDir: Path, private val ttlDays: Long = 30L) {

    private val logger = LogManager.getLogger(SearchHistory::class.java)
    private val historyFile = historyDir.resolve("search-history.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val maxEntries = 50

    @Volatile
    private var entries: MutableList<HistoryEntry> = load()

    fun suggestions(query: String, limit: Int = 8): List<String> {
        if (query.isEmpty()) return recent(limit)
        val q = query.trim().lowercase()
        return entries
            .filter { it.query.lowercase().contains(q) }
            .sortedByDescending { it.timestamp }
            .take(limit)
            .map { it.query }
    }

    fun recent(limit: Int = 8): List<String> {
        return entries
            .sortedByDescending { it.timestamp }
            .take(limit)
            .map { it.query }
    }

    fun record(query: String) {
        val trimmed = query.trim()
        if (trimmed.length < 2) return

        entries.removeAll { it.query.equals(trimmed, ignoreCase = true) }
        entries.add(0, HistoryEntry(query = trimmed, timestamp = System.currentTimeMillis()))

        val cutoff = System.currentTimeMillis() - ttlDays * 24 * 60 * 60 * 1000
        entries.removeAll { it.timestamp < cutoff }

        if (entries.size > maxEntries) {
            entries = entries.take(maxEntries).toMutableList()
        }

        save()
    }

    private fun load(): MutableList<HistoryEntry> {
        if (!historyFile.exists()) return mutableListOf()
        return runCatching {
            val data = json.decodeFromString<HistoryData>(historyFile.readText())
            val cutoff = System.currentTimeMillis() - ttlDays * 24 * 60 * 60 * 1000
            data.entries.filter { it.timestamp >= cutoff }.toMutableList()
        }.getOrElse { e ->
            logger.warn("Failed to load search history: {}", e.message)
            mutableListOf()
        }
    }

    private fun save() {
        runCatching {
            historyFile.parent.createDirectories()
            historyFile.writeText(json.encodeToString(HistoryData(entries = entries)))
        }.onFailure { e ->
            logger.warn("Failed to save search history: {}", e.message)
        }
    }

    @Serializable
    private data class HistoryEntry(
        val query: String,
        val timestamp: Long,
    )

    @Serializable
    private data class HistoryData(
        val entries: List<HistoryEntry>,
    )
}
