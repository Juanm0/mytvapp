package com.quilmes.xuper.ui.screens

import android.view.KeyEvent as AndroidKeyEvent
import android.view.ViewGroup
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
import kotlinx.coroutines.delay

/**
 * Reproductor de estilo streaming (overlay propio, se oculta solo, pistas nativas y failover
 * de fuentes). Los controles los pensamos como los de cualquier app grande (Disney+, Netflix,
 * etc.): no se puede copiar su código — es propietario — pero sí las funciones que importan:
 * flecha izq/der adelanta y atrasa SIEMPRE (no hace falta "entrar" a ningún botón primero),
 * con un indicador que confirma el salto, play/pause, audio/subtítulos, cambiar fuente y
 * episodio siguiente. CH+/CH- siguen moviendo canales de Argentina como antes.
 */
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
            .build()
    }

    fun setUrl(url: String, headers: Map<String, String> = emptyMap()) {
        dataSourceFactory.setDefaultRequestProperties(headers)
        val cleanUrl = url.substringBefore('?').lowercase()
        val item = MediaItem.Builder().setUri(url).apply {
            when {
                cleanUrl.endsWith(".m3u8") -> setMimeType(MimeTypes.APPLICATION_M3U8)
                cleanUrl.endsWith(".mpd") -> setMimeType(MimeTypes.APPLICATION_MPD)
            }
        }.build()
        attemptedUrls = attemptedUrls + url
        exoPlayer.setMediaItem(item)
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
            isPlaying = exoPlayer.isPlaying
            showNextEpisode = nextEpisode != null && duration > 0 && position >= duration - 60_000L
            delay(500)
        }
    }

    // Si una URL falla, intenta automáticamente la siguiente fuente disponible. El botón manual
    // permite recorrerlas incluso cuando el stream abre pero corresponde a otra película/episodio.
    DisposableEffect(exoPlayer, alternatePlayers, sourceIndex) {
        val listener = object : Player.Listener {
            override fun onPlayerError(e: PlaybackException) {
                val currentUrl = exoPlayer.currentMediaItem?.localConfiguration?.uri?.toString().orEmpty()
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
        Modifier.fillMaxSize().background(Color.Black).focusable()
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
                    "◀  10 s        10 s  ▶        ▲▼  cambiar botón",
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
