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
    private val allowedRedirectHosts: Set<String> = emptySet(),
    private val connectTimeout: Duration = Duration.ofSeconds(10),
    private val readTimeout: Duration = Duration.ofSeconds(15),
    private val maxRedirects: Int = 5,
) : HianimeHttpClient {
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(connectTimeout)
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    override suspend fun get(url: String, headers: Map<String, String>): String {
        var currentUrl = url
        repeat(maxRedirects + 1) { redirectCount ->
            val requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(currentUrl))
                .timeout(readTimeout)
                .header("User-Agent", userAgent)
                .header("Accept", "*/*")

            headers.forEach { (key, value) -> requestBuilder.header(key, value) }

            val response = try {
                client.send(requestBuilder.GET().build(), HttpResponse.BodyHandlers.ofString())
            } catch (error: Exception) {
                throw HianimeScraperException("Request failed for $currentUrl", error)
            }

            val status = response.statusCode()
            val body = response.body()
            val host = extractHost(currentUrl)

            if (status in setOf(403, 429, 503)) {
                throw HianimeUpstreamBlockedException(currentUrl, status, "Upstream host $host rejected the request")
            }

            if (status in 300..399) {
                if (redirectCount >= maxRedirects) {
                    throw HianimeHttpException(currentUrl, status, "Too many redirects")
                }
                val location = response.headers().firstValue("location").orElse("")
                if (location.isBlank()) {
                    throw HianimeHttpException(currentUrl, status, "Redirect response missing location header")
                }
                val redirectTarget = URI.create(currentUrl).resolve(location).toString()
                if (!isRedirectAllowed(currentUrl, redirectTarget)) {
                    throw HianimeUnexpectedRedirectException(currentUrl, redirectTarget)
                }
                currentUrl = redirectTarget
                return@repeat
            }

            if (status !in 200..299) {
                throw HianimeHttpException(currentUrl, status, "Request failed")
            }

            if (looksLikeSecurityBlockPage(body)) {
                throw HianimeUpstreamBlockedException(currentUrl, status, "Upstream host $host returned a security-block page")
            }

            return body
        }
        throw HianimeScraperException("Unexpected redirect handling failure for $url")
    }

    private fun isRedirectAllowed(fromUrl: String, toUrl: String): Boolean {
        val fromHost = extractHost(fromUrl)
        val toHost = extractHost(toUrl)
        return fromHost.equals(toHost, ignoreCase = true) || allowedRedirectHosts.any { it.equals(toHost, ignoreCase = true) }
    }

    private fun extractHost(url: String): String = try {
        URI(url).host?.lowercase().orEmpty()
    } catch (_: Exception) {
        ""
    }

    private fun looksLikeSecurityBlockPage(body: String): Boolean {
        val normalized = body.lowercase()
        return normalized.contains("sorry, you have been blocked") ||
            normalized.contains("you are unable to access") ||
            normalized.contains("cloudflare ray id") ||
            normalized.contains("attention required") && normalized.contains("cloudflare")
    }
}
