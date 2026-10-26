package com.example.scraper

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

interface HianimeHttpClient {
    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): String
}

class DefaultHianimeHttpClient(
    private val userAgent: String,
    private val connectTimeout: Duration = Duration.ofSeconds(10),
    private val readTimeout: Duration = Duration.ofSeconds(15),
) : HianimeHttpClient {
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(connectTimeout)
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    override suspend fun get(url: String, headers: Map<String, String>): String {
        val requestBuilder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(readTimeout)
            .header("User-Agent", userAgent)
            .header("Accept", "*/*")

        headers.forEach { (key, value) -> requestBuilder.header(key, value) }

        val response = try {
            client.send(requestBuilder.GET().build(), HttpResponse.BodyHandlers.ofString())
        } catch (error: Exception) {
            throw HianimeScraperException("Request failed for $url", error)
        }

        val status = response.statusCode()
        if (status !in 200..299) {
            throw HianimeHttpException(url, status, "Request failed")
        }

        return response.body()
    }
}
