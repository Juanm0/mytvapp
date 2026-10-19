package com.quilmes.xuper.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.quilmes.xuper.data.ContentRepository
import com.quilmes.xuper.ui.components.NavItem
import com.quilmes.xuper.ui.components.SideBar
import com.quilmes.xuper.ui.theme.XuperBackground

/** Navegación principal fija: Buscar, TV en vivo, Películas, Animación y Descubrir. */
@Composable
fun MainScreen(
    repository: ContentRepository,
    onPlay: (String) -> Unit,
) {
    val sections by repository.sections.collectAsState()
    var dest by rememberSaveable { mutableStateOf("live") }

    val items = listOf(
        NavItem("search", "🔍", "Buscar"),
        NavItem("live", "📺", "TV EN VIVO"),
        NavItem("movies", "🎬", "PELÍCULAS"),
        NavItem("animation", "🍥", "ANIMACIÓN"),
        NavItem("discover", "✨", "DESCUBRIR"),
    )

    BackHandler(enabled = dest != "live") { dest = "live" }

    Box(modifier = Modifier.fillMaxSize().background(XuperBackground)) {
        Box(modifier = Modifier.fillMaxSize().padding(start = 72.dp)) {
            when (dest) {
                "search" -> SearchScreen(repository = repository, onPlay = onPlay)
                "live" -> LibraryScreen(
                    sections = sections,
                    mode = LibraryMode.LIVE,
                    onPlay = onPlay,
                )
                "movies" -> LibraryScreen(
                    sections = sections,
                    mode = LibraryMode.MOVIES,
                    onPlay = onPlay,
                )
                "animation" -> LibraryScreen(
                    sections = sections,
                    mode = LibraryMode.ANIMATION,
                    onPlay = onPlay,
                )
                "discover" -> DiscoverScreen(sections = sections, onPlay = onPlay)
            }
        }
        SideBar(
            items = items,
            selectedId = dest,
            onSelect = { dest = it },
            modifier = Modifier.align(Alignment.CenterStart),
        )
    }
}
