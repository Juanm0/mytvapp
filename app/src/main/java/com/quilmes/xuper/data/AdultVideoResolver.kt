package com.quilmes.xuper.data

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class ResolvedAdultVideo(
    val title: String,
    val url: String,
    val type: String,
    val quality: String,
    val headers: Map<String, String>,
)

/**
 * Ejecuta el mismo extractor yt-dlp probado en CineSrc, pero embebido en el APK mediante Chaquopy.
 * No requiere instalar Python ni Termux en el Android TV.
 */
object AdultVideoResolver {
    @Volatile private var started = false
    private val startLock = Any()

    suspend fun resolve(context: Context, viewKey: String): ResolvedAdultVideo = withContext(Dispatchers.IO) {
        check(AdultContentSettings.isEnabled()) { "El perfil +18 está bloqueado." }
        require(AdultCatalogService.isValidViewKey(viewKey)) { "Identificador de video inválido." }

        if (!started) synchronized(startLock) {
            if (!started) {
                if (!Python.isStarted()) Python.start(AndroidPlatform(context.applicationContext))
                started = true
            }
        }

        val jsonText = Python.getInstance()
            .getModule("adult_extractor")
            .callAttr("resolve", viewKey)
            .toString()
        val json = JSONObject(jsonText)
        val rawHeaders = json.optJSONObject("headers") ?: JSONObject()
        val headers = buildMap {
            val keys = rawHeaders.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = rawHeaders.optString(key)
                if (value.isNotBlank()) put(key, value)
            }
        }
        val url = json.getString("streamUrl")
        require(url.startsWith("https://")) { "El extractor devolvió una URL insegura." }
        val type = json.optString("streamType", "hls").lowercase()
        require(type == "hls" || type == "mp4") { "Formato de video no compatible." }

        ResolvedAdultVideo(
            title = json.optString("title", "Video +18"),
            url = url,
            type = type,
            quality = json.optString("quality", "Auto"),
            headers = headers,
        )
    }
}
