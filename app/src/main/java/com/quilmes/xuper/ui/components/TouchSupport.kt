package com.quilmes.xuper.ui.components

import android.os.SystemClock
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.tv.material3.CardBorder
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.CardScale
import androidx.tv.material3.CardShape
import androidx.tv.material3.ClickableSurfaceColors
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ClickableSurfaceShape
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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

/** Cuánto hay que mantener apretado OK (o el dedo) para que cuente como "mantener apretado". */
private const val LONG_PRESS_MS = 600L

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
    if (onLongClick == null) {
        androidx.tv.material3.Card(
            onClick = fire,
            modifier = modifier.tapToClick(true, fire),
            shape = shape,
            scale = scale,
            border = border,
            content = content,
        )
        return
    }

    // Mantener apretado con el MANDO: el `onLongClick` de androidx.tv depende de que el control mande
    // repeticiones de la tecla (muchos no lo hacen, y por eso "no dejaba mantener apretado"). Acá se mide
    // con un temporizador: si OK sigue apretado a los 600 ms se dispara el "largo", y si se suelta antes es un
    // clic normal. No importa si el control repite la tecla o no.
    val scope = rememberCoroutineScope()
    val longAction: () -> Unit = onLongClick
    val currentLong by rememberUpdatedState(longAction)
    val fireLong = rememberDebouncedClick { currentLong() }
    var pressJob by remember { mutableStateOf<Job?>(null) }
    var longDone by remember { mutableStateOf(false) }

    androidx.tv.material3.Card(
        onClick = fire,
        // El largo con el mando lo maneja el temporizador de abajo; con el dedo, tapToClick.
        modifier = modifier
            .tapToClick(true, fire, fireLong)
            .onPreviewKeyEvent { event ->
                val code = event.nativeKeyEvent.keyCode
                val isSelect = code == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                    code == android.view.KeyEvent.KEYCODE_ENTER ||
                    code == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER
                if (!isSelect) return@onPreviewKeyEvent false
                when (event.type) {
                    KeyEventType.KeyDown -> {
                        if (event.nativeKeyEvent.repeatCount == 0) {
                            longDone = false
                            pressJob?.cancel()
                            pressJob = scope.launch {
                                delay(LONG_PRESS_MS)
                                longDone = true
                                fireLong()
                            }
                        }
                        true // se consumen también las repeticiones: el clic se decide al soltar
                    }
                    KeyEventType.KeyUp -> {
                        pressJob?.cancel()
                        pressJob = null
                        if (!longDone) fire()
                        longDone = false
                        true
                    }
                    else -> false
                }
            },
        shape = shape,
        scale = scale,
        border = border,
        content = content,
    )
}
