package com.quilmes.xuper.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.quilmes.xuper.data.ContentRepository
import com.quilmes.xuper.data.M3uRepository
import com.quilmes.xuper.data.TmdbCatalogService
import com.quilmes.xuper.data.Title
import com.quilmes.xuper.ui.components.CardWidth
import com.quilmes.xuper.ui.components.PosterCard
import com.quilmes.xuper.ui.theme.XuperAccentBright
import com.quilmes.xuper.ui.theme.XuperSurfaceVariant
import com.quilmes.xuper.ui.theme.XuperTextPrimary
import com.quilmes.xuper.ui.theme.XuperTextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Buscador: escribís y filtra en todas las listas a la vez (sin tildes ni mayúsculas). */
@Composable
fun SearchScreen(
    repository: ContentRepository,
    catalogService: TmdbCatalogService,
    onPlay: (String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Title>>(emptyList()) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    LaunchedEffect(query) {
        delay(250) // espera a que termine de escribir
        if (query.isBlank()) {
            results = emptyList()
        } else {
            val tmdb = catalogService.search(query)
            // registerCatalogTitles reconstruye el índice: nunca en el hilo principal.
            val localResults = withContext(Dispatchers.Default) {
                (repository as? M3uRepository)?.registerCatalogTitles(tmdb)
                repository.search(query)
            }
            // Conserva los resultados directos de TMDB: al buscar un ID numérico,
            // el título se obtiene por ID y no coincide por texto con el índice local.
            results = (tmdb + localResults).distinctBy { it.id }.take(120)
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(start = 48.dp, end = 48.dp, top = 32.dp)) {
        BasicTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            textStyle = TextStyle(color = XuperTextPrimary, fontSize = 24.sp),
            cursorBrush = SolidColor(XuperAccentBright),
            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
            decorationBox = { inner ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(XuperSurfaceVariant)
                        .padding(horizontal = 20.dp, vertical = 16.dp)
                ) {
                    if (query.isEmpty()) {
                        Text(
                            text = "🔍  Buscar título, serie, canal o ID de TMDB…",
                            color = XuperTextSecondary,
                            fontSize = 24.sp,
                        )
                    }
                    inner()
                }
            },
        )

        if (query.isNotBlank() && results.isEmpty()) {
            Text(
                text = "Sin resultados para \"$query\"",
                color = XuperTextSecondary,
                fontSize = 20.sp,
                modifier = Modifier.padding(top = 24.dp),
            )
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(CardWidth),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 20.dp),
        ) {
            items(results, key = { it.id }) { title ->
                PosterCard(title = title, onClick = { onPlay(title.id) })
            }
        }
    }
}
