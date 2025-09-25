package eu.kanade.tachiyomi.animeextension.all.cloudstream

import android.app.Application
import android.util.Log
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.AnimeSourceFactory
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get



class CloudStreamFactory : AnimeSourceFactory {
    private val context = Injekt.get<Application>()

    override fun createSources(): List<AnimeSource> {
        val apis = PluginLoader.loadAllPlugins(context)
        Log.d("CloudStream", "Loaded ${apis.size}")
        return apis.map { api -> MainApiAdapter(api) } + CloudStreamSettings()
    }
}
