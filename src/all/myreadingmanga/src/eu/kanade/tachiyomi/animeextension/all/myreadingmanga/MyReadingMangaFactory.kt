package eu.kanade.tachiyomi.animeextension.all.myreadingmanga

import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.AnimeSourceFactory

class MyReadingMangaFactory : AnimeSourceFactory {
    override fun createSources(): List<AnimeSource> = languageList.map { MyReadingManga(it.tachiLang, it.siteLang, it.latestLang) }
}

private data class Source(val tachiLang: String, val siteLang: String, val latestLang: String = siteLang)

// These should all be valid. Add a language code and uncomment to enable
private val languageList = listOf(
    Source("ar", "Arabic"),
//    Source("", "Bahasa"),
    Source("id", "Indonesia"),
    Source("bg", "Bulgarian"),
    Source("zh", "Chinese"),
    Source("hr", "Croatian"),
    Source("cs", "Czech"),
    Source("en", "English"),
    Source("fil", "Filipino"),
    Source("fi", "Finnish"),
//    Source("", "Flemish", "flemish-dutch"),
    Source("nl", "Dutch"),
    Source("fr", "French"),
    Source("de", "German"),
    Source("el", "Greek"),
    Source("he", "Hebrew"),
    Source("hi", "Hindi"),
    Source("hu", "Hungarian"),
    Source("it", "Italian"),
    Source("ja", "Japanese", "jp"),
    Source("ko", "Korean"),
    Source("pl", "Polish"),
    Source("pt-BR", "Portuguese"),
    Source("ro", "Romanian"),
    Source("ru", "Russian"),
    Source("sk", "Slovak"),
    Source("es", "Spanish"),
    Source("sv", "Swedish"),
    Source("th", "Thai"),
    Source("tr", "Turkish"),
    Source("vi", "Vietnamese"),
)
