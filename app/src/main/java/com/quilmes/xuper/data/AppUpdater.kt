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

/** A published GitHub Release. Its tag is v<versionCode>. */
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val notes: String,
    val assetUrl: String,
    val sizeBytes: Long,
)

sealed interface UpdateState {
    /** The app starts locked until GitHub confirms that this installation is current. */
    object Checking : UpdateState
    object Idle : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data class Downloading(val info: UpdateInfo, val percent: Int) : UpdateState
    data class NeedsPermission(val info: UpdateInfo, val file: File) : UpdateState
    data class Installing(val info: UpdateInfo) : UpdateState
    /** info is null when the release check itself failed; retry then checks GitHub again. */
    data class Failed(val message: String, val info: UpdateInfo?, val file: File?) : UpdateState
}

/** Mandatory GitHub Releases updater. Android remains responsible for its installer/security confirmation. */
object AppUpdater {
    const val INSTALL_ACTION = "com.quilmes.xuper.INSTALL_RESULT"
    private const val CHECK_INTERVAL_MS = 30 * 60 * 1000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Checking)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private var lastSuccessfulCheckMs = 0L
    private var checkInProgress = false
    private var job: Job? = null
    @Volatile private var lastApk: File? = null

    /** Called from Activity.onResume. The initial process check is never throttled. */
    suspend fun check(context: Context, force: Boolean = false) {
        val app = context.applicationContext
        when (val current = _state.value) {
            is UpdateState.NeedsPermission -> {
                if (canInstall(app)) {
                    job?.cancel()
                    job = scope.launch { install(app, current.info, current.file) }
                }
                return
            }
            UpdateState.Checking -> Unit
            UpdateState.Idle -> Unit
            is UpdateState.Failed -> if (force && current.info == null) Unit else return
            else -> return
        }
        if (checkInProgress) return
        val now = System.currentTimeMillis()
        if (!force && lastSuccessfulCheckMs != 0L && now - lastSuccessfulCheckMs < CHECK_INTERVAL_MS) return

        checkInProgress = true
        _state.value = UpdateState.Checking
        try {
            val info = withContext(Dispatchers.IO) { fetchLatest() }
            lastSuccessfulCheckMs = System.currentTimeMillis()
            val installed = installedVersionCode(app)
            if (info.versionCode > installed) {
                Log.i(TAG, "Versión nueva: ${info.versionCode} (instalada: $installed)")
                _state.value = UpdateState.Available(info)
            } else {
                Log.i(TAG, "Al día (instalada: $installed, última publicada: ${info.versionCode})")
                _state.value = UpdateState.Idle
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo verificar la versión; Xuper permanece bloqueado", e)
            _state.value = UpdateState.Failed(friendly(e), null, null)
        } finally {
            checkInProgress = false
        }
    }

    fun cleanupOldDownloads(context: Context) {
        if (_state.value is UpdateState.Downloading || _state.value is UpdateState.Installing) return
        runCatching { File(context.cacheDir, "updates").deleteRecursively() }
    }

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
        val current = _state.value as? UpdateState.NeedsPermission ?: return
        val app = context.applicationContext
        if (!canInstall(app)) return
        job?.cancel()
        job = scope.launch { install(app, current.info, current.file) }
    }

    /** The only action offered after any update/check/install failure. */
    fun retry(context: Context) {
        val failed = _state.value as? UpdateState.Failed ?: return
        val info = failed.info
        val apk = failed.file
        if (info != null && apk != null && apk.isFile) {
            val app = context.applicationContext
            job?.cancel()
            job = scope.launch { install(app, info, apk) }
        } else if (info != null) {
            startUpdate(context, info)
        } else {
            scope.launch { check(context.applicationContext, force = true) }
        }
    }

    /** Opens Android's per-source install permission screen; it never bypasses that permission. */
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
                // Try the next Android settings entry point.
            }
        }
        return false
    }

    internal fun onInstallResult(status: Int, message: String?) {
        if (status == PackageInstaller.STATUS_SUCCESS) {
            // Android normally replaces/closes the process. If it stays alive, the verified APK is installed.
            _state.value = UpdateState.Idle
            lastSuccessfulCheckMs = System.currentTimeMillis()
            return
        }
        val info = (_state.value as? UpdateState.Installing)?.info
        _state.value = UpdateState.Failed(describeFailure(status, message), info, lastApk)
    }

    private fun describeFailure(status: Int, message: String?): String = when (status) {
        PackageInstaller.STATUS_FAILURE_ABORTED -> "Android canceló la instalación. Podés reintentar."
        PackageInstaller.STATUS_FAILURE_CONFLICT -> "Android no pudo instalarla encima de la versión actual (firma o versión distinta)."
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "Esta actualización no es compatible con esta tele."
        PackageInstaller.STATUS_FAILURE_STORAGE -> "No hay espacio suficiente en la tele."
        PackageInstaller.STATUS_FAILURE_BLOCKED -> "Android bloqueó la instalación por sus controles de seguridad."
        else -> message?.takeIf { it.isNotBlank() } ?: "La instalación falló (código $status)."
    }

    private fun openConnection(url: String, accept: String, token: String?): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = false
            setRequestProperty("User-Agent", "XuperQuilmesTV-Updater")
            setRequestProperty("Accept", accept)
            if (!token.isNullOrBlank()) setRequestProperty("Authorization", "Bearer $token")
        }

    /** The GitHub token is never forwarded to the redirected asset host. */
    private fun openFollowing(startUrl: String, accept: String, token: String?): HttpURLConnection {
        var url = startUrl
        var auth = token
        repeat(6) {
            val conn = openConnection(url, accept, auth)
            val code = conn.responseCode
            if (code in 300..399) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location.isNullOrBlank()) throw IllegalStateException("GitHub devolvió una redirección sin destino")
                url = URL(URL(url), location).toString()
                auth = null
            } else {
                return conn
            }
        }
        throw IllegalStateException("Demasiadas redirecciones al consultar GitHub")
    }

    private fun fetchLatest(): UpdateInfo {
        val repo = BuildConfig.UPDATE_REPO.trim()
        require(Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$").matches(repo)) {
            "No está configurado UPDATE_REPO. La actualización obligatoria no puede verificarse."
        }
        val token = BuildConfig.UPDATE_TOKEN.ifBlank { null }
        val api = "https://api.github.com/repos/$repo/releases/latest"
        val conn = openFollowing(api, "application/vnd.github+json", token)
        try {
            val code = conn.responseCode
            if (code == 404) throw IllegalStateException("GitHub no encontró un Release publicado. Xuper no puede verificar esta versión.")
            if (code !in 200..299) throw IllegalStateException("GitHub respondió HTTP $code al consultar actualizaciones")
            val json = JSONObject(conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
            val versionCode = Regex("^v?(\\d+)$").find(json.optString("tag_name").trim())
                ?.groupValues?.get(1)?.toIntOrNull()
                ?: throw IllegalStateException("El último Release no tiene un tag v<versionCode> válido")
            val assets = json.optJSONArray("assets") ?: throw IllegalStateException("El Release no contiene APK")
            val asset = (0 until assets.length())
                .mapNotNull { assets.optJSONObject(it) }
                .firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) }
                ?: throw IllegalStateException("El último Release no contiene un archivo APK")
            val url = if (token != null) asset.optString("url") else asset.optString("browser_download_url")
            if (url.isBlank()) throw IllegalStateException("GitHub no informó la URL del APK")
            return UpdateInfo(
                versionCode = versionCode,
                versionName = json.optString("name").ifBlank { "0.1.$versionCode" },
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
            val conn = openFollowing(info.assetUrl, if (token != null) "application/octet-stream" else "*/*", token)
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
            validate(app, target, info.versionCode)
            target
        }

    private fun validate(app: Context, apk: File, expectedVersionCode: Int) {
        val archive = app.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)
            ?: throw IllegalStateException("El archivo descargado no es un APK válido")
        if (archive.packageName != app.packageName) throw IllegalStateException("El APK descargado es de otra aplicación")
        val apkVersionCode = versionCodeOf(archive)
        if (apkVersionCode != expectedVersionCode) {
            throw IllegalStateException("El versionCode del APK no coincide con el tag del Release.")
        }
        if (apkVersionCode <= installedVersionCode(app)) {
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
            if (Build.VERSION.SDK_INT >= 31) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("xuper.apk", 0, apk.length()).use { output ->
                    input.copyTo(output)
                    session.fsync(output)
                }
            }
            val intent = Intent(app, UpdateInstallReceiver::class.java).setAction(INSTALL_ACTION)
            var flags = PendingIntent.FLAG_UPDATE_CURRENT
            if (Build.VERSION.SDK_INT >= 31) flags = flags or PendingIntent.FLAG_MUTABLE
            val pending = PendingIntent.getBroadcast(app, sessionId, intent, flags)
            session.commit(pending.intentSender)
        }
    }

    private fun friendly(e: Exception): String = when (e) {
        is UnknownHostException, is ConnectException, is SocketTimeoutException ->
            "No se pudo conectar con GitHub. Revisá el internet de la tele y reintentá."
        else -> e.message?.takeIf { it.isNotBlank() } ?: "Error inesperado (${e.javaClass.simpleName})."
    }
}
