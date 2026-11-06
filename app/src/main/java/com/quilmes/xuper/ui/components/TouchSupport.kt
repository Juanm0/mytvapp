package com.quilmes.xuper.ui.components

import android.os.SystemClock
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.tv.material3.CardBorder
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.CardScale
import androidx.tv.material3.CardShape
import androidx.tv.material3.ClickableSurfaceColors
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ClickableSurfaceShape

/**
 * Soporte para celular (pantalla táctil).
 *
 * Los componentes de `androidx.tv.material3` están pensados para mando a distancia: en un celular se
 * pueden ver pero tocar no dispara `onClick`. Estos envoltorios tienen la misma firma que los usados
 * en el proyecto y delegan en los originales (el mando sigue funcionando igual), y además detectan el
 * toque con el dedo.
 *
 * - Si el componente original ya procesa el toque, el gesto llega consumido y acá no se dispara nada.
 * - Si por alguna razón se disparan los dos, el antirrebote de [rememberDebouncedClick] evita el doble clic.
 * - El desplazamiento (scroll) con el dedo no cuenta como toque.
 */
@Composable
internal fun rememberDebouncedClick(onClick: () -> Unit): () -> Unit {
    val current by rememberUpdatedState(onClick)
    val last = remember { longArrayOf(0L) }
    return remember {
        {
            val now = SystemClock.uptimeMillis()
            if (now - last[0] > 250L) {
                last[0] = now
                current()
            }
        }
    }
}

internal fun Modifier.tapToClick(enabled: Boolean, onTap: () -> Unit, onLongPress: (() -> Unit)? = null): Modifier =
    if (!enabled) this else this.pointerInput(onLongPress != null) {
        val longPress: ((Offset) -> Unit)? = onLongPress?.let { action -> { _: Offset -> action() } }
        detectTapGestures(onLongPress = longPress, onTap = { onTap() })
    }

@Composable
fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val fire = rememberDebouncedClick(onClick)
    androidx.tv.material3.Button(
        onClick = fire,
        modifier = modifier.tapToClick(enabled, fire),
        enabled = enabled,
        content = content,
    )
}

@Composable
fun Surface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: ClickableSurfaceShape = ClickableSurfaceDefaults.shape(),
    colors: ClickableSurfaceColors = ClickableSurfaceDefaults.colors(),
    content: @Composable BoxScope.() -> Unit,
) {
    val fire = rememberDebouncedClick(onClick)
    androidx.tv.material3.Surface(
        onClick = fire,
        modifier = modifier.tapToClick(true, fire),
        shape = shape,
        colors = colors,
        content = content,
    )
}

@Composable
fun Card(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    shape: CardShape = CardDefaults.shape(),
    scale: CardScale = CardDefaults.scale(),
    border: CardBorder = CardDefaults.border(),
    content: @Composable ColumnScope.() -> Unit,
) {
    val fire = rememberDebouncedClick(onClick)
    val fireLong = rememberDebouncedClick(onLongClick ?: {})
    val longOrNull: (() -> Unit)? = if (onLongClick != null) fireLong else null
    androidx.tv.material3.Card(
        onClick = fire,
        onLongClick = longOrNull,
        modifier = modifier.tapToClick(true, fire, longOrNull),
        shape = shape,
        scale = scale,
        border = border,
        content = content,
    )
}
