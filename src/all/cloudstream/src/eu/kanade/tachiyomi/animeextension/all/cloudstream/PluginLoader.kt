package eu.kanade.tachiyomi.animeextension.all.cloudstream

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import dalvik.system.PathClassLoader
import java.io.File
import java.io.InputStreamReader

object PluginLoader {
    private const val PLUGIN_FOLDER = "cloudstream"
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    fun loadPlugin(context: Application, file: File): Boolean {
        try {
            // Pass extension's classloader as parent (not Aniyomi's) so Cloudstream core classes
            // (like BasePlugin, MainAPI) that are bundled in this library are available
            val loader = PathClassLoader(file.absolutePath, this::class.java.classLoader)
            loader.getResourceAsStream("manifest.json").use { stream ->
                if (stream == null) return false
                InputStreamReader(stream).use { reader ->
                    val manifest = parseJson(reader, BasePlugin.Manifest::class.java)
                    val pluginClass = loader.loadClass(manifest.pluginClassName)
                    val pluginInstance = pluginClass.getDeclaredConstructor().newInstance() as BasePlugin

                    if (pluginInstance is Plugin) {
                        Log.d("CloudStream","Class Plugin not yet fully supported: ${manifest.name} ($file)")

                        val hasOpenSettings = pluginInstance.openSettings != null
                        hasOpenSettings && handler.post {
                            Toast.makeText(context, "Plugin ${manifest.name} not supported", Toast.LENGTH_SHORT).show()
                        }
                        pluginInstance.load(context)
                    } else {
                        pluginInstance.load()
                    }
                    return true
                }
            }
        } catch (e: Throwable) {
            // Skip invalid plugins
            Log.d("CloudStream", "Failed to load $file")
            e.printStackTrace()
        }
        return false
    }

    fun loadAllPlugins(context: Application): List<MainAPI> {
        val internalDir = File(context.filesDir, PLUGIN_FOLDER)
        if (!internalDir.exists()) internalDir.mkdirs()

        val pluginFiles = internalDir.listFiles { f -> f.extension == "cs3" } ?: emptyArray()

        Log.d("CloudStream", "Found ${pluginFiles.size} plugins")

        pluginFiles.forEach { src ->
            val dest = File(internalDir, src.name)
            loadPlugin(context,dest)
        }

        // Plugins register themselves in APIHolder during load()
        return APIHolder.allProviders.toList()
    }
}
