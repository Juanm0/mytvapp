package com.quilmes.xuper.data

import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom

/**
 * Mini servidor web DENTRO de la tele para subir subtítulos desde el celular (misma red WiFi).
 *
 * Solo está encendido mientras el panel "Subir subtítulos" del reproductor LAB está abierto.
 * Cada pedido necesita el código `t` que viaja en la dirección del QR, para que otro aparato de la
 * red no pueda mandar archivos a la tele. No usa librerías externas: solo `ServerSocket`.
 *
 * Rutas (todas con `?t=CÓDIGO`): `GET /` página, `GET /api/list` guardados, `POST /api/upload?name=` (cuerpo = archivo
 * crudo), `POST /api/delete?id=`.
 */
class LabSubtitleUploadServer(
    private val titleName: String,
    private val listSaved: () -> List<LabSubtitleStore.Saved>,
    /** Guarda el archivo. Devuelve `null` si salió bien o el mensaje de error para mostrar en el celular. */
    private val onUpload: (name: String, bytes: ByteArray) -> String?,
    private val onDelete: (id: String) -> Unit,
) {
    private val token: String = run {
        val alphabet = "abcdefghjkmnpqrstuvwxyz23456789"
        val random = SecureRandom()
        (1..8).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
    }

    @Volatile private var serverSocket: ServerSocket? = null

    /** Enciende el servidor y devuelve la dirección para el QR, o `null` si la tele no tiene una red local. */
    fun start(): String? {
        val ip = localIpv4() ?: return null
        val socket = try { ServerSocket(0, 8) } catch (e: Exception) { return null }
        serverSocket = socket
        Thread({
            while (!socket.isClosed) {
                try {
                    val client = socket.accept()
                    Thread({ handle(client) }, "xuper-sub-conn").apply { isDaemon = true }.start()
                } catch (e: Exception) {
                    if (socket.isClosed) break
                }
            }
        }, "xuper-sub-server").apply { isDaemon = true }.start()
        return "http://$ip:${socket.localPort}/?t=$token"
    }

    fun stop() {
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    private fun handle(client: Socket) {
        try {
            client.soTimeout = 20_000
            val input = BufferedInputStream(client.getInputStream())
            val output = client.getOutputStream()
            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val target = parts[1]
            val headers = HashMap<String, String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
            }
            val path = target.substringBefore('?')
            val query = parseQuery(target.substringAfter('?', ""))
            if (query["t"] != token) {
                respond(output, 403, "text/plain; charset=utf-8", "Código inválido. Escaneá de nuevo el QR de la tele.")
                return
            }
            when {
                method == "GET" && path == "/" ->
                    respond(output, 200, "text/html; charset=utf-8", PAGE.replace("__TOKEN__", token).replace("__TITLE__", htmlEscape(titleName)))
                method == "GET" && path == "/api/list" ->
                    respond(output, 200, "application/json; charset=utf-8", listJson())
                method == "POST" && path == "/api/upload" -> {
                    val length = headers["content-length"]?.toIntOrNull() ?: -1
                    if (length <= 0 || length > LabSubtitleStore.MAX_BYTES) {
                        respond(output, 200, "application/json; charset=utf-8", resultJson(false, "El archivo está vacío o es demasiado grande (máximo 3 MB)."))
                        return
                    }
                    val body = ByteArray(length)
                    var read = 0
                    while (read < length) {
                        val n = input.read(body, read, length - read)
                        if (n < 0) break
                        read += n
                    }
                    if (read < length) {
                        respond(output, 200, "application/json; charset=utf-8", resultJson(false, "El archivo llegó incompleto. Probá de nuevo."))
                        return
                    }
                    val error = onUpload(query["name"].orEmpty(), body)
                    respond(output, 200, "application/json; charset=utf-8", resultJson(error == null, error))
                }
                method == "POST" && path == "/api/delete" -> {
                    query["id"]?.let { onDelete(it) }
                    respond(output, 200, "application/json; charset=utf-8", resultJson(true, null))
                }
                else -> respond(output, 404, "text/plain; charset=utf-8", "No encontrado")
            }
        } catch (e: Exception) {
            // Conexión cortada o tiempo agotado: no hay nada que hacer.
        } finally {
            runCatching { client.close() }
        }
    }

    private fun listJson(): String =
        "[" + listSaved().joinToString(",") { "{\"id\":\"${jsonEscape(it.id)}\",\"name\":\"${jsonEscape(it.name)}\"}" } + "]"

    private fun resultJson(ok: Boolean, error: String?): String =
        "{\"ok\":$ok,\"error\":\"${jsonEscape(error.orEmpty())}\"}"

    private fun respond(out: OutputStream, status: Int, contentType: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val reason = when (status) { 200 -> "OK"; 403 -> "Forbidden"; 404 -> "Not Found"; else -> "Error" }
        val head = "HTTP/1.1 $status $reason\r\nContent-Type: $contentType\r\nContent-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-store\r\nConnection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        out.write(bytes)
        out.flush()
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (sb.length < 8 * 1024) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
        }
        return null
    }

    private fun parseQuery(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        return raw.split('&').mapNotNull { pair ->
            val i = pair.indexOf('=')
            if (i <= 0) return@mapNotNull null
            val key = runCatching { URLDecoder.decode(pair.substring(0, i), "UTF-8") }.getOrNull() ?: return@mapNotNull null
            val value = runCatching { URLDecoder.decode(pair.substring(i + 1), "UTF-8") }.getOrDefault("")
            key to value
        }.toMap()
    }

    private fun htmlEscape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun jsonEscape(s: String) = buildString {
        s.forEach { c ->
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c.code < 0x20 -> append(' ')
                else -> append(c)
            }
        }
    }

    /** IPv4 local de la tele (WiFi o cable). Sin permisos especiales. */
    private fun localIpv4(): String? {
        val candidates = mutableListOf<Pair<String, String>>()
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces()?.toList() }.getOrNull().orEmpty()
        for (nif in interfaces) {
            if (!runCatching { nif.isUp && !nif.isLoopback }.getOrDefault(false)) continue
            for (addr in nif.inetAddresses) {
                if (addr is Inet4Address && addr.isSiteLocalAddress) candidates += nif.name to (addr.hostAddress ?: continue)
            }
        }
        return (candidates.firstOrNull { it.first.startsWith("wlan") || it.first.startsWith("eth") } ?: candidates.firstOrNull())?.second
    }

    companion object {
        // Sin signos de dólar a propósito (esto es un string de Kotlin). El código y el título se reemplazan al servir la página.
        private val PAGE = """
<!doctype html><html lang="es"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Subtítulos para la tele</title>
<style>
 body{margin:0;background:#0b0f14;color:#e8eef5;font-family:system-ui,Arial,sans-serif}
 main{max-width:480px;margin:0 auto;padding:22px 18px 40px}
 h1{font-size:22px;margin:0 0 4px} .sub{color:#9db0ba;font-size:14px;margin:0 0 20px}
 label{display:block;font-weight:600;margin:16px 0 6px}
 input[type=text]{width:100%;box-sizing:border-box;padding:13px;border-radius:10px;border:1px solid #2a3644;background:#141b24;color:#fff;font-size:16px}
 input[type=file]{width:100%;box-sizing:border-box;padding:12px;border-radius:10px;border:1px dashed #3a4b5e;background:#141b24;color:#cfd9e3;font-size:15px}
 button{width:100%;margin-top:18px;padding:14px;border:0;border-radius:12px;background:#0a93e8;color:#fff;font-size:17px;font-weight:600}
 button.sec{background:#2a3644} button:disabled{opacity:.5}
 .msg{margin-top:14px;padding:12px;border-radius:10px;font-size:15px;display:none}
 .ok{background:#12372b;color:#8cf0c6;display:block} .err{background:#3a1a1a;color:#ffb3b3;display:block} .info{background:#1a2a3a;color:#b9d8f5;display:block}
 h2{font-size:16px;margin:28px 0 8px;color:#9db0ba} .row{display:flex;align-items:center;gap:10px;padding:10px 12px;margin-bottom:8px;background:#141b24;border-radius:10px}
 .row span{flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap} .row button{width:auto;margin:0;padding:8px 12px;font-size:14px;background:#5a2a2a}
 .hidden{display:none}
</style></head><body><main>
<h1>Subir subtítulos</h1>
<p class="sub">Para: <b>__TITLE__</b></p>
<div id="form">
 <label for="name">Nombre de los subtítulos</label>
 <input id="name" type="text" maxlength="60" placeholder="Ej: Español - YTS" autocomplete="off">
 <label for="file">Archivo (.srt o .vtt)</label>
 <input id="file" type="file" accept=".srt,.vtt,text/plain">
 <button id="send">Enviar a la tele</button>
</div>
<div id="done" class="hidden">
 <div class="msg ok" id="doneMsg"></div>
 <button id="another" class="sec">Subir otro</button>
</div>
<div id="msg" class="msg"></div>
<h2>Guardados para este título</h2>
<div id="saved"></div>
</main>
<script>
var T="__TOKEN__";
function el(id){return document.getElementById(id);}
function msg(text,kind){var m=el("msg");m.textContent=text;m.className="msg "+(kind||"info");}
function clearMsg(){var m=el("msg");m.textContent="";m.className="msg";}
function refresh(){
 fetch("/api/list?t="+T).then(function(r){return r.json();}).then(function(list){
  var box=el("saved");box.textContent="";
  if(!list.length){var p=document.createElement("div");p.className="sub";p.textContent="Todavía no subiste ninguno.";box.appendChild(p);return;}
  list.forEach(function(item){
   var row=document.createElement("div");row.className="row";
   var s=document.createElement("span");s.textContent=item.name;
   var b=document.createElement("button");b.textContent="Borrar";
   b.onclick=function(){if(!confirm("¿Borrar "+item.name+"?"))return;
    fetch("/api/delete?t="+T+"&id="+encodeURIComponent(item.id),{method:"POST"}).then(refresh);};
   row.appendChild(s);row.appendChild(b);box.appendChild(row);});
 }).catch(function(){});
}
el("send").onclick=function(){
 var name=el("name").value.trim();var f=el("file").files[0];
 if(!name){msg("Poné un nombre para los subtítulos.","err");return;}
 if(!f){msg("Elegí el archivo .srt o .vtt.","err");return;}
 if(f.size>3*1024*1024){msg("El archivo es demasiado grande (máximo 3 MB).","err");return;}
 el("send").disabled=true;msg("Enviando…","info");
 fetch("/api/upload?t="+T+"&name="+encodeURIComponent(name),{method:"POST",body:f})
  .then(function(r){return r.json();})
  .then(function(j){
   el("send").disabled=false;
   if(j.ok){clearMsg();el("form").className="hidden";el("done").className="";
    el("doneMsg").textContent="✓ Enviado a la tele: "+name;refresh();}
   else{msg(j.error||"No se pudo guardar el archivo.","err");}
  })
  .catch(function(){el("send").disabled=false;msg("No se pudo enviar. ¿Seguís en el mismo WiFi que la tele?","err");});
};
el("another").onclick=function(){
 el("name").value="";el("file").value="";clearMsg();
 el("done").className="hidden";el("form").className="";
};
refresh();
</script></body></html>
"""
    }
}
