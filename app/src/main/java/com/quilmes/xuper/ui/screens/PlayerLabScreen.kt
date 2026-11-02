package com.quilmes.xuper.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import com.quilmes.xuper.data.PlayerLabEngine
import com.quilmes.xuper.data.PlayerLabException
import com.quilmes.xuper.data.PlayerLabMovie
import com.quilmes.xuper.data.PlayerLabPhase
import com.quilmes.xuper.data.PlayerLabRepository
import com.quilmes.xuper.data.PlayerLabVariant
import com.quilmes.xuper.ui.theme.XuperAccentBright
import com.quilmes.xuper.ui.theme.XuperBackground
import com.quilmes.xuper.ui.theme.XuperSurfaceVariant
import com.quilmes.xuper.ui.theme.XuperTextPrimary
import com.quilmes.xuper.ui.theme.XuperTextSecondary
import kotlinx.coroutines.delay

/**
 * PlayerLab (reproductor de pruebas, reemplaza al antiguo "Alternativo"):
 * ID de TMDB -> IMDb -> calidades de YTS -> elegir calidad -> torrent en streaming -> ExoPlayer.
 * Solo películas (el lab original no implementa series).
 */
@Composable
fun PlayerLabScreen(
    catalogId: String,
    titleName: String,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    val repository = remember { PlayerLabRepository(context.cacheDir) }
    var engine by remember { mutableStateOf(PlayerLabEngine(context)) }
    var movie by remember { mutableStateOf<PlayerLabMovie?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var attempt by remember { mutableStateOf(0) }
    var selected by remember { mutableStateOf<PlayerLabVariant?>(null) }
    val status by engine.status.collectAsState()

    // Al salir de la pantalla se corta la descarga y se borra lo bajado.
    DisposableEffect(engine) { onDispose { engine.stop() } }

    LaunchedEffect(catalogId, attempt) {
        loading = true
        loadError = null
        try {
            movie = repository.findMovie(catalogId)
        } catch (e: PlayerLabException) {
            movie = null
            loadError = e.message
        } catch (e: Exception) {
            movie = null
            loadError = "No se pudo completar la búsqueda: ${e.message ?: e.javaClass.simpleName}"
        }
        loading = false
    }

    fun backToQualities() {
        engine.stop()
        engine = PlayerLabEngine(context)
        selected = null
    }

    // Atrás durante la descarga o la reproducción vuelve a la lista de calidades.
    BackHandler(enabled = selected != null) { backToQualities() }

    val variant = selected
    val videoUrl = status.videoUrl
    when {
        variant != null && status.phase == PlayerLabPhase.READY && videoUrl != null -> {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                PlayerScreen(
                    name = "$titleName · ${variant.quality}",
                    streamUrl = videoUrl,
                    currentTitleId = "playerlab:$catalogId:${variant.quality}",
                    directOnly = true,
                    onExit = onExit,
                )
                // Indicador de prueba: velocidad, peers y avance de la descarga.
                Text(
                    "PlayerLab · ↓ ${formatSpeed(status.speedBytesPerSec)} · ${status.peers} peers · ${"%.1f".format(status.progressPercent)} %",
                    color = Color(0xB3FFFFFF),
                    fontSize = 11.sp,
                    modifier = Modifier.align(Alignment.TopEnd).padding(top = 10.dp, end = 14.dp)
                        .background(Color(0x66000000), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
        variant != null -> PreparingView(
            titleName = titleName,
            quality = variant.quality,
            phase = status.phase,
            message = status.message,
            prebuffer = status.prebufferPercent,
            speed = status.speedBytesPerSec,
            peers = status.peers,
            seeds = status.seeds,
            onBack = { backToQualities() },
        )
        loading -> CenterMessage("PlayerLab", "Consultando TMDB, recuperando IMDb y buscando calidades…", null, null)
        loadError != null -> CenterMessage(
            "PlayerLab no pudo continuar", loadError.orEmpty(),
            "↻ Reintentar" to { attempt += 1 },
            "← Volver" to onExit,
        )
        else -> QualityList(
            titleName = titleName,
            movie = movie,
            onSelect = { chosen ->
                selected = chosen
                engine.start(chosen)
            },
            onBack = onExit,
        )
    }
}

@Composable
private fun QualityList(
    titleName: String,
    movie: PlayerLabMovie?,
    onSelect: (PlayerLabVariant) -> Unit,
    onBack: () -> Unit,
) {
    val firstFocus = remember { FocusRequester() }
    val variants = movie?.variants.orEmpty()
    LaunchedEffect(variants.size) {
        delay(120)
        runCatching { firstFocus.requestFocus() }
    }
    Column(
        Modifier.fillMaxSize().background(XuperBackground).padding(horizontal = 56.dp, vertical = 36.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("PLAYERLAB · PRUEBAS", color = XuperAccentBright, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Text(titleName, color = XuperTextPrimary, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Text(
            "TMDB ${movie?.tmdbId.orEmpty()} · IMDb ${movie?.imdbId.orEmpty()}  ·  ${variants.size} calidades encontradas",
            color = XuperTextSecondary, fontSize = 14.sp,
        )
        Spacer(Modifier.height(8.dp))
        Text("Elegí calidad", color = XuperTextPrimary, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(variants) { index, v ->
                Row(
                    Modifier.fillMaxWidth().background(XuperSurfaceVariant, RoundedCornerShape(10.dp)).padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            v.quality + if (v.type.isNotBlank()) "  ·  ${v.type}" else "",
                            color = XuperTextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                        )
                        Text(
                            listOf(
                                v.size.ifBlank { "Tamaño n/d" },
                                v.videoCodec.ifBlank { "Códec n/d" },
                                v.audioChannels.ifBlank { "Audio n/d" },
                            ).joinToString("  ·  "),
                            color = XuperTextSecondary, fontSize = 13.sp,
                        )
                        Text("↑ ${v.seeds ?: "—"} seeds   ↓ ${v.peers ?: "—"} peers", color = XuperAccentBright, fontSize = 12.sp)
                    }
                    Button(
                        modifier = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier,
                        onClick = { onSelect(v) },
                    ) { Text("▶  Reproducir") }
                }
            }
        }
        Button(onClick = onBack) { Text("← Volver") }
    }
}

@Composable
private fun PreparingView(
    titleName: String,
    quality: String,
    phase: PlayerLabPhase,
    message: String,
    prebuffer: Int,
    speed: Long,
    peers: Int,
    seeds: Int,
    onBack: () -> Unit,
) {
    val backFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(150)
        runCatching { backFocus.requestFocus() }
    }
    Box(Modifier.fillMaxSize().background(XuperBackground).padding(48.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 720.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                if (phase == PlayerLabPhase.ERROR) "No se pudo reproducir" else "Preparando $titleName · $quality",
                color = XuperTextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold,
            )
            Text(message.ifBlank { "Iniciando…" }, color = XuperTextSecondary, fontSize = 16.sp)
            if (phase != PlayerLabPhase.ERROR) {
                Box(Modifier.fillMaxWidth().height(6.dp).background(Color(0xFF2A3647), RoundedCornerShape(4.dp))) {
                    Box(
                        Modifier.fillMaxWidth((prebuffer.coerceIn(0, 100)) / 100f).height(6.dp)
                            .background(XuperAccentBright, RoundedCornerShape(4.dp))
                    )
                }
                Text(
                    "↓ ${formatSpeed(speed)}  ·  $peers peers  ·  $seeds seeds",
                    color = XuperTextSecondary, fontSize = 13.sp,
                )
            }
            Button(modifier = Modifier.focusRequester(backFocus), onClick = onBack) { Text("← Elegir otra calidad") }
        }
    }
}

@Composable
private fun CenterMessage(
    title: String,
    detail: String,
    primary: Pair<String, () -> Unit>?,
    secondary: Pair<String, () -> Unit>?,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(primary != null) {
        if (primary != null) {
            delay(150)
            runCatching { focus.requestFocus() }
        }
    }
    Box(Modifier.fillMaxSize().background(XuperBackground).padding(48.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 720.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, color = XuperTextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text(detail, color = XuperTextSecondary, fontSize = 16.sp)
            if (primary != null || secondary != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (primary != null) Button(modifier = Modifier.focusRequester(focus), onClick = primary.second) { Text(primary.first) }
                    if (secondary != null) Button(onClick = secondary.second) { Text(secondary.first) }
                }
            }
        }
    }
}

private fun formatSpeed(bytesPerSec: Long): String =
    if (bytesPerSec >= 1_048_576L) "%.1f MB/s".format(bytesPerSec / 1_048_576.0)
    else "%d KB/s".format(bytesPerSec / 1024)
