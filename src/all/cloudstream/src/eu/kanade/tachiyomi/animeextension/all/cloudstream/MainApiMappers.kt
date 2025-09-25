package eu.kanade.tachiyomi.animeextension.all.cloudstream

import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LiveStreamLoadResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TorrentLoadResponse
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import okhttp3.Headers.Companion.toHeaders

/**
 * Mapping extensions between Cloudstream MainAPI models and Tachiyomi anime models.
 */

fun SearchResponse.toSAnime(): SAnime {
    return SAnime.create().apply {
        title = name
        url = this@toSAnime.url
        thumbnail_url = posterUrl
    }
}

fun LoadResponse.toSAnime(): SAnime {
    return SAnime.create().apply {
        title = name
        url = this@toSAnime.url
        thumbnail_url = posterUrl
        description = plot
        status = when (this@toSAnime) {
            is AnimeLoadResponse -> showStatus?.toStatus() ?: SAnime.UNKNOWN
            is TvSeriesLoadResponse  -> showStatus?.toStatus() ?: SAnime.UNKNOWN
            else -> {SAnime.UNKNOWN}
        }
        initialized = true
    }
}

fun ShowStatus.toStatus(): Int {
    return when (this) {
        ShowStatus.Ongoing -> SAnime.ONGOING
        ShowStatus.Completed -> SAnime.COMPLETED
    }
}

fun LoadResponse.toSEpisodeList(): List<SEpisode> {
    return when (this) {
        is AnimeLoadResponse -> episodes.values.flatten()
            .sortedWith(
                compareByDescending<Episode> { it.season ?: Int.MIN_VALUE }
                .thenByDescending { it.episode ?: Int.MIN_VALUE }
            ).map { ep: Episode ->
            SEpisode.create().apply {
                name = ep.name ?: "Untitled"
                url = ep.data
                ep.episode?.let { episode_number = it.toFloat() }
                ep.date?.let { date_upload = it }
            }
        }

        is TvSeriesLoadResponse -> episodes
            .sortedWith(
                compareByDescending<Episode> { it.season ?: Int.MIN_VALUE }
                .thenByDescending { it.episode ?: Int.MIN_VALUE }
            ).map { ep: Episode ->
            SEpisode.create().apply {
                name = ep.name ?: "Untitled"
                url = ep.data
                ep.episode?.let { episode_number = it.toFloat() }
                ep.date?.let { date_upload = it }
            }
        }

        is MovieLoadResponse -> listOf(
            SEpisode.create().apply {
                name = "Movie"
                url = dataUrl
            }
        )

        //TODO
//        is TorrentLoadResponse
//        is LiveStreamLoadResponse
        else -> emptyList()
    }
}

fun HomePageResponse.toAnimePage(): AnimesPage {
    return AnimesPage(
        animes = items.flatMap { it.list }.map { it.toSAnime() },
        hasNextPage = hasNext
    )
}

fun SearchResponseList.toAnimePage(): AnimesPage {
    return AnimesPage(
        animes = items.map { it.toSAnime() },
        hasNextPage = hasNext
    )
}

 fun ExtractorLink.toVideo(): Video {
    return Video(
        url = url,
        quality = quality.toString(),
        videoUrl = url,
        headers = headers.toHeaders()
    )
 }

fun SubtitleFile.toTrack(): Track {
    return Track(
        url = url,
        lang = lang
    )
}
