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
                // OK con el hub oculto pausa/reanuda, como en el reproductor nativo (README: "OK/Select").
                HubKey.OK -> HubKeyAction.TogglePlayPause
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

/**
 * Avance/retroceso acelerado al mantener apretada una tecla (estilo plataforma de streaming).
 *
 * En vez de mandarle al reproductor un seek cada pocos milisegundos (no sabemos cómo reacciona cada
 * reproductor embebido a seeks encadenados), se ACUMULA el salto mientras la tecla está apretada y se
 * aplica de una sola vez al soltarla. Un toque corto sigue siendo un salto de 10 s.
 */
internal object SeekRamp {
    /** Tiempo apretado antes de empezar a acelerar (el primer paso de 10 s ocurre al apretar). */
    const val HOLD_START_MS = 450L

    /** Cada cuánto se suma un paso mientras la tecla sigue apretada. */
    const val TICK_MS = 300L

    /** Dos pasos separados por menos que esto cuentan como una misma racha (mandos que repiten pulsaciones). */
    const val STREAK_GAP_MS = 700L

    /** Si no llega ningún "soltar" (mandos que lo pierden), se aplica lo acumulado pasado este tiempo sin pasos. */
    const val COMMIT_IDLE_MS = 700L

    /** Tope de seguridad de una sola pulsación sostenida. */
    const val MAX_HOLD_MS = 15_000L

    private const val MAX_PENDING_SECONDS = 4 * 3600

    /** Segundos que suma el paso número [streak] (1 = primer paso de la racha). */
    fun stepSeconds(streak: Int): Int = when {
        streak <= 2 -> 10
        streak <= 5 -> 20
        streak <= 9 -> 30
        streak <= 14 -> 60
        streak <= 19 -> 120
        else -> 180
    }

    fun clampPending(seconds: Int): Int = seconds.coerceIn(-MAX_PENDING_SECONDS, MAX_PENDING_SECONDS)

    /**
     * Limita un salto a lo que cabe entre el inicio y el final cuando se conocen posición y duración
     * (FrameXTV informa ambas por `frameXTV:timeupdate`). Sin duración conocida no se toca.
     */
    fun clampDelta(delta: Int, currentSeconds: Double, durationSeconds: Double): Int {
        if (durationSeconds <= 0.0 || currentSeconds.isNaN() || durationSeconds.isNaN()) return delta
        val minDelta = -currentSeconds.toInt().coerceAtLeast(0)
        val maxDelta = (durationSeconds - currentSeconds - 2.0).toInt().coerceAtLeast(0)
        return delta.coerceIn(minDelta, maxDelta)
    }

    /** "45 s", "1:30", "1:02:03" (siempre en positivo; el signo lo pone quien lo muestra). */
    fun formatDelta(seconds: Int): String {
        val total = kotlin.math.abs(seconds)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return when {
            total < 60 -> "$total s"
            h > 0 -> "%d:%02d:%02d".format(h, m, s)
            else -> "%d:%02d".format(m, s)
        }
    }

    /** "12:34" o "1:02:03". */
    fun formatClock(seconds: Double): String {
        val total = if (seconds.isNaN()) 0 else seconds.toInt().coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }
}

/** Acumula los pasos de un avance sostenido. Kotlin puro: se prueba en la JVM. */
internal class SeekScrubber {
    /** Segundos acumulados todavía sin aplicar (negativo = atrás). */
    var pendingSeconds: Int = 0
        private set

    private var streak = 0
    private var lastStepAtMs = Long.MIN_VALUE / 2
    private var lastForward = true

    /** Suma un paso en la dirección dada y devuelve el total pendiente. */
    fun step(forward: Boolean, nowMs: Long): Int {
        val continues = forward == lastForward && nowMs - lastStepAtMs <= SeekRamp.STREAK_GAP_MS
        streak = if (continues) streak + 1 else 1
        lastForward = forward
        lastStepAtMs = nowMs
        val signed = SeekRamp.stepSeconds(streak) * if (forward) 1 else -1
        pendingSeconds = SeekRamp.clampPending(pendingSeconds + signed)
        return pendingSeconds
    }

    /** Devuelve lo acumulado y lo deja en cero (la racha sigue viva por si el mando repite pulsaciones). */
    fun consume(): Int {
        val pending = pendingSeconds
        pendingSeconds = 0
        return pending
    }
}
