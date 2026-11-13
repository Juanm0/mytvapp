package com.quilmes.xuper.data

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.quilmes.xuper.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

private const val TAG = "XuperUpdate"

/** Una versión publicada en GitHub Releases. El tag del release es `v<versionCode>` (lo pone el workflow). */
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val notes: String,
    /** URL desde donde bajar el APK (con token: la URL de la API del asset; sin token: la pública). */
    val assetUrl: String,
    val sizeBytes: Long,
)

sealed interface UpdateState {
    object Idle : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    /** percent: 0..100. */
    data class Downloading(val info: UpdateInfo, val percent: Int) : UpdateState
    /** Android pide "Instalar apps desconocidas" para Xuper; hay que permitirlo una vez. */
    data class NeedsPermission(val info: UpdateInfo, val file: File) : UpdateState
    /** El APK ya está entregado al instalador del sistema (puede mostrar su propia confirmación). */
    data class Installing(val info: UpdateInfo) : UpdateState
    data class Failed(val message: String, val info: UpdateInfo?, val file: File?) : UpdateState
}

/**
 * Actualizaciones automáticas de Xuper.
 *
 * Flujo: al abrir la app (onResume, máximo cada 30 min) se pregunta a GitHub cuál es el último release del repo
 * `BuildConfig.UPDATE_REPO`. Si su número (tag `v<N>`) es mayor que el versionCode instalado, se ofrece
 * actualizar: se descarga el APK a `cacheDir/updates/`, se comprueba que sea de Xuper y más nuevo, y se instala
 * con `PackageInstaller`. Android exige la MISMA firma que el APK instalado (por eso el keystore es fijo).
 * La confirmación final del sistema no se puede saltear en una instalación normal: hay que apretar OK en la tele.
 */
object AppUpdater {
    const val INSTALL_ACTION = "com.quilmes.xuper.INSTALL_RESULT"
    private const val CHECK_INTERVAL_MS = 30 * 60 * 1000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private var lastCheckMs = 0L
    private var job: Job? = null
    @Volatile private var lastApk: File? = null

    // ---------------------------------------------------------------- consulta

    /** Llamar desde onResume. Es barata: sin repo configurado no hace nada y, con repo, consulta como mucho cada 30 min. */
    suspend fun check(context: Context) {
        val app = context.applicationContext
        val current = _state.value
        // Volvió de Ajustes habiendo permitido la instalación: seguir sin pedir nada más.
        if (current is UpdateState.NeedsPermission && canInstall(app)) {
            job = scope.launch { install(app, current.info, current.file) }
            return
        }
        if (BuildConfig.UPDATE_REPO.isBlank()) return
        if (current !is UpdateState.Idle && current !is UpdateState.Available) return
        val now = System.currentTimeMillis()
        if (now - lastCheckMs < CHECK_INTERVAL_MS) return
        lastCheckMs = now

        val info = withContext(Dispatchers.IO) {
            runCatching { fetchLatest() }
                .onFailure { Log.w(TAG, "No se pudo consultar actualizaciones: ${it.message}") }
                .getOrNull()
        } ?: return
        val installed = installedVersionCode(app)
        if (info.versionCode > installed) {
            Log.i(TAG, "Versión nueva: ${info.versionCode} (instalada: $installed)")
            val now2 = _state.value
            if (now2 is UpdateState.Idle || now2 is UpdateState.Available) _state.value = UpdateState.Available(info)
        } else {
            Log.i(TAG, "Al día (instalada: $installed, última publicada: ${info.versionCode})")
        }
    }

    /** Borra APK viejos de una actualización anterior. Llamar al abrir la app. */
    fun cleanupOldDownloads(context: Context) {
        if (_state.value is UpdateState.Downloading || _state.value is UpdateState.Installing) return
        runCatching { File(context.cacheDir, "updates").deleteRecursively() }
    }

    // ---------------------------------------------------------------- acciones de la pantalla

    fun startUpdate(context: Context, info: UpdateInfo) {
        val app = context.applicationContext
        job?.cancel()
        job = scope.launch {
            try {
                _state.value = UpdateState.Downloading(info, 0)
                val apk = download(app, info) { percent -> _state.value = UpdateState.Downloading(info, percent) }
                install(app, info, apk)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Falló la actualización", e)
                _state.value = UpdateState.Failed(friendly(e), info, null)
            }
        }
    }

    fun continueAfterPermission(context: Context) {
        val s = _state.value as? UpdateState.NeedsPermission ?: return
        val app = context.applicationContext
        job?.cancel()
        job = scope.launch { install(app, s.info, s.file) }
    }

    fun retry(context: Context) {
        val s = _state.value as? UpdateState.Failed ?: return
        val info = s.info ?: run { _state.value = UpdateState.Idle; return }
        val apk = s.file
        if (apk != null && apk.isFile) {
            val app = context.applicationContext
            job?.cancel()
            job = scope.launch { install(app, info, apk) }
        } else {
            startUpdate(context, info)
        }
    }

    /** "Más tarde" / "Cancelar": cierra el cartel (y corta la descarga si estaba en curso). */
    fun cancelOrDismiss() {
        job?.cancel()
        _state.value = UpdateState.Idle
    }

    /** Abre la pantalla "Instalar apps desconocidas" de Xuper. Devuelve false si esta tele no la tiene. */
    fun openInstallSettings(context: Context): Boolean {
        val intents = buildList {
            if (Build.VERSION.SDK_INT >= 26) {
                add(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
            }
            add(Intent(Settings.ACTION_SECURITY_SETTINGS))
        }
        for (intent in intents) {
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(intent)
                return true
            } catch (_: Exception) {
                // probar la siguiente
            }
        }
        return false
    }

    // ---------------------------------------------------------------- resultado del instalador (UpdateInstallReceiver)

    internal fun onInstallResult(status: Int, message: String?) {
        if (status == PackageInstaller.STATUS_SUCCESS) {
            // Normalmente Android cierra la app al reemplazarla, así que esto casi no se llega a ver.
            _state.value = UpdateState.Idle
            return
        }
        val info = (_state.value as? UpdateState.Installing)?.info
        _state.value = UpdateState.Failed(describeFailure(status, message), info, lastApk)
    }

    private fun describeFailure(status: Int, message: String?): String = when (status) {
        PackageInstaller.STATUS_FAILURE_ABORTED -> "Se canceló la instalación."
        PackageInstaller.STATUS_FAILURE_CONFLICT -> "Android no pudo instalarla encima de la versión actual (firma o versión distinta)."
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "Esta actualización no es compatible con esta tele."
        PackageInstaller.STATUS_FAILURE_STORAGE -> "No hay espacio suficiente en la tele."
        PackageInstaller.STATUS_FAILURE_BLOCKED -> "Android bloqueó la instalación."
        else -> message?.takeIf { it.isNotBlank() } ?: "La instalación falló (código $status)."
    }

    // ---------------------------------------------------------------- red

    private fun openConnection(url: String, accept: String, token: String?): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = false
            setRequestProperty("User-Agent", "XuperQuilmesTV-Updater")
            setRequestProperty("Accept", accept)
            if (!token.isNullOrBlank()) setRequestProperty("Authorization", "Bearer $token")
        }

    /**
     * Abre la URL siguiendo las redirecciones a mano: el token (si hay) solo viaja a GitHub, nunca al
     * servidor de descargas al que redirige (esa URL ya viene firmada).
     */
    private fun openFollowing(startUrl: String, accept: String, token: String?): HttpURLConnection {
        var url = startUrl
        var auth = token
        repeat(6) {
            val conn = openConnection(url, accept, auth)
            val code = conn.responseCode
            if (code in 300..399) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location.isNullOrBlank()) throw IllegalStateException("Redirección sin destino")
                url = URL(URL(url), location).toString()
                auth = null
                return@repeat
            }
            return conn
        }
        throw IllegalStateException("Demasiadas redirecciones")
    }

    private fun fetchLatest(): UpdateInfo? {
        val token = BuildConfig.UPDATE_TOKEN.ifBlank { null }
        val api = "https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest"
        val conn = openFollowing(api, "application/vnd.github+json", token)
        try {
            val code = conn.responseCode
            if (code == 404) {
                Log.i(TAG, "Sin releases publicados (o el repo es privado y falta el token)")
                return null
            }
            if (code !in 200..299) throw IllegalStateException("GitHub respondió HTTP $code")
            val json = JSONObject(conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
            val versionCode = Regex("^v?(\\d+)$").find(json.optString("tag_name").trim())
                ?.groupValues?.get(1)?.toIntOrNull() ?: return null
            val assets = json.optJSONArray("assets") ?: return null
            val asset = (0 until assets.length())
                .mapNotNull { assets.optJSONObject(it) }
                .firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) }
                ?: return null
            val url = if (token != null) asset.optString("url") else asset.optString("browser_download_url")
            if (url.isBlank()) return null
            return UpdateInfo(
                versionCode = versionCode,
                versionName = "0.1.$versionCode",
                notes = json.optString("body").trim(),
                assetUrl = url,
                sizeBytes = asset.optLong("size", 0L),
            )
        } finally {
            conn.disconnect()
        }
    }

    private suspend fun download(app: Context, info: UpdateInfo, onProgress: (Int) -> Unit): File =
        withContext(Dispatchers.IO) {
            val dir = File(app.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val part = File(dir, "Xuper-${info.versionCode}.part")
            val target = File(dir, "Xuper-${info.versionCode}.apk")

            val token = BuildConfig.UPDATE_TOKEN.ifBlank { null }
            val accept = if (token != null) "application/octet-stream" else "*/*"
            val conn = openFollowing(info.assetUrl, accept, token)
            try {
                val code = conn.responseCode
                if (code !in 200..299) throw IllegalStateException("La descarga falló (HTTP $code)")
                val total = conn.contentLengthLong.takeIf { it > 0 } ?: info.sizeBytes
                conn.inputStream.use { input ->
                    part.outputStream().use { out ->
                        val buffer = ByteArray(64 * 1024)
                        var done = 0L
                        var lastPercent = -1
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            done += n
                            ensureActive()
                            if (total > 0) {
                                val percent = (done * 100 / total).toInt().coerceIn(0, 100)
                                if (percent != lastPercent) {
                                    lastPercent = percent
                                    onProgress(percent)
                                }
                            }
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }
            if (info.sizeBytes > 0 && part.length() != info.sizeBytes) {
                part.delete()
                throw IllegalStateException("La descarga quedó incompleta. Probá de nuevo.")
            }
            if (!part.renameTo(target)) throw IllegalStateException("No se pudo guardar el archivo descargado")
            validate(app, target)
            target
        }

    // ---------------------------------------------------------------- instalación

    /** Defensa extra antes de entregarlo a Android: tiene que ser un APK de Xuper y más nuevo que el instalado. */
    private fun validate(app: Context, apk: File) {
        val archive = app.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)
            ?: throw IllegalStateException("El archivo descargado no es un APK válido")
        if (archive.packageName != app.packageName) throw IllegalStateException("El APK descargado es de otra aplicación")
        if (versionCodeOf(archive) <= installedVersionCode(app)) {
            throw IllegalStateException("El APK descargado no es más nuevo que el instalado")
        }
    }

    fun installedVersionCode(context: Context): Int =
        versionCodeOf(context.packageManager.getPackageInfo(context.packageName, 0))

    @Suppress("DEPRECATION")
    private fun versionCodeOf(info: PackageInfo): Int =
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else info.versionCode

    private fun canInstall(app: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || app.packageManager.canRequestPackageInstalls()

    private suspend fun install(app: Context, info: UpdateInfo, apk: File) {
        lastApk = apk
        if (!canInstall(app)) {
            _state.value = UpdateState.NeedsPermission(info, apk)
            return
        }
        _state.value = UpdateState.Installing(info)
        try {
            withContext(Dispatchers.IO) { commitSession(app, apk) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo entregar el APK al instalador", e)
            _state.value = UpdateState.Failed(friendly(e), info, apk)
        }
    }

    private fun commitSession(app: Context, apk: File) {
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
            // Android 12+: si Xuper fue quien instaló la versión anterior, puede actualizarse sin pedir confirmación.
            // Si no se cumplen las condiciones del sistema, simplemente pide confirmación como siempre.
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("xuper.apk", 0, apk.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            val intent = Intent(app, UpdateInstallReceiver::class.java).setAction(INSTALL_ACTION)
            var flags = PendingIntent.FLAG_UPDATE_CURRENT
            // El sistema agrega extras (estado, intent de confirmación): en Android 12+ tiene que ser mutable.
            if (Build.VERSION.SDK_INT >= 31) flags = flags or PendingIntent.FLAG_MUTABLE
            val pending = PendingIntent.getBroadcast(app, sessionId, intent, flags)
            session.commit(pending.intentSender)
        }
    }

    private fun friendly(e: Exception): String = when (e) {
        is UnknownHostException, is ConnectException, is SocketTimeoutException ->
            "No se pudo conectar. Revisá el internet de la tele."
        else -> e.message?.takeIf { it.isNotBlank() } ?: "Error inesperado (${e.javaClass.simpleName})."
    }
}
