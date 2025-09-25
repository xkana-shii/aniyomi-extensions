package eu.kanade.tachiyomi.animeextension.all.cloudstream

import android.app.Application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

object PluginManager {
    private val context = Injekt.get<Application>()
    private val extensionsDir = File(context.filesDir, "cloudstream")

    suspend fun downloadPluginToFile(pluginUrl: String): File? =
        withContext(Dispatchers.IO) {
            val file = File(extensionsDir, "${pluginUrl.hashCode()}.cs3")
            try {
                file.parentFile?.mkdirs()
                if (file.exists()) file.delete()
                file.createNewFile()

                val connection = URL(pluginUrl).openConnection() as HttpURLConnection
                connection.inputStream.use { input ->
                    file.outputStream().use { output ->
                        write(input, output)
                    }
                }
                connection.disconnect()
                file.setReadOnly() // Make it read-only (for Android 14+ to read dex files)
                file
            } catch (e: Exception) {
                null
            }
        }

    suspend fun deletePluginFile(pluginUrl: String): Boolean = withContext(Dispatchers.IO) {
        val file = File(extensionsDir, "${pluginUrl.hashCode()}.cs3")
        file.delete()
    }

    suspend fun deleteAllPluginFiles(): Boolean = withContext(Dispatchers.IO) {
        extensionsDir.deleteRecursively()
        extensionsDir.mkdirs()
    }

    fun getPluginCount(): Int = extensionsDir.listFiles()?.size ?: 0

    private fun write(stream: InputStream, output: OutputStream) {
        val input = BufferedInputStream(stream)
        val dataBuffer = ByteArray(512)
        var readBytes: Int
        while (input.read(dataBuffer).also { readBytes = it } != -1) {
            output.write(dataBuffer, 0, readBytes)
        }
    }
}
