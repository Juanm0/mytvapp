package com.quilmes.xuper.ui.screens

import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.net.URI
import java.util.Locale

/**
 * Filtro ligero para el WebView del reproductor. No reemplaza las listas extensas de Brave/uBlock:
 * cubre dominios publicitarios y de seguimiento conocidos y se aplica a solicitudes HTTP(S).
 */
internal object XuperWebRequestFilter {
    private val blockedHosts = setOf(
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "adservice.google.com",
        "adnxs.com",
        "adform.net",
        "adroll.com",
        "adsrvr.org",
        "adthrive.com",
        "taboola.com",
        "outbrain.com",
        "popads.net",
        "popcash.net",
        "propellerads.com",
        "exoclick.com",
        "trafficjunky.net",
        "juicyads.com",
        "onclickads.net",
        "clickadu.com",
        "hilltopads.net",
        "adsterra.com",
        "admaven.com",
        "pushground.com",
        "monetag.com",
        "analytics.google.com",
        "googletagmanager.com",
        "google-analytics.com",
        "hotjar.com",
        "clarity.ms",
        "scorecardresearch.com",
        "quantserve.com",
        "segment.io",
    )

    private val blockedUrlFragments = listOf(
        "/adsbygoogle.js",
        "/pagead/",
        "/pagead2/",
        "/adservice/",
        "/popunder",
        "/popunder/",
        "/popup-ad",
        "/tracking-pixel",
    )

    fun shouldBlock(rawUrl: String): Boolean {
        val uri = runCatching { URI(rawUrl) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return false
        if (scheme != "http" && scheme != "https") return false
        val host = uri.host?.lowercase(Locale.ROOT)?.trimEnd('.') ?: return false
        if (blockedHosts.any { host == it || host.endsWith(".$it") }) return true
        val pathAndQuery = ((uri.rawPath ?: "") + "?" + (uri.rawQuery ?: "")).lowercase(Locale.ROOT)
        return blockedUrlFragments.any { pathAndQuery.contains(it) }
    }

    fun emptyResponse(): WebResourceResponse = WebResourceResponse(
        "text/plain",
        "utf-8",
        204,
        "Blocked by Xuper",
        emptyMap(),
        ByteArrayInputStream(ByteArray(0)),
    )
}
