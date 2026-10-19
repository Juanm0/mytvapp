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

// Inicio prioriza el catálogo editorial tipo streaming (tendencias y catálogos por plataforma)
// que llega desde TMDB. IPTV en vivo y canales argentinos viven exclusivamente en ARGENTINA.
// Los M3U antiguos de dominio público no se mezclan en Inicio para evitar pósters viejos o
// títulos que parezcan una oferta principal sin una fuente de reproducción válida.
private const val HOME_VOD_ITEMS = 40

@Composable
fun HomeScreen(
    repository: ContentRepository,
    onPlay: (String) -> Unit,
) {
    val sections by repository.sections.collectAsState()
    val loading by repository.loading.collectAsState()

    // Inicio es el escaparate de películas y series, como una app de streaming. IPTV argentino
    // está únicamente en la sección ARGENTINA de la barra lateral.
    var rows by remember { mutableStateOf<List<Category>>(emptyList()) }
    LaunchedEffect(sections) {
        val computed = withContext(Dispatchers.Default) { buildHomeRows(sections) }
        rows = computed
    }
    // Banner editorial: abre la ficha del título destacado. Si no hay un stream directo,
    // el botón dice "Ver detalles" en vez de prometer una reproducción que no existe.
    val featured = remember(rows) {
        rows.firstOrNull { it.id.startsWith("tmdb-") }
            ?.items?.firstOrNull { it.backdropUrl.isNotBlank() }
            ?: rows.firstOrNull()?.items?.firstOrNull()
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
                Text(if (playable) "▶  Reproducir" else "Ver detalles")
            }
        }
    }
}

private fun buildHomeRows(sections: List<Section>): List<Category> {
    // Las filas TMDB contienen novedades, tendencias y catálogos de plataformas en Argentina.
    // Son metadatos/editorial; al abrir una ficha se muestran las fuentes web guardadas para ese
    // contenido. Nunca fingimos que una ficha TMDB por sí sola tenga un vídeo reproducible.
    val tmdb = sections.firstOrNull { it.id == "tmdb-catalog" }?.categories.orEmpty()
    val preferredOrder = listOf(
        "Tendencias · Películas", "Tendencias · Series", "Películas populares", "Series populares",
        "Netflix · Series", "Netflix · Películas", "Prime Video · Series", "Prime Video · Películas",
        "Disney+ · Series", "Disney+ · Películas", "Max · Series", "Max · Películas",
        "Apple TV+ · Series", "Apple TV+ · Películas", "Paramount+ · Series", "Paramount+ · Películas",
        "Crunchyroll · Series", "Crunchyroll · Películas", "MUBI · Películas", "MUBI · Series"
    )
    val byTitle = tmdb.associateBy { it.title }
    return preferredOrder.mapNotNull { name ->
        byTitle[name]?.takeIf { it.items.isNotEmpty() }?.let { category ->
            category.copy(items = category.items.take(HOME_VOD_ITEMS))
        }
    }.ifEmpty {
        tmdb.filter { it.items.isNotEmpty() }.take(12).map { it.copy(items = it.items.take(HOME_VOD_ITEMS)) }
    }
}

