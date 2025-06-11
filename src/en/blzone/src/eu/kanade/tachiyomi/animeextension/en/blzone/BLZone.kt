package eu.kanade.tachiyomi.animeextension.en.blzone

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.lib.mixdropextractor.MixDropExtractor
import eu.kanade.tachiyomi.lib.vidguardextractor.VidguardExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.await
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element

class BLZone : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "BLZone"
    override val baseUrl = "https://blzone.net"
    override val lang = "en"
    override val supportsLatest = true

    // ---- FILTERS ----
    private enum class Type(val path: String, val display: String) {
        ANIME("anime", "Anime"),
        DRAMA("dorama", "Drama"),
        BOTH("", "Both")
    }

    private class TypeFilter : AnimeFilter.Select<String>(
        "Type", arrayOf(Type.BOTH.display, Type.ANIME.display, Type.DRAMA.display)
    )

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(TypeFilter())

    private fun getTypeFromFilters(filters: AnimeFilterList): Type {
        val typeIndex = (filters.getOrNull(0) as? AnimeFilter.Select<*>)?.state ?: 0
        return when (typeIndex) {
            1 -> Type.ANIME
            2 -> Type.DRAMA
            else -> Type.BOTH
        }
    }

    // ---- POPULAR ----
    override fun popularAnimeRequest(page: Int): Request {
        return GET("$baseUrl/trending/", headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animeList = mutableListOf<SAnime>()
        animeList += document.select("#dt-tvshows .item.tvshows").map { popularAnimeFromElement(it) }
        animeList += document.select("#dt-movies .item.tvshows").map { popularAnimeFromElement(it) }
        return AnimesPage(animeList, hasNextPage = false)
    }

    override fun popularAnimeSelector(): String =
        "#dt-tvshows .item.tvshows, #dt-movies .item.tvshows"

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        val poster = element.selectFirst(".poster")
        val link = poster?.selectFirst("a")?.attr("href") ?: ""
        val img = poster?.selectFirst("img")
        anime.title = img?.attr("alt") ?: element.selectFirst("h3 a")?.text() ?: "No title"
        anime.thumbnail_url = img?.attr("src")
        anime.setUrlWithoutDomain(link)
        return anime
    }

    // ---- LATEST ----
    override fun latestUpdatesRequest(page: Int): Request {
        val animePageUrl = if (page == 1) "$baseUrl/anime/" else "$baseUrl/anime/page/$page/"
        return GET(animePageUrl, headers)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animeList = mutableListOf<SAnime>()
        animeList += document.select(".items.full .item.tvshows").map { latestAnimeFromElement(it) }

        // Only fetch dorama for first page (to avoid duplicate pagination issues)
        if (response.request.url.encodedPath.endsWith("/anime/")) {
            try {
                val dramaResponse = client.newCall(GET("$baseUrl/dorama/", headers)).execute()
                val dramaDoc = dramaResponse.asJsoup()
                animeList += dramaDoc.select(".items.full .item.tvshows").map { latestAnimeFromElement(it) }
            } catch (_: Exception) {
                // Ignore errors on drama
            }
        }

        return AnimesPage(animeList, hasNextPage = hasNextPage(document))
    }

    override fun latestUpdatesSelector(): String = ".items.full .item.tvshows"

    override fun latestAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun latestUpdatesFromElement(element: Element): SAnime = latestAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector(): String? = ".pagination .next:not(.disabled)"

    private fun hasNextPage(document: org.jsoup.nodes.Document): Boolean {
        return document.selectFirst(".pagination .next:not(.disabled)") != null
    }

    // ---- SEARCH ----
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val type = getTypeFromFilters(filters)
        val q = query.trim()
        return when (type) {
            Type.ANIME -> GET("$baseUrl/anime/?s=$q", headers)
            Type.DRAMA -> GET("$baseUrl/dorama/?s=$q", headers)
            Type.BOTH  -> GET("$baseUrl/?s=$q", headers)
        }
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animeList = document.select(".search-page .result-item article").map { searchAnimeFromElement(it) }
        return AnimesPage(animeList, hasNextPage = false)
    }

    override fun searchAnimeSelector(): String = ".search-page .result-item article"

    override fun searchAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        val img = element.selectFirst(".thumbnail img")
        val link = element.selectFirst(".thumbnail a")?.attr("href") ?: ""
        anime.title = img?.attr("alt") ?: element.selectFirst(".title a")?.text() ?: "No title"
        anime.thumbnail_url = img?.attr("src")
        anime.setUrlWithoutDomain(link)
        return anime
    }

    // ---- DETAILS ----
    override fun animeDetailsParse(document: org.jsoup.nodes.Document): SAnime {
        val anime = SAnime.create()
        val poster = document.selectFirst(".sheader .poster img")
        anime.title = document.selectFirst(".sheader .data h1")?.text() ?: poster?.attr("alt") ?: ""
        anime.thumbnail_url = poster?.attr("src")
        anime.genre = document.select(".sheader .sgeneros a").joinToString { it.text() }
        anime.description = document.selectFirst(".sbox .wp-content p")?.text() ?: ""
        val altTitle = document.selectFirst(".custom_fields b.variante:contains(Original Title) + span.valor")?.text()
        if (altTitle != null && altTitle.isNotBlank()) {
            anime.description += "\n\nOriginal Title: $altTitle"
        }
        return anime
    }

    // ---- EPISODES ----
    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        return document.select("#episodes ul.episodios2 > li").map { episodeFromElement(it) }
    }

    override fun episodeFromElement(element: Element): SEpisode {
        val ep = SEpisode.create()
        val link = element.selectFirst(".episodiotitle a")?.attr("href") ?: ""
        ep.setUrlWithoutDomain(link)
        ep.name = element.selectFirst(".episodiotitle a")?.text() ?: "Episode"
        val episodeNum = Regex("""Episode (\d+)""", RegexOption.IGNORE_CASE).find(ep.name!!)?.groupValues?.getOrNull(1)
        ep.episode_number = episodeNum?.toFloatOrNull() ?: 1f
        ep.date_upload = 0L
        return ep
    }

    // ---- VIDEOS ----
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val streamtapeExtractor by lazy { StreamTapeExtractor(client) }
    private val mixDropExtractor by lazy { MixDropExtractor(client) }
    private val vidguardExtractor by lazy { VidguardExtractor(client) }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val serverNames = document.select("#playeroptionsul li span.title").map { it.text().trim().lowercase() }
        val serverBoxes = document.select(".dooplay_player .source-box").drop(1)

        val videos = mutableListOf<BLZoneRawVideo>()

        serverBoxes.forEachIndexed { index, box ->
            val iframe = box.selectFirst("iframe.metaframe")
            val src = iframe?.attr("src")?.trim().orEmpty()
            if (src.isNotBlank()) {
                val name = serverNames.getOrNull(index) ?: "server${index + 1}"
                val videoUrl = if (src.contains("/diclaimer/?url=")) {
                    java.net.URLDecoder.decode(src.substringAfter("/diclaimer/?url="), "UTF-8")
                } else src
                videos += BLZoneRawVideo(videoUrl, name)
            }
        }
        return videos.map { Video(it.url, it.name, it.url) }
    }

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val response = client.newCall(GET(baseUrl + episode.url)).await()
        val rawVideos = videoListParse(response)

        val extractedVideos = mutableListOf<Video>()

        for (rawVideo in rawVideos) {
            val url = rawVideo.url
            val name = rawVideo.name
            when {
                url.contains("filemoon") -> {
                    extractedVideos += filemoonExtractor.videosFromUrl(url)
                }
                url.contains("streamtape") -> {
                    extractedVideos += streamtapeExtractor.videosFromUrl(url)
                }
                url.contains("mixdrop") -> {
                    extractedVideos += mixDropExtractor.videosFromUrl(url)
                }
                url.contains("vgembed") || name.contains("vidguard") -> {
                    extractedVideos += vidguardExtractor.videosFromUrl(url)
                }
                // Upnshare, p2p, zoneplay: no extractors, pass as direct
                name.contains("upnshare") || url.contains("upns") -> {
                    extractedVideos += Video(url, "Upnshare", url)
                }
                name.contains("p2p") || url.contains("p2p") -> {
                    extractedVideos += Video(url, "P2P", url)
                }
                name.contains("zoneplay") || url.contains("zoneplay") -> {
                    extractedVideos += Video(url, "ZonePlay", url)
                }
                // fallback: direct iframe (may be playable)
                else -> {
                    extractedVideos += Video(url, name.replaceFirstChar { it.uppercase() }, url)
                }
            }
        }
        return extractedVideos
    }

    data class BLZoneRawVideo(val url: String, val name: String, val quality: String? = null)
}