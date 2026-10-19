package com.quilmes.xuper.data

import android.content.Context
import org.json.JSONArray
import java.net.URI

/**
 * Fuentes web alternativas guardadas por contenido. Las películas usan su ID de catálogo;
 * las series usan ID + temporada + episodio. Esto hace que el mismo flujo sirva para todo el catálogo.
 * Las URL se consideran candidatas, no prueba de que el video esté disponible.
 */
object WebSourceRegistry {
    private const val PREFS = "xuper-web-sources"
    private const val KEY_PREFIX = "sources:"

    const val FRAME_XTV_EXAMPLE = "https://framextv.xyz/watch/1396/3/11"
    private const val BREAKING_BAD_S3E11_KEY = "tmdb-tv-1396|s3|e11"

    fun titleKey(titleId: String): String = titleId

    fun episodeKey(seriesId: String, season: Int, episode: Int): String = "$seriesId|s$season|e$episode"

    fun getSources(context: Context, contentKey: String): List<String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = decode(prefs.getString(KEY_PREFIX + contentKey, null))
        val hiddenDefaults = decode(prefs.getString("hidden:$contentKey", null)).toSet()
        // Keep the user's example available for the exact episode, without applying it to unrelated titles.
        val defaults = if (contentKey == BREAKING_BAD_S3E11_KEY && FRAME_XTV_EXAMPLE !in hiddenDefaults) listOf(FRAME_XTV_EXAMPLE) else emptyList()
        return (defaults + stored).distinct()
    }

    fun addSource(context: Context, contentKey: String, rawUrl: String): Boolean {
        val url = normalizeHttpUrl(rawUrl) ?: return false
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = decode(prefs.getString(KEY_PREFIX + contentKey, null)).toMutableList()
        if (url in getSources(context, contentKey) || url in current) return false
        current.add(url)
        prefs.edit().putString(KEY_PREFIX + contentKey, JSONArray(current).toString()).apply()
        return true
    }

    fun removeSource(context: Context, contentKey: String, rawUrl: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = decode(prefs.getString(KEY_PREFIX + contentKey, null)).filterNot { it == rawUrl }
        val editor = prefs.edit().putString(KEY_PREFIX + contentKey, JSONArray(current).toString())
        if (contentKey == BREAKING_BAD_S3E11_KEY && rawUrl == FRAME_XTV_EXAMPLE) {
            val hidden = (decode(prefs.getString("hidden:$contentKey", null)) + rawUrl).distinct()
            editor.putString("hidden:$contentKey", JSONArray(hidden).toString())
        }
        editor.apply()
    }

    private fun decode(raw: String?): List<String> = runCatching {
        val json = JSONArray(raw ?: "[]")
        (0 until json.length()).mapNotNull { index -> normalizeHttpUrl(json.optString(index)) }
    }.getOrDefault(emptyList())

    private fun normalizeHttpUrl(raw: String): String? = runCatching {
        val value = raw.trim()
        val uri = URI(value)
        if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) return null
        if (value.any { it.isWhitespace() }) return null
        uri.toASCIIString()
    }.getOrNull()
}
