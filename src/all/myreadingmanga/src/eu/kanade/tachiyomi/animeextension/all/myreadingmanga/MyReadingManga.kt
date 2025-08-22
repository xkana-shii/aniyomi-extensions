package eu.kanade.tachiyomi.animeextension.all.myreadingmanga

import android.annotation.SuppressLint
import android.app.Application
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Locale

open class MyReadingManga(override val lang: String, private val siteLang: String, private val latestLang: String) : ParsedAnimeHttpSource(), ConfigurableAnimeSource {

    // LOGGING: Utility functions
    private fun logRequest(request: Request) {
        Log.d("MRM/HTTP-Request", "URL: ${request.url}")
        Log.d("MRM/HTTP-Request", "Method: ${request.method}")
        for ((name, value) in request.headers) {
            Log.d("MRM/HTTP-Request", "Header: $name = $value")
        }
    }
    private fun logResponse(response: Response) {
        Log.d("MRM/HTTP-Response", "URL: ${response.request.url}")
        Log.d("MRM/HTTP-Response", "Status: ${response.code}")
        for ((name, value) in response.headers) {
            Log.d("MRM/HTTP-Response", "Header: $name = $value")
        }
        val contentType = response.header("Content-Type") ?: ""
        if (contentType.contains("text") || contentType.contains("json") || contentType.contains("xml")) {
            val responseBody = response.peekBody(1024*10).string() // log up to 10KB
            Log.d("MRM/HTTP-Response", "Body (partial): ${responseBody.take(1000)}")
        }
    }

    // Basic Info
    override val name = "MyReadingManga"
    final override val baseUrl = "https://myreadingmanga.info"
    override fun headersBuilder(): Headers.Builder =
        super.headersBuilder()
            .set("User-Agent", USER_AGENT)
            .add("X-Requested-With", randomString((1..20).random()))

    private val preferences: SharedPreferences = Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    private val credentials: Credential get() = Credential(
        username = preferences.getString(USERNAME_PREF, "") ?: "",
        password = preferences.getString(PASSWORD_PREF, "") ?: "",
    )
    private data class Credential(val username: String, val password: String)
    private var isLoggedIn: Boolean = false

    override val client = network.client.newBuilder()
        // LOGGING: add global logging interceptor
        .addInterceptor { chain ->
            val request = chain.request()
            logRequest(request)
            val response = chain.proceed(request)
            logResponse(response)
            response
        }
        .addInterceptor { chain ->
            val request = chain.request()
            val headers = request.headers.newBuilder().apply {
                removeAll("X-Requested-With")
            }.build()
            chain.proceed(request.newBuilder().headers(headers).build())
        }
        .addInterceptor(::loginInterceptor)
        .build()

    override val supportsLatest = true

    // Login Interceptor
    private fun loginInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()

        if (credentials.username.isBlank() || credentials.password.isBlank()) {
            return chain.proceed(request)
        }

        if (isLoggedIn) {
            return chain.proceed(request)
        }

        try {
            val loginForm = FormBody.Builder()
                .add("log", credentials.username)
                .add("pwd", credentials.password)
                .add("wp-submit", "Log In")
                .add("redirect_to", "$baseUrl/")
                .add("testcookie", "1")
                .build()

            val loginRequest = POST("$baseUrl/wp-login.php", headers, loginForm)
            logRequest(loginRequest) // LOGGING
            val loginResponse = network.client.newCall(loginRequest).execute()
            logResponse(loginResponse) // LOGGING

            if (loginResponse.isSuccessful) {
                isLoggedIn = true
                return chain.proceed(request)
            } else {
                Toast.makeText(Injekt.get<Application>(), "MyReadingManga login failed. Please check your credentials.", Toast.LENGTH_LONG).show()
            }
            return chain.proceed(request)
        } catch (e: Exception) {
            Log.e("MRM/HTTP-Login", "Exception: ${e.message}")
            return chain.proceed(request)
        }
    }

    // Preference Screen
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val application = Injekt.get<Application>()
        val usernamePref = EditTextPreference(screen.context).apply {
            key = USERNAME_PREF
            title = "Username"
            summary = "Enter your username"
            setOnPreferenceChangeListener { _, _ ->
                Toast.makeText(application, "Restart the app to apply changes", Toast.LENGTH_LONG).show()
                true
            }
        }
        val passwordPref = EditTextPreference(screen.context).apply {
            key = PASSWORD_PREF
            title = "Password"
            summary = "Enter your password"
            setOnPreferenceChangeListener { _, _ ->
                Toast.makeText(application, "Restart the app to apply changes", Toast.LENGTH_LONG).show()
                true
            }
        }
        screen.addPreference(usernamePref)
        screen.addPreference(passwordPref)
    }

    // Popular
    override fun popularAnimeRequest(page: Int): Request {
        val req = GET("$baseUrl/popular/", headers)
        logRequest(req) // LOGGING
        return req
    }

    override fun popularAnimeNextPageSelector() = "li.pagination-next"
    override fun popularAnimeSelector() = "div.entry-content ul.wpp-list > li:has(img[src*=vlcsnap])"
    override fun popularAnimeFromElement(element: Element) = buildAnime(element.select(".wpp-post-title").first()!!, element.select("img.wpp-thumbnail").first())
    override fun popularAnimeParse(response: Response): AnimesPage {
        logResponse(response) // LOGGING
        cacheAssistant()
        return super.popularAnimeParse(response)
    }

    // Latest
    @SuppressLint("DefaultLocale")
    override fun latestUpdatesRequest(page: Int): Request {
        val req = GET("$baseUrl/lang/${latestLang.lowercase()}" + if (page > 1) "/page/$page/" else "", headers)
        logRequest(req) // LOGGING
        return req
    }

    override fun latestUpdatesNextPageSelector() = "li.pagination-next"
    override fun latestUpdatesSelector() = "div.content-archive article.post:has(img[src*=vlcsnap])"
    override fun latestUpdatesFromElement(element: Element) = buildAnime(element.select("a[rel]").first()!!, element.select("a.entry-image-link img").first())
    override fun latestUpdatesParse(response: Response): AnimesPage {
        logResponse(response) // LOGGING
        cacheAssistant()
        return super.latestUpdatesParse(response)
    }

    // Search
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val uri = if (page > 1) {
            Uri.parse("$baseUrl/page/$page/").buildUpon()
        } else {
            Uri.parse("$baseUrl/").buildUpon()
        }

        uri.appendQueryParameter("s", query)

        filters.forEach { filter ->
            if (filter is UriFilter) {
                filter.addToUri(uri)
            }
        }

        uri.appendQueryParameter("ep_filter_category", "video")

        val req = GET(uri.toString(), headers)
        logRequest(req) // LOGGING
        return req
    }

    override fun searchAnimeNextPageSelector() = "div.archive-pagination li.pagination-next a"
    override fun searchAnimeSelector() = "div.content-archive article.post"
    private var animeParsedSoFar = 0
    override fun searchAnimeParse(response: Response): AnimesPage {
        logResponse(response) // LOGGING
        val document = response.asJsoup()
        val currentUrl = document.location()
        if (!currentUrl.contains("/page/") || currentUrl.contains("/page/1/")) {
            animeParsedSoFar = 0
        }

        val animes = document.select(searchAnimeSelector()).map { searchAnimeFromElement(it) }
            .also { animeParsedSoFar += it.count() }

        val totalResultsText = document.select("h2.ep-search-count").first()?.text()
        val totalResults = totalResultsText?.let { Regex("""(\d+)""").find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: 0

        val hasNextPage = if (animes.isEmpty()) false else animeParsedSoFar < totalResults

        return AnimesPage(animes, hasNextPage)
    }
    override fun searchAnimeFromElement(element: Element) = buildAnime(element.select("h2.entry-title a.entry-title-link").first()!!, element.select("a.entry-image-link img.post-image").first())

    // Build Anime From Element
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

    // removes resizing
    private fun getThumbnail(thumbnailUrl: String?): String? {
        thumbnailUrl ?: return null
        val url = thumbnailUrl.substringBeforeLast("-") + "." + thumbnailUrl.substringAfterLast(".")
        return if (URLUtil.isValidUrl(url)) url else null
    }

    // cleans up the name removing author and language from the title
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
        logResponse(response) // LOGGING
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

    // Start Episode Get
    override fun episodeListSelector() = "a[class=page-numbers]"

    @SuppressLint("DefaultLocale")
    override fun episodeListParse(response: Response): List<SEpisode> {
        logResponse(response) // LOGGING
        val document = response.asJsoup()
        val episodes = mutableListOf<SEpisode>()

        val date = parseDate(document.select(".entry-time").text())
        val animeUrl = document.baseUri()
        // create first episodes since its on main anime page
        episodes.add(createEpisode("1", document.baseUri(), date, "Ep. 1"))
        // see if there are multiple episodes or not
        val lastEpisodeNumber = document.select(episodeListSelector()).last()?.text()
        if (lastEpisodeNumber != null) {
            // There are entries with more episodes but those never show up,
            // so we take the last one and loop it to get all hidden ones.
            // Example: 1 2 3 4 .. 7 8 9 Next
            for (i in 2..lastEpisodeNumber.toInt()) {
                episodes.add(createEpisode(i.toString(), document.baseUri(), date, "Ep. $i"))
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

    override fun videoFromElement(element: Element): Video {
        TODO("Not yet implemented")
    }

    override fun videoUrlParse(document: Document): String {
        return document.selectFirst(videoListSelector())?.attr("src")
            ?: throw Exception("No video URL found")
    }

    override fun videoListParse(response: Response): List<Video> {
        logResponse(response) // LOGGING
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
            /* disable -->
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
            disable <-- */
        }.build()

        // LOGGING: Log the headers and URL that will be used for the video player
        Log.d("MRM/Video-Player", "VideoUrl: $videoUrl")
        for ((name, value) in customHeaders) {
            Log.d("MRM/Video-Player", "Header: $name = $value")
        }

        return listOf(Video(videoUrl, "Default", videoUrl, customHeaders))
    }

    private var filtersCached = false
    private val filterMap = mutableMapOf<String, String>()

    // Grabs page containing filters and puts it into cache
    private fun filterAssist(url: String) {
        val response = client.newCall(GET(url, headers)).execute()
        logResponse(response) // LOGGING
        filterMap[url] = response.body.string()
    }

    private fun cacheAssistant() {
        if (!filtersCached) {
            cachedPagesUrls.onEach { filterAssist(it.value) }
            filtersCached = true
        }
    }

    // Parses cached page for filters
    private fun returnFilter(url: String, css: String): Array<Pair<String, String>> {
        val document = if (filterMap.isNullOrEmpty()) {
            filtersCached = false
            null
        } else {
            filtersCached = true
            filterMap[url]?.let { Jsoup.parse(it) }
        }
        // New scraping logic to get both name and slug.
        return document?.select(css)?.map { element ->
            val name = element.select("a").text().substringBefore(" (").trim()
            val slug = element.attr("data-term-slug").trim()
            Pair(name, slug)
        }?.toTypedArray() ?: arrayOf(Pair("Press 'Reset' to load filters", ""))
    }

    // URLs for the pages we need to cache
    private val cachedPagesUrls = hashMapOf(
        Pair("genres", baseUrl),
        Pair("tags", baseUrl),
        Pair("categories", baseUrl),
        Pair("pairing", baseUrl),
        Pair("artists", baseUrl),
    )

    // Generates the filter lists for app
    override fun getFilterList(): AnimeFilterList {
        return AnimeFilterList(
            EnforceLanguageFilter(siteLang),
            GenreFilter(returnFilter(cachedPagesUrls["genres"]!!, ".tagcloud a[href*=/genre/]")),
            TagFilter(returnFilter(cachedPagesUrls["tags"]!!, "div[data-facet=post_tag] .term")),
            CatFilter(returnFilter(cachedPagesUrls["categories"]!!, "div[data-facet=category] .term")),
            PairingFilter(returnFilter(cachedPagesUrls["pairing"]!!, "div[data-facet=pairing] .term")),
            ArtistFilter(returnFilter(cachedPagesUrls["artists"]!!, "div[data-facet=artist] .term")),
        )
    }

    private class EnforceLanguageFilter(val siteLang: String) : AnimeFilter.CheckBox("Enforce language", true), UriFilter {
        override fun addToUri(uri: Uri.Builder) {
            if (state) uri.appendQueryParameter("ep_filter_lang", siteLang)
        }
    }

    /**
     * Represents a filter that is able to modify a URI.
     */
    private interface UriFilter {
        fun addToUri(uri: Uri.Builder)
    }

    private open class UriSelectFilter(
        displayName: String,
        val uriParam: String,
        val vals: Array<Pair<String, String>>,
        val firstIsUnspecified: Boolean = true,
        defaultValue: Int = 0,
    ) : AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray(), defaultValue), UriFilter {
        override fun addToUri(uri: Uri.Builder) {
            if (state != 0 || !firstIsUnspecified) {
                val selectedSlug = vals[state].second
                uri.appendQueryParameter(uriParam, selectedSlug)
            }
        }
    }

    private class GenreFilter(GENRES: Array<Pair<String, String>>) : UriSelectFilter("Genre", "ep_filter_genre", arrayOf(Pair("Any", ""), *GENRES))
    private class TagFilter(POPTAGS: Array<Pair<String, String>>) : UriSelectFilter("Popular Tags", "ep_filter_post_tag", arrayOf(Pair("Any", ""), *POPTAGS))
    private class CatFilter(CATID: Array<Pair<String, String>>) : UriSelectFilter("Categories", "ep_filter_category", arrayOf(Pair("Any", ""), *CATID))
    private class PairingFilter(PAIRING: Array<Pair<String, String>>) : UriSelectFilter("Pairing", "ep_filter_pairing", arrayOf(Pair("Any", ""), *PAIRING))
    private class ArtistFilter(ARTISTS: Array<Pair<String, String>>) : UriSelectFilter("Circle/ Artist", "ep_filter_artist", arrayOf(Pair("Any", ""), *ARTISTS))

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Mobile Safari/537.36"
        private const val USERNAME_PREF = "MYREADINGMANGA_USERNAME"
        private const val PASSWORD_PREF = "MYREADINGMANGA_PASSWORD"
    }

    private fun randomString(length: Int): String {
        val charPool = ('a'..'z') + ('A'..'Z')
        return List(length) { charPool.random() }.joinToString("")
    }
}
