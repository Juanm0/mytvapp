package com.quilmes.xuper.ui.screens

import android.view.KeyEvent as AndroidKeyEvent
import android.view.ViewGroup
import android.view.View
import android.app.Activity
import android.content.ContextWrapper
import android.widget.FrameLayout
import android.webkit.CookieManager
import android.os.Build
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.os.Message
import java.net.URI
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.media3.ui.TrackSelectionDialogBuilder
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import com.quilmes.xuper.data.Title
import com.quilmes.xuper.data.WebPageMediaResolver
import kotlinx.coroutines.delay

/**
 * Reproductor de estilo streaming (overlay propio, se oculta solo, pistas nativas y failover
 * de fuentes). Los controles los pensamos como los de cualquier app grande (Disney+, Netflix,
 * etc.): no se puede copiar su código — es propietario — pero sí las funciones que importan:
 * flecha izq/der adelanta y atrasa SIEMPRE (no hace falta "entrar" a ningún botón primero),
 * con un indicador que confirma el salto, play/pause, audio/subtítulos, cambiar fuente y
 * episodio siguiente. CH+/CH- siguen moviendo canales de Argentina como antes.
 */
private fun isWebPageUrl(raw: String): Boolean {
    val parsed = runCatching { URI(raw) }.getOrNull() ?: return false
    if (parsed.scheme !in setOf("http", "https") || parsed.host.isNullOrBlank()) return false
    val path = parsed.path.orEmpty().lowercase()
    return !listOf(
        ".m3u", ".m3u8", ".mpd", ".mp4", ".m4v", ".webm", ".mkv", ".mov",
        ".ts", ".m2ts", ".mts", ".3gp", ".flv", ".avi", ".mpg", ".mpeg",
        ".aac", ".m4a", ".mp3", ".ogg", ".wav", ".wmv"
    ).any(path::endsWith)
}

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    name: String,
    streamUrl: String,
    streamHeaders: Map<String, String> = emptyMap(),
    channelQueue: List<Title> = emptyList(),
    currentTitleId: String = "",
    nextEpisode: Title? = null,
    alternatePlayers: List<Title> = emptyList(),
    onChangeChannel: (String) -> Unit = {},
    onChangePlayer: (String) -> Unit = {},
    onPlayNextEpisode: (String) -> Unit = {},
    onExit: () -> Unit = {},
    directOnly: Boolean = false,
) {
    // Los embeds FrameXTV son páginas HTML, no streams directos. En modo de prueba
    // entramos al WebView de inmediato y evitamos que IPTV/ExoPlayer intente resolverlos.
    if (streamUrl.startsWith("https://framextv.xyz/embed/") || streamUrl.startsWith("https://playlatinsrc.co/")) {
        WebEmbedPlayer(url = streamUrl, onExit = onExit)
        return
    }
    var resolveAsWebPage by remember(streamUrl) { mutableStateOf(false) }
    val resolvePageCallback = remember(streamUrl, directOnly) { { if (!directOnly) resolveAsWebPage = true } }
    if (resolveAsWebPage && !directOnly) {
        WebPageResolverPlayer(name = name, pageUrl = streamUrl)
        return
    }
    NativePlayerScreen(
        name = name,
        streamUrl = streamUrl,
        streamHeaders = streamHeaders,
        channelQueue = channelQueue,
        currentTitleId = currentTitleId,
        nextEpisode = nextEpisode,
        alternatePlayers = alternatePlayers,
        onChangeChannel = onChangeChannel,
        onChangePlayer = onChangePlayer,
        onPlayNextEpisode = onPlayNextEpisode,
        onExit = onExit,
        directOnly = directOnly,
        onResolveAsWebPage = resolvePageCallback,
    )
}

@OptIn(UnstableApi::class)
@Composable
private fun NativePlayerScreen(
    name: String,
    streamUrl: String,
    streamHeaders: Map<String, String>,
    channelQueue: List<Title>,
    currentTitleId: String,
    nextEpisode: Title?,
    alternatePlayers: List<Title>,
    onChangeChannel: (String) -> Unit,
    onChangePlayer: (String) -> Unit,
    onPlayNextEpisode: (String) -> Unit,
    onExit: () -> Unit,
    directOnly: Boolean,
    onResolveAsWebPage: () -> Unit,
) {
    val context = LocalContext.current
    var error by remember(streamUrl) { mutableStateOf<String?>(null) }
    var showControls by remember(streamUrl) { mutableStateOf(true) }
    var showNextEpisode by remember { mutableStateOf(false) }
    var sourceIndex by remember(streamUrl, alternatePlayers) {
        mutableStateOf(alternatePlayers.indexOfFirst { it.streamUrl == streamUrl })
    }
    var position by remember(streamUrl) { mutableStateOf(0L) }
    var duration by remember(streamUrl) { mutableStateOf(0L) }
    var isPlaying by remember(streamUrl) { mutableStateOf(true) }
    var failedSources by remember(streamUrl) { mutableStateOf(0) }
    var attemptedUrls by remember(streamUrl) { mutableStateOf(setOf(streamUrl)) }

    // Indicador de salto (el "⏪ 10 s" / "10 s ⏩" que destella al tocar izquierda/derecha,
    // como en cualquier reproductor de streaming grande).
    var seekFlash by remember { mutableStateOf<String?>(null) }
    var seekFlashToken by remember { mutableStateOf(0) }

    // Fila de botones de abajo: en vez de depender de que izquierda/derecha muevan el foco
    // entre botones (eso es lo que quedaba "pegado" — la flecha derecha quedaba reservada
    // para adelantar y no para moverse), el foco entre botones se maneja con arriba/abajo,
    // de forma explícita y predecible.
    val buttonCount = 4 // Reproducir/Pausar, Cambiar reproductor, Audio, Subtítulos
    var focusedButton by remember(streamUrl) { mutableStateOf(0) }
    val rootFocusRequester = remember(streamUrl) { FocusRequester() }
    val buttonFocusRequesters = remember { List(buttonCount) { FocusRequester() } }

    val dataSourceFactory = remember {
        DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent("Mozilla/5.0 (Linux; Android TV) XuperQuilmesTV")
    }
    val exoPlayer = remember {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build().apply {
                // Preferir subtítulos en español automáticamente cuando el stream los ofrece.
                // Media3 conserva esta preferencia al cambiar de contenido dentro del reproductor.
                trackSelectionParameters = trackSelectionParameters.buildUpon()
                    .setPreferredTextLanguage("es")
                    .setSelectUndeterminedTextLanguage(false)
                    .build()
            }
    }

    fun setUrl(url: String, headers: Map<String, String> = emptyMap()) {
        dataSourceFactory.setDefaultRequestProperties(headers)
        val cleanUrl = url.substringBefore('?').lowercase()
        val fullLowerUrl = url.lowercase()
        val item = MediaItem.Builder().setUri(url).apply {
            when {
                cleanUrl.endsWith(".m3u8") || fullLowerUrl.contains(".m3u8?") -> setMimeType(MimeTypes.APPLICATION_M3U8)
                cleanUrl.endsWith(".mpd") || fullLowerUrl.contains(".mpd?") -> setMimeType(MimeTypes.APPLICATION_MPD)
            }
        }.build()
        attemptedUrls = attemptedUrls + url
        val resumeKey = currentTitleId.ifBlank { streamUrl }
        val savedPosition = context.getSharedPreferences("xuper-playback", android.content.Context.MODE_PRIVATE)
            .getLong(resumeKey, 0L).coerceAtLeast(0L)
        exoPlayer.setMediaItem(item, savedPosition)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
        error = null
        isPlaying = true
    }

    fun flashSeek(label: String) {
        seekFlash = label
        seekFlashToken++
    }
    LaunchedEffect(seekFlashToken) {
        if (seekFlashToken > 0) {
            delay(650)
            seekFlash = null
        }
    }

    fun seekBy(deltaMs: Long) {
        val target = (exoPlayer.currentPosition + deltaMs).coerceAtLeast(0L)
        exoPlayer.seekTo(target)
        flashSeek(if (deltaMs < 0) "⏪  10 s" else "10 s  ⏩")
        showControls = true
    }

    LaunchedEffect(exoPlayer, streamUrl) {
        setUrl(streamUrl, streamHeaders)
    }
    LaunchedEffect(streamUrl) {
        delay(100)
        runCatching { rootFocusRequester.requestFocus() }
    }
    // El foco se reasigna al botón seleccionado cada vez que los controles aparecen (al abrir
    // o al reaparecer tras auto-ocultarse), así queda listo para navegar sin tocar dos veces.
    LaunchedEffect(streamUrl, showControls) {
        if (showControls) {
            focusedButton = 0
            delay(80)
            runCatching { buttonFocusRequesters[0].requestFocus() }
        }
    }
    LaunchedEffect(exoPlayer, showControls) {
        if (showControls) {
            delay(6000)
            showControls = false
        }
    }
    LaunchedEffect(exoPlayer, nextEpisode?.id) {
        while (true) {
            position = exoPlayer.currentPosition.coerceAtLeast(0L)
            duration = exoPlayer.duration.takeIf { it > 0 } ?: 0L
            if (currentTitleId.isNotBlank() && duration > 0L) {
                val savePosition = if (position >= duration - 15_000L) 0L else position
                context.getSharedPreferences("xuper-playback", android.content.Context.MODE_PRIVATE)
                    .edit().putLong(currentTitleId, savePosition).apply()
            }
            isPlaying = exoPlayer.isPlaying
            showNextEpisode = nextEpisode != null && duration > 0 && position >= duration - 60_000L
            delay(500)
        }
    }

    // Si una URL falla, intenta automáticamente la siguiente fuente disponible. El botón manual
    // permite recorrerlas incluso cuando el stream abre pero corresponde a otra película/episodio.
    DisposableEffect(exoPlayer, alternatePlayers, sourceIndex, directOnly, onResolveAsWebPage) {
        val listener = object : Player.Listener {
            override fun onPlayerError(e: PlaybackException) {
                val currentUrl = exoPlayer.currentMediaItem?.localConfiguration?.uri?.toString().orEmpty()
                // Una URL sin extensión puede ser un stream directo o una página de reproducción.
                // Primero la probamos como medio; solo si falla el enlace inicial intentamos resolver HTML.
                if (!directOnly && currentUrl == streamUrl && isWebPageUrl(streamUrl)) {
                    onResolveAsWebPage()
                    return
                }
                val nextIndex = (1..alternatePlayers.size).asSequence()
                    .map { (sourceIndex + it) % alternatePlayers.size.coerceAtLeast(1) }
                    .firstOrNull { index ->
                        val candidateUrl = alternatePlayers.getOrNull(index)?.streamUrl.orEmpty()
                        candidateUrl.isNotBlank() && candidateUrl != currentUrl && candidateUrl !in attemptedUrls
                    }
                if (nextIndex != null) {
                    sourceIndex = nextIndex
                    failedSources++
                    setUrl(alternatePlayers[nextIndex].streamUrl, alternatePlayers[nextIndex].requestHeaders)
                    showControls = true
                } else {
                    error = "No se encontró otro enlace coincidente que no se haya probado. Puede que esta película o episodio no esté disponible en las listas cargadas."
                    showControls = true
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener) }
    }

    DisposableEffect(exoPlayer) { onDispose { exoPlayer.release() } }

    // CH+/CH-: recorre channelQueue (ya armada en MainActivity según la sección/grupo
    // del canal actual) y da la vuelta en bucle: del último pasa al primero y viceversa.
    fun changeChannelBy(delta: Int) {
        if (channelQueue.size <= 1 || currentTitleId.isBlank()) return
        val index = channelQueue.indexOfFirst { it.id == currentTitleId }
        if (index < 0) return
        val size = channelQueue.size
        val nextIndex = ((index + delta) % size + size) % size
        val next = channelQueue[nextIndex]
        if (next.id != currentTitleId) onChangeChannel(next.id)
    }

    fun togglePlayPause() {
        if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
        showControls = true
    }

    fun changeSource() {
        if (alternatePlayers.isEmpty()) {
            error = "No hay otra fuente coincidente en las listas cargadas."
            showControls = true
            return
        }
        val currentUrl = exoPlayer.currentMediaItem?.localConfiguration?.uri?.toString().orEmpty()
        val nextIndex = (1..alternatePlayers.size).asSequence()
            .map { (sourceIndex + it) % alternatePlayers.size }
            .firstOrNull { alternatePlayers[it].streamUrl.isNotBlank() && alternatePlayers[it].streamUrl != currentUrl }
        if (nextIndex != null) {
            sourceIndex = nextIndex
            // Switch locally: don't navigate away from the player, otherwise focus/controls
            // are recreated and the user loses the playback overlay.
            setUrl(alternatePlayers[nextIndex].streamUrl, alternatePlayers[nextIndex].requestHeaders)
            error = null
            showControls = true
        } else {
            error = "No se encontraron más enlaces distintos para este contenido."
            showControls = true
        }
    }

    fun moveButtonFocus(delta: Int) {
        focusedButton = (focusedButton + delta).coerceIn(0, buttonCount - 1)
        runCatching { buttonFocusRequesters[focusedButton].requestFocus() }
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black).focusRequester(rootFocusRequester).focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                // CH+/CH- y las teclas físicas de medios (si el control remoto las tiene)
                // funcionan siempre, se vean o no los controles — como en cualquier TV.
                when (event.nativeKeyEvent.keyCode) {
                    AndroidKeyEvent.KEYCODE_CHANNEL_UP -> { changeChannelBy(1); return@onPreviewKeyEvent true }
                    AndroidKeyEvent.KEYCODE_CHANNEL_DOWN -> { changeChannelBy(-1); return@onPreviewKeyEvent true }
                    AndroidKeyEvent.KEYCODE_MEDIA_REWIND -> { seekBy(-10_000); return@onPreviewKeyEvent true }
                    AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { seekBy(10_000); return@onPreviewKeyEvent true }
                    AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { togglePlayPause(); return@onPreviewKeyEvent true }
                    AndroidKeyEvent.KEYCODE_MEDIA_PLAY -> { exoPlayer.play(); isPlaying = true; return@onPreviewKeyEvent true }
                    AndroidKeyEvent.KEYCODE_MEDIA_PAUSE -> { exoPlayer.pause(); isPlaying = false; return@onPreviewKeyEvent true }
                }
                when (event.key) {
                    // Izquierda/derecha SIEMPRE adelantan o atrasan 10 s, se vean los controles
                    // o no y esté el foco donde esté — no hace falta "entrar" a un botón primero.
                    Key.DirectionLeft -> { seekBy(-10_000); true }
                    Key.DirectionRight -> { seekBy(10_000); true }
                    Key.DirectionCenter, Key.Enter -> {
                        if (!showControls) { showControls = true; true } else false
                    }
                    Key.DirectionUp -> {
                        if (!showControls) { showControls = true; true } else { moveButtonFocus(-1); true }
                    }
                    Key.DirectionDown -> {
                        if (!showControls) { showControls = true; true } else { moveButtonFocus(1); true }
                    }
                    else -> false
                }
            }
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = {
                PlayerView(context).apply {
                    player = exoPlayer
                    useController = false
                    keepScreenOn = true
                    isFocusable = false
                    isFocusableInTouchMode = false
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    setOnClickListener { showControls = true }
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                }
            },
            update = { it.player = exoPlayer },
        )

        // Indicador de salto, centrado, se desvanece solo — funciona con los controles
        // visibles u ocultos.
        val seekFlashAlpha by animateFloatAsState(if (seekFlash != null) 1f else 0f, label = "seekFlashAlpha")
        if (seekFlashAlpha > 0f) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .alpha(seekFlashAlpha)
                    .background(Color(0xB0000000), RoundedCornerShape(12.dp))
                    .padding(horizontal = 28.dp, vertical = 16.dp)
            ) {
                Text(seekFlash.orEmpty(), color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            }
        }

        if (showControls) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xB8000000), Color.Transparent, Color(0xE6000000)))))
            Column(Modifier.align(Alignment.TopStart).fillMaxWidth().padding(horizontal = 38.dp, vertical = 26.dp)) {
                Text("XUPER  ·  $name", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                if (failedSources > 0) Text("Fuente alternativa $failedSources", color = Color(0xFF72E8F2), fontSize = 13.sp)
            }
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 42.dp, vertical = 30.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (duration > 0) {
                    Box(Modifier.fillMaxWidth().background(Color(0x66FFFFFF), RoundedCornerShape(4.dp)).padding(0.dp)) {
                        Box(Modifier.fillMaxWidth((position.toFloat() / duration.toFloat()).coerceIn(0f, 1f)).background(Color(0xFF65DCE8), RoundedCornerShape(4.dp)).padding(vertical = 2.dp))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(formatTime(position), color = Color.White, fontSize = 13.sp)
                        Text("−${formatTime((duration - position).coerceAtLeast(0))}", color = Color.White, fontSize = 13.sp)
                    }
                }
                Text(
                    "◀/▶  saltar 10 s · ▲/▼  cambiar control · OK  ejecutar · Atrás  salir",
                    color = Color(0x99FFFFFF),
                    fontSize = 11.sp,
                )
                // Una sola fila; arriba/abajo mueve el foco entre estos botones (ver
                // moveButtonFocus) — izquierda/derecha queda libre para adelantar/atrasar.
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        modifier = Modifier.focusRequester(buttonFocusRequesters[0]),
                        onClick = { togglePlayPause() },
                    ) { Text(if (isPlaying) "Ⅱ  Pausar" else "▶  Reproducir") }
                    Button(
                        modifier = Modifier.focusRequester(buttonFocusRequesters[1]),
                        onClick = { changeSource() },
                    ) { Text("⟳  Cambiar reproductor") }
                    Button(
                        modifier = Modifier.focusRequester(buttonFocusRequesters[2]),
                        onClick = {
                            showControls = true
                            TrackSelectionDialogBuilder(context, "Audio", exoPlayer, C.TRACK_TYPE_AUDIO).build().show()
                        },
                    ) { Text("♫  Audio") }
                    Button(
                        modifier = Modifier.focusRequester(buttonFocusRequesters[3]),
                        onClick = {
                            showControls = true
                            TrackSelectionDialogBuilder(context, "Subtítulos", exoPlayer, C.TRACK_TYPE_TEXT).build().show()
                        },
                    ) { Text("CC  Subtítulos") }
                }
                if (error != null) {
                    Text(error!!, color = Color.White, fontSize = 15.sp, modifier = Modifier.background(Color(0xCC14202B), RoundedCornerShape(8.dp)).padding(12.dp))
                    Button(onClick = { setUrl(exoPlayer.currentMediaItem?.localConfiguration?.uri?.toString() ?: streamUrl, streamHeaders) }) { Text("Reintentar") }
                }
            }
        }
        if (showNextEpisode && nextEpisode != null) {
            Button(onClick = { onPlayNextEpisode(nextEpisode.id) }, modifier = Modifier.align(Alignment.BottomEnd).padding(30.dp)) {
                Text("▶  Episodio siguiente · ${nextEpisode.name}")
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}


/** Resuelve una URL de detalle fuera del hilo principal y deriva al reproductor adecuado. */
@Composable
private fun WebPageResolverPlayer(name: String, pageUrl: String) {
    var attempt by remember(pageUrl) { mutableStateOf(0) }
    var result by remember(pageUrl) { mutableStateOf<WebPageMediaResolver.Result?>(null) }

    LaunchedEffect(pageUrl, attempt) {
        result = null
        result = WebPageMediaResolver.resolve(pageUrl)
    }

    when (val resolved = result) {
        null -> Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Buscando una fuente declarada por la página…", color = Color.White, fontSize = 18.sp)
                Text("Esto puede tardar unos segundos", color = Color(0xFF9CCBD4), fontSize = 13.sp)
            }
        }
        is WebPageMediaResolver.Result.DirectMedia -> PlayerScreen(name = name, streamUrl = resolved.url, directOnly = true)
        is WebPageMediaResolver.Result.EmbedPage -> WebEmbedPlayer(url = resolved.url)
        is WebPageMediaResolver.Result.NotFound -> Box(
            Modifier.fillMaxSize().background(Color(0xFF07111B)).padding(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("No se encontró una fuente reproducible", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(resolved.reason, color = Color(0xFFB9CDD6), fontSize = 15.sp)
                Text("El extractor solo lee fuentes públicas del HTML inicial. Si el sitio construye el reproductor con JavaScript, la app no puede deducir una URL de vídeo fiable con Jsoup.", color = Color(0xFF8BA7B5), fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { attempt++ }) { Text("Reintentar") }
                }
            }
        }
    }
}

/**
 * Un embed es una página HTML, no un archivo de video: se renderiza en WebView.
 * ExoPlayer queda reservado para URLs directas (MP4, HLS/M3U8, DASH/MPD, etc.).
 */
@Composable
private fun WebEmbedPlayer(url: String, onExit: () -> Unit = {}) {
    val context = LocalContext.current
    var progress by remember(url) { mutableStateOf(0) }
    var pageError by remember(url) { mutableStateOf<String?>(null) }
    var pageFinished by remember(url) { mutableStateOf(false) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    // No dejamos un indicador de carga infinito si el servidor no termina de responder.
    LaunchedEffect(url) {
        delay(30_000)
        if (!pageFinished && pageError == null) {
            pageError = "El sitio no terminó de cargar en 30 segundos. Puede estar caído, bloqueando la app o esperando una verificación incompatible."
        }
    }


    BackHandler(enabled = true) {
        if (webViewRef?.canGoBack() == true) webViewRef?.goBack() else onExit()
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { viewContext ->
                val container = FrameLayout(viewContext).apply { setBackgroundColor(android.graphics.Color.BLACK) }
                val webView = WebView(viewContext).apply {
                    setBackgroundColor(android.graphics.Color.BLACK)
                    setLayerType(View.LAYER_TYPE_HARDWARE, null)
                    settings.cacheMode = WebSettings.LOAD_DEFAULT
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    // Bloqueo básico de ventanas emergentes y contenido mixto inseguro.
                    settings.javaScriptCanOpenWindowsAutomatically = false
                    settings.setSupportMultipleWindows(false)
                    settings.loadsImagesAutomatically = true
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    settings.userAgentString = settings.userAgentString + " XuperQuilmesTV/1.0"
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) settings.safeBrowsingEnabled = true
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    isFocusable = true
                    isFocusableInTouchMode = true
                    layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    webChromeClient = object : WebChromeClient() {
                        private var fullscreenView: View? = null
                        private var fullscreenCallback: CustomViewCallback? = null
                        private var originalUi = 0
                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            progress = newProgress
                        }
                        override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean {
                            // Nunca crear una segunda ventana/pestaña; suele ser la vía de los popunders.
                            return false
                        }
                        override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                            if (view == null || fullscreenView != null) { callback?.onCustomViewHidden(); return }
                            val activity = generateSequence(context) { (it as? ContextWrapper)?.baseContext }.filterIsInstance<Activity>().firstOrNull()
                            val decor = activity?.window?.decorView as? ViewGroup
                            if (decor == null) { callback?.onCustomViewHidden(); return }
                            fullscreenView = view
                            fullscreenCallback = callback
                            originalUi = decor.systemUiVisibility
                            decor.addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                            decor.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        }
                        override fun onHideCustomView() {
                            val activity = generateSequence(context) { (it as? ContextWrapper)?.baseContext }.filterIsInstance<Activity>().firstOrNull()
                            val decor = activity?.window?.decorView as? ViewGroup
                            fullscreenView?.let { decor?.removeView(it) }
                            fullscreenView = null
                            fullscreenCallback?.onCustomViewHidden()
                            fullscreenCallback = null
                            decor?.systemUiVisibility = originalUi
                        }
                    }
                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                            val requestUrl = request?.url?.toString() ?: return null
                            // Una excepción del motor de filtros nunca debe cerrar el reproductor.
                            return runCatching {
                                if (XuperBraveAdblock.shouldBlock(request, view?.url)) XuperBraveAdblock.blockedResponse() else null
                            }.onFailure { android.util.Log.w("XuperWebView", "Falló el filtro de una solicitud; se permite continuar", it) }
                                .getOrNull()
                        }
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            val target = request?.url?.toString() ?: return true
                            val scheme = runCatching { URI(target).scheme?.lowercase() }.getOrNull()
                            // Solo navegación web http(s); rechaza esquemas externos y destinos publicitarios conocidos.
                            if (scheme != "http" && scheme != "https") return true
                            val blocked = runCatching { XuperBraveAdblock.shouldBlockUrl(target, view?.url, "document") }
                                .onFailure { android.util.Log.w("XuperWebView", "Falló el filtro de navegación; se permite continuar", it) }
                                .getOrDefault(false)
                            if (blocked) {
                                pageError = "Se bloqueó una redirección publicitaria o de seguimiento. Volvé al reproductor e intentá reproducir otra vez."
                                return true
                            }
                            return false
                        }
                        override fun onPageStarted(view: WebView?, pageUrl: String?, favicon: android.graphics.Bitmap?) { progress = 0; pageFinished = false; pageError = null }
                        override fun onPageFinished(view: WebView?, pageUrl: String?) {
                            progress = 100
                            pageFinished = true
                            // Preferencia best-effort para embeds web: algunos reproductores exponen
                            // pistas de subtítulos y el elemento <video> en el documento principal.
                            // Si el proveedor lo encierra en un iframe cross-origin, el navegador
                            // impide controlarlo desde aquí y respetamos esa limitación.
                            view?.evaluateJavascript("""(function(){
                              try {
                                if (!window.__xuperSubtitleResume) {
                                  window.__xuperSubtitleResume = true;
                                  var key='xuper-pos:'+location.hostname+location.pathname;
                                  var tick=function(){
                                    document.querySelectorAll('video').forEach(function(v){
                                      try {
                                        var tracks=v.textTracks;
                                        if(tracks){
                                          var spanish=Array.from(tracks).find(function(t){var x=((t.language||'')+' '+(t.label||'')).toLowerCase();return /(^|[^a-z])(es|spa|spanish|espanol|español|castellano)([^a-z]|$)/i.test(x)});
                                          if(spanish && spanish.mode==='disabled') spanish.mode='showing';
                                        }
                                        var saved=parseFloat(localStorage.getItem(key)||'0');
                                        if(saved>5 && v.duration && isFinite(v.duration) && Math.abs(v.currentTime-saved)>8 && !v.dataset.xuperRestored){
                                          v.dataset.xuperRestored='1'; v.currentTime=Math.min(saved,Math.max(0,v.duration-2));
                                        }
                                        if(v.duration && isFinite(v.duration) && v.currentTime>0) localStorage.setItem(key,String(v.currentTime));
                                      }catch(e){}
                                    });
                                  };
                                  tick(); setInterval(tick,2000);
                                  window.addEventListener('beforeunload',tick);
                                }
                              } catch(e) {}
                            })();""", null)
                            // Convierte el D-pad del control remoto en navegación por foco/click
                            // dentro del reproductor web, con un foco visual consistente.
                            view?.evaluateJavascript("""(function(){
                              if(window.__xuperRemoteNavInstalled)return;window.__xuperRemoteNavInstalled=true;
                              var css='button:focus,a:focus,[role=button]:focus,input:focus,select:focus,video:focus,[tabindex]:focus{outline:3px solid #42d9ff!important;outline-offset:3px!important;box-shadow:0 0 0 4px rgba(66,217,255,.22)!important}';
                              var st=document.getElementById('xuper-remote-focus');if(!st){st=document.createElement('style');st.id='xuper-remote-focus';(document.head||document.documentElement).appendChild(st)}st.textContent=css;
                              window.__xuperRemoteKey=function(k){
                                var sel='button:not([disabled]),a[href],[role=button]:not([aria-disabled=true]),input:not([disabled]),select:not([disabled]),video,[tabindex]:not([tabindex="-1"])';
                                var els=Array.from(document.querySelectorAll(sel)).filter(function(e){var r=e.getBoundingClientRect(),s=getComputedStyle(e);return r.width>0&&r.height>0&&s.visibility!=='hidden'&&s.display!=='none'});
                                var active=document.activeElement, idx=els.indexOf(active);
                                if(k==='enter'||k==='center'){if(active&&active!==document.body){active.click();return true}if(els[0]){els[0].focus();els[0].click();return true}return false}
                                if(!['left','right','up','down'].includes(k))return false;
                                var cur=idx>=0?els[idx]:null;if(!cur){if(els[0]){els[0].focus();els[0].scrollIntoView({block:'nearest',inline:'nearest'});return true}return false}
                                var a=cur.getBoundingClientRect(),ax=a.left+a.width/2,ay=a.top+a.height/2,best=null,bestScore=Infinity;
                                els.forEach(function(e){if(e===cur)return;var r=e.getBoundingClientRect(),x=r.left+r.width/2,y=r.top+r.height/2,dx=x-ax,dy=y-ay,primary,secondary;
                                  if(k==='left'){if(dx>=-1)return;primary=-dx;secondary=Math.abs(dy)}
                                  if(k==='right'){if(dx<=1)return;primary=dx;secondary=Math.abs(dy)}
                                  if(k==='up'){if(dy>=-1)return;primary=-dy;secondary=Math.abs(dx)}
                                  if(k==='down'){if(dy<=1)return;primary=dy;secondary=Math.abs(dx)}
                                  var score=primary+secondary*1.8;if(score<bestScore){best=e;bestScore=score}
                                });
                                if(best){best.focus();best.scrollIntoView({block:'nearest',inline:'nearest',behavior:'auto'});return true}return false;
                              };
                              document.addEventListener('keydown',function(e){var map={ArrowLeft:'left',ArrowRight:'right',ArrowUp:'up',ArrowDown:'down',Enter:'center',' ':'center'};var k=map[e.key];if(k&&window.__xuperRemoteKey&&window.__xuperRemoteKey(k)){e.preventDefault();e.stopPropagation()}},true);
                            })();""", null)
                            if (view != null && !pageUrl.isNullOrBlank()) {
                                runCatching { XuperBraveAdblock.injectCosmetics(view, pageUrl) }
                                    .onFailure { android.util.Log.w("XuperWebView", "No se pudieron aplicar reglas cosméticas", it) }
                            }
                            // Refuerzo best-effort del filtro: reduce banners/capas publicitarias,
                            // bloquea window.open y enlaces de salida claramente publicitarios. No
                            // oculta video/iframe de forma global para no romper el reproductor.
                            view?.evaluateJavascript("""(function(){
                              try {
                                if (!window.__xuperAdGuardInstalled) {
                                  window.__xuperAdGuardInstalled = true;
                                  window.open = function(){ return null; };
                                  var adHref = /doubleclick|googlesyndication|googleadservices|popunder|popcash|popads|adsterra|exoclick|onclickads|clickadu|trafficjunky|ad-maven|admaven|hilltopads|monetag/i;
                                  document.addEventListener('click', function(ev){
                                    var a = ev.target && ev.target.closest ? ev.target.closest('a[href]') : null;
                                    if (a && adHref.test(a.href || '')) { ev.preventDefault(); ev.stopImmediatePropagation(); }
                                  }, true);
                                }
                                var css = '[id*="ad-" i],[id^="ads" i],[class*="popunder" i],[class*="popup-ad" i],[class*="advertisement" i],[class*="ad-banner" i],[class*="adsbox" i],[class*="ad-container" i],iframe[src*="doubleclick" i],iframe[src*="googlesyndication" i]{display:none!important;visibility:hidden!important;pointer-events:none!important;max-height:0!important;max-width:0!important}';
                                var style=document.getElementById('xuper-ad-cosmetics');
                                if(!style){style=document.createElement('style');style.id='xuper-ad-cosmetics';(document.head||document.documentElement).appendChild(style);} style.textContent=css;
                              } catch(e) {}
                            })();""", null)

                        }
                        override fun onRenderProcessGone(view: WebView?, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
                            // Android puede matar el proceso aislado de WebView por memoria o por un fallo del sitio.
                            // Consumir el evento evita que ese fallo arrastre la Activity de Xuper.
                            pageError = "El reproductor web se cerró inesperadamente. Volvé atrás e intentá otra fuente."
                            if (view != null) {
                                runCatching { (view.parent as? ViewGroup)?.removeView(view) }
                                // Ya fuera del contenedor, liberar el WebView que perdió su renderer.
                                runCatching { view.destroy() }
                            }
                            webViewRef = null
                            return true
                        }
                        override fun onReceivedError(view: WebView?, request: android.webkit.WebResourceRequest?, error: android.webkit.WebResourceError?) {
                            if (request?.isForMainFrame == true) {
                                val description = error?.description?.toString()?.takeIf { it.isNotBlank() }
                                pageError = if (description != null) "Falló la carga de la página: $description" else "No se pudo cargar la página. Revisá la conexión e intentá de nuevo."
                            }
                        }
                        override fun onReceivedHttpError(view: WebView?, request: android.webkit.WebResourceRequest?, response: android.webkit.WebResourceResponse?) {
                            if (request?.isForMainFrame == true && (response?.statusCode ?: 200) >= 400) pageError = "El sitio respondió HTTP ${response?.statusCode}. Puede que el contenido no esté disponible."
                        }
                    }
                    setDownloadListener { _, _, _, _, _ -> pageError = "El sitio ofreció una descarga en lugar de reproducir el video." }
                    loadUrl(url)
                    requestFocus()
                }
                webViewRef = webView
                container.addView(webView)
                container
            },
            update = { container ->
                val webView = container.getChildAt(0) as? WebView
                if (webView != null && webView.url != url && webView.tag != url) { webView.tag = url; webView.loadUrl(url) }
            },
            onRelease = { container ->
                (container.getChildAt(0) as? WebView)?.let { webView ->
                    runCatching { webView.stopLoading() }
                    runCatching { webView.loadUrl("about:blank") }
                    runCatching { webView.clearHistory() }
                    runCatching { webView.removeAllViews() }
                    runCatching { webView.destroy() }
                }
                webViewRef = null
            },
        )
        // Controles nativos visibles y enfocables con el D-pad: no dependemos de que el sitio
        // web exponga sus botones al foco del WebView. El click intenta reproducir el video
        // o activar un botón de play reconocible en el documento/iframe accesible.
        Row(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(22.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = {
                webViewRef?.evaluateJavascript("""(function(){
                  try {
                    var videos=Array.from(document.querySelectorAll('video'));
                    videos.forEach(function(v){v.muted=false;var p=v.play();if(p&&p.catch)p.catch(function(){});});
                    var labels=/play|reproducir|watch now|ver ahora|start/i;
                    var els=Array.from(document.querySelectorAll('button,[role=button],a,[aria-label],.play,.vjs-big-play-button'));
                    var b=els.find(function(e){var x=((e.innerText||'')+' '+(e.getAttribute('aria-label')||'')+' '+(e.title||'')+' '+e.className).toLowerCase();var r=e.getBoundingClientRect();return labels.test(x)&&r.width>0&&r.height>0});
                    if(b)b.click();
                    return 'attempted';
                  }catch(e){return 'error'}
                })();""", null)
            }) { Text("▶  Reproducir") }
            Button(onClick = {
                webViewRef?.evaluateJavascript("""(function(){try{
                  var videos=Array.from(document.querySelectorAll('video'));
                  var tracks=[];videos.forEach(function(v){try{Array.from(v.textTracks||[]).forEach(function(t){tracks.push(t)})}catch(e){}});
                  var t=tracks.find(function(t){return /es|spa|spanish|español|castellano/i.test((t.language||'')+' '+(t.label||''))});
                  if(t){t.mode='showing';return 'enabled'}
                  var buttons=Array.from(document.querySelectorAll('button,[role=button],[aria-label],a'));
                  var b=buttons.find(function(e){var x=((e.innerText||'')+' '+(e.getAttribute('aria-label')||'')+' '+(e.title||'')).toLowerCase();return /subtitle|captions|subtitulos|subtítulos|cc/.test(x)});
                  if(b)b.click();return 'attempted';
                }catch(e){return 'error'}})();""", null)
            }) { Text("CC  Subtítulos") }
            Button(onClick = { webViewRef?.reload() }) { Text("↻  Reiniciar") }
        }
        if (progress < 100 && pageError == null) {
            Text("Cargando reproductor web… $progress%", Modifier.align(Alignment.TopCenter).padding(16.dp).background(Color(0xCC101820), RoundedCornerShape(8.dp)).padding(12.dp), color = Color.White, fontSize = 14.sp)
        }
        if (pageError != null) {
            Column(Modifier.align(Alignment.Center).fillMaxWidth(0.78f).background(Color(0xF014202B), RoundedCornerShape(14.dp)).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("No se pudo reproducir", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(pageError.orEmpty(), color = Color(0xFFD8E3EA), fontSize = 15.sp)
                Text("Esta URL /embed/ es una página web, no un archivo de video directo. Si el proveedor bloquea WebView o no aloja este título, la app no puede generar un stream alternativo.", color = Color(0xFFB5C6D2), fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { pageError = null; progress = 0; pageFinished = false; webViewRef?.reload() }) { Text("↻  Reintentar") }
                    Button(onClick = { webViewRef?.let { if (it.canGoBack()) it.goBack() } }) { Text("←  Volver") }
                }
            }
        }
        // Se quitó la banda permanente de ayuda: tapaba parte del contenido web.
    }
}
