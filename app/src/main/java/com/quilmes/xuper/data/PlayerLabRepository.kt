package com.quilmes.xuper.data

import com.quilmes.xuper.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Error con mensaje listo para mostrar en pantalla. */
class PlayerLabException(message: String) : Exception(message)

/** Una calidad disponible (una entrada de `movie.torrents` de la API compatible con YTS). */
data class PlayerLabVariant(
    val quality: String,
    val type: String,
    val size: String,
    val sizeBytes: Long,
    val seeds: Int?,
    val peers: Int?,
    val videoCodec: String,
    val audioChannels: String,
    val torrentUrl: String?,
)

data class PlayerLabMovie(
    val tmdbId: String,
    val imdbId: String,
    val ytsTitle: String,
    val variants: List<PlayerLabVariant>,
)

/**
 * PlayerLab, paso 1 (port de `server.js` de Xuper Player Lab):
 * ID de TMDB -> IMDb (`external_ids`) -> catálogo YTS (`list_movies.json?query_term=<imdb>`) -> variantes.
 * Se exige coincidencia EXACTA de `imdb_code`: nunca se elige una película por parecido de título.
 *
 * La clave de TMDB sale de BuildConfig.TMDB_API_KEY (el workflow la toma del secret TMDB_API_KEY).
 */
class PlayerLabRepository(cacheDir: File) {
    private val externalIds = TmdbExternalIdResolver(cacheDir)

    suspend fun findMovie(catalogId: String): PlayerLabMovie = withContext(Dispatchers.IO) {
        val match = Regex("^tmdb-movie-(\\d+)$").matchEntire(catalogId)
            ?: throw PlayerLabException("PlayerLab solo funciona con películas que tengan ID de TMDB.")
        val tmdbId = match.groupValues[1]
        if (BuildConfig.TMDB_API_KEY.isBlank()) {
            throw PlayerLabException("Este APK se compiló sin la clave de TMDB. Revisá que el secret TMDB_API_KEY exista en GitHub (Settings > Secrets > Actions) y volvé a compilar.")
        }
        val imdbId = externalIds.resolve(catalogId)
            ?: throw PlayerLabException("TMDB no devolvió un ID de IMDb para esta película (o no hubo conexión con TMDB).")

        var lastError: Exception? = null
        for (base in YTS_BASES) {
            try {
                return@withContext queryYts(base, tmdbId, imdbId)
            } catch (e: PlayerLabException) {
                throw e
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw PlayerLabException("No se pudo consultar el catálogo de calidades (${lastError?.message ?: "sin conexión"}).")
    }

    private fun queryYts(base: String, tmdbId: String, imdbId: String): PlayerLabMovie {
        val url = "$base/list_movies.json?query_term=$imdbId&limit=10&sort_by=seeds&order_by=desc"
        val root = JSONObject(httpGet(url))
        if (root.optString("status") != "ok") {
            throw Exception(root.optString("status_message").ifBlank { "la API devolvió un error" })
        }
        val movies = root.optJSONObject("data")?.optJSONArray("movies")
        var found: JSONObject? = null
        if (movies != null) {
            for (i in 0 until movies.length()) {
                val movie = movies.optJSONObject(i) ?: continue
                if (movie.optString("imdb_code").equals(imdbId, ignoreCase = true)) { found = movie; break }
            }
        }
        val movie = found
            ?: throw PlayerLabException("Se obtuvo IMDb $imdbId, pero el catálogo no tiene una coincidencia exacta para esta película.")
        val torrents = movie.optJSONArray("torrents")
        val variants = mutableListOf<PlayerLabVariant>()
        if (torrents != null) {
            for (i in 0 until torrents.length()) {
                val t = torrents.optJSONObject(i) ?: continue
                val torrentUrl = t.optString("url").takeIf { it.startsWith("https://") }
                if (torrentUrl == null) continue
                variants += PlayerLabVariant(
                    quality = t.optString("quality").ifBlank { "Calidad n/d" },
                    type = t.optString("type"),
                    size = t.optString("size"),
                    sizeBytes = t.optLong("size_bytes", 0L),
                    seeds = if (t.has("seeds")) t.optInt("seeds") else null,
                    peers = if (t.has("peers")) t.optInt("peers") else null,
                    videoCodec = t.optString("video_codec"),
                    audioChannels = t.optString("audio_channels"),
                    torrentUrl = torrentUrl,
                )
            }
        }
        if (variants.isEmpty()) throw PlayerLabException("No se encontraron calidades disponibles para esta película.")
        return PlayerLabMovie(
            tmdbId = tmdbId,
            imdbId = imdbId,
            ytsTitle = movie.optString("title_long").ifBlank { movie.optString("title") },
            variants = variants,
        )
    }

    private fun httpGet(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000
            readTimeout = 15_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", USER_AGENT)
        }
        try {
            if (conn.responseCode !in 200..299) throw Exception("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android TV) XuperQuilmesTV-PlayerLab"
        /** Misma API por defecto que el lab (YTS_API); el segundo es un respaldo si el primero no responde. */
        private val YTS_BASES = listOf("https://movies-api.accel.li/api/v2", "https://yts.mx/api/v2")
    }
}
