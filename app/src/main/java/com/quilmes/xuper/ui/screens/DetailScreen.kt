package com.quilmes.xuper.ui.screens

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import com.quilmes.xuper.ui.theme.XuperAccentBright
import com.quilmes.xuper.ui.theme.XuperBackground
import com.quilmes.xuper.ui.theme.XuperSurfaceVariant
import com.quilmes.xuper.ui.theme.XuperTextPrimary
import com.quilmes.xuper.ui.theme.XuperTextSecondary

@Composable
fun DetailScreen(title: Title, catalogService: TmdbCatalogService, onPlay: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("xuper-library", Context.MODE_PRIVATE) }
    var favorites by remember(title.id) { mutableStateOf(prefs.getStringSet("favorites", emptySet())?.contains(title.id) == true) }
    var seasons by remember(title.id) { mutableStateOf<List<TmdbSeason>>(emptyList()) }
    var episodes by remember(title.id) { mutableStateOf<List<TmdbEpisode>>(emptyList()) }
    var selectedSeason by remember(title.id) { mutableStateOf<Int?>(null) }
    var selectedEpisode by remember(title.id) { mutableStateOf<Int?>(null) }
    var loadingEpisodes by remember(title.id) { mutableStateOf(false) }
    val hasTmdbSeasons = title.id.startsWith("tmdb-tv-")
    val seasonEpisode = remember(title.name) {
        val match = Regex("(?i)(?:s(\\d{1,2})\\s*e(\\d{1,3})|(\\d{1,2})x(\\d{1,3})|temporada\\s*(\\d+).*?(?:episodio|ep)\\s*(\\d+))").find(title.name)
        when {
            match == null -> null
            match.groupValues[1].isNotBlank() -> "Temporada ${match.groupValues[1]} · Episodio ${match.groupValues[2]}"
            match.groupValues[3].isNotBlank() -> "Temporada ${match.groupValues[3]} · Episodio ${match.groupValues[4]}"
            else -> "Temporada ${match.groupValues[5]} · Episodio ${match.groupValues[6]}"
        }
    }

    LaunchedEffect(title.id) {
        if (hasTmdbSeasons) {
            seasons = catalogService.loadSeasons(title.id)
            selectedSeason = seasons.firstOrNull { it.number > 0 }?.number ?: seasons.firstOrNull()?.number
        } else {
            seasons = emptyList(); episodes = emptyList(); selectedSeason = null
        }
    }
    LaunchedEffect(title.id, selectedSeason) {
        val season = selectedSeason
        if (hasTmdbSeasons && season != null) {
            loadingEpisodes = true
            episodes = catalogService.loadEpisodes(title.id, season)
            loadingEpisodes = false
            selectedEpisode = null
        } else episodes = emptyList()
    }

    LazyColumn(modifier = Modifier.fillMaxSize().background(XuperBackground), contentPadding = PaddingValues(bottom = 40.dp)) {
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
                        if (seasonEpisode != null) Text(seasonEpisode, color = XuperTextPrimary, fontSize = 16.sp)
                        Text(title.description.ifBlank { "Sin descripción disponible." }, color = XuperTextSecondary, fontSize = 15.sp, maxLines = 4)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(onClick = onPlay, enabled = title.streamUrl.isNotBlank()) {
                                Text(if (title.streamUrl.isBlank()) "▶  Video no disponible" else "▶  Reproducir")
                            }
                            Button(onClick = {
                                val ids = prefs.getStringSet("favorites", emptySet())?.toMutableSet() ?: mutableSetOf()
                                if (favorites) ids.remove(title.id) else ids.add(title.id)
                                prefs.edit().putStringSet("favorites", ids).apply()
                                favorites = !favorites
                            }) { Text(if (favorites) "♥  En mi lista" else "♡  Mi lista") }
                        }
                        if (title.year != null || title.rating != null || title.id.startsWith("tmdb-")) {
                            Text("Metadatos e imágenes: TMDB. Este producto no está respaldado ni certificado por TMDB.", color = XuperTextSecondary, fontSize = 10.sp)
                        }
                    }
                }
            }
        }
        if (hasTmdbSeasons) {
            item {
                Column(Modifier.fillMaxWidth().padding(start = 48.dp, end = 48.dp, top = 20.dp, bottom = 8.dp)) {
                    Text("Episodios", color = XuperTextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    if (seasons.isEmpty()) {
                        Text("Cargando temporadas o no hay temporadas disponibles.", color = XuperTextSecondary, fontSize = 15.sp, modifier = Modifier.padding(top = 8.dp))
                    } else {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
                            items(seasons, key = { it.number }) { season ->
                                Button(onClick = { selectedSeason = season.number }, enabled = selectedSeason != season.number) {
                                    Text("${season.name}  ·  ${season.episodeCount} ep.")
                                }
                            }
                        }
                    }
                    seasons.firstOrNull { it.number == selectedSeason }?.overview?.takeIf { it.isNotBlank() }?.let {
                        Text(it, color = XuperTextSecondary, fontSize = 14.sp, modifier = Modifier.padding(bottom = 8.dp))
                    }
                }
            }
            if (loadingEpisodes) {
                item { Text("Cargando episodios…", color = XuperTextSecondary, modifier = Modifier.padding(start = 48.dp, top = 8.dp)) }
            }
            items(episodes, key = { "${selectedSeason}-${it.number}" }) { episode ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 48.dp, vertical = 6.dp)
                        .background(if (selectedEpisode == episode.number) XuperSurfaceVariant else Color.Transparent, RoundedCornerShape(8.dp))
                        .clickable { selectedEpisode = episode.number }
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (episode.stillUrl.isNotBlank()) {
                        AsyncImage(model = episode.stillUrl, contentDescription = null, contentScale = ContentScale.Crop,
                            modifier = Modifier.width(170.dp).height(96.dp).background(XuperSurfaceVariant, RoundedCornerShape(6.dp)))
                    } else {
                        Box(Modifier.width(170.dp).height(96.dp).background(XuperSurfaceVariant, RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
                            Text("${episode.number}", color = XuperAccentBright, fontSize = 30.sp)
                        }
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("${episode.number}. ${episode.name}", color = XuperTextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        episode.runtime?.let { Text("${it} min", color = XuperAccentBright, fontSize = 12.sp) }
                        Text(episode.overview.ifBlank { "Sinopsis del episodio no disponible." }, color = XuperTextSecondary, fontSize = 13.sp, maxLines = 3)
                        if (selectedEpisode == episode.number) Text("Este episodio no tiene una fuente de video conectada.", color = XuperAccentBright, fontSize = 12.sp)
                    }
                }
            }
        } else if (title.contentKind == ContentKind.SERIES && seasonEpisode == null) {
            item {
                Text(
                    "Esta entrada no trae datos de temporada y episodio. Si está en el catálogo TMDB, abrí la ficha de la serie original para explorar sus temporadas.",
                    color = XuperTextSecondary, fontSize = 14.sp, modifier = Modifier.padding(start = 48.dp, end = 48.dp, top = 18.dp),
                )
            }
        }
    }
}
