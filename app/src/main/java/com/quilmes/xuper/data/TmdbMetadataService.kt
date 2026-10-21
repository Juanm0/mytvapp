package com.quilmes.xuper.data

import android.util.Log
import com.quilmes.xuper.BuildConfig
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

/** Enriches real M3U VOD entries with TMDB artwork and metadata; it never supplies stream URLs. */
internal class TmdbMetadataService(private val cacheDir: File) {
    private val apiKey = BuildConfig.TMDB_API_KEY.trim()
    private val cacheFile = File(cacheDir, "tmdb_metadata.tsv")
    private val metadataCache = linkedMapOf<String, Title>()
    private var cacheLoaded = false

    val enabled: Boolean get() = apiKey.isNotBlank()

    suspend fun enrich(entries: List<EntryForMetadata>): List<EntryForMetadata> {
        if (!enabled || entries.isEmpty()) return entries
        return withContext(Dispatchers.IO) {
            loadCache()
            val lookups = AtomicInteger(0)
            val permits = Semaphore(4)
            coroutineScope {
                entries.map { item -> async {
                    val title = item.title
                    if (title.isLive || title.contentKind !in setOf(ContentKind.MOVIE, ContentKind.SERIES)) return@async item
                    val query = cleanQuery(title.name, title.contentKind)
                    if (query.isBlank()) return@async item
                    // El ajuste +18 cambia los resultados posibles: no mezclar entradas del caché entre ambos modos.
                    val adultPrefix = if (AdultContentSettings.isEnabled()) "+18|" else ""
                    val key = "$adultPrefix${title.contentKind}|${normalize(query)}"
                    val cached = synchronized(metadataCache) { metadataCache[key] }
                    if (cached != null) return@async item.copy(title = merge(title, cached))
                    // Keep first launch responsive on very large lists; later launches use the disk cache.
                    if (lookups.incrementAndGet() > 60) return@async item
                    val metadata = runCatching { permits.withPermit { lookup(query, title.contentKind) } }.getOrNull()
                    if (metadata != null) {
                        synchronized(metadataCache) { metadataCache[key] = metadata; saveCacheLocked() }
                        item.copy(title = merge(title, metadata))
                    } else item
                } }.awaitAll()
            }
        }
    }

    private fun lookup(query: String, kind: ContentKind): Title? {
        val endpoint = if (kind == ContentKind.SERIES) "tv" else "movie"
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = URL(AdultContentSettings.applyIncludeAdult(
            "https://api.themoviedb.org/3/search/$endpoint?api_key=$apiKey&language=es-AR&query=$encoded",
            AdultContentSettings.isEnabled(),
        ))
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 7000
            readTimeout = 9000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "XuperQuilmesTV/0.2")
        }
        try {
            val json = connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
            val results = json.optJSONArray("results") ?: return null
            val queryNorm = normalize(query)
            var best: JSONObject? = null
            var bestScore = 0.0
            for (i in 0 until results.length()) {
                val candidate = results.optJSONObject(i) ?: continue
                val candidateName = candidate.optString(if (endpoint == "tv") "name" else "title")
                val score = similarity(queryNorm, normalize(candidateName))
                if (score > bestScore) { bestScore = score; best = candidate }
            }
            val match = best ?: return null
            // Conservative match: avoid attaching a random poster/synopsis to an unrelated stream.
            if (bestScore < 0.58) return null
            val poster = match.optString("poster_path").takeIf { it.startsWith("/") }?.let { "https://image.tmdb.org/t/p/w500$it" }
            val backdrop = match.optString("backdrop_path").takeIf { it.startsWith("/") }?.let { "https://image.tmdb.org/t/p/w1280$it" }
            val date = match.optString(if (endpoint == "tv") "first_air_date" else "release_date")
            val rating = match.optDouble("vote_average", 0.0).takeIf { it > 0.0 }?.let { String.format(Locale.US, "%.1f", it) }
            val displayName = match.optString(if (endpoint == "tv") "name" else "title").ifBlank { query }
            return Title(
                id = "tmdb-${match.optLong("id")}", name = displayName,
                description = match.optString("overview"), posterUrl = poster.orEmpty(),
                backdropUrl = backdrop.orEmpty(), streamUrl = "", isLive = false,
                year = date.take(4).takeIf { it.length == 4 }, rating = rating, contentKind = kind,
            )
        } finally { connection.disconnect() }
    }

    private fun merge(original: Title, metadata: Title): Title = original.copy(
        // Keep episode identifiers (S01E02 / EP2) so the UI can still group and order episodes.
        name = if (original.contentKind == ContentKind.SERIES && hasEpisodeMarker(original.name)) original.name
            else metadata.name.ifBlank { original.name },
        description = metadata.description.ifBlank { original.description },
        posterUrl = metadata.posterUrl.ifBlank { original.posterUrl },
        backdropUrl = metadata.backdropUrl.ifBlank { original.backdropUrl },
        year = metadata.year ?: original.year,
        rating = metadata.rating ?: original.rating,
    )

    private fun hasEpisodeMarker(name: String): Boolean = Regex(
        "(?i)\\b(s\\s*\\d{1,2}\\s*e\\s*\\d{1,3}|\\d{1,2}x\\d{1,3}|season\\s*\\d+|temporada\\s*\\d+|episode\\s*\\d+|episodio\\s*\\d+|ep\\s*\\d+)\\b"
    ).containsMatchIn(name)

    private fun cleanQuery(raw: String, kind: ContentKind): String {
        var value = raw.replace(Regex("(?i)\\[[^]]*]|\\([^)]*(?:1080p|720p|4k|hevc|h264)[^)]*\\)"), " ")
        value = value.replace(Regex("(?i)\\b(1080p|720p|480p|2160p|4k|uhd|fhd|hd|sd|h264|h265|hevc|x264|x265)\\b"), " ")
        if (kind == ContentKind.SERIES) {
            value = value.replace(Regex("(?i)\\b(s\\s*\\d{1,2}\\s*e\\s*\\d{1,3}|\\d{1,2}x\\d{1,3}|season\\s*\\d+|temporada\\s*\\d+|episode\\s*\\d+|episodio\\s*\\d+|ep\\s*\\d+)\\b.*$"), " ")
        }
        value = value.replace(Regex("\\s*\\(?(19|20)\\d{2}\\)?\\s*$"), " ")
        return value.replace(Regex("[_ .-]+"), " ").trim()
    }

    private fun similarity(a: String, b: String): Double {
        if (a.isBlank() || b.isBlank()) return 0.0
        if (a == b) return 1.0
        if (a.contains(b) || b.contains(a)) return minOf(a.length, b.length).toDouble() / maxOf(a.length, b.length)
        val aa = a.split(' ').filter { it.length > 1 }.toSet()
        val bb = b.split(' ').filter { it.length > 1 }.toSet()
        if (aa.isEmpty() || bb.isEmpty()) return 0.0
        return aa.intersect(bb).size.toDouble() / maxOf(aa.size, bb.size)
    }

    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "").lowercase(Locale.ROOT).replace(Regex("[^a-z0-9 ]"), " ")
        .replace(Regex("\\s+"), " ").trim()

    private fun loadCache() {
        synchronized(metadataCache) {
            if (cacheLoaded) return
            cacheLoaded = true
            runCatching {
                if (cacheFile.exists()) cacheFile.forEachLine { line ->
                    val fields = line.split('\t')
                    if (fields.size >= 7) metadataCache[fields[0]] = Title(
                        id = fields[1], name = fields[2], description = fields[3], posterUrl = fields[4],
                        backdropUrl = fields[5], streamUrl = "", isLive = false,
                        year = fields[6].ifBlank { null }, rating = fields.getOrNull(7)?.ifBlank { null },
                        contentKind = runCatching { ContentKind.valueOf(fields.getOrElse(8) { "MOVIE" }) }.getOrDefault(ContentKind.MOVIE),
                    )
                }
            }.onFailure { Log.w("XuperTMDB", "No se pudo leer caché de metadatos", it) }
        }
    }

    private fun saveCacheLocked() {
        runCatching {
            cacheFile.parentFile?.mkdirs()
            val temp = File(cacheFile.parentFile, "tmdb_metadata.tmp")
            temp.bufferedWriter().use { writer -> metadataCache.forEach { (key, title) ->
                val fields = listOf(key, title.id, title.name, title.description, title.posterUrl, title.backdropUrl,
                    title.year.orEmpty(), title.rating.orEmpty(), title.contentKind.name)
                writer.write(fields.joinToString("\t") { it.replace('\t', ' ').replace('\n', ' ') })
                writer.newLine()
            } }
            temp.renameTo(cacheFile)
        }.onFailure { Log.w("XuperTMDB", "No se pudo guardar caché de metadatos", it) }
    }
}

/** Keeps the parser's entry group attached while metadata is resolved. */
internal data class EntryForMetadata(val group: String, val title: Title)
