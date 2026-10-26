# Test-moescraper

HiAnime scraper plugin prototype for Cloudstream.

## Public scraper API

`com.example.scraper.HianimeScraper` exposes the scraping flow:

1. `search(query)` for anime title search results (slug/title/poster)
2. `episodes(slug)` for episode IDs and numbers
3. `streamForEpisode(episodeId, mode, qualityPreference)` for stream resolution

`streamForEpisode` resolves:
- ZokoAnime server (preferred when available)
- decoded embed URL from base64 hash
- `window.__P` payload deobfuscation (`base64 + XOR("otaku-embed-v1")`)
- HLS master playlist, subtitle tracks, audio track, and quality variants

Quality preference accepts `best`, `worst`, or an explicit value like `720p`. If unavailable, it falls back to the best variant.

## Cloudstream provider usage

The provider (`MoescrapperProvider`) now uses `HianimeScraper` for:
- search results
- episode listing
- playable HLS links/subtitles in `loadLinks`

## Testing

Focused tests are in:

`/home/runner/work/Test-moescraper/Test-moescraper/src/test/kotlin/com/example/scraper/HianimeScraperTest.kt`

They validate:
- XOR/base64 payload deobfuscation
- mocked end-to-end parsing of server list, embed payload, playlist/audio/subtitles, and quality selection
