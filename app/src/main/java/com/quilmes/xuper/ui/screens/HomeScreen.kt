package com.quilmes.xuper.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.quilmes.xuper.data.Category
import com.quilmes.xuper.data.ContentKind
import com.quilmes.xuper.data.ContentRepository
import com.quilmes.xuper.data.Section
import com.quilmes.xuper.data.Title
import com.quilmes.xuper.ui.components.ContentRow
import com.quilmes.xuper.ui.components.SkeletonRows
import com.quilmes.xuper.ui.theme.XuperAccentBright
import com.quilmes.xuper.ui.theme.XuperBackground
import com.quilmes.xuper.ui.theme.XuperSurfaceVariant
import com.quilmes.xuper.ui.theme.XuperTextPrimary
import com.quilmes.xuper.ui.theme.XuperTextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// El inicio prioriza el catálogo visual de TMDB, como una app de streaming. Las fichas
// no prometen disponibilidad: al abrir una, la app empieza a buscar fuentes IPTV bajo demanda.
private const val HOME_VOD_ITEMS = 40

@Composable
fun HomeScreen(
    repository: ContentRepository,
    onPlay: (String) -> Unit,
) {
    val sections by repository.sections.collectAsState()
    val loading by repository.loading.collectAsState()

    // Inicio = un poco de todo: canales de Argentina (con Gran Hermano y Deportes aparte),
    // las primeras filas de películas/series de las listas M3U, y unas pocas filas de TMDB
    // (tendencias y populares). El catálogo COMPLETO de TMDB (plataformas, etc.) se encuentra
    // desde el buscador.
    //
    // El cálculo corre en un hilo de fondo para no bloquear la composición.
    var rows by remember { mutableStateOf<List<Category>>(emptyList()) }
    LaunchedEffect(sections) {
        val computed = withContext(Dispatchers.Default) { buildHomeRows(sections) }
        rows = computed
    }
    // Banner destacado: SIEMPRE un título que realmente se pueda reproducir (streamUrl no
    // vacío). Antes podía caer una ficha de TMDB sin video — era literalmente lo primero que
    // el usuario veía y tocaba, y no andaba.
    val featured = remember(rows) {
        rows.firstOrNull { it.id.startsWith("home-tmdb-") }
            ?.items?.firstOrNull { it.backdropUrl.isNotBlank() }
            ?: rows.firstOrNull { it.id.startsWith("home-vod-") }
                ?.items?.firstOrNull { it.backdropUrl.isNotBlank() }
            ?: rows.firstOrNull { it.id.startsWith("home-tmdb-") }?.items?.firstOrNull()
            ?: rows.firstOrNull { it.id.startsWith("home-vod-") }?.items?.firstOrNull()
            ?: rows.firstOrNull { it.id == "home-argentina" }?.items?.firstOrNull()
    }

    if (rows.isEmpty()) {
        if (loading) {
            SkeletonRows()
        } else {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "Todavía no hay contenido en el catálogo. Revisá la conexión o las fuentes configuradas.",
                    color = XuperTextPrimary,
                    fontSize = 20.sp,
                )
            }
        }
    } else {
        LazyColumn(contentPadding = PaddingValues(bottom = 48.dp)) {
            featured?.let { f ->
                item(key = "hero") { HeroBanner(title = f, onPlay = { onPlay(f.id) }) }
            }
            items(rows, key = { it.id }) { category ->
                ContentRow(
                    category = category,
                    onItemClick = onPlay,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun HeroBanner(title: Title, onPlay: () -> Unit) {
    val hasBackdrop = title.backdropUrl.isNotBlank()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(260.dp)
            .background(Brush.horizontalGradient(listOf(XuperSurfaceVariant, XuperBackground)))
    ) {
        if (hasBackdrop) {
            AsyncImage(
                model = title.backdropUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            // Degradados para que el texto se lea sobre cualquier imagen
            Box(
                modifier = Modifier.fillMaxSize().background(
                    Brush.horizontalGradient(listOf(XuperBackground, XuperBackground.copy(alpha = 0.7f), Color.Transparent))
                )
            )
            Box(
                modifier = Modifier.fillMaxSize().background(
                    Brush.verticalGradient(listOf(Color.Transparent, XuperBackground))
                )
            )
        }
        Column(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 48.dp)
                .widthIn(max = 560.dp)
        ) {
            Text(
                text = "DESTACADO",
                color = XuperAccentBright,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = title.name,
                color = XuperTextPrimary,
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
            val meta = listOfNotNull(title.rating?.let { "★ $it" }, title.year).joinToString("  ·  ")
            if (meta.isNotBlank()) {
                Text(text = meta, color = XuperAccentBright, fontSize = 16.sp, modifier = Modifier.padding(top = 4.dp))
            }
            Text(
                text = title.description,
                color = XuperTextSecondary,
                fontSize = 16.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp, bottom = 16.dp),
            )
            Button(onClick = onPlay) {
                val playable = title.streamUrl.isNotBlank() || title.contentKind == ContentKind.LIVE
                Text(if (playable) "▶  Reproducir" else "Ver ficha")
            }
        }
    }
}

private fun buildHomeRows(sections: List<Section>): List<Category> {
    // --- Canales argentinos (en vivo) ---
    val argentina = sections.firstOrNull { it.name.equals("Argentina", ignoreCase = true) }
        ?.categories.orEmpty().flatMap { it.items }.filter { it.isLive }
    val gh = argentina.filter { isGranHermano(it.name) }
    val sports = argentina.filter { isArgentinaSports(it.name) && !isGranHermano(it.name) }
    val mainChannels = argentina.filter { !isGranHermano(it.name) && !isArgentinaSports(it.name) }

    // --- Películas / series / VOD de las listas M3U: las primeras `homeRows` filas de cada fuente ---
    val seenUrls = HashSet<String>()
    val vodRows = sections
        .filter { it.id != "tmdb-catalog" && it.homeRows > 0 && !it.name.startsWith("Argentina", ignoreCase = true) }
        .flatMap { section ->
            section.categories.take(section.homeRows).mapNotNull { category ->
                val available = category.items
                    .filter { !it.isLive && it.streamUrl.isNotBlank() && seenUrls.add(it.streamUrl) }
                    .take(HOME_VOD_ITEMS)
                if (available.isEmpty()) null
                else Category("home-vod-${category.id}", "${section.name} · ${category.title}", available)
            }
        }

    val tmdbRows = sections.firstOrNull { it.id == "tmdb-catalog" }?.categories.orEmpty()
        .filter { it.items.isNotEmpty() }
        .map { category -> Category("home-tmdb-${category.id}", category.title, category.items) }

    return buildList {
        addAll(tmdbRows)
        if (mainChannels.isNotEmpty()) add(Category("home-argentina", "ARGENTINA · CANALES EN VIVO", mainChannels))
        if (gh.isNotEmpty()) add(Category("home-gran-hermano", "GRAN HERMANO · EN VIVO", gh))
        if (sports.isNotEmpty()) add(Category("home-deportes-argentina", "DEPORTES ARGENTINA", sports))
        addAll(vodRows)
    }
}

private fun isGranHermano(name: String): Boolean = name.contains("gran hermano", ignoreCase = true) || name.trim().equals("GH", ignoreCase = true)

private fun isArgentinaSports(name: String): Boolean = listOf(
    "directv sports", "espn", "fox sports", "tyc sports", "tnt sports", "deportes argentina"
).any { name.contains(it, ignoreCase = true) }
