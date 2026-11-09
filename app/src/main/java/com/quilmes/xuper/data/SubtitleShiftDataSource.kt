@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.quilmes.xuper.data

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * Sincronía y encabezados de subtítulos externos del reproductor principal.
 *
 * Media3 no tiene "retardo de subtítulos", así que se corrige el archivo antes de que el reproductor lo lea:
 * los pedidos de pistas externas `.srt` / `.vtt` pasan por acá, se descargan completos, se les suma
 * `offsetMs` a todas las marcas de tiempo y se entregan ya corregidos. El video y el audio no se tocan.
 *
 * - `offsetMs > 0`: los subtítulos aparecen más TARDE (atrasar).
 * - `offsetMs < 0`: los subtítulos aparecen más TEMPRANO (adelantar).
 * - `offsetMs == 0`: pasa directo, sin leer nada de más.
 *
 * Cambiar el valor no afecta a lo ya cargado: hay que volver a preparar el reproductor (ver `PlayerScreen`).
 */
class SubtitleShiftDataSourceFactory(
    private val upstream: DataSource.Factory,
    private val subtitleUrls: () -> Set<String>,
    private val offsetMs: AtomicLong,
    private val subtitleRequestHeaders: (Uri) -> Map<String, String> = { emptyMap() },
) : DataSource.Factory {

    override fun createDataSource(): DataSource = ShiftingDataSource(upstream.createDataSource())

    private inner class ShiftingDataSource(private val inner: DataSource) : DataSource {
        private var buffer: ByteArray? = null
        private var position = 0
        private var shiftedUri: Uri? = null
        private var innerOpen = false

        override fun addTransferListener(transferListener: TransferListener) {
            inner.addTransferListener(transferListener)
        }

        override fun open(dataSpec: DataSpec): Long {
            val offset = offsetMs.get()
            buffer = null
            val subtitle = isSubtitle(dataSpec.uri)
            val headers = if (subtitle) subtitleRequestHeaders(dataSpec.uri) else emptyMap()
            val request = if (headers.isNotEmpty()) {
                dataSpec.buildUpon().setHttpRequestHeaders(headers).build()
            } else dataSpec
            if (offset == 0L || !subtitle) {
                val length = inner.open(request)
                innerOpen = true
                return length
            }
            val bytes = try {
                inner.open(request)
                readFully(inner)
            } finally {
                runCatching { inner.close() }
            }
            val shifted = shiftSubtitleTimestamps(bytes, offset)
            buffer = shifted
            shiftedUri = dataSpec.uri
            position = dataSpec.position.toInt().coerceIn(0, shifted.size)
            val remaining = shifted.size - position
            return if (dataSpec.length == C.LENGTH_UNSET.toLong()) remaining.toLong()
            else minOf(dataSpec.length, remaining.toLong())
        }

        override fun read(target: ByteArray, offset: Int, length: Int): Int {
            val data = buffer ?: return inner.read(target, offset, length)
            if (length == 0) return 0
            if (position >= data.size) return C.RESULT_END_OF_INPUT
            val count = minOf(length, data.size - position)
            System.arraycopy(data, position, target, offset, count)
            position += count
            return count
        }

        override fun getUri(): Uri? = if (buffer != null) shiftedUri else inner.uri

        override fun getResponseHeaders(): Map<String, List<String>> =
            if (buffer != null) emptyMap() else inner.responseHeaders

        override fun close() {
            buffer = null
            shiftedUri = null
            if (innerOpen) {
                innerOpen = false
                inner.close()
            }
        }
    }

    private fun isSubtitle(uri: Uri): Boolean {
        if (uri.toString() in subtitleUrls()) return true
        val path = uri.path.orEmpty().lowercase(Locale.ROOT)
        return path.endsWith(".srt") || path.endsWith(".vtt")
    }

    private fun readFully(source: DataSource): ByteArray {
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(8 * 1024)
        while (true) {
            val read = source.read(chunk, 0, chunk.size)
            if (read == C.RESULT_END_OF_INPUT) break
            out.write(chunk, 0, read)
        }
        return out.toByteArray()
    }
}

// 00:01:02,345 (SRT) · 00:01:02.345 (VTT) · 01:02.345 (VTT sin horas)
private val TIMESTAMP = Regex("""(?:(\d{1,2}):)?(\d{1,2}):(\d{2})([.,])(\d{1,3})""")
private val TIMING_LINE = Regex("""^.*-->.*$""", RegexOption.MULTILINE)

/** Suma [offsetMs] a las marcas de tiempo de las líneas "inicio --> fin". Mantiene `,` o `.` y el resto de los bytes. */
internal fun shiftSubtitleTimestamps(bytes: ByteArray, offsetMs: Long): ByteArray {
    // ISO-8859-1 conserva todos los bytes tal cual al ir y volver (el texto con acentos no se altera).
    val text = String(bytes, Charsets.ISO_8859_1)
    val shifted = TIMING_LINE.replace(text) { line ->
        TIMESTAMP.replace(line.value) { m ->
            val hours = m.groupValues[1].ifEmpty { "0" }.toLong()
            val minutes = m.groupValues[2].toLong()
            val seconds = m.groupValues[3].toLong()
            val millis = m.groupValues[5].padEnd(3, '0').toLong()
            val total = (hours * 3_600_000L + minutes * 60_000L + seconds * 1_000L + millis + offsetMs).coerceAtLeast(0L)
            String.format(
                Locale.ROOT, "%02d:%02d:%02d%s%03d",
                total / 3_600_000L, (total / 60_000L) % 60L, (total / 1_000L) % 60L, m.groupValues[4], total % 1_000L,
            )
        }
    }
    return shifted.toByteArray(Charsets.ISO_8859_1)
}
