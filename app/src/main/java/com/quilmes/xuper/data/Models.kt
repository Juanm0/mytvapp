package com.quilmes.xuper.data

/**
 * Un ítem reproducible: película, episodio o canal en vivo.
 * `streamUrl` es lo único que depende de la fuente real (tu backend, un M3U
 * de IPTV, Xtream Codes, etc.) — todo lo demás es metadata de presentación.
 */
enum class ContentKind { LIVE, MOVIE, SERIES, OTHER_VOD }

data class Title(
    val id: String,
    val name: String,
    val description: String = "",
    val posterUrl: String,
    val backdropUrl: String = posterUrl,
    val streamUrl: String,
    val isLive: Boolean = false,
    val year: String? = null,
    val rating: String? = null,
    val contentKind: ContentKind = if (isLive) ContentKind.LIVE else ContentKind.OTHER_VOD,
)

/** Una fila horizontal (ej: "Noticias", "Deportes"). */
data class Category(
    val id: String,
    val title: String,
    val items: List<Title>,
)

/**
 * Una fuente de contenido (una lista M3U). Es un ítem de la barra lateral:
 * al entrar se ven todas sus filas; en Inicio solo las primeras `homeRows`.
 */
data class Section(
    val id: String,
    val name: String,
    val icon: String,
    val categories: List<Category>,
    val homeRows: Int,
)
