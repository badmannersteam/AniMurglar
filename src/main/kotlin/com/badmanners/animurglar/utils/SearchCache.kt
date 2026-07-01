package com.badmanners.animurglar.utils

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.apache.logging.log4j.LogManager
import java.nio.file.Path
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

class SearchCache(cacheDir: Path, private val defaultTtlDays: Long = 3L) {

    private val logger = LogManager.getLogger(SearchCache::class.java)
    private val cacheFile = cacheDir.createDirectories().resolve("search-cache.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = ReentrantReadWriteLock()

    @Volatile
    private var entries: ConcurrentHashMap<String, CacheEntry> = load()

    suspend fun <T> getOrPut(
        key: String,
        ttlDays: Long = this.defaultTtlDays,
        serializer: (T) -> String,
        deserializer: (String) -> T,
        compute: suspend () -> T,
    ): T {
        lock.read {
            entries[key]?.let { entry ->
                val age = ChronoUnit.HOURS.between(Instant.ofEpochMilli(entry.timestamp), Instant.now())
                if (age < ttlDays * 24) {
                    RequestLogger.logCacheHit(key)
                    return deserializer(entry.data)
                }
            }
        }

        RequestLogger.logCacheMiss(key)
        val result = compute()

        lock.write {
            val entryTimestamp = entries[key]?.timestamp ?: 0L
            val age = ChronoUnit.HOURS.between(Instant.ofEpochMilli(entryTimestamp), Instant.now())
            if (age >= ttlDays * 24) {
                entries[key] = CacheEntry(timestamp = System.currentTimeMillis(), data = serializer(result))
                RequestLogger.logCacheWrite(key)
                save()
            }
        }
        return result
    }

    fun clear() {
        lock.write {
            entries.clear()
            save()
        }
        logger.info("Search cache cleared")
    }

    private fun load(): ConcurrentHashMap<String, CacheEntry> {
        if (!cacheFile.exists()) return ConcurrentHashMap()
        return runCatching {
            val data = json.decodeFromString<CacheData>(cacheFile.readText())
            val cutoff = System.currentTimeMillis() - defaultTtlDays * 24 * 60 * 60 * 1000
            ConcurrentHashMap(data.entries.filter { it.value.timestamp >= cutoff })
        }.getOrElse { e ->
            logger.warn("Failed to load search cache: {}", e.message)
            ConcurrentHashMap()
        }
    }

    private fun save() {
        runCatching {
            cacheFile.writeText(json.encodeToString(CacheData(entries = entries.toMap())))
        }.onFailure { e ->
            logger.warn("Failed to save search cache: {}", e.message)
        }
    }

    @Serializable
    private data class CacheEntry(
        val timestamp: Long,
        val data: String,
    )

    @Serializable
    private data class CacheData(
        val entries: Map<String, CacheEntry>,
    )
}
