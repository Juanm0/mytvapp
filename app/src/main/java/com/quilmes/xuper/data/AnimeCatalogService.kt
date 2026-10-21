package com.quilmes.xuper.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Filas de la sección ANIME (todas de AniList, siempre sin contenido adulto). */
enum class AnimeRow(val label: String, internal val args: String) {
    TRENDING("Tendencias", "sort:[TRENDING_DESC]"),
    AIRING("En emisión", "status:RELEASING,sort:[POPULARITY_DESC]"),
    POPULAR("Populares", "sort:[POPULARITY_DESC]"),
    TOP("Mejor valoradas", "sort:[SCORE_DESC]"),
}

/**
 * Sección ANIME: fichas de AniList + TMDb.
 *
 * Cada anime de AniList se busca en TMDb; si hay una coincidencia segura (título parecido, año ±1 y género
 * Animación) se muestra la ficha de TMDb (que tiene temporadas, episodios, etc.) y se le asocia su id de AniList
 * para reproducir por la ruta de anime de FrameXTV. Si no, queda la ficha de AniList (`anilist-<id>`), con
 * lista de episodios 1..N y reproducción por FrameXTV (ruta de anime).
 */
class AnimeCatalogService(cacheDir: File, private val tmdb: TmdbCatalogService) {
    private val mapFile = File(cacheDir, "anime_tmdb_map.json")
    private val mapCache = ConcurrentHashMap<Long, JSONObject>() // anilistId -> {t: tmdbId|0, n, d, p, b, y, r}
    @Volatile private var mapLoaded = false
    private val episodeCounts = ConcurrentHashMap<Long, Int>()
    private val rowCache = ConcurrentHashMap<AnimeRow, Pair<Long, List<Title>>>()
    private val rowCacheMs = 10 * 60 * 1000L

    private data class Entry(
        val id: Long, val romaji: String, val english: String, val native: String, val description: String,
        val cover: String, val banner: String, val year: Int?, val score: Int?, val episodes: Int,
    )

    suspend fun loadRow(row: AnimeRow): List<Title> = withContext(Dispatchers.IO) {
        rowCache[row]?.takeIf { System.currentTimeMillis() - it.first < rowCacheMs }?.let { return@withContext it.second }
        val entries = runCatching { fetch(row.args, 24, null) }.getOrDefault(emptyList())
        val titles = toTitles(entries)
        if (titles.isNotEmpty()) rowCache[row] = System.currentTimeMillis() to titles
        titles
    }

    suspend fun search(query: String): List<Title> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.length < 2) return@withContext emptyList()
        val entries = runCatching { fetch("search:\$q", 15, q) }.getOrDefault(emptyList())
        toTitles(entries)
    }

    // ---- Fichas de AniList (sin equivalente en TMDb): temporadas/episodios sintéticos ----

    suspend fun seasons(titleId: String): List<TmdbSeason> {
        val id = aniListId(titleId) ?: return emptyList()
        val count = episodeCount(id)
        return listOf(TmdbSeason(1, "Episodios", count, "", ""))
    }

    suspend fun episodes(titleId: String): List<TmdbEpisode> {
        val id = aniListId(titleId) ?: return emptyList()
        return (1..episodeCount(id)).map { TmdbEpisode(it, "Episodio $it", "", "", null) }
    }

    suspend fun episodeCount(aniListId: Long): Int {
        episodeCounts[aniListId]?.let { return it }
        val fetched = withContext(Dispatchers.IO) {
            runCatching {
                val json = post("query{Media(id:$aniListId,type:ANIME){episodes nextAiringEpisode{episode}}}", null)
                json?.optJSONObject("data")?.optJSONObject("Media")?.let { countOf(it) }
            }.getOrNull()
        } ?: 12
        episodeCounts[aniListId] = fetched
        return fetched
    }

    private fun aniListId(titleId: String): Long? = Regex("^anilist-(\\d+)$").matchEntire(titleId)?.groupValues?.get(1)?.toLongOrNull()

    // ---- Conversión AniList -> Title (preferencia por TMDb) ----

    private suspend fun toTitles(entries: List<Entry>): List<Title> = coroutineScope {
        loadMap()
        val semaphore = Semaphore(4)
        val result = entries.map { entry ->
            async {
                episodeCounts[entry.id] = entry.episodes
                semaphore.acquire()
                try { toTitle(entry) } finally { semaphore.release() }
            }
        }.awaitAll().distinctBy { it.id }
        saveMap()
        result
    }

    private suspend fun toTitle(entry: Entry): Title {
        val cached = mapCache[entry.id] ?: run {
            val names = listOf(entry.romaji, entry.english, entry.native)
            val match = runCatching { tmdb.matchAnimeTv(names, entry.year) }.getOrNull()
            val json = if (match == null) JSONObject().put("t", 0L) else JSONObject()
                .put("t", match.id.removePrefix("tmdb-tv-").toLongOrNull() ?: 0L)
                .put("n", match.name).put("d", match.description).put("p", match.posterUrl)
                .put("b", match.backdropUrl).put("y", match.year.orEmpty()).put("r", match.rating.orEmpty())
            mapCache[entry.id] = json
            json
        }
        val tmdbId = cached.optLong("t", 0L)
        if (tmdbId > 0L) {
            // Prioridad TMDb. Se registra que es anime y su id de AniList para usar la ruta de anime de FrameXTV.
            WebSourceRegistry.markAnime(tmdbId.toString())
            WebSourceRegistry.setAniListId(tmdbId.toString(), entry.id)
            return Title(
                id = "tmdb-tv-$tmdbId", name = cached.optString("n"), description = cached.optString("d"),
                posterUrl = cached.optString("p"), backdropUrl = cached.optString("b").ifBlank { cached.optString("p") },
                streamUrl = "", isLive = false, year = cached.optString("y").takeIf { it.isNotBlank() },
                rating = cached.optString("r").takeIf { it.isNotBlank() }, contentKind = ContentKind.SERIES,
            )
        }
        return Title(
            id = "anilist-${entry.id}", name = entry.english.ifBlank { entry.romaji }.ifBlank { entry.native },
            description = entry.description, posterUrl = entry.cover, backdropUrl = entry.banner.ifBlank { entry.cover },
            streamUrl = "", isLive = false, year = entry.year?.toString(),
            rating = entry.score?.let { String.format(Locale.US, "%.1f", it / 10.0) },
            contentKind = ContentKind.SERIES,
        )
    }

    // ---- AniList GraphQL ----

    private fun fetch(args: String, perPage: Int, search: String?): List<Entry> {
        val full = args
        val decl = if (search != null) "(\$q:String)" else ""
        val query = "query$decl{Page(perPage:$perPage){media($full,type:ANIME,isAdult:false,format_in:[TV,TV_SHORT,ONA]){" +
            "id title{romaji english native} description(asHtml:false) coverImage{extraLarge large} bannerImage " +
            "startDate{year} averageScore episodes nextAiringEpisode{episode}}}}"
        val media = post(query, search)?.optJSONObject("data")?.optJSONObject("Page")?.optJSONArray("media") ?: return emptyList()
        return buildList {
            for (i in 0 until media.length()) {
                val m = media.optJSONObject(i) ?: continue
                val id = m.optLong("id", 0L)
                if (id <= 0L) continue
                val title = m.optJSONObject("title")
                val cover = m.optJSONObject("coverImage")
                add(Entry(
                    id = id, romaji = title.strOrEmpty("romaji"), english = title.strOrEmpty("english"), native = title.strOrEmpty("native"),
                    description = m.strOrEmpty("description").replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ").trim(),
                    cover = cover.strOrEmpty("extraLarge").ifBlank { cover.strOrEmpty("large") },
                    banner = m.strOrEmpty("bannerImage"),
                    year = m.optJSONObject("startDate")?.optInt("year", 0)?.takeIf { it > 0 },
                    score = m.optInt("averageScore", 0).takeIf { it > 0 },
                    episodes = countOf(m),
                ))
            }
        }
    }

    private fun countOf(media: JSONObject): Int =
        media.optInt("episodes", 0).takeIf { it > 0 }
            ?: media.optJSONObject("nextAiringEpisode")?.optInt("episode", 0)?.minus(1)?.takeIf { it > 0 }
            ?: 12

    private fun post(query: String, search: String?): JSONObject? {
        val body = JSONObject().put("query", query).apply { if (search != null) put("variables", JSONObject().put("q", search)) }.toString()
        val connection = (URL("https://graphql.anilist.co").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 7000; readTimeout = 10000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "XuperQuilmesTV/1.0 AndroidTV")
        }
        try {
            connection.outputStream.use { it.write(body.toByteArray()) }
            if (connection.responseCode !in 200..299) return null
            return JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally { connection.disconnect() }
    }

    // ---- Caché en disco de las equivalencias AniList -> TMDb ----

    @Synchronized private fun loadMap() {
        if (mapLoaded) return
        mapLoaded = true
        runCatching {
            if (!mapFile.exists()) return
            val root = JSONObject(mapFile.readText())
            root.keys().forEach { key -> key.toLongOrNull()?.let { id -> root.optJSONObject(key)?.let { mapCache[id] = it } } }
        }
    }

    @Synchronized private fun saveMap() {
        runCatching {
            val root = JSONObject()
            mapCache.forEach { (k, v) -> root.put(k.toString(), v) }
            mapFile.parentFile?.mkdirs()
            mapFile.writeText(root.toString())
        }
    }
}
