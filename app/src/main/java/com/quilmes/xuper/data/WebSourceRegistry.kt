package com.quilmes.xuper.data

/** Único constructor de URLs de reproducción del catálogo: FrameXTV + IDs TMDB. */
object WebSourceRegistry {
    /**
     * TMDB movies/series use tmdb-movie-ID and tmdb-tv-ID. For episodes the key is
     * tmdb-tv-ID|sSEASON|eEPISODE, matching FrameXTV's documented embed route.
     */
    fun frameXEmbedUrl(catalogId: String): String? {
        val episode = Regex("^tmdb-tv-(\\d+)\\|s(\\d+)\\|e(\\d+)$").matchEntire(catalogId)
        if (episode != null) {
            val (id, season, number) = episode.destructured
            return "https://framextv.xyz/embed/$id/$season/$number?autoplay=1&muted=0"
        }
        val title = Regex("^tmdb-(?:movie|tv)-(\\d+)$").matchEntire(catalogId) ?: return null
        return "https://framextv.xyz/embed/${title.groupValues[1]}?autoplay=1&muted=0"
    }
}
