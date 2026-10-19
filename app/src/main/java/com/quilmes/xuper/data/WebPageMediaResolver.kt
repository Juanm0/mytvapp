package com.quilmes.xuper.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Connection
import org.jsoup.Jsoup
import java.net.URL

/**
 * Resuelve únicamente fuentes que la página expone públicamente en su HTML inicial.
 * No ejecuta JavaScript, no descifra players ofuscados y no evita DRM, paywalls,
 * autenticación ni controles anti-bot. Un iframe es una página HTML, no un stream para ExoPlayer.
 */
object WebPageMediaResolver {
    sealed interface Result {
        data class DirectMedia(val url: String) : Result
        data class EmbedPage(val url: String) : Result
        data class NotFound(val reason: String) : Result
    }

    private val mediaExtensions = listOf(".m3u8", ".mpd", ".mp4", ".m4v", ".webm", ".mkv", ".mov")

    /** Heurística conservadora para distinguir una página de detalle de una URL de stream. */
    fun looksLikeDetailPage(rawUrl: String): Boolean = runCatching {
        val url = URL(rawUrl)
        val path = url.path.lowercase()
        url.protocol in setOf("http", "https") &&
            (path.endsWith(".html") || path.contains("/ver-pelicula/") || path.contains("/ver-serie/") ||
                path.contains("/episodio/") || path.contains("/capitulo/"))
    }.getOrDefault(false)

    suspend fun resolve(pageUrl: String): Result = withContext(Dispatchers.IO) {
        val parsedUrl = runCatching { URL(pageUrl) }.getOrNull()
            ?: return@withContext Result.NotFound("La URL de la página no es válida.")
        if (parsedUrl.protocol !in setOf("http", "https") || parsedUrl.userInfo != null) {
            return@withContext Result.NotFound("Solo se admiten páginas HTTP/HTTPS públicas.")
        }

        try {
            val response = Jsoup.connect(pageUrl)
                .userAgent("Mozilla/5.0 (Linux; Android  TV) AppleWebKit/537.36 Chrome/120 Safari/537.36 XuperQuilmesTV")
                .timeout(12_000)
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

            // Solo enlaces declarados como medios en el HTML recibido, nunca cadenas arbitrarias
            // encontradas dentro de scripts minificados u ofuscados.
            val declaredRaw = buildList {
                response.select("video[src]").forEach { add(it.attr("src")) }
                response.select("video source[src], source[src]").forEach { add(it.attr("src")) }
                response.select("meta[property=og:video], meta[property=og:video:url], meta[name=twitter:player:stream]")
                    .forEach { add(it.attr("content")) }
                response.select("link[rel=video_src][href]").forEach { add(it.attr("href")) }
            }
            val declaredMedia = declaredRaw.mapNotNull(::absolute).distinct()

            val direct = declaredMedia.firstOrNull { candidate ->
                val path = runCatching { URL(candidate).path.lowercase() }.getOrDefault("")
                mediaExtensions.any { path.endsWith(it) } || candidate.contains(".m3u8?", true) || candidate.contains(".mpd?", true)
            }
            if (direct != null) return@withContext Result.DirectMedia(direct)

            // Si la página solo declara un iframe, se devuelve como embed; no se etiqueta como vídeo directo.
            val iframe = response.select("iframe[src]")
                .firstOrNull { frame ->
                    val hint = (frame.attr("src") + " " + frame.attr("title") + " " + frame.className() + " " + frame.id()).lowercase()
                    listOf("player", "embed", "video", "watch", "reproductor").any { hint.contains(it) }
                }
                ?.let { absolute(it.attr("src")) }
            if (iframe != null) return@withContext Result.EmbedPage(iframe)

            Result.NotFound("La página no expone un vídeo directo ni un iframe en su HTML inicial. Puede cargar el reproductor con JavaScript o requerir acceso del proveedor.")
        } catch (e: Exception) {
            Result.NotFound("No se pudo leer la página (${e.javaClass.simpleName}). Verificá la conexión o si el sitio permite solicitudes desde la app.")
        }
    }
}
