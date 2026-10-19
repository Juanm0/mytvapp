package com.quilmes.xuper.ui.screens

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

/**
 * Filtro de solicitudes seguro para el WebView.
 *
 * Se deshabilitó el wrapper nativo adblock-rust/Brave: su código JNI puede terminar el proceso
 * completo si falla y no es recuperable desde Kotlin. Conservamos el filtro local de dominios y
 * rutas publicitarias como respaldo, sin cargar bibliotecas nativas al abrir un reproductor.
 */
internal object XuperBraveAdblock {
    @Volatile private var status = "Filtro local activo (motor nativo deshabilitado)"

    // Se conserva por compatibilidad con la inicialización de MainActivity; no carga código nativo.
    fun initialize(context: Context) {
        status = "Filtro local activo (motor nativo deshabilitado)"
    }

    fun shouldBlock(request: WebResourceRequest, sourceUrl: String?): Boolean =
        shouldBlockUrl(request.url.toString(), sourceUrl)

    fun shouldBlockUrl(requestUrl: String, sourceUrl: String?, requestType: String = "document"): Boolean =
        XuperWebRequestFilter.shouldBlock(requestUrl)

    // Sin motor nativo no hay reglas cosméticas externas que aplicar.
    // El filtro de solicitudes sigue actuando en shouldInterceptRequest.
    fun injectCosmetics(webView: android.webkit.WebView, pageUrl: String) = Unit

    fun protectionStatus(): String = status

    fun blockedResponse(): WebResourceResponse = WebResourceResponse(
        "text/plain", "utf-8", 204, "Blocked by Xuper local filter", emptyMap(),
        ByteArrayInputStream(ByteArray(0))
    )
}
