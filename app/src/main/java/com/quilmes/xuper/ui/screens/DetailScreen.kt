package com.quilmes.xuper.ui.screens

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.quilmes.xuper.data.ContentKind
import com.quilmes.xuper.data.Title
import com.quilmes.xuper.data.TmdbCatalogService
import com.quilmes.xuper.data.TmdbEpisode
import com.quilmes.xuper.data.TmdbSeason
import com.quilmes.xuper.data.WebSourceRegistry
import com.quilmes.xuper.ui.theme.XuperAccentBright
import com.quilmes.xuper.ui.theme.XuperBackground
import com.quilmes.xuper.ui.theme.XuperSurfaceVariant
import com.quilmes.xuper.ui.theme.XuperTextPrimary
import com.quilmes.xuper.ui.theme.XuperTextSecondary

@Composable
fun DetailScreen(
    title: Title,
    catalogService: TmdbCatalogService,
    onPlay: (String) -> Unit,
    findPlayableEpisode: (String, Int, Int) -> Title? = { _, _, _ -> null },
    onPlayStream: (String, String) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("xuper-library", Context.MODE_PRIVATE) }
    var favorites by remember(title.id) { mutableStateOf(prefs.getStringSet("favorites", emptySet())?.contains(title.id) == true) }
    var seasons by remember(title.id) { mutableStateOf<List<TmdbSeason>>(emptyList()) }
    var episodes by remember(title.id) { mutableStateOf<List<TmdbEpisode>>(emptyList()) }
    var selectedSeason by remember(title.id) { mutableStateOf<Int?>(null) }
    var selectedEpisode by remember(title.id) { mutableStateOf<Int?>(null) }
    var loadingSeasons by remember(title.id) { mutableStateOf(false) }
    var loadingEpisodes by remember(title.id) { mutableStateOf(false) }
    var webSourcesVersion by remember(title.id) { mutableIntStateOf(0) }
    var imdbId by remember(title.id) { mutableStateOf<String?>(null) }
    var loadingImdbId by remember(title.id) { mutableStateOf(false) }
    val isTmdb = title.id.startsWith("tmdb-")
    val hasTmdbSeasons = title.id.startsWith("tmdb-tv-") && title.contentKind == ContentKind.SERIES

    LaunchedEffect(title.id) {
        if (isTmdb && title.contentKind != ContentKind.LIVE) {
            loadingImdbId = true
            imdbId = catalogService.loadImdbId(title.id)
            loadingImdbId = false
        } else imdbId = null
    }

    LaunchedEffect(title.id) {
        if (hasTmdbSeasons) {
            loadingSeasons = true
            val loaded = catalogService.loadSeasons(title.id)
            seasons = loaded
            selectedSeason = loaded.firstOrNull { it.number > 0 }?.number ?: loaded.firstOrNull()?.number
            loadingSeasons = false
        } else {
            seasons = emptyList()
            selectedSeason = null
        }
    }

    LaunchedEffect(title.id, selectedSeason) {
        val season = selectedSeason
        if (hasTmdbSeasons && season != null) {
            loadingEpisodes = true
            episodes = catalogService.loadEpisodes(title.id, season)
            loadingEpisodes = false
            selectedEpisode = episodes.firstOrNull()?.number
        } else episodes = emptyList()
    }

    val sourceKey = when {
        title.contentKind == ContentKind.SERIES && selectedSeason != null && selectedEpisode != null ->
            WebSourceRegistry.episodeKey(title.id, selectedSeason!!, selectedEpisode!!)
        title.contentKind != ContentKind.SERIES -> WebSourceRegistry.titleKey(title.id)
        else -> null
    }
    val webSources = remember(sourceKey, webSourcesVersion, imdbId, title.name, selectedSeason, selectedEpisode) {
        sourceKey?.let {
            WebSourceRegistry.getSources(
                context = context,
                contentKey = it,
                imdbId = imdbId,
                displayTitle = title.name,
                isSeries = title.contentKind == ContentKind.SERIES,
                season = selectedSeason,
                episodeNumber = selectedEpisode,
            )
        }.orEmpty()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(XuperBackground),
        contentPadding = PaddingValues(bottom = 40.dp),
    ) {
        item {
            Box(Modifier.fillMaxWidth().height(350.dp)) {
                AsyncImage(
                    model = title.backdropUrl.ifBlank { title.posterUrl }, contentDescription = null,
                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                )
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x330B0F17), XuperBackground))))
                Row(
                    Modifier.fillMaxSize().padding(start = 48.dp, end = 52.dp, top = 40.dp, bottom = 26.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    AsyncImage(
                        model = title.posterUrl, contentDescription = title.name, contentScale = ContentScale.Crop,
                        modifier = Modifier.width(164.dp).height(244.dp).background(XuperSurfaceVariant, RoundedCornerShape(10.dp)),
                    )
                    Spacer(Modifier.width(26.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Text(title.name, color = XuperTextPrimary, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                        Text(
                            listOfNotNull(title.year, title.rating?.let { "★ $it" }, when (title.contentKind) {
                                ContentKind.MOVIE -> "Película"
                                ContentKind.SERIES -> "Serie"
                                ContentKind.LIVE -> "En vivo"
                                else -> "Contenido"
                            }).joinToString("  ·  "), color = XuperTextSecondary, fontSize = 16.sp,
                        )
                        Text(title.description.ifBlank { "Sin descripción disponible." }, color = XuperTextSecondary, fontSize = 15.sp, maxLines = 4)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (title.streamUrl.isNotBlank()) {
                                Button(onClick = { onPlay(title.id) }) { Text("▶  Reproducir") }
                            }
                            Button(onClick = {
                                val ids = prefs.getStringSet("favorites", emptySet())?.toMutableSet() ?: mutableSetOf()
                                if (favorites) ids.remove(title.id) else ids.add(title.id)
                                prefs.edit().putStringSet("favorites", ids).apply()
                                favorites = !favorites
                            }) { Text(if (favorites) "♥  En mi lista" else "♡  Mi lista") }
                        }
                        if (isTmdb) Text(
                            "Metadatos e imágenes: TMDB. La ficha no garantiza que exista una fuente de vídeo.",
                            color = XuperTextSecondary, fontSize = 10.sp,
                        )
                    }
                }
            }
        }

        if (hasTmdbSeasons) {
            item {
                Column(Modifier.fillMaxWidth().padding(start = 48.dp, end = 48.dp, top = 20.dp, bottom = 8.dp)) {
                    Text("Temporadas y episodios", color = XuperTextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    when {
                        loadingSeasons -> Text("Cargando temporadas…", color = XuperTextSecondary, modifier = Modifier.padding(top = 8.dp))
                        seasons.isEmpty() -> Text("No se pudieron cargar las temporadas. Revisá la conexión y la clave de TMDB.", color = XuperTextSecondary, modifier = Modifier.padding(top = 8.dp))
                        else -> LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
                            items(seasons, key = { it.number }) { season ->
                                Button(onClick = { selectedSeason = season.number }, enabled = selectedSeason != season.number) {
                                    Text("${season.name} · ${season.episodeCount} ep.")
                                }
                            }
                        }
                    }
                    seasons.firstOrNull { it.number == selectedSeason }?.overview?.takeIf { it.isNotBlank() }?.let {
                        Text(it, color = XuperTextSecondary, fontSize = 14.sp, modifier = Modifier.padding(bottom = 8.dp))
                    }
                }
            }
            if (loadingEpisodes) item {
                Text("Cargando episodios…", color = XuperTextSecondary, modifier = Modifier.padding(start = 48.dp, top = 8.dp))
            }
            items(episodes, key = { "${selectedSeason}-${it.number}" }) { episode ->
                val playableEpisode = findPlayableEpisode(title.name, selectedSeason ?: 0, episode.number)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 48.dp, vertical = 6.dp)
                        .background(XuperSurfaceVariant, RoundedCornerShape(8.dp)).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (episode.stillUrl.isNotBlank()) {
                        AsyncImage(
                            model = episode.stillUrl, contentDescription = null, contentScale = ContentScale.Crop,
                            modifier = Modifier.width(170.dp).height(96.dp).background(XuperBackground, RoundedCornerShape(6.dp)),
                        )
                    } else {
                        Box(Modifier.width(170.dp).height(96.dp).background(XuperBackground), contentAlignment = Alignment.Center) {
                            Text("${episode.number}", color = XuperAccentBright, fontSize = 30.sp)
                        }
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("${episode.number}. ${episode.name}", color = XuperTextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        episode.runtime?.let { Text("${it} min", color = XuperAccentBright, fontSize = 12.sp) }
                        Text(episode.overview.ifBlank { "Sinopsis del episodio no disponible." }, color = XuperTextSecondary, fontSize = 13.sp, maxLines = 3)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                selectedEpisode = episode.number
                                val seasonNo = selectedSeason ?: 1
                                val seriesTmdbId = title.id.removePrefix("tmdb-tv-")
                                val frameXUrl = "https://framextv.xyz/watch/$seriesTmdbId/$seasonNo/${episode.number}"
                                onPlayStream("${title.name} · T${seasonNo}E${episode.number} · FrameXTV", frameXUrl)
                            }) { Text("▶  Reproducir episodio") }
                            if (playableEpisode != null) Button(onClick = { onPlay(playableEpisode.id) }) { Text("▶  Fuente IPTV") }
                        }
                    }
                }
            }
        }

        if (isTmdb && title.contentKind != ContentKind.LIVE) {
            item {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 48.dp, vertical = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("Fuentes web alternativas", color = XuperTextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text(
                        if (title.contentKind == ContentKind.SERIES)
                            "Xuper prepara fuentes automáticamente para el episodio elegido: FrameXTV (ambos órdenes de temporada/capítulo), búsqueda en Movies2Watch y búsqueda en RePelisHD. Tocá Reproducir; no hace falta escribir enlaces."
                        else "Xuper genera StreamIMDB usando el IMDb ID real consultado a TMDB y agrega búsquedas en Movies2Watch y RePelisHD. Las páginas se abren dentro de Xuper con el filtro web activo.",
                        color = XuperTextSecondary, fontSize = 13.sp,
                    )
                    if (title.contentKind != ContentKind.SERIES && loadingImdbId) {
                        Text("Buscando el identificador IMDb…", color = XuperAccentBright, fontSize = 12.sp)
                    }
                    if (sourceKey == null) {
                        Text("Las fuentes alternativas corresponden al episodio más reciente seleccionado.", color = XuperAccentBright, fontSize = 13.sp)
                    } else {
                        if (webSources.isEmpty()) {
                            Text("No se generaron fuentes para este contenido. Revisá que TMDB tenga IMDb ID o que haya un episodio seleccionado.", color = XuperTextSecondary, fontSize = 12.sp)
                        } else {
                            webSources.forEachIndexed { index, source ->
                                Row(
                                    Modifier.fillMaxWidth().background(XuperSurfaceVariant, RoundedCornerShape(8.dp)).padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                        val providerLabel = when {
                                            source.contains("streamimdb.ru", true) -> "StreamIMDB · embed por IMDb"
                                            source.contains("movies2watch.vc", true) -> "Movies2Watch · búsqueda del título"
                                            source.contains("framextv.xyz", true) -> "FrameXTV · episodio"
                                            source.contains("repelishd", true) && source.contains("?s=", true) -> "RePelisHD · búsqueda del título"
                                            source.contains("repelishd", true) -> "RePelisHD · catálogo"
                                            else -> "Fuente alternativa ${index + 1}"
                                        }
                                        Text(providerLabel, color = XuperTextPrimary, fontWeight = FontWeight.SemiBold)
                                        Text(source, color = XuperTextSecondary, fontSize = 11.sp, maxLines = 2)
                                        Text("Candidata · se comprueba al abrir", color = XuperTextSecondary, fontSize = 10.sp)
                                    }
                                    Button(onClick = { onPlayStream("${title.name} · ${when {
                                        source.contains("streamimdb.ru", true) -> "StreamIMDB"
                                        source.contains("movies2watch.vc", true) -> "Movies2Watch"
                                        source.contains("framextv.xyz", true) -> "FrameXTV"
                                        source.contains("repelishd", true) -> "RePelisHD"
                                        else -> "Fuente ${index + 1}"
                                    }}", source) }) { Text("▶ Reproducir") }
                                    Spacer(Modifier.width(6.dp))
                                    Button(onClick = {
                                        WebSourceRegistry.removeSource(context, sourceKey, source)
                                        webSourcesVersion++
                                    }) { Text("Quitar") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
