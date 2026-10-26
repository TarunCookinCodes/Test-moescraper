package com.example.scraper

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64

class HianimeScraper(
    private val config: HianimeConfig = HianimeConfig(),
    private val httpClient: HianimeHttpClient = DefaultHianimeHttpClient(
        userAgent = config.userAgent,
        allowedRedirectHosts = config.allowedRedirectHosts,
    ),
) {
    suspend fun search(query: String): List<AnimeSearchResult> {
        val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8)
        val url = config.searchEndpoint.format(encoded)
        val page = fetch(url)
        val document = Jsoup.parse(page, config.baseUrl)

        return document
            .select("h3.film-name a[title]")
            .mapNotNull { anchor ->
                val title = anchor.attr("title").trim()
                val href = anchor.absUrl("href").ifBlank { anchor.attr("href") }
                val slug = href.substringAfterLast('/').substringBefore('?').substringBefore('#').trim()
                if (title.isBlank() || slug.isBlank()) {
                    null
                } else {
                    val filmDetail = anchor.closest(".flw-item")
                    val posterUrl = filmDetail?.selectFirst("img[data-src],img[src]")?.let { image ->
                        image.absUrl("data-src").ifBlank {
                            image.absUrl("src").ifBlank { image.attr("src") }
                        }
                    }
                    AnimeSearchResult(
                        slug = slug,
                        title = org.jsoup.parser.Parser.unescapeEntities(title, false),
                        href = href,
                        posterUrl = posterUrl,
                        rawAttributes = mapOf("href" to href),
                    )
                }
            }
            .distinctBy { it.slug }
    }

    suspend fun episodes(animeSlug: String): List<EpisodeInfo> {
        val slugSuffix = animeSlug.substringAfterLast('-')
        val endpoint = config.episodesEndpoint.format(slugSuffix)
        val responseBody = fetch(endpoint)
        val episodeHtml = extractHtmlPayload(responseBody)
            ?: throw HianimeParseException("Could not find episode list HTML")

        return Jsoup.parseBodyFragment(episodeHtml)
            .select(".ep-item[data-id][data-number]")
            .mapNotNull { item ->
                val number = item.attr("data-number").trim()
                val id = item.attr("data-id").trim()
                if (number.isBlank() || id.isBlank()) {
                    null
                } else {
                    val title = item.attr("title").ifBlank { "Episode $number" }
                    EpisodeInfo(
                        episodeId = id,
                        number = number,
                        title = title,
                        rawAttributes = item.attributes().associate { it.key to it.value },
                    )
                }
            }
            .sortedBy { it.number.toDoubleOrNull() ?: Double.MAX_VALUE }
    }

    suspend fun streamForEpisode(
        episodeId: String,
        mode: StreamMode = StreamMode.SUB,
        qualityPreference: String = "best",
    ): EpisodeStream {
        val servers = serversForEpisode(episodeId)
        val preferredServer = config.preferredServerNames
            .firstNotNullOfOrNull { preferred ->
                servers.firstOrNull { it.type == mode && it.serverName.equals(preferred, ignoreCase = true) }
            }
        val selectedServer = preferredServer
            ?: servers.firstOrNull { it.type == mode }
            ?: throw HianimeNotFoundException("No servers available for mode=$mode")

        val embedUrl = decodeBase64(selectedServer.hash)
            ?: throw HianimeParseException("Could not decode server hash to embed URL")
        val referer = extractOrigin(embedUrl)

        val embedPage = fetch(embedUrl)
        val payloadBlob = extractWindowPayload(embedPage)
            ?: throw HianimeParseException("window.__P payload not found in embed page")

        val payloadJsonText = deobfuscatePayload(payloadBlob)
        val payloadJson = parseJsonObject(payloadJsonText)

        val masterUrl = extractMasterPlaylistUrl(payloadJson)
            ?: throw HianimeNotFoundException("No .m3u8 master playlist found")

        val subtitles = extractSubtitleTracks(payloadJson)

        val playlist = fetch(masterUrl, mapOf("Referer" to referer))
        val variants = parseMasterPlaylist(masterUrl, playlist)
        if (variants.isEmpty()) {
            throw HianimeNotFoundException("No quality variants found in master playlist")
        }

        val selectedVariant = selectQualityVariant(variants, qualityPreference)
        val audioUrl = extractAudioTrack(masterUrl, playlist)

        return EpisodeStream(
            selected = selectedVariant,
            variants = variants,
            subtitles = subtitles,
            audioUrl = audioUrl,
            referer = referer,
            masterPlaylistUrl = masterUrl,
            embedUrl = embedUrl,
            rawPayloadJson = payloadJsonText,
        )
    }

    suspend fun serversForEpisode(episodeId: String): List<EpisodeServer> {
        val url = config.serversEndpoint.format(episodeId)
        val responseBody = fetch(url)
        val serversHtml = extractHtmlPayload(responseBody)
            ?: throw HianimeParseException("Could not find server list HTML")

        return Jsoup.parseBodyFragment(serversHtml)
            .select(".server-item[data-type][data-server-name][data-hash]")
            .mapNotNull { item ->
                val type = when (item.attr("data-type").trim().lowercase()) {
                    "dub" -> StreamMode.DUB
                    "sub" -> StreamMode.SUB
                    else -> null
                } ?: return@mapNotNull null

                val name = item.attr("data-server-name").trim()
                val hash = item.attr("data-hash").trim()
                if (name.isBlank() || hash.isBlank()) {
                    null
                } else {
                    EpisodeServer(
                        serverName = name,
                        type = type,
                        hash = hash,
                        rawAttributes = item.attributes().associate { it.key to it.value },
                    )
                }
            }
    }

    fun deobfuscatePayload(base64Blob: String): String {
        val decoded = try {
            Base64.getDecoder().decode(base64Blob)
        } catch (error: IllegalArgumentException) {
            throw HianimeParseException("Invalid base64 payload blob", error)
        }

        val key = "otaku-embed-v1".toByteArray()
        val output = ByteArray(decoded.size)
        decoded.forEachIndexed { index, byte ->
            output[index] = (byte.toInt() xor key[index % key.size].toInt()).toByte()
        }
        return output.toString(Charsets.UTF_8)
    }

    fun parseMasterPlaylist(masterUrl: String, body: String): List<QualityVariant> {
        val lines = body.lines()
        val variants = mutableListOf<QualityVariant>()

        var index = 0
        while (index < lines.size) {
            val line = lines[index].trim()
            if (line.startsWith("#EXT-X-STREAM-INF:")) {
                val resolution = Regex("RESOLUTION=\\d+x(\\d+)").find(line)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: -1
                val qualityLabel = if (resolution > 0) "${resolution}p" else "auto"
                val urlLine = lines.getOrNull(index + 1)?.trim().orEmpty()
                if (urlLine.isNotBlank() && !urlLine.startsWith("#")) {
                    val resolvedUrl = resolveUrl(masterUrl, urlLine)
                    variants += QualityVariant(qualityLabel, resolution, resolvedUrl)
                }
            }
            index += 1
        }

        return variants
            .distinctBy { it.url }
            .sortedByDescending { it.height }
    }

    fun selectQualityVariant(variants: List<QualityVariant>, qualityPreference: String): QualityVariant {
        if (variants.isEmpty()) {
            throw HianimeNotFoundException("No variants available")
        }

        val preference = qualityPreference.trim().lowercase()
        return when (preference) {
            "best", "" -> variants.maxByOrNull { it.height } ?: variants.first()
            "worst" -> variants.minByOrNull { it.height } ?: variants.last()
            else -> {
                val normalized = preference.removeSuffix("p")
                variants.firstOrNull { it.qualityLabel.lowercase().contains(preference) || it.height.toString() == normalized }
                    ?: (variants.maxByOrNull { it.height } ?: variants.first())
            }
        }
    }

    fun extractWindowPayload(embedPage: String): String? {
        val doubleQuote = Regex("window\\.__P\\s*=\\s*\"([^\"]+)\"")
        val singleQuote = Regex("window\\.__P\\s*=\\s*'([^']+)'")
        return doubleQuote.find(embedPage)?.groupValues?.getOrNull(1)
            ?: singleQuote.find(embedPage)?.groupValues?.getOrNull(1)
    }

    private fun extractAudioTrack(masterUrl: String, body: String): String? {
        val audioLine = body
            .lineSequence()
            .map(String::trim)
            .firstOrNull { it.startsWith("#EXT-X-MEDIA:") && it.contains("TYPE=AUDIO") && it.contains("DEFAULT=YES") }
            ?: return null

        val uri = Regex("URI=\"([^\"]+)\"").find(audioLine)?.groupValues?.getOrNull(1) ?: return null
        return resolveUrl(masterUrl, uri)
    }

    private fun extractMasterPlaylistUrl(payload: JSONObject): String? {
        val candidates = mutableListOf<String>()
        collectStringValues(payload, candidates)
        return candidates.firstOrNull { it.contains(".m3u8") }
    }

    private fun extractSubtitleTracks(payload: JSONObject): List<SubtitleTrack> {
        val subtitlesArray = payload.optJSONArray("subtitles") ?: return emptyList()
        return buildList {
            for (index in 0 until subtitlesArray.length()) {
                val entry = subtitlesArray.optJSONObject(index) ?: continue
                val src = entry.optString("src", "").trim()
                if (src.isBlank()) continue
                add(
                    SubtitleTrack(
                        label = entry.optString("label", "Subtitle"),
                        url = src,
                        isDefault = entry.optBoolean("default", false),
                    ),
                )
            }
        }
    }

    private fun extractHtmlPayload(responseBody: String): String? {
        val root = parseJsonObject(responseBody)

        val directHtml = root.optString("html", "")
        if (directHtml.isNotBlank()) {
            return directHtml
        }

        val nestedHtml = root.optJSONObject("data")?.optString("html", "").orEmpty()
        if (nestedHtml.isNotBlank()) {
            return nestedHtml
        }

        val candidates = mutableListOf<String>()
        collectStringValues(root, candidates)
        return candidates.firstOrNull { it.contains("server-item") || it.contains("ep-item") }
    }

    private fun collectStringValues(value: Any?, output: MutableList<String>) {
        when (value) {
            is JSONObject -> value.keys().forEach { key -> collectStringValues(value.opt(key), output) }
            is JSONArray -> for (index in 0 until value.length()) collectStringValues(value.opt(index), output)
            is String -> output += value
        }
    }

    private fun parseJsonObject(responseBody: String): JSONObject = try {
        JSONObject(responseBody)
    } catch (error: Exception) {
        throw HianimeParseException("Failed to parse JSON response", error)
    }

    private fun extractOrigin(url: String): String {
        val uri = URI(url)
        return "${uri.scheme}://${uri.host}${if (uri.port > 0) ":${uri.port}" else ""}/"
    }

    private fun resolveUrl(base: String, relativeOrAbsolute: String): String = try {
        URI(base).resolve(relativeOrAbsolute).toString()
    } catch (_: Exception) {
        relativeOrAbsolute
    }

    private fun decodeBase64(value: String): String? = try {
        String(Base64.getDecoder().decode(value), Charsets.UTF_8)
    } catch (_: IllegalArgumentException) {
        null
    }

    private suspend fun fetch(url: String, headers: Map<String, String> = emptyMap()): String {
        val body = httpClient.get(url, headers)
        if (looksLikeSecurityBlockPage(body)) {
            val host = runCatching { URI(url).host }.getOrNull().orEmpty()
            throw HianimeUpstreamBlockedException(url, 200, "Upstream host $host returned a security-block page")
        }
        return body
    }

    private fun looksLikeSecurityBlockPage(body: String): Boolean {
        val normalized = body.lowercase()
        return normalized.contains("sorry, you have been blocked") ||
            normalized.contains("you are unable to access") ||
            normalized.contains("cloudflare ray id") ||
            normalized.contains("security service to protect itself from online attacks")
    }
}
