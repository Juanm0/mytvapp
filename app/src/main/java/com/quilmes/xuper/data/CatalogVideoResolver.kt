package com.quilmes.xuper.data

import android.content.Context
import com.chaquo.python.Python
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URI

data class ResolvedCatalogSubtitle(
    val url: String,
    val language: String,
    val label: String,
    val extension: String,
)

data class ResolvedCatalogVideo(
    val url: String,
    val type: String,
    val quality: String,
    val headers: Map<String, String>,
    val subtitles: List<ResolvedCatalogSubtitle>,
)

/** yt-dlp fallback for the existing TMDB/VideoAPI movie and episode flow. */
object CatalogVideoResolver {
    suspend fun resolve(
        context: Context,
        pageUrl: String,
        maxAttempts: Int = 3,
        onAttempt: (Int) -> Unit = {},
    ): ResolvedCatalogVideo = withContext(Dispatchers.IO) {
        val page = URI(pageUrl)
        require(page.scheme == "https" && WebSourceRegistry.isVideoApiHost(page.host)) {
            "La URL no corresponde a un proveedor de video configurado."
        }
        require(maxAttempts in 1..4) { "Cantidad de intentos no válida." }
        ChaquopyRuntime.ensureStarted(context)

        var lastError: Exception? = null
        for (attempt in 1..maxAttempts) {
            onAttempt(attempt)
            try {
                val jsonText = Python.getInstance()
                    .getModule("catalog_extractor")
                    .callAttr("resolve", pageUrl)
                    .toString()
                val json = JSONObject(jsonText)
                val streamUrl = json.getString("streamUrl")
                val streamUri = URI(streamUrl)
                require(streamUri.scheme == "https" && !streamUri.host.isNullOrBlank() && streamUri.userInfo == null) {
                    "yt-dlp devolvió una dirección de video no segura."
                }
                val type = json.optString("streamType", "hls").lowercase()
                require(type == "hls" || type == "mp4") { "El formato extraído no es compatible con Media3." }

                val rawHeaders = json.optJSONObject("headers") ?: JSONObject()
                val headers = buildMap {
                    val keys = rawHeaders.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val value = rawHeaders.optString(key)
                        if (key.isNotBlank() && value.isNotBlank()) put(key, value)
                    }
                }
                val rawSubtitles = json.optJSONArray("subtitles")
                val subtitles = buildList {
                    for (index in 0 until (rawSubtitles?.length() ?: 0)) {
                        val item = rawSubtitles?.optJSONObject(index) ?: continue
                        val url = item.optString("url")
                        val uri = runCatching { URI(url) }.getOrNull() ?: continue
                        if (uri.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo != null) continue
                        val extension = item.optString("ext").lowercase()
                        if (extension !in setOf("vtt", "srt", "ass", "ssa", "ttml")) continue
                        add(ResolvedCatalogSubtitle(
                            url = url,
                            language = item.optString("language", "und").ifBlank { "und" },
                            label = item.optString("label", "Subtítulo").ifBlank { "Subtítulo" },
                            extension = extension,
                        ))
                    }
                }
                return@withContext ResolvedCatalogVideo(
                    url = streamUrl,
                    type = type,
                    quality = json.optString("quality", "Auto"),
                    headers = headers,
                    subtitles = subtitles,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                lastError = e
                if (attempt < maxAttempts) delay(1_000L * (1 shl (attempt - 1)))
            }
        }
        throw IllegalStateException(
            lastError?.message ?: "yt-dlp no pudo resolver este título.",
            lastError,
        )
    }
}
