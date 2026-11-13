package com.quilmes.xuper.ui.screens

import android.annotation.SuppressLint
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.tv.material3.Text
import com.quilmes.xuper.data.AdultCatalogService
import com.quilmes.xuper.data.AdultContentSettings
import com.quilmes.xuper.ui.components.Button
import kotlinx.coroutines.delay

/**
 * Reproductor +18: aloja el embed OFICIAL (https://www.pornhub.com/embed/{viewkey}) dentro de Xuper, sin abrir
 * ningún navegador. No se extrae M3U8 ni MP4: el reproductor del sitio gestiona la reproducción y sus accesos.
 *
 * Mando: OK = reproducir/pausar · ◀ ▶ = ±10 s (mantener = ±30 s) · ATRÁS = volver al catálogo.
 * Las flechas se consumen acá (no llegan a la página), así que no hay forma de quedar "atrapado" en el iframe.
 * La navegación del WebView está limitada al embed: no se puede salir a otras páginas del sitio ni abrir popups.
 * Si el perfil +18 se bloquea mientras se mira, el reproductor se cierra solo.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun AdultEmbedPlayerScreen(viewKey: String, title: String, onExit: () -> Unit) {
    val enabled by AdultContentSettings.enabledFlow.collectAsState()
    LaunchedEffect(enabled) { if (!enabled) onExit() }
    BackHandler { onExit() }

    if (!AdultCatalogService.isValidViewKey(viewKey)) {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("No se pudo abrir este video.", color = Color.White, fontSize = 20.sp)
                Button(onClick = onExit) { Text("VOLVER") }
            }
        }
        return
    }

    val embedUrl = remember(viewKey) { AdultCatalogService.embedUrl(viewKey) }
    val rootFocus = remember { FocusRequester() }
    var webView by remember(viewKey) { mutableStateOf<WebView?>(null) }
    var hint by remember(viewKey) { mutableStateOf(true) }
    var heldMs by remember { mutableStateOf(0L) }

    LaunchedEffect(viewKey) {
        runCatching { rootFocus.requestFocus() }
        delay(7_000)
        hint = false
    }
    DisposableEffect(viewKey) {
        onDispose {
            webView?.let { view ->
                runCatching {
                    view.stopLoading()
                    view.loadUrl("about:blank")
                    (view.parent as? ViewGroup)?.removeView(view)
                    view.destroy()
                }
            }
            webView = null
        }
    }

    /** OK/seek sobre el <video> de la página; si no hay <video> todavía (portada con botón Play), toca el centro. */
    fun runCommand(view: WebView, command: String, seconds: Int) {
        val js = """
            (function(){
              function vids(doc){ var out=[]; try{ out=out.concat([].slice.call(doc.querySelectorAll('video')));
                [].slice.call(doc.querySelectorAll('iframe')).forEach(function(f){ try{ if(f.contentDocument) out=out.concat(vids(f.contentDocument)); }catch(e){} }); }catch(e){} return out; }
              var list = vids(document);
              var v = list.filter(function(x){ return x.readyState > 0 || x.currentSrc; })[0] || list[0];
              if (!v) return 'none';
              if ('$command' === 'toggle') { if (v.paused) { var p = v.play(); if (p && p.catch) p.catch(function(){}); } else { v.pause(); } }
              else { try { v.currentTime = Math.max(0, v.currentTime + ($seconds)); } catch(e) {} }
              return 'ok';
            })()
        """.trimIndent()
        view.evaluateJavascript(js) { result ->
            if (result?.contains("none") == true) tapCenter(view)
        }
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black).focusRequester(rootFocus).focusable()
            .onPreviewKeyEvent { event ->
                val view = webView ?: return@onPreviewKeyEvent false
                val isOk = event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter
                val isSeek = event.key == Key.DirectionLeft || event.key == Key.DirectionRight
                val isNav = isSeek || event.key == Key.DirectionUp || event.key == Key.DirectionDown
                if (!isOk && !isNav) return@onPreviewKeyEvent false
                if (event.type == KeyEventType.KeyDown) {
                    hint = true
                    when {
                        isOk -> if (event.nativeKeyEvent.repeatCount == 0) runCommand(view, "toggle", 0)
                        isSeek -> {
                            val repeat = event.nativeKeyEvent.repeatCount
                            val step = if (repeat >= 3) 30 else 10
                            if (repeat == 0 || repeat % 3 == 0) runCommand(view, "seek", if (event.key == Key.DirectionLeft) -step else step)
                        }
                    }
                    heldMs = SystemClock.uptimeMillis()
                }
                true
            }
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                WebView(context).apply {
                    setBackgroundColor(android.graphics.Color.BLACK)
                    // El foco lo maneja Compose (las teclas se traducen a comandos del <video>), no el WebView.
                    isFocusable = false
                    isFocusableInTouchMode = false
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    settings.javaScriptCanOpenWindowsAutomatically = false
                    settings.setSupportMultipleWindows(false)
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                                        val embedView = this
                    CookieManager.getInstance().apply {
                        setAcceptCookie(true)
                        setAcceptThirdPartyCookies(embedView, true)
                        setCookie("https://www.pornhub.com", "accessAgeDisclaimerPH=1")
                    }
                    webChromeClient = WebChromeClient()
                    webViewClient = object : WebViewClient() {
                        // Solo se permite el propio embed: nada de salir a otras páginas ni abrir apps externas.
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            val uri = request?.url ?: return true
                            val host = uri.host.orEmpty().lowercase()
                            val allowed = (uri.scheme == "https") && (host == "www.pornhub.com" || host == "pornhub.com") &&
                                uri.path.orEmpty().startsWith("/embed/")
                            return !allowed
                        }
                    }
                    webView = this
                    loadUrl(embedUrl)
                }
            },
        )
        if (hint) {
            Column(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp)
                    .background(Color(0xCC14202B), RoundedCornerShape(10.dp)).padding(horizontal = 18.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text("OK reproducir/pausar  ·  ◀ ▶ ±10 s  ·  ATRÁS volver al catálogo", color = Color(0xFFB4C0CE), fontSize = 13.sp)
            }
        }
    }
}

/** Toque real en el centro de la vista (llega también a iframes de otro origen), como un clic. */
private fun tapCenter(view: WebView) {
    if (view.width <= 0 || view.height <= 0) return
    val x = view.width / 2f
    val y = view.height / 2f
    val now = SystemClock.uptimeMillis()
    val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
    val up = MotionEvent.obtain(now, now + 60, MotionEvent.ACTION_UP, x, y, 0)
    runCatching {
        view.dispatchTouchEvent(down)
        view.dispatchTouchEvent(up)
    }
    down.recycle()
    up.recycle()
}
