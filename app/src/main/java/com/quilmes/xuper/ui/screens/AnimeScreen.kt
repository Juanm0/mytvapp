package com.quilmes.xuper.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.quilmes.xuper.data.AdultContentSettings
import com.quilmes.xuper.data.JkanimeAnime
import com.quilmes.xuper.data.JkanimeCard
import com.quilmes.xuper.data.JkanimeEpisode
import com.quilmes.xuper.data.JkanimeEpisodePage
import com.quilmes.xuper.data.JkanimeService
import com.quilmes.xuper.ui.theme.XuperAccent
import com.quilmes.xuper.ui.theme.XuperAccentBright
import com.quilmes.xuper.ui.theme.XuperBackground
import com.quilmes.xuper.ui.theme.XuperSurfaceVariant
import com.quilmes.xuper.ui.theme.XuperTextPrimary
import com.quilmes.xuper.ui.theme.XuperTextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Catálogo ANIME independiente: integra catálogo, búsqueda, fichas, episodios paginados
 * y extracción HLS de JKanime Anime Hub 2.3 sin incorporar el servidor Node al APK.
 */
@Composable
fun AnimeScreen(
    service: JkanimeService,
    adultService: JkanimeService? = null,
    onPlayEpisode: (title: String, streamUrl: String, episodeUrl: String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    // +18: el modo adulto exige un sitio configurado (AnimeSites.ADULT_URL) Y el ajuste +18 activo.
    // Se lee el estado al momento de llamar, así las funciones de abajo siempre usan el sitio vigente.
    val adultEnabled by AdultContentSettings.enabledFlow.collectAsState()
    var adultMode by rememberSaveable { mutableStateOf(false) }
    fun adultActive(): Boolean = adultMode && adultEnabled && adultService != null
    fun activeService(): JkanimeService = adultService?.takeIf { adultActive() } ?: service
    var query by remember { mutableStateOf("") }
    var heading by remember { mutableStateOf("Anime para descubrir") }
    var catalog by remember { mutableStateOf<List<JkanimeCard>>(emptyList()) }
    var selectedCard by remember { mutableStateOf<JkanimeCard?>(null) }
    var anime by remember { mutableStateOf<JkanimeAnime?>(null) }
    var episodePage by remember { mutableStateOf<JkanimeEpisodePage?>(null) }
    var loading by remember { mutableStateOf(false) }
    var playingEpisodeUrl by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun loadCatalog() {
        scope.launch {
            loading = true
            error = null
            selectedCard = null
            anime = null
            episodePage = null
            heading = if (adultActive()) "Anime +18" else "Anime para descubrir"
            try {
                catalog = withContext(Dispatchers.IO) { activeService().loadCatalog() }
                if (catalog.isEmpty()) error = "JKanime no devolvió títulos. Revisá la conexión o probá buscar un anime."
            } catch (e: Exception) {
                error = e.message ?: "No se pudo cargar el catálogo de JKanime."
            } finally {
                loading = false
            }
        }
    }

    fun search() {
        val term = query.trim()
        if (term.isBlank()) {
            loadCatalog()
            return
        }
        scope.launch {
            loading = true
            error = null
            selectedCard = null
            anime = null
            episodePage = null
            heading = "Resultados para “$term”"
            try {
                catalog = withContext(Dispatchers.IO) { activeService().searchAnime(term) }
                if (catalog.isEmpty()) error = "No encontramos resultados para “$term”."
            } catch (e: Exception) {
                error = e.message ?: "No se pudo completar la búsqueda."
            } finally {
                loading = false
            }
        }
    }

    fun openAnime(card: JkanimeCard) {
        scope.launch {
            selectedCard = card
            anime = null
            episodePage = null
            loading = true
            error = null
            try {
                val info = withContext(Dispatchers.IO) { activeService().getAnimeInfo(card.url) }
                anime = info
                episodePage = withContext(Dispatchers.IO) { activeService().getEpisodesPage(info.url, 1) }
            } catch (e: Exception) {
                error = e.message ?: "No se pudo cargar la ficha del anime."
            } finally {
                loading = false
            }
        }
    }

    fun loadPage(page: Int) {
        val current = anime ?: return
        scope.launch {
            loading = true
            error = null
            try {
                episodePage = withContext(Dispatchers.IO) { activeService().getEpisodesPage(current.url, page) }
            } catch (e: Exception) {
                error = e.message ?: "No se pudieron cargar los episodios."
            } finally {
                loading = false
            }
        }
    }

    fun playEpisode(episode: JkanimeEpisode) {
        val current = anime ?: return
        scope.launch {
            playingEpisodeUrl = episode.url
            loading = true
            error = null
            try {
                val stream = withContext(Dispatchers.IO) { activeService().getEpisodeStream(episode.url) }
                onPlayEpisode("${current.title} · ${episode.title}", stream.streamUrl, stream.episodeUrl)
            } catch (e: Exception) {
                error = e.message ?: "No se pudo extraer el enlace del episodio."
            } finally {
                loading = false
                playingEpisodeUrl = null
            }
        }
    }

    LaunchedEffect(Unit) { loadCatalog() }
    // Si se apaga el ajuste +18 estando en el modo adulto, se vuelve al catálogo normal.
    LaunchedEffect(adultEnabled) {
        if (!adultEnabled && adultMode) { adultMode = false; query = ""; loadCatalog() }
    }
    BackHandler(enabled = selectedCard != null || adultMode) {
        if (selectedCard != null) {
            selectedCard = null
            anime = null
            episodePage = null
            error = null
            loading = false
        } else {
            adultMode = false; query = ""; loadCatalog()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(XuperBackground)) {
        if (selectedCard == null) {
            Column(
                modifier = Modifier.fillMaxSize().padding(start = 44.dp, end = 40.dp, top = 28.dp, bottom = 22.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    // Arriba a la izquierda: solo existe con un sitio +18 configurado y el ajuste +18 activo.
                    if (adultEnabled && adultService != null) {
                        Button(onClick = { adultMode = !adultMode; query = ""; loadCatalog() }) {
                            Text(if (adultMode) "← ANIME" else "+18 ANIME CONTENT")
                        }
                    }
                    Text(if (adultActive()) "ANIME +18" else "ANIME", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = XuperTextPrimary)
                    Text(if (adultActive()) "Contenido para adultos · Catálogo, búsqueda y episodios" else "JKAnime · Catálogo, búsqueda y episodios", fontSize = 15.sp, color = XuperTextSecondary)
                }
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = TextStyle(color = XuperTextPrimary, fontSize = 19.sp),
                        cursorBrush = SolidColor(XuperAccentBright),
                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                            .background(XuperSurfaceVariant).padding(horizontal = 18.dp, vertical = 14.dp),
                        decorationBox = { inner ->
                            Box {
                                if (query.isBlank()) Text("Buscar anime…", color = XuperTextSecondary, fontSize = 19.sp)
                                inner()
                            }
                        },
                    )
                    Button(onClick = { search() }) { Text("BUSCAR") }
                    Button(onClick = { loadCatalog() }) { Text("ACTUALIZAR") }
                }
                Text(heading, color = XuperTextPrimary, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 20.dp, bottom = 12.dp))
                when {
                    loading && catalog.isEmpty() -> StatusText("Conectando con JKanime…")
                    error != null && catalog.isEmpty() -> StatusText(error.orEmpty())
                    catalog.isEmpty() -> StatusText("No hay títulos para mostrar.")
                    else -> LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 154.dp),
                        contentPadding = PaddingValues(bottom = 30.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        gridItems(catalog, key = { it.url }) { card ->
                            AnimePosterCard(card = card, onClick = { openAnime(card) })
                        }
                    }
                }
                if (loading && catalog.isNotEmpty()) Text("Cargando…", color = XuperTextSecondary, fontSize = 15.sp, modifier = Modifier.padding(top = 8.dp))
                if (error != null && catalog.isNotEmpty()) Text(error.orEmpty(), color = XuperAccentBright, fontSize = 16.sp, modifier = Modifier.padding(top = 8.dp))
            }
        } else {
            val currentAnime = anime
            Column(
                modifier = Modifier.fillMaxSize().padding(start = 44.dp, end = 40.dp, top = 24.dp, bottom = 18.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = {
                        selectedCard = null
                        anime = null
                        episodePage = null
                        error = null
                    }) { Text("← CATÁLOGO") }
                    Text(currentAnime?.title ?: selectedCard?.title.orEmpty(), fontSize = 27.sp, fontWeight = FontWeight.Bold, color = XuperTextPrimary)
                }
                Spacer(Modifier.height(14.dp))
                if (currentAnime != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth().height(220.dp),
                        horizontalArrangement = Arrangement.spacedBy(22.dp),
                    ) {
                        AsyncImage(
                            model = currentAnime.image.ifBlank { selectedCard?.image },
                            contentDescription = currentAnime.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.width(150.dp).height(220.dp).clip(RoundedCornerShape(10.dp)),
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                            Text("FICHA DEL ANIME", color = XuperAccentBright, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            Text(currentAnime.title, color = XuperTextPrimary, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                            Text(
                                currentAnime.description.ifBlank { "No hay descripción disponible para este título." },
                                color = XuperTextSecondary, fontSize = 16.sp,
                            )
                            Text("ID JKanime: ${currentAnime.id}  ·  ${currentAnime.slug}", color = XuperTextSecondary, fontSize = 12.sp)
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("EPISODIOS", fontSize = 21.sp, fontWeight = FontWeight.Bold, color = XuperTextPrimary)
                    Spacer(Modifier.weight(1f))
                    val page = episodePage
                    if (page != null) {
                        Text("${page.total.takeIf { it > 0 } ?: page.episodes.size} episodios · Página ${page.page}${if (page.totalPages > page.page) " de ${page.totalPages}+" else ""}", color = XuperTextSecondary, fontSize = 14.sp)
                        Button(onClick = { if (page.page > 1) loadPage(page.page - 1) }, enabled = page.page > 1) { Text("ANTERIOR") }
                        Button(onClick = { if (page.episodes.size >= 16 || page.page < page.totalPages) loadPage(page.page + 1) }, enabled = page.episodes.size >= 16 || page.page < page.totalPages) { Text("SIGUIENTE") }
                    }
                }
                when {
                    loading && episodePage == null -> StatusText("Cargando ficha y episodios…")
                    error != null && episodePage == null -> StatusText(error.orEmpty())
                    episodePage?.episodes.isNullOrEmpty() -> StatusText(error ?: "No se encontraron episodios en esta página.")
                    else -> LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        contentPadding = PaddingValues(bottom = 24.dp),
                    ) {
                        items(episodePage?.episodes.orEmpty(), key = { it.id }) { episode ->
                            EpisodeRow(
                                episode = episode,
                                busy = playingEpisodeUrl == episode.url,
                                onClick = { playEpisode(episode) },
                            )
                        }
                    }
                }
                if (loading && episodePage != null) Text("Cargando…", color = XuperTextSecondary, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
                if (error != null && episodePage != null) Text(error.orEmpty(), color = XuperAccentBright, fontSize = 15.sp, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

@Composable
private fun AnimePosterCard(card: JkanimeCard, onClick: () -> Unit) {
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
                modifier = Modifier.fillMaxWidth().height(190.dp).clip(RoundedCornerShape(7.dp))
                    .background(XuperSurfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                if (card.image.isNotBlank()) {
                    AsyncImage(
                        model = card.image,
                        contentDescription = card.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text("🎌", fontSize = 42.sp)
                }
            }
            Text(
                card.title,
                color = XuperTextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                modifier = Modifier.padding(horizontal = 5.dp, vertical = 8.dp),
            )
            Text("VER FICHA  ↗", color = XuperTextSecondary, fontSize = 11.sp, modifier = Modifier.padding(start = 5.dp, bottom = 4.dp))
        }
    }
}

@Composable
private fun EpisodeRow(episode: JkanimeEpisode, busy: Boolean, onClick: () -> Unit) {
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
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier.size(width = 44.dp, height = 44.dp).clip(RoundedCornerShape(8.dp)).background(XuperBackground),
                contentAlignment = Alignment.Center,
            ) {
                Text(episode.number.toString(), color = XuperTextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(episode.title, color = XuperTextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                Text("Episodio ${episode.number} · ${episode.timestamp.ifBlank { "Reproducir" }}", color = XuperTextSecondary, fontSize = 12.sp)
            }
            Text(if (busy) "EXTRAYENDO…" else "▶", color = XuperAccentBright, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun StatusText(message: String) {
    Text(message, color = XuperTextSecondary, fontSize = 17.sp, modifier = Modifier.padding(top = 22.dp, start = 4.dp))
}
