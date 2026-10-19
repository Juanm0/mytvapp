package com.quilmes.xuper.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Connection
import org.jsoup.Jsoup
import java.net.URL

/**
 * Resuelve fuentes de vídeo declaradas en HTML público y sigue un número limitado de iframes.
 * No ejecuta JavaScript, no descifra players ofuscados y no evita DRM, autenticación ni controles
 * anti-bot. Si el servidor no expone el stream, la UI conserva el embed dentro de Xuper.
 */
object WebPageMediaResolver {
    sealed interface Result {
        data class DirectMedia(val url: String) : Result
        data class EmbedPage(val url: String) : Result
        data class NotFound(val reason: String) : Result
    }

    private val mediaExtensions = listOf(".m3u8", ".mpd", ".mp4", ".m4v", ".webm", ".mkv", ".mov", ".ts")
    private const val MAX_IFRAME_DEPTH = 2

    fun looksLikeDetailPage(rawUrl: String): Boolean = runCatching {
        val url = URL(rawUrl)
        val path = url.path.lowercase()
        url.protocol in setOf("http", "https") &&
            (path.endsWith(".html") || path.contains("/ver-pelicula/") || path.contains("/ver-serie/") ||
                path.contains("/episodio/") || path.contains("/capitulo/") || path.contains("/watch/"))
    }.getOrDefault(false)

    suspend fun resolve(pageUrl: String): Result = withContext(Dispatchers.IO) {
        val visited = linkedSetOf<String>()
        resolvePage(pageUrl, 0, visited)
    }

    private fun resolvePage(rawUrl: String, depth: Int, visited: MutableSet<String>): Result {
        val parsedUrl = runCatching { URL(rawUrl) }.getOrNull()
            ?: return Result.NotFound("La URL de la página no es válida.")
        if (parsedUrl.protocol !in setOf("http", "https") || parsedUrl.userInfo != null) {
            return Result.NotFound("Solo se admiten páginas HTTP/HTTPS públicas.")
        }
        val normalized = parsedUrl.toExternalForm()
        if (!visited.add(normalized)) return Result.NotFound("Se detectó un ciclo entre reproductores.")

        return try {
            val response = Jsoup.connect(normalized)
                .userAgent("Mozilla/5.0 (Linux; Android TV) AppleWebKit/537.36 Chrome/120 Safari/537.36 XuperQuilmesTV")
                .timeout(8_000)
                .followRedirects(true)
                .ignoreHttpErrors(false)
                .ignoreContentType(false)
                .method(Connection.Method.GET)
                .get()

            fun absolute(raw: String?): String? {
                val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
                val resolved = runCatching { response.absUrl(value).ifBlank { URL(parsedUrl, value).toString() } }.getOrNull()
                    ?: return null
                val target = runCatching { URL(resolved) }.getOrNull() ?: return null
                return resolved.takeIf { target.protocol in setOf("http", "https") && target.userInfo == null }
            }

            val declaredRaw = buildList {
                response.select("video[src]").forEach { add(it.attr("src")) }
                response.select("video source[src], source[src]").forEach { add(it.attr("src")) }
                response.select("meta[property=og:video], meta[property=og:video:url], meta[name=twitter:player:stream]")
                    .forEach { add(it.attr("content")) }
                response.select("link[rel=video_src][href]").forEach { add(it.attr("href")) }
            }
            val declaredMedia = declaredRaw.mapNotNull(::absolute).distinct()
            val direct = declaredMedia.firstOrNull(::isDirectMedia)
            if (direct != null) return Result.DirectMedia(direct)

            // Muchos sitios solo declaran un iframe del player. Seguimos como máximo dos niveles,
            // siempre sobre URLs HTTP(S) públicas, para encontrar un MP4/HLS/DASH declarado.
            val iframeUrls = response.select("iframe[src]")
                .sortedByDescending { frame ->
                    val hint = (frame.attr("src") + " " + frame.attr("title") + " " + frame.className() + " " + frame.id()).lowercase()
                    listOf("player", "embed", "video", "watch", "reproductor", "stream").count { hint.contains(it) }
                }
                .mapNotNull { absolute(it.attr("src")) }
                .distinct()
            if (depth < MAX_IFRAME_DEPTH) {
                for (iframeUrl in iframeUrls.take(2)) {
                    when (val nested = resolvePage(iframeUrl, depth + 1, visited)) {
                        is Result.DirectMedia -> return nested
                        else -> Unit
                    }
                }
            }
            // Si no se puede extraer el archivo, cargar el iframe dentro de Xuper es mejor que
            // mandar al usuario al navegador externo. Se prioriza el iframe más probable.
            val embed = iframeUrls.firstOrNull()
            if (embed != null) Result.EmbedPage(embed)
            else Result.NotFound("El servidor no expone un vídeo directo en el HTML consultable. Puede generar el stream con JavaScript o exigir una sesión del proveedor.")
        } catch (e: Exception) {
            Result.NotFound("No se pudo resolver el vídeo (${e.javaClass.simpleName}). Verificá la conexión o si el sitio permite solicitudes desde la app.")
        }
    }

    private fun isDirectMedia(candidate: String): Boolean {
        val path = runCatching { URL(candidate).path.lowercase() }.getOrDefault("")
        return mediaExtensions.any(path::endsWith) || candidate.contains(".m3u8?", true) || candidate.contains(".mpd?", true)
    }
}
