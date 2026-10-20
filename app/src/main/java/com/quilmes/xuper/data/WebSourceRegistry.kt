package com.quilmes.xuper.data

/** URLs del reproductor oficial de VideoAPI para el catálogo TMDb. */
object WebSourceRegistry {
    /**
     * Devuelve un embed de VideoAPI. En series requiere temporada y episodio.
     * Activa las funciones públicas documentadas que puede ofrecer el reproductor.
     */
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
}
