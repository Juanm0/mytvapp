package com.quilmes.xuper.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.quilmes.xuper.data.AdultContentSettings
import com.quilmes.xuper.ui.theme.XuperAccent
import com.quilmes.xuper.ui.theme.XuperSurface
import com.quilmes.xuper.ui.theme.XuperSurfaceVariant
import com.quilmes.xuper.ui.theme.XuperTextPrimary
import com.quilmes.xuper.ui.theme.XuperTextSecondary

/**
 * Diálogo de PIN pensado para control remoto: teclado numérico en pantalla (también acepta las teclas
 * numéricas del mando). Se completa solo al llegar a 4 dígitos.
 *
 * @param confirmEntry si es true pide el PIN dos veces (crear/cambiar PIN).
 * @param onComplete recibe el PIN; devuelve un mensaje de error para mostrar, o null si todo salió bien
 *                   (en ese caso quien llama cierra el diálogo).
 */
@Composable
fun PinDialog(
    title: String,
    message: String,
    confirmEntry: Boolean,
    onDismiss: () -> Unit,
    onComplete: (String) -> String?,
) {
    val length = AdultContentSettings.PIN_LENGTH
    var entry by remember { mutableStateOf("") }
    var first by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val firstKey = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstKey.requestFocus() } }

    fun push(digit: Char) {
        if (entry.length >= length) return
        entry += digit
        error = null
        if (entry.length < length) return
        val value = entry
        when {
            confirmEntry && first == null -> { first = value; entry = "" }
            confirmEntry && first != value -> { first = null; entry = ""; error = "Los PIN no coinciden. Probá de nuevo." }
            else -> onComplete(value)?.let { msg -> entry = ""; first = null; error = msg }
        }
    }
    fun erase() { if (entry.isNotEmpty()) { entry = entry.dropLast(1); error = null } }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .background(XuperSurface, RoundedCornerShape(16.dp))
                .padding(horizontal = 36.dp, vertical = 28.dp)
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val digit = when (e.key) {
                        Key.Zero, Key.NumPad0 -> '0'; Key.One, Key.NumPad1 -> '1'; Key.Two, Key.NumPad2 -> '2'
                        Key.Three, Key.NumPad3 -> '3'; Key.Four, Key.NumPad4 -> '4'; Key.Five, Key.NumPad5 -> '5'
                        Key.Six, Key.NumPad6 -> '6'; Key.Seven, Key.NumPad7 -> '7'; Key.Eight, Key.NumPad8 -> '8'
                        Key.Nine, Key.NumPad9 -> '9'
                        else -> null
                    }
                    when {
                        digit != null -> { push(digit); true }
                        e.key == Key.Backspace || e.key == Key.Delete -> { erase(); true }
                        else -> false
                    }
                },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(title, color = XuperTextPrimary, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text(
                if (confirmEntry && first != null) "Repetí el PIN para confirmarlo" else message,
                color = XuperTextSecondary, fontSize = 15.sp,
            )
            Text(
                (0 until length).joinToString("  ") { if (it < entry.length) "●" else "○" },
                color = XuperTextPrimary, fontSize = 30.sp,
            )
            Box(Modifier.height(22.dp), contentAlignment = Alignment.Center) {
                error?.let { Text(it, color = Color(0xFFFF6B6B), fontSize = 14.sp) }
            }
            val rows = listOf("123", "456", "789")
            rows.forEachIndexed { r, digits ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    digits.forEachIndexed { c, d ->
                        PinKey(
                            label = d.toString(),
                            modifier = if (r == 0 && c == 0) Modifier.focusRequester(firstKey) else Modifier,
                            onClick = { push(d) },
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PinKey("⌫", onClick = { erase() })
                PinKey("0", onClick = { push('0') })
                PinKey("✕", onClick = onDismiss)
            }
        }
    }
}

@Composable
private fun PinKey(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.width(84.dp).height(56.dp),
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = XuperSurfaceVariant,
            contentColor = XuperTextPrimary,
            focusedContainerColor = XuperAccent,
            focusedContentColor = Color.White,
        ),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(label, fontSize = 24.sp, fontWeight = FontWeight.Medium)
        }
    }
}
