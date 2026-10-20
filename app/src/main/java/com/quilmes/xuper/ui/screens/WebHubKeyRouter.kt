package com.quilmes.xuper.ui.screens

/**
 * Lógica de teclas del hub de los reproductores web (FrameXTV, Latino/VideoAPI, OK.ru, Simuladores).
 *
 * Es Kotlin puro, sin dependencias de Android, a propósito: así la decisión "qué hace cada tecla
 * según el estado del hub" vive en UN solo lugar (antes había dos copias, una en Compose y otra en
 * el WebView, que ya habían divergido) y se puede probar en la JVM sin un dispositivo.
 */
internal enum class HubKey { UP, DOWN, LEFT, RIGHT, OK, REWIND, FAST_FORWARD, PLAY_PAUSE, OTHER }

internal enum class HubDirection { UP, DOWN, LEFT, RIGHT }

internal sealed class HubKeyAction {
    /** No consumir la tecla: sigue su camino normal (Compose, botón enfocado o página web). */
    object PassThrough : HubKeyAction()

    /** Mostrar el hub y enfocar su primer botón. */
    object OpenHub : HubKeyAction()

    /** Mover el foco dentro del hub. La tecla se consume siempre para que el foco no escape al WebView. */
    data class MoveFocus(val direction: HubDirection) : HubKeyAction()

    /** Salto de 10 s hacia adelante o atrás (se repite mientras se mantiene la tecla). */
    data class Seek(val forward: Boolean) : HubKeyAction()

    object TogglePlayPause : HubKeyAction()
}

internal object WebHubKeyRouter {
    /**
     * @param hubVisible el hub está en pantalla.
     * @param overlayOwnsKeys el mini-hub de "Episodio siguiente" está visible y necesita las flechas/OK.
     */
    fun route(key: HubKey, hubVisible: Boolean, overlayOwnsKeys: Boolean): HubKeyAction {
        if (overlayOwnsKeys && key in OVERLAY_KEYS) return HubKeyAction.PassThrough

        // Teclas físicas de medios: siempre actúan, con el hub visible o no.
        when (key) {
            HubKey.REWIND -> return HubKeyAction.Seek(forward = false)
            HubKey.FAST_FORWARD -> return HubKeyAction.Seek(forward = true)
            HubKey.PLAY_PAUSE -> return HubKeyAction.TogglePlayPause
            else -> Unit
        }

        return if (hubVisible) {
            when (key) {
                HubKey.UP -> HubKeyAction.MoveFocus(HubDirection.UP)
                HubKey.DOWN -> HubKeyAction.MoveFocus(HubDirection.DOWN)
                HubKey.LEFT -> HubKeyAction.MoveFocus(HubDirection.LEFT)
                HubKey.RIGHT -> HubKeyAction.MoveFocus(HubDirection.RIGHT)
                // OK activa el botón enfocado; el resto de teclas no son nuestras.
                else -> HubKeyAction.PassThrough
            }
        } else {
            when (key) {
                // Abajo abre el hub (arriba también, como en el reproductor nativo).
                HubKey.DOWN, HubKey.UP -> HubKeyAction.OpenHub
                HubKey.LEFT -> HubKeyAction.Seek(forward = false)
                HubKey.RIGHT -> HubKeyAction.Seek(forward = true)
                else -> HubKeyAction.PassThrough
            }
        }
    }

    private val OVERLAY_KEYS = setOf(HubKey.UP, HubKey.DOWN, HubKey.LEFT, HubKey.RIGHT, HubKey.OK)
}

/**
 * Selector de subtítulos de FrameXTV.
 *
 * FrameXTV solo acepta `setSubtitle {index}` y no documenta ningún comando para listar las pistas ni
 * sus nombres, así que NO sabemos cuántas hay: se recorre un rango fijo de índices y la interfaz solo
 * puede mostrar "Pista N". Si el reproductor llegara a informar las pistas, [SLOTS] debería reemplazarse
 * por el número real.
 */
internal object FrameXtvSubtitleSlots {
    const val SLOTS = 32

    /** Avanza y da la vuelta al llegar al final. */
    fun next(current: Int): Int = (current.coerceIn(0, SLOTS - 1) + 1) % SLOTS

    /** Retrocede sin dar la vuelta: desde la pista 1 no salta a un índice casi seguro inexistente. */
    fun previous(current: Int): Int = (current.coerceIn(0, SLOTS - 1) - 1).coerceAtLeast(0)
}
