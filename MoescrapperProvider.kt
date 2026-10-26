package com.example

import com.example.scraper.HianimeScraper
import com.example.scraper.StreamMode
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities

class MoescrapperProvider : MainAPI() {
    override var mainUrl = "https://hianime.at"
    override var name = "Moescrapper"
    override val supportedTypes = setOf(TvType.Anime)
    override var hasMainPage = false

    private val scraper = HianimeScraper()

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return newHomePageResponse(emptyList())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return scraper.search(query).map { item ->
            newAnimeSearchResponse(item.title, item.slug) {
                posterUrl = item.posterUrl
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val slug = url.substringAfterLast('/').substringBefore('?').ifBlank { url }
        val episodes = scraper.episodes(slug).map { episode ->
            newEpisode("${episode.episodeId}|sub") {
                name = episode.title
                this.episode = episode.number.toIntOrNull()
            }
        }

        return newTvSeriesLoadResponse(
            slug.replace('-', ' ').replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() },
            slug,
            TvType.Anime,
        ) {
            this.episodes = episodes
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val episodeId = data.substringBefore('|')
        val mode = if (data.substringAfter('|', "sub").equals("dub", ignoreCase = true)) StreamMode.DUB else StreamMode.SUB
        val stream = scraper.streamForEpisode(episodeId = episodeId, mode = mode, qualityPreference = "best")

        stream.subtitles.forEach { subtitle ->
            subtitleCallback(SubtitleFile(subtitle.label, subtitle.url))
        }

        stream.variants.forEach { variant ->
            callback(
                ExtractorLink(
                    source = name,
                    name = "$name ${variant.qualityLabel}",
                    url = variant.url,
                    referer = stream.referer,
                    quality = variant.height.takeIf { it > 0 } ?: Qualities.Unknown.value,
                    isM3u8 = true,
                ),
            )
        }

        stream.audioUrl?.let { audioUrl ->
            callback(
                ExtractorLink(
                    source = name,
                    name = "$name Audio",
                    url = audioUrl,
                    referer = stream.referer,
                    quality = Qualities.Unknown.value,
                    isM3u8 = true,
                ),
            )
        }

        return true
    }
}
