package com.quilmes.xuper.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.quilmes.xuper.data.Section
import com.quilmes.xuper.data.Title
import com.quilmes.xuper.ui.components.CardWidth
import com.quilmes.xuper.ui.components.PosterCard
import com.quilmes.xuper.ui.theme.XuperTextPrimary
import com.quilmes.xuper.ui.theme.XuperTextSecondary

/**
 * +18 muestra exclusivamente los canales/contenidos de mis-listas/xxx.m3u.
 * Ya no consulta el catálogo adulto de TMDb.
 */
@Composable
fun AdultScreen(
    sections: List<Section>,
    onPlay: (String) -> Unit,
) {
    val adultSection = sections.firstOrNull {
        it.name.equals("xxx", ignoreCase = true) ||
            it.name.equals("+18", ignoreCase = true)
    }
    val titles = adultSection?.categories.orEmpty().flatMap { it.items }.distinctBy { it.streamUrl }

    androidx.compose.foundation.layout.Column(
        modifier = Modifier.fillMaxSize().padding(top = 32.dp)
    ) {
        Text("+18", color = XuperTextPrimary, fontSize = 32.sp,
            modifier = Modifier.padding(horizontal = 48.dp))
        if (titles.isEmpty()) {
            Text(
                "Agregá xxx.m3u en la carpeta mis-listas para mostrar contenido acá.",
                color = XuperTextSecondary, fontSize = 18.sp,
                modifier = Modifier.padding(start = 48.dp, top = 24.dp, end = 48.dp)
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(CardWidth),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(horizontal = 48.dp, vertical = 20.dp),
            ) {
                items(titles, key = { it.id }) { title ->
                    PosterCard(title = title, onClick = { onPlay(title.id) })
                }
            }
        }
    }
}
