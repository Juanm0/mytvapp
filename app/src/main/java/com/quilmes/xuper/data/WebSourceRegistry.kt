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
    const val FRAME_XTV_HOME = "https://framextv.xyz/"

    /** Estos dos dominios aparecen como Referer en la lista IPTV del usuario, no como URLs VOD. */
    const val IPTV_REFERER_LEWBLIVE = "https://lewblivehdplay.ru/"
    const val IPTV_REFERER_QQWEBPLAY = "https://qqwebplay.xyz/"

    fun titleKey(titleId: String): String = titleId

    fun episodeKey(seriesId: String, season: Int, episode: Int): String = "$seriesId|s$season|e$episode"

    fun getSources(context: Context, contentKey: String): List<String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = decode(prefs.getString(KEY_PREFIX + contentKey, null))
        val hiddenDefaults = decode(prefs.getString("hidden:$contentKey", null)).toSet()
        // Fuente global candidata: la única ruta que pudimos observar directamente fue
        // /watch/{TMDB_ID}/{temporada}/{episodio}. Se genera por episodio y se etiqueta como no verificada.
        // Para películas no inventamos un patrón de reproducción: dejamos la página del proveedor como búsqueda.
        val episode = Regex("^(?:tmdb-tv-)?(\\d+)\\|s(\\d+)\\|e(\\d+)$").matchEntire(contentKey)
        val defaults = when {
            episode != null -> listOf("https://framextv.xyz/watch/${episode.groupValues[1]}/${episode.groupValues[2]}/${episode.groupValues[3]}")
            contentKey.startsWith("tmdb-movie-") -> listOf(FRAME_XTV_HOME)
            else -> emptyList()
        }.filterNot { it in hiddenDefaults }
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
        // Las fuentes predeterminadas se ocultan solo para este contenido cuando el usuario las quita.
        val hidden = (decode(prefs.getString("hidden:$contentKey", null)) + rawUrl).distinct()
        editor.putString("hidden:$contentKey", JSONArray(hidden).toString())
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
