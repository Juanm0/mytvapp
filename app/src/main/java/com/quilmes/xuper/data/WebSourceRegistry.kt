package com.quilmes.xuper.data

/** Fuentes web de reproducción: VideoAPI aporta el video del Inicio; CineSrc se usa para capturar subtítulos. Vidly/VidLux quedan como fuentes heredadas. */
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

    // ---- Prueba: VideoAPI con la navegación de mando "de fábrica" (como Vidly/VidLux), sin hub de Xuper ----
    // Usa exactamente la misma URL de VideoAPI; solo se le agrega un fragmento (#xuper-prueba) que NO se envía al
    // servidor y que PlayerScreen usa para tratarlo como página web normal (sin hub ni agente de frames de Latino).
    private const val PRUEBA_FRAGMENT = "#xuper-prueba"

    fun videoApiPruebaUrl(catalogId: String): String? = videoApiUrl(catalogId)?.let { it + PRUEBA_FRAGMENT }

    fun isVideoApiPruebaUrl(url: String): Boolean = url.endsWith(PRUEBA_FRAGMENT) && isVideoApiEmbedUrl(url)

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
    // ---- CineSrc (captura de subtítulos para el reproductor único del Inicio) ----
    // Embed por ID de TMDB: /embed/movie/{id} y /embed/tv/{id}?s={temporada}&e={episodio}.
    private const val CINESRC_HOST = "cinesrc.st"

    fun isCineSrcHost(host: String?): Boolean {
        val h = host?.lowercase()?.trimEnd('.') ?: return false
        return h == CINESRC_HOST || h.endsWith(".$CINESRC_HOST")
    }

    /** true si la URL es un embed de CineSrc (https://cinesrc.st/embed/movie/... o /embed/tv/...). */
    fun isCineSrcEmbedUrl(url: String): Boolean =
        url.startsWith("https://$CINESRC_HOST/embed/movie/") || url.startsWith("https://$CINESRC_HOST/embed/tv/")

    /** Embed de CineSrc por ID de catálogo (`tmdb-movie-<id>` o `tmdb-tv-<id>|s<T>|e<E>`). Serie sin episodio: T1 E1. */
    fun cineSrcUrl(catalogId: String): String? {
        val episode = Regex("^tmdb-tv-(\\d+)\\|s(\\d+)\\|e(\\d+)$").matchEntire(catalogId)
        return when {
            episode != null -> {
                val (id, season, number) = episode.destructured
                "https://$CINESRC_HOST/embed/tv/$id?s=$season&e=$number"
            }
            else -> Regex("^tmdb-movie-(\\d+)$").matchEntire(catalogId)?.let { "https://$CINESRC_HOST/embed/movie/${it.groupValues[1]}" }
                ?: Regex("^tmdb-tv-(\\d+)$").matchEntire(catalogId)?.let { "https://$CINESRC_HOST/embed/tv/${it.groupValues[1]}?s=1&e=1" }
        }
    }

    // ---- VidLux (reproductor alternativo embebido) ----
    private const val VIDLUX_HOST = "vidlux.xyz"
    // Dominios alternativos que publica VidLux (mismo servicio); solo se usan para reconocer el host.
    private val VIDLUX_HOSTS = listOf(VIDLUX_HOST, "vidlux.top", "vidlux.site", "vidlux.online")

    fun isVidLuxHost(host: String?): Boolean {
        val h = host?.lowercase()?.trimEnd('.') ?: return false
        return VIDLUX_HOSTS.any { h == it || h.endsWith(".$it") }
    }

    fun isVidLuxEmbedUrl(url: String): Boolean =
        VIDLUX_HOSTS.any { url.startsWith("https://$it/embed/movie/") || url.startsWith("https://$it/embed/tv/") }

    /** VidLux recibe IDs TMDB: /embed/movie/{id} o /embed/tv/{id}/{season}/{episode} (season y episode obligatorios). */
    fun vidLuxUrl(catalogId: String): String? {
        val episode = Regex("^tmdb-tv-(\\d+)\\|s(\\d+)\\|e(\\d+)$").matchEntire(catalogId)
        val base = when {
            episode != null -> {
                val (id, season, number) = episode.destructured
                "https://$VIDLUX_HOST/embed/tv/$id/$season/$number"
            }
            else -> Regex("^tmdb-movie-(\\d+)$").matchEntire(catalogId)?.let {
                "https://$VIDLUX_HOST/embed/movie/${it.groupValues[1]}"
            } ?: Regex("^tmdb-tv-(\\d+)$").matchEntire(catalogId)?.let {
                "https://$VIDLUX_HOST/embed/tv/${it.groupValues[1]}/1/1"
            } ?: return null
        }
        // - `color=16A085` (sin #): color de marca del reproductor (único parámetro documentado que usamos).
        // - Sin parámetros de autoplay: su nombre no está confirmado en la documentación consultada. El arranque con
        //   sonido lo hace Xuper desde el WebView (ver XUPER_VIDLUX_AUTOPLAY_JS en PlayerScreen.kt).
        return "$base?color=16A085"
    }
}
