package com.quilmes.xuper.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.quilmes.xuper.R
import com.quilmes.xuper.data.Category
import com.quilmes.xuper.data.ContentRepository
import com.quilmes.xuper.data.Title
import com.quilmes.xuper.ui.components.ContentRow
import com.quilmes.xuper.ui.theme.XuperBackground

@Composable
fun HomeScreen(
    repository: ContentRepository,
    onPlay: (String) -> Unit,
) {
    var featured by remember { mutableStateOf<Title?>(null) }
    var categories by remember { mutableStateOf<List<Category>>(emptyList()) }

    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        categories = repository.getCategories()
        featured = repository.getFeatured()
        loaded = true
    }

    Box(modifier = Modifier.fillMaxSize().background(XuperBackground)) {
        LazyColumn(contentPadding = PaddingValues(bottom = 48.dp)) {
            item {
                featured?.let { HeroBanner(it, onPlay = { onPlay(it.id) }) }
            }
            items(categories, key = { it.id }) { category ->
                ContentRow(
                    category = category,
                    onItemClick = onPlay,
                    modifier = Modifier.padding(top = 24.dp),
                )
            }
        }

        if (!loaded) {
            Text("Cargando canales…", modifier = Modifier.align(Alignment.Center))
        } else if (categories.isEmpty()) {
            Text(
                "No se pudo cargar ninguna lista. Revisá la conexión a internet.",
                modifier = Modifier.align(Alignment.Center),
            )
        }

        // Logo chico arriba a la izquierda, siempre visible
        Image(
            painter = androidx.compose.ui.res.painterResource(id = R.drawable.logo_splash),
            contentDescription = "Xuper Quilmes TV",
            modifier = Modifier
                .padding(top = 24.dp, start = 48.dp)
                .widthIn(max = 56.dp)
                .aspectRatio(1f),
        )
    }
}

@Composable
private fun HeroBanner(title: Title, onPlay: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 7f)
    ) {
        AsyncImage(
            model = title.backdropUrl,
            contentDescription = title.name,
            contentScale = if (title.isLive) ContentScale.Fit else ContentScale.Crop,
            modifier = Modifier.fillMaxSize().padding(
                horizontal = if (title.isLive) 120.dp else 0.dp,
                vertical = if (title.isLive) 24.dp else 0.dp,
            ),
        )
        // Degradado hacia el fondo para que el texto se lea bien
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, XuperBackground),
                        startY = 100f,
                    )
                )
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 48.dp, bottom = 32.dp)
                .widthIn(max = 520.dp)
        ) {
            Text(text = title.name, style = MaterialTheme.typography.displaySmall)
            Text(
                text = title.description,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
            )
            Button(onClick = onPlay) {
                Text("▶  Reproducir")
            }
        }
    }
}
