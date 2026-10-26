package com.example.scraper

import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HianimeScraperTest {
    private val config = HianimeConfig(baseUrl = "https://example.test")

    @Test
    fun `deobfuscatePayload reverses base64 xor blob`() {
        val scraper = HianimeScraper(config = config, httpClient = FakeHttpClient(emptyMap()))
        val original = """{"src":"https://cdn.example/master.m3u8"}"""
        val obfuscated = obfuscatePayload(original)

        val decoded = scraper.deobfuscatePayload(obfuscated)

        assertEquals(original, decoded)
    }

    @Test
    fun `streamForEpisode parses servers payload and playlist variants`() {
        val episodeId = "999"
        val embedUrl = "https://embed.example/mal/1001/episode"
        val hash = Base64.getEncoder().encodeToString(embedUrl.toByteArray())

        val payloadJson = """
            {
              "src": "https://cdn.example/master.m3u8",
              "subtitles": [
                {"label":"English","src":"https://cdn.example/sub-en.vtt","default":true}
              ]
            }
        """.trimIndent()
        val payloadBlob = obfuscatePayload(payloadJson)

        val serversResponse = """{"html":"<div class=\\"server-item\\" data-type=\\"sub\\" data-server-name=\\"ZokoAnime\\" data-hash=\\"$hash\\"></div>"}"""
        val embedResponse = """<html><script>window.__P=\"$payloadBlob\";</script></html>"""
        val playlist = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="English",DEFAULT=YES,AUTOSELECT=YES,URI="audio-default.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=600000,RESOLUTION=640x360
            360p/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=1400000,RESOLUTION=1280x720
            720p/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2800000,RESOLUTION=1920x1080
            https://cdn.example/1080p/index.m3u8
        """.trimIndent()

        val responses = mapOf(
            config.serversEndpoint.format(episodeId) to serversResponse,
            embedUrl to embedResponse,
            "https://cdn.example/master.m3u8" to playlist,
        )

        val scraper = HianimeScraper(config = config, httpClient = FakeHttpClient(responses))

        val stream720 = runSuspend { scraper.streamForEpisode(episodeId, StreamMode.SUB, "720p") }
        val streamMissingQuality = runSuspend { scraper.streamForEpisode(episodeId, StreamMode.SUB, "999p") }
        val streamWorst = runSuspend { scraper.streamForEpisode(episodeId, StreamMode.SUB, "worst") }

        assertEquals("720p", stream720.selected.qualityLabel)
        assertEquals("1080p", streamMissingQuality.selected.qualityLabel)
        assertEquals("360p", streamWorst.selected.qualityLabel)
        assertEquals("https://cdn.example/audio-default.m3u8", stream720.audioUrl)
        assertEquals("https://embed.example/", stream720.referer)
        assertTrue(stream720.variants.size >= 3)
        assertNotNull(stream720.subtitles.firstOrNull())
    }

    @Test
    fun `streamForEpisode throws blocked exception for cloudflare page`() {
        val episodeId = "123"
        val blockedEmbedUrl = "https://nexabloom.top/e/blocked"
        val hash = Base64.getEncoder().encodeToString(blockedEmbedUrl.toByteArray())
        val serversResponse = """{"html":"<div class=\\"server-item\\" data-type=\\"sub\\" data-server-name=\\"ZokoAnime\\" data-hash=\\"$hash\\"></div>"}"""
        val blockedPage = """
            <html><body>
            <h1>Sorry, you have been blocked</h1>
            <p>You are unable to access nexabloom.top</p>
            <p>Cloudflare Ray ID: 1234567890abcdef</p>
            </body></html>
        """.trimIndent()

        val scraper = HianimeScraper(
            config = config,
            httpClient = FakeHttpClient(
                mapOf(
                    config.serversEndpoint.format(episodeId) to serversResponse,
                    blockedEmbedUrl to blockedPage,
                ),
            ),
        )

        val error = assertFailsWith<HianimeUpstreamBlockedException> {
            runSuspend { scraper.streamForEpisode(episodeId, StreamMode.SUB, "best") }
        }
        assertTrue(error.message.orEmpty().contains("blocked", ignoreCase = true))
        assertTrue(error.message.orEmpty().contains("provider/source", ignoreCase = true))
    }

    private fun obfuscatePayload(input: String): String {
        val key = "otaku-embed-v1".encodeToByteArray()
        val bytes = input.toByteArray()
        val encoded = ByteArray(bytes.size)
        bytes.forEachIndexed { index, byte ->
            encoded[index] = (byte.toInt() xor key[index % key.size].toInt()).toByte()
        }
        return Base64.getEncoder().encodeToString(encoded)
    }
}

private class FakeHttpClient(
    private val responses: Map<String, String>,
) : HianimeHttpClient {
    override suspend fun get(url: String, headers: Map<String, String>): String {
        return responses[url] ?: throw AssertionError("Missing fixture for URL: $url")
    }
}

private fun <T> runSuspend(block: suspend () -> T): T {
    var result: Result<T>? = null
    val latch = CountDownLatch(1)

    block.startCoroutine(object : Continuation<T> {
        override val context: CoroutineContext = EmptyCoroutineContext

        override fun resumeWith(resumeResult: Result<T>) {
            result = resumeResult
            latch.countDown()
        }
    })

    check(latch.await(5, TimeUnit.SECONDS)) { "Timed out waiting for coroutine completion" }
    return result!!.getOrThrow()
}
