package com.quilmes.xuper.ui.screens

import android.view.KeyEvent
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.MimeTypes
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

/** Reproductor de estilo streaming: overlay propio, auto-ocultado, pistas nativas y failover de fuentes. */
@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    name: String,
    streamUrl: String,
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
        mutableStateOf(alternatePlayers.indexOfFirst { it.streamUrl == streamUrl }.coerceAtLeast(0))
    }
    var position by remember(streamUrl) { mutableStateOf(0L) }
    var duration by remember(streamUrl) { mutableStateOf(0L) }
    var isPlaying by remember(streamUrl) { mutableStateOf(true) }
    var failedSources by remember(streamUrl) { mutableStateOf(0) }

    val exoPlayer = remember {
        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent("Mozilla/5.0 (Linux; Android TV) XuperQuilmesTV")
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
    }

    fun setUrl(url: String) {
        val cleanUrl = url.substringBefore('?').lowercase()
        val item = MediaItem.Builder().setUri(url).apply {
            when {
                cleanUrl.endsWith(".m3u8") -> setMimeType(MimeTypes.APPLICATION_M3U8)
                cleanUrl.endsWith(".mpd") -> setMimeType(MimeTypes.APPLICATION_MPD)
            }
        }.build()
        exoPlayer.setMediaItem(item)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
        error = null
        isPlaying = true
    }

    LaunchedEffect(exoPlayer, streamUrl) { setUrl(streamUrl) }
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
                    .firstOrNull { alternatePlayers.getOrNull(it)?.streamUrl?.isNotBlank() == true && alternatePlayers[it].streamUrl != currentUrl }
                if (nextIndex != null) {
                    sourceIndex = nextIndex
                    failedSources++
                    setUrl(alternatePlayers[nextIndex].streamUrl)
                    showControls = true
                } else {
                    error = "No se pudo reproducir esta fuente (${e.errorCodeName}). Probá cambiar de reproductor."
                    showControls = true
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener) }
    }

    DisposableEffect(exoPlayer) { onDispose { exoPlayer.release() } }

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
            onChangePlayer(alternatePlayers[nextIndex].id)
            setUrl(alternatePlayers[nextIndex].streamUrl)
            showControls = true
        } else {
            error = "No se encontraron más enlaces distintos para este contenido."
            showControls = true
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = {
                object : PlayerView(context) {
                    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                        if (event.action == KeyEvent.ACTION_DOWN) {
                            when (event.keyCode) {
                                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                                    showControls = !showControls
                                    return true
                                }
                                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                                    showControls = true
                                }
                                KeyEvent.KEYCODE_CHANNEL_UP -> {
                                    val index = channelQueue.indexOfFirst { it.id == currentTitleId }
                                    if (index >= 0 && channelQueue.isNotEmpty()) {
                                        onChangeChannel(channelQueue[(index + 1) % channelQueue.size].id); return true
                                    }
                                }
                                KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                                    val index = channelQueue.indexOfFirst { it.id == currentTitleId }
                                    if (index >= 0 && channelQueue.isNotEmpty()) {
                                        onChangeChannel(channelQueue[(index - 1 + channelQueue.size) % channelQueue.size].id); return true
                                    }
                                }
                                KeyEvent.KEYCODE_DPAD_LEFT -> { exoPlayer.seekBack(); showControls = true; return true }
                                KeyEvent.KEYCODE_DPAD_RIGHT -> { exoPlayer.seekForward(); showControls = true; return true }
                            }
                        }
                        return super.dispatchKeyEvent(event)
                    }
                }.apply {
                    player = exoPlayer
                    useController = false
                    keepScreenOn = true
                    isFocusable = true
                    isFocusableInTouchMode = true
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    setOnClickListener { showControls = !showControls }
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    post { requestFocus() }
                }
            },
            update = { it.player = exoPlayer },
        )

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
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play(); showControls = true }) {
                        Text(if (isPlaying) "Ⅱ  Pausar" else "▶  Reproducir")
                    }
                    Button(onClick = { exoPlayer.seekBack(); showControls = true }) { Text("↶ 10 s") }
                    Button(onClick = { exoPlayer.seekForward(); showControls = true }) { Text("10 s ↷") }
                    Button(onClick = { changeSource() }) { Text("⟳  Cambiar reproductor") }
                    Button(onClick = {
                        TrackSelectionDialogBuilder(context, "Audio y subtítulos", exoPlayer).build().show()
                        showControls = true
                    }) { Text("♫  Audio / subtítulos") }
                }
                if (error != null) {
                    Text(error!!, color = Color.White, fontSize = 15.sp, modifier = Modifier.background(Color(0xCC14202B), RoundedCornerShape(8.dp)).padding(12.dp))
                    Button(onClick = { setUrl(exoPlayer.currentMediaItem?.localConfiguration?.uri?.toString() ?: streamUrl) }) { Text("Reintentar") }
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
