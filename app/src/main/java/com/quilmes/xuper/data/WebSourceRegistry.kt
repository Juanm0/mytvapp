package com.quilmes.xuper.data

/** Fuentes de reproducción separadas: FrameXTV para Reproducir y VideoAPI para Latino. */
object WebSourceRegistry {
    /** Embed de VideoAPI. En series requiere temporada y episodio. */
    fun videoApiUrl(catalogId: String): String? {
        val episode = Regex("^tmdb-tv-(\\d+)\\|s(\\d+)\\|e(\\d+)$").matchEntire(catalogId)
        val base = if (episode != null) {
            val (id, season, number) = episode.destructured
            "https://videoapi.la/e/tv/$id/$season/$number"
        } else {
            val title = Regex("^tmdb-movie-(\\d+)$").matchEntire(catalogId) ?: return null
            "https://videoapi.la/e/movie/${title.groupValues[1]}"
        }
        return "$base?autoplay=1&auto_next=1&skip_intro=1&thumbnails=1&subtitles=1&quality_selector=1"
    }

    /** URL candidata de FrameXTV para películas y episodios. */
    fun frameXtvUrl(catalogId: String): String? {
        val episode = Regex("^tmdb-tv-(\\d+)\\|s(\\d+)\\|e(\\d+)$").matchEntire(catalogId)
        if (episode != null) {
            val (id, season, number) = episode.destructured
            return "https://framextv.xyz/embed/$id/$season/$number"
        }
        Regex("^tmdb-movie-(\\d+)$").matchEntire(catalogId)?.let {
            return "https://framextv.xyz/embed/${it.groupValues[1]}"
        }
        // Si se pide una serie sin episodio elegido, abre el primer episodio como valor inicial.
        Regex("^tmdb-tv-(\\d+)$").matchEntire(catalogId)?.let {
            return "https://framextv.xyz/embed/${it.groupValues[1]}/1/1"
        }
        return null
    }
}
