package com.quilmes.xuper.data

import com.quilmes.xuper.BuildConfig
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
import java.net.URLEncoder
import java.util.Locale

/** Catálogo visual de películas/series desde TMDB. Nunca inventa enlaces de reproducción. */
class TmdbCatalogService(private val cacheDir: File) {
    private val apiKey = BuildConfig.TMDB_API_KEY.trim()
    private val cacheFile = File(cacheDir, "tmdb_home_catalog.json")
    private val cacheMaxAgeMs = 18 * 60 * 60 * 1000L

    suspend fun loadCatalog(): List<Category> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext emptyList()
        readCache()?.let { return@withContext it }

        val providers = listOf(
            "Netflix" to "8",
            "Prime Video" to "119",
            "Disney+" to "337",
            "Max" to "1899",
            "Apple TV+" to "350",
            "Paramount+" to "531",
            "Crunchyroll" to "283",
            "MUBI" to "11",
        )
        val baseRequests = listOf(
            asyncRequest("Tendencias · Películas", "trending/movie/week", ContentKind.MOVIE),
            asyncRequest("Tendencias · Series", "trending/tv/week", ContentKind.SERIES),
            asyncRequest("Series populares", "tv/popular", ContentKind.SERIES),
            asyncRequest("Películas populares", "movie/popular", ContentKind.MOVIE),
        )
        val platformRequests = providers.flatMap { (name, provider) ->
            listOf(
                asyncRequest("$name · Películas", "discover/movie?watch_region=AR&with_watch_providers=$provider&with_watch_monetization_types=flatrate", ContentKind.MOVIE),
                asyncRequest("$name · Series", "discover/tv?watch_region=AR&with_watch_providers=$provider&with_watch_monetization_types=flatrate", ContentKind.SERIES),
            )
        }
        val semaphore = Semaphore(4)
        val categories = coroutineScope {
            (baseRequests + platformRequests).map { loader ->
                async {
                    semaphore.acquire()
                    try { loader() } catch (_: Exception) { null } finally { semaphore.release() }
                }
            }.awaitAll().filterNotNull()
        }.filter { it.items.isNotEmpty() }
        if (categories.isNotEmpty()) writeCache(categories)
        categories
    }

    private fun asyncRequest(title: String, endpoint: String, kind: ContentKind): suspend () -> Category = {
        withContext(Dispatchers.IO) {
            val json = request(endpoint)
            val results = json.optJSONArray("results") ?: JSONArray()
            val items = buildList {
                for (i in 0 until results.length()) {
                    val item = results.optJSONObject(i) ?: continue
                    val id = item.optLong("id", 0L)
                    if (id <= 0L) continue
                    val isTv = kind == ContentKind.SERIES || endpoint.contains("/tv/") || endpoint.startsWith("tv/")
                    val candidateName = item.optString(if (isTv) "name" else "title")
                    if (candidateName.isBlank()) continue
                    val name = candidateName
                    val posterPath = item.optString("poster_path")
                    val backdropPath = item.optString("backdrop_path")
                    val date = item.optString(if (isTv) "first_air_date" else "release_date")
                    val rating = item.optDouble("vote_average", 0.0).takeIf { it > 0.0 }
                        ?.let { String.format(Locale.US, "%.1f", it) }
                    add(Title(
                        id = "tmdb-${if (isTv) "tv" else "movie"}-$id",
                        name = name,
                        description = item.optString("overview"),
                        posterUrl = imageUrl(posterPath, "w500"),
                        backdropUrl = imageUrl(backdropPath, "w1280").ifBlank { imageUrl(posterPath, "w500") },
                        streamUrl = "",
                        isLive = false,
                        year = date.take(4).takeIf { it.length == 4 },
                        rating = rating,
                        contentKind = if (isTv) ContentKind.SERIES else ContentKind.MOVIE,
                    ))
                }
            }.distinctBy { it.id }
            Category("tmdb-${slug(title)}", title, items)
        }
    }

    suspend fun search(query: String): List<Title> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (apiKey.isBlank() || q.length < 2) return@withContext emptyList()
        runCatching {
            val data = request("search/multi?query=${URLEncoder.encode(q, "UTF-8")}&include_adult=false")
            val results = data.optJSONArray("results") ?: JSONArray()
            buildList {
                for (i in 0 until results.length()) {
                    val item = results.optJSONObject(i) ?: continue
                    val mediaType = item.optString("media_type")
                    if (mediaType != "movie" && mediaType != "tv") continue
                    val id = item.optLong("id", 0L)
                    if (id <= 0L) continue
                    val isTv = mediaType == "tv"
                    val name = item.optString(if (isTv) "name" else "title")
                    if (name.isBlank()) continue
                    val date = item.optString(if (isTv) "first_air_date" else "release_date")
                    val rating = item.optDouble("vote_average", 0.0).takeIf { it > 0.0 }?.let { String.format(Locale.US, "%.1f", it) }
                    add(Title(
                        id = "tmdb-${if (isTv) "tv" else "movie"}-$id", name = name,
                        description = item.optString("overview"),
                        posterUrl = imageUrl(item.optString("poster_path"), "w500"),
                        backdropUrl = imageUrl(item.optString("backdrop_path"), "w1280").ifBlank { imageUrl(item.optString("poster_path"), "w500") },
                        streamUrl = "", isLive = false, year = date.take(4).takeIf { it.length == 4 }, rating = rating,
                        contentKind = if (isTv) ContentKind.SERIES else ContentKind.MOVIE,
                    ))
                }
            }.distinctBy { it.id }
        }.getOrDefault(emptyList())
    }

    suspend fun loadSeasons(titleId: String): List<TmdbSeason> = withContext(Dispatchers.IO) {
        val parsed = parseTmdbId(titleId) ?: return@withContext emptyList()
        if (apiKey.isBlank() || parsed.first != "tv") return@withContext emptyList()
        runCatching {
            val data = request("tv/${parsed.second}")
            val seasons = data.optJSONArray("seasons") ?: JSONArray()
            buildList {
                for (i in 0 until seasons.length()) {
                    val season = seasons.optJSONObject(i) ?: continue
                    val number = season.optInt("season_number", -1)
                    if (number < 0) continue
                    add(TmdbSeason(
                        number = number,
                        name = season.optString("name").ifBlank { "Temporada $number" },
                        episodeCount = season.optInt("episode_count", 0),
                        overview = season.optString("overview"),
                        posterUrl = imageUrl(season.optString("poster_path"), "w500"),
                    ))
                }
            }
        }.getOrDefault(emptyList())
    }

    suspend fun loadEpisodes(titleId: String, seasonNumber: Int): List<TmdbEpisode> = withContext(Dispatchers.IO) {
        val parsed = parseTmdbId(titleId) ?: return@withContext emptyList()
        if (apiKey.isBlank() || parsed.first != "tv") return@withContext emptyList()
        runCatching {
            val data = request("tv/${parsed.second}/season/$seasonNumber")
            val episodes = data.optJSONArray("episodes") ?: JSONArray()
            buildList {
                for (i in 0 until episodes.length()) {
                    val episode = episodes.optJSONObject(i) ?: continue
                    val episodeNumber = episode.optInt("episode_number", i + 1)
                    add(TmdbEpisode(
                        number = episodeNumber,
                        name = episode.optString("name").ifBlank { "Episodio $episodeNumber" },
                        overview = episode.optString("overview"),
                        stillUrl = imageUrl(episode.optString("still_path"), "w500"),
                        runtime = episode.optInt("runtime", 0).takeIf { it > 0 },
                    ))
                }
            }
        }.getOrDefault(emptyList())
    }

    /** Resuelve el IMDb ID que necesita el protocolo de streams de Stremio. */
    suspend fun getImdbId(titleId: String): String? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext null
        val parsed = parseTmdbId(titleId) ?: return@withContext null
        val endpoint = if (parsed.first == "tv") "tv" else "movie"
        runCatching {
            request("$endpoint/${parsed.second}/external_ids")
                .optString("imdb_id")
                .takeIf { it.matches(Regex("tt\\d{5,12}")) }
        }.getOrNull()
    }

    private fun parseTmdbId(id: String): Pair<String, Long>? {
        val match = Regex("^tmdb-(tv|movie)-(\\d+)$").matchEntire(id) ?: return null
        return match.groupValues[1] to (match.groupValues[2].toLongOrNull() ?: return null)
    }

    private fun request(path: String): JSONObject {
        val separator = if (path.contains('?')) '&' else '?'
        val url = URL("https://api.themoviedb.org/3/$path${separator}api_key=${URLEncoder.encode(apiKey, "UTF-8")}&language=es-AR&page=1&include_adult=false")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 10000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "XuperQuilmesTV/1.0 AndroidTV")
        }
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error("TMDB respondió HTTP $code")
            return JSONObject(body)
        } finally { connection.disconnect() }
    }

    private fun imageUrl(path: String, size: String): String =
        path.takeIf { it.startsWith("/") }?.let { "https://image.tmdb.org/t/p/$size$it" }.orEmpty()

    private fun slug(value: String) = value.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-").trim('-')

    private fun readCache(): List<Category>? {
        if (!cacheFile.exists() || System.currentTimeMillis() - cacheFile.lastModified() > cacheMaxAgeMs) return null
        return runCatching {
            val root = JSONObject(cacheFile.readText())
            val rows = root.optJSONArray("categories") ?: return null
            buildList {
                for (i in 0 until rows.length()) {
                    val row = rows.optJSONObject(i) ?: continue
                    val arr = row.optJSONArray("items") ?: JSONArray()
                    val titles = buildList {
                        for (j in 0 until arr.length()) {
                            val item = arr.optJSONObject(j) ?: continue
                            add(Title(
                                id = item.optString("id"), name = item.optString("name"),
                                description = item.optString("description"), posterUrl = item.optString("posterUrl"),
                                backdropUrl = item.optString("backdropUrl"), streamUrl = "", isLive = false,
                                year = item.optString("year").takeIf { it.isNotBlank() },
                                rating = item.optString("rating").takeIf { it.isNotBlank() },
                                contentKind = runCatching { ContentKind.valueOf(item.optString("kind")) }.getOrDefault(ContentKind.MOVIE),
                            ))
                        }
                    }
                    add(Category(row.optString("id"), row.optString("title"), titles))
                }
            }
        }.getOrNull()
    }

    private fun writeCache(categories: List<Category>) {
        runCatching {
            val rows = JSONArray()
            categories.forEach { category ->
                val items = JSONArray()
                category.items.forEach { title -> items.put(JSONObject().apply {
                    put("id", title.id); put("name", title.name); put("description", title.description)
                    put("posterUrl", title.posterUrl); put("backdropUrl", title.backdropUrl)
                    put("year", title.year.orEmpty()); put("rating", title.rating.orEmpty()); put("kind", title.contentKind.name)
                }) }
                rows.put(JSONObject().apply { put("id", category.id); put("title", category.title); put("items", items) })
            }
            cacheFile.parentFile?.mkdirs()
            cacheFile.writeText(JSONObject().put("categories", rows).toString())
        }
    }
}

data class TmdbSeason(val number: Int, val name: String, val episodeCount: Int, val overview: String, val posterUrl: String)
data class TmdbEpisode(val number: Int, val name: String, val overview: String, val stillUrl: String, val runtime: Int?)
