package com.example.scraper

open class HianimeScraperException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class HianimeHttpException(
    val url: String,
    val statusCode: Int,
    message: String,
) : HianimeScraperException("$message (url=$url, status=$statusCode)")

class HianimeParseException(message: String, cause: Throwable? = null) : HianimeScraperException(message, cause)

class HianimeNotFoundException(message: String) : HianimeScraperException(message)

class HianimeUpstreamBlockedException(
    val url: String,
    val statusCode: Int?,
    message: String,
) : HianimeScraperException(
        "$message (url=$url${statusCode?.let { ", status=$it" } ?: ""}). " +
            "The upstream host blocked this request; try another configured provider/source.",
    )

class HianimeUnexpectedRedirectException(
    val fromUrl: String,
    val toUrl: String,
) : HianimeScraperException(
        "Blocked redirect from $fromUrl to unexpected host $toUrl. " +
            "Configure allowed redirect hosts explicitly if this host is trusted.",
    )
