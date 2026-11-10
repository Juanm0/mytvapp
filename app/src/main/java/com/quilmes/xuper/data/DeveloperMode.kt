package com.quilmes.xuper.data

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ÚNICO lugar que decide si LAB está disponible ("opciones de desarrollador").
 *
 * Secuencia secreta:
 *  1. Tocar INICIO en la barra lateral [HOME_TAPS_REQUIRED] veces seguidas (sin tocar otro ítem entre medio y con
 *     menos de [HOME_TAP_WINDOW_MS] entre toques) -> [Event.HOME_UNLOCKED]: la UI reproduce `sonido.mp3` y el
 *     gatito de la barra queda habilitado ([kittenArmedFlow]).
 *  2. Tocar el gatito [KITTEN_TAPS_REQUIRED] veces. Al llegar a [KITTEN_HINT_AT] -> [Event.KITTEN_HINT] (cartel
 *     "faltan 3"). Al llegar a [KITTEN_TAPS_REQUIRED] -> [Event.DEVELOPER_ENABLED]: se guarda el desbloqueo.
 *
 * - Solo el desbloqueo final se guarda en SharedPreferences (sobrevive al cierre de la app y al reinicio del equipo).
 *   El progreso intermedio (toques en INICIO, gatito habilitado, cuenta del gatito) vive en memoria mientras dure el
 *   proceso: no se reinicia por recomposiciones ni por ir y volver de un reproductor, porque no depende de `remember`.
 * - Los contadores solo se mueven desde los métodos `on...Pressed`, que la UI llama dentro de un `onClick`
 *   (nunca durante la composición), así que una recomposición no puede sumar pulsaciones.
 * - Para apagarlo (LAB vuelve a ocultarse y el Easter Egg vuelve a empezar): [disable].
 * - Estado expuesto como [StateFlow] para que la barra lateral y el buscador reaccionen al instante.
 */
object DeveloperMode {
    enum class Event { NONE, HOME_UNLOCKED, KITTEN_HINT, DEVELOPER_ENABLED }

    const val HOME_TAPS_REQUIRED = 3
    const val KITTEN_TAPS_REQUIRED = 67
    /** Cuando el contador llega acá (faltan 3) se muestra el aviso "Usted es desarrollador en 3.2.1". */
    const val KITTEN_HINT_AT = KITTEN_TAPS_REQUIRED - 3
    /** Tiempo máximo entre dos toques en INICIO para que cuenten como una ráfaga. */
    private const val HOME_TAP_WINDOW_MS = 2_000L

    private const val PREFS = "xuper-settings"
    private const val KEY_UNLOCKED = "developer_mode_unlocked"

    @Volatile private var prefs: SharedPreferences? = null

    private val _enabledFlow = MutableStateFlow(false)
    /** true = opciones de desarrollador desbloqueadas: LAB aparece en la barra y en el buscador. */
    val enabledFlow: StateFlow<Boolean> = _enabledFlow.asStateFlow()

    private val _kittenArmedFlow = MutableStateFlow(false)
    /** true = ya se completaron los toques en INICIO: el gatito cuenta toques. Antes de eso no hace nada. */
    val kittenArmedFlow: StateFlow<Boolean> = _kittenArmedFlow.asStateFlow()

    private var homeTaps = 0
    private var lastHomeTapAt = 0L
    private var kittenTaps = 0

    /** Llamar una vez al arrancar (MainActivity.onCreate), antes de componer la UI. */
    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _enabledFlow.value = isEnabled()
    }

    fun isEnabled(): Boolean = prefs?.getBoolean(KEY_UNLOCKED, false) ?: false

    /**
     * Mecanismo interno para apagar el modo: oculta LAB y reinicia el Easter Egg desde cero.
     * (No hay botón visible para esto a propósito.)
     */
    @Synchronized
    fun disable() {
        resetProgress()
        // commit(): se guarda en el acto, aunque el equipo se apague enseguida.
        prefs?.edit()?.putBoolean(KEY_UNLOCKED, false)?.commit()
        _enabledFlow.value = false
    }

    /** Se tocó INICIO. Devuelve [Event.HOME_UNLOCKED] solo en el toque que completa la secuencia. */
    @Synchronized
    fun onHomePressed(now: Long = SystemClock.uptimeMillis()): Event {
        if (_enabledFlow.value || _kittenArmedFlow.value) return Event.NONE // ya superado: no se repite el sonido
        if (homeTaps > 0 && now - lastHomeTapAt > HOME_TAP_WINDOW_MS) homeTaps = 0
        homeTaps++
        lastHomeTapAt = now
        if (homeTaps < HOME_TAPS_REQUIRED) return Event.NONE
        homeTaps = 0
        kittenTaps = 0
        _kittenArmedFlow.value = true
        return Event.HOME_UNLOCKED
    }

    /** Se tocó cualquier otro ítem de la barra: la ráfaga en INICIO se corta. */
    @Synchronized
    fun onOtherNavPressed() {
        homeTaps = 0
    }

    /** Se tocó el gatito. Solo cuenta si está habilitado y el modo todavía no se desbloqueó. */
    @Synchronized
    fun onKittenPressed(): Event {
        if (_enabledFlow.value || !_kittenArmedFlow.value) return Event.NONE
        kittenTaps++
        return when {
            kittenTaps >= KITTEN_TAPS_REQUIRED -> {
                unlock()
                Event.DEVELOPER_ENABLED
            }
            kittenTaps == KITTEN_HINT_AT -> Event.KITTEN_HINT
            else -> Event.NONE
        }
    }

    private fun unlock() {
        resetProgress()
        // commit(): se guarda en el acto, aunque el equipo se apague enseguida.
        prefs?.edit()?.putBoolean(KEY_UNLOCKED, true)?.commit()
        _enabledFlow.value = true
    }

    private fun resetProgress() {
        homeTaps = 0
        lastHomeTapAt = 0L
        kittenTaps = 0
        _kittenArmedFlow.value = false
    }
}
