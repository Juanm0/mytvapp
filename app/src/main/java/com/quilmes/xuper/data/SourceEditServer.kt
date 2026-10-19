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

    fun editorUrl(contentKey: String, title: String, isSeries: Boolean, season: Int? = null, episode: Int? = null, imdbId: String? = null): String? {
        val ip = localIpv4() ?: return null
        return "http://$ip:$PORT/edit?key=" + URLEncoder.encode(contentKey, "UTF-8") +
            "&title=" + URLEncoder.encode(title, "UTF-8") + "&series=$isSeries" +
            (imdbId?.takeIf { it.matches(Regex("tt\\d{5,12}")) }?.let { "&imdbId=" + URLEncoder.encode(it, "UTF-8") } ?: "") +
            (season?.let { "&season=$it" } ?: "") + (episode?.let { "&episode=$it" } ?: "")
    }

    /** Prefer the real Wi-Fi/Ethernet LAN address; VPN/virtual interfaces often appear first. */
    private fun localIpv4(): String? = runCatching {
        val interfaces = NetworkInterface.getNetworkInterfaces().toList()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
        val candidates = interfaces.flatMap { network ->
            val name = network.name.lowercase()
            network.inetAddresses.toList().filterIsInstance<Inet4Address>()
                .filter { !it.isLoopbackAddress && it.isSiteLocalAddress }
                .map { address -> Triple(address, name, address.hostAddress ?: "") }
        }.filter { it.third.isNotBlank() }
        candidates.sortedBy { (address, name, host) ->
            when {
                name.startsWith("wlan") || name.startsWith("wifi") || name.startsWith("eth") -> 0
                host.startsWith("192.168.") -> 1
                host.startsWith("10.") -> 2
                name.startsWith("tun") || name.startsWith("tap") || name.startsWith("ppp") || name.startsWith("wg") -> 9
                else -> 4
            }
        }.firstOrNull()?.first?.hostAddress
    }.getOrNull()

    private class EditHttpServer(private val appContext: Context) : NanoHTTPD(PORT) {
        override fun serve(session: IHTTPSession): Response {
            val key = session.parameters["key"]?.firstOrNull().orEmpty()
            val displayTitle = session.parameters["title"]?.firstOrNull().orEmpty()
            val isSeries = session.parameters["series"]?.firstOrNull().toBoolean()
            val imdbId = session.parameters["imdbId"]?.firstOrNull()
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
                    // Keep the pasted value as text: the editor must not reject custom schemes or
                    // provider-specific URLs. Only an empty/whitespace-only value is refused.
                    val newUrl = form["newUrl"].orEmpty()
                    if (newUrl.isBlank()) {
                        html("Pegá el enlace que querés guardar.", key, displayTitle, isSeries, season, episode, imdbId)
                    } else {
                        val saved = WebSourceRegistry.replaceSource(appContext, key, oldUrl, newUrl)
                        html(if (saved) "¡Guardado! El enlace quedó guardado en el televisor. Volvé a abrir la ficha para ver el cambio." else "No se pudo guardar en el televisor. Revisá que el teléfono y la TV sigan en la misma Wi-Fi e intentá otra vez.", key, displayTitle, isSeries, season, episode, imdbId)
                    }
                } catch (_: Exception) { html("No se pudo guardar el enlace. Volvé e intentá otra vez.", key, displayTitle, isSeries, season, episode, imdbId) }
            }
            return html(null, key, displayTitle, isSeries, season, episode, imdbId)
        }

        private fun html(message: String?, key: String, title: String, isSeries: Boolean, season: Int?, episode: Int?, imdbId: String?): Response {
            val sources = WebSourceRegistry.getSources(appContext, key, imdbId = imdbId, displayTitle = title, isSeries = isSeries, season = season, episodeNumber = episode)
            // Select an existing source by default. Previously the empty "add new" option
            // was first, so a normal Save could append a duplicate instead of replacing the
            // selected provider URL, making users think the edit had not taken effect.
            val options = buildString {
                sources.forEachIndexed { index, url ->
                    val safe = esc(url)
                    val selected = if (index == 0) " selected" else ""
                    append("<option value=\"$safe\"$selected>$safe</option>")
                }
                append("<option value=\"\">Agregar como fuente nueva</option>")
            }
            val encodedKey = URLEncoder.encode(key, "UTF-8")
            val extra = "&title=${URLEncoder.encode(title, "UTF-8")}&series=$isSeries" +
                (imdbId?.takeIf { it.matches(Regex("tt\\d{5,12}")) }?.let { "&imdbId=${URLEncoder.encode(it, "UTF-8")}" } ?: "") + (season?.let { "&season=$it" } ?: "") + (episode?.let { "&episode=$it" } ?: "")
            val msg = message?.let { "<p class='msg'>${esc(it)}</p>" }.orEmpty()
            val body = """<!doctype html><html lang="es"><meta name="viewport" content="width=device-width,initial-scale=1"><meta charset="utf-8"><title>Editar fuente Xuper</title><style>*{box-sizing:border-box}body{font:16px system-ui;max-width:680px;margin:24px auto;padding:0 16px;background:#10131b;color:#f5f6fa}input,select,button{box-sizing:border-box;width:100%;padding:13px;margin:8px 0;border-radius:8px;border:1px solid #555;background:#202634;color:#fff}input:focus,select:focus{outline:2px solid #8d78ff;border-color:#8d78ff}button{background:linear-gradient(135deg,#8065ff,#6446ed);font-weight:800;border:0;cursor:pointer;transition:transform .16s,filter .16s,box-shadow .16s}button:hover,button:focus{filter:brightness(1.15);transform:translateY(-2px);box-shadow:0 8px 22px #7355ff55}.msg{padding:12px;background:#1c4937;border-radius:8px;line-height:1.5}small{color:#bbc2d0}</style><h1>Editar fuente de Xuper</h1><p>Elegí una fuente existente para reemplazarla (ya aparece seleccionada) o elegí “Agregar como fuente nueva”. Pegá el enlace y guardá. Se guarda directamente en el televisor.</p>$msg<form method="POST" action="/save?key=$encodedKey$extra"><label>Fuente a reemplazar</label><select name="oldUrl">$options</select><label>URL o texto del enlace</label><input name="newUrl" type="text" inputmode="url" autocapitalize="off" autocomplete="off" spellcheck="false" placeholder="Pegá el link tal como te lo pasaron" required><button type="submit">Guardar en Xuper</button></form><small>Importante: celular y televisor en la misma Wi-Fi. Después de guardar, volvé a abrir la ficha en Xuper para recargar la lista de fuentes.</small></html>"""
            return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", body)
        }
        private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")
    }
}
