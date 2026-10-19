package com.quilmes.xuper.data

/** URL builders for configured web providers. FrameXTV remains the default provider. */
object WebSourceRegistry {
    /** TMDB IDs stay canonical for FrameXTV; episodes use the series ID + season + episode. */
    fun frameXEmbedUrl(catalogId: String): String? {
        val episode = Regex("^tmdb-tv-(\\d+)\\|s(\\d+)\\|e(\\d+)$").matchEntire(catalogId)
        if (episode != null) {
            val (id, season, number) = episode.destructured
            return "https://framextv.xyz/embed/$id/$season/$number?autoplay=1&muted=0"
        }
        val title = Regex("^tmdb-(?:movie|tv)-(\\d+)$").matchEntire(catalogId) ?: return null
        return "https://framextv.xyz/embed/${title.groupValues[1]}?autoplay=1&muted=0"
    }

    /** Builds a PLAYLATINSRC URL only after a real IMDb ID has been resolved. */
    fun playLatinSrcMovieUrl(imdbId: String, language: String? = null): String? {
        if (!imdbId.matches(Regex("tt\\d+"))) return null
        val suffix = languageQuery(language) ?: ""
        return "https://playlatinsrc.co/movie/$imdbId$suffix"
    }

    fun playLatinSrcSeriesUrl(imdbId: String, language: String? = null): String? {
        if (!imdbId.matches(Regex("tt\\d+"))) return null
        val suffix = languageQuery(language) ?: ""
        return "https://playlatinsrc.co/serial/$imdbId$suffix"
    }

    fun playLatinSrcEpisodeUrl(imdbId: String, season: Int, episode: Int, language: String? = null): String? {
        if (!imdbId.matches(Regex("tt\\d+")) || season < 0 || episode < 1) return null
        val suffix = languageQuery(language) ?: ""
        return "https://playlatinsrc.co/custom/serial/$imdbId/$season/$episode$suffix"
    }

    private fun languageQuery(language: String?): String? = when (language?.lowercase()) {
        "castellano", "latino", "subtitulado" -> "?lang=$language"
        else -> null
    }
}
