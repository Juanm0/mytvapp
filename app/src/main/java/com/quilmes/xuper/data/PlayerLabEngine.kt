package com.quilmes.xuper.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionManager
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

enum class PlayerLabPhase { IDLE, FETCHING_TORRENT, CONNECTING, BUFFERING, READY, ERROR }

data class PlayerLabStatus(
    val phase: PlayerLabPhase = PlayerLabPhase.IDLE,
    val message: String = "",
    /** Avance de la descarga completa del archivo de video (0..100). */
    val progressPercent: Float = 0f,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val speedBytesPerSec: Long = 0L,
    val peers: Int = 0,
    val seeds: Int = 0,
    val fileName: String = "",
    /** Avance del pre-buffer inicial (0..100) antes de abrir el reproductor. */
    val prebufferPercent: Int = 0,
    /** URL local (http://127.0.0.1:<puerto>/...) que lee ExoPlayer cuando phase == READY. */
    val videoUrl: String? = null,
)

private val VIDEO_EXT = Regex("\\.(mp4|webm|m4v|mov|mkv)$", RegexOption.IGNORE_CASE)
/** Cuánto se espera descargar antes de abrir el reproductor. */
private const val PREBUFFER_BYTES = 10L * 1024 * 1024
/** Cuántas piezas por delante de la posición de lectura se piden con prioridad. */
private const val READ_AHEAD_PIECES = 16

/**
 * PlayerLab, paso 2 (port del motor WebTorrent de `server.js`): descarga el .torrent, lo agrega a libtorrent,
 * elige el video más grande, pide las piezas en orden y lo sirve por un HTTP local con soporte de Range.
 * ExoPlayer reproduce esa URL mientras el resto sigue bajando.
 *
 * Una instancia = una sesión de reproducción. [stop] libera todo (sesión, servidor y archivos temporales).
 */
class PlayerLabEngine(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stopped = AtomicBoolean(false)
    private val _status = MutableStateFlow(PlayerLabStatus())
    val status: StateFlow<PlayerLabStatus> = _status.asStateFlow()

    @Volatile private var session: SessionManager? = null
    @Volatile private var server: TorrentHttpServer? = null
    @Volatile private var workDir: File? = null
    private var job: Job? = null

    fun start(variant: PlayerLabVariant) {
        if (stopped.get() || job != null) return
        job = scope.launch {
            try {
                run(variant)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: PlayerLabException) {
                fail(e.message ?: "No se pudo iniciar la reproducción.")
            } catch (e: Throwable) {
                android.util.Log.e("PlayerLab", "fallo del motor torrent", e)
                fail("Error del motor torrent: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    /** Detiene la reproducción y borra lo descargado. Seguro de llamar varias veces. */
    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        job?.cancel()
        val srv = server
        val sm = session
        val dir = workDir
        server = null
        session = null
        workDir = null
        // Se hace en otro hilo: detener la sesión de libtorrent puede tardar y no debe trabar la UI.
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { srv?.close() }
            runCatching { sm?.stop() }
            runCatching { dir?.deleteRecursively() }
        }
        scope.coroutineContext[Job]?.cancel()
    }

    private fun fail(message: String) {
        _status.value = _status.value.copy(phase = PlayerLabPhase.ERROR, message = message, videoUrl = null)
    }

    private suspend fun run(variant: PlayerLabVariant) {
        val torrentUrl = variant.torrentUrl ?: throw PlayerLabException("La fuente seleccionada no es válida.")
        _status.value = PlayerLabStatus(PlayerLabPhase.FETCHING_TORRENT, "Descargando archivo torrent (${variant.quality})…")

        // ---- 1) Archivo .torrent (igual que /api/start del lab: solo HTTPS, 20 B..5 MB) ----
        val torrentBytes = downloadTorrentFile(torrentUrl)
        val root = File(appContext.externalCacheDir ?: appContext.cacheDir, "playerlab")
        root.deleteRecursively()
        val dataDir = File(root, "data").apply { mkdirs() }
        val torrentFile = File(root, "source.torrent").apply { writeBytes(torrentBytes) }
        workDir = root

        val info = try {
            TorrentInfo(torrentFile)
        } catch (e: Exception) {
            throw PlayerLabException("El archivo torrent descargado no es válido.")
        }

        // ---- 2) Video más grande (mp4, webm, m4v, mov, mkv) ----
        val files = info.files()
        var fileIndex = -1
        var fileSize = -1L
        for (i in 0 until info.numFiles()) {
            val path = files.filePath(i)
            if (!VIDEO_EXT.containsMatchIn(path)) continue
            val size = files.fileSize(i)
            if (size > fileSize) { fileSize = size; fileIndex = i }
        }
        if (fileIndex < 0) throw PlayerLabException("El torrent no contiene un video compatible (MP4, WebM, M4V, MOV o MKV).")
        val relativePath = files.filePath(fileIndex)
        val fileName = relativePath.substringAfterLast('/')
        val extension = fileName.substringAfterLast('.', "mp4").lowercase()
        val fileOffset = files.fileOffset(fileIndex)
        val pieceLength = info.pieceLength().toLong()
        val firstPiece = (fileOffset / pieceLength).toInt()
        val lastPiece = ((fileOffset + fileSize - 1) / pieceLength).toInt()

        // ---- 3) Sesión libtorrent ----
        _status.value = PlayerLabStatus(
            PlayerLabPhase.CONNECTING, "Conectando con la red torrent…",
            totalBytes = fileSize, fileName = fileName,
        )
        val sm = SessionManager()
        session = sm
        sm.start()
        if (stopped.get()) { runCatching { sm.stop() }; return }
        sm.download(info, dataDir)
        val infoHash = info.infoHash()
        var handle: TorrentHandle? = null
        for (attempt in 0 until 100) {
            val candidate = sm.find(infoHash)
            if (candidate != null && candidate.isValid) { handle = candidate; break }
            delay(100)
        }
        val th = handle ?: throw PlayerLabException("No se pudo agregar el torrent al motor de descarga.")

        th.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)
        val priorities = Array(info.numFiles()) { if (it == fileIndex) Priority.DEFAULT else Priority.IGNORE }
        th.prioritizeFiles(priorities)
        th.resume()

        // Piezas del principio (para arrancar) y del final (los MP4 sin "faststart" guardan el índice al final).
        val headPieces = ((PREBUFFER_BYTES + pieceLength - 1) / pieceLength).toInt().coerceIn(2, 64)
            .coerceAtMost(lastPiece - firstPiece + 1)
        for (i in 0 until headPieces) th.setPieceDeadline(firstPiece + i, 100 + i * 80)
        for (i in 0 until minOf(2, lastPiece - firstPiece + 1)) {
            val p = lastPiece - i
            if (p >= firstPiece + headPieces) th.setPieceDeadline(p, 3_000 + i * 200)
        }

        // ---- 4) Servidor HTTP local ----
        val srv = TorrentHttpServer(
            handle = th,
            file = File(dataDir, relativePath),
            fileOffset = fileOffset,
            fileSize = fileSize,
            pieceLength = pieceLength,
            firstPiece = firstPiece,
            lastPiece = lastPiece,
            mime = when (extension) {
                "webm" -> "video/webm"
                "mkv" -> "video/x-matroska"
                "mov" -> "video/quicktime"
                else -> "video/mp4"
            },
        )
        srv.start()
        server = srv
        if (stopped.get()) { srv.close(); return }
        val localUrl = "http://127.0.0.1:${srv.port}/video.$extension"

        // ---- 5) Vigilar el estado; abrir el reproductor cuando el pre-buffer esté listo ----
        var ready = false
        val startedAt = System.currentTimeMillis()
        while (currentCoroutineContext().isActive && !stopped.get()) {
            val st = th.status()
            var have = 0
            for (i in 0 until headPieces) if (th.havePiece(firstPiece + i)) have++
            val prebuffer = (have * 100) / headPieces
            if (!ready && have == headPieces) ready = true
            val peers = st.numPeers()
            val elapsed = (System.currentTimeMillis() - startedAt) / 1000
            val phase = when {
                ready -> PlayerLabPhase.READY
                peers > 0 || st.downloadRate() > 0 -> PlayerLabPhase.BUFFERING
                else -> PlayerLabPhase.CONNECTING
            }
            val message = when (phase) {
                PlayerLabPhase.READY -> "Descargando y reproduciendo"
                PlayerLabPhase.BUFFERING -> "Cargando el inicio del video ($prebuffer %)…"
                else -> if (elapsed > 45) "Todavía sin conexiones ($elapsed s). Puede que este torrent tenga pocos seeds: probá otra calidad."
                else "Conectando con la red torrent…"
            }
            _status.value = PlayerLabStatus(
                phase = phase,
                message = message,
                progressPercent = (st.progress() * 100f).coerceIn(0f, 100f),
                downloadedBytes = (st.progress().toDouble() * fileSize).toLong().coerceIn(0L, fileSize),
                totalBytes = fileSize,
                speedBytesPerSec = st.downloadRate().toLong(),
                peers = peers,
                seeds = st.numSeeds(),
                fileName = fileName,
                prebufferPercent = prebuffer,
                videoUrl = if (ready) localUrl else null,
            )
            delay(1_000)
        }
    }

    private fun downloadTorrentFile(url: String): ByteArray {
        if (!url.startsWith("https://")) throw PlayerLabException("La URL del archivo torrent debe usar HTTPS.")
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", PlayerLabRepository.USER_AGENT)
        }
        try {
            if (conn.responseCode !in 200..299) {
                throw PlayerLabException("No se pudo descargar el archivo torrent (HTTP ${conn.responseCode}).")
            }
            val bytes = conn.inputStream.use { it.readBytes() }
            if (bytes.size < 20 || bytes.size > 5 * 1024 * 1024) {
                throw PlayerLabException("El archivo torrent descargado no tiene un tamaño válido.")
            }
            return bytes
        } catch (e: PlayerLabException) {
            throw e
        } catch (e: IOException) {
            throw PlayerLabException("No se pudo descargar el archivo torrent (${e.message ?: "sin conexión"}).")
        } finally {
            conn.disconnect()
        }
    }

    /**
     * HTTP mínimo en 127.0.0.1 (GET/HEAD con Range) que lee el archivo en disco a medida que libtorrent
     * completa piezas. Equivale al `createServer` de WebTorrent en el lab.
     */
    private class TorrentHttpServer(
        private val handle: TorrentHandle,
        private val file: File,
        private val fileOffset: Long,
        private val fileSize: Long,
        private val pieceLength: Long,
        private val firstPiece: Int,
        private val lastPiece: Int,
        private val mime: String,
    ) {
        private val socket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        private val pool: ExecutorService = Executors.newCachedThreadPool()
        @Volatile private var closed = false
        val port: Int get() = socket.localPort

        fun start() {
            pool.execute {
                while (!closed) {
                    val client = try { socket.accept() } catch (_: IOException) { break }
                    pool.execute { runCatching { serve(client) }; runCatching { client.close() } }
                }
            }
        }

        fun close() {
            closed = true
            runCatching { socket.close() }
            pool.shutdownNow()
        }

        private fun serve(client: Socket) {
            val reader = client.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            val requestLine = reader.readLine() ?: return
            val method = requestLine.substringBefore(' ').uppercase()
            var rangeHeader: String? = null
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                if (line.startsWith("range:", ignoreCase = true)) rangeHeader = line.substringAfter(':').trim()
            }
            val out = BufferedOutputStream(client.getOutputStream(), 64 * 1024)
            if (method != "GET" && method != "HEAD") {
                out.write("HTTP/1.1 405 Method Not Allowed\r\nConnection: close\r\nContent-Length: 0\r\n\r\n".toByteArray())
                out.flush(); return
            }

            var start = 0L
            var end = fileSize - 1
            var partial = false
            val m = rangeHeader?.let { Regex("bytes=(\\d*)-(\\d*)").find(it) }
            if (m != null) {
                val a = m.groupValues[1]
                val b = m.groupValues[2]
                if (a.isNotEmpty()) {
                    start = a.toLong()
                    end = if (b.isNotEmpty()) minOf(b.toLong(), fileSize - 1) else fileSize - 1
                } else if (b.isNotEmpty()) {
                    start = (fileSize - b.toLong()).coerceAtLeast(0L)
                }
                partial = true
            }
            if (start >= fileSize || start > end) {
                out.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$fileSize\r\nConnection: close\r\nContent-Length: 0\r\n\r\n".toByteArray())
                out.flush(); return
            }
            val length = end - start + 1
            val head = StringBuilder()
                .append(if (partial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
                .append("Content-Type: $mime\r\n")
                .append("Accept-Ranges: bytes\r\n")
                .append("Content-Length: $length\r\n")
            if (partial) head.append("Content-Range: bytes $start-$end/$fileSize\r\n")
            head.append("Connection: close\r\n\r\n")
            out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
            if (method == "HEAD") { out.flush(); return }

            var raf: RandomAccessFile? = null
            var emptyReads = 0
            try {
                val buffer = ByteArray(64 * 1024)
                var pos = start
                while (pos <= end && !closed) {
                    val absolute = fileOffset + pos
                    val piece = (absolute / pieceLength).toInt()
                    if (!awaitPiece(piece)) break
                    if (raf == null) raf = openWhenExists() ?: break
                    // Solo se lee hasta el borde de la pieza que ya está completa.
                    val pieceEndRel = (piece + 1L) * pieceLength - fileOffset
                    val limit = minOf(end + 1, pieceEndRel)
                    val want = minOf(buffer.size.toLong(), limit - pos).toInt()
                    raf.seek(pos)
                    val n = raf.read(buffer, 0, want)
                    if (n <= 0) {
                        // La pieza ya está verificada pero el archivo todavía no refleja los bytes: reintentar.
                        Thread.sleep(100)
                        if (++emptyReads > 100) break
                        continue
                    }
                    emptyReads = 0
                    out.write(buffer, 0, n)
                    pos += n
                }
                out.flush()
            } finally {
                runCatching { raf?.close() }
            }
        }

        private fun openWhenExists(): RandomAccessFile? {
            var tries = 0
            while (!closed && tries++ < 200) {
                if (file.exists()) {
                    val opened = runCatching { RandomAccessFile(file, "r") }.getOrNull()
                    if (opened != null) return opened
                }
                Thread.sleep(50)
            }
            return null
        }

        /** Bloquea hasta que la pieza esté descargada, empujando su prioridad (y la de las siguientes). */
        private fun awaitPiece(piece: Int): Boolean {
            if (handle.havePiece(piece)) return true
            prioritizeFrom(piece)
            var waited = 0
            while (!closed) {
                if (handle.havePiece(piece)) return true
                Thread.sleep(120)
                waited += 120
                if (waited % 2_000 < 120) prioritizeFrom(piece)
            }
            return false
        }

        private fun prioritizeFrom(piece: Int) {
            for (i in 0 until READ_AHEAD_PIECES) {
                val p = piece + i
                if (p > lastPiece) break
                if (p < firstPiece) continue
                if (!handle.havePiece(p)) handle.setPieceDeadline(p, 60 + i * 120)
            }
        }
    }
}
