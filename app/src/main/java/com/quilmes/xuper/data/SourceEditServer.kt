package com.quilmes.xuper.data

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URLDecoder
import java.net.URLEncoder

/** Tiny LAN-only editor so a phone on the same Wi-Fi can fix a title/episode URL. */
object SourceEditServer {
    private const val PORT = 8765
    @Volatile private var server: EditHttpServer? = null

    fun start(context: Context): Boolean {
        if (server != null) return true
        return synchronized(this) {
            if (server != null) return@synchronized true
            runCatching {
                EditHttpServer(context.applicationContext).also { it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false); server = it }
            }.isSuccess
        }
    }

    fun editorUrl(contentKey: String, title: String, isSeries: Boolean, season: Int? = null, episode: Int? = null): String? {
        val ip = localIpv4() ?: return null
        return "http://$ip:$PORT/edit?key=" + URLEncoder.encode(contentKey, "UTF-8") +
            "&title=" + URLEncoder.encode(title, "UTF-8") + "&series=$isSeries" +
            (season?.let { "&season=$it" } ?: "") + (episode?.let { "&episode=$it" } ?: "")
    }

    private fun localIpv4(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList().asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull()

    private class EditHttpServer(private val appContext: Context) : NanoHTTPD(PORT) {
        override fun serve(session: IHTTPSession): Response {
            val key = session.parameters["key"]?.firstOrNull().orEmpty()
            val displayTitle = session.parameters["title"]?.firstOrNull().orEmpty()
            val isSeries = session.parameters["series"]?.firstOrNull().toBoolean()
            val season = session.parameters["season"]?.firstOrNull()?.toIntOrNull()
            val episode = session.parameters["episode"]?.firstOrNull()?.toIntOrNull()
            if (key.isBlank() || key.length > 240 || key.any { it.isISOControl() }) {
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Falta la clave del contenido.")
            }
            if (session.method == Method.POST && session.uri == "/save") {
                val files = HashMap<String, String>()
                return try {
                    session.parseBody(files)
                    val body = files["postData"].orEmpty()
                    val form = body.split('&').mapNotNull { part ->
                        val i = part.indexOf('='); if (i < 0) null else URLDecoder.decode(part.substring(0, i), "UTF-8") to URLDecoder.decode(part.substring(i + 1), "UTF-8")
                    }.toMap()
                    val oldUrl = form["oldUrl"].orEmpty()
                    val newUrl = form["newUrl"].orEmpty().trim()
                    val normalized = newUrl.takeIf { it.startsWith("https://") || it.startsWith("http://") }
                    if (oldUrl.isBlank() || normalized == null || WebSourceRegistry.normalizeForEditor(normalized) == null) {
                        html("URL inválida. Usá una dirección completa que empiece con https:// o http://", key, displayTitle, isSeries, season, episode)
                    } else {
                        WebSourceRegistry.removeSource(appContext, key, oldUrl)
                        val saved = WebSourceRegistry.addSource(appContext, key, normalized)
                        html(if (saved) "¡Listo! La fuente quedó guardada en Xuper. Volvé al televisor y actualizá la ficha." else "La URL ya existe o no se pudo guardar. Revisá el enlace.", key, displayTitle, isSeries, season, episode)
                    }
                } catch (_: Exception) { html("No se pudo guardar el enlace. Volvé e intentá otra vez.", key, displayTitle, isSeries, season, episode) }
            }
            return html(null, key, displayTitle, isSeries, season, episode)
        }

        private fun html(message: String?, key: String, title: String, isSeries: Boolean, season: Int?, episode: Int?): Response {
            val sources = WebSourceRegistry.getSources(appContext, key, displayTitle = title, isSeries = isSeries, season = season, episodeNumber = episode)
            val options = sources.joinToString("") { url ->
                val safe = esc(url)
                "<option value=\"$safe\">$safe</option>"
            }
            val encodedKey = URLEncoder.encode(key, "UTF-8")
            val extra = "&title=${URLEncoder.encode(title, "UTF-8")}&series=$isSeries" + (season?.let { "&season=$it" } ?: "") + (episode?.let { "&episode=$it" } ?: "")
            val msg = message?.let { "<p class='msg'>${esc(it)}</p>" }.orEmpty()
            val body = """<!doctype html><html lang="es"><meta name="viewport" content="width=device-width,initial-scale=1"><meta charset="utf-8"><title>Editar fuente Xuper</title><style>body{font:16px system-ui;max-width:680px;margin:24px auto;padding:0 16px;background:#10131b;color:#f5f6fa}input,select,button{box-sizing:border-box;width:100%;padding:13px;margin:8px 0;border-radius:8px;border:1px solid #555;background:#202634;color:#fff}button{background:#7b61ff;font-weight:700}.msg{padding:12px;background:#1c4937;border-radius:8px;line-height:1.5}small{color:#bbc2d0}</style><h1>Editar fuente de Xuper</h1><p>Elegí el enlace que querés reemplazar y pegá la URL correcta. Se guarda en el televisor por Wi-Fi.</p>$msg<form method="POST" action="/save?key=$encodedKey$extra"><label>Fuente actual</label><select name="oldUrl" required>$options</select><label>URL corregida</label><input name="newUrl" type="url" placeholder="https://..." required><button type="submit">Guardar en Xuper</button></form><small>El celular y el televisor deben estar conectados a la misma red Wi-Fi. Si no se actualiza enseguida, salí y volvé a entrar a la ficha.</small></html>"""
            return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", body)
        }
        private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")
    }
}
