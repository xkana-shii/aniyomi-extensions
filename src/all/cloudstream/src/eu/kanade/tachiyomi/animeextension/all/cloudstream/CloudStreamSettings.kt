package eu.kanade.tachiyomi.animeextension.all.cloudstream

import android.annotation.SuppressLint
import android.app.Application
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.TvType
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.collections.flatMap
import kotlin.collections.ifEmpty


class CloudStreamSettings() : AnimeHttpSource(), ConfigurableAnimeSource {
    override val lang: String = "none"
    override val name: String = "! CloudStream Settings"

    private val context = Injekt.get<Application>()
    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    @SuppressLint("ApplySharedPref")
    override fun setupPreferenceScreen(screen: PreferenceScreen) {

        val pluginsPref: MultiSelectListPreference = MultiSelectListPreference(screen.context).apply {
            key = "EXTENSIONS"
            title = "Choose plugins to install/uninstall"
            summary = "Loading..."
            dialogTitle = "Check/uncheck plugins to install/uninstall"
            entries = emptyArray()
            entryValues = emptyArray()
            setDefaultValue(emptySet<String>())
            setEnabled(false)
        }

        val repoFilterPref = MultiSelectListPreference(screen.context).apply {
            key = "FILTER_REPO2"
            title = "Filter by repository"
            summary = "${preferences.getStringSet(key, emptySet())?.size} repo(s) selected"
            entries = emptyArray()
            entryValues = emptyArray()
            setDefaultValue(emptySet<String>())
        }

        val langFilterPref = MultiSelectListPreference(screen.context).apply {
            key = "FILTER_LANGUAGE"
            title = "Filter by language"
            entries = emptyArray()
            entryValues = emptyArray()
            setDefaultValue(emptySet<String>())
        }

        val reposPref = EditTextPreference(screen.context).apply {
            key = "REPOS"
            title = "Plugin repositories"
            dialogMessage = "CloudStream repositories (one per line):"
            summary = "${preferences.getString(key, "")?.lines()?.filter { it.isNotBlank() }?.size} repo(s) added"
            setDefaultValue("")
        }

        val tvTypesFilterPref = MultiSelectListPreference(screen.context).apply {
            key = "FILTER_TVTYPE"
            title = "Filter by type"
            entries = TvType.values().map {it.name}.toTypedArray()
            entryValues = TvType.values().map {it.name}.toTypedArray()
            setDefaultValue(TvType.values().map {it.name}.toSet())
            summary = "${preferences.getStringSet(key, emptySet())?.size}/${entries.size} selected"
        }

        val statusFilterPref = MultiSelectListPreference(screen.context).apply {
            key = "FILTER_STATUS2"
            title = "Filter by status"
            entries = arrayOf("Down", "Ok", "Slow", "Beta")
            entryValues = arrayOf("0", "1", "2", "3")
            setDefaultValue(setOf("0", "1", "2", "3"))
            summary = "${preferences.getStringSet(key, emptySet())?.size}/${entries.size} selected"
        }

        val fm = FilterManager(preferences, pluginsPref, repoFilterPref, langFilterPref)

        // Change Listeners
        pluginsPref.setOnPreferenceChangeListener { pref, newValue ->
            preferences.edit()
                .putStringSet(pref.key, newValue as Set<String>)
                .commit() // save now because app restarts later

            val selected = newValue
            val oldSelected = (pref as MultiSelectListPreference).values
            val removed = oldSelected - selected
            val added = selected - oldSelected

            // Disable temporarily
            pref.setEnabled(false)

            val scope = CoroutineScope(Dispatchers.IO)

            scope.launch {
                // Install
                added.forEach { pluginUrl ->
                    val pluginFile = PluginManager.downloadPluginToFile(pluginUrl)
                    // validate if plugin loads
                    if (pluginFile != null) {
                        PluginLoader.loadPlugin(context, pluginFile)
                    }
                }

                // Remove
                removed.forEach { pluginUrl ->
                    PluginManager.deletePluginFile(pluginUrl)
                }

                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Installed ${added.size}, removed ${removed.size} plugins", Toast.LENGTH_SHORT).show()
                    pref.setEnabled(true)
                    restartApp(context)
                }
            }
            true
        }

        repoFilterPref.setOnPreferenceChangeListener { pref, newValue ->
            val newSelection = (newValue as Set<String>).intersect(fm.getRepos()) // exclude repos that were selected but removed
            preferences.edit().putStringSet(pref.key, newSelection as Set<String>).commit()
            fm.reloadReposAndPlugins()
            pref.summary = "${newSelection.size} repo(s) selected"
            true
        }

        langFilterPref.setOnPreferenceChangeListener { pref, newValue ->
            preferences.edit().putStringSet(pref.key, newValue as Set<String>).commit()
            fm.applyFilters()
            pref.summary = "${newValue.size}/${(pref as MultiSelectListPreference).entries.size} selected"
            true
        }

        reposPref.setOnPreferenceChangeListener { pref, newValue ->
            val newRepos = (newValue as String).lines().filter { it.isNotBlank() }.toSet()
            val oldRepos = preferences.getString("REPOS", "")
                ?.lines()
                ?.filter { it.isNotBlank() }
                .orEmpty()
                .toSet()

            preferences.edit().putString(pref.key, newValue).commit()

            val selectedRepos = preferences.getStringSet("FILTER_REPO2", emptySet())?.toMutableSet() ?: mutableSetOf()
            val added = newRepos - oldRepos
            selectedRepos.addAll(added)
            val removed = oldRepos - newRepos
            selectedRepos.removeAll(removed)
            preferences.edit().putStringSet("FILTER_REPO2", selectedRepos).commit()

            fm.reloadReposAndPlugins()
            pref.summary = "${newRepos.size} repo(s) added"
            true
        }

        tvTypesFilterPref.setOnPreferenceChangeListener { pref, newValue ->
            preferences.edit()
                .putStringSet(pref.key, newValue as Set<String>)
                .commit()
            fm.applyFilters()
            pref.summary = "${newValue.size}/${(pref as MultiSelectListPreference).entries.size} selected"
            true
        }

        statusFilterPref.setOnPreferenceChangeListener { pref, newValue ->
            preferences.edit()
                .putStringSet(pref.key, newValue as Set<String>)
                .commit()
            fm.applyFilters()
            pref.summary = "${newValue.size}/${(pref as MultiSelectListPreference).entries.size} selected"
            true
        }

        // Add preferences to screen
        screen.addPreference(reposPref)
        screen.addPreference(pluginsPref)  // add it first, we'll populate later
        EditTextPreference(screen.context).apply {
            summary = "Filters"
            setEnabled(false)
        }.also(screen::addPreference)
        screen.addPreference(repoFilterPref)
        screen.addPreference(langFilterPref)
        screen.addPreference(tvTypesFilterPref)
        screen.addPreference(statusFilterPref)
        EditTextPreference(screen.context).apply {
            summary = "Advanced"
            setEnabled(false)
        }.also(screen::addPreference)

        // Initial load of plugins list
        fm.reloadReposAndPlugins()

        SwitchPreferenceCompat(screen.context).apply {
            key = "PLUGINS_PURGE"
            title = "Purge all plugin files"
            setDefaultValue(false)
            summary = "${PluginManager.getPluginCount()} plugin(s) installed, ${APIHolder.allProviders.size} provider(s) loaded"
            setOnPreferenceClickListener { pref ->
                val switchPref = pref as SwitchPreferenceCompat
                switchPref.isChecked = false

                val scope = CoroutineScope(Dispatchers.IO)
                scope.launch {
                    setEnabled(false)
                    val success = PluginManager.deleteAllPluginFiles()

                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "All plugin files deleted? $success", Toast.LENGTH_SHORT).show()
                        setEnabled(true)
                        preferences.edit()
                            .putBoolean(pref.key, false)
                            .putStringSet("EXTENSIONS", emptySet<String>())
                            .commit()
                    }
                }

                false
            }
        }.also(screen::addPreference)

        //TODO button to remove plugins from removed repos
        SwitchPreferenceCompat(screen.context).apply {
            title = "Remove orphaned plugins"
            summary = "Will delete files that not listed in added repos"
            setEnabled(false)
        }.also(screen::addPreference)

    }

    /** Restart host application to force it to reload all plugins */
    fun restartApp(context: Application) {
        val packageManager = context.packageManager
        val intent = packageManager.getLaunchIntentForPackage(context.packageName)
        val componentName = intent?.component
        if (componentName != null) {
            val restartIntent = Intent.makeRestartActivityTask(componentName)
            context.startActivity(restartIntent)
            Runtime.getRuntime().exit(0) // kill old process after scheduling restart
        }
    }


    // Unused
    override val baseUrl: String = ""
    override val supportsLatest: Boolean = false
    override fun animeDetailsParse(response: Response): SAnime = throw UnsupportedOperationException()
    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()
    override fun latestUpdatesParse(response: Response): AnimesPage = throw UnsupportedOperationException()
    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()
    override fun popularAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException()
    override fun popularAnimeRequest(page: Int): Request = throw UnsupportedOperationException()
    override fun searchAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException()
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request  = throw UnsupportedOperationException()
}

class FilterManager(
    private val preferences: SharedPreferences,
    private val pluginsPref: MultiSelectListPreference,
    private val repoFilterPref: MultiSelectListPreference,
    private val langFilterPref: MultiSelectListPreference
) {
    private val scope = CoroutineScope(Dispatchers.IO)

    @Volatile private var cachedPlugins: List<SitePlugin> = emptyList()

    fun getRepos(): Set<String> =
        preferences.getString("REPOS", "")
            ?.lines()
            ?.filter { it.isNotBlank() }
            .orEmpty()
            .toSet()

    private fun getSelectedRepos(): Set<String> =
        preferences.getStringSet("FILTER_REPO2", emptySet()) ?: emptySet()

    private fun getSelectedTypes(): Set<String> =
        preferences.getStringSet("FILTER_TVTYPE", TvType.values().map { it.name }.toSet())
            ?: emptySet()

    private fun getSelectedStatus(): Set<String> =
        preferences.getStringSet("FILTER_STATUS2", setOf("0","1","2","3"))
            ?: emptySet()

    private fun getSelectedLanguages(): Set<String> =
        preferences.getStringSet("FILTER_LANGUAGE", emptySet()) ?: emptySet()

    /** Refetches SELECTED repos and updates plugins list */
    fun reloadReposAndPlugins() {
        pluginsPref.setEnabled(false)
        val allRepos = getRepos()
        val selectedRepos = getSelectedRepos()
        val repos = selectedRepos.ifEmpty { allRepos }

        // refresh FILTER_REPO2 entries dynamically
        repoFilterPref.apply {
            entries = allRepos.map { Uri.parse(it).path?.trimStart('/') ?: it }.toTypedArray()
            entryValues = allRepos.toTypedArray()
            values = selectedRepos
            summary = "${selectedRepos.size} repo(s) selected"
            setEnabled(allRepos.isNotEmpty())
        }

        scope.launch {
            cachedPlugins = RepositoryManager.getAllPlugins(repos)

            refreshFilters()
        }
    }

    /** Call this on any filter change except REPOS */
    fun applyFilters() {
        scope.launch {
            refreshFilters()
        }
    }

    private suspend fun refreshFilters() {
        val selectedTypes = getSelectedTypes()
        val selectedStatus = getSelectedStatus()
        val selectedLangs = getSelectedLanguages()

        val allPlugins = cachedPlugins

        // refresh FILTER_LANGUAGE dynamically
        withContext(Dispatchers.Main) {
            langFilterPref.apply {
                val langs = allPlugins
                    .mapNotNull { it.language }
                    .filter { it.isNotBlank() }
                    .distinct()
                    .sorted()

                entries = langs.toTypedArray()
                entryValues = langs.toTypedArray()
                setDefaultValue(langs.toSet())
                setEnabled(langs.isNotEmpty())
            }
        }

        // filtering logic
        val filteredPlugins = allPlugins.filter { plugin ->
            (plugin.tvTypes.isNullOrEmpty() || plugin.tvTypes.any { it in selectedTypes }) &&
                (plugin.status.toString() in selectedStatus) &&
                (selectedLangs.isEmpty() || plugin.language.isNullOrBlank() || plugin.language in selectedLangs)
        }

        withContext(Dispatchers.Main) {
            if (filteredPlugins.isEmpty()) {
                pluginsPref.apply {
                    summary = "No plugins available"
                    setEnabled(false)
                }
            } else {
                pluginsPref.apply {
                    entries = filteredPlugins.map {
                        "${it.name} (${it.language?.uppercase() ?: "ALL"})" +
                            (it.description.takeIf { d -> d != it.name }?.let { "\n    ⓘ $it" } ?: "")
                    }.toTypedArray()
                    entryValues = filteredPlugins.map { it.url }.toTypedArray()
                    summary = "Showing ${filteredPlugins.size} plugins"
                    setEnabled(true)
                }
            }
        }
    }
}
