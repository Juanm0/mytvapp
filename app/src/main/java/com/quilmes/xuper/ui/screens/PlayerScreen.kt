package com.quilmes.xuper.ui.screens

import android.view.KeyEvent as AndroidKeyEvent
import android.view.ViewGroup
import android.view.View
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.widget.FrameLayout
import android.webkit.CookieManager
import android.os.Build
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.JavascriptInterface
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.layout.ContentScale
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
import coil.compose.AsyncImage
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

/** Mantiene los parámetros del embed y fuerza controles/subtítulos visibles en VideoAPI. */
private fun videoApiEmbedUrl(raw: String, resumePositionSeconds: Long = 0L): String {
    val base = raw.substringBefore('?').trimEnd('/')
    val existing = raw.substringAfter('?', "").split('&').filter { it.isNotBlank() }
        .filterNot { it.substringBefore('=').lowercase() in setOf("autoplay", "subtitles", "hide_controls", "skin", "start_at") }
    val resumeParam = if (resumePositionSeconds > 0L) listOf("start_at=$resumePositionSeconds") else emptyList()
    return (listOf("autoplay=1", "subtitles=1", "hide_controls=0", "skin=netflix") + resumeParam + existing).joinToString(
        separator = "&", prefix = "$base?"
    )
}

/** WebView que puede recuperar el foco del hub aunque el iframe consuma las flechas. */
private class HubAwareWebView(
    context: Context,
    private val onDpadDown: () -> Boolean,
) : WebView(context) {
    override fun dispatchKeyEvent(event: AndroidKeyEvent): Boolean {
        if (event.action == AndroidKeyEvent.ACTION_DOWN && event.keyCode == AndroidKeyEvent.KEYCODE_DPAD_DOWN) {
            if (onDpadDown()) return true
        }
        return super.dispatchKeyEvent(event)
    }
}

/** Control best-effort de VideoAPI: funciona cuando el vídeo/pistas están en el documento accesible.
 * Los iframes cross-origin pueden impedir el acceso; en ese caso el WebView recibe una tecla estándar.
 */
private fun videoApiCommandScript(action: String, index: Int, seconds: Int): String {
    val safeAction = action.filter { it.isLetter() }
    return """(function(){
      var action='$safeAction', idx=$index, seconds=$seconds, handled=false;
      function walk(w){
        var d; try{d=w.document;}catch(e){return;}
        try {
          var videos=Array.from(d.querySelectorAll('video'));
          if(videos.length){
            var v=videos[0];
            if(action==='playPause'){if(v.paused){var p=v.play();if(p&&p.catch)p.catch(function(){});}else v.pause();handled=true;}
            else if(action==='seekBack'||action==='seekForward'){
              var delta=action==='seekBack'?-Math.abs(seconds):Math.abs(seconds);
              if(isFinite(v.duration))v.currentTime=Math.max(0,Math.min(v.duration||Infinity,v.currentTime+delta));else v.currentTime=Math.max(0,v.currentTime+delta);handled=true;
            } else if(action==='mute'){v.muted=!v.muted;handled=true;}
            else if(action==='subtitle'){
              var tracks=v.textTracks;
              if(tracks&&tracks.length){var selected=idx%tracks.length;for(var i=0;i<tracks.length;i++)tracks[i].mode=(i===selected?'showing':'disabled');handled=true;}
            }
          }
          if(!handled && action==='subtitle'){
            var els=Array.from(d.querySelectorAll('button,[role=button],[aria-label],[title]'));
            var target=els.find(function(e){var t=((e.getAttribute('aria-label')||'')+' '+(e.getAttribute('title')||'')+' '+(e.innerText||'')).toLowerCase();return /subtitle|captions|closed caption|\bcc\b|subtitulos|subtítulos/.test(t)});
            if(target){target.click();handled=true;}
          }
          if(!handled && (action==='playPause'||action==='mute')){
            var terms=action==='playPause'?/play|pause|reproducir|pausar/:/mute|volume|sonido/;
            var b=Array.from(d.querySelectorAll('button,[role=button],[aria-label],[title]')).find(function(e){return terms.test(((e.getAttribute('aria-label')||'')+' '+(e.getAttribute('title')||'')+' '+(e.innerText||'')).toLowerCase())});
            if(b){b.click();handled=true;}
          }
          if(!handled){try{for(var j=0;j<w.frames.length;j++)walk(w.frames[j]);}catch(e){}}
        }catch(e){}
      }
      walk(window); return handled;
    })();"""
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
    // Los embeds de VideoAPI son páginas HTML, no streams directos. En modo de prueba
    // entramos al WebView de inmediato y evitamos que IPTV/ExoPlayer intente resolverlos.
    if (streamUrl.startsWith("https://videoapi.la/e/") || streamUrl.startsWith("https://framextv.xyz/embed/")) {
        WebEmbedPlayer(
            url = streamUrl,
            contentKey = currentTitleId.ifBlank { streamUrl },
            nextEpisode = nextEpisode,
            onPlayNextEpisode = onPlayNextEpisode,
            onExit = onExit,
        )
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
    var showNextEpisode by remember(streamUrl, nextEpisode?.id) { mutableStateOf(false) }
    var nextEpisodeTriggered by remember(streamUrl, nextEpisode?.id) { mutableStateOf(false) }
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
            val remaining = (duration - position).coerceAtLeast(0L)
            showNextEpisode = nextEpisode != null && duration > 0L && remaining <= 20_000L && !nextEpisodeTriggered
            if (nextEpisode != null && duration > 0L && isPlaying && remaining <= 1_000L && !nextEpisodeTriggered) {
                nextEpisodeTriggered = true
                onPlayNextEpisode(nextEpisode.id)
            }
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
                    isFocusable = true
                    isFocusableInTouchMode = true
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
            val secondsLeft = (((duration - position).coerceAtLeast(0L) + 999L) / 1000L).toInt().coerceIn(0, 10)
            NextEpisodeOverlay(
                episode = nextEpisode,
                secondsLeft = secondsLeft,
                onPlayNow = {
                    if (!nextEpisodeTriggered) {
                        nextEpisodeTriggered = true
                        onPlayNextEpisode(nextEpisode.id)
                    }
                },
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 24.dp, bottom = if (showControls) 158.dp else 24.dp),
            )
        }
    }
}

@Composable
private fun NextEpisodeOverlay(
    episode: Title,
    secondsLeft: Int,
    onPlayNow: () -> Unit,
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null,
) {
    val match = Regex("^tmdb-tv-(\\d+)\\|s(\\d+)\\|e(\\d+)$").matchEntire(episode.id)
    val episodeMeta = match?.destructured?.let { (_, season, number) -> "Temporada $season · Episodio $number" }
        ?: "Próximo episodio"
    Row(
        modifier = modifier.widthIn(max = 430.dp)
            .background(Color(0xF018222C), RoundedCornerShape(14.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AsyncImage(
            model = episode.posterUrl.ifBlank { episode.backdropUrl },
            contentDescription = episode.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.width(124.dp).height(76.dp).background(Color(0xFF26323D), RoundedCornerShape(8.dp)),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("EPISODIO SIGUIENTE", color = Color(0xFF65DCE8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text(episode.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
            Text(episodeMeta, color = Color(0xFFCFD8DE), fontSize = 12.sp)
            Text("Episodio siguiente en $secondsLeft…", color = Color.White, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = onPlayNow) { Text("▶ Ahora") }
                if (onDismiss != null) Button(onClick = onDismiss) { Text("Cancelar") }
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

/** Identificadores de proveedores publicados en la documentación de FrameXTV.
 * "archer" está confirmado por el ejemplo de la API; los otros slugs se infieren
 * de los nombres visibles y deben validarse con el reproductor real.
 */
private val frameXtvProviders = listOf("auto", "barbarian", "archer", "goblin", "pekka")
private fun frameXtvProvider(serverIndex: Int): String =
    frameXtvProviders.getOrElse(serverIndex.coerceIn(0, frameXtvProviders.lastIndex)) { "auto" }
private fun frameXtvProviderLabel(serverIndex: Int): String = when (frameXtvProvider(serverIndex)) {
    "barbarian" -> "Barbarian"
    "archer" -> "Archer"
    "goblin" -> "Goblin"
    "pekka" -> "P.E.K.K.A."
    else -> "Auto"
}

/** Construye la página contenedora de FrameXTV y conserva el canal postMessage documentado. */
private fun frameXtvWrapper(rawUrl: String, resumePositionSeconds: Long = 0L, subtitleIndex: Int = 0, serverIndex: Int = 0): String {
    var embedUrl = rawUrl
    if (!Regex("(?:[?&])autoplay=").containsMatchIn(embedUrl)) embedUrl += if (embedUrl.contains('?')) "&autoplay=1" else "?autoplay=1"
    if (!Regex("(?:[?&])muted=").containsMatchIn(embedUrl)) embedUrl += "&muted=0"
    if (!Regex("(?:[?&])controls=").containsMatchIn(embedUrl)) embedUrl += "&controls=1"
    val safeEmbed = embedUrl.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")
    val providerName = frameXtvProvider(serverIndex)
    return """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no"><style>html,body{margin:0;width:100%;height:100%;overflow:hidden;background:#000}iframe{width:100%;height:100%;border:0;display:block}</style></head><body><iframe id="framextv-player" src="$safeEmbed" allow="autoplay; encrypted-media; fullscreen; picture-in-picture" allowfullscreen></iframe><script>
      (function(){var f=document.getElementById('framextv-player');window.xuperFrameCommand=function(action,extra){try{f.contentWindow.postMessage(Object.assign({action:action},extra||{}),'*')}catch(e){}};
      f.addEventListener('load',function(){
        setTimeout(function(){window.xuperFrameCommand('setSubtitle',{index:$subtitleIndex})},2200);
        if($serverIndex>0) setTimeout(function(){
          window.xuperFrameCommand('setProvider',{provider:'$providerName'});
        },2600);
        if($resumePositionSeconds>5) setTimeout(function(){window.xuperFrameCommand('seekBy',{seconds:$resumePositionSeconds})},3200);
      });
      window.addEventListener('message',function(e){var d=e.data;if(d&&typeof d==='object'&&d.event==='frameXTV:timeupdate'){try{window.XuperBridge.onTimeUpdate(Number(d.currentTime)||0,Number(d.duration)||0)}catch(x){}}});})();</script></body></html>"""
}

private fun dispatchWebKey(webView: WebView?, keyCode: Int) {
    if (webView == null) return
    runCatching {
        webView.requestFocus()
        webView.dispatchKeyEvent(AndroidKeyEvent(AndroidKeyEvent.ACTION_DOWN, keyCode))
        webView.dispatchKeyEvent(AndroidKeyEvent(AndroidKeyEvent.ACTION_UP, keyCode))
    }
}

/**
 * Guarda/restaura el progreso en videos HTML5 accesibles desde el documento o iframes same-origin.
 * Los iframes cross-origin bloquean este acceso por diseño del navegador; ahí el proveedor debe ofrecer su propia reanudación.
 */
private fun webPlaybackResumeScript(resumePositionSeconds: Long, autoSelectSpanish: Boolean = false): String = """(function(){
  if(window.__xuperPlaybackTracking) return; window.__xuperPlaybackTracking=true;
  var resume=$resumePositionSeconds;
  function visit(w){
    var d; try{d=w.document;}catch(e){return;}
    try{
      Array.from(d.querySelectorAll('video')).forEach(function(v){
        if($autoSelectSpanish){try{var tracks=v.textTracks;if(tracks){var spanish=Array.from(tracks).find(function(t){var x=((t.language||'')+' '+(t.label||'')).toLowerCase();return /(^|[^a-z])(es|spa|spanish|espanol|español|castellano)([^a-z]|$)/i.test(x)});if(spanish&&spanish.mode==='disabled')spanish.mode='showing';}}catch(e){}}
        if(v.dataset.xuperTracking==='1') return; v.dataset.xuperTracking='1';
        var restore=function(){try{if(resume>5 && !v.dataset.xuperRestored && isFinite(v.duration) && v.duration>0){v.dataset.xuperRestored='1';v.currentTime=Math.min(resume,Math.max(0,v.duration-2));}}catch(e){}};
        v.addEventListener('loadedmetadata',restore); if(v.readyState>=1) restore();
        v.addEventListener('timeupdate',function(){try{if(window.XuperBridge)window.XuperBridge.onTimeUpdate(Number(v.currentTime)||0,Number(v.duration)||0);}catch(e){}});
      });
      for(var i=0;i<w.frames.length;i++){try{visit(w.frames[i]);}catch(e){}}
    }catch(e){}
  }
  visit(window); setInterval(function(){visit(window);},2000);
})();"""

/**
 * Un embed es una página HTML, no un archivo de video: se renderiza en WebView.
 * ExoPlayer queda reservado para URLs directas (MP4, HLS/M3U8, DASH/MPD, etc.).
 */
@Composable
private fun WebEmbedPlayer(
    url: String,
    contentKey: String = url,
    nextEpisode: Title? = null,
    onPlayNextEpisode: (String) -> Unit = {},
    onExit: () -> Unit = {},
) {
    val context = LocalContext.current
    val playbackPrefs = remember(context) { context.getSharedPreferences("xuper-playback", Context.MODE_PRIVATE) }
    val stableContentKey = contentKey.ifBlank { url }
    val subtitlePreferenceKey = "web-subtitle:$stableContentKey"
    val serverPreferenceKey = "web-server:$stableContentKey"
    val positionPreferenceKey = "web-position:$stableContentKey"
    val savedSubtitleIndex = remember(url, stableContentKey) { playbackPrefs.getInt(subtitlePreferenceKey, -1) }
    val savedResumePosition = remember(url, stableContentKey) { playbackPrefs.getLong(positionPreferenceKey, 0L).coerceAtLeast(0L) }
    val savedServerIndex = remember(url, stableContentKey) { playbackPrefs.getInt(serverPreferenceKey, 0).coerceAtLeast(0) }
    var saveSubtitleForContent by remember(url, stableContentKey) { mutableStateOf(savedSubtitleIndex >= 0) }
    var progress by remember(url) { mutableStateOf(0) }
    var pageError by remember(url) { mutableStateOf<String?>(null) }
    var pageFinished by remember(url) { mutableStateOf(false) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    val isFrameXtv = remember(url) { runCatching { URI(url).host.equals("framextv.xyz", true) }.getOrDefault(false) }
    val isVideoApi = remember(url) { runCatching { URI(url).host.equals("videoapi.la", true) }.getOrDefault(false) }
    val hasCustomHub = isFrameXtv || isVideoApi
    var frameCurrentTime by remember(url) { mutableStateOf(savedResumePosition.toDouble()) }
    var frameDuration by remember(url) { mutableStateOf(0.0) }
    var nextEpisodeTriggered by remember(stableContentKey, nextEpisode?.id) { mutableStateOf(false) }
    var nextEpisodeDismissed by remember(stableContentKey, nextEpisode?.id) { mutableStateOf(false) }
    val nextEpisodeRemaining = (frameDuration - frameCurrentTime).coerceAtLeast(0.0)
    val showNextEpisodeCard = nextEpisode != null && frameDuration > 0.0 && nextEpisodeRemaining <= 20.0 && !nextEpisodeDismissed && !nextEpisodeTriggered
    var framePaused by remember(url) { mutableStateOf(false) }
    var frameMuted by remember(url) { mutableStateOf(false) }
    // Índice de pista de subtítulos; se reinicia al cambiar de proveedor.
    var frameSubtitleIndex by remember(url, stableContentKey) { mutableStateOf(savedSubtitleIndex.coerceAtLeast(0)) }
    var frameServerIndex by remember(url, stableContentKey) { mutableStateOf(savedServerIndex) }
    var saveServerForContent by remember(url, stableContentKey) { mutableStateOf(playbackPrefs.getBoolean("web-save-server:$stableContentKey", true)) }
    var frameControlsVisible by remember(url) { mutableStateOf(true) }
    var frameInteraction by remember(url) { mutableStateOf(0) }
    val frameControlsFocus = remember(url) { FocusRequester() }

    // VideoAPI no expone en su contrato público el tiempo actual: estimamos el avance
    // mientras la pantalla está reproduciendo y guardamos start_at para la próxima apertura.
    // Es aproximado: no detecta pausas hechas dentro del reproductor nativo del proveedor.
    LaunchedEffect(url, isVideoApi, framePaused) {
        if (isVideoApi && !framePaused) {
            while (true) {
                delay(1_000)
                frameCurrentTime += 1.0
                playbackPrefs.edit().putLong(positionPreferenceKey, frameCurrentTime.toLong()).apply()
            }
        }
    }

    // No dejamos un indicador de carga infinito si el servidor no termina de responder.
    LaunchedEffect(url) {
        delay(30_000)
        if (!pageFinished && pageError == null) {
            pageError = "El sitio no terminó de cargar en 30 segundos. Puede estar caído, bloqueando la app o esperando una verificación incompatible."
        }
    }
    LaunchedEffect(hasCustomHub, frameControlsVisible) {
        if (hasCustomHub && frameControlsVisible) {
            // Esperar a que el hub esté compuesto antes de pedir foco.
            delay(180)
            runCatching { frameControlsFocus.requestFocus() }
        } else if (hasCustomHub) {
            // El WebView recupera el foco; su dispatchKeyEvent intercepta abajo para reabrir el hub.
            delay(80)
            runCatching { webViewRef?.requestFocus() }
        } else {
            Unit
        }
    }
    // Cinco segundos sin interacción real cierran el hub; navegar por sus botones reinicia el contador.
    LaunchedEffect(hasCustomHub, frameControlsVisible, frameInteraction) {
        if (hasCustomHub && frameControlsVisible) {
            delay(5_000)
            frameControlsVisible = false
        }
    }
    LaunchedEffect(nextEpisode?.id, frameCurrentTime, frameDuration, framePaused, nextEpisodeDismissed, nextEpisodeTriggered) {
        if (nextEpisode != null && frameDuration > 0.0 && !framePaused && !nextEpisodeDismissed && !nextEpisodeTriggered) {
            val remaining = frameDuration - frameCurrentTime
            if (remaining <= 1.0 && remaining >= 0.0) {
                nextEpisodeTriggered = true
                onPlayNextEpisode(nextEpisode.id)
            }
        }
    }

    BackHandler(enabled = true) {
        when {
            // Atrás con el hub abierto solo lo cierra; recién con el hub cerrado sale del reproductor.
            hasCustomHub && frameControlsVisible -> frameControlsVisible = false
            webViewRef?.canGoBack() == true -> webViewRef?.goBack()
            else -> onExit()
        }
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black).onPreviewKeyEvent { event ->
            if (!hasCustomHub || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            if (event.nativeKeyEvent.keyCode == AndroidKeyEvent.KEYCODE_DPAD_DOWN && !frameControlsVisible) {
                frameControlsVisible = true
                frameInteraction++
                true
            } else {
                // Las flechas se dejan pasar para navegar por el hub; cada pulsación reinicia el timeout.
                if (frameControlsVisible) frameInteraction++
                false
            }
        }
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { viewContext ->
                val container = FrameLayout(viewContext).apply { setBackgroundColor(android.graphics.Color.BLACK) }
                val webView = HubAwareWebView(viewContext) {
                    if (hasCustomHub) {
                        if (!frameControlsVisible) {
                            // Los botones todavía no están compuestos: pedirles foco acá tiraba
                            // IllegalStateException (FocusRequester not initialized) y cerraba la app.
                            // El LaunchedEffect(frameControlsVisible) les da foco cuando ya existen.
                            frameControlsVisible = true
                        } else {
                            // Hub ya visible pero con el foco en el WebView: se lo devolvemos al hub.
                            runCatching { frameControlsFocus.requestFocus() }
                        }
                        frameInteraction++
                        true
                    } else false
                }.apply {
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
                    // El reproductor oficial controla sus propios botones y navegación.
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
                            // Foco visible para Android TV: resalta controles HTML accesibles y permite
                            // recorrerlos con el D-pad cuando están en el documento actual. Los iframes
                            // cross-origin no se pueden modificar desde la página contenedora.
                            // No interceptamos las flechas: el reproductor/iframe debe recibirlas para
                            // pausar, navegar por controles y buscar. Solo añadimos un indicador de foco
                            // y hacemos que los iframes sean alcanzables con el control remoto.
                            view?.evaluateJavascript("""(function(){
                              try {
                                if (!window.__xuperTvFocusStyle) {
                                  window.__xuperTvFocusStyle = true;
                                  var css = `:focus{outline:4px solid #65DCE8!important;outline-offset:4px!important;box-shadow:0 0 0 5px rgba(101,220,232,.28)!important;filter:brightness(1.18)!important} button:focus,a:focus,[role=button]:focus,[tabindex]:focus,iframe:focus{transform:scale(1.025)!important;z-index:2147483647!important}`;
                                  var st=document.createElement('style');st.id='xuper-tv-focus-style';st.textContent=css;(document.head||document.documentElement).appendChild(st);
                                  var frames=Array.from(document.querySelectorAll('iframe'));
                                  frames.forEach(function(f){
                                    if(!f.hasAttribute('tabindex')) f.setAttribute('tabindex','0');
                                    if(!f.getAttribute('title')) f.setAttribute('title','Reproductor de video');
                                  });
                                  var playerFrame=frames.find(function(f){return /player|embed|video|stream|watch/i.test((f.src||'')+' '+(f.title||''))}) || frames[0];
                                  if(playerFrame) setTimeout(function(){try{playerFrame.focus()}catch(e){}},250);
                                  // No se bloquean ni se redirigen ArrowLeft/Right/Up/Down: hacerlo
                                  // aquí impide que el iframe de otro origen controle su propio player.
                                }
                              } catch(e) {}
                            })();""", null)
                            view?.evaluateJavascript(webPlaybackResumeScript(savedResumePosition, autoSelectSpanish = isVideoApi), null)
                            if (isVideoApi) view?.evaluateJavascript("""(function(){
                              try {
                                var css='html,body{margin:0!important;padding:0!important;border:0!important;outline:0!important;box-shadow:none!important;background:#000!important} iframe,video,html:focus,body:focus,iframe:focus,video:focus{border:0!important;outline:0!important;box-shadow:none!important}';
                                var st=document.getElementById('xuper-videoapi-edge-cleanup');
                                if(!st){st=document.createElement('style');st.id='xuper-videoapi-edge-cleanup';(document.head||document.documentElement).appendChild(st);}st.textContent=css;
                              }catch(e){}
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
                    tag = url
                    if (hasCustomHub) {
                        addJavascriptInterface(object {
                            private var lastPersistedPosition = -1L
                            @JavascriptInterface
                            fun onTimeUpdate(current: Double, duration: Double) {
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    val safeCurrent = current.coerceAtLeast(0.0)
                                    val safeDuration = duration.coerceAtLeast(0.0)
                                    frameCurrentTime = safeCurrent
                                    frameDuration = safeDuration
                                    val positionToSave = if (safeDuration > 0 && safeCurrent >= safeDuration - 15.0) 0L else safeCurrent.toLong()
                                    if (positionToSave == 0L && lastPersistedPosition != 0L ||
                                        lastPersistedPosition < 0L || positionToSave >= lastPersistedPosition + 5L ||
                                        positionToSave <= lastPersistedPosition - 5L) {
                                        playbackPrefs.edit().putLong(positionPreferenceKey, positionToSave).apply()
                                        lastPersistedPosition = positionToSave
                                    }
                                }
                            }
                        }, "XuperBridge")
                    }
                    if (isFrameXtv) {
                        loadDataWithBaseURL("https://xuper.local/", frameXtvWrapper(url, savedResumePosition, frameSubtitleIndex, frameServerIndex), "text/html", "UTF-8", null)
                    } else if (runCatching { URI(url).host.equals("videoapi.la", true) }.getOrDefault(false)) {
                        // En VideoAPI se activan los controles/subtítulos documentados y se carga su página real,
                        // sin simular un origen ni crear una capa externa que no puede controlar el iframe.
                        loadUrl(videoApiEmbedUrl(url, savedResumePosition))
                    } else loadUrl(url)
                    requestFocus()
                }
                webViewRef = webView
                container.addView(webView)
                container
            },
            update = { container ->
                val webView = container.getChildAt(0) as? WebView
                if (webView != null && webView.tag != url) {
                    webView.tag = url
                    if (isFrameXtv) {
                        webView.loadDataWithBaseURL("https://xuper.local/", frameXtvWrapper(url, savedResumePosition, frameSubtitleIndex, frameServerIndex), "text/html", "UTF-8", null)
                    } else if (runCatching { URI(url).host.equals("videoapi.la", true) }.getOrDefault(false)) {
                        webView.loadUrl(videoApiEmbedUrl(url, playbackPrefs.getLong(positionPreferenceKey, savedResumePosition).coerceAtLeast(0L)))
                    } else webView.loadUrl(url)
                }
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
        if (showNextEpisodeCard && nextEpisode != null) {
            val secondsLeft = kotlin.math.ceil(nextEpisodeRemaining).toInt().coerceIn(0, 20)
            NextEpisodeOverlay(
                episode = nextEpisode,
                secondsLeft = secondsLeft,
                onPlayNow = {
                    if (!nextEpisodeTriggered) {
                        nextEpisodeTriggered = true
                        onPlayNextEpisode(nextEpisode.id)
                    }
                },
                onDismiss = { nextEpisodeDismissed = true },
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 22.dp, bottom = if (frameControlsVisible) 154.dp else 22.dp),
            )
        }
        if (hasCustomHub && frameControlsVisible && pageError == null) {
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp)
                    .background(Color(0xE6101820), RoundedCornerShape(14.dp)).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (isFrameXtv) {
                    val timeText = { value: Double ->
                        val total = value.toInt().coerceAtLeast(0)
                        "%02d:%02d".format(total / 60, total % 60)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${timeText(frameCurrentTime)} / ${timeText(frameDuration)}", color = Color.White, fontSize = 13.sp, modifier = Modifier.widthIn(min = 104.dp))
                        Box(Modifier.weight(1f).height(4.dp).background(Color(0xFF52616B), RoundedCornerShape(4.dp))) {
                            Box(Modifier.fillMaxWidth(if (frameDuration > 0) (frameCurrentTime / frameDuration).toFloat().coerceIn(0f, 1f) else 0f).height(4.dp).background(Color(0xFF65DCE8), RoundedCornerShape(4.dp)))
                        }
                    }
                } else {
                    Text("LATINO · controles", color = Color(0xFF65DCE8), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(modifier = Modifier.focusRequester(frameControlsFocus), onClick = {
                        if (isFrameXtv) {
                            webViewRef?.evaluateJavascript("window.xuperFrameCommand('${if (framePaused) "play" else "pause"}');", null)
                        } else {
                            webViewRef?.evaluateJavascript(videoApiCommandScript("playPause", frameSubtitleIndex, 0)) { result ->
                                if (result != "true") dispatchWebKey(webViewRef, AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                            }
                        }
                        framePaused = !framePaused
                        frameInteraction++
                    }) { Text(if (framePaused) "▶ Reproducir" else "Ⅱ Pausar") }
                    Button(onClick = {
                        if (isFrameXtv) webViewRef?.evaluateJavascript("window.xuperFrameCommand('seekBy',{seconds:-10});", null)
                        else webViewRef?.evaluateJavascript(videoApiCommandScript("seekBack", frameSubtitleIndex, 10)) { result -> if (result != "true") dispatchWebKey(webViewRef, AndroidKeyEvent.KEYCODE_DPAD_LEFT) }
                        frameInteraction++
                    }) { Text("↶ 10 s") }
                    Button(onClick = {
                        if (isFrameXtv) webViewRef?.evaluateJavascript("window.xuperFrameCommand('seekBy',{seconds:10});", null)
                        else webViewRef?.evaluateJavascript(videoApiCommandScript("seekForward", frameSubtitleIndex, 10)) { result -> if (result != "true") dispatchWebKey(webViewRef, AndroidKeyEvent.KEYCODE_DPAD_RIGHT) }
                        frameInteraction++
                    }) { Text("10 s ↷") }
                    Button(onClick = {
                        if (isFrameXtv) webViewRef?.evaluateJavascript(if (frameMuted) "window.xuperFrameCommand('setVolume',{volume:1});" else "window.xuperFrameCommand('mute');", null)
                        else webViewRef?.evaluateJavascript(videoApiCommandScript("mute", frameSubtitleIndex, 0)) { result -> if (result != "true") dispatchWebKey(webViewRef, AndroidKeyEvent.KEYCODE_M) }
                        frameMuted = !frameMuted
                        frameInteraction++
                    }) { Text(if (frameMuted) "🔊 Sonido" else "🔇 Silencio") }
                    if (isFrameXtv) {
                        Button(onClick = {
                            frameSubtitleIndex = (frameSubtitleIndex + 1) % 32
                            if (saveSubtitleForContent) playbackPrefs.edit().putInt(subtitlePreferenceKey, frameSubtitleIndex).apply()
                            webViewRef?.evaluateJavascript("window.xuperFrameCommand('setSubtitle',{index:$frameSubtitleIndex});", null)
                            frameInteraction++
                        }) { Text("CC · Pista ${frameSubtitleIndex + 1}") }
                        Button(onClick = {
                            // Cambia entre proveedores de extracción documentados por FrameXTV.
                            // El índice 0 representa Auto; 1..4 son los proveedores nombrados.
                            frameServerIndex = if (frameServerIndex >= 4) 1 else frameServerIndex + 1
                            frameSubtitleIndex = 0
                            saveSubtitleForContent = false
                            playbackPrefs.edit()
                                .apply {
                                    if (saveServerForContent) putInt(serverPreferenceKey, frameServerIndex)
                                    else remove(serverPreferenceKey)
                                    remove(subtitlePreferenceKey)
                                }
                                .apply()
                            webViewRef?.loadDataWithBaseURL(
                                "https://xuper.local/",
                                frameXtvWrapper(url, 0L, 0, frameServerIndex),
                                "text/html", "UTF-8", null
                            )
                            frameInteraction++
                        }) { Text("Proveedor · ${frameXtvProviderLabel(frameServerIndex)}") }
                        Button(onClick = {
                            saveServerForContent = !saveServerForContent
                            playbackPrefs.edit().apply {
                                putBoolean("web-save-server:$stableContentKey", saveServerForContent)
                                if (saveServerForContent) putInt(serverPreferenceKey, frameServerIndex)
                                else remove(serverPreferenceKey)
                            }.apply()
                            frameInteraction++
                        }) { Text(if (saveServerForContent) "✓ Guardar servidor" else "□ Guardar servidor") }
                        Button(onClick = {
                            saveSubtitleForContent = !saveSubtitleForContent
                            if (saveSubtitleForContent) playbackPrefs.edit().putInt(subtitlePreferenceKey, frameSubtitleIndex).apply()
                            else playbackPrefs.edit().remove(subtitlePreferenceKey).apply()
                            frameInteraction++
                        }) { Text(if (saveSubtitleForContent) "✓ Guardar pista" else "□ Guardar pista") }
                    }
                }
            }
        }
        if (progress < 100 && pageError == null) {
            Text("Cargando reproductor web… $progress%", Modifier.align(Alignment.TopCenter).padding(16.dp).background(Color(0xCC101820), RoundedCornerShape(8.dp)).padding(12.dp), color = Color.White, fontSize = 14.sp)
        }
        if (pageError != null) {
            Column(Modifier.align(Alignment.Center).fillMaxWidth(0.78f).background(Color(0xF014202B), RoundedCornerShape(14.dp)).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("No se pudo reproducir", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(pageError.orEmpty(), color = Color(0xFFD8E3EA), fontSize = 15.sp)
                Text("El sitio de reproducción no pudo cargar este título en el reproductor integrado. Revisá la conexión e intentá nuevamente.", color = Color(0xFFB5C6D2), fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { pageError = null; progress = 0; pageFinished = false; webViewRef?.reload() }) { Text("↻  Reintentar") }
                    Button(onClick = { webViewRef?.let { if (it.canGoBack()) it.goBack() } }) { Text("←  Volver") }
                }
            }
        }
        // Se quitó la banda permanente de ayuda: tapaba parte del contenido web.
    }
}
