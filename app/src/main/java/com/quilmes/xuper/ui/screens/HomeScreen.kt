package com.quilmes.xuper.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.quilmes.xuper.data.ContentRepository
import com.quilmes.xuper.data.Category
import com.quilmes.xuper.data.ContentKind
import com.quilmes.xuper.data.Section
import com.quilmes.xuper.data.Title
import com.quilmes.xuper.ui.components.ContentRow
import com.quilmes.xuper.ui.components.SkeletonRows
import com.quilmes.xuper.ui.theme.XuperAccentBright
import com.quilmes.xuper.ui.theme.XuperBackground
import com.quilmes.xuper.ui.theme.XuperLogoBackground
import com.quilmes.xuper.ui.theme.XuperSurfaceVariant
import com.quilmes.xuper.ui.theme.XuperTextPrimary
import com.quilmes.xuper.ui.theme.XuperTextSecondary

@Composable
fun HomeScreen(
    repository: ContentRepository,
    onPlay: (String) -> Unit,
) {
    val sections by repository.sections.collectAsState()
    val loading by repository.loading.collectAsState()

    // Inicio muestra solo contenido real y reproducible: canales de Argentina
    // (con Gran Hermano y Deportes separados) y las películas/series de las
    // listas M3U activas. El catálogo editorial de TMDB (tendencias, populares,
    // plataformas) no se muestra acá; se puede encontrar desde el buscador.
    //
    // `sections` se actualiza una vez por cada fuente M3U a medida que termina de
    // cargar (hasta 6 veces seguidas al abrir la app). Antes este cálculo corría
    // sincrónicamente dentro de `remember`, es decir en el hilo principal, y como
    // recorre TODAS las listas (canales + películas + series + Mickflix + VOD)
    // cada vez que `sections` cambia, la interfaz se congelaba varias veces
    // seguidas justo al abrir la app. Ahora se calcula en un hilo de fondo
    // (Dispatchers.Default) y solo se publica el resultado cuando termina, sin
    // bloquear la composición ni la navegación mientras tanto.
    var rows by remember { mutableStateOf<List<Category>>(emptyList()) }
    LaunchedEffect(sections) {
        val computed = withContext(Dispatchers.Default) { buildHomeRows(sections) }
        rows = computed
    }
    // El banner destacado ahora toma el primer título VOD reproducible de `rows`
    // (ya no depende de la fila "Tendencias" de TMDB, que se sacó de Inicio).
    val featured = remember(rows) {
        rows.firstOrNull { it.id.startsWith("vod-added-") }?.items?.firstOrNull()
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
                item { HeroBanner(title = f, onPlay = { onPlay(f.id) }) }
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
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(230.dp)
            .background(Brush.horizontalGradient(listOf(XuperSurfaceVariant, XuperBackground)))
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 48.dp)
                .widthIn(max = 520.dp)
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
            Text(
                text = title.description,
                color = XuperTextSecondary,
                fontSize = 16.sp,
                modifier = Modifier.padding(top = 6.dp, bottom = 16.dp),
            )
            Button(onClick = onPlay) {
                Text("▶  Reproducir")
            }
        }
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 64.dp)
                .size(width = 240.dp, height = 150.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(XuperLogoBackground)
                .padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = title.posterUrl,
                contentDescription = title.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private fun buildHomeRows(sections: List<Section>): List<Category> {
    val argentina = sections.firstOrNull { it.name.equals("Argentina", ignoreCase = true) }
        ?.categories.orEmpty().flatMap { it.items }.filter { it.isLive }
    val gh = argentina.filter { isGranHermano(it.name) }
    val sports = argentina.filter { isArgentinaSports(it.name) && !isGranHermano(it.name) }
    val mainChannels = argentina.filter { !isGranHermano(it.name) && !isArgentinaSports(it.name) }
    val vodSourceNames = setOf("vod general", "agrupado por contenido", "peliculas")
    val vodSources = sections.filter { normalizeCatalogLabel(it.name) in vodSourceNames }
    val seenUrls = HashSet<String>()
    val mergedVodRows = linkedMapOf<String, Pair<String, MutableList<Title>>>()
    vodSources.forEach { section ->
        section.categories.forEach { category ->
            val available = category.items.filter { !it.isLive && it.streamUrl.isNotBlank() && seenUrls.add(it.streamUrl) }
            if (available.isNotEmpty()) {
                val key = normalizeCatalogLabel(category.title)
                val row = mergedVodRows.getOrPut(key) { category.id to mutableListOf() }
                row.second.addAll(available)
            }
        }
    }
    val vodRows = mergedVodRows.map { (key, pair) ->
        val label = vodSources.asSequence().flatMap { it.categories.asSequence() }
            .firstOrNull { normalizeCatalogLabel(it.title) == key }?.title ?: "Películas y series"
        Category("vod-added-${pair.first}", label, pair.second.toList())
    }
    // El catálogo editorial de TMDB (tendencias, populares, plataformas) ya no se
    // muestra en Inicio: el usuario lo pidió así porque mezclaba fichas sin
    // reproducción disponible con el contenido real. Ese catálogo sigue existiendo
    // y se puede encontrar enteramente desde el buscador (ver registerCatalogTitles /
    // setCatalogCategories en M3uRepository).
    return buildList {
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

private fun yearForSort(title: Title): Int = title.year?.toIntOrNull()
    ?: Regex("\\b(?:19|20)\\d{2}\\b").find(title.name)?.value?.toIntOrNull()
    ?: 0

private fun normalizeCatalogLabel(value: String): String =
    java.text.Normalizer.normalize(value.lowercase(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "").trim()
