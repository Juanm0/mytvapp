package com.quilmes.xuper.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.quilmes.xuper.data.AdultCatalogService
import com.quilmes.xuper.data.AdultCategory
import com.quilmes.xuper.data.AdultContentSettings
import com.quilmes.xuper.data.AdultPage
import com.quilmes.xuper.data.AdultVideo
import com.quilmes.xuper.ui.components.Button
import com.quilmes.xuper.ui.components.Card
import com.quilmes.xuper.ui.theme.XuperAccentBright
import com.quilmes.xuper.ui.theme.XuperLogoBackground
import com.quilmes.xuper.ui.theme.XuperSurfaceVariant
import com.quilmes.xuper.ui.theme.XuperTextPrimary
import com.quilmes.xuper.ui.theme.XuperTextSecondary
import kotlinx.coroutines.launch

/**
 * Estado del catálogo +18 mientras la app está abierta: así, al volver del reproductor, se conserva
 * la categoría, la búsqueda y las páginas ya cargadas. Vive solo en memoria y se borra al bloquear el perfil +18.
 */
private object AdultSession {
    var category: AdultCategory? = AdultCategory.POPULAR // null = resultados de búsqueda
    var query: String = ""
    var videos: List<AdultVideo> = emptyList()
    var page: Int = 0
    var hasNext: Boolean = false

    fun clear() {
        category = AdultCategory.POPULAR; query = ""; videos = emptyList(); page = 0; hasNext = false
    }
}

/**
 * Sección +18: catálogo dinámico (Populares, Recientes, Más vistos, Mejor valorados, Más largos), buscador y
 * paginación. Elegir una miniatura abre el reproductor nativo Media3 (yt-dlp + AdultNativePlayerScreen).
 * Solo se muestra con el perfil +18 desbloqueado (MainScreen).
 */
@Composable
fun AdultScreen(onPlayVideo: (viewKey: String, title: String) -> Unit) {
    val enabled by AdultContentSettings.enabledFlow.collectAsState()
    LaunchedEffect(enabled) { if (!enabled) AdultSession.clear() }

    val scope = rememberCoroutineScope()
    var category by remember { mutableStateOf(AdultSession.category) }
    var queryText by remember { mutableStateOf(AdultSession.query) }
    var videos by remember { mutableStateOf(AdultSession.videos) }
    var page by remember { mutableStateOf(AdultSession.page) }
    var hasNext by remember { mutableStateOf(AdultSession.hasNext) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // Cada carga lleva un número: si cambia la categoría mientras llega una respuesta vieja, se descarta.
    var requestId by remember { mutableStateOf(0) }

    fun save() {
        AdultSession.category = category; AdultSession.query = queryText
        AdultSession.videos = videos; AdultSession.page = page; AdultSession.hasNext = hasNext
    }

    fun load(reset: Boolean) {
        val myRequest = ++requestId
        val targetPage = if (reset) 1 else page + 1
        val cat = category
        val q = queryText
        loading = true
        error = null
        if (reset) { videos = emptyList(); hasNext = false; page = 0 }
        scope.launch {
            val result: Result<AdultPage> = runCatching {
                if (cat != null) AdultCatalogService.list(cat, targetPage) else AdultCatalogService.search(q, targetPage)
            }
            if (myRequest != requestId) return@launch
            result.onSuccess { data ->
                videos = if (reset) data.videos else (videos + data.videos).distinctBy { it.viewKey }
                page = targetPage
                hasNext = data.hasNext
                save()
            }.onFailure { e ->
                error = e.message?.take(140) ?: "No se pudo cargar el catálogo."
            }
            loading = false
        }
    }

    // Primera carga (o si todavía no hay nada guardado de una visita anterior).
    LaunchedEffect(Unit) { if (AdultSession.videos.isEmpty()) load(reset = true) }

    fun selectCategory(c: AdultCategory) { category = c; load(reset = true) }
    fun submitSearch() {
        if (queryText.isBlank()) return
        category = null
        load(reset = true)
    }

    Column(modifier = Modifier.fillMaxSize().padding(top = 28.dp)) {
        Text("+18", color = XuperTextPrimary, fontSize = 32.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 48.dp))
        // Categorías: ◀ ▶ las recorren, OK abre. El foco queda visible en cada botón.
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 48.dp, end = 48.dp, top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AdultCategory.values().forEach { c ->
                Button(onClick = { selectCategory(c) }) {
                    Text(
                        text = (if (category == c) "● " else "") + c.label,
                        color = if (category == c) XuperAccentBright else XuperTextPrimary,
                        fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
        // Buscador independiente.
        BasicTextField(
            value = queryText,
            onValueChange = { queryText = it },
            singleLine = true,
            textStyle = TextStyle(color = XuperTextPrimary, fontSize = 20.sp),
            cursorBrush = SolidColor(XuperAccentBright),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { submitSearch() }),
            modifier = Modifier.fillMaxWidth().padding(start = 48.dp, end = 48.dp, top = 12.dp),
            decorationBox = { inner ->
                Box(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .background(XuperSurfaceVariant).padding(horizontal = 18.dp, vertical = 12.dp)
                ) {
                    if (queryText.isEmpty()) Text("🔍  Buscar y apretar Buscar/Enter del teclado…", color = XuperTextSecondary, fontSize = 18.sp)
                    inner()
                }
            },
        )
        val status = when {
            category == null -> "Resultados de «$queryText»"
            else -> category!!.label
        }
        Text(
            text = status + if (page > 0) "  ·  página $page" else "",
            color = XuperTextSecondary, fontSize = 14.sp,
            modifier = Modifier.padding(start = 48.dp, top = 8.dp),
        )
        when {
            error != null && videos.isEmpty() -> Column(Modifier.padding(start = 48.dp, top = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("No se pudo cargar el catálogo: $error", color = XuperTextPrimary, fontSize = 17.sp)
                Button(onClick = { load(reset = true) }) { Text("REINTENTAR") }
            }
            loading && videos.isEmpty() -> Text("Cargando…", color = XuperTextSecondary, fontSize = 18.sp, modifier = Modifier.padding(start = 48.dp, top = 24.dp))
            videos.isEmpty() -> Text("Sin resultados.", color = XuperTextSecondary, fontSize = 18.sp, modifier = Modifier.padding(start = 48.dp, top = 24.dp))
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(230.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 14.dp, bottom = 48.dp),
            ) {
                items(videos, key = { it.viewKey }) { video ->
                    AdultVideoCard(video = video, onClick = { onPlayVideo(video.viewKey, video.title) })
                }
                if (hasNext || loading || error != null) {
                    item(key = "more", span = { GridItemSpan(maxLineSpan) }) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Button(onClick = { if (!loading) load(reset = false) }, enabled = !loading) {
                                Text(if (loading) "Cargando…" else "CARGAR MÁS (página ${page + 1})")
                            }
                            error?.let { Text(it, color = XuperTextSecondary, fontSize = 14.sp) }
                        }
                    }
                }
            }
        }
    }
}

/** Miniatura 16:9 con duración, título y vistas. Con el mando se agranda y muestra borde celeste. */
@Composable
private fun AdultVideoCard(video: AdultVideo, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        scale = CardDefaults.scale(focusedScale = 1.07f),
        border = CardDefaults.border(
            focusedBorder = Border(border = BorderStroke(4.dp, XuperAccentBright), shape = RoundedCornerShape(8.dp))
        ),
        shape = CardDefaults.shape(shape = RoundedCornerShape(8.dp)),
    ) {
        Column(Modifier.fillMaxWidth().background(XuperSurfaceVariant)) {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(XuperLogoBackground)) {
                if (video.thumbUrl.isNotBlank()) {
                    AsyncImage(
                        model = video.thumbUrl, contentDescription = null,
                        contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                    )
                }
                if (video.duration.isNotBlank()) {
                    Text(
                        text = video.duration, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp)
                            .background(Color(0xCC000000), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
                if (video.hd) {
                    Text(
                        text = "HD", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.TopStart).padding(6.dp)
                            .background(Color(0xCC000000), RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 2.dp),
                    )
                }
            }
            Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = video.title, color = Color.White, fontSize = 13.sp, lineHeight = 16.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                if (video.views.isNotBlank()) {
                    Text(text = "👁 ${video.views}", color = XuperTextSecondary, fontSize = 11.sp, maxLines = 1)
                }
            }
        }
    }
}
