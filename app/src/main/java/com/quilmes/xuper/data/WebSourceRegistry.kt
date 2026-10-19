package com.quilmes.xuper.data

import android.content.Context
import org.json.JSONArray
import java.net.URI
import java.net.URLEncoder

/**
 * Central source planner for the whole catalog. Generated URLs are candidates: the app still
 * opens them in its protected WebView and cannot promise a provider has the requested title.
 */
object WebSourceRegistry {
    private const val PREFS = "xuper-web-sources"
    private const val KEY_PREFIX = "sources:"

    const val FRAME_XTV_EXAMPLE = "https://framextv.xyz/watch/1396/3/11"
    const val FRAME_XTV_HOME = "https://framextv.xyz/"

    /** Build the documented FrameXTV embed URL from a TMDB ID, optionally with season/episode. */
    fun frameXEmbedUrl(tmdbCatalogId: String): String? {
        val episodeMatch = Regex("^tmdb-tv-(\\d+)\\|s(\\d+)\\|e(\\d+)$").matchEntire(tmdbCatalogId)
        if (episodeMatch != null) {
            val (id, season, episode) = episodeMatch.destructured
            return "https://framextv.xyz/embed/$id/$season/$episode?autoplay=1&muted=0"
        }
        val match = Regex("^tmdb-(?:movie|tv)-(\\d+)$").matchEntire(tmdbCatalogId) ?: return null
        return "https://framextv.xyz/embed/${match.groupValues[1]}?autoplay=1&muted=0"
    }
    // Servidor predeterminado para episodios: se genera con TMDB ID/temporada/episodio.
    const val DEFAULT_SERIES_PROVIDER = "FrameXTV.xyz"
    const val STREAMIMDB_MOVIE_TEMPLATE = "https://streamimdb.ru/embed/movie/"
    const val REPELISHD_MOVIES = "https://repelishd.cv/peliculas/"
    const val REPELISHD_SERIES = "https://repelishd.cv/series/"
    const val MOVIES2WATCH_HOME = "https://movies2watch.vc/"
    // Candidate search URL; confirm against the live site because its internal routes can change.
    const val MOVIES2WATCH_SEARCH = "https://movies2watch.vc/?s="

    /** These domains appeared as Referer values in the user's IPTV playlist, not as VOD catalogues. */
    const val IPTV_REFERER_LEWBLIVE = "https://lewblivehdplay.ru/"
    const val IPTV_REFERER_QQWEBPLAY = "https://qqwebplay.xyz/"

    fun titleKey(titleId: String): String = titleId
    fun episodeKey(seriesId: String, season: Int, episode: Int): String = "$seriesId|s$season|e$episode"

    /**
     * Return ready-to-open candidates in preferred order. IMDb IDs are retrieved asynchronously
     * from TMDB; they are never guessed. FrameXTV season/episode orders are both included because
     * the example establishes one URL but not the provider's universal parameter order.
     */
    fun getSources(
        context: Context,
        contentKey: String,
        imdbId: String? = null,
        displayTitle: String = "",
        isSeries: Boolean = false,
        season: Int? = null,
        episodeNumber: Int? = null,
    ): List<String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = decode(prefs.getString(KEY_PREFIX + contentKey, null))
        val hiddenDefaults = decode(prefs.getString("hidden:$contentKey", null)).toSet()
        val episodeMatch = Regex("^(?:tmdb-tv-)?(\\d+)\\|s(\\d+)\\|e(\\d+)$").matchEntire(contentKey)
        val seriesId = episodeMatch?.groupValues?.get(1)
        val movieId = Regex("^tmdb-movie-(\\d+)$").matchEntire(contentKey)?.groupValues?.get(1)
        val seasonNo = season ?: episodeMatch?.groupValues?.get(2)?.toIntOrNull()
        val episodeNo = episodeNumber ?: episodeMatch?.groupValues?.get(3)?.toIntOrNull()
        val queryTitle = buildString {
            append(displayTitle.trim())
            if (isSeries && seasonNo != null && episodeNo != null) {
                append(" S").append(seasonNo.toString().padStart(2, '0'))
                append("E").append(episodeNo.toString().padStart(2, '0'))
            }
        }.trim()
        val encodedQuery = URLEncoder.encode(queryTitle.ifBlank { "" }, "UTF-8")
        val encodedTitle = URLEncoder.encode(displayTitle.trim(), "UTF-8")

        val generated = buildList {
            if (isSeries && seriesId != null && seasonNo != null && episodeNo != null) {
                // Documented FrameXTV embed format: /embed/{TMDB ID}/{season}/{episode}.
                add("https://framextv.xyz/embed/$seriesId/$seasonNo/$episodeNo?autoplay=1&muted=0")
                // Movies2Watch uses provider-specific slugs/IDs, so use a title search candidate rather than inventing a detail slug.
                if (encodedQuery.isNotBlank()) add("$MOVIES2WATCH_SEARCH$encodedQuery")
                if (encodedTitle.isNotBlank() && encodedTitle != encodedQuery) add("$MOVIES2WATCH_SEARCH$encodedTitle")
                // RePelisHD opens a title search, letting the site's own catalogue resolve its slug/player.
                if (encodedQuery.isNotBlank()) add("https://repelishd.cv/?s=$encodedQuery")
                // Some catalog search forms index the series title but not the SxxExx suffix.
                if (encodedTitle.isNotBlank() && encodedTitle != encodedQuery) add("https://repelishd.cv/?s=$encodedTitle")
                add(REPELISHD_SERIES)
            } else if (!isSeries) {
                // FrameXTV se prueba también para películas en todo el catálogo. La ruta por ID
                // de película es una candidata, no una garantía de que el proveedor la implemente.
                if (movieId != null) add("https://framextv.xyz/embed/$movieId?autoplay=1&muted=0")
                // The provider's documented template requires an IMDb tt ID; don't fabricate one.
                if (!imdbId.isNullOrBlank() && imdbId.matches(Regex("tt\\d{5,12}"))) {
                    add("$STREAMIMDB_MOVIE_TEMPLATE$imdbId/")
                }
                if (encodedQuery.isNotBlank()) add("$MOVIES2WATCH_SEARCH$encodedQuery")
                if (encodedTitle.isNotBlank() && encodedTitle != encodedQuery) add("$MOVIES2WATCH_SEARCH$encodedTitle")
                if (encodedQuery.isNotBlank()) add("https://repelishd.cv/?s=$encodedQuery")
                add(REPELISHD_MOVIES)
            }
        }.filterNot { it in hiddenDefaults }

        return (generated + stored).distinct()
    }

    fun addSource(context: Context, contentKey: String, rawUrl: String): Boolean {
        val url = rawUrl.trim()
        if (url.isEmpty()) return false
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = decode(prefs.getString(KEY_PREFIX + contentKey, null)).toMutableList()
        if (url in current) return false
        current.add(url)
        return prefs.edit().putString(KEY_PREFIX + contentKey, JSONArray(current).toString()).commit()
    }

    /** Replace selected source atomically; don't hide/remove the old URL before the new one is saved. */
    fun replaceSource(context: Context, contentKey: String, oldUrl: String, rawUrl: String): Boolean {
        val replacement = rawUrl
        if (replacement.isBlank()) return false
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = decode(prefs.getString(KEY_PREFIX + contentKey, null)).toMutableList()
        val index = oldUrl.takeIf { it.isNotBlank() }?.let(current::indexOf) ?: -1
        if (index >= 0) current[index] = replacement else current.add(replacement)
        val result = current.distinct()
        val hidden = if (oldUrl.isBlank()) decode(prefs.getString("hidden:$contentKey", null))
            else (decode(prefs.getString("hidden:$contentKey", null)) + oldUrl).distinct()
        return prefs.edit()
            .putString(KEY_PREFIX + contentKey, JSONArray(result).toString())
            .putString("hidden:$contentKey", JSONArray(hidden).toString())
            .commit()
    }

    fun removeSource(context: Context, contentKey: String, rawUrl: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = decode(prefs.getString(KEY_PREFIX + contentKey, null)).filterNot { it == rawUrl }
        val editor = prefs.edit().putString(KEY_PREFIX + contentKey, JSONArray(current).toString())
        val hidden = (decode(prefs.getString("hidden:$contentKey", null)) + rawUrl).distinct()
        editor.putString("hidden:$contentKey", JSONArray(hidden).toString())
        editor.apply()
    }

    private fun decode(raw: String?): List<String> = runCatching {
        val json = JSONArray(raw ?: "[]")
        (0 until json.length()).mapNotNull { index -> json.optString(index).takeIf { it.isNotBlank() } }
    }.getOrDefault(emptyList())

    // The QR editor deliberately stores the user's pasted text as-is; playback decides whether it can open it.
    fun normalizeForEditor(raw: String): String? = raw.trim().takeIf { it.isNotEmpty() }

    private fun normalizeHttpUrl(raw: String): String? = runCatching {
        val value = raw.trim()
        val uri = URI(value)
        if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) return null
        if (value.any { it.isWhitespace() }) return null
        uri.toASCIIString()
    }.getOrNull()
}
