package com.quilmes.xuper.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Subtítulos que la persona subió desde el celular (LAB), guardados en el almacenamiento interno de la app.
 *
 * Se guardan por título: `titleId` es el `catalogId` de LAB, que en series incluye temporada y episodio
 * (`tmdb-tv-123|s1|e4`), así que cada episodio tiene sus propios subtítulos.
 * Carpeta: `files/lab-subtitles/<titleId>/` con los archivos `.srt`/`.vtt` ya pasados a UTF-8 y un `index.json`.
 */
object LabSubtitleStore {
    data class Saved(val id: String, val name: String, val fileName: String, val mimeType: String)

    const val MAX_BYTES = 3 * 1024 * 1024
    private const val MIME_SRT = "application/x-subrip"
    private const val MIME_VTT = "text/vtt"
    private val lock = Any()

    private fun titleDir(context: Context, titleId: String): File {
        val safe = titleId.replace(Regex("[^A-Za-z0-9_-]"), "_").take(80).ifBlank { "sin-id" }
        return File(File(context.filesDir, "lab-subtitles"), safe).also { it.mkdirs() }
    }

    fun list(context: Context, titleId: String): List<Saved> = synchronized(lock) {
        val index = File(titleDir(context, titleId), "index.json")
        if (!index.exists()) return emptyList()
        runCatching {
            val array = JSONArray(index.readText(Charsets.UTF_8))
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                val saved = Saved(o.optString("id"), o.optString("name"), o.optString("file"), o.optString("mime", MIME_SRT))
                saved.takeIf { it.id.isNotBlank() && File(titleDir(context, titleId), it.fileName).exists() }
            }
        }.getOrDefault(emptyList())
    }

    fun file(context: Context, titleId: String, saved: Saved): File = File(titleDir(context, titleId), saved.fileName)

    /** Guarda un archivo subido. Tira [IllegalArgumentException] con un mensaje para mostrar si no sirve. */
    fun add(context: Context, titleId: String, name: String, rawBytes: ByteArray): Saved = synchronized(lock) {
        require(rawBytes.isNotEmpty()) { "El archivo está vacío." }
        require(rawBytes.size <= MAX_BYTES) { "El archivo es demasiado grande (máximo 3 MB)." }
        val text = decodeText(rawBytes)
        require(text.contains("-->")) { "No parece un archivo de subtítulos (.srt o .vtt)." }
        val isVtt = text.trimStart().startsWith("WEBVTT")
        val cleanName = name.trim().replace(Regex("\\s+"), " ").take(60).ifBlank { "Subtítulo subido" }
        val id = System.currentTimeMillis().toString(36)
        val fileName = "$id.${if (isVtt) "vtt" else "srt"}"
        val dir = titleDir(context, titleId)
        File(dir, fileName).writeBytes(text.toByteArray(Charsets.UTF_8))
        val saved = Saved(id, cleanName, fileName, if (isVtt) MIME_VTT else MIME_SRT)
        writeIndex(dir, list(context, titleId) + saved)
        saved
    }

    fun remove(context: Context, titleId: String, id: String) = synchronized(lock) {
        val dir = titleDir(context, titleId)
        val current = list(context, titleId)
        current.firstOrNull { it.id == id }?.let { runCatching { File(dir, it.fileName).delete() } }
        writeIndex(dir, current.filterNot { it.id == id })
    }

    private fun writeIndex(dir: File, items: List<Saved>) {
        val array = JSONArray()
        items.forEach {
            array.put(JSONObject().put("id", it.id).put("name", it.name).put("file", it.fileName).put("mime", it.mimeType))
        }
        File(dir, "index.json").writeText(array.toString(), Charsets.UTF_8)
    }

    /** Los .srt viejos suelen venir en Windows-1252 / Latin-1: se detecta y se pasa a UTF-8 para que no se rompan ñ y tildes. */
    /** Texto UTF-8 de un archivo de subtítulos en cualquier codificación común. */
    fun toUtf8(bytes: ByteArray): String = decodeText(bytes)

    private fun decodeText(bytes: ByteArray): String {
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte())
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte())
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        val body = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte())
            bytes.copyOfRange(3, bytes.size) else bytes
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(body)).toString()
        } catch (e: CharacterCodingException) {
            String(body, Charset.forName("windows-1252"))
        }
    }
}
