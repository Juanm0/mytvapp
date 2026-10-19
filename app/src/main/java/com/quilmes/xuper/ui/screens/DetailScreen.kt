package com.quilmes.xuper.ui.screens

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.quilmes.xuper.data.ContentKind
import com.quilmes.xuper.data.Title
import com.quilmes.xuper.data.TmdbCatalogService
import com.quilmes.xuper.data.TmdbEpisode
import com.quilmes.xuper.data.TmdbSeason
import com.quilmes.xuper.data.WebSourceRegistry
import com.quilmes.xuper.data.SourceEditServer
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
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
    onOpenTitle: (Title) -> Unit = {},
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
    var relatedTitles by remember(title.id) { mutableStateOf<List<Title>>(emptyList()) }
    var imdbId by remember(title.id) { mutableStateOf<String?>(null) }
    var loadingImdbId by remember(title.id) { mutableStateOf(false) }
    val isTmdb = title.id.startsWith("tmdb-")
    val hasTmdbSeasons = title.id.startsWith("tmdb-tv-") && title.contentKind == ContentKind.SERIES

    LaunchedEffect(Unit) { SourceEditServer.start(context) }

    LaunchedEffect(title.id) {
        if (isTmdb && title.contentKind != ContentKind.LIVE) {
            loadingImdbId = true
            imdbId = catalogService.getImdbId(title.id)
            loadingImdbId = false
        } else imdbId = null
    }

    LaunchedEffect(title.id) {
        relatedTitles = if (isTmdb && title.contentKind != ContentKind.LIVE) catalogService.loadRelatedTitles(title.id) else emptyList()
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
                            Button(onClick = { onPlay(title.id) }) {
                                Text(if (title.streamUrl.isNotBlank()) "▶  Reproducir" else "🔎  Buscar fuente")
                            }
                            Button(onClick = {
                                val ids = prefs.getStringSet("favorites", emptySet())?.toMutableSet() ?: mutableSetOf()
                                if (favorites) ids.remove(title.id) else ids.add(title.id)
                                prefs.edit().putStringSet("favorites", ids).apply()
                                favorites = !favorites
                            }) { Text(if (favorites) "♥  En mi lista" else "♡  Mi lista") }
                        }
                        if (title.streamUrl.isBlank()) {
                            Text(
                                "Todavía no hay una fuente IPTV vinculada. Xuper va a probar las listas disponibles en orden y recordar la fuente que funcione.",
                                color = XuperTextSecondary, fontSize = 12.sp,
                            )
                        }
                        val qrKey = sourceKey ?: WebSourceRegistry.titleKey(title.id)
                        SourceQrEditor(
                            contentKey = qrKey,
                            title = title.name,
                            isSeries = title.contentKind == ContentKind.SERIES,
                            season = selectedSeason,
                            episode = selectedEpisode,
                            compact = true,
                            imdbId = imdbId,
                            onPlayStream = onPlayStream,
                        )
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
                            Button(onClick = { selectedEpisode = episode.number }) {
                                Text(if (selectedEpisode == episode.number) "✓  Servidores elegidos" else "Elegir servidores")
                            }
                            if (selectedEpisode == episode.number) {
                                Text("FrameXTV.xyz queda primero como servidor predeterminado abajo.", color = XuperAccentBright, fontSize = 11.sp)
                            }
                            if (playableEpisode != null) Button(onClick = { onPlay(playableEpisode.id) }) { Text("▶  Fuente IPTV") }
                        }
                    }
                    SourceQrEditor(
                        contentKey = WebSourceRegistry.episodeKey(title.id, selectedSeason ?: 1, episode.number),
                        title = title.name,
                        isSeries = true,
                        season = selectedSeason ?: 1,
                        episode = episode.number,
                        compact = true,
                        imdbId = imdbId,
                        onPlayStream = onPlayStream,
                    )
                }
            }
        }

        if (relatedTitles.isNotEmpty()) {
            item {
                Column(
                    Modifier.fillMaxWidth().padding(start = 48.dp, end = 24.dp, top = 24.dp, bottom = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("También te puede gustar", color = XuperTextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text("Recomendaciones y títulos similares", color = XuperTextSecondary, fontSize = 13.sp)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                        items(relatedTitles, key = { it.id }) { related ->
                            Column(
                                Modifier.width(142.dp).clickable { onOpenTitle(related) },
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                AsyncImage(
                                    model = related.posterUrl.ifBlank { related.backdropUrl },
                                    contentDescription = related.name,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxWidth().height(204.dp).background(XuperSurfaceVariant, RoundedCornerShape(8.dp)),
                                )
                                Text(related.name, color = XuperTextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
                                Text(listOfNotNull(related.year, related.rating?.let { "★ $it" }).joinToString(" · "), color = XuperTextSecondary, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }

    }
}


@Composable
private fun SourceQrEditor(
    contentKey: String,
    title: String,
    isSeries: Boolean,
    season: Int?,
    episode: Int?,
    compact: Boolean,
    imdbId: String? = null,
    onPlayStream: (String, String) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    var enlarged by remember(contentKey) { mutableStateOf(false) }
    var sourceVersion by remember(contentKey) { mutableIntStateOf(0) }
    val sources = remember(contentKey, title, isSeries, season, episode, imdbId, sourceVersion) {
        WebSourceRegistry.getSources(context, contentKey, imdbId, title, isSeries, season, episode)
    }
    val url = remember(contentKey, title, isSeries, season, episode, imdbId) {
        SourceEditServer.editorUrl(contentKey, title, isSeries, season, episode, imdbId)
    }
    val bitmap = remember(url) {
        url?.let {
            runCatching {
                val matrix = QRCodeWriter().encode(it, BarcodeFormat.QR_CODE, 220, 220)
                Bitmap.createBitmap(220, 220, Bitmap.Config.RGB_565).also { bmp ->
                    for (x in 0 until 220) for (y in 0 until 220) bmp.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
                }
            }.getOrNull()
        }
    }
    Column(
        modifier = Modifier.padding(start = 8.dp).width(if (compact) 94.dp else 140.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Tocar para agrandar el QR y editar enlaces desde el celular",
                modifier = Modifier
                    .size(if (compact) 76.dp else 120.dp)
                    .border(3.dp, XuperAccentBright, RoundedCornerShape(10.dp))
                    .background(androidx.compose.ui.graphics.Color.White, RoundedCornerShape(10.dp))
                    .clickable { enlarged = true }
                    .focusable()
                    .padding(3.dp),
            )
            Text("◉ TOCÁ PARA AGRANDAR", color = XuperAccentBright, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            if (enlarged) {
                Dialog(onDismissRequest = { enlarged = false }) {
                    Column(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 760.dp)
                            .verticalScroll(rememberScrollState())
                            .background(androidx.compose.ui.graphics.Color.White, RoundedCornerShape(18.dp)).padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("Escaneá para editar las fuentes desde el celular", color = androidx.compose.ui.graphics.Color.Black, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Image(bitmap = bitmap.asImageBitmap(), contentDescription = "Código QR ampliado", modifier = Modifier.widthIn(max = 300.dp).fillMaxWidth().aspectRatio(1f))
                        Text("Fuentes para este contenido", color = androidx.compose.ui.graphics.Color.Black, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        sources.forEachIndexed { index, source ->
                            val provider = when {
                                source.contains("framextv.xyz", true) -> "FrameXTV"
                                source.contains("streamimdb.ru", true) -> "StreamIMDB"
                                source.contains("movies2watch", true) -> "Movies2Watch"
                                source.contains("repelishd", true) -> "RePelisHD"
                                else -> "Fuente ${index + 1}"
                            }
                            Column(
                                Modifier.fillMaxWidth().background(androidx.compose.ui.graphics.Color(0xFFF0F1F5), RoundedCornerShape(8.dp)).padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                Text("${index + 1}. $provider", color = androidx.compose.ui.graphics.Color.Black, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                Text(source, color = androidx.compose.ui.graphics.Color.DarkGray, fontSize = 10.sp, maxLines = 3)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { onPlayStream("$title · $provider", source) }) { Text("▶ Reproducir") }
                                    Button(onClick = {
                                        WebSourceRegistry.removeSource(context, contentKey, source)
                                        sourceVersion++
                                    }) { Text("Quitar") }
                                }
                            }
                        }
                        Text("Al cerrar el QR, las URLs vuelven a ocultarse. El editor del celular guarda los cambios en Xuper.", color = androidx.compose.ui.graphics.Color.DarkGray, fontSize = 11.sp)
                    }
                }
            }
        } else {
            Text("QR no disponible", color = XuperTextSecondary, fontSize = 10.sp)
        }
    }
}
