package com.example.scraper

enum class StreamMode {
    SUB,
    DUB,
}

data class HianimeConfig(
    val baseUrl: String = "https://hianime.at",
    val userAgent: String = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Safari/537.36",
) {
    val searchEndpoint: String = "$baseUrl/search?keyword=%s"
    val episodesEndpoint: String = "$baseUrl/api/theme/episode/list/%s"
    val serversEndpoint: String = "$baseUrl/api/theme/episode/servers?episodeId=%s"
}

data class AnimeSearchResult(
    val slug: String,
    val title: String,
    val href: String,
    val posterUrl: String? = null,
    val rawAttributes: Map<String, String> = emptyMap(),
)

data class EpisodeInfo(
    val episodeId: String,
    val number: String,
    val title: String,
    val rawAttributes: Map<String, String> = emptyMap(),
)

data class EpisodeServer(
    val serverName: String,
    val type: StreamMode,
    val hash: String,
    val rawAttributes: Map<String, String> = emptyMap(),
)

data class SubtitleTrack(
    val label: String,
    val url: String,
    val isDefault: Boolean,
)

data class QualityVariant(
    val qualityLabel: String,
    val height: Int,
    val url: String,
)

data class EpisodeStream(
    val selected: QualityVariant,
    val variants: List<QualityVariant>,
    val subtitles: List<SubtitleTrack>,
    val audioUrl: String?,
    val referer: String,
    val masterPlaylistUrl: String,
    val embedUrl: String,
    val rawPayloadJson: String,
)
