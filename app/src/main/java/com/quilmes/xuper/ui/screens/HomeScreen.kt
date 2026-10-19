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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import com.quilmes.xuper.data.ContentKind
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

    // Inicio prioriza contenido VOD, como una app de streaming, y deja TV en vivo al final.
    val rows = remember(sections) {
        val all = sections.flatMap { it.categories }.flatMap { it.items }.distinctBy { it.streamUrl }
        val movies = all.filter { it.contentKind == ContentKind.MOVIE || it.contentKind == ContentKind.OTHER_VOD }
            .sortedWith(compareByDescending<Title> { it.rating?.toDoubleOrNull() ?: -1.0 }
                .thenByDescending { yearForSort(it) }.thenBy { it.name.lowercase() })
        val series = all.filter { it.contentKind == ContentKind.SERIES }
            .sortedWith(compareByDescending<Title> { it.rating?.toDoubleOrNull() ?: -1.0 }
                .thenByDescending { yearForSort(it) }.thenBy { it.name.lowercase() })
        val live = all.filter { it.isLive }
        buildList {
            if (movies.isNotEmpty()) add(com.quilmes.xuper.data.Category("home-movies", "Películas", movies))
            if (series.isNotEmpty()) add(com.quilmes.xuper.data.Category("home-series", "Series y episodios", series))
            if (live.isNotEmpty()) add(com.quilmes.xuper.data.Category("home-live", "TV en vivo", live))
        }
    }
    val featured = remember(sections) {
        sections.asSequence().flatMap { it.categories.asSequence() }
            .flatMap { it.items.asSequence() }
            .filter { !it.isLive && (it.rating?.toDoubleOrNull() != null || yearForSort(it) > 0) }
            .maxWithOrNull(compareBy<Title> { it.rating?.toDoubleOrNull() ?: -1.0 }
                .thenBy { yearForSort(it) }.thenBy { it.name.lowercase() })
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

private fun yearForSort(title: Title): Int = title.year?.toIntOrNull()
    ?: Regex("\\b(?:19|20)\\d{2}\\b").find(title.name)?.value?.toIntOrNull()
    ?: 0
