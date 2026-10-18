package com.quilmes.xuper.data

/**
 * Un ítem reproducible: película, episodio o canal en vivo.
 * `streamUrl` es lo único que depende de la fuente real (tu backend, un M3U
 * de IPTV, Xtream Codes, etc.) — todo lo demás es metadata de presentación.
 */
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
)

/** Una fila horizontal de la pantalla principal (ej: "Populares", "Noticias en vivo"). */
data class Category(
    val id: String,
    val title: String,
    val items: List<Title>,
)
