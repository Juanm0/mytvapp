package com.quilmes.xuper.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.quilmes.xuper.data.ContentKind
import com.quilmes.xuper.data.Title
import com.quilmes.xuper.data.TmdbCatalogService
import com.quilmes.xuper.ui.components.Button
import com.quilmes.xuper.ui.theme.XuperAccent
import com.quilmes.xuper.ui.theme.XuperAccentBright
import com.quilmes.xuper.ui.theme.XuperBackground
import com.quilmes.xuper.ui.theme.XuperSurface
import com.quilmes.xuper.ui.theme.XuperTextPrimary
import com.quilmes.xuper.ui.theme.XuperTextSecondary
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

/**
 * Inicio estilo Netflix para INICIO (LabScreen en modo "home"): banner destacado grande y filas de TMDB
 * (Top 10, estrenos en cartelera, próximamente, series al aire, populares y mejor valoradas).
 * Tocar un título abre la ficha de LAB (CineSrc / Latino), igual que en el listado.
 */
private data class LabHomeRow(val id: String, val title: String, val items: List<Title>, val numbered: Boolean)

private data class LabHomeSpec(val id: String, val title: String, val endpoint: String, val kind: ContentKind?, val numbered: Boolean = false)

private val LAB_HOME_SPECS = listOf(
    LabHomeSpec("top10", "🔥  Top 10 de la semana", "trending/all/week", null, numbered = true),
    LabHomeSpec("now-playing", "🎬  Estrenos en cartelera", "movie/now_playing?region=AR", ContentKind.MOVIE),
    LabHomeSpec("upcoming", "🗓  Próximamente", "movie/upcoming?region=AR", ContentKind.MOVIE),
    LabHomeSpec("on-the-air", "📺  Series que están al aire", "tv/on_the_air", ContentKind.SERIES),
    LabHomeSpec("popular-movies", "Películas populares", "movie/popular", ContentKind.MOVIE),
    LabHomeSpec("popular-series", "Series populares", "tv/popular", ContentKind.SERIES),
    LabHomeSpec("top-movies", "⭐  Películas mejor valoradas", "movie/top_rated", ContentKind.MOVIE),
    LabHomeSpec("top-series", "⭐  Series mejor valoradas", "tv/top_rated", ContentKind.SERIES),
)

/** Filas ya cargadas (en memoria): al volver de una ficha o del reproductor no se vuelve a pedir todo a TMDB. */
private object LabHomeCache {
    var rows: List<LabHomeRow> = emptyList()
}

@Composable
internal fun LabHome(
    catalogService: TmdbCatalogService,
    onOpenTitle: (Title) -> Unit,
    modifier: Modifier = Modifier,
) {
    var rows by remember { mutableStateOf(LabHomeCache.rows) }
    var loading by remember { mutableStateOf(LabHomeCache.rows.isEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf(0) }

    LaunchedEffect(attempt) {
        if (rows.isNotEmpty() && attempt == 0) return@LaunchedEffect
        loading = true
        error = null
        val results = coroutineScope {
            LAB_HOME_SPECS.map { spec ->
                async { spec to runCatching { catalogService.loadLabPage(spec.endpoint, 1, spec.kind) } }
            }.awaitAll()
        }
        val built = results.mapNotNull { (spec, result) ->
            val items = result.getOrNull()?.items.orEmpty()
            if (items.isEmpty()) null
            else LabHomeRow(spec.id, spec.title, if (spec.numbered) items.take(10) else items, spec.numbered)
        }
        if (built.isEmpty()) {
            error = results.firstNotNullOfOrNull { it.second.exceptionOrNull()?.message }
                ?: "No se pudo cargar el catálogo de TMDB."
        } else {
            rows = built
            LabHomeCache.rows = built
        }
        loading = false
    }

    when {
        rows.isEmpty() && loading -> Text("Cargando catálogo…", color = XuperTextSecondary, fontSize = 18.sp, modifier = modifier.padding(top = 24.dp))
        rows.isEmpty() -> Column(modifier = modifier.padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(error ?: "No hay títulos para mostrar.", color = XuperAccentBright, fontSize = 17.sp)
            Button(onClick = { attempt++ }) { Text("REINTENTAR") }
        }
        else -> {
            val hero = remember(rows) {
                rows.firstOrNull()?.items?.let { list ->
                    list.firstOrNull { it.backdropUrl.isNotBlank() && it.description.isNotBlank() } ?: list.firstOrNull()
                }
            }
            val heroFocus = remember { FocusRequester() }
            LaunchedEffect(hero?.id) {
                if (hero != null) {
                    delay(200)
                    runCatching { heroFocus.requestFocus() }
                }
            }
            val heroHeight = (LocalConfiguration.current.screenHeightDp * 0.6f).coerceIn(200f, 340f).dp
            LazyColumn(
                modifier = modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 40.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                if (hero != null) {
                    item(key = "hero") {
                        LabHero(title = hero, focus = heroFocus, height = heroHeight, onOpen = { onOpenTitle(hero) })
                    }
                }
                items(rows, key = { it.id }) { row ->
                    Column {
                        Text(
                            row.title,
                            color = XuperTextPrimary,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(bottom = 10.dp),
                        )
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            contentPadding = PaddingValues(end = 24.dp),
                        ) {
                            itemsIndexed(row.items, key = { _, t -> t.id }) { index, t ->
                                if (row.numbered) {
                                    Row(verticalAlignment = Alignment.Bottom) {
                                        Text(
                                            "${index + 1}",
                                            color = XuperAccent,
                                            fontSize = if (index + 1 >= 10) 64.sp else 88.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            modifier = Modifier.padding(end = 4.dp, bottom = 40.dp),
                                        )
                                        Box(Modifier.width(140.dp)) { LabPosterCard(title = t, onClick = { onOpenTitle(t) }) }
                                    }
                                } else {
                                    Box(Modifier.width(150.dp)) { LabPosterCard(title = t, onClick = { onOpenTitle(t) }) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LabHero(title: Title, focus: FocusRequester, height: androidx.compose.ui.unit.Dp, onOpen: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(14.dp)).background(XuperSurface),
    ) {
        if (title.backdropUrl.isNotBlank()) {
            AsyncImage(
                model = title.backdropUrl,
                contentDescription = title.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(listOf(XuperBackground.copy(alpha = 0.92f), XuperBackground.copy(alpha = 0.55f), Color.Transparent))
            )
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color.Transparent, XuperBackground.copy(alpha = 0.75f)))
            )
        )
        Column(
            modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth(0.58f).padding(start = 26.dp, end = 12.dp, bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "🔥 EN TENDENCIA",
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.background(XuperAccent, RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
            )
            Text(title.name, color = XuperTextPrimary, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(
                    if (title.contentKind == ContentKind.SERIES) "Serie" else "Película",
                    title.year,
                    title.rating?.let { "★ $it" },
                ).joinToString("  ·  "),
                color = XuperAccentBright,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
            if (title.description.isNotBlank()) {
                Text(title.description, color = XuperTextPrimary, fontSize = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            Button(onClick = onOpen, modifier = Modifier.focusRequester(focus)) { Text("▶  VER AHORA") }
        }
    }
}
