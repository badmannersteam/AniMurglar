package com.badmanners.animurglar.dubs

import com.badmanners.animurglar.utils.BROWSER_USER_AGENT
import com.badmanners.animurglar.utils.json
import com.badmanners.animurglar.utils.normalizeUrl
import com.badmanners.animurglar.utils.string
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Parameters
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.apache.logging.log4j.LogManager
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlin.text.RegexOption.DOT_MATCHES_ALL
import kotlin.text.RegexOption.IGNORE_CASE


class KodikGateway(private val client: HttpClient) {

    private val logger = LogManager.getLogger(KodikGateway::class.java)

    @Volatile
    private var cachedDecoderShift: Int = 0

    suspend fun resolveManifest(seriaUrl: String): String {
        val kodikPageHtml = loadText(
            url = seriaUrl,
            referer = KODIK_REFERER,
            origin = KODIK_ORIGIN,
        )
        val playerScriptPath = extractPlayerScriptPath(kodikPageHtml)
        val playerScriptBody = loadText(
            url = "https://$KODIK_DOMAIN$playerScriptPath",
            referer = KODIK_REFERER,
            origin = KODIK_ORIGIN,
        )
        val endpointPath = extractEndpoint(playerScriptBody)
        val signedParams = extractSignedParams(kodikPageHtml)
        val videoInfo = extractVideoInfo(kodikPageHtml, seriaUrl)
        val linksPayload = loadKodikLinks(
            endpointPath = endpointPath,
            signedParams = signedParams,
            videoInfo = videoInfo,
            referer = buildKodikPostReferer(seriaUrl),
        )

        return selectManifest(linksPayload)
    }

    private suspend fun loadKodikLinks(
        endpointPath: String,
        signedParams: SignedParams,
        videoInfo: VideoInfo,
        referer: String,
    ): JsonObject {
        val endpointUrl = when {
            endpointPath.startsWith("http://") || endpointPath.startsWith("https://") -> endpointPath
            endpointPath.startsWith("/") -> "https://$KODIK_DOMAIN$endpointPath"
            else -> "https://$KODIK_DOMAIN/$endpointPath"
        }

        val body = client.submitForm(
            url = endpointUrl,
            formParameters = Parameters.build {
                append("d", signedParams.domain)
                append("d_sign", signedParams.domainSign)
                append("pd", signedParams.playerDomain)
                append("pd_sign", signedParams.playerDomainSign)
                append("ref", signedParams.referrer)
                append("ref_sign", signedParams.referrerSign)
                append("bad_user", "false")
                append("cdn_is_working", "true")
                append("type", videoInfo.type)
                append("hash", videoInfo.hash)
                append("id", videoInfo.id)
                append("info", "{}")
            }
        ) {
            headers {
                append("origin", KODIK_ORIGIN)
                append("referer", referer)
                append("accept", "application/json, text/javascript, */*; q=0.01")
                append("x-requested-with", "XMLHttpRequest")
                append("user-agent", BROWSER_USER_AGENT)
            }
        }.bodyAsText()

        val root = json.decodeFromString<JsonObject>(body)
        return root["links"]?.jsonObject
            ?: error("Kodik response does not contain links.")
    }

    private fun selectManifest(links: JsonObject): String {
        val encoded360 = firstEncodedSrc(links, "360")
//        val encoded480 = firstEncodedSrc(links, "480")
//        val encoded720 = firstEncodedSrc(links, "720")

        val decoded360 = decodeLinkOrNull(encoded360)
//        val decoded480 = decodeLinkOrNull(encoded480)
//            ?: decoded360?.replace("/360.mp4", "/480.mp4")
//        val decoded720 = decodeLinkOrNull(encoded720)
//            ?: decoded360?.replace("/360.mp4", "/720.mp4")

        return (/*decoded720 ?: decoded480 ?:*/ decoded360)/*?.removeSuffix(":hls:manifest.m3u8")*/
            ?: error("No decodable Kodik links found.")
    }

    private suspend fun loadText(url: String, referer: String, origin: String): String =
        client.get(url) {
            headers {
                append("origin", origin)
                append("referer", referer)
                append("user-agent", BROWSER_USER_AGENT)
            }
        }.bodyAsText()

    private fun buildKodikPostReferer(sourceUrl: String): String {
        val querySeparator = if ('?' in sourceUrl) '&' else '?'
        return "$sourceUrl${querySeparator}translations=false"
    }

    private fun extractPlayerScriptPath(html: String): String {
        val match = PLAYER_SCRIPT_REGEX.find(html)
            ?: error("Kodik player script path not found.")
        return match.groupValues[1]
    }

    private fun extractEndpoint(playerScriptBody: String): String {
        val encoded = ENDPOINT_REGEX.find(playerScriptBody)?.groupValues?.getOrNull(1)
            ?: error("Kodik endpoint not found in player script.")

        return runCatching {
            String(Base64.getDecoder().decode(encoded), Charsets.UTF_8)
        }.getOrNull() ?: error("Unable to decode Kodik endpoint.")
    }

    private fun extractSignedParams(html: String): SignedParams {
        val paramsMatch = URL_PARAMS_REGEX.find(html)?.groupValues?.getOrNull(1)
            ?: error("Kodik signed params not found.")
        val paramsJson = json.decodeFromString<JsonObject>(paramsMatch)

        return SignedParams(
            domain = paramsJson.string("d") ?: error("Missing Kodik param d."),
            domainSign = paramsJson.string("d_sign") ?: error("Missing Kodik param d_sign."),
            playerDomain = paramsJson.string("pd") ?: error("Missing Kodik param pd."),
            playerDomainSign = paramsJson.string("pd_sign")
                ?: error("Missing Kodik param pd_sign."),
            referrer = decodeUrlComponent(
                paramsJson.string("ref") ?: error("Missing Kodik param ref.")
            ),
            referrerSign = paramsJson.string("ref_sign")
                ?: error("Missing Kodik param ref_sign."),
        )
    }

    private fun decodeUrlComponent(value: String) = runCatching { URLDecoder.decode(value, StandardCharsets.UTF_8) }
        .getOrDefault(value)

    private fun extractVideoInfo(html: String, kodikUrl: String): VideoInfo {
        val fields = mutableMapOf<String, String>()
        VIDEO_INFO_REGEX.findAll(html).forEach { match ->
            fields[match.groupValues[1]] = match.groupValues[2]
        }

        val type = fields["type"]
        val hash = fields["hash"]
        val id = fields["id"]
        if (type != null && hash != null && id != null) {
            return VideoInfo(type = type, hash = hash, id = id)
        }

        val pathMatch = URL_VIDEO_INFO_REGEX.find(kodikUrl)
            ?: error("Unable to extract Kodik video info from page and URL.")
        return VideoInfo(
            type = pathMatch.groupValues[1],
            id = pathMatch.groupValues[2],
            hash = pathMatch.groupValues[3],
        )
    }

    private fun firstEncodedSrc(links: JsonObject, quality: String): String? {
        val entries = links[quality] as? JsonArray ?: return null
        val first = entries.firstOrNull()?.jsonObject ?: return null
        return first.string("src")
    }

    private fun decodeLinkOrNull(encoded: String?): String? {
        if (encoded.isNullOrBlank()) {
            return null
        }

        return runCatching { decodeLink(encoded) }
            .onFailure { logger.warn("Kodik link decode failed: {}", it.message) }
            .getOrNull()
    }

    private fun decodeLink(encoded: String): String {
        val initialShift = cachedDecoderShift.coerceIn(MIN_SHIFT, MAX_SHIFT)
        tryDecode(encoded = encoded, shift = initialShift)?.let { return it }

        for (shift in MIN_SHIFT..MAX_SHIFT) {
            val decoded = tryDecode(encoded = encoded, shift = shift) ?: continue
            cachedDecoderShift = shift
            return decoded
        }

        error("Unable to decode Kodik link.")
    }

    private fun tryDecode(encoded: String, shift: Int): String? {
        val caesarDecoded = caesarDecode(encoded, shift)
        val padded = buildString {
            append(caesarDecoded)
            while (length % 4 != 0)
                append('=')
        }

        val decoded = runCatching {
            String(Base64.getDecoder().decode(padded), Charsets.UTF_8)
        }.getOrNull() ?: return null

        return normalizeUrl(decoded)
    }

    private fun caesarDecode(text: String, shift: Int): String = buildString(text.length) {
        text.forEach { character ->
            append(
                when (character) {
                    in 'a'..'z' -> {
                        val position = character.code - 'a'.code
                        val newPosition = (position + ALPHABET_SIZE - shift) % ALPHABET_SIZE
                        ('a'.code + newPosition).toChar()
                    }

                    in 'A'..'Z' -> {
                        val position = character.code - 'A'.code
                        val newPosition = (position + ALPHABET_SIZE - shift) % ALPHABET_SIZE
                        ('A'.code + newPosition).toChar()
                    }

                    else -> character
                }
            )
        }
    }

    private data class VideoInfo(
        val type: String,
        val hash: String,
        val id: String,
    )

    private data class SignedParams(
        val domain: String,
        val domainSign: String,
        val playerDomain: String,
        val playerDomainSign: String,
        val referrer: String,
        val referrerSign: String,
    )

    companion object {
        const val KODIK_DOMAIN = "kodikplayer.com"
        const val KODIK_ORIGIN = "https://$KODIK_DOMAIN"
        const val KODIK_REFERER = "$KODIK_ORIGIN/"
        const val KODIK_DEFAULT_QUALITY = "720p"

        private const val ALPHABET_SIZE = 26
        private const val MIN_SHIFT = 0
        private const val MAX_SHIFT = ALPHABET_SIZE

        private val PLAYER_SCRIPT_REGEX = Regex(
            """<script\s*type="text/javascript"\s*src="(/assets/js/app\.player_single[^"]+)""", IGNORE_CASE
        )
        private val ENDPOINT_REGEX = Regex("""\$\.ajax\([^>]+,url:\s*atob\(["']([\w=]+)["']\)""", DOT_MATCHES_ALL)
        private val URL_PARAMS_REGEX = Regex("""var\s+urlParams\s*=\s*'([^']+)';""")
        private val VIDEO_INFO_REGEX = Regex("""vInfo\.(type|hash|id)\s*=\s*'([^']+)';""")
        private val URL_VIDEO_INFO_REGEX = Regex("""/([^/]+)/(\d+)/([a-z0-9]+)""", IGNORE_CASE)
    }
}