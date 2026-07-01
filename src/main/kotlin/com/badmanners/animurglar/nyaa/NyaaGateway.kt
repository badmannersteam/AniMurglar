package com.badmanners.animurglar.nyaa

import com.badmanners.animurglar.app.config.AppConfig
import com.badmanners.animurglar.nyaa.TorrentEntry.EpisodeMediaFile
import com.badmanners.animurglar.nyaa.TorrentEntry.ParsedTorrentMetadata
import com.badmanners.animurglar.nyaa.TorrentEntry.ParsedTorrentMetadata.TorrentCategory
import com.badmanners.animurglar.nyaa.TorrentEpisodeGroup.TorrentGroupProfile
import com.badmanners.animurglar.utils.RequestLogger
import com.badmanners.animurglar.utils.SearchCache
import com.badmanners.animurglar.utils.executeWithProxyFallback
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.URLBuilder
import org.apache.logging.log4j.LogManager
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.Instant
import kotlin.math.roundToLong
import kotlin.text.RegexOption.IGNORE_CASE


class NyaaGateway(private val client: HttpClient, private val directClient: HttpClient, private val config: AppConfig, private val searchCache: SearchCache) {

    companion object {
        private val squareBracketsContentRegex = Regex("""\[[^]]*]""")
        private val parenthesesContentRegex = Regex("""\([^)]*\)""")
        private val mediaExtensionRegex = Regex("""(?i)\.(mkv|mp4)\b""")
        private val resolutionRegex = Regex("""(?i)\b\d{3,4}p\b""")
        private val seasonEpisodeRegex = Regex("""(?i)\bs\d{1,2}\s*[-_. ]?e\d{1,3}\b""")
        private val explicitEpisodeRegex = Regex("""(?i)\b(?:EP|E|EPISODE)\s*\d{1,3}\b""")
        private val episodeRangeRegex = Regex("""(?i)\b\d{1,3}\s*[-~]\s*\d{1,3}\b""")
        private val batchMarkersRegex = Regex("""(?i)\b(BATCH|COMPLETE|VOL\.?\s*\d+\s*-\s*\d+)\b""")
        private val groupTitleEpisodeTailRegex = Regex(
            """(?i)(?:[\s._-]+(?:EP|E|EPISODE)?\s*0*\d{1,3}(?:V\d+)?)+\s*$"""
        )
        private val extractResolutionRegex = Regex("""(?i)\b(480p|576p|720p|900p|1080p|1440p|2160p|4k)\b""")
        private val extractSeasonPatterns = listOf(
            Regex("""(?i)\bs(\d{1,2})\s*[-_. ]?e\d{1,3}\b"""),
            Regex("""(?i)\b(\d{1,2})x\d{1,3}\b"""),
            Regex("""(?i)\b(\d{1,2})(?:st|nd|rd|th)?\s+season\b"""),
            Regex("""(?i)\bseason\s*[-_. ]?(\d{1,2})\b"""),
            Regex("""(?i)\b第\s*(\d{1,2})\s*季\b"""),
            Regex("""(?i)\bs(\d{1,2})(?=\b|[^0-9])"""),
        )
        private val extractEpisodePatterns = listOf(
            Regex("""(?i)\bs\d{1,2}\s*[-_. ]?e(\d{1,3})(?:v\d+)?\b"""),
            Regex("""(?i)\b(?:EP|E|EPISODE)\s*[-_. ]?0*(\d{1,3})(?:v\d+)?\b"""),
            Regex("""(?i)第\s*0*(\d{1,3})\s*[話话]\b"""),
            Regex("""(?i)\[(\d{1,3})](?=[^0-9]|$)"""),
            Regex("""(?i)-\s*0*(\d{1,3})(?:v\d+)?\b"""),
            Regex("""(?i)(?:^|[\s\[(._-])0*(\d{1,3})(?:v\d+)?(?=$|[])\s._-]|\.m(?:kv|p4)\b)"""),
        )
        private val nonAlphaNumericRegex = Regex("""[^A-Z0-9]+""")
        private val torrentSizeRegex = Regex("""(?i)(\d+(?:\.\d+)?)\s*([KMGT]?i?B|B)""")
        private val technicalTagsRegex = Regex(
            """(?i)\b(?:HEVC|AVC|X265|X264|H\.265|H\.264|H265|H264|FLAC|AAC|AC3|DTS|PCM|OPUS|BDREMUX|BDMV|BLURAY|BDRIP|WEB-DL|WEBRIP|DVD)\b"""
        )
        private val whitespaceRegex = Regex("""\s+""")
    }

    private val logger = LogManager.getLogger(NyaaGateway::class.java)

    private val specialsBlacklist = listOf(
        Regex("""\bNC(?:OP|ED)(?:\d+[A-Z]?|V\d+)?\b""", IGNORE_CASE),
        Regex("""\b(?:OP|ED)(?:\d+[A-Z]?|V\d+)?\b""", IGNORE_CASE),
        Regex("""\b(?:OPENING|ENDING)(?:\d+[A-Z]?|V\d+)?\b""", IGNORE_CASE),
        Regex("""\bSPECIALS?\b""", IGNORE_CASE),
        Regex("""\bEXTRAS?\b""", IGNORE_CASE),
        Regex("""\b(?:CREDITLESS|NONCREDIT(?:ED|LESS)?)\b""", IGNORE_CASE),
        Regex("""\bTRAILERS?\b""", IGNORE_CASE),
        Regex("""\bPV\d*[A-Z]?\b""", IGNORE_CASE),
        Regex("""\bCM\d*[A-Z]?\b""", IGNORE_CASE),
        Regex("""\bMENU(?:\d+|(?:\s+NEW)?)\b""", IGNORE_CASE),
        Regex("""\b(?:TOKUTEN|OUTTAKES?|SHORTS?|OMAKE|BONUS)\b""", IGNORE_CASE),
    )

    private val sourceTags = listOf("BDREMUX", "BDMV", "BLURAY", "BDRIP", "WEB-DL", "WEBRIP", "DVD")
    private val videoCodecTags =
        listOf("HEVC", "AVC", "X265", "X264", "H.265", "H.264", "H265", "H264", "H 265", "H 264")
    private val audioCodecTags = listOf("FLAC", "AAC", "AC3", "DTS", "PCM", "OPUS")

    suspend fun search(
        queries: List<String>,
        episodesAired: Int,
        isMovie: Boolean,
        onProgress: ((NyaaSearchProgress) -> Unit)
    ): List<TorrentCandidate> {
        val listEntries = buildList {
            for (query in queries)
                addAll(loadAllListEntries(query))
        }

        val deduplicatedListEntries = listEntries.distinctBy { it.id }
        val totalRequests = deduplicatedListEntries.size
        onProgress(NyaaSearchProgress(currentRequest = 0, totalRequests = totalRequests))

        val enrichedEntries = buildList {
            deduplicatedListEntries.forEachIndexed { index, entry ->
                enrichWithDetails(entry, isMovie = isMovie)?.let(::add)
                onProgress(NyaaSearchProgress(currentRequest = index + 1, totalRequests = totalRequests))
            }
        }
        val candidates = buildCandidates(
            entries = enrichedEntries,
            episodesAired = when {
                isMovie -> 1
                else -> episodesAired
            },
        )

        logger.info(
            "Nyaa search completed. queries={}, listEntries={}, deduplicatedEntries={}, validEntries={}, candidates={}",
            queries.size,
            listEntries.size,
            deduplicatedListEntries.size,
            enrichedEntries.size,
            candidates.size,
        )

        return candidates
    }

    private fun proxyInfo(): String {
        val proxy = config.proxy
        return if (proxy.enabled && proxy.host.isNotBlank()) "${proxy.type}://${proxy.host}:${proxy.port}" else "direct"
    }

    private fun buildSearchUrl(query: String, page: Int): String {
        val builder = URLBuilder("https://nyaa.si/")
        builder.parameters.append("f", "0")
        builder.parameters.append("c", "1_0")
        builder.parameters.append("q", query)
        builder.parameters.append("s", "size")
        builder.parameters.append("o", "desc")
        builder.parameters.append("p", page.toString())
        return builder.buildString()
    }

    private suspend fun loadAllListEntries(query: String): List<TorrentEntry> {
        val firstPageDocument = loadDocument(buildSearchUrl(query = query, page = 1))
        val totalPages = (firstPageDocument.select("ul.pagination li a")
            .mapNotNull { it.text().toIntOrNull() }
            .maxOrNull()
            ?: 1)

        return buildList {
            addAll(parseListEntries(firstPageDocument))
            for (page in 2..totalPages.coerceAtMost(2)) {
                val pageDocument = loadDocument(buildSearchUrl(query = query, page = page))
                addAll(parseListEntries(pageDocument))
            }
        }
    }

    private suspend fun loadDocument(url: String): Document {
        val html = searchCache.getOrPut(
            key = "nyaa:$url",
            ttlDays = config.cache.nyaaCacheTtlDays,
            serializer = { it },
            deserializer = { it },
        ) {
            client.executeWithProxyFallback(directClient) {
                RequestLogger.logRequest(url, via = proxyInfo())
                get(url).bodyAsText()
            }
        }
        return Jsoup.parse(html, url)
    }

    private fun parseListEntries(document: Document) = document.select("table.torrent-list tbody tr").mapNotNull {
        val cells = it.select("> td")

        val category = cells[0].selectFirst("a[title]")!!.attr("title").removePrefix("Anime - ")
        val nameAnchor = cells[1].select("a[href*=/view/]").last()!!

        val displayName = nameAnchor.attr("title").trim()

        val detailsLink = nameAnchor.absUrl("href").absolutizeNyaaUrl()
        val id = detailsLink.substringAfterLast('/').substringBefore('#')

        val magnetLink = cells[2].selectFirst("a[href^=magnet:]")!!.attr("href")
        val totalSizeBytes = cells[3].text().parseSizeToBytes()
        val uploadedAt = cells[4].attr("data-timestamp").toLong().let(Instant::ofEpochSecond)
        val seeders = cells[5].text().toInt()
        val totalDownloads = cells[7].text().toInt()

        TorrentEntry(
            id = id,
            displayName = displayName,
            nyaaCategory = category,
            detailsLink = detailsLink,
            magnetLink = magnetLink,
            metadata = parseTorrentMetadata(displayName),
            totalSizeBytes = totalSizeBytes,
            seeders = seeders,
            totalDownloads = totalDownloads,
            uploadedAt = uploadedAt,
        )
    }

    private suspend fun enrichWithDetails(entry: TorrentEntry, isMovie: Boolean): TorrentEntry? {
        val detailsDocument = loadDocument(entry.detailsLink)
        val episodeMediaFiles = parseEpisodeMediaFiles(detailsDocument, isMovie = isMovie)
        if (episodeMediaFiles.isEmpty())
            return null

        val filesByEpisode = episodeMediaFiles
            .groupBy(EpisodeMediaFile::episodeNumber)
            .mapValues { (_, files) -> files.maxByOrNull { it.sizeBytes } ?: files.first() }

        val resolvedCategory = entry.metadata.category.resolveCategory(filesByEpisode.size)

        return entry.copy(
            metadata = entry.metadata.copy(category = resolvedCategory),
            episodeMediaFiles = filesByEpisode,
        )
    }

    private fun parseEpisodeMediaFiles(document: Document, isMovie: Boolean): List<EpisodeMediaFile> {
        val mediaFiles = buildList {
            document.select("div.torrent-file-list > ul").forEach { root ->
                root.select("> li").forEach { element ->
                    collectMediaFiles(
                        entryElement = element,
                        pathPrefix = emptyList(),
                        destination = this,
                    )
                }
            }
        }

        val numberedFiles = mediaFiles.mapNotNull { mediaFile ->
            mediaFile.episodeNumber?.let { episodeNumber ->
                EpisodeMediaFile(
                    episodeNumber = episodeNumber,
                    fileName = mediaFile.fileName,
                    fullPathInTorrent = mediaFile.fullPathInTorrent,
                    sizeBytes = mediaFile.sizeBytes,
                )
            }
        }
        if (numberedFiles.isNotEmpty() || !isMovie) {
            return numberedFiles
        }

        val movieFile = mediaFiles.singleOrNull() ?: return emptyList()
        return listOf(
            EpisodeMediaFile(
                episodeNumber = 1,
                fileName = movieFile.fileName,
                fullPathInTorrent = movieFile.fullPathInTorrent,
                sizeBytes = movieFile.sizeBytes,
            )
        )
    }

    private fun collectMediaFiles(
        entryElement: Element,
        pathPrefix: List<String>,
        destination: MutableList<ParsedMediaFile>,
    ) {
        if (entryElement.selectFirst("> i.fa-file") != null) {
            val fileName = entryElement.ownText().replace(whitespaceRegex, " ").trim()
            if (fileName.isBlank()) {
                return
            }

            val lowerName = fileName.lowercase()
            if (!lowerName.endsWith(".mkv") && !lowerName.endsWith(".mp4")) {
                return
            }

            val fullPath = (pathPrefix + fileName).joinToString("/")
            if (fullPath.containsSpecialsMarker()) {
                return
            }

            val sizeText = entryElement.selectFirst("span.file-size")!!.text().removePrefix("(").removeSuffix(")")

            destination += ParsedMediaFile(
                episodeNumber = fileName.extractEpisodeNumber(),
                fileName = fileName,
                fullPathInTorrent = fullPath,
                sizeBytes = sizeText.parseSizeToBytes(),
            )
            return
        }

        val folderName = entryElement.selectFirst("> a.folder")
            ?.ownText()
            ?.replace(whitespaceRegex, " ")
            ?.trim()
            ?.takeIf(String::isNotBlank)
        val nextPrefix = if (folderName != null) {
            pathPrefix + folderName
        } else {
            pathPrefix
        }

        val childList = entryElement.selectFirst("> ul") ?: return
        childList.select("> li").forEach { child ->
            collectMediaFiles(
                entryElement = child,
                pathPrefix = nextPrefix,
                destination = destination,
            )
        }
    }

    private fun buildCandidates(entries: List<TorrentEntry>, episodesAired: Int): List<TorrentCandidate> {
        val seasonCandidates = mutableListOf<TorrentCandidate.SeasonPack>()
        val singleEpisodeBuckets = linkedMapOf<String, MutableList<TorrentEntry>>()

        for (entry in entries) {
            val effectiveCategory = entry.metadata.category.resolveCategory(entry.episodeMediaFiles.size)

            if (effectiveCategory == TorrentCategory.SEASON_PACK) {
                val sortedEpisodes = entry.episodeMediaFiles.keys.sorted()
                seasonCandidates += TorrentCandidate.SeasonPack(
                    entry = entry.copy(
                        metadata = entry.metadata.copy(category = TorrentCategory.SEASON_PACK),
                        totalSizeBytes = entry.episodeMediaFiles.values.sumOf { it.sizeBytes }
                    ),
                    episodes = sortedEpisodes,
                )
                continue
            }

            val episodeNumber = entry.episodeMediaFiles.keys.firstOrNull() ?: entry.metadata.episodeNumber ?: continue
            val singleEpisodeEntry = entry.copy(metadata = entry.metadata.copy(episodeNumber = episodeNumber))
            val profile = buildGroupProfile(singleEpisodeEntry)
            val key = buildGroupKey(profile)
            singleEpisodeBuckets.getOrPut(key) { mutableListOf() }.add(singleEpisodeEntry)
        }

        val groupedCandidates = singleEpisodeBuckets.mapNotNull { (groupKey, groupEntries) ->
            val sortedEntries = groupEntries.sortedWith(
                compareByDescending<TorrentEntry> { it.seeders }
                    .thenByDescending { it.totalDownloads }
                    .thenByDescending { it.totalSizeBytes }
                    .thenBy { it.displayName.lowercase() },
            )

            val episodes = linkedMapOf<Int, TorrentEntry>()
            for (entry in sortedEntries) {
                val episodeNumber = entry.metadata.episodeNumber ?: continue
                episodes.putIfAbsent(episodeNumber, entry)
            }

            if (episodes.isEmpty()) {
                return@mapNotNull null
            }

            val first = sortedEntries.first()
            val profile = buildGroupProfile(first)
            val displayName = buildGroupDisplayName(sortedEntries, first.displayName)
            val sortedEpisodes = episodes.toSortedMap()

            TorrentCandidate.EpisodeGroup(
                group = TorrentEpisodeGroup(
                    groupKey = groupKey,
                    displayName = displayName,
                    profile = profile,
                    episodes = sortedEpisodes,
                ),
                episodes = sortedEpisodes.keys.toList(),
            )
        }

        return (seasonCandidates + groupedCandidates).sortedWith(
            compareByDescending<TorrentCandidate> { it.episodes.size == episodesAired }
                .thenByDescending { it.candidateSeasonSizeBytes() }
        )
    }

    private fun buildGroupProfile(entry: TorrentEntry): TorrentGroupProfile {
        val metadata = entry.metadata
        return TorrentGroupProfile(
            normalizedTitle = normalizeGroupTitle(
                value = metadata.normalizedTitle,
                episodeNumber = metadata.episodeNumber,
            ),
            season = metadata.season,
            resolution = metadata.resolution,
            source = metadata.source,
            videoCodec = metadata.videoCodec,
            audioCodec = metadata.audioCodec,
        )
    }

    private fun buildGroupKey(profile: TorrentGroupProfile): String {
        val videoCodec = profile.videoCodec ?: ""
        val audioCodec = profile.audioCodec ?: ""
        return listOf(
            profile.normalizedTitle,
            profile.season?.toString().orEmpty(),
            profile.resolution.orEmpty(),
            profile.source.orEmpty(),
            videoCodec,
            audioCodec,
        ).joinToString("|").lowercase()
    }

    private fun buildGroupDisplayName(sortedEntries: List<TorrentEntry>, fallbackName: String): String {
        val mergedDisplayName = sortedEntries.asSequence()
            .map(TorrentEntry::displayName)
            .fold(null as String?) { commonName, displayName ->
                when (commonName) {
                    null -> displayName
                    else -> longestCommonCharSequence(commonName, displayName)
                }
            }
            ?.replace(whitespaceRegex, " ")
            ?.trim()
            .orEmpty()

        return when {
            mergedDisplayName.isBlank() -> fallbackName
            else -> mergedDisplayName
        }
    }

    private fun longestCommonCharSequence(first: String, second: String): String {
        if (first.isEmpty() || second.isEmpty()) {
            return ""
        }

        val matrix = Array(first.length + 1) { IntArray(second.length + 1) }
        for (i in first.indices) {
            for (j in second.indices) {
                matrix[i + 1][j + 1] = when {
                    first[i].equals(second[j], ignoreCase = true) -> matrix[i][j] + 1
                    else -> maxOf(matrix[i][j + 1], matrix[i + 1][j])
                }
            }
        }

        val result = StringBuilder(matrix[first.length][second.length])
        var i = first.length
        var j = second.length

        while (i > 0 && j > 0) {
            when {
                first[i - 1].equals(second[j - 1], ignoreCase = true) -> {
                    result.append(first[i - 1])
                    i--
                    j--
                }

                matrix[i - 1][j] >= matrix[i][j - 1] -> i--
                else -> j--
            }
        }

        return result.reverse().toString()
    }

    private fun normalizeGroupTitle(value: String, episodeNumber: Int?): String {
        val withoutExtension = value.replace(mediaExtensionRegex, " ")
        val withoutEpisodeTail = withoutExtension.replace(groupTitleEpisodeTailRegex, " ")
        val withoutEpisodeToken = when (episodeNumber) {
            null -> withoutEpisodeTail
            else -> withoutEpisodeTail.removeEpisodeToken(episodeNumber)
        }
        return withoutEpisodeToken.replace(whitespaceRegex, " ").trim()
    }

    private fun String.removeEpisodeToken(episodeNumber: Int): String {
        val escapedEpisode = Regex.escape(episodeNumber.toString())
        val episodeTokenRegex = Regex(
            """(?i)(?<=[\s._\-(\[])""" +
                """(?:EP|E|EPISODE)?\s*0*$escapedEpisode(?:V\d+)?""" +
                """(?=[\s._\-)\]]|$)"""
        )
        return replace(episodeTokenRegex, " ")
    }

    private fun TorrentCandidate.candidateSeasonSizeBytes() = when (this) {
        is TorrentCandidate.SeasonPack -> this.entry.episodeMediaFiles.values.sumOf(EpisodeMediaFile::sizeBytes)
        is TorrentCandidate.EpisodeGroup -> this.group.episodes.values.sumOf { it.totalSizeBytes }
    }

    private data class ParsedMediaFile(
        val episodeNumber: Int? = null,
        val fileName: String,
        val fullPathInTorrent: String,
        val sizeBytes: Long,
    )

    private fun parseTorrentMetadata(displayName: String): ParsedTorrentMetadata {
        val upper = displayName.uppercase()
        val season = displayName.extractSeason()
        val episodeNumber = displayName.extractEpisodeNumber()
        val hasEpisodeRange = episodeRangeRegex.containsMatchIn(displayName)
        val hasBatchMarkers = batchMarkersRegex.containsMatchIn(displayName)
        val category = when {
            episodeNumber != null && !hasEpisodeRange && !hasBatchMarkers -> TorrentCategory.SINGLE_EPISODE
            else -> TorrentCategory.SEASON_PACK
        }

        return ParsedTorrentMetadata(
            normalizedTitle = displayName.normalizeTitle(),
            season = season,
            episodeNumber = if (category == TorrentCategory.SINGLE_EPISODE) episodeNumber else null,
            resolution = displayName.extractResolution(),
            source = sourceTags.firstOrNull(upper::contains),
            videoCodec = videoCodecTags.firstOrNull(upper::contains),
            audioCodec = audioCodecTags.firstOrNull(upper::contains),
            category = category,
        )
    }

    private fun String.normalizeTitle(): String {
        val withoutBrackets = replace(squareBracketsContentRegex, " ")
            .replace(parenthesesContentRegex, " ")
            .replace("|", " ")

        val cleaned = withoutBrackets
            .replace(mediaExtensionRegex, " ")
            .replace(resolutionRegex, " ")
            .replace(seasonEpisodeRegex, " ")
            .replace(explicitEpisodeRegex, " ")
            .replace(episodeRangeRegex, " ")
            .replace(technicalTagsRegex, " ")
            .replace(whitespaceRegex, " ")
            .trim()

        return cleaned.ifBlank { this }.lowercase()
    }

    private fun String.extractResolution() = extractResolutionRegex.find(this)
        ?.groupValues?.getOrNull(1)?.uppercase()

    private fun String.extractSeason() = extractSeasonPatterns.firstNotNullOfOrNull { pattern ->
        pattern.find(this)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun String.extractEpisodeNumber() = extractEpisodePatterns.asSequence()
        .mapNotNull { pattern -> pattern.find(this)?.groupValues?.getOrNull(1)?.toIntOrNull() }
        .firstOrNull { number -> number in 1..999 }

    private fun String.containsSpecialsMarker(): Boolean {
        val normalized = uppercase().replace(nonAlphaNumericRegex, " ")
        return specialsBlacklist.any { regex -> regex.containsMatchIn(normalized) }
    }

    private fun String.parseSizeToBytes(): Long {
        val match = torrentSizeRegex.find(this)!!
        val amount = match.groupValues[1].toDouble()
        val unit = match.groupValues[2].uppercase()

        val multiplier = when (unit) {
            "KB", "KIB" -> 1024L
            "MB", "MIB" -> 1024L * 1024L
            "GB", "GIB" -> 1024L * 1024L * 1024L
            "TB", "TIB" -> 1024L * 1024L * 1024L * 1024L
            else -> 1L
        }

        return (amount * multiplier).roundToLong()
    }

    private fun String.absolutizeNyaaUrl() = when {
        this.startsWith("http") -> this
        else -> "https://nyaa.si${this}"
    }

    private fun TorrentCategory.resolveCategory(episodeFilesCount: Int) = when {
        episodeFilesCount > 1 -> TorrentCategory.SEASON_PACK
        else -> this
    }
}
