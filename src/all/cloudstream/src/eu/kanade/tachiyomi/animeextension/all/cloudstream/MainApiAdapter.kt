package eu.kanade.tachiyomi.animeextension.all.cloudstream

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okhttp3.Response

/**
 * Adapter: Wraps a Cloudstream MainAPI provider so it can be used
 * as a Aniyomi AnimeHttpSource at runtime.
 */
class MainApiAdapter(
    private val api: MainAPI,
) : AnimeHttpSource() {

    override val name: String = api.name
    override val baseUrl: String = api.mainUrl
    override val lang: String = when (api.lang) {
        "un" -> "all"
        else -> api.lang
    }
    override val supportsLatest: Boolean = false

    // === Popular Anime ===

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        if (!api.hasMainPage) throw UnsupportedOperationException("This extension doesn't have main page")

        return runBlocking {
            try {
                api.getMainPage(page, MainPageRequest("popular", baseUrl, false))
                    ?.toAnimePage() as AnimesPage
            } catch (e: NotImplementedError) {
                throw UnsupportedOperationException("Not implemented")
            }
        }
    }

    override fun popularAnimeRequest(page: Int): Request = throw UnsupportedOperationException()
    override fun popularAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    // === Search ===

    override suspend fun getSearchAnime(
        page: Int,
        query: String,
        filters: AnimeFilterList
    ): AnimesPage {
        return runBlocking {
            try {
                api.search(query, page)?.toAnimePage() ?: AnimesPage(emptyList(), false)
            } catch (e: NotImplementedError) {
                throw UnsupportedOperationException("Not implemented")
            }
        }
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = throw UnsupportedOperationException()
    override fun searchAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    // === Latest Updates ===

    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()
    override fun latestUpdatesParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    // === Anime Details ===

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val details = runBlocking {
            try{
                api.load(anime.url)
            } catch (e: NotImplementedError) {
                throw UnsupportedOperationException("Not implemented")
            }
        }
        return details?.toSAnime() ?: SAnime.create()
    }

    override fun animeDetailsParse(response: Response): SAnime = throw UnsupportedOperationException()

    // === Episode List ===

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val loadResponse = runBlocking {
            try{
                api.load(anime.url)
            } catch (e: NotImplementedError) {
                throw UnsupportedOperationException("Not implemented")
            }
        }
        return loadResponse?.toSEpisodeList() ?: emptyList()
    }

    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()

    // === Video Streams ===

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val videos = mutableListOf<Video>()
        val subs = mutableListOf<Track>()
        runBlocking {
            try{
                api.loadLinks(
                    episode.url,
                    isCasting = false,
                    subtitleCallback = { subtitleFile ->
                        subs.add(subtitleFile.toTrack())
                    },
                    callback = { extractorLink ->
                        videos.add(extractorLink.toVideo())
                    }
                )
            } catch (e: NotImplementedError) {
                throw UnsupportedOperationException("Not implemented")
            }
        }
        return videos
    }

    override fun videoListParse(response: Response): List<Video> = throw UnsupportedOperationException()

    override fun getAnimeUrl(anime: SAnime): String {
        return if (anime.url.startsWith(baseUrl)) anime.url else super.getAnimeUrl(anime)
    }
}
