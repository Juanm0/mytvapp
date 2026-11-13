package com.quilmes.xuper.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Text
import com.quilmes.xuper.data.AdultContentSettings
import com.quilmes.xuper.data.AdultVideoResolver
import com.quilmes.xuper.data.ResolvedAdultVideo
import com.quilmes.xuper.ui.components.Button
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Reproductor adulto nativo: yt-dlp (Chaquopy) resuelve el enlace y Media3 reproduce HLS/MP4.
 * Ante errores transitorios vuelve a resolver la URL firmada antes de reintentar.
 */
@Composable
fun AdultNativePlayerScreen(viewKey: String, title: String, onExit: () -> Unit) {
    val context = LocalContext.current
    val enabled by AdultContentSettings.enabledFlow.collectAsState()
    val scope = rememberCoroutineScope()

    var player by remember(viewKey) { mutableStateOf<ExoPlayer?>(null) }
    var status by remember(viewKey) { mutableStateOf("Buscando una fuente de reproducción…") }
    var errorText by remember(viewKey) { mutableStateOf<String?>(null) }
    val retryCounter = remember(viewKey) { mutableIntStateOf(0) }
    var manualRetry by remember(viewKey) { mutableIntStateOf(0) }

    BackHandler { onExit() }
    LaunchedEffect(enabled) { if (!enabled) onExit() }

    fun buildPlayer(resolved: ResolvedAdultVideo): ExoPlayer {
        val requestHeaders = resolved.headers.toMutableMap()
        if (requestHeaders.keys.none { it.equals("User-Agent", true) }) {
            requestHeaders["User-Agent"] =
                "Mozilla/5.0 (Linux; Android 11; Android TV) AppleWebKit/537.36 Chrome/120.0 Safari/537.36"
        }
        val httpFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(20_000)
            .setReadTimeoutMs(30_000)
            .setDefaultRequestProperties(requestHeaders)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(12_000, 50_000, 2_500, 5_000)
            .build()
        val created = ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpFactory))
            .setLoadControl(loadControl)
            .build()
        val mediaItem = MediaItem.Builder()
            .setUri(resolved.url)
            .setMimeType(if (resolved.type == "hls") MimeTypes.APPLICATION_M3U8 else MimeTypes.VIDEO_MP4)
            .build()
        created.setMediaItem(mediaItem)
        created.prepare()
        created.playWhenReady = true
        return created
    }

    LaunchedEffect(viewKey, manualRetry) {
        player = null
        errorText = null
        retryCounter.intValue = 0
        var lastError: Throwable? = null
        for (attempt in 0..2) {
            status = when (attempt) {
                0 -> "Resolviendo video…"
                else -> "El enlace tardó. Reintentando (${attempt}/2)…"
            }
            if (attempt > 0) delay(1_500L * attempt)
            try {
                val resolved = AdultVideoResolver.resolve(context, viewKey)
                if (!AdultContentSettings.isEnabled()) return@LaunchedEffect
                status = "Iniciando reproducción · ${resolved.quality}"
                player = buildPlayer(resolved)
                lastError = null
                break
            } catch (t: Throwable) {
                lastError = t
            }
        }
        if (player == null) {
            errorText = lastError?.message ?: "No se pudo resolver el video."
            status = ""
        }
    }

    DisposableEffect(player) {
        val active = player
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                if (retryCounter.intValue >= 2 || !AdultContentSettings.isEnabled()) {
                    errorText = "No se pudo reproducir después de varios intentos: ${error.errorCodeName}"
                    status = ""
                    return
                }
                retryCounter.intValue += 1
                val currentAttempt = retryCounter.intValue
                scope.launch {
                    status = "Conexión interrumpida. Renovando el enlace (${currentAttempt}/2)…"
                    errorText = null
                    var lastFailure: Throwable? = null
                    for (round in currentAttempt..2) {
                        delay(1_500L * round)
                        try {
                            val fresh = AdultVideoResolver.resolve(context, viewKey)
                            if (!AdultContentSettings.isEnabled()) return@launch
                            player = buildPlayer(fresh)
                            status = "Reintentando reproducción…"
                            lastFailure = null
                            break
                        } catch (failure: Throwable) {
                            lastFailure = failure
                        }
                    }
                    if (lastFailure != null) {
                        errorText = lastFailure?.message ?: "No se pudo renovar el enlace."
                        status = ""
                    }
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    status = ""
                    errorText = null
                } else if (playbackState == Player.STATE_BUFFERING && player != null) {
                    status = "Cargando video…"
                }
            }
        }
        active?.addListener(listener)
        onDispose {
            active?.removeListener(listener)
            active?.release()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        if (player != null) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { viewContext ->
                    PlayerView(viewContext).apply {
                        useController = true
                        controllerShowTimeoutMs = 5_000
                        setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
                        keepScreenOn = true
                        isFocusable = true
                        isFocusableInTouchMode = true
                    }
                },
                update = { view ->
                    if (view.player !== player) view.player = player
                },
            )
        }
        if (status.isNotBlank()) {
            Text(
                status,
                color = Color.White,
                fontSize = 18.sp,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 24.dp),
            )
        }
        errorText?.let { message ->
            Column(
                modifier = Modifier.align(Alignment.Center).background(Color(0xE6101722)).padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(title, color = Color.White, fontSize = 18.sp)
                Text(message, color = Color.White, fontSize = 15.sp)
                Button(onClick = { manualRetry += 1 }) { Text("REINTENTAR") }
                Button(onClick = onExit) { Text("VOLVER AL CATÁLOGO") }
            }
        }
    }
}
