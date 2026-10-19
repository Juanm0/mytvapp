package com.quilmes.xuper.ui.screens

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
import com.quilmes.xuper.ui.theme.XuperBackground
import com.quilmes.xuper.ui.theme.XuperTextPrimary
import com.quilmes.xuper.ui.theme.XuperTextSecondary

@Composable
fun DetailScreen(title: Title, onPlay: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("xuper-library", Context.MODE_PRIVATE) }
    var favorites by remember(title.id) { mutableStateOf(prefs.getStringSet("favorites", emptySet())?.contains(title.id) == true) }
    val seasonEpisode = remember(title.name) {
        val match = Regex("(?i)(?:s(\\d{1,2})\\s*e(\\d{1,3})|(\\d{1,2})x(\\d{1,3})|temporada\\s*(\\d+).*?(?:episodio|ep)\\s*(\\d+))").find(title.name)
        when {
            match == null -> null
            match.groupValues[1].isNotBlank() -> "Temporada ${match.groupValues[1]} · Episodio ${match.groupValues[2]}"
            match.groupValues[3].isNotBlank() -> "Temporada ${match.groupValues[3]} · Episodio ${match.groupValues[4]}"
            else -> "Temporada ${match.groupValues[5]} · Episodio ${match.groupValues[6]}"
        }
    }
    Box(Modifier.fillMaxSize().background(XuperBackground)) {
        AsyncImage(model = title.backdropUrl.ifBlank { title.posterUrl }, contentDescription = null,
            contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().height(340.dp).align(Alignment.TopCenter))
        Box(Modifier.fillMaxWidth().height(380.dp).background(Brush.verticalGradient(listOf(Color.Transparent, XuperBackground))))
        Row(Modifier.fillMaxSize().padding(start = 56.dp, end = 56.dp, top = 80.dp, bottom = 42.dp), verticalAlignment = Alignment.Bottom) {
            AsyncImage(model = title.posterUrl, contentDescription = title.name, contentScale = ContentScale.Crop,
                modifier = Modifier.width(190.dp).height(280.dp).background(Color.DarkGray, RoundedCornerShape(12.dp)))
            Spacer(Modifier.width(32.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(title.name, color = XuperTextPrimary, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                Text(listOfNotNull(title.year, title.rating?.let { "★ $it" }, when(title.contentKind) { ContentKind.MOVIE -> "Película"; ContentKind.SERIES -> "Serie"; ContentKind.LIVE -> "En vivo"; else -> "Contenido" }).joinToString("  ·  "), color = XuperTextSecondary, fontSize = 18.sp)
                if (seasonEpisode != null) Text(seasonEpisode, color = XuperTextPrimary, fontSize = 18.sp)
                Text(title.description.ifBlank { "Sin descripción disponible en la fuente." }, color = XuperTextSecondary, fontSize = 17.sp)
                if (title.year != null || title.rating != null) {
                    Text("Metadatos de The Movie Database (TMDB). Este producto utiliza la API de TMDB, pero no está respaldado ni certificado por TMDB.",
                        color = XuperTextSecondary, fontSize = 10.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onPlay) { Text(if (title.isLive) "▶ Ver en vivo" else "▶ Reproducir") }
                    Button(onClick = {
                        val ids = prefs.getStringSet("favorites", emptySet())?.toMutableSet() ?: mutableSetOf()
                        if (favorites) ids.remove(title.id) else ids.add(title.id)
                        prefs.edit().putStringSet("favorites", ids).apply(); favorites = !favorites
                    }) { Text(if (favorites) "♥ En favoritos" else "♡ Guardar en favoritos") }
                }
            }
        }
    }
}
