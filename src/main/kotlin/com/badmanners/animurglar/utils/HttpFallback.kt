package com.badmanners.animurglar.utils

import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import org.apache.logging.log4j.LogManager

private val logger = LogManager.getLogger("HttpFallback")

suspend fun <T> HttpClient.executeWithProxyFallback(
    directClient: HttpClient,
    block: suspend HttpClient.() -> T,
): T {
    try {
        return block(this)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.warn("Proxy request failed: {} — falling back to direct", e.message)
    }

    return block(directClient)
}
