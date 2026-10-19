package com.quilmes.xuper.ui.screens

import android.view.ViewGroup
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Text
import com.quilmes.xuper.data.Title
import kotlinx.coroutines.delay

/**
 * Reproductor de pantalla completa con controles persistentes (pausa, ±10 s y
 * barra de progreso). Los controles no se ocultan automáticamente.
 * En canales en vivo sin buffer de retroceso la barra aparece bloqueada: es normal.
 */
@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    name: String,
    streamUrl: String,
    channelQueue: List<Title> = emptyList(),
    currentTitleId: String = "",
    onChangeChannel: (String) -> Unit = {},
    onPlaybackError: () -> Unit = {},
) {
    val context = LocalContext.current
    var error by remember { mutableStateOf<String?>(null) }
    var showName by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        delay(5000)
        showName = false
    }

    val exoPlayer = remember {
        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent("Mozilla/5.0 (Linux; Android TV) XuperQuilmesTV")
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
            .apply {
                setMediaItem(MediaItem.fromUri(streamUrl))
                prepare()
                playWhenReady = true
            }
    }

    DisposableEffect(Unit) {
        val listener = object : Player.Listener {
            override fun onPlayerError(e: PlaybackException) {
                error = "Este contenido no está disponible (${e.errorCodeName}). Se va a ocultar del catálogo."
                onPlaybackError()
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = {
                object : PlayerView(context) {
                    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                        if (event.action == KeyEvent.ACTION_DOWN) {
                            when (event.keyCode) {
                                KeyEvent.KEYCODE_DPAD_UP -> {
                                    // Esta tecla siempre despeja: nunca debe volver a mostrar el overlay.
                                    showName = false
                                    hideController()
                                    return true
                                }
                                KeyEvent.KEYCODE_CHANNEL_UP -> {
                                    val index = channelQueue.indexOfFirst { it.id == currentTitleId }
                                    if (index >= 0 && channelQueue.isNotEmpty()) {
                                        onChangeChannel(channelQueue[(index + 1) % channelQueue.size].id)
                                        return true
                                    }
                                }
                                KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                                    val index = channelQueue.indexOfFirst { it.id == currentTitleId }
                                    if (index >= 0 && channelQueue.isNotEmpty()) {
                                        onChangeChannel(channelQueue[(index - 1 + channelQueue.size) % channelQueue.size].id)
                                        return true
                                    }
                                }
                            }
                        }
                        return super.dispatchKeyEvent(event)
                    }
                }.apply {
                    player = exoPlayer
                    useController = true
                    // Flecha arriba despeja la pantalla ocultando los controles. Las teclas
                    // dedicadas CH+/CH- hacen zapping dentro de la cola argentina.
                    controllerShowTimeoutMs = 0
                    controllerHideOnTouch = false
                    keepScreenOn = true
                    isFocusable = true
                    isFocusableInTouchMode = true
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    // Foco al reproductor para que el control remoto maneje los controles
                    post { requestFocus() }
                }
            },
        )

        if (showName) {
            Text(
                text = "$name   ·   OK: controles · CH+/CH-: cambiar canal",
                color = Color.White,
                fontSize = 18.sp,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(24.dp)
                    .background(Color(0xAA000000))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        error?.let {
            Text(
                text = it,
                color = Color.White,
                fontSize = 20.sp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(32.dp)
                    .background(Color(0xCC000000))
                    .padding(16.dp),
            )
        }
    }
}
