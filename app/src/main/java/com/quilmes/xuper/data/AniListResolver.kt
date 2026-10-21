package com.quilmes.xuper.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * Traduce una serie de TMDb a su id de AniList (lo que usa el embed de anime de FrameXTV).
 * TMDb no entrega ese id, así que se busca en la API pública de AniList por título + año de estreno
 * y se guarda en disco (`anilist_ids.json`) para no repetir la consulta.
 */
internal class AniListResolver(cacheDir: File) {
    private val file = File(cacheDir, "anilist_ids.json")
    private val memory = ConcurrentHashMap<String, Long>()
    private val failedThisSession: MutableSet<String> = ConcurrentHashMap.newKeySet()
    @Volatile private var loaded = false

    fun cached(tmdbId: String): Long? { load(); return memory[tmdbId] }

    suspend fun resolve(tmdbId: String, names: List<String>, year: Int?): Long? = withContext(Dispatchers.IO) {
        cached(tmdbId)?.let { return@withContext it }
        if (tmdbId in failedThisSession) return@withContext null
        val queries = names.map { it.trim() }.filter { it.isNotBlank() }.distinct().take(2)
        val wanted = names.map(::norm).filter { it.isNotBlank() }.toSet()
        if (queries.isEmpty()) return@withContext null

        var bestId = 0L
        var bestScore = 0.0
        for (q in queries) {
            val media = runCatching { search(q) }.getOrNull() ?: continue
            for (i in 0 until media.length()) {
                val m = media.optJSONObject(i) ?: continue
                val titles = m.optJSONObject("title")
                val candidates = listOf("romaji", "english", "native")
                    .map { norm(titles.strOrEmpty(it)) }.filter { it.isNotBlank() }
                var score = candidates.maxOfOrNull { c -> wanted.maxOfOrNull { w -> similarity(c, w) } ?: 0.0 } ?: 0.0
                val y = m.optJSONObject("startDate")?.optInt("year", 0) ?: 0
                if (year != null && y > 0) score += if (abs(y - year) <= 1) 0.3 else -0.4
                if (score > bestScore) { bestScore = score; bestId = m.optLong("id", 0L) }
            }
            if (bestScore >= 1.2) break // título exacto + año coincidente: no hace falta la segunda búsqueda
        }
        if (bestId > 0L && bestScore >= 0.75) {
            memory[tmdbId] = bestId
            save()
            bestId
        } else {
            failedThisSession.add(tmdbId)
            null
        }
    }

    private fun search(query: String): JSONArray? {
        val body = JSONObject()
            .put("query", "query(\$q:String){Page(perPage:10){media(search:\$q,type:ANIME,format_in:[TV,TV_SHORT,ONA]){id title{romaji english native} startDate{year}}}}")
            .put("variables", JSONObject().put("q", query))
            .toString()
        val connection = (URL("https://graphql.anilist.co").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 6000
            readTimeout = 8000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "XuperQuilmesTV/1.0 AndroidTV")
        }
        try {
            connection.outputStream.use { it.write(body.toByteArray()) }
            if (connection.responseCode !in 200..299) return null
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            return json.optJSONObject("data")?.optJSONObject("Page")?.optJSONArray("media")
        } finally { connection.disconnect() }
    }

    private fun norm(value: String) = TitleMatch.norm(value)
    private fun similarity(a: String, b: String) = TitleMatch.similarity(a, b)

    @Synchronized private fun load() {
        if (loaded) return
        loaded = true
        runCatching {
            if (!file.exists()) return
            val root = JSONObject(file.readText())
            root.keys().forEach { key -> memory[key] = root.optLong(key, 0L).takeIf { it > 0L } ?: return@forEach }
        }
    }

    @Synchronized private fun save() {
        runCatching {
            val root = JSONObject()
            memory.forEach { (k, v) -> root.put(k, v) }
            file.parentFile?.mkdirs()
            file.writeText(root.toString())
        }
    }
}
