package com.badmanners.animurglar.utils

import org.apache.logging.log4j.LogManager

object RequestLogger {

    private val logger = LogManager.getLogger("Requests")

    fun logRequest(url: String, method: String = "GET", status: Int? = null, chars: Int? = null, via: String? = null) {
        val parts = mutableListOf(method, url)
        via?.let { parts.add("via=$it") }
        status?.let { parts.add("status=$it") }
        chars?.let { parts.add("${it} chars") }
        logger.info(parts.joinToString(" "))
    }

    fun logCacheHit(key: String) {
        logger.info("CACHE HIT: $key")
    }

    fun logCacheMiss(key: String) {
        logger.info("CACHE MISS: $key")
    }

    fun logCacheWrite(key: String) {
        logger.info("CACHE WRITE: $key")
    }
}
