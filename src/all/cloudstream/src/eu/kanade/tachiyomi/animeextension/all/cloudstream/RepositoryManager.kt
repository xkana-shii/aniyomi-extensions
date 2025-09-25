package eu.kanade.tachiyomi.animeextension.all.cloudstream

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URL

@Serializable
data class Repository(
    val name: String,
    val description: String? = null,
    val pluginLists: List<String> = emptyList()
)

@Serializable
data class SitePlugin(
    val url: String,
    val name: String,
    val version: Int,
    val description: String? = null,
    val status: Int = 1,                // plugin status (0=down, 1=ok, 2=slow, 3=beta)
    val internalName: String,
    val repositoryUrl: String?,
    // These types are yet to be mapped and used
    val tvTypes: List<String>? = null,
    val language: String? = null
)

object RepositoryManager {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun parseRepository(url: String): Repository? = withContext(Dispatchers.IO) {
        return@withContext try {
            val response = URL(url).readText()
            json.decodeFromString<Repository>(response)
        } catch (e: Exception) {
            null
        }
    }

    suspend fun parsePlugins(url: String): List<SitePlugin> = withContext(Dispatchers.IO) {
        return@withContext try {
            val response = URL(url).readText()
            json.decodeFromString<List<SitePlugin>>(response)
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun getRepoPlugins(repoUrl: String): List<SitePlugin> {
        val repo = parseRepository(repoUrl) ?: return emptyList()
        return repo.pluginLists.flatMap { parsePlugins(it) }
    }

    suspend fun getAllPlugins(repos: Set<String>): List<SitePlugin> {
        return repos.flatMap { getRepoPlugins(it) }.distinctBy { it.url}
    }
}
