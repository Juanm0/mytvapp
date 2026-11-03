package com.quilmes.xuper.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.quilmes.xuper.data.ContentKind
import com.quilmes.xuper.data.Title
import com.quilmes.xuper.data.TmdbCatalogService
import com.quilmes.xuper.data.TmdbEpisode
import com.quilmes.xuper.data.TmdbSeason
import com.quilmes.xuper.ui.theme.XuperAccent
import com.quilmes.xuper.ui.theme.XuperAccentBright
import com.quilmes.xuper.ui.theme.XuperBackground
import com.quilmes.xuper.ui.theme.XuperSurfaceVariant
import com.quilmes.xuper.ui.theme.XuperTextPrimary
import com.quilmes.xuper.ui.theme.XuperTextSecondary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.URLEncoder

/** Una pestaña del catálogo LAB: ruta de TMDB y, si el listado es de un solo tipo, cuál es. */
private data class LabTab(val id: String, val label: String, val heading: String, val endpoint: String, val kind: ContentKind?)

private val LAB_TABS = listOf(
    LabTab("trending", "TENDENCIAS", "Tendencias de la semana", "trending/all/week", null),
    LabTab("movies", "PELÍCULAS", "Películas populares", "movie/popular", ContentKind.MOVIE),
    LabTab("series", "SERIES", "Series populares", "tv/popular", ContentKind.SERIES),
    LabTab("top-movies", "TOP PELÍCULAS", "Películas mejor valoradas", "movie/top_rated", ContentKind.MOVIE),
    LabTab("top-series", "TOP SERIES", "Series mejor valoradas", "tv/top_rated", ContentKind.SERIES),
    LabTab("now-playing", "EN CARTELERA", "Películas en cartelera", "movie/now_playing", ContentKind.MOVIE),
    LabTab("on-the-air", "AL AIRE", "Series que se están emitiendo", "tv/on_the_air", ContentKind.SERIES),
)

/**
 * Estado de LAB en memoria. Al reproducir, la navegación cierra esta pantalla; con esto, al volver desde el
 * reproductor se reencuentra la misma serie, la misma temporada y el mismo listado (sin recargar todo).
 */
private object LabSession {
    var tabId: String = "trending"
    var query: String = ""
    var items: List<Title> = emptyList()
    var page: Int = 0
    var totalPages: Int = 0
    var selected: Title? = null
    var season: Int? = null
}

/**
 * Categoría LAB: catálogo completo de TMDB (películas y series) que se reproduce con CineSrc.
 * Es independiente del resto de Xuper (igual que ANIME): no usa el repositorio M3U ni la ficha normal.
 * [onPlay] recibe el nombre a mostrar y el ID de catálogo (`tmdb-movie-<id>` o `tmdb-tv-<id>|s<T>|e<E>`).
 */
@Composable
fun LabScreen(
    catalogService: TmdbCatalogService,
    onPlay: (title: String, catalogId: String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var tabId by remember { mutableStateOf(LabSession.tabId) }
    var searchText by remember { mutableStateOf(LabSession.query) }
    var activeQuery by remember { mutableStateOf(LabSession.query) }
    var items by remember { mutableStateOf(LabSession.items) }
    var page by remember { mutableStateOf(LabSession.page) }
    var totalPages by remember { mutableStateOf(LabSession.totalPages) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf(LabSession.selected) }

    fun saveSession() {
        LabSession.tabId = tabId
        LabSession.query = activeQuery
        LabSession.items = items
        LabSession.page = page
        LabSession.totalPages = totalPages
        LabSession.selected = selected
    }

    fun load(newTab: String, newQuery: String, reset: Boolean) {
        if (loading) return
        val q = newQuery.trim()
        val tab = LAB_TABS.firstOrNull { it.id == newTab } ?: LAB_TABS[0]
        val endpoint = if (q.isNotEmpty()) "search/multi?query=${URLEncoder.encode(q, "UTF-8")}" else tab.endpoint
        val kind = if (q.isNotEmpty()) null else tab.kind
        val targetPage = if (reset) 1 else page + 1
        scope.launch {
            loading = true
            error = null
            try {
                val result = catalogService.loadLabPage(endpoint, targetPage, kind)
                tabId = tab.id
                activeQuery = q
                items = if (reset) result.items else (items + result.items).distinctBy { it.id }
                page = result.page
                totalPages = result.totalPages
                if (items.isEmpty()) {
                    error = if (q.isNotEmpty()) "No encontramos resultados para “$q”." else "No hay títulos para mostrar."
                }
            } catch (e: Exception) {
                error = e.message ?: "No se pudo cargar el catálogo de TMDB."
            } finally {
                loading = false
                saveSession()
            }
        }
    }

    fun openTitle(title: Title) {
        selected = title
        LabSession.selected = title
        LabSession.season = null
    }

    fun closeTitle() {
        selected = null
        LabSession.selected = null
        LabSession.season = null
    }

    LaunchedEffect(Unit) {
        if (items.isEmpty()) load(tabId, activeQuery, true)
    }

    // Atrás: de la ficha al listado; de una búsqueda al listado normal; del listado, MainScreen vuelve a Inicio.
    BackHandler(enabled = selected != null || activeQuery.isNotEmpty()) {
        if (selected != null) {
            closeTitle()
        } else {
            searchText = ""
            load(tabId, "", true)
        }
    }

    val current = selected
    if (current != null) {
        LabDetail(
            title = current,
            catalogService = catalogService,
            onBack = { closeTitle() },
            onPlay = onPlay,
        )
        return
    }

    val heading = if (activeQuery.isNotEmpty()) {
        "Resultados para “$activeQuery”"
    } else {
        (LAB_TABS.firstOrNull { it.id == tabId } ?: LAB_TABS[0]).heading
    }

    Column(
        modifier = Modifier.fillMaxSize().background(XuperBackground)
            .padding(start = 44.dp, end = 40.dp, top = 28.dp, bottom = 22.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("LAB", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = XuperTextPrimary)
            Text("TMDB + CineSrc · Películas y series", fontSize = 15.sp, color = XuperTextSecondary)
        }
        Spacer(Modifier.height(14.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BasicTextField(
                value = searchText,
                onValueChange = { searchText = it },
                singleLine = true,
                textStyle = TextStyle(color = XuperTextPrimary, fontSize = 19.sp),
                cursorBrush = SolidColor(XuperAccentBright),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { load(tabId, searchText, true) }),
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                    .background(XuperSurfaceVariant).padding(horizontal = 18.dp, vertical = 14.dp),
                decorationBox = { inner ->
                    Box {
                        if (searchText.isBlank()) Text("Buscar películas y series…", color = XuperTextSecondary, fontSize = 19.sp)
                        inner()
                    }
                },
            )
            Button(onClick = { load(tabId, searchText, true) }) { Text("BUSCAR") }
            if (activeQuery.isNotEmpty()) {
                Button(onClick = {
                    searchText = ""
                    load(tabId, "", true)
                }) { Text("LIMPIAR") }
            }
        }
        Spacer(Modifier.height(10.dp))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(vertical = 6.dp),
        ) {
            items(LAB_TABS, key = { it.id }) { tab ->
                val active = activeQuery.isEmpty() && tab.id == tabId
                Button(onClick = {
                    searchText = ""
                    load(tab.id, "", true)
                }) { Text(if (active) "● ${tab.label}" else tab.label) }
            }
        }
        Text(
            heading,
            color = XuperTextPrimary,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 12.dp, bottom = 12.dp),
        )
        when {
            loading && items.isEmpty() -> LabStatusText("Cargando catálogo…")
            error != null && items.isEmpty() -> LabStatusText(error.orEmpty())
            items.isEmpty() -> LabStatusText("No hay títulos para mostrar.")
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 154.dp),
                contentPadding = PaddingValues(bottom = 30.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                gridItems(items, key = { it.id }) { title ->
                    LabPosterCard(title = title, onClick = { openTitle(title) })
                }
                if (page < totalPages) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Button(onClick = { load(tabId, activeQuery, false) }) {
                            Text(if (loading) "CARGANDO…" else "CARGAR MÁS")
                        }
                    }
                }
            }
        }
        if (error != null && items.isNotEmpty()) {
            Text(error.orEmpty(), color = XuperAccentBright, fontSize = 16.sp, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

/** Ficha de una película o serie de LAB. En series: temporadas y episodios; cada uno abre CineSrc. */
@Composable
private fun LabDetail(
    title: Title,
    catalogService: TmdbCatalogService,
    onBack: () -> Unit,
    onPlay: (title: String, catalogId: String) -> Unit,
) {
    val isSeries = title.contentKind == ContentKind.SERIES
    var seasons by remember(title.id) { mutableStateOf<List<TmdbSeason>>(emptyList()) }
    var episodes by remember(title.id) { mutableStateOf<List<TmdbEpisode>>(emptyList()) }
    var selectedSeason by remember(title.id) { mutableStateOf<Int?>(LabSession.season) }
    var loadingSeasons by remember(title.id) { mutableStateOf(isSeries) }
    var loadingEpisodes by remember(title.id) { mutableStateOf(false) }
    val firstFocus = remember(title.id) { FocusRequester() }

    LaunchedEffect(title.id) {
        if (!isSeries) return@LaunchedEffect
        loadingSeasons = true
        val loaded = catalogService.loadSeasons(title.id)
        // La temporada 0 son los especiales: solo se muestra si no hay otra.
        val usable = loaded.filter { it.number > 0 }.ifEmpty { loaded }.sortedBy { it.number }
        seasons = usable
        if (selectedSeason == null || usable.none { it.number == selectedSeason }) {
            selectedSeason = usable.firstOrNull()?.number
        }
        loadingSeasons = false
    }

    LaunchedEffect(title.id, selectedSeason) {
        val season = selectedSeason
        if (!isSeries || season == null) {
            episodes = emptyList()
            return@LaunchedEffect
        }
        LabSession.season = season
        loadingEpisodes = true
        episodes = catalogService.loadEpisodes(title.id, season).sortedBy { it.number }
        loadingEpisodes = false
    }

    // Foco inicial: en películas, el botón Reproducir; en series, el primero de la ficha (VOLVER).
    LaunchedEffect(title.id) {
        delay(200)
        runCatching { firstFocus.requestFocus() }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(XuperBackground),
        contentPadding = PaddingValues(start = 44.dp, end = 40.dp, top = 24.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                AsyncImage(
                    model = title.posterUrl,
                    contentDescription = title.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.width(150.dp).height(222.dp).clip(RoundedCornerShape(10.dp)).background(XuperSurfaceVariant),
                )
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text("FICHA · LAB", color = XuperAccentBright, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text(title.name, color = XuperTextPrimary, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    Text(
                        listOfNotNull(title.year, title.rating?.let { "★ $it" }, if (isSeries) "Serie" else "Película")
                            .joinToString("  ·  "),
                        color = XuperTextSecondary,
                        fontSize = 16.sp,
                    )
                    Text(
                        title.description.ifBlank { "Sin descripción disponible." },
                        color = XuperTextSecondary,
                        fontSize = 15.sp,
                        maxLines = 5,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = onBack,
                            modifier = if (isSeries) Modifier.focusRequester(firstFocus) else Modifier,
                        ) { Text("← VOLVER") }
                        if (!isSeries) {
                            Button(
                                onClick = { onPlay(title.name, title.id) },
                                modifier = Modifier.focusRequester(firstFocus),
                            ) { Text("▶  REPRODUCIR") }
                        }
                    }
                }
            }
        }

        if (isSeries) {
            item {
                Column(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    Text("TEMPORADAS Y EPISODIOS", color = XuperTextPrimary, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                    when {
                        loadingSeasons -> LabStatusText("Cargando temporadas…")
                        seasons.isEmpty() -> LabStatusText("No se pudieron cargar las temporadas. Revisá la conexión y la clave de TMDB.")
                        else -> LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            contentPadding = PaddingValues(vertical = 10.dp),
                        ) {
                            items(seasons, key = { it.number }) { season ->
                                val active = season.number == selectedSeason
                                Button(onClick = { selectedSeason = season.number }) {
                                    Text((if (active) "● " else "") + "${season.name} · ${season.episodeCount} ep.")
                                }
                            }
                        }
                    }
                }
            }
            if (loadingEpisodes) {
                item { LabStatusText("Cargando episodios…") }
            } else if (!loadingSeasons && seasons.isNotEmpty() && episodes.isEmpty()) {
                item { LabStatusText("No se encontraron episodios para esta temporada.") }
            }
            items(episodes, key = { "${selectedSeason}-${it.number}" }) { episode ->
                LabEpisodeRow(
                    episode = episode,
                    onClick = {
                        val season = selectedSeason ?: 1
                        onPlay(
                            "${title.name} · T$season E${episode.number}",
                            "${title.id}|s$season|e${episode.number}",
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun LabPosterCard(title: Title, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = XuperSurfaceVariant,
            contentColor = XuperTextPrimary,
            focusedContainerColor = XuperAccent,
            focusedContentColor = XuperTextPrimary,
        ),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(6.dp)) {
            Box(
                modifier = Modifier.fillMaxWidth().height(215.dp).clip(RoundedCornerShape(7.dp))
                    .background(XuperSurfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                if (title.posterUrl.isNotBlank()) {
                    AsyncImage(
                        model = title.posterUrl,
                        contentDescription = title.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text(if (title.contentKind == ContentKind.SERIES) "📺" else "🎬", fontSize = 42.sp)
                }
            }
            Text(
                title.name,
                color = XuperTextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                modifier = Modifier.padding(horizontal = 5.dp, vertical = 8.dp),
            )
            Text(
                listOfNotNull(
                    if (title.contentKind == ContentKind.SERIES) "SERIE" else "PELÍCULA",
                    title.year,
                    title.rating?.let { "★ $it" },
                ).joinToString(" · "),
                color = XuperTextSecondary,
                fontSize = 11.sp,
                maxLines = 1,
                modifier = Modifier.padding(start = 5.dp, bottom = 4.dp),
            )
        }
    }
}

@Composable
private fun LabEpisodeRow(episode: TmdbEpisode, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = XuperSurfaceVariant,
            contentColor = XuperTextPrimary,
            focusedContainerColor = XuperAccent,
            focusedContentColor = XuperTextPrimary,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier.size(width = 150.dp, height = 84.dp).clip(RoundedCornerShape(6.dp)).background(XuperBackground),
                contentAlignment = Alignment.Center,
            ) {
                if (episode.stillUrl.isNotBlank()) {
                    AsyncImage(
                        model = episode.stillUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text(episode.number.toString(), color = XuperAccentBright, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    "${episode.number}. ${episode.name}",
                    color = XuperTextPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
                Text(
                    episode.overview.ifBlank { "Sinopsis del episodio no disponible." },
                    color = XuperTextSecondary,
                    fontSize = 12.sp,
                    maxLines = 2,
                )
                episode.runtime?.let { Text("$it min", color = XuperAccentBright, fontSize = 12.sp) }
            }
            Text("▶", color = XuperAccentBright, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun LabStatusText(message: String) {
    Text(message, color = XuperTextSecondary, fontSize = 17.sp, modifier = Modifier.padding(top = 14.dp, start = 4.dp))
}
