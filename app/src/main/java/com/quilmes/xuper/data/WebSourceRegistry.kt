package com.quilmes.xuper.data

/** Fuentes de reproducción: Vidly (Reproducir), VideoAPI (Latino) y FrameXTV (Alternativo). */
object WebSourceRegistry {
    private const val VIDEOAPI_HOST = "videoapi.la"
    /** VideoAPI sirve el anime desde otro dominio y con otra ruta: /e/anime/<id>/<temporada>/<episodio>. */
    private const val VIDEOAPI_ANIME_HOST = "videoapp.zip"
    private val videoApiHosts = listOf(VIDEOAPI_HOST, VIDEOAPI_ANIME_HOST)

    // Series que TMDb clasifica como anime (Animación + idioma original japonés). Se completan al ver
    // las fichas/temporadas (TmdbCatalogService); alcanza con abrir la ficha antes de reproducir.
    private val animeTvIds: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    fun markAnime(tmdbTvId: String) { animeTvIds.add(tmdbTvId) }
    fun isAnime(tmdbTvId: String): Boolean = tmdbTvId in animeTvIds

    /** true si [host] es de VideoAPI (dominio normal o el de anime). Único lugar con la lista de hosts. */
    fun isVideoApiHost(host: String?): Boolean {
        val h = host?.lowercase()?.trimEnd('.') ?: return false
        return videoApiHosts.any { h == it || h.endsWith(".$it") }
    }

    /** true si la URL es un embed de VideoAPI (https://<host>/e/...). */
    fun isVideoApiEmbedUrl(url: String): Boolean =
        videoApiHosts.any { url.startsWith("https://$it/e/") }

    /** Embed de VideoAPI. En series requiere temporada y episodio. */
    fun videoApiUrl(catalogId: String): String? {
        val episode = Regex("^tmdb-tv-(\\d+)\\|s(\\d+)\\|e(\\d+)$").matchEntire(catalogId)
        val base = if (episode != null) {
            val (id, season, number) = episode.destructured
            if (isAnime(id)) "https://$VIDEOAPI_ANIME_HOST/e/anime/$id/$season/$number"
            else "https://$VIDEOAPI_HOST/e/tv/$id/$season/$number"
        } else {
            val title = Regex("^tmdb-movie-(\\d+)$").matchEntire(catalogId) ?: return null
            "https://videoapi.la/e/movie/${title.groupValues[1]}"
        }
        return "$base?autoplay=1&auto_next=1&skip_intro=1&thumbnails=1&subtitles=1&quality_selector=1"
    }

    // ---- Vidly (reproductor principal: botón "Reproducir") ----
    private const val VIDLY_HOST = "vidly.pro"

    fun isVidlyHost(host: String?): Boolean {
        val h = host?.lowercase()?.trimEnd('.') ?: return false
        return h == VIDLY_HOST || h.endsWith(".$VIDLY_HOST")
    }

    /** true si la URL es un embed de Vidly (https://vidly.pro/movie/... o /tv/...). */
    fun isVidlyEmbedUrl(url: String): Boolean =
        url.startsWith("https://$VIDLY_HOST/movie/") || url.startsWith("https://$VIDLY_HOST/tv/")

    /**
     * Embed de Vidly por id de TMDb: /movie/{id} y /tv/{id}/{temporada}/{episodio}.
     * `autoplay=1` arranca solo (por defecto Vidly carga en pausa).
     */
    fun vidlyUrl(catalogId: String): String? {
        val episode = Regex("^tmdb-tv-(\\d+)\\|s(\\d+)\\|e(\\d+)$").matchEntire(catalogId)
        val base = when {
            episode != null -> {
                val (id, season, number) = episode.destructured
                "https://$VIDLY_HOST/tv/$id/$season/$number"
            }
            else -> Regex("^tmdb-movie-(\\d+)$").matchEntire(catalogId)?.let { "https://$VIDLY_HOST/movie/${it.groupValues[1]}" }
                // Serie sin episodio elegido: primer episodio.
                ?: Regex("^tmdb-tv-(\\d+)$").matchEntire(catalogId)?.let { "https://$VIDLY_HOST/tv/${it.groupValues[1]}/1/1" }
                ?: return null
        }
        return "$base?autoplay=1"
    }


    /** URL candidata de FrameXTV para películas y episodios. */
    fun frameXtvUrl(catalogId: String): String? {
        val episode = Regex("^tmdb-tv-(\\d+)\\|s(\\d+)\\|e(\\d+)$").matchEntire(catalogId)
        if (episode != null) {
            val (id, season, number) = episode.destructured
            return "https://framextv.cc/embed/$id/$season/$number"
        }
        Regex("^tmdb-movie-(\\d+)$").matchEntire(catalogId)?.let {
            return "https://framextv.cc/embed/${it.groupValues[1]}"
        }
        // Si se pide una serie sin episodio elegido, abre el primer episodio como valor inicial.
        Regex("^tmdb-tv-(\\d+)$").matchEntire(catalogId)?.let {
            return "https://framextv.cc/embed/${it.groupValues[1]}/1/1"
        }
        return null
    }
}
