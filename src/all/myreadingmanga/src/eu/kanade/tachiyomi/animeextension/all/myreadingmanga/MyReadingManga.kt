// myreadingmanga.kt (transformed for Aniyomi anime extension)
package eu.kanade.tachiyomi.animeextension.all.myreadingmanga

import android.annotation.SuppressLint
import android.net.Uri
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
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
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

    // --- Selectors common to Popular, Latest, and Search (for video entries) ---
    // We'll primarily target articles categorized as "video"
    private val VIDEO_ARTICLE_SELECTOR = "article.category-video"

    private fun buildAnimeFromVideoElement(element: Element): SAnime {
        val anime = SAnime.create().apply {
            setUrlWithoutDomain(element.select("a.entry-title-link").first()?.attr("href") ?: throw Exception("URL not found"))
            title = cleanTitle(element.select("h2.entry-title").text())
        }
        val thumbnailElement = element.select("img.entry-image").first()
        if (thumbnailElement != null) anime.thumbnail_url = getThumbnail(getImage(thumbnailElement))
        return anime
    }

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request {
        // Direct to the video category for popular
        return GET("$baseUrl/video/" + if (page > 1) "page/$page/" else "", headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        cacheAssistant() // Still useful for filters
        val document = response.asJsoup()
        val animes = document.select(VIDEO_ARTICLE_SELECTOR)
            .map { buildAnimeFromVideoElement(it) }
        val hasNextPage = document.select("li.pagination-next").first() != null
        return AnimesPage(animes, hasNextPage)
    }
    override fun popularAnimeNextPageSelector() = "li.pagination-next"
    override fun popularAnimeSelector() = VIDEO_ARTICLE_SELECTOR
    override fun popularAnimeFromElement(element: Element) = buildAnimeFromVideoElement(element)

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request {
        // Direct to the video category for latest
        return GET("$baseUrl/video/" + if (page > 1) "page/$page/" else "", headers)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage {
        cacheAssistant()
        val document = response.asJsoup()
        val animes = document.select(VIDEO_ARTICLE_SELECTOR)
            .map { buildAnimeFromVideoElement(it) }
        val hasNextPage = document.select("li.pagination-next").first() != null
        return AnimesPage(animes, hasNextPage)
    }
    override fun latestUpdatesNextPageSelector() = "li.pagination-next"
    override fun latestUpdatesSelector() = VIDEO_ARTICLE_SELECTOR
    override fun latestUpdatesFromElement(element: Element) = buildAnimeFromVideoElement(element)

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        return if (query.startsWith(PREFIX_SEARCH)) { // URL intent handler
            val id = query.removePrefix(PREFIX_SEARCH)
            client.newCall(GET("$baseUrl/$id"))
                .awaitSuccess()
                .use(::searchAnimeByIdParse)
        } else {
            super.getSearchAnime(page, query, filters)
        }
    }

    private fun searchAnimeByIdParse(response: Response): AnimesPage {
        val details = animeDetailsParse(response.asJsoup()).apply {
            setUrlWithoutDomain(response.request.url.toString())
            initialized = true
        }
        return AnimesPage(listOf(details), false)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
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
        // Force search results to include 'video' category
        uri.appendQueryParameter("wpsolr_fq[${filterList.size - indexModifier}]", "category:video")
        uri.appendQueryParameter("wpsolr_page", page.toString())

        return GET(uri.toString(), headers)
    }

    override fun searchAnimeNextPageSelector(): String? = throw UnsupportedOperationException() // Pagination for search is often tricky with custom filters
    override fun searchAnimeSelector() = "div.results-by-facets div[id*=res].category-video" // Filter search results
    private var animeParsedSoFar = 0
    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        if (document.location().contains("page=1")) animeParsedSoFar = 0
        val animes = document.select(searchAnimeSelector()).map { element ->
            // Re-use buildAnimeFromVideoElement for consistency, ensure it works with search result elements
            SAnime.create().apply {
                setUrlWithoutDomain(element.select("a").first()?.attr("href") ?: throw Exception("URL not found"))
                title = cleanTitle(element.select("a").first()?.text() ?: "")
                thumbnail_url = getThumbnail(getImage(element.select("img").firstOrNull()))
            }
        }.also { animeParsedSoFar += it.count() }
        val totalResults = Regex("""(\d+)""").find(document.select("div.res_info").text())?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return AnimesPage(animes, animeParsedSoFar < totalResults)
    }
    override fun searchAnimeFromElement(element: Element) = throw UnsupportedOperationException() // Use custom logic in searchAnimeParse

    // Build Anime From Element (consolidated for internal use)
    private val extensionRegex = Regex("""\.(jpg|png|jpeg|webp)""")

    private fun getImage(element: Element?): String? {
        element ?: return null
        val url = when {
            element.attr("data-src").contains(extensionRegex) -> element.attr("abs:data-src")
            element.attr("data-cfsrc").contains(extensionRegex) -> element.attr("abs:data-cfsrc")
            element.attr("src").contains(extensionRegex) -> element.attr("abs:src")
            else -> element.attr("abs:data-lazy-src")
        }
        return if (URLUtil.isValidUrl(url)) url else null
    }

    // removes resizing
    private fun getThumbnail(thumbnailUrl: String?): String? {
        thumbnailUrl ?: return null
        val url = thumbnailUrl.substringBeforeLast("-") + "." + thumbnailUrl.substringAfterLast(".")
        return if (URLUtil.isValidUrl(url)) url else null
    }

    // cleans up the name removing author and language from the title
    private val titleRegex = Regex("""\[[^]]*]""")
    private fun cleanTitle(title: String) = title.replace(titleRegex, "").substringBeforeLast("(").trim()

    private fun cleanAuthor(author: String) = author.substringAfter("[").substringBefore("]").trim()

    // =========================== Anime Details ============================
    override fun fetchAnimeDetails(anime: SAnime): Observable<SAnime> {
        val needCover = anime.thumbnail_url?.let { !client.newCall(GET(it, headers)).execute().isSuccessful } ?: true

        return client.newCall(GET(anime.url, headers))
            .asObservableSuccess()
            .map { response ->
                animeDetailsParse(response.asJsoup(), needCover).apply { initialized = true }
            }
    }

    private fun animeDetailsParse(document: Document, needCover: Boolean = true): SAnime {
        return SAnime.create().apply {
            title = cleanTitle(document.select("h1").text())
            author = cleanAuthor(document.select("h1").text())
            artist = author
            genre = document.select(".entry-header p a[href*=genre], [href*=tag], span.entry-categories a").joinToString { it.text() }
            val basicDescription = document.select("h1").text()
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

    // ============================== Episodes ==============================
    // For animated content, usually there's one "episode" per entry unless multi-part
    override fun episodeListSelector() = "irrelevant" // We will create episode manually

    @SuppressLint("DefaultLocale")
    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodes = mutableListOf<SEpisode>()

        // Check if it's actually a video page by looking for the "Video" category
        val isVideoContent = document.select("article.category-video").first() != null ||
            document.select("h1").text().contains("Animation:", ignoreCase = true)

        if (isVideoContent) {
            val date = parseDate(document.select(".entry-time").text())
            val animeUrl = document.baseUri()

            val episode = SEpisode.create().apply {
                url = animeUrl
                name = document.select("h1").text() // Use the title as the episode name
                date_upload = date
                episode_number = 1F // Assume single episode for now
            }
            episodes.add(episode)
        }
        return episodes
    }

    private fun parseDate(date: String): Long {
        return SimpleDateFormat("MMM dd, yyyy", Locale.US).parse(date)?.time ?: 0
    }

    override fun episodeFromElement(element: Element) = throw UnsupportedOperationException()

    // ============================ Video Links =============================
    override fun videoListParse(document: Document): List<Video> {
        val videos = mutableListOf<Video>()

        // Look for video elements
        val videoElements = document.select("div.entry-content video source, div.entry-content iframe")

        videoElements.forEach { element ->
            val videoUrl = if (element.tagName() == "source") {
                element.attr("src")
            } else { // iframe
                element.attr("src")
            }

            if (URLUtil.isValidUrl(videoUrl)) {
                // Try to infer quality or use a generic name
                val quality = if (videoUrl.contains("1080p")) "1080p"
                else if (videoUrl.contains("720p")) "720p"
                else if (videoUrl.contains("480p")) "480p"
                else if (videoUrl.contains("360p")) "360p"
                else "Default"

                videos.add(Video(videoUrl, quality, videoUrl, headers = headers)) // Pass headers for potential referer issues
            }
        }
        return videos.distinctBy { it.videoUrl } // Avoid duplicate video URLs
    }

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    // ============================== Settings ==============================
    // Filter Parsing, grabs pages as document and filters out Genres, Popular Tags, and Categories, Parings, and Scan Groups
    private var filtersCached = false
    private val filterMap = mutableMapOf<String, String>()

    // Grabs page containing filters and puts it into cache
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

    // Parses cached page for filters
    private fun returnFilter(url: String, css: String): Array<String> {
        val document = if (filterMap.isNullOrEmpty()) {
            filtersCached = false
            null
        } else {
            filtersCached = true
            Jsoup.parse(filterMap[url]!!)
        }
        return document?.select(css)?.map { it.text() }?.toTypedArray()
            ?: arrayOf("Press 'Reset' to load filters")
    }

    // URLs for the pages we need to cache
    private val cachedPagesUrls = hashMapOf(
        Pair("genres", baseUrl),
        Pair("tags", baseUrl),
        Pair("categories", "$baseUrl/cats/"),
        Pair("pairings", "$baseUrl/pairing/"),
        Pair("groups", "$baseUrl/group/"),
    )

    // Generates the filter lists for app
    override fun getFilterList(): AnimeFilterList {
        return AnimeFilterList(
            EnforceLanguageFilter(siteLang),
            SearchSortTypeList(),
            // Only add categories relevant to video content if possible, or leave as is if general
            GenreFilter(returnFilter(cachedPagesUrls["genres"]!!, ".tagcloud a[href*=/genre/]")),
            TagFilter(returnFilter(cachedPagesUrls["tags"]!!, ".tagcloud a[href*=/tag/]")),
            // We need to ensure that the "Video" category is always implicitly applied for search.
            // A direct category filter might conflict if not handled carefully, so keeping it generic
            // for search and adding "category:video" in searchAnimeRequest is safer.
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

    private interface UriFilter {
        fun addToUri(uri: Uri.Builder, uriParam: String)
    }

    companion object {
        const val PREFIX_SEARCH = "id:" // Keep for direct ID search
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Mobile Safari/537.36"
    }

    private fun randomString(length: Int): String {
        val charPool = ('a'..'z') + ('A'..'Z')
        return List(length) { charPool.random() }.joinToString("")
    }
}
