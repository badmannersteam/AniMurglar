package com.badmanners.animurglar.utils

import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.nio.file.Path
import java.awt.Desktop
import java.net.URI
import kotlin.io.path.PathWalkOption
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.walk
import kotlin.random.Random


const val BROWSER_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36"

val json = Json { ignoreUnknownKeys = true }

suspend inline fun <R> suspendRunCatching(block: suspend () -> R): Result<R> = try {
    Result.success(block())
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (throwable: Throwable) {
    Result.failure(throwable)
}

inline fun <T> List<T>.upsert(item: T, predicate: (T) -> Boolean): List<T> {
    val idx = indexOfFirst(predicate)
    if (idx < 0) {
        return this + item
    }
    return toMutableList().apply {
        this[idx] = item
    }
}

fun JsonObject.string(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull

fun JsonObject.int(name: String): Int? {
    val primitive = this[name]?.jsonPrimitive ?: return null
    return primitive.intOrNull ?: primitive.contentOrNull?.toIntOrNull()
}

fun JsonObject.long(name: String): Long? {
    val primitive = this[name]?.jsonPrimitive ?: return null
    return primitive.longOrNull ?: primitive.contentOrNull?.toLongOrNull()
}

fun Path.deleteRecursivelyIfExists() {
    if (!exists()) {
        return
    }

    walk(PathWalkOption.INCLUDE_DIRECTORIES).sortedByDescending { it.nameCount }.forEach { path ->
        path.deleteIfExists()
    }
}


fun normalizeUrl(url: String) = when {
    url.startsWith("https://") -> url
    url.startsWith("http://") -> "https://${url.removePrefix("http://")}" 
    url.startsWith("//") -> "https:$url"
    else -> null
}

fun openInBrowser(link: String) = runCatching {
    if (Desktop.isDesktopSupported()) {
        Desktop.getDesktop().browse(URI(link))
    }
}

@Composable
fun Modifier.debugBorder(): Modifier {
    val color = Color(Random.nextFloat(), Random.nextFloat(), Random.nextFloat())
    return this.then(Modifier.border(1.dp, color))
}
