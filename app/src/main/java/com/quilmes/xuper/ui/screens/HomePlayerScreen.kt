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
import android.webkit.JavascriptInterface
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
import androidx.compose.runtime.key
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
import com.quilmes.xuper.ui.components.Button
import androidx.tv.material3.Text
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.quilmes.xuper.data.ContentKind
import com.quilmes.xuper.data.Title
import com.quilmes.xuper.data.TmdbCatalogService
import com.quilmes.xuper.data.WebSourceRegistry
import kotlinx.coroutines.delay
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentHashMap
import android.util.Base64
import org.json.JSONObject

/** Enlace de video y subtítulos que la propia página de CineSrc solicitó. */
private data class HomeStream(
    val url: String,
    val referer: String,
    val userAgent: String,
    val cookie: String = "",
    val subtitles: List<ExternalSubtitleTrack> = emptyList(),
)

/**
 * Subtítulos de VideoAPI deducidos del propio enlace del video, sin tocar nada en su reproductor.
 *
 * El HLS crudo se ve así:  https://vimeos.zip/hls2/03/00015/7t0r7ooe2llz_,n,h,.urlset/master.m3u8?...&srv=s1&...
 * y los subtítulos viven en: https://s1.vimeos.net/vtt/03/00015/7t0r7ooe2llz_spa.vtt  (y _eng.vtt, etc.)
 * Regla: `hls2` pasa a `vtt`, el host sale de `srv=` (+ `.vimeos.net`) y el nombre es lo que hay antes del primer `_` + `_<idioma>.vtt`.
 * Se prueba cada idioma con un pedido corto y solo se devuelven los que existen de verdad (empiezan con WEBVTT).
 * Corre en un hilo de fondo (hace red). Si el enlace no tiene ese formato devuelve vacío y todo sigue como antes.
 */
private fun derivedVideoApiSubtitles(streamUrl: String, userAgent: String): List<ExternalSubtitleTrack> {
    val uri = runCatching { URI(streamUrl) }.getOrNull() ?: return emptyList()
    val match = Regex("^/hls\\d*/(.+?)/([A-Za-z0-9]+)_[^/]*/[^/]*$").find(uri.rawPath.orEmpty()) ?: return emptyList()
    val server = android.net.Uri.parse(streamUrl).getQueryParameter("srv")
        ?.takeIf { Regex("[A-Za-z0-9-]{1,12}").matches(it) } ?: return emptyList()
    val base = "https://$server.vimeos.net/vtt/${match.groupValues[1]}/${match.groupValues[2]}_"
    val referer = "${uri.scheme}://${uri.host}/"
    // código en el servidor -> (idioma para Media3, nombre que se muestra)
    val languages = listOf(
        "spa" to ("es" to "Español"), "lat" to ("es-419" to "Español (Latinoamérica)"), "eng" to ("en" to "English"),
        "por" to ("pt" to "Português"), "fre" to ("fr" to "Français"), "ita" to ("it" to "Italiano"), "ger" to ("de" to "Deutsch"),
    )
    val found = java.util.concurrent.ConcurrentHashMap<String, ExternalSubtitleTrack>()
    val threads = languages.map { (code, info) ->
        Thread {
            val url = "$base$code.vtt"
            var connection: java.net.HttpURLConnection? = null
            try {
                connection = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout = 4_000
                    readTimeout = 4_000
                    setRequestProperty("Referer", referer)
                    setRequestProperty("User-Agent", userAgent)
                    setRequestProperty("Range", "bytes=0-63")
                }
                if (connection.responseCode in 200..299) {
                    val head = connection.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8).trimStart('\uFEFF', ' ', '\n', '\r')
                    if (head.startsWith("WEBVTT")) {
                        found[code] = ExternalSubtitleTrack(
                            url = url, referer = referer, language = info.first, label = info.second,
                            mimeType = androidx.media3.common.MimeTypes.TEXT_VTT, flags = 0, source = "VideoAPI",
                        )
                    }
                }
            } catch (_: Exception) {
            } finally {
                runCatching { connection?.disconnect() }
            }
        }.apply { isDaemon = true; start() }
    }
    threads.forEach { runCatching { it.join(6_000) } }
    return languages.mapNotNull { found[it.first] }
}

private val M3U8_URL = Regex("\\.m3u8(?:[?#]|$)", RegexOption.IGNORE_CASE)
private val SUBTITLE_URL = Regex("(?:\\.(?:srt|vtt)(?:[?#]|$))|[?&]format=(?:srt|vtt)\\b|[?&]m=[A-Za-z0-9_%-=]+", RegexOption.IGNORE_CASE)
// A los 30 s se avisa que tarda y se ofrecen las opciones, pero SIGUE cargando; recién a los 5 min se da por fallido.
private const val SLOW_NOTICE_MS = 30_000L
private const val RESOLVE_TIMEOUT_MS = 300_000L
private val SUBTITLE_CAPTURE_JS = """
(function(){
  if (window.__xuperSubtitleCapture) return; window.__xuperSubtitleCapture = true;
  var URL_RE = /(?:\.srt(?:[?#]|$)|\.vtt(?:[?#]|$)|[?&]format=(?:srt|vtt)\b|[?&]m=[A-Za-z0-9_%=-]+)/i;
  var sent = Object.create(null);
  function cleanText(value){ return String(value || '')
    .replace(/\\+\//g, '/')
    .replace(/\\+u0026/gi, '&').replace(/\\+u003d/gi, '=').replace(/\\+u002f/gi, '/')
    .replace(/&amp;/gi, '&'); }
  function scan(text, ref){ try {
    text = cleanText(text);
    var re = /https?:\/\/[^\"'`\s\\<>]+/gi, m;
    while ((m = re.exec(text)) !== null) {
      var candidate = m[0].replace(/[),.;}\]]+$/, '');
      sendUrl(candidate, ref);
    }
  } catch(e) {} }
  function sendUrl(value, ref){
    try {
      var u = cleanText(value).replace(/[),.;}\]]+$/, '');
      if (!URL_RE.test(u) || sent[u]) return;
      sent[u] = true;
      window.XuperSubtitleBridge.add(u, ref || location.href);
    } catch(e) {}
  }
  function captureUrl(value, ref){
    try { var u = typeof value === 'string' ? value : (value && value.url) || ''; sendUrl(u, ref); } catch(e) {}
  }
  function smallTextResponse(r){ try {
    var ct = r.headers.get('content-type') || ''; var len = Number(r.headers.get('content-length') || 0);
    return /json|text|javascript/i.test(ct) && (!len || len < 500000);
  } catch(e) { return false; } }
  try {
    var originalFetch = window.fetch;
    if (originalFetch) window.fetch = function(input, init){
      captureUrl(input, location.href);
      return originalFetch.apply(this, arguments).then(function(r){
        if (smallTextResponse(r)) { try { r.clone().text().then(function(t){ scan(t, location.href); }).catch(function(){}); } catch(e) {} }
        return r;
      });
    };
  } catch(e) {}
  try {
    var originalOpen = XMLHttpRequest.prototype.open, originalSend = XMLHttpRequest.prototype.send;
    XMLHttpRequest.prototype.open = function(method, url){ this.__xuperUrl = url; captureUrl(url, location.href); return originalOpen.apply(this, arguments); };
    XMLHttpRequest.prototype.send = function(){
      this.addEventListener('load', function(){ try {
        var ct = this.getResponseHeader('content-type') || '';
        if (/json|text|javascript/i.test(ct)) {
          var body = this.responseType === 'json' ? JSON.stringify(this.response) :
            ((!this.responseType || this.responseType === 'text') ? this.responseText : '');
          if (body && body.length < 500000) scan(body, location.href);
        }
      } catch(e) {} });
      return originalSend.apply(this, arguments);
    };
  } catch(e) {}
  // Fallback: algunos embeds agregan pistas al DOM sin usar fetch/XHR desde el frame principal.
  try {
    var scanResources = function(){ try {
      (performance.getEntriesByType('resource') || []).forEach(function(entry){ captureUrl(entry.name, location.href); });
    } catch(e) {} };
    scanResources(); setInterval(scanResources, 700);
  } catch(e) {}
})();
""".trimIndent()

/**
 * Reproductor único del Inicio: la persona nunca ve los embeds de VideoAPI ni CineSrc.
 * 1) Un WebView oculto captura el HLS de VideoAPI.
 * 2) Otro WebView oculto reproduce el método de CineSrc: observa pedidos directos y extrae URLs de respuestas JSON/texto.
 * 3) Media3 reproduce el HLS y carga las pistas externas en la misma sesión nativa.
 */
@Composable
fun HomePlayerScreen(
    name: String,
    catalogId: String,
    catalogService: TmdbCatalogService,
    onPlayNext: (name: String, catalogId: String) -> Unit,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    val videoApiUrl = remember(catalogId) { WebSourceRegistry.videoApiUrl(catalogId) }
    val subtitleEmbedUrl = remember(catalogId) { WebSourceRegistry.cineSrcUrl(catalogId) }
    var attempt by remember(catalogId) { mutableStateOf(0) }
    var stream by remember(catalogId) { mutableStateOf<HomeStream?>(null) }
    var failure by remember(catalogId) { mutableStateOf<String?>(null) }
    var nextEpisode by remember(catalogId) { mutableStateOf<Title?>(null) }
    val baseName = remember(name) { name.substringBefore(" · ") }

    // "Continuar viendo": mismo registro que usan los demás reproductores web (sin el enlace temporal del video).
    LaunchedEffect(catalogId) {
        val url = videoApiUrl ?: return@LaunchedEffect
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
        videoApiUrl == null || subtitleEmbedUrl == null -> HomeMessage("No se pudo preparar el video o los subtítulos de este título.", onExit, null)
        found != null -> PlayerScreen(
            name = name,
            streamUrl = found.url,
            streamHeaders = buildMap {
                put("Referer", found.referer)
                originOf(found.referer)?.let { put("Origin", it) }
                put("User-Agent", found.userAgent)
                if (found.cookie.isNotBlank()) put("Cookie", found.cookie)
            },
            currentTitleId = catalogId,
            nextEpisode = nextEpisode,
            onPlayNextEpisode = playNext,
            onExit = onExit,
            directOnly = true,
            externalSubtitles = found.subtitles,
            labMode = true,
        )
        failure != null -> HomeMessage(
            message = failure.orEmpty(),
            onExit = onExit,
            onRetry = {
                failure = null
                attempt += 1
            },
        )
        else -> key(attempt) {
            HomeResolving(
                name = name,
                videoApiUrl = videoApiUrl.orEmpty(),
                subtitleEmbedUrl = subtitleEmbedUrl.orEmpty(),
                attempt = attempt,
                onFound = { stream = it },
                onFail = { failure = it },
                onExit = onExit,
                onRetry = { attempt += 1 },
            )
        }
    }
}

/** Captura en paralelo el HLS de VideoAPI y los subtítulos que solicita CineSrc. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun HomeResolving(
    name: String,
    videoApiUrl: String,
    subtitleEmbedUrl: String,
    attempt: Int,
    onFound: (HomeStream) -> Unit,
    onFail: (String) -> Unit,
    onExit: () -> Unit,
    onRetry: () -> Unit,
) {
    BackHandler { onExit() }
    val handler = remember { Handler(Looper.getMainLooper()) }
    val done = remember(videoApiUrl, subtitleEmbedUrl, attempt) { AtomicBoolean(false) }
    val streamCaptured = remember(videoApiUrl, attempt) { AtomicBoolean(false) }
    val finishScheduled = remember(videoApiUrl, subtitleEmbedUrl, attempt) { AtomicBoolean(false) }
    val capturedSubtitles = remember(subtitleEmbedUrl, attempt) { ConcurrentHashMap<String, ExternalSubtitleTrack>() }
    val lastSubtitleCaptureAt = remember(subtitleEmbedUrl, attempt) { java.util.concurrent.atomic.AtomicLong(0L) }
    // Subtítulos deducidos del enlace de VideoAPI (null = todavía comprobando).
    val derivedSubtitles = remember(videoApiUrl, attempt) { java.util.concurrent.atomic.AtomicReference<List<ExternalSubtitleTrack>?>(null) }
    val streamCapturedAt = remember(videoApiUrl, attempt) { java.util.concurrent.atomic.AtomicLong(0L) }
    val resolveStartedAt = remember(videoApiUrl, subtitleEmbedUrl, attempt) { SystemClock.uptimeMillis() }
    val subtitleBridge = remember(subtitleEmbedUrl, attempt) {
        object {
            @JavascriptInterface
            fun add(url: String?, referer: String?) {
                val value = url?.trim().orEmpty()
                if (value.startsWith("https://", true) && SUBTITLE_URL.containsMatchIn(value)) {
                    val requestReferer = referer?.takeIf { it.startsWith("https://") } ?: subtitleEmbedUrl
                    val previous = capturedSubtitles.putIfAbsent(value, subtitleFromUrl(value, requestReferer))
                    if (previous == null) {
                        lastSubtitleCaptureAt.set(SystemClock.uptimeMillis())
                        android.util.Log.i("XuperSubtitles", "Pista CineSrc capturada: ${subtitleFromUrl(value, requestReferer).label}")
                    }
                }
            }
        }
    }
    val foundState = rememberUpdatedState(onFound)
    val failState = rememberUpdatedState(onFail)
    // Se conserva el HLS encontrado junto con sus encabezados para que Media3 pueda abrirlo.
    val capturedVideo = remember(videoApiUrl, attempt) { java.util.concurrent.atomic.AtomicReference<HomeStream?>(null) }
    var videoWebView by remember(videoApiUrl, attempt) { mutableStateOf<WebView?>(null) }
    var subtitleWebView by remember(subtitleEmbedUrl, attempt) { mutableStateOf<WebView?>(null) }
    var seconds by remember(videoApiUrl, attempt) { mutableStateOf(0) }
    var slow by remember(videoApiUrl, attempt) { mutableStateOf(false) }

    fun finishWhenReady() {
        if (!streamCaptured.get() || !finishScheduled.compareAndSet(false, true)) return
        // Igual que el resolver original de CineSrc: espera a la primera lista de subtítulos
        // y deja una ventana de gracia para capturar todas las pistas; máximo 25 s.
        val poll = object : Runnable {
            override fun run() {
                if (done.get()) return
                val now = SystemClock.uptimeMillis()
                val last = lastSubtitleCaptureAt.get()
                val hasTracks = capturedSubtitles.isNotEmpty()
                val quietLongEnough = hasTracks && now - last >= 1_200L
                val timedOut = now - resolveStartedAt >= 25_000L
                // Con los subtítulos de VideoAPI ya confirmados alcanza una pequeña gracia para sumar los de CineSrc
                // que lleguen: no hace falta esperar los 25 s de antes.
                val derived = derivedSubtitles.get()
                val derivedReady = derived != null && derived.isNotEmpty() && now - streamCapturedAt.get() >= 3_000L
                // Si VideoAPI no tiene ninguno, se sigue esperando a CineSrc como siempre.
                val derivedPending = derived == null && now - streamCapturedAt.get() < 8_000L && !timedOut
                if ((quietLongEnough && !derivedPending) || timedOut || derivedReady) {
                    if (done.compareAndSet(false, true)) {
                        val tracks = (derived.orEmpty() + capturedSubtitles.values.toList())
                            .distinctBy { it.url }
                            .sortedWith(compareBy<ExternalSubtitleTrack> {
                                when { isSpanishSubtitle(it) -> 0; isEnglishSubtitle(it) -> 1; else -> 2 }
                            }.thenBy { it.label })
                        android.util.Log.i("XuperSubtitles", "Captura finalizada: ${tracks.size} pistas externas")
                        val captured = capturedVideo.get()
                        if (captured != null) foundState.value(captured.copy(subtitles = tracks))
                        else failState.value("VideoAPI no entregó un enlace de video válido.")
                    }
                } else handler.postDelayed(this, 250L)
            }
        }
        handler.postDelayed(poll, 250L)
    }

    LaunchedEffect(videoApiUrl, subtitleEmbedUrl, attempt) {
        val started = SystemClock.uptimeMillis()
        var taps = 0
        while (!done.get()) {
            delay(250)
            val elapsed = SystemClock.uptimeMillis() - started
            seconds = (elapsed / 1000).toInt()
            if (!slow && elapsed > SLOW_NOTICE_MS && !streamCaptured.get()) slow = true
            if (taps < 3 && elapsed > 3_500L * (taps + 1)) {
                taps++
                videoWebView?.let { tapCenter(it) }
                subtitleWebView?.let { tapCenter(it) }
            }
            if (elapsed > RESOLVE_TIMEOUT_MS && !streamCaptured.get() && done.compareAndSet(false, true)) {
                failState.value("VideoAPI no entregó el video en ${RESOLVE_TIMEOUT_MS / 60_000} minutos.")
            }
        }
    }

    DisposableEffect(videoApiUrl, subtitleEmbedUrl, attempt) {
        onDispose {
            done.set(true)
            videoWebView?.let { view ->
                runCatching {
                    view.stopLoading()
                    view.loadUrl("about:blank")
                    (view.parent as? ViewGroup)?.removeView(view)
                    view.destroy()
                }
            }
            subtitleWebView?.let { view ->
                runCatching {
                    view.stopLoading()
                    view.loadUrl("about:blank")
                    (view.parent as? ViewGroup)?.removeView(view)
                    view.destroy()
                }
            }
            videoWebView = null
            subtitleWebView = null
        }
    }

    fun configureWebView(view: WebView, subtitleMode: Boolean) {
        view.setBackgroundColor(android.graphics.Color.BLACK)
        view.isFocusable = false
        view.isFocusableInTouchMode = false
        view.settings.javaScriptEnabled = true
        view.settings.domStorageEnabled = true
        view.settings.mediaPlaybackRequiresUserGesture = false
        view.settings.javaScriptCanOpenWindowsAutomatically = false
        view.settings.setSupportMultipleWindows(false)
        view.settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        view.settings.userAgentString = view.settings.userAgentString + " XuperQuilmesTV/1.0"
        if (subtitleMode) {
            view.addJavascriptInterface(subtitleBridge, "XuperSubtitleBridge")
            if (runCatching { WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) }.getOrDefault(false)) {
                runCatching { WebViewCompat.addDocumentStartJavaScript(view, SUBTITLE_CAPTURE_JS, setOf("*")) }
            }
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
        val webUserAgent = view.settings.userAgentString
        view.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, pageUrl: String?, favicon: android.graphics.Bitmap?) {
                // En Android TV algunos WebView no implementan DOCUMENT_START_SCRIPT. Inyectamos
                // al inicio del documento principal; las URLs directas pedidas por iframes se
                // siguen observando desde shouldInterceptRequest.
                if (subtitleMode && !runCatching { WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) }.getOrDefault(false)) {
                    view?.evaluateJavascript(SUBTITLE_CAPTURE_JS, null)
                }
            }

            override fun onPageFinished(view: WebView?, pageUrl: String?) {
                if (subtitleMode && !runCatching { WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) }.getOrDefault(false)) {
                    view?.evaluateJavascript(SUBTITLE_CAPTURE_JS, null)
                }
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val req = request ?: return null
                val url = req.url.toString()
                if (done.get()) return XuperBraveAdblock.blockedResponse()
                if (subtitleMode) {
                    if (M3U8_URL.containsMatchIn(url)) return XuperBraveAdblock.blockedResponse()
                    if (SUBTITLE_URL.containsMatchIn(url)) {
                        val headers = req.requestHeaders.orEmpty()
                        val referer = headers.entries.firstOrNull { it.key.equals("Referer", true) }?.value
                            ?.takeIf { it.startsWith("https://", true) } ?: view?.url ?: subtitleEmbedUrl
                        val previous = capturedSubtitles.putIfAbsent(url, subtitleFromUrl(url, referer))
                        if (previous == null) {
                            lastSubtitleCaptureAt.set(SystemClock.uptimeMillis())
                            android.util.Log.i("XuperSubtitles", "Request CineSrc capturado: ${capturedSubtitles[url]?.label}")
                        }
                        // No bloquear la descarga: CineSrc debe poder completar su propio request.
                        return null
                    }
                } else if (M3U8_URL.containsMatchIn(url)) {
                    if (streamCaptured.compareAndSet(false, true)) {
                        val headers = req.requestHeaders.orEmpty()
                        val referer = headers.entries.firstOrNull { it.key.equals("Referer", true) }?.value ?: videoApiUrl
                        val ua = headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value ?: webUserAgent
                        val cookie = CookieManager.getInstance().getCookie(url).orEmpty()
                        capturedVideo.set(HomeStream(url, referer, ua, cookie))
                        streamCapturedAt.set(SystemClock.uptimeMillis())
                        // Sin tocar nada en VideoAPI: los subtítulos salen del propio enlace (ver derivedVideoApiSubtitles).
                        Thread {
                            val derived = runCatching { derivedVideoApiSubtitles(url, ua) }.getOrDefault(emptyList())
                            android.util.Log.i("XuperSubtitles", "VideoAPI: ${derived.size} subtítulos deducidos (${derived.joinToString { it.label }})")
                            derivedSubtitles.set(derived)
                        }.apply { isDaemon = true; start() }
                        finishWhenReady()
                    }
                    return XuperBraveAdblock.blockedResponse()
                }
                return runCatching {
                    if (XuperBraveAdblock.shouldBlock(req, view?.url)) XuperBraveAdblock.blockedResponse() else null
                }.getOrNull()
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val target = request?.url?.toString() ?: return true
                val scheme = runCatching { URI(target).scheme?.lowercase() }.getOrNull()
                if (scheme != "http" && scheme != "https") return true
                return runCatching { XuperBraveAdblock.shouldBlockUrl(target, view?.url, "document") }.getOrDefault(false)
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // Dos páginas ocultas: VideoAPI entrega únicamente el HLS; CineSrc aporta las pistas externas.
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { viewContext -> WebView(viewContext).apply {
                configureWebView(this, false)
                videoWebView = this
                loadUrl(videoApiUrl)
            } },
        )
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { viewContext -> WebView(viewContext).apply {
                configureWebView(this, true)
                subtitleWebView = this
                loadUrl(subtitleEmbedUrl)
            } },
        )
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(name, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
                Text(
                    "Preparando video y subtítulos…  " + if (seconds >= 60) "${seconds / 60} min ${(seconds % 60).toString().padStart(2, '0')} s" else "${seconds}s",
                    color = Color(0xFFB4C0CE), fontSize = 18.sp,
                )
                if (slow) {
                    Text("Está tardando más de lo normal. Sigue intentando…", color = Color(0xFFB4C0CE), fontSize = 16.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 6.dp)) {
                        Button(onClick = onRetry) { Text("REINTENTAR") }
                        Button(onClick = onExit) { Text("VOLVER") }
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeMessage(
    message: String,
    onExit: () -> Unit,
    onRetry: (() -> Unit)?,
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

private fun normalizedSubtitleLanguage(track: ExternalSubtitleTrack): String =
    (track.language + " " + track.label + " " + track.source.orEmpty()).lowercase()
        .replace('_', '-').replace(Regex("\\s+"), " ").trim()

private fun isSpanishSubtitle(track: ExternalSubtitleTrack): Boolean {
    val value = normalizedSubtitleLanguage(track)
    // `ea` aparece en algunas respuestas de CineSrc aunque la pista reproducida sea español.
    return Regex("(^|[^a-z])(es|spa|ea|es-[a-z0-9-]+|spanish|espanol|español|castellano|latino|latin american)([^a-z]|$)")
        .containsMatchIn(value)
}

private fun isEnglishSubtitle(track: ExternalSubtitleTrack): Boolean {
    val value = normalizedSubtitleLanguage(track)
    return Regex("(^|[^a-z])(en|eng|en-[a-z0-9-]+|english|ingles|inglés)([^a-z]|$)").containsMatchIn(value)
}

private fun subtitleFromUrl(url: String, embedUrl: String): ExternalSubtitleTrack {
    var lang = "und"
    var display = ""
    var source: String? = null
    var flags = 0
    runCatching {
        val encoded = android.net.Uri.parse(url).getQueryParameter("m")
        if (!encoded.isNullOrBlank()) {
            val normalized = encoded.replace('-', '+').replace('_', '/')
            val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
            val json = String(Base64.decode(padded, Base64.DEFAULT), Charsets.UTF_8)
            val meta = JSONObject(json)
            lang = meta.optString("lang", "und").lowercase().trim().ifBlank { "und" }
            display = meta.optString("display").trim()
            source = meta.optString("src").trim().takeIf { it.isNotBlank() }
            if (meta.optString("hi") == "1" || meta.optBoolean("hi")) flags = flags or ExternalSubtitleTrack.FLAG_HEARING_IMPAIRED
            if (meta.optString("forced") == "1" || meta.optBoolean("forced")) flags = flags or ExternalSubtitleTrack.FLAG_FORCED
        }
    }

    val evidence = "$lang $display ${source.orEmpty()}".lowercase().replace('_', '-')
    val spanish = Regex("(^|[^a-z])(es|spa|ea|es-[a-z0-9-]+|spanish|espanol|español|castellano|latino|latin american)([^a-z]|$)")
        .containsMatchIn(evidence)
    val english = !spanish && Regex("(^|[^a-z])(en|eng|en-[a-z0-9-]+|english|ingles|inglés)([^a-z]|$)")
        .containsMatchIn(evidence)
    val normalizedLanguage = when {
        spanish -> if (lang.startsWith("es-")) lang else "es"
        english -> if (lang.startsWith("en-")) lang else "en"
        lang != "und" -> lang
        else -> "und"
    }

    val rawDisplay = display.trim()
    val alias = rawDisplay.lowercase().replace('_', ' ').trim()
    val fullLabel = when {
        alias in setOf("ea", "es spanish", "spanish", "espanol", "español", "castellano") -> "Español"
        alias in setOf("spanish (la)", "spanish la", "es-la", "es-419", "latin spanish", "latin american", "latino", "español latino") -> "Español (Latinoamérica)"
        alias in setOf("en", "eng", "english") -> "English"
        rawDisplay.length > 2 -> rawDisplay // conservar el nombre completo: SDH, forced, release, variante, etc.
        spanish -> "Español"
        english -> "English"
        normalizedLanguage != "und" -> normalizedLanguage
        else -> "Subtítulo del reproductor"
    }
    val mime = if (url.substringBefore('?').substringBefore('#').endsWith(".vtt", true) ||
        android.net.Uri.parse(url).getQueryParameter("format")?.equals("vtt", true) == true)
        androidx.media3.common.MimeTypes.TEXT_VTT else androidx.media3.common.MimeTypes.APPLICATION_SUBRIP
    return ExternalSubtitleTrack(
        url = url,
        referer = embedUrl,
        language = normalizedLanguage,
        label = fullLabel,
        mimeType = mime,
        flags = flags,
        source = source,
    )
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
