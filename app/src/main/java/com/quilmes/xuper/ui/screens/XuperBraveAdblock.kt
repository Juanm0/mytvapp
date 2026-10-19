package com.quilmes.xuper.ui.screens

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

/**
 * Filtro publicitario sin dependencia nativa externa.
 * Conserva la interfaz usada por WebView y delega el bloqueo al filtro local Xuper.
 */
internal object XuperBraveAdblock {
    @Volatile private var status = "Filtro local de Xuper activo"

    fun initialize(context: Context) {
        // No requiere inicialización ni descargas para empezar a filtrar solicitudes.
        status = "Filtro local de Xuper activo"
    }

    fun shouldBlock(request: WebResourceRequest, sourceUrl: String?): Boolean =
        XuperWebRequestFilter.shouldBlock(request.url.toString())

    fun shouldBlockUrl(requestUrl: String, sourceUrl: String?, requestType: String = "document"): Boolean =
        XuperWebRequestFilter.shouldBlock(requestUrl)

    fun injectCosmetics(webView: android.webkit.WebView, pageUrl: String) {
        // Sin motor de listas cosméticas, no se inyectan reglas de CSS/JS.
    }

    fun protectionStatus(): String = status

    fun blockedResponse(): WebResourceResponse = WebResourceResponse(
        "text/plain", "utf-8", 204, "Blocked by Xuper", emptyMap(), ByteArrayInputStream(ByteArray(0))
    )
}
