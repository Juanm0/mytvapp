package com.quilmes.xuper.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.tv.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.quilmes.xuper.data.AdultContentSettings
import com.quilmes.xuper.data.ContentRepository
import com.quilmes.xuper.data.TmdbCatalogService
import com.quilmes.xuper.ui.components.NavItem
import com.quilmes.xuper.ui.components.PinDialog
import com.quilmes.xuper.ui.components.SideBar
import com.quilmes.xuper.ui.theme.XuperAccent
import com.quilmes.xuper.ui.theme.XuperBackground
import com.quilmes.xuper.ui.theme.XuperSurfaceVariant
import com.quilmes.xuper.ui.theme.XuperTextPrimary
import com.quilmes.xuper.ui.theme.XuperTextSecondary
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface

/** Navegación estilo streaming: solo quedan Inicio, Buscar, Argentina y utilidades personales. */
@Composable
fun MainScreen(
    repository: ContentRepository,
    catalogService: TmdbCatalogService,
    onPlay: (String) -> Unit,
    onRequestIptv: () -> Unit = {},
) {
    val sections by repository.sections.collectAsState()
    var dest by rememberSaveable { mutableStateOf("home") }

    // La sección +18 existe en la barra lateral solo con el ajuste activado (Perfil, protegido con PIN).
    val adultEnabled by AdultContentSettings.enabledFlow.collectAsState()
    val items = buildList {
        add(NavItem("home", "⌂", "INICIO"))
        add(NavItem("search", "⌕", "BUSCAR"))
        add(NavItem("live", "🇦🇷", "ARGENTINA"))
        add(NavItem("favorites", "♡", "MI LISTA"))
        add(NavItem("continue", "▶", "CONTINUAR VIENDO"))
        if (adultEnabled) add(NavItem("adult", "🔞", "+18"))
        add(NavItem("profile", "●", "PERFIL"))
    }
    LaunchedEffect(adultEnabled) { if (!adultEnabled && dest == "adult") dest = "home" }

    BackHandler(enabled = dest != "home") { dest = "home" }

    Box(modifier = Modifier.fillMaxSize().background(XuperBackground)) {
        Box(modifier = Modifier.fillMaxSize().padding(start = 72.dp)) {
            when (dest) {
                "home" -> HomeScreen(repository = repository, onPlay = onPlay)
                "search" -> SearchScreen(repository = repository, catalogService = catalogService, onPlay = onPlay)
                "live" -> LibraryScreen(sections = sections, mode = LibraryMode.ARGENTINA, onPlay = onPlay)
                "favorites" -> LibraryScreen(sections = sections, mode = LibraryMode.FAVORITES, onPlay = onPlay)
                "continue" -> LibraryScreen(sections = sections, mode = LibraryMode.CONTINUE, onPlay = onPlay)
                "adult" -> if (adultEnabled) AdultScreen(repository = repository, catalogService = catalogService, onPlay = onPlay)
                "profile" -> ProfileScreen()
            }
        }
        SideBar(items = items, selectedId = dest, onSelect = { selected ->
            if (selected == "live") onRequestIptv()
            dest = selected
        }, modifier = Modifier.align(Alignment.CenterStart))
    }
}

@Composable
private fun ProfileScreen() {
    // Perfil simple: no depende de servicios externos ni de integraciones retiradas.
    Column(
        modifier = Modifier.fillMaxSize().padding(start = 52.dp, end = 48.dp, top = 52.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text("¿Quién está viendo?", fontSize = 32.sp)
        Row(
            modifier = Modifier.padding(24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Box(
                modifier = Modifier.size(76.dp).background(XuperBackground),
                contentAlignment = Alignment.Center,
            ) {
                Text("X", fontSize = 38.sp)
            }
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("Perfil principal", fontSize = 23.sp)
                Text("Xuper Quilmes TV · Android TV", fontSize = 16.sp)
            }
        }
        Text("Preferencias", fontSize = 20.sp)
        Text(
            "Tus favoritos, historial y fuentes de reproducción se gestionan localmente en este dispositivo.",
            fontSize = 16.sp,
        )
        AdultContentPreference()
    }
}

private enum class PinStep { CREATE_AND_ENABLE, VERIFY_TO_ENABLE, VERIFY_TO_CHANGE, CREATE_NEW }

/** Check "Mostrar contenido adulto" (+18), apagado por defecto. Activarlo exige PIN; desactivarlo no. */
@Composable
private fun AdultContentPreference() {
    val enabled by AdultContentSettings.enabledFlow.collectAsState()
    var step by remember { mutableStateOf<PinStep?>(null) }
    var hasPin by remember { mutableStateOf(AdultContentSettings.hasPin()) }

    PreferenceRow(
        check = if (enabled) "☑" else "☐",
        title = "Mostrar contenido adulto (+18)",
        subtitle = if (enabled) "Activado: aparece la sección +18 y las búsquedas/fichas incluyen contenido adulto."
            else "Desactivado: la sección +18 queda oculta. Para activarlo se pide un PIN.",
        onClick = {
            if (enabled) AdultContentSettings.disable()
            else step = if (hasPin) PinStep.VERIFY_TO_ENABLE else PinStep.CREATE_AND_ENABLE
        },
    )
    if (hasPin) {
        PreferenceRow(
            check = "🔒", title = "Cambiar PIN", subtitle = "Se pide el PIN actual.",
            onClick = { step = PinStep.VERIFY_TO_CHANGE },
        )
    }

    fun verdict(result: AdultContentSettings.PinResult, onOk: () -> Unit): String? = when (result) {
        AdultContentSettings.PinResult.OK -> { onOk(); null }
        AdultContentSettings.PinResult.WRONG -> "PIN incorrecto."
        AdultContentSettings.PinResult.LOCKED -> "Demasiados intentos. Esperá ${AdultContentSettings.lockSecondsLeft()} s."
    }

    step?.let { current ->
        key(current) {
            when (current) {
                PinStep.CREATE_AND_ENABLE -> PinDialog(
                    title = "Creá un PIN", message = "Elegí un PIN de 4 dígitos para proteger el contenido +18.",
                    confirmEntry = true, onDismiss = { step = null },
                ) { pin ->
                    if (AdultContentSettings.createPinAndEnable(pin)) { hasPin = true; step = null; null } else "PIN inválido."
                }
                PinStep.VERIFY_TO_ENABLE -> PinDialog(
                    title = "Ingresá tu PIN", message = "Necesitás el PIN para activar el contenido +18.",
                    confirmEntry = false, onDismiss = { step = null },
                ) { pin -> verdict(AdultContentSettings.enableWithPin(pin)) { step = null } }
                PinStep.VERIFY_TO_CHANGE -> PinDialog(
                    title = "PIN actual", message = "Ingresá tu PIN actual para cambiarlo.",
                    confirmEntry = false, onDismiss = { step = null },
                ) { pin -> verdict(AdultContentSettings.verifyPin(pin)) { step = PinStep.CREATE_NEW } }
                PinStep.CREATE_NEW -> PinDialog(
                    title = "Nuevo PIN", message = "Elegí un PIN de 4 dígitos.",
                    confirmEntry = true, onDismiss = { step = null },
                ) { pin -> if (AdultContentSettings.setPin(pin)) { step = null; null } else "PIN inválido." }
            }
        }
    }
}

@Composable
private fun PreferenceRow(check: String, title: String, subtitle: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = XuperSurfaceVariant,
            contentColor = XuperTextPrimary,
            focusedContainerColor = XuperAccent,
            focusedContentColor = Color.White,
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(check, fontSize = 26.sp)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, fontSize = 19.sp)
                Text(subtitle, fontSize = 14.sp, color = XuperTextSecondary)
            }
        }
    }
}
