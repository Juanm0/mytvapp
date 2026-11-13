package com.quilmes.xuper.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Text
import com.quilmes.xuper.data.AppUpdater
import com.quilmes.xuper.data.UpdateState
import com.quilmes.xuper.ui.theme.XuperAccentBright
import com.quilmes.xuper.ui.theme.XuperSurface
import com.quilmes.xuper.ui.theme.XuperSurfaceVariant
import com.quilmes.xuper.ui.theme.XuperTextPrimary
import com.quilmes.xuper.ui.theme.XuperTextSecondary
import kotlinx.coroutines.delay

/**
 * Cartel de actualización automática. Se pone una sola vez en MainActivity, arriba de la navegación:
 * no dibuja nada mientras no haya una actualización (estado Idle).
 */
@Composable
fun UpdatePrompt() {
    val context = LocalContext.current
    val state by AppUpdater.state.collectAsState()
    val current = state
    if (current is UpdateState.Idle) return

    val firstButton = remember { FocusRequester() }
    var settingsFailed by remember { mutableStateOf(false) }
    // El foco vuelve al primer botón cada vez que cambia el estado (los botones cambian con él).
    val stateKey = current.javaClass
    LaunchedEffect(stateKey) {
        delay(120)
        runCatching { firstButton.requestFocus() }
    }

    Dialog(onDismissRequest = { AppUpdater.cancelOrDismiss() }) {
        Column(
            modifier = Modifier
                .widthIn(min = 420.dp, max = 580.dp)
                .background(XuperSurface, RoundedCornerShape(16.dp))
                .padding(horizontal = 36.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            when (current) {
                is UpdateState.Available -> {
                    DialogTitle("Actualización disponible")
                    DialogBody("Hay una versión nueva de Xuper (${current.info.versionName}). Se descarga y se instala en un minuto.")
                    if (current.info.notes.isNotBlank()) {
                        DialogBody("Novedades: " + current.info.notes.take(300), dim = true)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        DialogButton("Actualizar ahora", Modifier.focusRequester(firstButton)) {
                            AppUpdater.startUpdate(context, current.info)
                        }
                        DialogButton("Más tarde") { AppUpdater.cancelOrDismiss() }
                    }
                }

                is UpdateState.Downloading -> {
                    DialogTitle("Descargando actualización…")
                    DialogBody("No apagues la tele ni salgas de Xuper. ${current.percent} %")
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(10.dp)
                            .background(XuperSurfaceVariant, RoundedCornerShape(5.dp)),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth((current.percent / 100f).coerceIn(0.02f, 1f))
                                .height(10.dp)
                                .background(XuperAccentBright, RoundedCornerShape(5.dp)),
                        )
                    }
                    DialogButton("Cancelar", Modifier.focusRequester(firstButton)) { AppUpdater.cancelOrDismiss() }
                }

                is UpdateState.NeedsPermission -> {
                    DialogTitle("Falta un permiso (una sola vez)")
                    DialogBody(
                        "Para actualizarse, Android tiene que dejar que Xuper instale aplicaciones. " +
                            "Apretá «Abrir ajustes», activá «Permitir de esta fuente», volvé con ATRÁS y listo."
                    )
                    if (settingsFailed) {
                        DialogBody(
                            "Esta tele no abrió esa pantalla sola. Andá a Ajustes › Aplicaciones › Acceso especial › " +
                                "Instalar apps desconocidas › Xuper, y activalo.",
                            dim = true,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        DialogButton("Abrir ajustes", Modifier.focusRequester(firstButton)) {
                            settingsFailed = !AppUpdater.openInstallSettings(context)
                        }
                        DialogButton("Continuar") { AppUpdater.continueAfterPermission(context) }
                        DialogButton("Cancelar") { AppUpdater.cancelOrDismiss() }
                    }
                }

                is UpdateState.Installing -> {
                    DialogTitle("Instalando…")
                    DialogBody("Si Android te pregunta, elegí «Instalar». Cuando termine, volvé a abrir Xuper.")
                    DialogButton("Cerrar", Modifier.focusRequester(firstButton)) { AppUpdater.cancelOrDismiss() }
                }

                is UpdateState.Failed -> {
                    DialogTitle("No se pudo actualizar")
                    DialogBody(current.message)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (current.info != null) {
                            DialogButton("Reintentar", Modifier.focusRequester(firstButton)) { AppUpdater.retry(context) }
                            DialogButton("Cerrar") { AppUpdater.cancelOrDismiss() }
                        } else {
                            DialogButton("Cerrar", Modifier.focusRequester(firstButton)) { AppUpdater.cancelOrDismiss() }
                        }
                    }
                }

                UpdateState.Idle -> Unit
            }
        }
    }
}

@Composable
private fun DialogTitle(text: String) {
    Text(text, color = XuperTextPrimary, fontSize = 26.sp, fontWeight = FontWeight.Bold)
}

@Composable
private fun DialogBody(text: String, dim: Boolean = false) {
    Text(text, color = if (dim) XuperTextSecondary else XuperTextPrimary, fontSize = 16.sp)
}

@Composable
private fun DialogButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    // Button = envoltorio de TouchSupport.kt (mando + dedo), según las reglas del proyecto.
    Button(onClick = onClick, modifier = modifier) {
        Text(label, fontSize = 16.sp)
    }
}
