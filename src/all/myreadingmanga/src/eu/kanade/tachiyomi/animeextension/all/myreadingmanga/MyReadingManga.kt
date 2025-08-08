package eu.kanade.tachiyomi.animeextension.all.myreadingmanga

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.URLUtil
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

open class MyReadingManga(override val lang: String, private val siteLang: String, private val latestLang: String) : ParsedAnimeHttpSource() {

    // Basic Info
    override val name = "MyReadingManga"
    final override val baseUrl = "https://myreadingmanga.info"
    override fun headersBuilder(): Headers.Builder =
        super.headersBuilder()
            .set("User-Agent", USER_AGENT)
            .add("X-Requested-With", randomString((1..20).random()))
    override val client = network.cloudflareClient.newBuilder()
        .addInterceptor { chain ->
            val request = chain.request()
            val headers = request.headers.newBuilder().apply {
                removeAll("X-Requested-With")
            }.build()

            chain.proceed(request.newBuilder().headers(headers).build())
        }
        .build()

    override val supportsLatest = true

    // Popular - Relevancy
    override fun popularAnimeRequest(page: Int): Request {
        return GET("$baseUrl/search/?wpsolr_sort=sort_by_relevancy_desc&wpsolr_page=$page&wpsolr_fq[0]=lang_str:$siteLang&wpsolr_fq[1]=categories:Video", headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        cacheAssistant()
        return searchAnimeParse(response)
    }
    override fun popularAnimeNextPageSelector() = throw UnsupportedOperationException()
    override fun popularAnimeSelector() = throw UnsupportedOperationException()
    override fun popularAnimeFromElement(element: Element) = throw UnsupportedOperationException()

    // Latest - Date Uploaded
    @SuppressLint("DefaultLocale")
    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/search/?wpsolr_sort=sort_by_date_desc&wpsolr_page=$page&wpsolr_fq[0]=lang_str:$siteLang&wpsolr_fq[1]=categories:Video", headers)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage {
        cacheAssistant()
        return searchAnimeParse(response)
    }

    override fun latestUpdatesNextPageSelector() = throw UnsupportedOperationException()
    override fun latestUpdatesSelector() = throw UnsupportedOperationException()
    override fun latestUpdatesFromElement(element: Element): SAnime = throw UnsupportedOperationException()

    // Search
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        // whether enforce language is true will change the index of the loop below
        val indexModifier = filterList.filterIsInstance<EnforceLanguageFilter>().first().indexModifier()

        val uri = Uri.parse("$baseUrl/search/").buildUpon()
            .appendQueryParameter("wpsolr_q", query)
        filterList.forEachIndexed { i, filter ->
            if (filter is UriFilter) {
                filter.addToUri(uri, "wpsolr_fq[${i - indexModifier}]")
            }
            if (filter is SearchSortTypeList) {
                uri.appendQueryParameter("wpsolr_sort", listOf("sort_by_date_desc", "sort_by_date_asc", "sort_by_random", "sort_by_relevancy_desc")[filter.state])
            }
        }
        uri.appendQueryParameter("wpsolr_page", page.toString())

        uri.appendQueryParameter("wpsolr_fq[1]", "categories:Video")

        return GET(uri.toString(), headers)
    }

    override fun searchAnimeNextPageSelector(): String? = throw UnsupportedOperationException()
    override fun searchAnimeSelector() = "div.results-by-facets div[id*=res]"
    override fun videoFromElement(element: Element): Video {
        TODO("Not yet implemented")
    }

    private var animeParsedSoFar = 0
    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        if (document.location().contains("page=1")) animeParsedSoFar = 0
        val animes = document.select(searchAnimeSelector()).map { searchAnimeFromElement(it) }
            .also { animeParsedSoFar += it.count() }
        val totalResults = Regex("""(\d+)""").find(document.select("div.res_info").text())?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return AnimesPage(animes, animeParsedSoFar < totalResults)
    }
    override fun searchAnimeFromElement(element: Element) = buildAnime(element.select("a").first()!!, element.select("img").first())

    private fun buildAnime(titleElement: Element, thumbnailElement: Element?): SAnime {
        val anime = SAnime.create().apply {
            setUrlWithoutDomain(titleElement.attr("href"))
            title = cleanTitle(titleElement.text())
        }
        if (thumbnailElement != null) anime.thumbnail_url = getThumbnail(getImage(thumbnailElement))
        return anime
    }

    private val extensionRegex = Regex("""\.(jpg|png|jpeg|webp)""")

    private fun getImage(element: Element): String? {
        val url = when {
            element.attr("data-src").contains(extensionRegex) -> element.attr("abs:data-src")
            element.attr("data-cfsrc").contains(extensionRegex) -> element.attr("abs:data-cfsrc")
            element.attr("src").contains(extensionRegex) -> element.attr("abs:src")
            else -> element.attr("abs:data-lazy-src")
        }

        return if (URLUtil.isValidUrl(url)) url else null
    }

    private fun getThumbnail(thumbnailUrl: String?): String? {
        thumbnailUrl ?: return null
        val url = thumbnailUrl.substringBeforeLast("-") + "." + thumbnailUrl.substringAfterLast(".")
        return if (URLUtil.isValidUrl(url)) url else null
    }

    private val titleRegex = Regex("""^(\w+):|\[[^]]*]|\([^)]*\)""")
    private fun cleanTitle(title: String) = title.replace(titleRegex, "").substringBeforeLast("(").trim()

    private fun cleanAuthor(author: String) = author.substringAfter("[").substringBefore("]").trim()

    // Anime Details
    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val needCover = anime.thumbnail_url?.let {
            runCatching {
                !client.newCall(GET(it, headers)).awaitSuccess().isSuccessful
            }.getOrDefault(true)
        } ?: true

        val response = client.newCall(animeDetailsRequest(anime)).awaitSuccess()
        return animeDetailsParse(response.asJsoup(), needCover).apply { initialized = true }
    }

    private fun animeDetailsParse(document: Document, needCover: Boolean = true): SAnime {
        return SAnime.create().apply {
            title = cleanTitle(document.select("h1").text())
            author = cleanAuthor(document.select("h1").text())
            artist = author
            genre = document.select(".entry-header p a[href*=genre], [href*=tag], span.entry-categories a").joinToString { it.text() }
            val basicDescription = document.select("h1").text()
            // too troublesome to achieve 100% accuracy assigning scanlator group during episodeListParse
            val scanlatedBy = document.select(".entry-terms:has(a[href*=group])").firstOrNull()
                ?.select("a[href*=group]")?.joinToString(prefix = "Scanlated by: ") { it.text() }
            val extendedDescription = document.select(".entry-content p:not(p:containsOwn(|)):not(.chapter-class + p)").joinToString("\n") { it.text() }
            description = listOfNotNull(basicDescription, scanlatedBy, extendedDescription).joinToString("\n").trim()
            status = when (document.select("a[href*=status]").first()?.text()) {
                "Ongoing" -> SAnime.ONGOING
                "Completed" -> SAnime.COMPLETED
                else -> SAnime.UNKNOWN
            }

            if (needCover) {
                thumbnail_url = getThumbnail(
                    getImage(
                        client.newCall(GET("$baseUrl/search/?search=${document.location()}", headers))
                            .execute().asJsoup().select("div.wdm_results div.p_content img").first()!!,
                    ),
                )
            }
        }
    }

    override fun animeDetailsParse(document: Document) = throw UnsupportedOperationException()
    override fun episodeFromElement(element: Element): SEpisode {
        TODO("Not yet implemented")
    }

    override fun episodeListSelector() = "a[class=page-numbers]"

    @SuppressLint("DefaultLocale")
    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodes = mutableListOf<SEpisode>()

        val date = parseDate(document.select(".entry-time").text())
        val animeUrl = document.baseUri()
        // create first episode since its on main anime page
        episodes.add(createEpisode("1", document.baseUri(), date, "Episode 1"))
        // see if there are multiple episodes or not
        val lastEpisodeNumber = document.select(episodeListSelector()).last()?.text()
        if (lastEpisodeNumber != null) {
            // There are entries with more episodes but those never show up,
            // so we take the last one and loop it to get all hidden ones.
            // Example: 1 2 3 4 .. 7 8 9 Next
            for (i in 2..lastEpisodeNumber.toInt()) {
                episodes.add(createEpisode(i.toString(), document.baseUri(), date, "Episode $i"))
            }
        }
        episodes.reverse()
        return episodes
    }

    private fun parseDate(date: String): Long {
        return SimpleDateFormat("MMM dd, yyyy", Locale.US).parse(date)?.time ?: 0
    }

    private fun createEpisode(pageNumber: String, animeUrl: String, date: Long, epname: String): SEpisode {
        val episode = SEpisode.create()
        episode.setUrlWithoutDomain("$animeUrl/$pageNumber")
        episode.name = epname
        episode.date_upload = date
        return episode
    }

    override fun videoListSelector(): String = "div.video-container-ads video source"

    override fun videoUrlParse(document: Document): String {
        return document.selectFirst(videoListSelector())?.attr("src")
            ?: throw Exception("No video URL found")
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoUrl = videoUrlParse(document)
        if (videoUrl.isEmpty()) return emptyList()

        val refererUrl = response.request.url.toString()
        val cookieManager = CookieManager.getInstance()
        val cookies = cookieManager.getCookie(videoUrl) ?: ""

        val customHeaders = Headers.Builder().apply {
            set("Referer", refererUrl)
            set("Cookie", cookies)
            set("User-Agent", USER_AGENT)
            set("Range", "bytes=0-")
            set("Accept", "*/*")
            set("Accept-Encoding", "identity;q=1, *;q=0")
            set("Accept-Language", "en-US,en;q=0.9,pt-BR;q=0.8,pt;q=0.7,es;q=0.6")
            set("sec-ch-ua", "Chromium;v=\"134\", Not:A-Brand;v=\"24\", Opera GX;v=\"119\"")
            set("sec-ch-ua-arch", "x86")
            set("sec-ch-ua-bitness", "64")
            set("sec-ch-ua-full-version", "119.0.5497.163")
            set("sec-ch-ua-full-version-list", "Chromium;v=\"134.0.6998.205\", Not:A-Brand;v=\"24.0.0.0\", Opera GX;v=\"119.0.5497.163\"")
            set("sec-ch-ua-mobile", "?0")
            set("sec-ch-ua-model", "")
            set("sec-ch-ua-platform", "Windows")
            set("sec-ch-ua-platform-version", "19.0.0")
            set("sec-fetch-dest", "video")
            set("sec-fetch-mode", "no-cors")
            set("sec-fetch-site", "same-origin")
        }.build()

        return listOf(Video(videoUrl, "Default", videoUrl, customHeaders))
    }

    private var filtersCached = false
    private val filterMap = mutableMapOf<String, String>()

    private fun filterAssist(url: String) {
        val response = client.newCall(GET(url, headers)).execute()
        filterMap[url] = response.body.string()
    }

    private fun cacheAssistant() {
        if (!filtersCached) {
            cachedPagesUrls.onEach { filterAssist(it.value) }
            filtersCached = true
        }
    }

    private fun returnFilter(url: String, css: String): Array<String> {
        val document = if (filterMap.isEmpty()) {
            filtersCached = false
            null
        } else {
            filtersCached = true
            Jsoup.parse(filterMap[url]!!)
        }
        return document?.select(css)?.map { it.text() }?.toTypedArray()
            ?: arrayOf("Press 'Reset' to load filters")
    }

    private val cachedPagesUrls = hashMapOf(
        Pair("genres", baseUrl),
        Pair("tags", baseUrl),
        Pair("categories", "$baseUrl/cats/"),
        Pair("pairings", "$baseUrl/pairing/"),
        Pair("groups", "$baseUrl/group/"),
    )

    override fun getFilterList(): AnimeFilterList {
        return AnimeFilterList(
            EnforceLanguageFilter(siteLang),
            SearchSortTypeList(),
            GenreFilter(returnFilter(cachedPagesUrls["genres"]!!, ".tagcloud a[href*=/genre/]")),
            TagFilter(returnFilter(cachedPagesUrls["tags"]!!, ".tagcloud a[href*=/tag/]")),
            CatFilter(returnFilter(cachedPagesUrls["categories"]!!, ".links a")),
            PairingFilter(returnFilter(cachedPagesUrls["pairings"]!!, ".links a")),
            ScanGroupFilter(returnFilter(cachedPagesUrls["groups"]!!, ".links a")),
        )
    }

    private class EnforceLanguageFilter(val siteLang: String) : AnimeFilter.CheckBox("Enforce language", true), UriFilter {
        fun indexModifier() = if (state) 0 else 1
        override fun addToUri(uri: Uri.Builder, uriParam: String) {
            if (state) uri.appendQueryParameter(uriParam, "lang_str:$siteLang")
        }
    }

    private class GenreFilter(GENRES: Array<String>) : UriSelectFilter("Genre", "genre_str", arrayOf("Any", *GENRES))
    private class TagFilter(POPTAG: Array<String>) : UriSelectFilter("Popular Tags", "tags", arrayOf("Any", *POPTAG))
    private class CatFilter(CATID: Array<String>) : UriSelectFilter("Categories", "categories", arrayOf("Any", *CATID))
    private class PairingFilter(PAIR: Array<String>) : UriSelectFilter("Pairing", "pairing_str", arrayOf("Any", *PAIR))
    private class ScanGroupFilter(GROUP: Array<String>) : UriSelectFilter("Scanlation Group", "group_str", arrayOf("Any", *GROUP))
    private class SearchSortTypeList : AnimeFilter.Select<String>("Sort by", arrayOf("Newest", "Oldest", "Random", "More relevant"))

    /**
     * Class that creates a select filter. Each entry in the dropdown has a name and a display name.
     * If an entry is selected it is appended as a query parameter onto the end of the URI.
     * If `firstIsUnspecified` is set to true, if the first entry is selected, nothing will be appended on the the URI.
     */
    private open class UriSelectFilter(
        displayName: String,
        val uriValuePrefix: String,
        val vals: Array<String>,
        val firstIsUnspecified: Boolean = true,
        defaultValue: Int = 0,
    ) :
        AnimeFilter.Select<String>(displayName, vals.map { it }.toTypedArray(), defaultValue), UriFilter {
        override fun addToUri(uri: Uri.Builder, uriParam: String) {
            if (state != 0 || !firstIsUnspecified) {
                val splitFilter = vals[state].split(",")
                when {
                    splitFilter.size == 2 -> {
                        val reversedFilter = splitFilter.reversed().joinToString(" | ").trim()
                        uri.appendQueryParameter(uriParam, "$uriValuePrefix:$reversedFilter")
                    }
                    else -> {
                        uri.appendQueryParameter(uriParam, "$uriValuePrefix:${vals[state]}")
                    }
                }
            }
        }
    }

    /**
     * Represents a filter that is able to modify a URI.
     */
    private interface UriFilter {
        fun addToUri(uri: Uri.Builder, uriParam: String)
    }

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Mobile Safari/537.36"
    }

    private fun randomString(length: Int): String {
        val charPool = ('a'..'z') + ('A'..'Z')
        return List(length) { charPool.random() }.joinToString("")
    }
}
