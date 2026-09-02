package com.example

import com.lagradost.cloudstream3.*

class MoescrapperProvider : MainAPI() {
    override var mainUrl = "https://moescrapper.netlify.app"
    override var name = "moescrapper.netlify.app"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var hasMainPage = true

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        $todo
        return newHomePageResponse(emptyList())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        $todo
        return emptyList()
    }

    override suspend fun load(url: String): LoadResponse? {
        $todo
        return null
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        $todo
        return false
    }
}
