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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.quilmes.xuper.data.ContentRepository
import com.quilmes.xuper.ui.components.NavItem
import com.quilmes.xuper.ui.components.SideBar
import com.quilmes.xuper.ui.theme.XuperBackground

/** Pantalla principal: barra lateral + contenido (Buscar / Inicio / una lista). */
@Composable
fun MainScreen(
    repository: ContentRepository,
    onPlay: (String) -> Unit,
) {
    val sections by repository.sections.collectAsState()
    var dest by rememberSaveable { mutableStateOf("home") }

    val items = remember(sections) {
        listOf(
            NavItem("search", "🔍", "Buscar"),
            NavItem("home", "🏠", "Inicio"),
        ) + sections.map { NavItem(it.id, it.icon, it.name) }
    }

    // Atrás desde cualquier sección vuelve a Inicio (y recién ahí sale de la app)
    BackHandler(enabled = dest != "home") { dest = "home" }

    Box(modifier = Modifier.fillMaxSize().background(XuperBackground)) {
        Box(modifier = Modifier.fillMaxSize().padding(start = 72.dp)) {
            when (dest) {
                "search" -> SearchScreen(repository = repository, onPlay = onPlay)
                "home" -> HomeScreen(repository = repository, onPlay = onPlay)
                else -> CatalogScreen(repository = repository, sectionId = dest, onPlay = onPlay)
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
