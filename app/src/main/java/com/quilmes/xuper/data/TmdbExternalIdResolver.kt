package com.quilmes.xuper.data

import com.quilmes.xuper.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Resolves TMDB movie/TV IDs to their canonical IMDb IDs and caches successful lookups. */
class TmdbExternalIdResolver(cacheDir: File) {
    private val apiKey = BuildConfig.TMDB_API_KEY.trim()
    private val cacheFile = File(cacheDir, "tmdb_imdb_ids.tsv")
    private val cache = mutableMapOf<String, String>()

    suspend fun resolve(catalogId: String): String? = withContext(Dispatchers.IO) {
        val match = Regex("^tmdb-(movie|tv)-(\\d+)(?:\\|s\\d+\\|e\\d+)?$").matchEntire(catalogId) ?: return@withContext null
        val type = match.groupValues[1]
        val tmdbId = match.groupValues[2]
        synchronized(cache) {
            if (cache.isEmpty() && cacheFile.exists()) cacheFile.readLines().forEach { line ->
                val parts = line.split('\t')
                if (parts.size == 2) cache[parts[0]] = parts[1]
            }
            cache["$type-$tmdbId"]?.let { return@withContext it }
        }
        if (apiKey.isBlank()) return@withContext null
        val conn = (URL("https://api.themoviedb.org/3/$type/$tmdbId/external_ids?api_key=$apiKey").openConnection() as HttpURLConnection).apply {
            connectTimeout = 7000
            readTimeout = 9000
            setRequestProperty("Accept", "application/json")
        }
        try {
            val json = conn.inputStream.bufferedReader().use { JSONObject(it.readText()) }
            val imdb = json.optString("imdb_id").takeIf { Regex("^tt\\d{5,12}$").matches(it) } ?: return@withContext null
            synchronized(cache) {
                cache["$type-$tmdbId"] = imdb
                cacheFile.parentFile?.mkdirs()
                cacheFile.writeText(cache.entries.joinToString("\n") { "${it.key}\t${it.value}" })
            }
            imdb
        } catch (_: Exception) { null } finally { conn.disconnect() }
    }
}
