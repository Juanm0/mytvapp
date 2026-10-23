package com.quilmes.xuper.data

/**
 * Sitios de la sección ANIME. Para cambiar una URL se edita SOLO este archivo.
 *
 * Cada sitio usa una instancia propia de [JkanimeService] y debe tener la misma estructura que JKanime
 * (fichas en /<slug>/, episodios en /<slug>/<n>/, búsqueda, /ajax/episodes/<id>/<página> y /jkplayer/um?…).
 * Las URLs empiezan con https:// (o http://) y no llevan barra final.
 */
object AnimeSites {
    /** Sitio principal de la sección ANIME. */
    const val MAIN_URL = "https://jkanime.net"

    /**
     * Sitio +18. Es lo ÚNICO que hay que cambiar cuando exista el sitio real.
     *
     * - Con una URL: aparece el botón "+18 ANIME CONTENT" arriba a la izquierda de ANIME, pero solo si
     *   el ajuste "Mostrar contenido adulto" (Perfil, protegido con PIN) está activado.
     * - Vacío ("") o inválido: no aparece ningún botón.
     *
     * Por ahora apunta al mismo JKanime para probar el flujo completo.
     */
    const val ADULT_URL = "https://jkanime.net"
}
