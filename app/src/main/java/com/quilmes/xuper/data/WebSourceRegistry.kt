package com.quilmes.xuper.data

/** Fuentes de reproducción separadas: FrameXTV para Reproducir y VideoAPI para Latino. */
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

    // Id de AniList por serie de TMDb (lo usa el embed de anime de FrameXTV). Lo completa TmdbCatalogService.
    private val aniListIds = java.util.concurrent.ConcurrentHashMap<String, Long>()
    /** Id conocido con certeza (viene de una ficha de AniList): pisa cualquier suposición anterior. */
    fun setAniListId(tmdbTvId: String, aniListId: Long) { aniListIds[tmdbTvId] = aniListId }
    /** Id deducido por búsqueda de título: no pisa uno ya confirmado. */
    fun setAniListIdIfAbsent(tmdbTvId: String, aniListId: Long) { aniListIds.putIfAbsent(tmdbTvId, aniListId) }
    fun hasAniListId(tmdbTvId: String): Boolean = aniListIds.containsKey(tmdbTvId)
    private const val FRAMEXTV_ANIME_SUB_TYPE = "sub"

    private fun frameXtvAnimeUrl(tmdbTvId: String, season: String, episode: String): String? {
        // Un ID de AniList por sí solo NO convierte una serie TMDb en anime.
        // Esta ruta solo se permite para títulos clasificados explícitamente como anime.
        // Así, una equivalencia vieja/incorrecta en memoria no desvía una serie normal
        // al endpoint /embed/anime y deja intacta la ruta clásica de TMDb.
        if (!isAnime(tmdbTvId)) return null
        val aniList = aniListIds[tmdbTvId] ?: return null
        return "https://framextv.xyz/embed/anime?id=$aniList&type=tv&season=$season&episode=$episode&sub_type=$FRAMEXTV_ANIME_SUB_TYPE"
    }

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

    /** URL candidata de FrameXTV para películas y episodios. */
    fun frameXtvUrl(catalogId: String): String? {
        val episode = Regex("^tmdb-tv-(\\d+)\\|s(\\d+)\\|e(\\d+)$").matchEntire(catalogId)
        if (episode != null) {
            val (id, season, number) = episode.destructured
            frameXtvAnimeUrl(id, season, number)?.let { return it }
            return "https://framextv.xyz/embed/$id/$season/$number"
        }
        Regex("^tmdb-movie-(\\d+)$").matchEntire(catalogId)?.let {
            return "https://framextv.xyz/embed/${it.groupValues[1]}"
        }
        // Fichas de AniList sin equivalente en TMDb: el id ya es el de AniList.
        Regex("^anilist-(\\d+)\\|s(\\d+)\\|e(\\d+)$").matchEntire(catalogId)?.let {
            val (id, season, number) = it.destructured
            return "https://framextv.xyz/embed/anime?id=$id&type=tv&season=$season&episode=$number&sub_type=$FRAMEXTV_ANIME_SUB_TYPE"
        }
        Regex("^anilist-(\\d+)$").matchEntire(catalogId)?.let {
            return "https://framextv.xyz/embed/anime?id=${it.groupValues[1]}&type=tv&season=1&episode=1&sub_type=$FRAMEXTV_ANIME_SUB_TYPE"
        }
        // Si se pide una serie sin episodio elegido, abre el primer episodio como valor inicial.
        Regex("^tmdb-tv-(\\d+)$").matchEntire(catalogId)?.let {
            frameXtvAnimeUrl(it.groupValues[1], "1", "1")?.let { url -> return url }
            return "https://framextv.xyz/embed/${it.groupValues[1]}/1/1"
        }
        return null
    }
}
