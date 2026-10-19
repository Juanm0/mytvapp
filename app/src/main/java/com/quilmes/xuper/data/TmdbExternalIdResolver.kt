package com.quilmes.xuper.data

import com.quilmes.xuper.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Resolves a TMDB movie/series ID to its canonical IMDb ID and caches the mapping locally. */
class TmdbExternalIdResolver(private val cacheDir: File) {
    private val apiKey = BuildConfig.TMDB_API_KEY.trim()
    private val cacheFile = File(cacheDir, "tmdb_imdb_ids.tsv")
    private val cache = linkedMapOf<String, String>()
    private var loaded = false

    suspend fun imdbId(catalogId: String): String? = withContext(Dispatchers.IO) {
        val match = Regex("^tmdb-(movie|tv)-(\\d+)(?:\\|s\\d+\\|e\\d+)?$").matchEntire(catalogId) ?: return@withContext null
        if (apiKey.isBlank()) return@withContext null
        val type = match.groupValues[1]
        val tmdbId = match.groupValues[2]
        val key = "$type:$tmdbId"
        synchronized(cache) {
            if (!loaded) {
                cacheFile.takeIf { it.isFile }?.forEachLine { line ->
                    val parts = line.split('\t', limit = 2)
                    if (parts.size == 2 && parts[1].matches(Regex("tt\\d+"))) cache[parts[0]] = parts[1]
                }
                loaded = true
            }
            cache[key]?.let { return@withContext it }
        }
        val endpoint = if (type == "tv") "tv" else "movie"
        val encodedKey = URLEncoder.encode(apiKey, "UTF-8")
        val url = URL("https://api.themoviedb.org/3/$endpoint/$tmdbId/external_ids?api_key=$encodedKey")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 7000
            readTimeout = 9000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "XuperQuilmesTV/1.0 AndroidTV")
        }
        val id = try {
            if (connection.responseCode !in 200..299) return@withContext null
            JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                .optString("imdb_id").takeIf { it.matches(Regex("tt\\d+")) }
        } finally { connection.disconnect() }
        if (id != null) synchronized(cache) {
            cache[key] = id
            runCatching {
                cacheFile.parentFile?.mkdirs()
                cacheFile.writeText(cache.entries.joinToString("\n") { "${it.key}\t${it.value}" } + "\n")
            }
        }
        id
    }
}
