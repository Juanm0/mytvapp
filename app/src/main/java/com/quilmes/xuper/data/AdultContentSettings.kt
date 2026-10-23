package com.quilmes.xuper.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * ÚNICO lugar que decide si se muestra contenido adulto ("+18").
 *
 * - Apagado por defecto. Se activa/desactiva desde Perfil; activarlo exige PIN.
 * - Los servicios de TMDb leen [isEnabled] (SharedPreferences) para el parámetro `include_adult`.
 * - Solo aplica al buscador, a las fichas y a la sección +18; las filas de catálogo de Inicio
 *   siempre piden `include_adult=false`.
 * - El PIN se guarda con sal + SHA-256 (nunca en texto plano).
 */
object AdultContentSettings {
    enum class PinResult { OK, WRONG, LOCKED }

    private const val PREFS = "xuper-settings"
    private const val KEY_ENABLED = "adult_enabled"
    private const val KEY_PIN_HASH = "adult_pin_hash"
    private const val KEY_PIN_SALT = "adult_pin_salt"
    private const val KEY_ADULT_IDS = "adult_ids"
    private const val MAX_ATTEMPTS = 5
    private const val LOCK_MS = 60_000L
    const val PIN_LENGTH = 4

    private val lock = Any()
    @Volatile private var prefs: SharedPreferences? = null
    private val _enabledFlow = MutableStateFlow(false)
    /** Para que la UI (barra lateral, Perfil) reaccione al instante. */
    val enabledFlow: StateFlow<Boolean> = _enabledFlow.asStateFlow()
    private var failedAttempts = 0
    private var lockedUntil = 0L

    /** Llamar una vez al arrancar (MainActivity.onCreate), antes de usar los servicios. */
    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _enabledFlow.value = isEnabled()
    }

    fun isEnabled(): Boolean = prefs?.getBoolean(KEY_ENABLED, false) ?: false

    fun hasPin(): Boolean = prefs?.getString(KEY_PIN_HASH, null).isNullOrBlank().not()

    /** Desactivar no pide PIN (ocultar siempre es seguro). Activar: usar [enableWithPin] o [createPinAndEnable]. */
    fun disable() = setEnabled(false)

    fun enableWithPin(pin: String): PinResult {
        val result = verifyPin(pin)
        if (result == PinResult.OK) setEnabled(true)
        return result
    }

    /** Primera vez: crea el PIN y activa. */
    fun createPinAndEnable(pin: String): Boolean {
        if (!setPin(pin)) return false
        setEnabled(true)
        return true
    }

    fun setPin(pin: String): Boolean {
        if (!isValidPin(pin)) return false
        val p = prefs ?: return false
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }.toHex()
        p.edit().putString(KEY_PIN_SALT, salt).putString(KEY_PIN_HASH, hash(salt, pin)).apply()
        return true
    }

    fun verifyPin(pin: String): PinResult = synchronized(lock) {
        val now = System.currentTimeMillis()
        if (now < lockedUntil) return PinResult.LOCKED
        val p = prefs ?: return PinResult.WRONG
        val salt = p.getString(KEY_PIN_SALT, null).orEmpty()
        val expected = p.getString(KEY_PIN_HASH, null).orEmpty()
        val ok = expected.isNotEmpty() &&
            MessageDigest.isEqual(hash(salt, pin).toByteArray(), expected.toByteArray())
        if (ok) { failedAttempts = 0; PinResult.OK }
        else {
            failedAttempts++
            if (failedAttempts >= MAX_ATTEMPTS) { failedAttempts = 0; lockedUntil = now + LOCK_MS; PinResult.LOCKED }
            else PinResult.WRONG
        }
    }

    fun lockSecondsLeft(): Int = ((lockedUntil - System.currentTimeMillis()) / 1000L + 1).toInt().coerceAtLeast(0)

    private fun setEnabled(value: Boolean) {
        prefs?.edit()?.putBoolean(KEY_ENABLED, value)?.apply()
        _enabledFlow.value = value
    }

    // ---- Ids de títulos marcados como adultos por TMDb (para ocultarlos de Mi lista, etc. al apagar) ----

    fun recordAdultIds(ids: Collection<String>) {
        if (ids.isEmpty()) return
        val p = prefs ?: return
        synchronized(lock) {
            val current = p.getStringSet(KEY_ADULT_IDS, emptySet()).orEmpty().toMutableSet()
            if (current.addAll(ids)) p.edit().putStringSet(KEY_ADULT_IDS, current).apply()
        }
    }

    /** true si el ajuste está apagado y el título es (o fue visto como) adulto: no debe mostrarse. */
    fun isHiddenAdult(title: Title): Boolean = !isEnabled() &&
        (title.adult || prefs?.getStringSet(KEY_ADULT_IDS, emptySet())?.contains(title.id) == true)

    fun isHiddenAdultId(id: String): Boolean = !isEnabled() &&
        prefs?.getStringSet(KEY_ADULT_IDS, emptySet())?.contains(id) == true

    // ---- Parámetro include_adult sin duplicar ----

    /**
     * Devuelve [pathOrUrl] con EXACTAMENTE un `include_adult=<valor>`: si ya traía uno
     * (en cualquier posición de la query) lo reemplaza, así nunca queda duplicado.
     */
    fun applyIncludeAdult(pathOrUrl: String, include: Boolean): String {
        val q = pathOrUrl.indexOf('?')
        val base = if (q < 0) pathOrUrl else pathOrUrl.substring(0, q)
        val params = if (q < 0) emptyList() else pathOrUrl.substring(q + 1).split('&')
            .filter { it.isNotEmpty() && !it.startsWith("include_adult=") }
        return base + "?" + (params + "include_adult=$include").joinToString("&")
    }

    private fun isValidPin(pin: String) = pin.length == PIN_LENGTH && pin.all { it in '0'..'9' }
    private fun hash(salt: String, pin: String) =
        MessageDigest.getInstance("SHA-256").digest("$salt:$pin".toByteArray()).toHex()
    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}
