package com.quilmes.xuper.ui.screens

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import com.quilmes.xuper.data.ContentKind
import com.quilmes.xuper.data.ExternalSubtitle
import com.quilmes.xuper.data.Title
import com.quilmes.xuper.data.TmdbCatalogService
import com.quilmes.xuper.data.WebSourceRegistry
import kotlinx.coroutines.delay
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean

/** Enlace de video que la propia página de CineSrc pidió, más lo necesario para repetir el pedido. */
private data class LabStream(val url: String, val referer: String, val userAgent: String, val subtitles: List<ExternalSubtitle> = emptyList())

private val M3U8_URL = Regex("\\.m3u8(?:[?#]|$)", RegexOption.IGNORE_CASE)
private const val RESOLVE_TIMEOUT_MS = 30_000L

/**
 * Reproductor de LAB: la persona nunca ve el reproductor de CineSrc.
 * 1) Un WebView oculto (tapado por la pantalla "Cargando…") abre el embed y atrapa el pedido `.m3u8` que hace
 *    la propia página (como F12 → Network). No replica tokens ni firmas: solo observa lo que el navegador pide.
 * 2) Con ese enlace (y el Referer real) se abre nuestro reproductor nativo (Media3).
 * 3) Si no aparece ningún enlace, se ofrece Reintentar o, a pedido, el reproductor original de CineSrc.
 */
@Composable
fun LabPlayerScreen(
    name: String,
    catalogId: String,
    catalogService: TmdbCatalogService,
    onPlayNext: (name: String, catalogId: String) -> Unit,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    val embedUrl = remember(catalogId) { WebSourceRegistry.cineSrcUrl(catalogId) }
    var attempt by remember(catalogId) { mutableStateOf(0) }
    var stream by remember(catalogId) { mutableStateOf<LabStream?>(null) }
    var failure by remember(catalogId) { mutableStateOf<String?>(null) }
    var useOriginal by remember(catalogId) { mutableStateOf(false) }
    var nextEpisode by remember(catalogId) { mutableStateOf<Title?>(null) }
    val baseName = remember(name) { name.substringBefore(" · ") }

    // "Continuar viendo": mismo registro que usan los demás reproductores web (sin el enlace temporal del video).
    LaunchedEffect(catalogId) {
        val url = embedUrl ?: return@LaunchedEffect
        runCatching { saveRecent(context, catalogId, name, url) }
    }

    // Siguiente episodio (solo series): el mismo criterio que el resto de Xuper.
    LaunchedEffect(catalogId) {
        nextEpisode = null
        val match = Regex("^tmdb-tv-(\\d+)\\|s(\\d+)\\|e(\\d+)$").matchEntire(catalogId) ?: return@LaunchedEffect
        val (tmdbNumber, seasonText, episodeText) = match.destructured
        val seriesId = "tmdb-tv-$tmdbNumber"
        val currentSeason = seasonText.toIntOrNull() ?: return@LaunchedEffect
        val currentEpisode = episodeText.toIntOrNull() ?: return@LaunchedEffect
        runCatching {
            val seasons = catalogService.loadSeasons(seriesId).filter { it.number > 0 }.sortedBy { it.number }
            var nextSeason = currentSeason
            var nextData = catalogService.loadEpisodes(seriesId, currentSeason).sortedBy { it.number }
                .firstOrNull { it.number > currentEpisode }
            if (nextData == null) {
                val following = seasons.firstOrNull { it.number > currentSeason }
                if (following != null) {
                    nextSeason = following.number
                    nextData = catalogService.loadEpisodes(seriesId, nextSeason).minByOrNull { it.number }
                }
            }
            val next = nextData ?: return@runCatching
            nextEpisode = Title(
                id = "$seriesId|s$nextSeason|e${next.number}",
                name = next.name,
                description = next.overview,
                posterUrl = next.stillUrl,
                streamUrl = "",
                contentKind = ContentKind.SERIES,
            )
        }
    }

    val playNext: (String) -> Unit = { nextId ->
        val m = Regex("^tmdb-tv-(\\d+)\\|s(\\d+)\\|e(\\d+)$").matchEntire(nextId)
        if (m != null) {
            val (_, s, e) = m.destructured
            onPlayNext("$baseName · T$s E$e", nextId)
        }
    }

    val found = stream
    when {
        embedUrl == null -> LabMessage("No se pudo armar el enlace de este título.", onExit, null, null)
        useOriginal -> PlayerScreen(
            name = name,
            streamUrl = embedUrl,
            currentTitleId = catalogId,
            nextEpisode = nextEpisode,
            onPlayNextEpisode = playNext,
            onExit = onExit,
        )
        found != null -> PlayerScreen(
            name = name,
            streamUrl = found.url,
            streamHeaders = buildMap {
                put("Referer", found.referer)
                originOf(found.referer)?.let { put("Origin", it) }
                put("User-Agent", found.userAgent)
            },
            currentTitleId = catalogId,
            nextEpisode = nextEpisode,
            onPlayNextEpisode = playNext,
            onExit = onExit,
            directOnly = true,
            externalSubtitles = found.subtitles,
        )
        failure != null -> LabMessage(
            message = failure.orEmpty(),
            onExit = onExit,
            onRetry = {
                failure = null
                attempt += 1
            },
            onOriginal = { useOriginal = true },
        )
        else -> LabResolving(
            name = name,
            embedUrl = embedUrl,
            attempt = attempt,
            onFound = { stream = it },
            onFail = { failure = it },
            onExit = onExit,
        )
    }
}

/** Pantalla "Cargando…" con el WebView oculto debajo. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun LabResolving(
    name: String,
    embedUrl: String,
    attempt: Int,
    onFound: (LabStream) -> Unit,
    onFail: (String) -> Unit,
    onExit: () -> Unit,
) {
    BackHandler { onExit() }
    val handler = remember { Handler(Looper.getMainLooper()) }
    val done = remember(embedUrl, attempt) { AtomicBoolean(false) }
    val foundState = rememberUpdatedState(onFound)
    val failState = rememberUpdatedState(onFail)
    var webViewRef by remember(embedUrl, attempt) { mutableStateOf<WebView?>(null) }
    var seconds by remember(embedUrl, attempt) { mutableStateOf(0) }
    val capturedSubtitles = remember(embedUrl, attempt) { linkedMapOf<String, ExternalSubtitle>() }
    val streamCaptured = remember(embedUrl, attempt) { AtomicBoolean(false) }

    // Tiempo de espera y, como en el lab, algunos toques en el centro por si el embed espera un "play".
    LaunchedEffect(embedUrl, attempt) {
        val started = SystemClock.uptimeMillis()
        var taps = 0
        while (!done.get()) {
            delay(250)
            val elapsed = SystemClock.uptimeMillis() - started
            seconds = (elapsed / 1000).toInt()
            if (taps < 3 && elapsed > 3_500L * (taps + 1)) {
                taps++
                webViewRef?.let { tapCenter(it) }
            }
            if (elapsed > RESOLVE_TIMEOUT_MS && done.compareAndSet(false, true)) {
                failState.value("No se pudo obtener el video de CineSrc en ${RESOLVE_TIMEOUT_MS / 1000} s.")
            }
        }
    }

    DisposableEffect(embedUrl, attempt) {
        onDispose {
            done.set(true)
            webViewRef?.let { view ->
                runCatching {
                    view.stopLoading()
                    view.loadUrl("about:blank")
                    (view.parent as? ViewGroup)?.removeView(view)
                    view.destroy()
                }
            }
            webViewRef = null
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // Va a pantalla completa (algunos reproductores no arrancan en una vista diminuta) pero tapado por el
        // "Cargando…" de abajo y sin foco, así que nunca se ve ni recibe el mando.
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { viewContext ->
                WebView(viewContext).apply {
                    setBackgroundColor(android.graphics.Color.BLACK)
                    isFocusable = false
                    isFocusableInTouchMode = false
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    settings.javaScriptCanOpenWindowsAutomatically = false
                    settings.setSupportMultipleWindows(false)
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    settings.userAgentString = settings.userAgentString + " XuperQuilmesTV/1.0"
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    val webUserAgent = settings.userAgentString
                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                            val req = request ?: return null
                            val url = req.url.toString()
                            // Ya tenemos el enlace: se corta todo lo demás (así no suena ni gasta datos el video oculto).
                            if (done.get()) return XuperBraveAdblock.blockedResponse()
                            if (ExternalSubtitle.isSubtitleUrl(url)) {
                                val headers = req.requestHeaders.orEmpty()
                                val referer = headers.entries.firstOrNull { it.key.equals("Referer", true) }?.value ?: embedUrl
                                synchronized(capturedSubtitles) {
                                    if (capturedSubtitles.size < 24) {
                                        capturedSubtitles.putIfAbsent(url, ExternalSubtitle.fromRequest(url, referer))
                                    }
                                }
                                return null
                            }
                            if (M3U8_URL.containsMatchIn(url)) {
                                if (streamCaptured.compareAndSet(false, true)) {
                                    val headers = req.requestHeaders.orEmpty()
                                    val referer = headers.entries.firstOrNull { it.key.equals("Referer", true) }?.value ?: embedUrl
                                    val ua = headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value ?: webUserAgent
                                    // El lab espera un pequeño margen tras el HLS para registrar también las pistas
                                    // externas que el mismo reproductor solicita al inicializarse.
                                    handler.postDelayed({
                                        if (done.compareAndSet(false, true)) {
                                            val subs = synchronized(capturedSubtitles) { capturedSubtitles.values.toList() }
                                            foundState.value(LabStream(url, referer, ua, subs))
                                        }
                                    }, 1_500L)
                                }
                                return XuperBraveAdblock.blockedResponse()
                            }
                            return runCatching {
                                if (XuperBraveAdblock.shouldBlock(req, view?.url)) XuperBraveAdblock.blockedResponse() else null
                            }.getOrNull()
                        }

                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            // Sin redirecciones ni popups: el embed se queda en su página.
                            val target = request?.url?.toString() ?: return true
                            val scheme = runCatching { URI(target).scheme?.lowercase() }.getOrNull()
                            if (scheme != "http" && scheme != "https") return true
                            return runCatching { XuperBraveAdblock.shouldBlockUrl(target, view?.url, "document") }.getOrDefault(false)
                        }
                    }
                    webViewRef = this
                    loadUrl(embedUrl)
                }
            },
        )
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(name, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
                Text("Cargando…  ${seconds}s", color = Color(0xFFB4C0CE), fontSize = 18.sp)
            }
        }
    }
}

@Composable
private fun LabMessage(
    message: String,
    onExit: () -> Unit,
    onRetry: (() -> Unit)?,
    onOriginal: (() -> Unit)?,
) {
    BackHandler { onExit() }
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(48.dp),
        ) {
            Text(message, color = Color.White, fontSize = 20.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (onRetry != null) Button(onClick = onRetry) { Text("REINTENTAR") }
                if (onOriginal != null) Button(onClick = onOriginal) { Text("USAR REPRODUCTOR ORIGINAL") }
                Button(onClick = onExit) { Text("VOLVER") }
            }
        }
    }
}

/** Toque real en el centro de la vista: llega también a los iframes de otro origen (como un clic del mouse). */
private fun tapCenter(view: WebView) {
    if (view.width <= 0 || view.height <= 0) return
    val x = view.width / 2f
    val y = view.height / 2f
    val now = SystemClock.uptimeMillis()
    val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
    val up = MotionEvent.obtain(now, now + 60, MotionEvent.ACTION_UP, x, y, 0)
    runCatching {
        view.dispatchTouchEvent(down)
        view.dispatchTouchEvent(up)
    }
    down.recycle()
    up.recycle()
}

private fun originOf(url: String): String? = runCatching {
    val u = URI(url)
    if (u.scheme == null || u.host == null) null else "${u.scheme}://${u.host}"
}.getOrNull()

/** Igual que el registro de "Continuar viendo" de los otros reproductores web de Xuper. */
private fun saveRecent(context: Context, contentKey: String, title: String, embedUrl: String) {
    val prefs = context.getSharedPreferences("xuper-library", Context.MODE_PRIVATE)
    val recent = prefs.getString("recent", "").orEmpty().split("|")
        .filter { it.isNotBlank() && it != contentKey }.toMutableList()
    recent.add(contentKey)
    val isEpisode = Regex("^tmdb-tv-(\\d+)\\|s(\\d+)\\|e(\\d+)$").matches(contentKey)
    val item = org.json.JSONObject().apply {
        put("id", contentKey)
        put("name", title)
        put("description", "")
        put("posterUrl", "")
        put("backdropUrl", "")
        put("streamUrl", embedUrl)
        put("isLive", false)
        put("year", "")
        put("rating", "")
        put("contentKind", if (isEpisode) ContentKind.SERIES.name else ContentKind.MOVIE.name)
    }
    val old = runCatching { org.json.JSONArray(prefs.getString("recent_items", "[]")) }.getOrDefault(org.json.JSONArray())
    val rebuilt = org.json.JSONArray().apply {
        put(item)
        for (i in 0 until old.length()) {
            val obj = old.optJSONObject(i) ?: continue
            if (obj.optString("id") != contentKey && length() < 29) put(obj)
        }
    }
    prefs.edit().putString("recent", recent.takeLast(30).joinToString("|"))
        .putString("recent_items", rebuilt.toString()).apply()
}
