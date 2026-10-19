package com.quilmes.xuper.ui.screens

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.text.TextStyle
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
import com.quilmes.xuper.data.ImdbEmbedSource
import com.quilmes.xuper.data.StremioRepository
import com.quilmes.xuper.data.StremioStream
import com.quilmes.xuper.ui.theme.XuperAccentBright
import com.quilmes.xuper.ui.theme.XuperBackground
import com.quilmes.xuper.ui.theme.XuperSurfaceVariant
import com.quilmes.xuper.ui.theme.XuperTextPrimary
import com.quilmes.xuper.ui.theme.XuperTextSecondary


private fun StremioStream.qualityLabel(): String = when (qualityRank) {
    4 -> "4K"
    3 -> "2K"
    2 -> "1080p"
    1 -> "720p/HD"
    else -> "Calidad sin especificar"
}

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
    var loadingEpisodes by remember(title.id) { mutableStateOf(false) }
    val stremioRepository = remember { StremioRepository() }
    val addonManager = remember { com.quilmes.xuper.data.StremioAddonManager(context) }
    var addonUrlsText by remember { mutableStateOf(addonManager.getAddons().joinToString("\n")) }
    var imdbId by remember(title.id) { mutableStateOf<String?>(null) }
    var stremioStreams by remember(title.id) { mutableStateOf<List<StremioStream>>(emptyList()) }
    var loadingStreams by remember(title.id) { mutableStateOf(false) }
    var streamMessage by remember(title.id) { mutableStateOf<String?>(null) }
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
        imdbId = catalogService.getImdbId(title.id)
        stremioStreams = emptyList()
        streamMessage = null
    }

    LaunchedEffect(loadingStreams, title.id, imdbId, selectedSeason, selectedEpisode) {
        if (!loadingStreams) return@LaunchedEffect
        val id = imdbId
        val addonUrls = addonManager.getAddons()
        if (id == null) {
            stremioStreams = emptyList()
            streamMessage = "No se pudo obtener el IMDb ID desde TMDB para este título."
        } else if (addonUrls.isEmpty()) {
            stremioStreams = emptyList()
            streamMessage = "Agregá al menos un addon válido (una URL por línea) y guardalo."
        } else if (title.contentKind == ContentKind.SERIES && (selectedSeason == null || selectedEpisode == null)) {
            stremioStreams = emptyList()
            streamMessage = "Para buscar streams de una serie, seleccioná una temporada y un episodio primero."
        } else {
            val result = if (title.contentKind == ContentKind.SERIES) {
                stremioRepository.getSeriesStreams(addonUrls, id, selectedSeason ?: 0, selectedEpisode ?: 0)
            } else {
                stremioRepository.getMovieStreams(addonUrls, id)
            }
            stremioStreams = result
            streamMessage = if (result.isEmpty()) "Ninguno de los ${addonUrls.size} addons devolvió URLs HTTP/HTTPS directas compatibles." else "${result.size} fuente(s) combinadas de ${addonUrls.size} addon(s), ordenadas por calidad."
        }
        loadingStreams = false
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
                            Button(onClick = { onPlay(title.id) }, enabled = title.streamUrl.isNotBlank()) {
                                Text(if (title.streamUrl.isBlank()) "▶  Video no disponible" else "▶  Reproducir")
                            }
                            Button(onClick = {
                                val ids = prefs.getStringSet("favorites", emptySet())?.toMutableSet() ?: mutableSetOf()
                                if (favorites) ids.remove(title.id) else ids.add(title.id)
                                prefs.edit().putStringSet("favorites", ids).apply()
                                favorites = !favorites
                            }) { Text(if (favorites) "♥  En mi lista" else "♡  Mi lista") }
                            val embedUrl = ImdbEmbedSource.buildUrl(imdbId)
                            if (title.id.startsWith("tmdb-") && title.contentKind != ContentKind.LIVE && embedUrl != null) {
                                Button(onClick = { onPlayStream("${title.name} · Reproductor web", embedUrl) }) {
                                    Text("▶  Abrir reproductor web")
                                }
                            }
                        }
                        if (title.id.startsWith("tmdb-") && title.contentKind != ContentKind.LIVE) {
                            Text(
                                "IMDb: ${imdbId ?: "buscando…"}",
                                color = XuperTextSecondary, fontSize = 11.sp,
                            )
                        }
                        if (title.year != null || title.rating != null || title.id.startsWith("tmdb-")) {
                            Text("Metadatos e imágenes: TMDB. Este producto no está respaldado ni certificado por TMDB.", color = XuperTextSecondary, fontSize = 10.sp)
                        }
                    }
                }
            }
        }
        if (title.id.startsWith("tmdb-") && title.contentKind != ContentKind.LIVE) {
            item {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 48.dp, vertical = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("Fuentes Stremio · múltiples addons", color = XuperTextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text("Pegá una URL de manifiesto o base por línea. Se consultan en paralelo; los resultados directos HTTP/HLS se combinan, deduplican y ordenan por calidad. Los torrents y enlaces externos no reproducibles directamente se omiten.", color = XuperTextSecondary, fontSize = 13.sp)
                    BasicTextField(
                        value = addonUrlsText,
                        onValueChange = { addonUrlsText = it },
                        textStyle = TextStyle(color = XuperTextPrimary, fontSize = 15.sp),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 70.dp, max = 150.dp)
                            .background(XuperSurfaceVariant, RoundedCornerShape(8.dp)).padding(12.dp),
                        decorationBox = { innerTextField ->
                            Box {
                                if (addonUrlsText.isBlank()) Text("https://addon-ejemplo/manifest.json\nhttps://otro-addon/manifest.json", color = XuperTextSecondary, fontSize = 14.sp)
                                innerTextField()
                            }
                        },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = {
                            val urls = addonUrlsText.lines().map { it.trim() }.filter { it.isNotBlank() }
                            addonManager.setAddons(urls)
                            addonUrlsText = addonManager.getAddons().joinToString("\n")
                            streamMessage = "Guardados ${addonManager.getAddons().size} addon(s)."
                        }) { Text("Guardar addons") }
                        Button(onClick = {
                            addonManager.setAddons(addonUrlsText.lines().map { it.trim() }.filter { it.isNotBlank() })
                            loadingStreams = true
                            streamMessage = null
                            stremioStreams = emptyList()
                        }) { Text(if (title.contentKind == ContentKind.SERIES) "Buscar episodio" else "Buscar en todos") }
                    }
                    if (title.contentKind == ContentKind.SERIES) {
                        Text("Series: seleccioná temporada y episodio en la sección Episodios y después buscá las fuentes.", color = XuperTextSecondary, fontSize = 12.sp)
                    }
                    if (loadingStreams) Text("Consultando addons en paralelo…", color = XuperAccentBright)
                    streamMessage?.let { Text(it, color = XuperTextSecondary, fontSize = 13.sp) }
                    stremioStreams.forEach { stream ->
                        Row(
                            Modifier.fillMaxWidth().background(XuperSurfaceVariant, RoundedCornerShape(8.dp)).padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text("${stream.name} · ${stream.qualityLabel()}", color = XuperTextPrimary, fontWeight = FontWeight.SemiBold)
                                Text(stream.title, color = XuperTextSecondary, fontSize = 12.sp, maxLines = 2)
                                Text(runCatching { java.net.URL(stream.addonUrl).host }.getOrDefault("Addon"), color = XuperAccentBright, fontSize = 11.sp)
                            }
                            Button(onClick = { onPlayStream("${title.name} · ${stream.name}", stream.url) }) { Text("▶ Reproducir") }
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
                val playableEpisode = findPlayableEpisode(title.name, selectedSeason ?: 0, episode.number)
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
                        if (playableEpisode != null) {
                            Button(onClick = { onPlay(playableEpisode.id) }) {
                                Text("▶  Reproducir episodio")
                            }
                        } else if (selectedEpisode == episode.number) {
                            Text("Este episodio no tiene una fuente de video coincidente en las listas conectadas.", color = XuperAccentBright, fontSize = 12.sp)
                        }
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
