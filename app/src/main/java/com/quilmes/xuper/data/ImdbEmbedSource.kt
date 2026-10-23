package com.quilmes.xuper.data

import java.net.URL

/** Construye la URL de inserción a partir del IMDb ID obtenido por TMDB. */
object ImdbEmbedSource {
    private const val BASE_URL = "https://streamimdb.ru/embed/movie/"
    private val imdbIdPattern = Regex("^tt\\d{7,10}$")

    fun buildUrl(imdbId: String?): String? {
        val id = imdbId?.trim()?.takeIf(imdbIdPattern::matches) ?: return null
        return "$BASE_URL$id/"
    }

    /** Evita cargar por error una URL arbitraria en el WebView dedicado a esta fuente. */
    fun isEmbedUrl(rawUrl: String): Boolean = runCatching {
        val url = URL(rawUrl)
        url.protocol == "https" && url.host.equals("streamimdb.ru", ignoreCase = true) &&
            url.path.matches(Regex("^/embed/movie/tt\\d{7,10}/?$"))
    }.getOrDefault(false)
}
