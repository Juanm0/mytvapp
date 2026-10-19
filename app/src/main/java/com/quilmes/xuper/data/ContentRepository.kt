package com.quilmes.xuper.data

/**
 * Punto único de entrada para el contenido de la app.
 *
 * Para conectar tu propia fuente más adelante, creá una implementación
 * nueva de esta interfaz (ej: `XtreamCodesRepository`, `M3uRepository`,
 * `MiBackendRepository`) y cambiá una sola línea en `MainActivity.kt`
 * donde se instancia el repositorio. El resto de la UI no se toca.
 */
interface ContentRepository {
    suspend fun getFeatured(): Title?
    suspend fun getCategories(): List<Category>
}

/**
 * Implementación de ejemplo con datos de prueba (imágenes públicas de
 * picsum.photos y un stream HLS de prueba de Mux) para que la app
 * compile y se vea completa desde el primer momento, sin backend.
 *
 * Reemplazar por tu fuente real (IPTV, tu propio catálogo, etc.) cuando
 * esté lista.
 */
class MockContentRepository : ContentRepository {

    private val sampleStream =
        "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"

    private fun mockTitle(id: String, name: String, seed: Int, live: Boolean = false) = Title(
        id = id,
        name = name,
        description = "Descripción de ejemplo para \"$name\". Reemplazá esto con la " +
            "sinopsis real cuando conectes tu fuente de datos.",
        posterUrl = "https://picsum.photos/seed/$seed/400/600",
        backdropUrl = "https://picsum.photos/seed/$seed/1280/720",
        streamUrl = sampleStream,
        isLive = live,
        year = if (!live) "2026" else null,
        rating = if (!live) "+13" else null,
    )

    override suspend fun getFeatured(): Title =
        mockTitle(id = "featured", name = "Título destacado", seed = 1)

    override suspend fun getCategories(): List<Category> = listOf(
        Category(
            id = "populares",
            title = "Populares en Xuper Quilmes TV",
            items = (1..10).map { mockTitle("pop_$it", "Película $it", seed = it + 10) }
        ),
        Category(
            id = "en_vivo",
            title = "Canales en vivo",
            items = (1..8).map {
                mockTitle("live_$it", "Canal $it", seed = it + 50, live = true)
            }
        ),
        Category(
            id = "series",
            title = "Series para maratonear",
            items = (1..10).map { mockTitle("serie_$it", "Serie $it", seed = it + 100) }
        ),
        Category(
            id = "recientes",
            title = "Agregados recientemente",
            items = (1..10).map { mockTitle("new_$it", "Estreno $it", seed = it + 150) }
        ),
    )
}
