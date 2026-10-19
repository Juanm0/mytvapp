package com.quilmes.xuper.ui.screens

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import org.mlm.adblock.AdblockEngine
import org.mlm.adblock.RequestTypeMapper
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Integración de adblock-rust (el motor de Brave) para el WebView de Xuper.
 * Descarga y cachea listas en segundo plano; mantiene el filtro local anterior como respaldo.
 */
internal object XuperBraveAdblock {
    private const val TAG = "XuperBraveAdblock"
    private const val MAX_FILTER_BYTES = 8 * 1024 * 1024
    private const val FILTER_MAX_AGE_MS = 24L * 60 * 60 * 1000

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val starting = AtomicBoolean(false)
    private val engineLock = Any()
    @Volatile private var engine: AdblockEngine? = null
    @Volatile private var initialized = false
    @Volatile private var status = "Preparando protección…"

    private val filterUrls = listOf(
        "https://easylist.to/easylist/easylist.txt",
        "https://easylist.to/easylist/easyprivacy.txt",
        "https://filters.adtidy.org/extension/chromium/filters/14.txt",
    )
    private const val RESOURCES_URL = "https://raw.githubusercontent.com/brave/adblock-resources/master/dist/resources.json"

    // Reglas pequeñas de emergencia: se aplican incluso si no hay Internet para bajar las listas.
    private val fallbackRules = """
        ||doubleclick.net^
        ||googlesyndication.com^
        ||googleadservices.com^
        ||adnxs.com^
        ||taboola.com^
        ||outbrain.com^
        ||popads.net^
        ||popcash.net^
        ||propellerads.com^
        ||exoclick.com^
        ||trafficjunky.com^
        ||onclickads.net^
        ||clickadu.com^
        ||hilltopads.net^
        ||adsterra.com^
        ||admaven.com^
        ||monetag.com^
        ||googletagmanager.com^
        ||google-analytics.com^
        ||hotjar.com^
        ||scorecardresearch.com^
        ||quantserve.com^
    """.trimIndent()

    fun initialize(context: Context) {
        if (initialized || !starting.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        executor.execute {
            try {
                val cacheDir = File(appContext.filesDir, "brave-adblock").apply { mkdirs() }
                val cacheFile = File(cacheDir, "filters.txt")
                val cached = if (cacheFile.exists()) runCatching { cacheFile.readText(Charsets.UTF_8) }.getOrDefault("") else ""
                val resourcesFile = File(cacheDir, "resources.json")
                val cachedResources = if (resourcesFile.exists()) runCatching { resourcesFile.readText(Charsets.UTF_8) }.getOrDefault("") else ""
                val now = System.currentTimeMillis()
                val filtersFresh = cacheFile.exists() && now - cacheFile.lastModified() < FILTER_MAX_AGE_MS
                val resourcesFresh = resourcesFile.exists() && now - resourcesFile.lastModified() < FILTER_MAX_AGE_MS

                // Activar primero con caché/reglas de emergencia; la descarga no bloquea la interfaz.
                val initialRules = cached.takeIf { it.isNotBlank() } ?: fallbackRules
                installEngine(appContext, initialRules, cachedResources)
                status = if (cached.isNotBlank()) "Brave adblock activo · listas en caché" else "Brave adblock activo · filtro de respaldo"

                val downloaded = if (filtersFresh) "" else filterUrls.mapNotNull { fetchFilter(it) }.joinToString("\n")
                val freshResources = if (resourcesFresh) null else fetchText(RESOURCES_URL, MAX_FILTER_BYTES)
                    ?.takeIf { it.trimStart().startsWith("[") || it.trimStart().startsWith("{") }
                if (!freshResources.isNullOrBlank()) runCatching { resourcesFile.writeText(freshResources, Charsets.UTF_8) }
                val combined = if (downloaded.isNotBlank()) "$downloaded\n\n$fallbackRules" else initialRules
                if (downloaded.isNotBlank()) runCatching { cacheFile.writeText(combined, Charsets.UTF_8) }
                if (downloaded.isNotBlank() || !freshResources.isNullOrBlank()) {
                    installEngine(appContext, combined, freshResources ?: cachedResources)
                    status = if (downloaded.isNotBlank()) "Brave adblock activo · listas actualizadas" else "Brave adblock activo · recursos actualizados"
                }
                initialized = true
            } catch (t: Throwable) {
                android.util.Log.e(TAG, "No se pudo iniciar adblock-rust; se conserva el filtro de respaldo", t)
                status = "Filtro de respaldo activo"
            } finally {
                starting.set(false)
            }
        }
    }

    private fun installEngine(context: Context, rules: String, resourcesOverride: String = "") {
        val newEngine = AdblockEngine.create()
        val loaded = newEngine.loadFilterList(rules)
        // Scriptlets/redirects de Brave; si no hay red, se usa el recurso cacheado o el asset vacío.
        val resources = resourcesOverride.takeIf { it.isNotBlank() } ?: runCatching {
            context.assets.open("adblock/resources.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrDefault("[]")
        runCatching { newEngine.loadResources(resources) }
        if (!loaded) {
            newEngine.close()
            throw IllegalStateException("adblock-rust no pudo cargar las reglas")
        }
        synchronized(engineLock) {
            val previous = engine
            engine = newEngine
            previous?.close()
        }
    }

    private fun fetchFilter(address: String): String? = fetchText(address, MAX_FILTER_BYTES)
        ?.takeIf { it.contains("||") || it.contains("##") }

    private fun fetchText(address: String, maxBytes: Int): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(address).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 12_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "XuperQuilmesTV/1.0 (adblock list updater)")
            }
            if (connection.responseCode !in 200..299) return null
            val output = StringBuilder()
            connection.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    if (output.length < maxBytes) output.append(line).append('\n')
                }
            }
            output.toString().takeIf { it.isNotBlank() }
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "No se pudo actualizar un recurso de protección: $address", t)
            null
        } finally {
            connection?.disconnect()
        }
    }

    fun shouldBlock(request: WebResourceRequest, sourceUrl: String?): Boolean {
        val requestUrl = request.url.toString()
        val accept = request.requestHeaders.entries.firstOrNull { it.key.equals("Accept", true) }?.value
        val type = RequestTypeMapper.from(request.url, accept)
        return shouldBlockUrl(requestUrl, sourceUrl, type)
    }

    fun shouldBlockUrl(requestUrl: String, sourceUrl: String?, requestType: String = "document"): Boolean {
        if (engine != null) {
            val decision = synchronized(engineLock) {
                engine?.checkNetworkRequest(requestUrl, sourceUrl.orEmpty(), requestType)
            }
            // Una excepción de la lista tiene prioridad: el filtro local no debe contradecirla.
            if (decision?.exception == true) return false
            if (decision?.matched == true) return true
        }
        return XuperWebRequestFilter.shouldBlock(requestUrl)
    }

    fun injectCosmetics(webView: android.webkit.WebView, pageUrl: String) {
        if (engine == null) return
        val cosmetics = synchronized(engineLock) { engine?.cosmeticResources(pageUrl) } ?: return
        if (cosmetics.selectors.isEmpty() && cosmetics.js.isBlank()) return
        val css = cosmetics.css()
        val cssLiteral = JSONObject.quote(css)
        // Scriptlets are authored by filter lists; insert only after the native engine has parsed them.
        val script = """(function(){try{
          var css=$cssLiteral;
          if(css){var style=document.getElementById('xuper-brave-cosmetics');if(!style){style=document.createElement('style');style.id='xuper-brave-cosmetics';(document.head||document.documentElement).appendChild(style);}style.textContent=css;}
          ${cosmetics.js}
        }catch(e){}})();"""
        webView.evaluateJavascript(script, null)
    }

    fun protectionStatus(): String = status

    fun blockedResponse(): WebResourceResponse = WebResourceResponse(
        "text/plain", "utf-8", 204, "Blocked by Brave adblock-rust", emptyMap(), ByteArrayInputStream(ByteArray(0))
    )
}
