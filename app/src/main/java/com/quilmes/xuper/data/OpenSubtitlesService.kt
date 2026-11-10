package com.quilmes.xuper.data

import android.content.Context
import com.quilmes.xuper.BuildConfig
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Resultado de búsqueda de OpenSubtitles.com. Cada opción representa un archivo descargable. */
data class OpenSubtitleOption(
    val fileId: Long,
    val fileName: String,
    val release: String,
    val language: String,
    val downloads: Int,
    val trusted: Boolean,
    val hearingImpaired: Boolean,
)

data class DownloadedOpenSubtitle(
    val file: java.io.File,
    val language: String,
    val displayName: String,
)

/** Cliente REST oficial de OpenSubtitles.com API v1. Las operaciones de red corren en IO. */
object OpenSubtitlesService {
    private const val API_BASE = "https://api.opensubtitles.com/api/v1"
    private const val USER_AGENT = "XuperQuilmesTV v0.1.0"
    private const val MAX_SUBTITLE_BYTES = 8 * 1024 * 1024

    private fun connection(url: String, method: String = "GET"): HttpURLConnection {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 12_000
            readTimeout = 22_000
            instanceFollowRedirects = true
            setRequestProperty("Api-Key", BuildConfig.OPEN_SUBTITLES_API_KEY)
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json")
        }
        return conn
    }

    private fun readResponse(conn: HttpURLConnection): String {
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        if (code !in 200..299) {
            val message = runCatching { JSONObject(body).optString("message") }.getOrNull().orEmpty()
            throw IllegalStateException(message.ifBlank { "OpenSubtitles respondió HTTP $code" })
        }
        return body
    }

    suspend fun search(
        title: String,
        contentId: String,
    ): List<OpenSubtitleOption> = withContext(Dispatchers.IO) {
        check(BuildConfig.OPEN_SUBTITLES_API_KEY.isNotBlank()) { "Falta configurar OpenSubtitles." }
        val tvMatch = Regex("^tmdb-tv-(\\d+)\\|s(\\d+)\\|e(\\d+)$").matchEntire(contentId)
        val movieMatch = Regex("^tmdb-movie-(\\d+)$").matchEntire(contentId)
        val params = sortedMapOf<String, String>()
        params["languages"] = "en,es"
        when {
            tvMatch != null -> {
                val (showId, season, episode) = tvMatch.destructured
                params["parent_tmdb_id"] = showId.toLongOrNull()?.toString() ?: showId
                params["type"] = "episode"
                params["season_number"] = season.toIntOrNull()?.toString() ?: season
                params["episode_number"] = episode.toIntOrNull()?.toString() ?: episode
            }
            movieMatch != null -> {
                val id = movieMatch.groupValues[1]
                params["tmdb_id"] = id.toLongOrNull()?.toString() ?: id
                params["type"] = "movie"
            }
            else -> {
                val cleaned = title.trim().take(100)
                require(cleaned.isNotBlank()) { "No se pudo identificar el título para buscar subtítulos." }
                params["query"] = cleaned.lowercase(Locale.ROOT)
            }
        }
        val query = params.entries.joinToString("&") { (key, value) ->
            "$key=${URLEncoder.encode(value, "UTF-8")}".replace("+", "%20")
        }
        val conn = connection("$API_BASE/subtitles?$query")
        try {
            val root = JSONObject(readResponse(conn))
            val data = root.optJSONArray("data") ?: return@withContext emptyList()
            buildList {
                for (i in 0 until data.length()) {
                    val attributes = data.optJSONObject(i)?.optJSONObject("attributes") ?: continue
                    val language = attributes.optString("language", "und")
                    if (language != "es" && language != "en" && !language.startsWith("es-")) continue
                    val files = attributes.optJSONArray("files") ?: continue
                    val release = attributes.optString("release").ifBlank {
                        attributes.optJSONObject("feature_details")?.optString("title").orEmpty()
                    }
                    for (j in 0 until files.length()) {
                        val file = files.optJSONObject(j) ?: continue
                        val fileId = file.optLong("file_id", 0L)
                        if (fileId <= 0L) continue
                        val fileName = file.optString("file_name", "subtitle.srt")
                        add(
                            OpenSubtitleOption(
                                fileId = fileId,
                                fileName = fileName,
                                release = release,
                                language = language,
                                downloads = attributes.optInt("download_count", 0),
                                trusted = attributes.optBoolean("from_trusted", false),
                                hearingImpaired = attributes.optBoolean("hearing_impaired", false),
                            )
                        )
                    }
                }
            }.distinctBy { it.fileId }
                .sortedWith(compareByDescending<OpenSubtitleOption> { it.language.startsWith("es") }
                    .thenByDescending { it.trusted }
                    .thenByDescending { it.downloads })
                .take(40)
        } finally {
            conn.disconnect()
        }
    }

    suspend fun download(context: Context, option: OpenSubtitleOption, contentId: String): DownloadedOpenSubtitle =
        withContext(Dispatchers.IO) {
            val request = connection("$API_BASE/download", "POST").apply {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            val downloadUrl = try {
                request.outputStream.use { it.write(JSONObject().put("file_id", option.fileId).toString().toByteArray(Charsets.UTF_8)) }
                JSONObject(readResponse(request)).optString("link")
                    .takeIf { it.startsWith("https://", true) }
                    ?: throw IllegalStateException("OpenSubtitles no devolvió un enlace de descarga.")
            } finally {
                request.disconnect()
            }

            val fileConn = (URL(downloadUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 12_000
                readTimeout = 25_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", USER_AGENT)
            }
            val downloaded = try {
                if (fileConn.responseCode !in 200..299) throw IllegalStateException("No se pudo descargar el subtítulo (HTTP ${fileConn.responseCode}).")
                fileConn.inputStream.use { input ->
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (out.size() + count > MAX_SUBTITLE_BYTES) throw IllegalStateException("El archivo de subtítulos supera el límite permitido.")
                        out.write(buffer, 0, count)
                    }
                    out.toByteArray()
                }
            } finally {
                fileConn.disconnect()
            }

            val extracted = extractSubtitle(downloaded, option.fileName)
            val extension = extracted.first.substringAfterLast('.', "srt").lowercase(Locale.ROOT)
                .let { if (it in setOf("srt", "vtt", "ass", "ssa")) it else "srt" }
            val folder = java.io.File(context.filesDir, "opensubtitles").apply { mkdirs() }
            val safeId = contentId.hashCode().toUInt().toString(16)
            val safeName = "${safeId}_${option.fileId}.$extension"
            val target = java.io.File(folder, safeName)
            target.writeBytes(extracted.second)
            DownloadedOpenSubtitle(
                file = target,
                language = option.language,
                displayName = option.release.ifBlank { option.fileName },
            )
        }

    private fun extractSubtitle(bytes: ByteArray, originalName: String): Pair<String, ByteArray> {
        if (bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) continue
                    val ext = entry.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                    if (ext !in setOf("srt", "vtt", "ass", "ssa")) continue
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(8 * 1024)
                    while (true) {
                        val count = zip.read(buffer)
                        if (count < 0) break
                        if (out.size() + count > MAX_SUBTITLE_BYTES) throw IllegalStateException("El subtítulo comprimido supera el límite permitido.")
                        out.write(buffer, 0, count)
                    }
                    if (out.size() > 0) return entry.name to out.toByteArray()
                }
            }
            throw IllegalStateException("El ZIP no contiene un archivo SRT/VTT/ASS compatible.")
        }
        val name = originalName.substringAfterLast('/').ifBlank { "opensubtitles.srt" }
        return name to bytes
    }
}
