package com.example.scraper

open class HianimeScraperException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class HianimeHttpException(
    val url: String,
    val statusCode: Int,
    message: String,
) : HianimeScraperException("$message (url=$url, status=$statusCode)")

class HianimeParseException(message: String, cause: Throwable? = null) : HianimeScraperException(message, cause)

class HianimeNotFoundException(message: String) : HianimeScraperException(message)
