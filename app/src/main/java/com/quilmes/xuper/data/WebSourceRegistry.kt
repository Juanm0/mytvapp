package com.quilmes.xuper.data

/** Constructores de páginas de reproducción. FrameXTV sigue siendo el predeterminado. */
object WebSourceRegistry {
    fun frameXEmbedUrl(catalogId: String): String? {
        val episode = Regex("^tmdb-tv-(\\d+)\\|s(\\d+)\\|e(\\d+)$").matchEntire(catalogId)
        if (episode != null) {
            val (id, season, number) = episode.destructured
            return "https://framextv.xyz/embed/$id/$season/$number?autoplay=1&muted=0"
        }
        val title = Regex("^tmdb-(?:movie|tv)-(\\d+)$").matchEntire(catalogId) ?: return null
        return "https://framextv.xyz/embed/${title.groupValues[1]}?autoplay=1&muted=0"
    }

    /** PLAYLATINSRC requires IMDb IDs; callers must resolve the ID through TMDB first. */
    fun playLatinsrcUrl(imdbId: String, catalogId: String, language: String = "castellano"): String? {
        if (!Regex("^tt\\d{5,12}$").matches(imdbId)) return null
        val episode = Regex("^tmdb-tv-\\d+\\|s(\\d+)\\|e(\\d+)$").matchEntire(catalogId)
        val base = if (episode != null) {
            val (season, number) = episode.destructured
            "https://playlatinsrc.co/serial/$imdbId/$season/$number"
        } else if (catalogId.startsWith("tmdb-tv-")) {
            "https://playlatinsrc.co/serial/$imdbId"
        } else {
            "https://playlatinsrc.co/movie/$imdbId"
        }
        return "$base?lang=${java.net.URLEncoder.encode(language, "UTF-8")}" 
    }
}
