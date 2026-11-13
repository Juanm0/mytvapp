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
import androidx.activity.compose.BackHandler
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Text
import com.quilmes.xuper.data.AppUpdater
import com.quilmes.xuper.data.UpdateState
import com.quilmes.xuper.ui.theme.XuperAccentBright
import com.quilmes.xuper.ui.theme.XuperSurface
import com.quilmes.xuper.ui.theme.XuperSurfaceVariant
import com.quilmes.xuper.ui.theme.XuperTextPrimary
import com.quilmes.xuper.ui.theme.XuperTextSecondary
import kotlinx.coroutines.delay

/** Mandatory update gate. It cannot be dismissed by Back or by touching outside the dialog. */
@Composable
fun UpdatePrompt() {
    val context = LocalContext.current
    val state by AppUpdater.state.collectAsState()
    val current = state
    if (current is UpdateState.Idle) return

    val firstButton = remember { FocusRequester() }
    var settingsFailed by remember { mutableStateOf(false) }
    val stateKey = current.javaClass
    LaunchedEffect(stateKey) {
        delay(120)
        runCatching { firstButton.requestFocus() }
    }

    // Consume Android/TV Back while the mandatory gate is visible.
    BackHandler(enabled = true) { }

    Dialog(
        onDismissRequest = { /* Mandatory: no dismiss path. */ },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = 420.dp, max = 580.dp)
                .background(XuperSurface, RoundedCornerShape(16.dp))
                .padding(horizontal = 36.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            when (current) {
                UpdateState.Checking -> {
                    DialogTitle("Verificando Xuper…")
                    DialogBody("Estamos comprobando en GitHub si esta instalación tiene la última versión. No cierres la aplicación.")
                }

                is UpdateState.Available -> {
                    DialogTitle("Actualización obligatoria")
                    DialogBody("Hay una versión nueva de Xuper (${current.info.versionName}). Para continuar, instalá esta actualización.")
                    if (current.info.notes.isNotBlank()) {
                        DialogBody("Novedades: " + current.info.notes.take(500), dim = true)
                    }
                    DialogButton("ACTUALIZAR XUPER", Modifier.focusRequester(firstButton)) {
                        AppUpdater.startUpdate(context, current.info)
                    }
                }

                is UpdateState.Downloading -> {
                    DialogTitle("Descargando actualización…")
                    DialogBody("Xuper permanecerá bloqueado hasta completar la actualización. ${current.percent} %")
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
                }

                is UpdateState.NeedsPermission -> {
                    DialogTitle("Permiso de instalación de Android")
                    DialogBody(
                        "Android necesita autorización para que Xuper pueda solicitar la instalación. " +
                            "Abrí los ajustes, habilitá «Permitir de esta fuente» para Xuper y volvé a esta pantalla."
                    )
                    if (settingsFailed) {
                        DialogBody(
                            "No se pudo abrir esa pantalla. Entrá manualmente en Ajustes › Aplicaciones › " +
                                "Acceso especial › Instalar apps desconocidas › Xuper.",
                            dim = true,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        DialogButton("ABRIR AJUSTES", Modifier.focusRequester(firstButton)) {
                            settingsFailed = !AppUpdater.openInstallSettings(context)
                        }
                        DialogButton("YA LO HABILITÉ") { AppUpdater.continueAfterPermission(context) }
                    }
                }

                is UpdateState.Installing -> {
                    DialogTitle("Instalando actualización…")
                    DialogBody("Android está procesando el APK. Si muestra su confirmación, elegí «Instalar». Xuper seguirá bloqueado hasta que termine.")
                }

                is UpdateState.Failed -> {
                    DialogTitle("No se pudo actualizar Xuper")
                    DialogBody(current.message)
                    DialogButton("REINTENTAR", Modifier.focusRequester(firstButton)) {
                        AppUpdater.retry(context)
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
