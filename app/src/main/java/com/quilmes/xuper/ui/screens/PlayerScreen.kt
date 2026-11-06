package com.quilmes.xuper.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.util.TypedValue
import android.view.KeyEvent as AndroidKeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.TrackSelectionDialogBuilder
import com.quilmes.xuper.ui.components.Button
import androidx.tv.material3.Text
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import coil.compose.AsyncImage
import com.quilmes.xuper.data.ChannelQueueStore
import com.quilmes.xuper.data.LabSubtitleStore
import com.quilmes.xuper.data.LabSubtitleUploadServer
import com.quilmes.xuper.data.SubtitleShiftDataSourceFactory
import com.quilmes.xuper.ui.components.qrImageBitmap
import com.quilmes.xuper.data.WebSourceRegistry
import com.quilmes.xuper.data.Title
import com.quilmes.xuper.data.WebPageMediaResolver
import java.net.URI
import kotlinx.coroutines.delay

private fun isWebPageUrl(raw: String): Boolean {
    val parsed = runCatching { URI(raw) }.getOrNull() ?: return false
    if (parsed.scheme !in setOf("http", "https") || parsed.host.isNullOrBlank()) return false
    val path = parsed.path.orEmpty().lowercase()
    return !listOf(
        ".m3u", ".m3u8", ".mpd", ".mp4", ".m4v", ".webm", ".mkv", ".mov",
        ".ts", ".m2ts", ".mts", ".3gp", ".flv", ".avi", ".mpg", ".mpeg",
        ".aac", ".m4a", ".mp3", ".ogg", ".wav", ".wmv"
    ).any(path::endsWith)
}

private fun videoApiEmbedUrl(raw: String, resumePositionSeconds: Long = 0L): String {
    val base = raw.substringBefore('?').trimEnd('/')
    val existing = raw.substringAfter('?', "").split('&').filter(String::isNotBlank)
        .filterNot { it.substringBefore('=').lowercase() in setOf("autoplay", "subtitles", "hide_controls", "skin", "start_at") }
    val params = mutableListOf("autoplay=1", "subtitles=1", "hide_controls=0", "skin=netflix")
    if (resumePositionSeconds > 0L) params += "start_at=$resumePositionSeconds"
    params += existing
    return "$base?${params.joinToString("&")}"
}

/** Keeps D-pad navigation from getting trapped inside a cross-origin iframe. */
private class HubAwareWebView(
    context: Context,
    /** true en Vidly: las flechas/OK se mandan a la página y no se dejan escapar a la navegación de foco de Android. */
    private val consumeDpad: Boolean,
    /** > 0: tras ese tiempo sin teclas se "saca" el mouse falso del reproductor (ver nudgeHover). 0 = nunca. Hoy solo Prueba. */
    private val hoverExitDelayMs: Long = 0L,
    private val onRemoteKey: (AndroidKeyEvent) -> Boolean,
) : WebView(context) {
    /** Dispatch a fallback key without re-entering our own remote-key interceptor. */
    fun dispatchInjectedKeyEvent(event: AndroidKeyEvent): Boolean = super.dispatchKeyEvent(event)

    private var hoverEntered = false
    private var hoverFlip = false
    private var lastHoverAt = 0L
    private var lastHoverX = 0f
    private var lastHoverY = 0f

    private val exitHover = Runnable {
        if (!hoverEntered) return@Runnable
        hoverEntered = false
        runCatching {
            val now = android.os.SystemClock.uptimeMillis()
            val exit = android.view.MotionEvent.obtain(now, now, android.view.MotionEvent.ACTION_HOVER_EXIT, lastHoverX, lastHoverY, 0)
            exit.source = android.view.InputDevice.SOURCE_MOUSE
            onHoverEvent(exit)
            exit.recycle()
        }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(exitHover)
        super.onDetachedFromWindow()
    }

    /**
     * Mueve un "mouse" real sobre el centro del reproductor. Muchos reproductores web solo muestran sus controles con
     * :hover o con movimiento de mouse, y eso no se puede simular con JavaScript; con el control remoto nunca hay mouse.
     */
    private fun nudgeHover() {
        val now = android.os.SystemClock.uptimeMillis()
        if (width == 0 || height == 0) return
        // Con salida programada, cada tecla reinicia la cuenta aunque el movimiento se limite a uno cada 250 ms.
        if (hoverExitDelayMs > 0L) { removeCallbacks(exitHover); postDelayed(exitHover, hoverExitDelayMs) }
        if (now - lastHoverAt < 250L) return
        lastHoverAt = now
        hoverFlip = !hoverFlip
        val x = width / 2f + if (hoverFlip) 3f else -3f
        val y = height * 0.5f
        lastHoverX = x
        lastHoverY = y
        runCatching {
            if (!hoverEntered) {
                hoverEntered = true
                val enter = android.view.MotionEvent.obtain(now, now, android.view.MotionEvent.ACTION_HOVER_ENTER, x, y, 0)
                enter.source = android.view.InputDevice.SOURCE_MOUSE
                onHoverEvent(enter)
                enter.recycle()
            }
            val move = android.view.MotionEvent.obtain(now, now, android.view.MotionEvent.ACTION_HOVER_MOVE, x, y, 0)
            move.source = android.view.InputDevice.SOURCE_MOUSE
            onHoverEvent(move)
            move.recycle()
        }
    }

    override fun dispatchKeyEvent(event: AndroidKeyEvent): Boolean {
        if ((event.action == AndroidKeyEvent.ACTION_DOWN || event.action == AndroidKeyEvent.ACTION_UP) && onRemoteKey(event)) return true
        if (consumeDpad && event.action == AndroidKeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                AndroidKeyEvent.KEYCODE_DPAD_UP, AndroidKeyEvent.KEYCODE_DPAD_DOWN,
                AndroidKeyEvent.KEYCODE_DPAD_LEFT, AndroidKeyEvent.KEYCODE_DPAD_RIGHT,
                AndroidKeyEvent.KEYCODE_DPAD_CENTER -> nudgeHover()
            }
        }
        val handled = super.dispatchKeyEvent(event)
        if (consumeDpad) {
            when (event.keyCode) {
                AndroidKeyEvent.KEYCODE_DPAD_UP, AndroidKeyEvent.KEYCODE_DPAD_DOWN,
                AndroidKeyEvent.KEYCODE_DPAD_LEFT, AndroidKeyEvent.KEYCODE_DPAD_RIGHT,
                AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER -> return true
            }
        }
        return handled
    }
}

/**
 * Navegación con el control remoto para páginas web "de fábrica" (hoy: Vidly / botón Prueba), sin hub propio.
 * Se inyecta en todos los frames. Flechas: con los controles ocultos saltan ±10 s en el video; con controles a la
 * vista (o al apretar ↑/↓) mueven un recuadro amarillo entre los botones de la pantalla; OK toca el elemento marcado
 * (o pausa/reanuda si no hay nada marcado); un slider enfocado maneja él mismo ←/→. Las teclas de medios
 * (play/pausa, retroceder/avanzar) también funcionan. Si el documento no tiene nada que manejar, la tecla pasa al
 * iframe más grande.
 */
private val XUPER_REMOTE_NAV_JS = """(function(){
  if(window.__xuperRemoteNav)return; window.__xuperRemoteNav=true;
  var HL_ID='xuper-nav-hl';
  var cur=null, hl=null, lastWake=0, lastSeekAt=0;
  // Reposo (opcional, solo si la app define window.__xuperIdleHideMs antes de este script; hoy solo "Prueba"):
  // tras ese tiempo sin teclas se borra el recuadro amarillo y se "saca" el mouse falso del reproductor para que
  // oculte sus controles (muchos no los ocultan mientras crean que el mouse está encima).
  var IDLE_MS=(window.__xuperIdleHideMs|0), idleTimer=null;
  var ROLES={button:1,link:1,menuitem:1,menuitemradio:1,menuitemcheckbox:1,option:1,tab:1,slider:1,'switch':1,checkbox:1,radio:1};
  function vw(){return window.innerWidth||document.documentElement.clientWidth||0;}
  function vh(){return window.innerHeight||document.documentElement.clientHeight||0;}
  function rectOf(e){try{return e.getBoundingClientRect();}catch(x){return null;}}
  // pointer-events y visibility se miran solo en el propio elemento (los contenedores de controles suelen traer
  // pointer-events:none con hijos en auto); display y opacity sí se heredan visualmente: se miran los ancestros.
  function faded(e){
    try{
      var own=getComputedStyle(e);
      if(own.pointerEvents==='none'||own.visibility==='hidden')return true;
      for(var p=e,i=0;p&&i<8;i++,p=p.parentElement){var s=getComputedStyle(p);if(s.display==='none'||parseFloat(s.opacity)<0.05)return true;}
    }catch(x){}
    return false;
  }
  function shown(e){
    var r=rectOf(e); if(!r||r.width<8||r.height<8)return false;
    if(r.bottom<=0||r.right<=0||r.top>=vh()||r.left>=vw())return false;
    return !faded(e);
  }
  function isInteractive(e){
    var t=e.tagName;
    if(t==='BUTTON'||t==='SELECT'||t==='TEXTAREA'||t==='SUMMARY')return true;
    if(t==='A'&&e.hasAttribute('href'))return true;
    if(t==='INPUT'&&e.type!=='hidden')return true;
    var role=e.getAttribute('role'); if(role&&ROLES[role])return true;
    var ti=e.getAttribute('tabindex'); if(ti!==null&&ti!=='-1')return true;
    if(e.hasAttribute('onclick'))return true;
    try{if(getComputedStyle(e).cursor==='pointer')return true;}catch(x){}
    return false;
  }
  // Elementos "tocables" a la vista: se descartan contenedores (que traen varios hijos tocables) y anidados (queda el más externo).
  function allEls(root,out,depth){
    var list=root.querySelectorAll('*');
    for(var i=0;i<list.length&&out.length<8000;i++){
      var e=list[i]; out.push(e);
      if(e.shadowRoot&&depth<4)allEls(e.shadowRoot,out,depth+1);
    }
  }
  function candidates(){
    var area=vw()*vh(), out=[], all=[];
    allEls(document.body||document.documentElement,all,0);
    var n=Math.min(all.length,6000);
    for(var i=0;i<n;i++){
      var e=all[i], t=e.tagName;
      if(t==='VIDEO'||t==='IFRAME'||t==='SCRIPT'||t==='STYLE'||t==='svg'||t==='path'||e.id===HL_ID)continue;
      var r=rectOf(e); if(!r||r.width<8||r.height<8||r.width*r.height>area*0.35)continue;
      if(!isInteractive(e)||!shown(e))continue;
      out.push(e);
    }
    var leaves=out.filter(function(c){return out.filter(function(o){return o!==c&&c.contains(o);}).length<2;});
    return leaves.filter(function(c){return !leaves.some(function(o){return o!==c&&o.contains(c);});});
  }
  function ensureHl(){
    var host=document.fullscreenElement||document.documentElement;
    if(host.tagName==='VIDEO')host=document.documentElement;
    if(!hl){
      hl=document.createElement('div'); hl.id=HL_ID;
      hl.style.cssText='position:fixed;z-index:2147483647;pointer-events:none;border:3px solid #FFD54F;border-radius:6px;box-shadow:0 0 0 2px rgba(0,0,0,.65),0 0 14px 3px rgba(255,213,79,.85);box-sizing:border-box;display:none;';
    }
    if(hl.parentNode!==host)host.appendChild(hl);
  }
  function clear(){cur=null; if(hl)hl.style.display='none';}
  function paint(){
    if(!cur||!cur.isConnected||!shown(cur)){clear();return;}
    ensureHl(); var r=rectOf(cur);
    hl.style.display='block'; hl.style.left=(r.left-3)+'px'; hl.style.top=(r.top-3)+'px';
    hl.style.width=(r.width+6)+'px'; hl.style.height=(r.height+6)+'px';
  }
  setInterval(function(){if(cur)paint();},300);
  function setCur(e){
    cur=e;
    try{e.focus({preventScroll:true});}catch(x){}
    try{e.scrollIntoView({block:'nearest',inline:'nearest'});}catch(x){}
    paint();
  }
  function pick(dir){
    var c=candidates(); if(!c.length)return false;
    var active=cur&&cur.isConnected&&shown(cur);
    if(!active){
      // Primera vez: arranca por lo más cercano al centro inferior (donde suele estar la barra de controles).
      var bx=vw()/2, by=vh()*0.9, best=null, bd=1e12;
      c.forEach(function(e){var r=rectOf(e),dx=r.left+r.width/2-bx,dy=r.top+r.height/2-by,d=dx*dx+dy*dy;if(d<bd){bd=d;best=e;}});
      setCur(best); return true;
    }
    var rc=rectOf(cur), cx=rc.left+rc.width/2, cy=rc.top+rc.height/2, best2=null, bs=1e12;
    c.forEach(function(e){
      if(e===cur)return;
      var r=rectOf(e), dx=r.left+r.width/2-cx, dy=r.top+r.height/2-cy, p, o;
      if(dir==='Right'){p=dx;o=Math.abs(dy);}
      else if(dir==='Left'){p=-dx;o=Math.abs(dy);}
      else if(dir==='Down'){p=dy;o=Math.abs(dx);}
      else{p=-dy;o=Math.abs(dx);}
      if(p<=4)return;
      var s=p+2.5*o; if(s<bs){bs=s;best2=e;}
    });
    if(best2){setCur(best2);return true;}
    return false;
  }
  function video(){
    var best=null, ba=0;
    Array.prototype.forEach.call(document.querySelectorAll('video'),function(v){var r=rectOf(v),a=r?r.width*r.height:0;if(a>ba){ba=a;best=v;}});
    return best;
  }
  // Los controles del reproductor se ocultan solos: un "movimiento de mouse" falso los vuelve a mostrar.
  function wake(){
    var x=vw()/2, y=vh()/2, targets=[document.elementFromPoint(x,y),document.body,document.documentElement];
    ['pointerover','pointermove','mouseover','mousemove'].forEach(function(n){
      targets.forEach(function(t){if(t)try{t.dispatchEvent(new MouseEvent(n,{bubbles:true,clientX:x,clientY:y}));}catch(e){}});
    });
  }
  function idleHide(){
    idleTimer=null;
    clear();
    try{
      var x=vw()/2, y=vh()/2, el=document.elementFromPoint(x,y), chain=[];
      for(var p=el,i=0;p&&i<30;p=p.parentElement,i++)chain.push(p);
      chain.push(document);
      chain.forEach(function(t){
        ['pointerout','mouseout'].forEach(function(n){try{t.dispatchEvent(new MouseEvent(n,{bubbles:true,clientX:x,clientY:y}));}catch(e){}});
        ['pointerleave','mouseleave'].forEach(function(n){try{t.dispatchEvent(new MouseEvent(n,{bubbles:false,clientX:x,clientY:y}));}catch(e){}});
      });
    }catch(e){}
  }
  function armIdle(){
    if(!IDLE_MS)return;
    if(idleTimer)clearTimeout(idleTimer);
    idleTimer=setTimeout(idleHide,IDLE_MS);
  }
  function clickEl(e){
    var r=rectOf(e), o={bubbles:true,cancelable:true,clientX:r.left+r.width/2,clientY:r.top+r.height/2,view:window};
    ['pointerdown','mousedown','pointerup','mouseup'].forEach(function(n){
      try{var C=(n.indexOf('pointer')===0&&window.PointerEvent)?window.PointerEvent:window.MouseEvent;e.dispatchEvent(new C(n,o));}catch(x){}
    });
    try{e.click();}catch(x){}
  }
  function togglePlay(v){try{if(v.paused){var p=v.play();if(p&&p.catch)p.catch(function(){});}else v.pause();}catch(e){}}
  function seek(v,delta){
    try{var d=v.duration,t=v.currentTime+delta;t=isFinite(d)?Math.min(Math.max(0,t),Math.max(0,d-1)):Math.max(0,t);v.currentTime=t;}catch(e){}
  }
  function isSlider(e){return !!e&&((e.tagName==='INPUT'&&e.type==='range')||(e.getAttribute&&e.getAttribute('role')==='slider'));}
  function stop(ev){ev.preventDefault();ev.stopImmediatePropagation();}
  // Si este documento no tiene nada que manejar, la tecla pasa al iframe más grande (el reproductor suele estar adentro de uno).
  function handoff(key,ev){
    var fr=Array.prototype.filter.call(document.querySelectorAll('iframe'),function(f){var r=rectOf(f);return r&&r.width>100&&r.height>100&&shown(f);});
    if(!fr.length)return false;
    fr.sort(function(a,b){var ra=rectOf(a),rb=rectOf(b);return rb.width*rb.height-ra.width*ra.height;});
    var f=fr[0]; stop(ev); clear();
    try{f.focus();}catch(e){}
    try{f.contentWindow.postMessage({__xuperNav:1,key:key},'*');}catch(e){}
    return true;
  }
  window.addEventListener('message',function(ev){
    var d=ev.data; if(!d||d.__xuperNav!==1)return;
    try{window.dispatchEvent(new KeyboardEvent('keydown',{key:String(d.key||''),bubbles:true,cancelable:true}));}catch(e){}
  });
  window.addEventListener('keydown',function(ev){
    var k=ev.key, now=Date.now();
    var dir=(k==='ArrowRight'||k==='Right')?'Right':(k==='ArrowLeft'||k==='Left')?'Left':(k==='ArrowDown'||k==='Down')?'Down':(k==='ArrowUp'||k==='Up')?'Up':null;
    var ok=(k==='Enter'||k==='Select'||ev.keyCode===13);
    var media=(k==='MediaPlayPause'||k==='MediaRewind'||k==='MediaFastForward');
    if(!dir&&!ok&&!media)return;
    var t=ev.target;
    if(t&&(t.tagName==='TEXTAREA'||(t.tagName==='INPUT'&&t.type!=='range'&&t.type!=='button'&&t.type!=='checkbox')))return;
    var v=video();
    if(media){
      if(!v)return;
      stop(ev);
      if(k==='MediaPlayPause')togglePlay(v); else seek(v,k==='MediaRewind'?-30:30);
      return;
    }
    if(now-lastWake>500){lastWake=now;wake();}
    armIdle();
    var active=cur&&cur.isConnected&&shown(cur);
    if(ok){
      if(active){stop(ev);clickEl(cur);setTimeout(paint,150);return;}
      if(v){stop(ev);togglePlay(v);return;}
      handoff('Enter',ev);
      return;
    }
    // Un slider enfocado maneja solo Izquierda/Derecha (la barra de progreso o el volumen).
    if(active&&isSlider(cur)&&(dir==='Left'||dir==='Right'))return;
    // Con los controles ocultos, Izquierda/Derecha saltan en el video (manteniendo apretado, saltos más largos).
    if(!active&&(dir==='Left'||dir==='Right')&&v){
      stop(ev);
      if(ev.repeat&&now-lastSeekAt<160)return;
      lastSeekAt=now; seek(v,(dir==='Right'?1:-1)*(ev.repeat?30:10));
      return;
    }
    stop(ev);
    if(active){pick(dir);return;}
    // Arranque: la app mueve un "mouse" sobre el reproductor y los controles tardan un instante en aparecer;
    // se espera antes de elegir para no quedarse con lo primero que haya a la vista (p. ej. la pantalla de pausa).
    setTimeout(function(){if(!pick(dir))handoff('Arrow'+dir,ev);},320);
  },true);
})();"""

/**
 * Arranque automático CON SONIDO de VidLux (botón Alternativo). La URL de VidLux no usa parámetros de autoplay
 * (no documentados); en su lugar, este script (inyectado en todos los frames, antes de
 * que cargue la página) espera a que el reproductor esté listo y lo inicia con la API del <video>
 * (`muted=false` + `play()`) y/o tocando el botón Play real de la página, sin dibujar nada encima. Una vez
 * que reproduce, verifica unos segundos que el audio siga activo y deja el resultado en Logcat
 * (etiqueta XuperVidLux, vía onConsoleMessage). No agrega botones de volumen/mute: el volumen es del TV.
 * Si la persona aprieta una tecla del mando antes de que arranque, se cancela y no pelea con ella.
 */
private val XUPER_VIDLUX_AUTOPLAY_JS = """(function(){
  if(window.__xuperVidLuxAutoplay) return; window.__xuperVidLuxAutoplay=true;
  var TAG='[XuperVidLux] ';
  function log(m){try{console.log(TAG+m);}catch(e){}}
  var isTop=(window===window.top);
  var bootAt=Date.now();
  var state={started:false,cancelled:false,gaveUp:false,clicks:0,centerClicks:0,lastClickAt:0,lastPlayAt:0,startedAt:0,unmuteClicked:false,reported:false};
  var MAX_WAIT_MS=90000, VERIFY_MS=12000, CLICK_GAP_MS=1500, MAX_CLICKS=10;
  var PLAY_RE=/(^|[^a-z])(play|reproducir|reanudar|resume)(?![a-z])/i;
  var NOT_RE=/pause|pausa|next|siguiente|prev|anterior|episod|server|servidor|mute|silenc|sound|sonido|volum|subtit|caption|setting|config|quality|calidad|speed|velocidad|full|pantalla|close|cerrar|download|descarg|cast|pip|picture|audio|lang|idioma|trailer|replay/i;
  var UNMUTE_RE=/unmute|activar (el )?sonido|reactivar (el )?sonido|quitar (el )?silencio|desilenciar/i;
  function rectOf(e){try{return e.getBoundingClientRect();}catch(x){return null;}}
  function vw(){return window.innerWidth||document.documentElement.clientWidth||0;}
  function vh(){return window.innerHeight||document.documentElement.clientHeight||0;}
  function shown(e){
    var r=rectOf(e); if(!r||r.width<8||r.height<8)return false;
    if(r.bottom<=0||r.right<=0||r.top>=vh()||r.left>=vw())return false;
    try{var s=getComputedStyle(e);if(s.display==='none'||s.visibility==='hidden'||parseFloat(s.opacity)<0.05)return false;}catch(x){}
    return true;
  }
  // En iframes solo actúa el que ocupa casi toda la pantalla (el reproductor), no anuncios ni widgets.
  function frameEligible(){
    if(isTop)return true;
    try{return vw()>=screen.width*0.6&&vh()>=screen.height*0.4;}catch(e){return true;}
  }
  function bestVideo(){
    var best=null,ba=-1;
    Array.prototype.forEach.call(document.querySelectorAll('video'),function(v){
      var r=rectOf(v),a=r?r.width*r.height:0;
      if(a>ba){ba=a;best=v;}
    });
    return best;
  }
  function labelOf(e){
    var cn='';try{cn=(typeof e.className==='string')?e.className:'';}catch(x){}
    return ((e.getAttribute('aria-label')||'')+' '+(e.getAttribute('title')||'')+' '+(e.getAttribute('data-tip')||'')+' '+(e.innerText||'')+' '+cn).toLowerCase();
  }
  function clickEl(e){
    var r=rectOf(e); if(!r)return;
    var o={bubbles:true,cancelable:true,clientX:r.left+r.width/2,clientY:r.top+r.height/2,view:window};
    ['pointerdown','mousedown','pointerup','mouseup'].forEach(function(n){
      try{var C=(n.indexOf('pointer')===0&&window.PointerEvent)?window.PointerEvent:window.MouseEvent;e.dispatchEvent(new C(n,o));}catch(x){}
    });
    try{e.click();}catch(x){}
  }
  function findPlayButton(){
    var list=document.querySelectorAll('button,[role=button],[class*=play i],[aria-label],[title]');
    var best=null,ba=-1;
    for(var i=0;i<list.length&&i<800;i++){
      var e=list[i],t=e.tagName;
      if(t==='VIDEO'||t==='IFRAME'||t==='SCRIPT'||t==='STYLE'||t==='A'&&!e.getAttribute('role'))continue;
      var lab=labelOf(e);
      if(!PLAY_RE.test(lab)||NOT_RE.test(lab))continue;
      if(!shown(e))continue;
      var r=rectOf(e),a=r.width*r.height;
      if(a>vw()*vh()*0.6)continue; // un contenedor gigante no es el botón
      if(a>ba){ba=a;best=e;} // el botón Play grande del póster gana al chico de la barra
    }
    return best;
  }
  function findUnmuteButton(){
    var list=document.querySelectorAll('button,[role=button],[aria-label],[title]');
    for(var i=0;i<list.length&&i<800;i++){
      var e=list[i],lab=((e.getAttribute('aria-label')||'')+' '+(e.getAttribute('title')||'')).toLowerCase();
      if(UNMUTE_RE.test(lab)&&shown(e))return e;
    }
    return null;
  }
  function forceSound(v){
    try{v.defaultMuted=false;}catch(e){}
    try{v.removeAttribute('muted');}catch(e){}
    try{if(v.muted)v.muted=false;}catch(e){}
    try{if(!(v.volume>0))v.volume=1;}catch(e){}
  }
  function isPlaying(v){return !!v&&!v.paused&&!v.ended&&(v.currentTime>0.05||v.readyState>=3);}
  function markStarted(v){
    if(state.started)return;
    state.started=true; state.startedAt=Date.now();
    log('reproduciendo; muted='+v.muted+' volume='+v.volume);
  }
  function tryPlay(v){
    var now=Date.now(); if(now-state.lastPlayAt<700)return;
    state.lastPlayAt=now;
    forceSound(v);
    try{
      var p=v.play();
      if(p&&p.catch)p.catch(function(err){log('play() rechazado: '+(err&&err.name?err.name:err));});
    }catch(e){log('play() lanzó: '+e);}
  }
  function tick(){
    if(state.cancelled||state.gaveUp)return;
    if(!frameEligible())return;
    var now=Date.now();
    var v=bestVideo();
    if(state.started){
      // Fase de verificación: el audio tiene que estar activo (un reproductor puede arrancar muteado).
      if(!v)return;
      if(v.muted||!(v.volume>0)){
        forceSound(v);
        if(v.muted&&!state.unmuteClicked&&now-state.startedAt>2500){
          var ub=findUnmuteButton(); if(ub){state.unmuteClicked=true;clickEl(ub);log('se tocó el botón real de sonido');}
        }
      }
      if(now-state.startedAt>VERIFY_MS){
        if(!state.reported){state.reported=true;
          log('estado final: paused='+v.paused+' muted='+v.muted+' volume='+v.volume+' t='+(v.currentTime||0).toFixed(1)+(v.muted?' ADVERTENCIA: sigue muteado':' OK con sonido'));
        }
        clearInterval(timer);
      }
      return;
    }
    if(now-bootAt>MAX_WAIT_MS){state.gaveUp=true;clearInterval(timer);log('no se pudo iniciar la reproducción en '+(MAX_WAIT_MS/1000)+' s');return;}
    if(v&&isPlaying(v)){forceSound(v);markStarted(v);return;}
    // 1) API del elemento <video> en cuanto tiene datos.
    if(v&&(v.readyState>=2||v.currentSrc||v.src))tryPlay(v);
    // 2) Botón Play real del reproductor (el video suele cargarse recién al tocarlo).
    if(now-state.lastClickAt>=CLICK_GAP_MS&&state.clicks<MAX_CLICKS&&(!v||v.paused)){
      var b=findPlayButton();
      if(b){state.lastClickAt=now;state.clicks++;clickEl(b);log('botón Play tocado (#'+state.clicks+')');}
      else if(state.clicks===0&&state.centerClicks<2&&now-bootAt>3000&&shown(document.documentElement)){
        // Último recurso: el centro del reproductor (pósters sin etiqueta accesible).
        var c=document.elementFromPoint(vw()/2,vh()/2);
        if(c&&!(c.closest&&c.closest('a[href]'))&&!(c.closest&&c.closest('[role=menu],[role=dialog]'))){
          state.lastClickAt=now;state.centerClicks++;clickEl(c);log('centro del reproductor tocado (#'+state.centerClicks+')');
        }
      }
    }
  }
  // Reacciona apenas el reproductor avisa que está listo / empezó (los eventos de media no burbujean: captura).
  ['loadedmetadata','canplay','playing'].forEach(function(n){
    document.addEventListener(n,function(ev){
      if(state.cancelled||state.started||!frameEligible())return;
      var v=ev.target; if(!v||v.tagName!=='VIDEO')return;
      if(n==='playing'){forceSound(v);markStarted(v);}
      else{tryPlay(v);}
    },true);
  });
  // Si la persona toma el control con el mando antes de que arranque, no se pelea con ella.
  window.addEventListener('keydown',function(ev){
    if(ev.isTrusted&&Date.now()-bootAt>3000&&!state.started){state.cancelled=true;clearInterval(timer);log('cancelado: el usuario tomó el control');}
  },true);
  var timer=setInterval(tick,800);
  tick();
})();"""


/**
 * Best-effort HTML5 controls. A cross-origin iframe is intentionally inaccessible by browser policy.
 * Return value is true only when this script actually handled the command in an accessible document.
 */
private fun videoApiCommandScript(action: String, index: Int, seconds: Int): String {
    val safeAction = action.filter(Char::isLetter)
    return """(function(){
      var action='$safeAction', idx=$index, seconds=$seconds, handled=false, visited=[];
      function walk(w){
        if(handled || visited.indexOf(w)>=0) return; visited.push(w);
        var d; try{d=w.document;}catch(e){return;}
        try{
          var videos=Array.from(d.querySelectorAll('video'));
          if(videos.length){
            var v=videos[0];
            if(action==='probe'){handled=true;}
            else if(action==='playPause'){
              if(v.paused){var p=v.play();if(p&&p.catch)p.catch(function(){});}else v.pause();
              handled=true;
            }else if(action==='seekBack'||action==='seekForward'){
              var delta=action==='seekBack'?-Math.abs(seconds):Math.abs(seconds);
              var target=(Number(v.currentTime)||0)+delta;
              v.currentTime=Math.max(0,isFinite(v.duration)&&v.duration>0?Math.min(v.duration,target):target);
              handled=true;
            }else if(action==='mute'){v.muted=!v.muted;handled=true;}
            else if(action==='subtitle'){
              var tracks=v.textTracks;
              if(tracks&&tracks.length){
                var selected=((idx%tracks.length)+tracks.length)%tracks.length;
                for(var i=0;i<tracks.length;i++)tracks[i].mode=i===selected?'showing':'disabled';
                handled=true;
              }
            }
          }
          if(!handled&&(action==='playPause'||action==='mute'||action==='subtitle')){
            var els=Array.from(d.querySelectorAll('button,[role=button],[aria-label],[title]'));
            var re=action==='playPause'?/play|pause|reproducir|pausar/i:action==='mute'?/mute|volume|sonido|silencio/i:/subtitle|captions|closed caption|\bcc\b|subtitulos|subtítulos/i;
            var target=els.find(function(e){return re.test(((e.getAttribute('aria-label')||'')+' '+(e.getAttribute('title')||'')+' '+(e.innerText||'')).toLowerCase())});
            if(target){target.click();handled=true;}
          }
          if(!handled){
            var frames=Array.from(w.frames);
            for(var j=0;j<frames.length&&!handled;j++)try{walk(frames[j]);}catch(e){}
          }
        }catch(e){}
      }
      walk(window); return handled;
    })();"""
}

/**
 * Agente de control que la APP inyecta en TODOS los frames de su WebView, incluidos los iframes de otro
 * origen, con WebViewCompat.addDocumentStartJavaScript (androidx.webkit).
 *
 * Por qué existe: evaluateJavascript() solo corre en el frame principal y el navegador no deja que una
 * página lea el <video> de un iframe de otro origen. VideoAPI (Latino) y ok.ru (Simuladores) ponen el
 * <video> en un iframe así, y su API pública no define ningún comando (post_message figura como bloqueado).
 * Este script no usa la API de ellos ni envía teclas: cada frame ubica SU <video> más grande, informa su
 * estado y obedece órdenes {__xuper:1,target,cmd} que la app le reparte por postMessage.
 *
 * Límites: necesita un System WebView con DOCUMENT_START_SCRIPT; no ve un <video> dentro de un shadow DOM
 * cerrado ni reproductores que no usen <video>. Si algo falla, el hub no aparece y el mando queda
 * en manos del reproductor original (comportamiento anterior).
 */
private val XUPER_FRAME_AGENT_JS = """(function(){
  try{
    if(window.__xuperFrameAgent) return;
    window.__xuperFrameAgent=true;
    var ID=Math.random().toString(36).slice(2,10)+Date.now().toString(36).slice(-4);
    var isTop=false; try{isTop=(window.top===window);}catch(e){}
    var tick=0, hadVideo=false, bound=null;
    function visible(el){try{var r=el.getBoundingClientRect(),s=getComputedStyle(el);return r.width>0&&r.height>0&&s.display!=='none'&&s.visibility!=='hidden'&&Number(s.opacity||1)>0.05;}catch(e){return false;}}
    function label(el){return (el.innerText||el.textContent||el.getAttribute('aria-label')||el.getAttribute('title')||el.getAttribute('data-testid')||'').replace(/\s+/g,' ').trim();}
    function findText(patterns){
      var sels='button,[role=button],[role=tab],[role=menuitem],[role=option],[role=menuitemradio],[role=radio],[aria-label],[title],[tabindex],div,span';
      var all=Array.from(document.querySelectorAll(sels)).filter(visible);
      for(var p=0;p<patterns.length;p++){
        var re=patterns[p];
        var hits=all.filter(function(e){return re.test(label(e));});
        // Evitar el contenedor padre: priorizar el elemento cuyo texto visible coincide exactamente.
        hits.sort(function(a,b){var la=label(a).toLowerCase().trim(),lb=label(b).toLowerCase().trim();var ea=re.test(la)&&patterns[p].source.replace(/^\^|\$$/g,'').toLowerCase()===la?0:1;var eb=re.test(lb)&&patterns[p].source.replace(/^\^|\$$/g,'').toLowerCase()===lb?0:1;return ea-eb||la.length-lb.length;});
        if(hits.length)return hits[0];
      }
      return null;
    }
    function clickText(patterns){var el=findText(patterns);if(!el)return false;try{el.click();return true;}catch(e){return false;}}
    function chooseSpanishSubtitle(){
      if(clickText([/^spanish$/i]))return true;
      return false;
    }
    function clickPlayerMenu(kind){
      if(kind==='subtitle'){
        // Flujo real de VideoAPI: CC -> Predeterminados -> Spanish. Cada paso espera a que
        // el panel anterior se pinte; no damos por activado el subtítulo solo por tocar una pestaña.
        if(findText([/^spanish$/i])) return chooseSpanishSubtitle();
        if(findText([/^predeterminados$/i])){
          if(clickText([/^predeterminados$/i])) setTimeout(function(){chooseSpanishSubtitle();},450);
          return true;
        }
        if(clickText([/^cc$/i,/^subtítulos?$/i,/^captions?$/i,/^closed captions?$/i])){
          setTimeout(function(){
            if(findText([/^spanish$/i])){chooseSpanishSubtitle();return;}
            if(clickText([/^predeterminados$/i]))setTimeout(function(){chooseSpanishSubtitle();},450);
          },350);
          return true;
        }
        return false;
      }
      // Audio: abrir Ajustes -> Idioma y elegir la opción contraria a la que figura seleccionada.
      function chooseLanguage(){
        var en=findText([/^english$/i]),es=findText([/^español$/i,/^espanol$/i]);
        if(!en&&!es)return false;
        function selected(el){
          if(!el)return false;
          var cur=el;
          for(var i=0;i<4&&cur;i++,cur=cur.parentElement){
            var a=(cur.getAttribute('aria-checked')||cur.getAttribute('aria-selected')||cur.getAttribute('data-state')||'').toLowerCase();
            var c=String(cur.className||'').toLowerCase();
            if(a==='true'||a==='checked'||a==='on'||/selected|active|checked|current/.test(c))return true;
          }
          return false;
        }
        var englishSelected=selected(en), spanishSelected=selected(es);
        // Si no hay estado accesible, usar Español como predeterminado; después alternar según el estado visible.
        var target=englishSelected?es:(spanishSelected?en:es);
        if(!target)target=en;
        try{target.click();return true;}catch(e){return false;}
      }
      if(chooseLanguage())return true;
      if(clickText([/^idioma$/i,/^language$/i])){
        setTimeout(function(){chooseLanguage();},350);return true;
      }
      if(clickText([/^(settings|ajustes|configuración|configuracion|opciones)$/i,/settings|gear/i])){
        setTimeout(function(){
          if(clickText([/^idioma$/i,/^language$/i]))setTimeout(function(){chooseLanguage();},350);
          else chooseLanguage();
        },350);return true;
      }
      return false;
    }

    function collect(root,out,budget){
      try{
        var vs=root.querySelectorAll('video');
        for(var i=0;i<vs.length;i++) out.push(vs[i]);
        if(!out.length && budget.n>0){
          var all=root.querySelectorAll('*');
          for(var j=0;j<all.length && budget.n>0;j++){
            budget.n--;
            var sr=all[j].shadowRoot;
            if(sr) collect(sr,out,budget);
          }
        }
      }catch(e){}
    }
    function best(){
      var out=[]; collect(document,out,{n:(tick%3===0)?4000:0});
      var b=null, ba=-1;
      for(var i=0;i<out.length;i++){
        var r; try{r=out[i].getBoundingClientRect();}catch(e){continue;}
        var a=r.width*r.height;
        if(a>ba){ba=a;b=out[i];}
      }
      return b?{v:b,area:ba}:null;
    }
    function deliver(id,data){
      try{window.XuperBridge.onAgentReport(id,JSON.stringify(data));}catch(e){}
    }
    function send(data){
      if(isTop){deliver(ID,data);return;}
      try{window.top.postMessage({__xuperReport:1,id:ID,data:data},'*');}catch(e){}
    }
    function report(b){
      if(!b){ if(hadVideo){hadVideo=false;send({none:true});} return; }
      hadVideo=true;
      var v=b.v, du=Number(v.duration);
      var audio=[],subs=[],audioSelected=-1,subtitleSelected=-1;
      try{var ats=v.audioTracks;if(ats){for(var ai=0;ai<ats.length;ai++){var at=ats[ai];audio.push(at.label||at.language||('Audio '+(ai+1)));if(at.enabled)audioSelected=ai;}}}catch(e){}
      try{var tts=v.textTracks;if(tts){for(var ti=0;ti<tts.length;ti++){var tt=tts[ti];subs.push(tt.label||tt.language||('Subtítulo '+(ti+1)));if(tt.mode==='showing')subtitleSelected=ti;}}}catch(e){}
      send({area:Math.round(b.area),cur:Number(v.currentTime)||0,dur:isFinite(du)?du:0,paused:!!v.paused,muted:!!v.muted||v.volume===0,audio:audio,audioSelected:audioSelected,subs:subs,subtitleSelected:subtitleSelected});
    }
    function bind(b){
      if(!b||b.v===bound) return; bound=b.v;
      ['play','pause','volumechange','seeked','loadedmetadata','ended'].forEach(function(n){
        try{b.v.addEventListener(n,function(){try{report(best());}catch(x){}});}catch(e){}
      });
    }
    setInterval(function(){tick++;try{var b=best();bind(b);report(b);}catch(e){}},1000);

    window.addEventListener('message',function(e){
      var d=e.data; if(!d||typeof d!=='object') return;
      if(d.__xuperReport===1&&isTop&&typeof d.id==='string'){deliver(d.id.slice(0,32),d.data||{});return;}
      if(d.__xuper!==1) return;
      var menuCommand=(d.cmd==='audioNext'||d.cmd==='subtitleNext');
      if(d.target!==ID && !(d.target==='*'&&menuCommand)) return;
      if(d.target==='*'&&menuCommand){
        // El menú puede vivir en un iframe distinto del que contiene el <video>.
        // Cada frame prueba el menú local y reenvía la orden a sus propios hijos;
        // así también alcanzamos iframes anidados de origen cruzado.
        try{clickPlayerMenu(d.cmd==='audioNext'?'audio':'subtitle');}catch(menuErr){}
        try{for(var fi=0;fi<window.frames.length;fi++){try{window.frames[fi].postMessage(d,'*');}catch(fe){}}}catch(ferr){}
        return;
      }
      var b=best(); if(!b) return; var v=b.v;
      try{
        if(d.cmd==='toggle'){
          if(v.paused){var p=v.play();if(p&&p.catch)p.catch(function(){});}else v.pause();
        }else if(d.cmd==='seek'){
          var t=(Number(v.currentTime)||0)+(Number(d.sec)||0), du=Number(v.duration);
          if(isFinite(du)&&du>0) t=Math.min(t,Math.max(0,du-1));
          v.currentTime=Math.max(0,t);
        }else if(d.cmd==='mute'){
          v.muted=!v.muted;
        }else if(d.cmd==='audioNext'){
          var ats=v.audioTracks;
          if(ats&&ats.length>1){var current=-1;for(var ai=0;ai<ats.length;ai++)if(ats[ai].enabled)current=ai;var next=(current+1)%ats.length;for(var aj=0;aj<ats.length;aj++)ats[aj].enabled=(aj===next);}
          else {clickPlayerMenu('audio');}
        }else if(d.cmd==='subtitleNext'){
          var tts=v.textTracks;
          if(tts&&tts.length){var current=-1;for(var ti=0;ti<tts.length;ti++)if(tts[ti].mode==='showing')current=ti;var next=current+1;if(next>=tts.length)next=-1;for(var tj=0;tj<tts.length;tj++)tts[tj].mode=(tj===next?'showing':'disabled');}
          else {clickPlayerMenu('subtitle');}
        }
      }catch(err){}
      setTimeout(function(){try{report(best());}catch(x){}},150);
    });

    if(isTop){
      window.__xuperSend=function(msg){
        var seen=0;
        function walk(w){
          var n=0; try{n=w.frames.length;}catch(e){return;}
          for(var i=0;i<n&&seen<300;i++){
            var f=null; try{f=w.frames[i];}catch(e){continue;}
            if(!f) continue; seen++;
            try{f.postMessage(msg,'*');}catch(e){}
            walk(f);
          }
        }
        walk(window);
        try{window.postMessage(msg,'*');}catch(e){}
      };
    }
  }catch(e){}
})();"""

/** Orden para el agente de frames. [target] es el id del frame que informó el video principal. */
private fun frameAgentCommandScript(target: String, cmd: String, seconds: Int): String {
    val safeTarget = if (target == "*") "*" else target.filter { it.isLetterOrDigit() }
    val safeCmd = cmd.filter { it.isLetter() }
    return "(function(){try{if(window.__xuperSend){window.__xuperSend({__xuper:1,target:'$safeTarget',cmd:'$safeCmd',sec:$seconds});return true;}}catch(e){}return false;})();"
}

/** Último estado informado por el agente de un frame con <video>. */
private class AgentVideoReport(
    val area: Double,
    val current: Double,
    val duration: Double,
    val paused: Boolean,
    val muted: Boolean,
    val audioTracks: List<String>,
    val audioSelected: Int,
    val subtitleTracks: List<String>,
    val subtitleSelected: Int,
    val atMs: Long,
)

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    name: String,
    streamUrl: String,
    streamHeaders: Map<String, String> = emptyMap(),
    channelQueue: List<Title> = emptyList(),
    currentTitleId: String = "",
    nextEpisode: Title? = null,
    alternatePlayers: List<Title> = emptyList(),
    /** Canal principal + sus secundarios (ARGENTINA). Si hay, el hub de un canal en vivo deja elegir uno. */
    relatedChannels: List<Title> = emptyList(),
    onChangeChannel: (String) -> Unit = {},
    onChangePlayer: (String) -> Unit = {},
    onPlayNextEpisode: (String) -> Unit = {},
    onExit: () -> Unit = {},
    directOnly: Boolean = false,
    externalSubtitles: List<ExternalSubtitleTrack> = emptyList(),
    labMode: Boolean = false,
) {
    val isKnownEmbed = WebSourceRegistry.isVideoApiEmbedUrl(streamUrl) || WebSourceRegistry.isVidlyEmbedUrl(streamUrl) ||
        WebSourceRegistry.isVidLuxEmbedUrl(streamUrl) || WebSourceRegistry.isCineSrcEmbedUrl(streamUrl) ||
        streamUrl.startsWith("https://los-simuladores.bringfeel.com.ar/reproductor") ||
        streamUrl.startsWith("https://ok.ru/videoembed/")

    if (isKnownEmbed) {
        WebEmbedPlayer(
            url = streamUrl,
            contentKey = currentTitleId.ifBlank { streamUrl },
            nextEpisode = nextEpisode,
            onPlayNextEpisode = onPlayNextEpisode,
            onExit = onExit,
        )
        return
    }

    var resolveAsWebPage by remember(streamUrl) { mutableStateOf(false) }
    val resolvePageCallback = remember(streamUrl, directOnly) {
        { if (!directOnly) resolveAsWebPage = true }
    }
    if (resolveAsWebPage && !directOnly) {
        WebPageResolverPlayer(name = name, pageUrl = streamUrl)
        return
    }
    NativePlayerScreen(
        name = name,
        streamUrl = streamUrl,
        streamHeaders = streamHeaders,
        channelQueue = channelQueue,
        currentTitleId = currentTitleId,
        nextEpisode = nextEpisode,
        alternatePlayers = alternatePlayers,
        relatedChannels = relatedChannels,
        onChangeChannel = onChangeChannel,
        onChangePlayer = onChangePlayer,
        onPlayNextEpisode = onPlayNextEpisode,
        onExit = onExit,
        directOnly = directOnly,
        externalSubtitles = externalSubtitles,
        labMode = labMode,
        onResolveAsWebPage = resolvePageCallback,
    )
}

@OptIn(UnstableApi::class)
@Composable
private fun NativePlayerScreen(
    name: String,
    streamUrl: String,
    streamHeaders: Map<String, String>,
    channelQueue: List<Title>,
    currentTitleId: String,
    nextEpisode: Title?,
    alternatePlayers: List<Title>,
    relatedChannels: List<Title>,
    onChangeChannel: (String) -> Unit,
    onChangePlayer: (String) -> Unit,
    onPlayNextEpisode: (String) -> Unit,
    onExit: () -> Unit,
    directOnly: Boolean,
    externalSubtitles: List<ExternalSubtitleTrack>,
    labMode: Boolean,
    onResolveAsWebPage: () -> Unit,
) {
    val context = LocalContext.current
    var error by remember(streamUrl) { mutableStateOf<String?>(null) }
    var showControls by remember(streamUrl, labMode) { mutableStateOf(!labMode) }
    var labPanelOpen by remember(streamUrl, labMode) { mutableStateOf(false) }
    var labSettingsOpen by remember(streamUrl, labMode) { mutableStateOf(false) }
    var labFocusRow by remember(streamUrl) { mutableStateOf(0) } // 0 audio, 1 toggle, 2 subtítulos, 3 tamaño, 4 estilo, 5 sincronía, 6 subir subtítulos, 7 resolución, 8 salida
    var labFocusOption by remember(streamUrl) { mutableStateOf(0) }
    var labAudioSelected by remember(streamUrl) { mutableStateOf(0) }
    var labSubtitleLanguage by remember(streamUrl) { mutableStateOf(0) }
    var labSubtitleSize by remember(streamUrl) { mutableStateOf(0) }
    var labSubtitleStyle by remember(streamUrl) { mutableStateOf(0) }
    var labSubtitlesEnabled by remember(streamUrl) { mutableStateOf(true) }
    // Sincronía de subtítulos: >0 los atrasa (aparecen más tarde), <0 los adelanta. Se aplica con una pausa corta tras el último cambio.
    var labSubtitleDelayMs by remember { mutableStateOf(0L) }
    var labAppliedDelayMs by remember { mutableStateOf(0L) }
    // Subtítulos subidos desde el celular (guardados por película/episodio) y panel del QR.
    var labUploadOpen by remember { mutableStateOf(false) }
    var labUploadUrl by remember { mutableStateOf<String?>(null) }
    var labUploadStatus by remember { mutableStateOf("") }
    var labSavedSubtitles by remember(currentTitleId) {
        mutableStateOf(if (labMode) uploadedSubtitleTracks(context, currentTitleId) else emptyList())
    }
    var labPlayerView by remember(streamUrl) { mutableStateOf<PlayerView?>(null) }
    var showNextEpisode by remember(streamUrl, nextEpisode?.id) { mutableStateOf(false) }
    var nextEpisodeTriggered by remember(streamUrl, nextEpisode?.id) { mutableStateOf(false) }
    var sourceIndex by remember(streamUrl, alternatePlayers) {
        mutableStateOf(alternatePlayers.indexOfFirst { it.streamUrl == streamUrl }.coerceAtLeast(0))
    }
    var position by remember(streamUrl) { mutableStateOf(0L) }
    var duration by remember(streamUrl) { mutableStateOf(0L) }
    var isPlaying by remember(streamUrl) { mutableStateOf(true) }
    var labSeekDirection by remember(streamUrl) { mutableStateOf(0) }
    var labSeekLabel by remember(streamUrl) { mutableStateOf("") }
    var failedSources by remember(streamUrl) { mutableStateOf(0) }
    var attemptedUrls by remember(streamUrl) { mutableStateOf(setOf(streamUrl)) }

    // Canales en vivo (hay cola de canales): reconexión automática ante cortes/trabas.
    val isLive = channelQueue.size > 1
    // Canal principal + secundarios (sin el que se está viendo): el hub deja elegir uno en vez de "cambiar de reproductor".
    val secondaryChoices = remember(relatedChannels, currentTitleId) { relatedChannels.filter { it.id != currentTitleId } }
    val otherChannelHint = if (secondaryChoices.isNotEmpty()) " (por ejemplo uno secundario)" else ""
    var secondaryPickerOpen by remember(streamUrl) { mutableStateOf(false) }
    var hubNotice by remember(streamUrl) { mutableStateOf<String?>(null) }
    var hubTick by remember(streamUrl) { mutableStateOf(0) } // sube con cada tecla: reinicia el auto-ocultado del hub
    val pickerFirstFocus = remember { FocusRequester() }
    val pickerSeen = remember { booleanArrayOf(false) }
    val liveRetryDelaysMs = remember { longArrayOf(1_000, 2_000, 3_000, 5_000, 5_000, 8_000, 8_000, 10_000) }
    var reconnectAttempt by remember(streamUrl) { mutableStateOf(0) }
    var reconnectRequest by remember(streamUrl) { mutableStateOf(0) }
    var reconnectPending by remember(streamUrl) { mutableStateOf(false) }
    var reconnecting by remember(streamUrl) { mutableStateOf(false) }

    val buttonCount = 4
    var focusedButton by remember(streamUrl) { mutableStateOf(0) }
    val rootFocusRequester = remember(streamUrl) { FocusRequester() }
    val buttonFocusRequesters = remember { List(buttonCount) { FocusRequester() } }

    val dataSourceFactory = remember {
        DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent("Mozilla/5.0 (Linux; Android TV) XuperQuilmesTV")
    }
    val subtitleOffsetMs = remember { java.util.concurrent.atomic.AtomicLong(0L) }
    val currentExternalSubtitles by rememberUpdatedState(labSavedSubtitles + externalSubtitles)
    val subtitleShiftFactory = remember {
        // DefaultDataSource: http(s) sigue yendo por dataSourceFactory y además lee los .srt/.vtt guardados en la tele (file://).
        SubtitleShiftDataSourceFactory(
            DefaultDataSource.Factory(context, dataSourceFactory),
            { currentExternalSubtitles.map { it.url }.toSet() },
            subtitleOffsetMs,
        )
    }
    val exoPlayer = remember {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(subtitleShiftFactory))
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build().apply {
                trackSelectionParameters = trackSelectionParameters.buildUpon()
                    .apply { if (labMode) setPreferredAudioLanguage("es") }
                    .setPreferredTextLanguage("es")
                    .setSelectUndeterminedTextLanguage(false)
                    .build()
            }
    }

    fun setUrl(url: String, headers: Map<String, String> = emptyMap()) {
        dataSourceFactory.setDefaultRequestProperties(headers)
        val cleanUrl = url.substringBefore('?').lowercase()
        val lowerUrl = url.lowercase()
        val item = MediaItem.Builder().setUri(url).apply {
            when {
                cleanUrl.endsWith(".m3u8") || lowerUrl.contains(".m3u8?") -> setMimeType(MimeTypes.APPLICATION_M3U8)
                cleanUrl.endsWith(".mpd") || lowerUrl.contains(".mpd?") -> setMimeType(MimeTypes.APPLICATION_MPD)
            }
            val allSubtitles = labSavedSubtitles + externalSubtitles
            if (allSubtitles.isNotEmpty()) setSubtitleConfigurations(subtitleConfigurations(allSubtitles))
        }.build()
        attemptedUrls = attemptedUrls + url
        subtitleOffsetMs.set(0L); labSubtitleDelayMs = 0L; labAppliedDelayMs = 0L
        val resumeKey = currentTitleId.ifBlank { streamUrl }
        val savedPosition = context.getSharedPreferences("xuper-playback", Context.MODE_PRIVATE)
            .getLong(resumeKey, 0L).coerceAtLeast(0L)
        exoPlayer.setMediaItem(item, savedPosition)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
        error = null
        isPlaying = true
    }

    fun seekBy(deltaMs: Long) {
        val target = (exoPlayer.currentPosition + deltaMs).coerceAtLeast(0L)
        val boundedTarget = if (exoPlayer.duration > 0L) target.coerceAtMost(exoPlayer.duration) else target
        exoPlayer.seekTo(boundedTarget)
        if (!labMode) showControls = true
    }

    // Pide una reconexión al mismo canal (con espera creciente). Devuelve false si ya se agotaron los intentos.
    fun requestLiveReconnect(reason: String): Boolean {
        if (!isLive || reconnectAttempt >= liveRetryDelaysMs.size) {
            reconnecting = false
            reconnectPending = false
            return false
        }
        reconnectAttempt++
        android.util.Log.w("XuperLive", "reconectando ($reason), intento $reconnectAttempt/${liveRetryDelaysMs.size}")
        reconnecting = true
        reconnectPending = true
        reconnectRequest++
        return true
    }

    LaunchedEffect(reconnectRequest) {
        if (reconnectRequest == 0) return@LaunchedEffect
        delay(liveRetryDelaysMs[(reconnectAttempt - 1).coerceIn(0, liveRetryDelaysMs.lastIndex)])
        setUrl(exoPlayer.currentMediaItem?.localConfiguration?.uri?.toString() ?: streamUrl, streamHeaders)
        reconnectPending = false
    }

    // Vigilante: si el canal queda trabado cargando, o el stream termina, se reconecta solo.
    LaunchedEffect(exoPlayer, streamUrl, isLive) {
        if (!isLive) return@LaunchedEffect
        var bufferingTicks = 0
        var playingTicks = 0
        while (true) {
            delay(1_000)
            if (reconnectPending) continue
            when {
                exoPlayer.isPlaying -> {
                    bufferingTicks = 0
                    playingTicks++
                    reconnecting = false
                    if (playingTicks >= 15 && reconnectAttempt > 0) reconnectAttempt = 0
                }
                exoPlayer.playbackState == Player.STATE_ENDED -> {
                    playingTicks = 0
                    if (!requestLiveReconnect("el stream terminó")) {
                        error = "Se cortó la transmisión de este canal. Elegí otro canal$otherChannelHint o probá de nuevo."
                        showControls = true
                    }
                }
                exoPlayer.playbackState == Player.STATE_BUFFERING && exoPlayer.playWhenReady && error == null -> {
                    playingTicks = 0
                    bufferingTicks++
                    if (bufferingTicks >= 15) {
                        bufferingTicks = 0
                        if (!requestLiveReconnect("trabado cargando")) {
                            error = "Este canal no responde. Elegí otro canal$otherChannelHint o probá de nuevo."
                            showControls = true
                        }
                    }
                }
                else -> bufferingTicks = 0
            }
        }
    }

    LaunchedEffect(exoPlayer, streamUrl) { setUrl(streamUrl, streamHeaders) }
    LaunchedEffect(streamUrl) {
        delay(100)
        runCatching { rootFocusRequester.requestFocus() }
    }
    LaunchedEffect(streamUrl, showControls) {
        if (showControls) {
            focusedButton = 0
            delay(80)
            runCatching { buttonFocusRequesters[0].requestFocus() }
        }
    }
    LaunchedEffect(exoPlayer, showControls, error, hubTick, secondaryPickerOpen) {
        // Se oculta tras unos segundos SIN tocar nada: cada tecla reinicia la cuenta, y no se oculta con el selector abierto.
        if (showControls && error == null && !secondaryPickerOpen) {
            delay(if (isLive) 9_000 else 6_000)
            showControls = false
        }
    }
    LaunchedEffect(showControls) { if (!showControls) hubNotice = null }
    LaunchedEffect(secondaryPickerOpen) {
        if (secondaryPickerOpen) {
            pickerSeen[0] = true
            delay(80)
            runCatching { pickerFirstFocus.requestFocus() }
        } else if (pickerSeen[0]) {
            pickerSeen[0] = false
            delay(60)
            if (showControls) {
                focusedButton = 1
                runCatching { buttonFocusRequesters[1].requestFocus() }
            } else runCatching { rootFocusRequester.requestFocus() }
        }
    }
    LaunchedEffect(labMode, labSubtitleSize, labSubtitleStyle, labPlayerView) {
        if (labMode) {
            val subtitleView = labPlayerView?.subtitleView
            if (subtitleView != null) {
                val size = when (labSubtitleSize) { 1 -> 25f; 2 -> 15f; else -> 19f }
                subtitleView.setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, size)
                subtitleView.setApplyEmbeddedStyles(false)
                val foreground = if (labSubtitleStyle == 1) AndroidColor.YELLOW else AndroidColor.WHITE
                val background = if (labSubtitleStyle == 2) AndroidColor.BLACK else AndroidColor.argb(170, 25, 30, 34)
                subtitleView.setStyle(CaptionStyleCompat(foreground, background, AndroidColor.TRANSPARENT, CaptionStyleCompat.EDGE_TYPE_OUTLINE, AndroidColor.BLACK, null))
            }
        }
    }
    LaunchedEffect(exoPlayer, nextEpisode?.id) {
        while (true) {
            position = exoPlayer.currentPosition.coerceAtLeast(0L)
            duration = exoPlayer.duration.takeIf { it > 0 } ?: 0L
            if (currentTitleId.isNotBlank() && duration > 0L) {
                val saved = if (position >= duration - 15_000L) 0L else position
                context.getSharedPreferences("xuper-playback", Context.MODE_PRIVATE)
                    .edit().putLong(currentTitleId, saved).apply()
            }
            isPlaying = exoPlayer.isPlaying
            val remaining = (duration - position).coerceAtLeast(0L)
            showNextEpisode = nextEpisode != null && duration > 0L && remaining <= 30_000L && !nextEpisodeTriggered
            if (nextEpisode != null && duration > 0L && isPlaying && remaining <= 1_000L && !nextEpisodeTriggered) {
                nextEpisodeTriggered = true
                onPlayNextEpisode(nextEpisode.id)
            }
            delay(500)
        }
    }

    DisposableEffect(exoPlayer, alternatePlayers, sourceIndex, directOnly, onResolveAsWebPage, channelQueue) {
        val listener = object : Player.Listener {
            override fun onPlayerError(e: PlaybackException) {
                val currentUrl = exoPlayer.currentMediaItem?.localConfiguration?.uri?.toString().orEmpty()
                if (!directOnly && currentUrl == streamUrl && isWebPageUrl(streamUrl)) {
                    onResolveAsWebPage()
                    return
                }
                if (isLive && requestLiveReconnect("error ${e.errorCodeName}")) return
                val nextIndex = (1..alternatePlayers.size).asSequence()
                    .map { (sourceIndex + it) % alternatePlayers.size.coerceAtLeast(1) }
                    .firstOrNull { i ->
                        val candidate = alternatePlayers.getOrNull(i)?.streamUrl.orEmpty()
                        candidate.isNotBlank() && candidate != currentUrl && candidate !in attemptedUrls
                    }
                if (nextIndex != null) {
                    sourceIndex = nextIndex
                    failedSources++
                    val candidate = alternatePlayers[nextIndex]
                    setUrl(candidate.streamUrl, candidate.requestHeaders)
                    showControls = true
                } else {
                    // Canales en vivo (hay cola de canales): NO se cambia solo a otro; el usuario elige.
                    error = if (channelQueue.size > 1) "No se puede reproducir este canal. Elegí otro canal$otherChannelHint o probá de nuevo."
                    else "No se encontró otro enlace coincidente que no se haya probado. Puede que este contenido no esté disponible en las listas cargadas."
                    showControls = true
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener) }
    }
    DisposableEffect(exoPlayer) { onDispose { exoPlayer.release() } }

    fun changeChannelBy(delta: Int) {
        if (channelQueue.size <= 1 || currentTitleId.isBlank()) return
        val index = channelQueue.indexOfFirst { it.id == currentTitleId }
        if (index < 0) return
        val size = channelQueue.size
        val next = channelQueue[((index + delta) % size + size) % size]
        if (next.id != currentTitleId) onChangeChannel(next.id)
    }
    fun togglePlayPause() {
        if (exoPlayer.isPlaying) {
            exoPlayer.pause()
            isPlaying = false
        } else {
            exoPlayer.play()
            isPlaying = true
        }
        if (!labMode) showControls = true
    }
    fun changeSource() {
        if (alternatePlayers.isEmpty()) {
            error = "No hay otra fuente coincidente en las listas cargadas."
            showControls = true
            return
        }
        val currentUrl = exoPlayer.currentMediaItem?.localConfiguration?.uri?.toString().orEmpty()
        val nextIndex = (1..alternatePlayers.size).asSequence()
            .map { (sourceIndex + it) % alternatePlayers.size }
            .firstOrNull { alternatePlayers[it].streamUrl.isNotBlank() && alternatePlayers[it].streamUrl != currentUrl }
        if (nextIndex != null) {
            sourceIndex = nextIndex
            setUrl(alternatePlayers[nextIndex].streamUrl, alternatePlayers[nextIndex].requestHeaders)
            error = null
            showControls = true
        } else {
            error = "No se encontraron más enlaces distintos para este contenido."
            showControls = true
        }
    }
    fun moveButtonFocus(delta: Int) {
        focusedButton = (focusedButton + delta).coerceIn(0, buttonCount - 1)
        runCatching { buttonFocusRequesters[focusedButton].requestFocus() }
    }
    fun applyLabAudio(index: Int) {
        labAudioSelected = index.coerceIn(0, 1)
        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
            .setPreferredAudioLanguage(if (labAudioSelected == 0) "es" else "en").build()
    }
    fun applyLabSubtitleLanguage(index: Int) {
        labSubtitleLanguage = index.coerceIn(0, 1)
        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
            .setPreferredTextLanguage(if (labSubtitleLanguage == 0) "es" else "en")
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !labSubtitlesEnabled).build()
    }
    fun moveLabFocus(delta: Int) {
        labFocusRow = (labFocusRow + delta).coerceIn(0, 8)
        labFocusOption = when (labFocusRow) {
            0 -> labAudioSelected
            2 -> 0
            3 -> labSubtitleSize
            4 -> labSubtitleStyle
            else -> 0
        }
    }
    fun moveLabOption(delta: Int) {
        if (labFocusRow == 5) { // sincronía: ◀ adelanta 0,5 s · ▶ atrasa 0,5 s
            labSubtitleDelayMs = (labSubtitleDelayMs + delta * 500L).coerceIn(-60_000L, 60_000L)
            return
        }
        val max = when (labFocusRow) { 0 -> 1; 3 -> 2; 4 -> 2; else -> 0 }
        labFocusOption = (labFocusOption + delta + max + 1) % (max + 1)
    }
    fun confirmLabFocus() {
        when (labFocusRow) {
            0 -> applyLabAudio(labFocusOption)
            1 -> {
                labSubtitlesEnabled = !labSubtitlesEnabled
                exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !labSubtitlesEnabled).build()
            }
            2 -> TrackSelectionDialogBuilder(context, "Pistas de subtítulos", exoPlayer, C.TRACK_TYPE_TEXT).build().show()
            3 -> labSubtitleSize = labFocusOption
            4 -> labSubtitleStyle = labFocusOption
            5 -> labSubtitleDelayMs = 0L
            6 -> labUploadOpen = !labUploadOpen
            7 -> TrackSelectionDialogBuilder(context, "Resolución", exoPlayer, C.TRACK_TYPE_VIDEO).build().show()
            8 -> labSettingsOpen = false
        }
    }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    fun reloadWithSavedSubtitles() {
        val item = exoPlayer.currentMediaItem ?: return
        val resumeAt = exoPlayer.currentPosition
        val wasPlaying = exoPlayer.playWhenReady
        val all = labSavedSubtitles + externalSubtitles
        exoPlayer.setMediaItem(item.buildUpon().setSubtitleConfigurations(subtitleConfigurations(all)).build(), resumeAt)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = wasPlaying
    }
    // Al cerrar el modal se cierra también el panel del QR (y se apaga el servidor).
    LaunchedEffect(labSettingsOpen) { if (!labSettingsOpen) labUploadOpen = false }
    // Servidor para subir subtítulos desde el celular: solo encendido mientras el panel del QR está abierto.
    val labUploadActive = labMode && labSettingsOpen && labUploadOpen
    DisposableEffect(labUploadActive, currentTitleId) {
        var server: LabSubtitleUploadServer? = null
        if (labUploadActive) {
            labUploadStatus = "Esperando archivo…"
            val created = LabSubtitleUploadServer(
                titleName = name,
                listSaved = { LabSubtitleStore.list(context, currentTitleId) },
                onUpload = { subName, bytes ->
                    try {
                        val saved = LabSubtitleStore.add(context, currentTitleId, subName, bytes)
                        mainHandler.post {
                            labSavedSubtitles = uploadedSubtitleTracks(context, currentTitleId)
                            labUploadStatus = "✓ Recibido: ${saved.name}"
                            reloadWithSavedSubtitles()
                        }
                        null
                    } catch (e: IllegalArgumentException) {
                        e.message ?: "No se pudo guardar el archivo."
                    } catch (e: Exception) {
                        "No se pudo guardar el archivo."
                    }
                },
                onDelete = { id ->
                    runCatching { LabSubtitleStore.remove(context, currentTitleId, id) }
                    mainHandler.post {
                        labSavedSubtitles = uploadedSubtitleTracks(context, currentTitleId)
                        labUploadStatus = "Subtítulo borrado"
                        reloadWithSavedSubtitles()
                    }
                },
            )
            server = created
            labUploadUrl = created.start()
            if (labUploadUrl == null) labUploadStatus = "La tele no está conectada a una red WiFi/cable."
        }
        onDispose {
            server?.stop()
            labUploadUrl = null
        }
    }
    LaunchedEffect(labSubtitleDelayMs) {
        if (!labMode || labSubtitleDelayMs == labAppliedDelayMs) return@LaunchedEffect
        delay(1_000) // si siguen tocando ◀ ▶ este bloque se cancela y espera de nuevo
        val item = exoPlayer.currentMediaItem ?: return@LaunchedEffect
        val resumeAt = exoPlayer.currentPosition
        val wasPlaying = exoPlayer.playWhenReady
        subtitleOffsetMs.set(labSubtitleDelayMs)
        labAppliedDelayMs = labSubtitleDelayMs
        exoPlayer.setMediaItem(item, resumeAt)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = wasPlaying
    }
    BackHandler(enabled = labMode && (labSettingsOpen || labPanelOpen)) {
        if (labUploadOpen) labUploadOpen = false else if (labSettingsOpen) labSettingsOpen = false else labPanelOpen = false
    }
    BackHandler(enabled = secondaryPickerOpen) {
        secondaryPickerOpen = false
        showControls = true
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black).focusRequester(rootFocusRequester).focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                if (secondaryPickerOpen) return@onPreviewKeyEvent false // el selector usa la navegación normal por foco
                if (showControls) hubTick += 1
                if (labMode) {
                    when (event.nativeKeyEvent.keyCode) {
                        AndroidKeyEvent.KEYCODE_BACK, AndroidKeyEvent.KEYCODE_ESCAPE -> {
                            if (labUploadOpen) labUploadOpen = false else if (labSettingsOpen) labSettingsOpen = false else if (labPanelOpen) labPanelOpen = false else onExit()
                            return@onPreviewKeyEvent true
                        }
                    }
                    when (event.key) {
                        Key.DirectionDown -> {
                            if (labSettingsOpen) moveLabFocus(1) else if (!labPanelOpen) { labPanelOpen = true; labSettingsOpen = false }
                            return@onPreviewKeyEvent true
                        }
                        Key.DirectionUp -> {
                            if (labSettingsOpen) moveLabFocus(-1) else if (labPanelOpen) labPanelOpen = false
                            return@onPreviewKeyEvent true
                        }
                        Key.DirectionLeft -> {
                            if (labSettingsOpen) moveLabOption(-1) else if (!labPanelOpen) {
                                val repeat = event.nativeKeyEvent.repeatCount
                                val seconds = when { repeat >= 5 -> 120; repeat >= 3 -> 30; repeat >= 1 -> 20; else -> 10 }
                                seekBy(-seconds * 1_000L); labSeekDirection = -1; labSeekLabel = "⏪  ${formatTime(exoPlayer.currentPosition)}"
                            }
                            return@onPreviewKeyEvent true
                        }
                        Key.DirectionRight -> {
                            if (labSettingsOpen) moveLabOption(1) else if (!labPanelOpen) {
                                val repeat = event.nativeKeyEvent.repeatCount
                                val seconds = when { repeat >= 5 -> 120; repeat >= 3 -> 30; repeat >= 1 -> 20; else -> 10 }
                                seekBy(seconds * 1_000L); labSeekDirection = 1; labSeekLabel = "⏩  ${formatTime(exoPlayer.currentPosition)}"
                            }
                            return@onPreviewKeyEvent true
                        }
                        Key.DirectionCenter, Key.Enter -> {
                            if (labSettingsOpen) confirmLabFocus()
                            else if (labPanelOpen) labSettingsOpen = true
                            else togglePlayPause()
                            return@onPreviewKeyEvent true
                        }
                        else -> {}
                    }
                }
                when (event.nativeKeyEvent.keyCode) {
                    AndroidKeyEvent.KEYCODE_CHANNEL_UP -> { changeChannelBy(1); return@onPreviewKeyEvent true }
                    AndroidKeyEvent.KEYCODE_CHANNEL_DOWN -> { changeChannelBy(-1); return@onPreviewKeyEvent true }
                    AndroidKeyEvent.KEYCODE_MEDIA_REWIND -> {
                        val repeat = event.nativeKeyEvent.repeatCount
                        val jumpSeconds = when { repeat >= 5 -> 120; repeat >= 3 -> 30; repeat >= 1 -> 20; else -> 10 }
                        seekBy(-jumpSeconds * 1_000L)
                        return@onPreviewKeyEvent true
                    }
                    AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                        val repeat = event.nativeKeyEvent.repeatCount
                        val jumpSeconds = when { repeat >= 5 -> 120; repeat >= 3 -> 30; repeat >= 1 -> 20; else -> 10 }
                        seekBy(jumpSeconds * 1_000L)
                        return@onPreviewKeyEvent true
                    }
                    AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { togglePlayPause(); return@onPreviewKeyEvent true }
                    AndroidKeyEvent.KEYCODE_MEDIA_PLAY -> { exoPlayer.play(); isPlaying = true; return@onPreviewKeyEvent true }
                    AndroidKeyEvent.KEYCODE_MEDIA_PAUSE -> { exoPlayer.pause(); isPlaying = false; return@onPreviewKeyEvent true }
                }
                val repeat = event.nativeKeyEvent.repeatCount
                val jumpSeconds = when { repeat >= 5 -> 120; repeat >= 3 -> 30; repeat >= 1 -> 20; else -> 10 }
                when (event.key) {
                    // En un canal en vivo saltar ±10 s no sirve: ◀ ▶ abren el hub y, con el hub abierto, pasan de un botón al otro.
                    Key.DirectionLeft -> {
                        if (isLive) { if (!showControls) showControls = true else moveButtonFocus(-1) } else seekBy(-jumpSeconds * 1_000L)
                        true
                    }
                    Key.DirectionRight -> {
                        if (isLive) { if (!showControls) showControls = true else moveButtonFocus(1) } else seekBy(jumpSeconds * 1_000L)
                        true
                    }
                    Key.DirectionCenter, Key.Enter -> {
                        if (!showControls) { showControls = true; true } else false
                    }
                    Key.DirectionUp -> {
                        if (!showControls) { showControls = true; true }
                        else { moveButtonFocus(-1); true }
                    }
                    Key.DirectionDown -> {
                        if (!showControls) { showControls = true; true }
                        else { moveButtonFocus(1); true }
                    }
                    else -> false
                }
            }
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = {
                PlayerView(context).apply {
                    player = exoPlayer
                    useController = false
                    keepScreenOn = true
                    isFocusable = true
                    isFocusableInTouchMode = true
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    setOnClickListener { if (!labMode) showControls = true }
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    labPlayerView = this
                }
            },
            update = { it.player = exoPlayer; if (labMode) labPlayerView = it },
        )
        if (reconnecting && error == null && !labMode) {
            Box(
                Modifier.align(Alignment.TopCenter).padding(top = 28.dp)
                    .background(Color(0xCC14202B), RoundedCornerShape(8.dp))
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Text("Reconectando… (intento $reconnectAttempt)", color = Color.White, fontSize = 15.sp)
            }
        }
        if (showControls && !labMode) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xB8000000), Color.Transparent, Color(0xE6000000)))))
            Column(Modifier.align(Alignment.TopStart).fillMaxWidth().padding(horizontal = 38.dp, vertical = 26.dp)) {
                Text("XUPER  ·  $name", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                if (failedSources > 0) Text("Fuente alternativa $failedSources", color = Color(0xFF72E8F2), fontSize = 13.sp)
            }
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 42.dp, vertical = 30.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (duration > 0) {
                    Box(Modifier.fillMaxWidth().background(Color(0x66FFFFFF), RoundedCornerShape(4.dp))) {
                        Box(
                            Modifier.fillMaxWidth((position.toFloat() / duration.toFloat()).coerceIn(0f, 1f))
                                .background(Color(0xFF65DCE8), RoundedCornerShape(4.dp)).padding(vertical = 2.dp)
                        )
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(formatTime(position), color = Color.White, fontSize = 13.sp)
                        Text("−${formatTime((duration - position).coerceAtLeast(0))}", color = Color.White, fontSize = 13.sp)
                    }
                }
                Text(
                    if (isLive) "◀/▶  cambiar control · OK  elegir · CH+/CH−  cambiar de canal · Atrás  salir"
                    else "◀/▶  saltar 10 s · ▲/▼  cambiar control · OK  ejecutar · Atrás  salir",
                    color = Color(0x99FFFFFF), fontSize = 11.sp,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(modifier = Modifier.focusRequester(buttonFocusRequesters[0]), onClick = { togglePlayPause() }) {
                        Text(if (isPlaying) "Ⅱ  Pausar" else "▶  Reproducir")
                    }
                    Button(modifier = Modifier.focusRequester(buttonFocusRequesters[1]), onClick = {
                        if (!isLive) changeSource()
                        else if (secondaryChoices.isNotEmpty()) secondaryPickerOpen = true
                        else hubNotice = "Este canal no tiene secundarios. En ARGENTINA, mantené apretado un canal y elegí «Añadir secundario»."
                    }) {
                        Text(
                            when {
                                !isLive -> "⟳  Cambiar reproductor"
                                secondaryChoices.isEmpty() -> "📺  Sin canales secundarios"
                                else -> "📺  Elegir canal secundario (${secondaryChoices.size})"
                            }
                        )
                    }
                    Button(modifier = Modifier.focusRequester(buttonFocusRequesters[2]), onClick = {
                        showControls = true
                        TrackSelectionDialogBuilder(context, "Audio", exoPlayer, C.TRACK_TYPE_AUDIO).build().show()
                    }) { Text("♫  Audio") }
                    Button(modifier = Modifier.focusRequester(buttonFocusRequesters[3]), onClick = {
                        showControls = true
                        TrackSelectionDialogBuilder(context, "Subtítulos", exoPlayer, C.TRACK_TYPE_TEXT).build().show()
                    }) { Text("CC  Subtítulos") }
                }
                hubNotice?.let { notice ->
                    Text(notice, color = Color.White, fontSize = 14.sp, modifier = Modifier.background(Color(0xCC14202B), RoundedCornerShape(8.dp)).padding(12.dp))
                }
                if (error != null) {
                    Text(error!!, color = Color.White, fontSize = 15.sp, modifier = Modifier.background(Color(0xCC14202B), RoundedCornerShape(8.dp)).padding(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = { reconnectAttempt = 0; reconnecting = false; setUrl(exoPlayer.currentMediaItem?.localConfiguration?.uri?.toString() ?: streamUrl, streamHeaders) }) { Text("Reintentar") }
                        if (isLive && secondaryChoices.isNotEmpty()) {
                            Button(onClick = { secondaryPickerOpen = true }) { Text("📺  Canal secundario (${secondaryChoices.size})") }
                        }
                        if (channelQueue.size > 1) {
                            Button(onClick = { changeChannelBy(-1) }) { Text("◀  Canal anterior") }
                            Button(onClick = { changeChannelBy(1) }) { Text("Canal siguiente  ▶") }
                        }
                    }
                }
            }
        }
        if (secondaryPickerOpen && isLive) {
            Box(Modifier.fillMaxSize().background(Color(0xB3000000)))
            Column(
                Modifier.align(Alignment.Center).width(460.dp)
                    .background(Color(0xF21B242B), RoundedCornerShape(18.dp)).padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Elegí un canal", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Canal actual: $name", color = Color(0xFFB4C0CE), fontSize = 13.sp,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                secondaryChoices.forEachIndexed { index, choice ->
                    val isPrincipal = choice.id == relatedChannels.firstOrNull()?.id
                    Button(
                        modifier = Modifier.fillMaxWidth().then(if (index == 0) Modifier.focusRequester(pickerFirstFocus) else Modifier),
                        onClick = {
                            secondaryPickerOpen = false
                            // El zapping (CH+/CH−) queda dentro de este grupo: principal + secundarios.
                            ChannelQueueStore.set(relatedChannels)
                            onChangeChannel(choice.id)
                        },
                    ) { Text((if (isPrincipal) "★ Principal · " else "") + choice.name, maxLines = 1) }
                }
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { secondaryPickerOpen = false; showControls = true },
                ) { Text("Cancelar") }
            }
        }
        if (labMode && labPanelOpen) {
            Column(
                Modifier.align(Alignment.BottomStart).padding(start = 42.dp, bottom = 34.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                Text("IDIOMA", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Box(
                    Modifier.width(190.dp).background(
                        if (!labSettingsOpen) Color(0xFF83E6D2) else Color(0xFF477E75), RoundedCornerShape(22.dp)
                    ).padding(horizontal = 18.dp, vertical = 11.dp)
                ) { Text("Configuración", color = Color(0xFF10221F), fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
                Text("RESOLUCIÓN", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Box(Modifier.width(190.dp).background(Color(0xFFE1E5E4), RoundedCornerShape(22.dp)).padding(horizontal = 18.dp, vertical = 10.dp)) {
                    Text("●  Automático", color = Color(0xFF26312F), fontSize = 14.sp)
                }
            }
        }
        if (labMode && !labSettingsOpen && !labPanelOpen && !isPlaying) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Box(Modifier.width(76.dp).height(76.dp).background(Color(0xFF168DC0), RoundedCornerShape(50)), contentAlignment = Alignment.Center) {
                    Text("▶", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        if (labMode && !labSettingsOpen && !labPanelOpen && labSeekDirection != 0) {
            LaunchedEffect(labSeekLabel) { delay(1_500); labSeekDirection = 0 }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Box(Modifier.background(Color(0xCC168DC0), RoundedCornerShape(50)).padding(horizontal = 24.dp, vertical = 16.dp)) {
                    Text(labSeekLabel, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        if (labMode && !labSettingsOpen && !labPanelOpen && labSeekDirection != 0 && duration > 0L) {
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 46.dp, vertical = 28.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(formatTime(position), color = Color(0xFF83E6D2), fontSize = 13.sp)
                Box(Modifier.fillMaxWidth().height(4.dp).background(Color(0x88FFFFFF), RoundedCornerShape(4.dp))) {
                    Box(Modifier.fillMaxWidth((position.toFloat() / duration.toFloat()).coerceIn(0f, 1f)).height(4.dp).background(Color(0xFF83E6D2), RoundedCornerShape(4.dp)))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text(formatTime(duration), color = Color.White, fontSize = 13.sp)
                }
            }
        }
        if (labMode && labSettingsOpen) {
            // Con pantalla ancha (tele) el QR va al lado del modal; en pantallas angostas (celular) se superpone al centro.
            val qrBeside = LocalConfiguration.current.screenWidthDp >= 960
            Box(Modifier.fillMaxSize().background(Color(0x99000000)))
            Column(
                Modifier.align(Alignment.Center).offset(x = if (labUploadOpen && qrBeside) (-145).dp else 0.dp).width(650.dp)
                    .background(Color(0xF21B242B), RoundedCornerShape(20.dp)).padding(horizontal = 30.dp, vertical = 22.dp),
                verticalArrangement = Arrangement.spacedBy(13.dp)
            ) {
                Text("Ajuste de reproducir", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.CenterHorizontally))
                Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF53616A)))
                LabSettingsRow("Audio", listOf("Español", "Inglés"), labAudioSelected, labFocusRow == 0, labFocusOption, false)
                LabToggleRow(labSubtitlesEnabled, labFocusRow == 1)
                LabSettingsRow("Pistas de subtítulos", listOf("Elegir pista"), 0, labFocusRow == 2, 0, true)
                LabSettingsRow("Tamaño", listOf("Normal", "Grande", "Pequeño"), labSubtitleSize, labFocusRow == 3, labFocusOption, true)
                LabStyleRow(labSubtitleStyle, labFocusRow == 4, labFocusOption)
                LabSyncRow(labSubtitleDelayMs, labFocusRow == 5, labSubtitleDelayMs != labAppliedDelayMs)
                LabSettingsRow("Subir subtítulos", listOf(if (labUploadOpen) "Cerrar QR" else "Subir desde el celular"), 0, labFocusRow == 6, 0, true)
                LabSettingsRow("Resolución", listOf("Automático / 720p / 1080p disponibles"), 0, labFocusRow == 7, 0, true)
                Box(Modifier.fillMaxWidth().padding(top = 3.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.width(220.dp).background(if (labFocusRow == 8) Brush.horizontalGradient(listOf(Color(0xFF00B98D), Color(0xFF168DC0))) else Brush.horizontalGradient(listOf(Color(0xFF39444C), Color(0xFF39444C))), RoundedCornerShape(22.dp)).padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                        Text("Salida", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            if (labUploadOpen) {
                Column(
                    Modifier.align(Alignment.Center).offset(x = if (qrBeside) 335.dp else 0.dp).width(270.dp)
                        .background(Color(0xF21B242B), RoundedCornerShape(20.dp)).padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("Subir subtítulos", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    val url = labUploadUrl
                    val qr = remember(url) { url?.let { qrImageBitmap(it) } }
                    if (qr != null) {
                        Box(Modifier.background(Color.White, RoundedCornerShape(10.dp)).padding(6.dp)) {
                            Image(bitmap = qr, contentDescription = "QR para subir subtítulos", modifier = Modifier.size(190.dp))
                        }
                        Text("Escaneá con el celular (mismo WiFi que la tele)", color = Color(0xFFB4C0CE), fontSize = 12.sp, textAlign = TextAlign.Center)
                        Text(url.orEmpty().substringBefore("?"), color = Color(0xFF9DB0BA), fontSize = 11.sp, textAlign = TextAlign.Center)
                    }
                    Text(labUploadStatus, color = if (labUploadStatus.startsWith("✓")) Color(0xFF83E6D2) else Color(0xFFB4C0CE), fontSize = 13.sp, textAlign = TextAlign.Center)
                    Text("Guardados para este título: ${labSavedSubtitles.size}", color = Color(0xFF9DB0BA), fontSize = 12.sp)
                }
            }
        }
        if (showNextEpisode && nextEpisode != null && !labMode) {
            val secondsLeft = (((duration - position).coerceAtLeast(0L) + 999L) / 1000L).toInt().coerceIn(0, 30)
            NextEpisodeOverlay(
                episode = nextEpisode,
                secondsLeft = secondsLeft,
                onPlayNow = {
                    if (!nextEpisodeTriggered) {
                        nextEpisodeTriggered = true
                        onPlayNextEpisode(nextEpisode.id)
                    }
                },
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 24.dp, bottom = if (showControls) 158.dp else 24.dp),
            )
        }
    }
}

/** Pistas subidas desde el celular para este título (más la marca "Subido"), listas para el reproductor. */
private fun uploadedSubtitleTracks(context: Context, titleId: String): List<ExternalSubtitleTrack> =
    LabSubtitleStore.list(context, titleId).map { saved ->
        ExternalSubtitleTrack(
            url = android.net.Uri.fromFile(LabSubtitleStore.file(context, titleId, saved)).toString(),
            referer = "",
            language = "es",
            label = saved.name,
            mimeType = saved.mimeType,
            flags = ExternalSubtitleTrack.FLAG_DEFAULT,
            source = "Subido",
        )
    }

private fun subtitleConfigurations(tracks: List<ExternalSubtitleTrack>): List<MediaItem.SubtitleConfiguration> =
    tracks.distinctBy { it.url }.map { track ->
        var selectionFlags = 0
        if (track.flags and ExternalSubtitleTrack.FLAG_FORCED != 0) selectionFlags = selectionFlags or C.SELECTION_FLAG_FORCED
        if (track.flags and ExternalSubtitleTrack.FLAG_DEFAULT != 0) selectionFlags = selectionFlags or C.SELECTION_FLAG_DEFAULT
        MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(track.url))
            .setMimeType(track.mimeType)
            .setLanguage(track.language)
            .setLabel(track.label + (track.source?.let { " · $it" } ?: ""))
            .setSelectionFlags(selectionFlags)
            .setRoleFlags(if (track.flags and ExternalSubtitleTrack.FLAG_HEARING_IMPAIRED != 0) C.ROLE_FLAG_CAPTION else 0)
            .build()
    }

@Composable
private fun LabSettingsRow(
    label: String,
    options: List<String>,
    selected: Int,
    rowFocused: Boolean,
    focusedOption: Int,
    checkmark: Boolean,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, fontSize = if (label == "Subtítulo") 18.sp else 15.sp, fontWeight = if (label == "Subtítulo") FontWeight.Bold else FontWeight.Medium, modifier = Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            options.forEachIndexed { index, option ->
                val focused = rowFocused && focusedOption == index
                val chosen = selected == index
                Box(
                    Modifier.widthIn(min = if (options.size == 3) 105.dp else 125.dp)
                        .background(
                            if (focused) Brush.horizontalGradient(listOf(Color(0xFF00B98D), Color(0xFF168DC0)))
                            else Brush.horizontalGradient(listOf(Color(0xFF39444C), Color(0xFF39444C))), RoundedCornerShape(22.dp)
                        ).padding(horizontal = 13.dp, vertical = 9.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(option, color = Color.White, fontSize = 13.sp, maxLines = 1)
                        if (chosen) Text(if (checkmark) "✓" else "●", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

private fun formatLabDelay(ms: Long): String =
    if (ms == 0L) "0,0 s" else String.format(java.util.Locale("es"), "%+.1f s", ms / 1000.0)

@Composable
private fun LabSyncRow(delayMs: Long, rowFocused: Boolean, pending: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Sincronía de subtítulos", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(
                if (pending) "Aplicando…" else "◀ adelantar · atrasar ▶ · OK = 0",
                color = Color(0xFF9DB0BA), fontSize = 11.sp, maxLines = 1,
            )
        }
        Box(
            Modifier.width(190.dp)
                .background(
                    if (rowFocused) Brush.horizontalGradient(listOf(Color(0xFF00B98D), Color(0xFF168DC0)))
                    else Brush.horizontalGradient(listOf(Color(0xFF39444C), Color(0xFF39444C))), RoundedCornerShape(22.dp)
                ).padding(horizontal = 14.dp, vertical = 9.dp),
            contentAlignment = Alignment.Center
        ) {
            Text("◀   ${formatLabDelay(delayMs)}   ▶", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

@Composable
private fun LabToggleRow(enabled: Boolean, focused: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Subtítulo", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Box(Modifier.width(125.dp).background(if (focused) Brush.horizontalGradient(listOf(Color(0xFF00B98D), Color(0xFF168DC0))) else Brush.horizontalGradient(listOf(Color(0xFF245F91), Color(0xFF245F91))), RoundedCornerShape(22.dp)).padding(horizontal = 14.dp, vertical = 9.dp), contentAlignment = Alignment.Center) {
            Text(if (enabled) "●  ON" else "○  OFF", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun LabStyleRow(selected: Int, rowFocused: Boolean, focusedOption: Int) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Estilo", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Aa", "Aa", "Aa").forEachIndexed { index, value ->
                val focused = rowFocused && focusedOption == index
                val normalBackground = when (index) { 1 -> Color(0xFF252D33); 2 -> Color.Black; else -> Color(0xFF59636A) }
                Box(Modifier.width(82.dp).background(if (focused) Brush.horizontalGradient(listOf(Color(0xFF00B98D), Color(0xFF168DC0))) else Brush.horizontalGradient(listOf(normalBackground, normalBackground)), RoundedCornerShape(18.dp)).padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(value, color = if (index == 1) Color.Yellow else Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        if (selected == index) Text("✓", color = Color.White, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun NextEpisodeOverlay(
    episode: Title,
    secondsLeft: Int,
    onPlayNow: () -> Unit,
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null,
    playFocusRequester: FocusRequester? = null,
    dismissFocusRequester: FocusRequester? = null,
) {
    val match = Regex("^tmdb-tv-(\\d+)\\|s(\\d+)\\|e(\\d+)$").matchEntire(episode.id)
    val episodeMeta = match?.destructured?.let { (_, season, number) -> "Temporada $season · Episodio $number" } ?: "Próximo episodio"
    Row(
        modifier = modifier.widthIn(max = 430.dp).background(Color(0xF018222C), RoundedCornerShape(14.dp)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AsyncImage(
            model = episode.posterUrl.ifBlank { episode.backdropUrl },
            contentDescription = episode.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.width(124.dp).height(76.dp).background(Color(0xFF26323D), RoundedCornerShape(8.dp)),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("EPISODIO SIGUIENTE", color = Color(0xFF65DCE8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text(episode.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
            Text(episodeMeta, color = Color(0xFFCFD8DE), fontSize = 12.sp)
            Text(if (secondsLeft > 0) "Episodio siguiente en $secondsLeft…" else "Se reproducirá automáticamente", color = Color.White, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(
                    modifier = playFocusRequester?.let { Modifier.focusRequester(it) } ?: Modifier,
                    onClick = onPlayNow,
                ) { Text("▶ Ahora") }
                if (onDismiss != null) {
                    Button(
                        modifier = dismissFocusRequester?.let { Modifier.focusRequester(it) } ?: Modifier,
                        onClick = onDismiss,
                    ) { Text("Cancelar") }
                }
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

@Composable
private fun WebPageResolverPlayer(name: String, pageUrl: String) {
    var attempt by remember(pageUrl) { mutableStateOf(0) }
    var result by remember(pageUrl) { mutableStateOf<WebPageMediaResolver.Result?>(null) }
    LaunchedEffect(pageUrl, attempt) {
        result = null
        result = WebPageMediaResolver.resolve(pageUrl)
    }
    when (val resolved = result) {
        null -> Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Buscando una fuente declarada por la página…", color = Color.White, fontSize = 18.sp)
                Text("Esto puede tardar unos segundos", color = Color(0xFF9CCBD4), fontSize = 13.sp)
            }
        }
        is WebPageMediaResolver.Result.DirectMedia -> PlayerScreen(name = name, streamUrl = resolved.url, directOnly = true)
        is WebPageMediaResolver.Result.EmbedPage -> WebEmbedPlayer(url = resolved.url)
        is WebPageMediaResolver.Result.NotFound -> Box(
            Modifier.fillMaxSize().background(Color(0xFF07111B)).padding(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("No se encontró una fuente reproducible", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(resolved.reason, color = Color(0xFFB9CDD6), fontSize = 15.sp)
                Text("El extractor solo lee fuentes públicas del HTML inicial. Si el sitio construye el reproductor con JavaScript, la app no puede deducir una URL de vídeo fiable con Jsoup.", color = Color(0xFF8BA7B5), fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { Button(onClick = { attempt++ }) { Text("Reintentar") } }
            }
        }
    }
}

private fun dispatchWebKey(webView: WebView?, keyCode: Int) {
    if (webView == null) return
    runCatching {
        val down = AndroidKeyEvent(AndroidKeyEvent.ACTION_DOWN, keyCode)
        val up = AndroidKeyEvent(AndroidKeyEvent.ACTION_UP, keyCode)
        if (webView is HubAwareWebView) {
            // Do not call requestFocus(): OK.ru/Latino may need the iframe's current focus.
            // Bypass the interceptor to avoid recursively re-running the same fallback command.
            webView.dispatchInjectedKeyEvent(down)
            webView.dispatchInjectedKeyEvent(up)
        } else {
            webView.dispatchKeyEvent(down)
            webView.dispatchKeyEvent(up)
        }
    }
}

private fun webPlaybackResumeScript(resumePositionSeconds: Long, autoSelectSpanish: Boolean = false): String = """(function(){
  if(window.__xuperPlaybackTracking) return; window.__xuperPlaybackTracking=true;
  var resume=$resumePositionSeconds;
  function visit(w){
    var d; try{d=w.document;}catch(e){return;}
    try{
      Array.from(d.querySelectorAll('video')).forEach(function(v){
        if($autoSelectSpanish){try{var tracks=v.textTracks;if(tracks){var spanish=Array.from(tracks).find(function(t){var x=((t.language||'')+' '+(t.label||'')).toLowerCase();return /(^|[^a-z])(es|spa|spanish|espanol|español|castellano)([^a-z]|$)/i.test(x)});if(spanish&&spanish.mode==='disabled')spanish.mode='showing';}}catch(e){}}
        if(v.dataset.xuperTracking==='1') return; v.dataset.xuperTracking='1';
        var restore=function(){try{if(resume>5&&!v.dataset.xuperRestored&&isFinite(v.duration)&&v.duration>0){v.dataset.xuperRestored='1';v.currentTime=Math.min(resume,Math.max(0,v.duration-2));}}catch(e){}};
        v.addEventListener('loadedmetadata',restore); if(v.readyState>=1)restore();
        v.addEventListener('timeupdate',function(){try{if(window.XuperBridge)window.XuperBridge.onTimeUpdate(Number(v.currentTime)||0,Number(v.duration)||0);}catch(e){}});
      });
      for(var i=0;i<w.frames.length;i++)try{visit(w.frames[i]);}catch(e){}
    }catch(e){}
  }
  visit(window); setInterval(function(){visit(window);},2000);
})();"""

private fun simuladoresAutoplayUrl(rawUrl: String): String {
    val beforeHash = rawUrl.substringBefore('#')
    val hash = rawUrl.substringAfter('#', "").let { if (rawUrl.contains('#')) "#$it" else "" }
    val existing = Regex("([?&])autoplay=[^&]*", RegexOption.IGNORE_CASE)
    if (existing.containsMatchIn(beforeHash)) return existing.replace(beforeHash) { "${it.groupValues[1]}autoplay=1" } + hash
    return beforeHash + if (beforeHash.contains('?')) "&autoplay=1" + hash else "?autoplay=1" + hash
}

/** Best-effort autoplay kick for the Simuladores web player, including dynamically inserted HTML5 videos. */
private fun simuladoresAutoplayScript(): String = """(function(){
  if(window.__xuperSimuladoresAutoplayInstalled) return;
  window.__xuperSimuladoresAutoplayInstalled=true;
  var attempts=0, maxAttempts=24;
  function tryPlay(){
    attempts++;
    try{
      document.querySelectorAll('iframe').forEach(function(f){
        try{
          var allow=(f.getAttribute('allow')||'');
          if(!/autoplay/i.test(allow))f.setAttribute('allow',(allow?allow+'; ':'')+'autoplay; fullscreen; encrypted-media; picture-in-picture');
          var src=f.getAttribute('src')||'';
          if(/(^|\.)ok\.ru\//i.test(src.replace(/^https?:\/\//i,'')) && !f.dataset.xuperAutoplayUrl){
            f.dataset.xuperAutoplayUrl='1';
            if(!/[?&]autoplay=/i.test(src)) f.setAttribute('src',src+(src.indexOf('?')>=0?'&':'?')+'autoplay=1');
          }
        }catch(e){}
      });
      document.querySelectorAll('video').forEach(function(v){
        try{
          v.setAttribute('playsinline','');
          if(v.readyState>=1 && v.paused){var p=v.play();if(p&&p.catch)p.catch(function(){try{v.muted=true;var q=v.play();if(q&&q.catch)q.catch(function(){});setTimeout(function(){try{v.muted=false;}catch(e){}},1200);}catch(e){}});}
        }catch(e){}
      });
      if(attempts===1){
        var buttons=Array.from(document.querySelectorAll('button,[role=button],a[role=button]'));
        var play=buttons.find(function(b){return /play|reproducir|ver ahora|iniciar/i.test(((b.getAttribute('aria-label')||'')+' '+(b.getAttribute('title')||'')+' '+(b.innerText||'')).toLowerCase());});
        if(play)try{play.click();}catch(e){}
      }
    }catch(e){}
    if(attempts<maxAttempts)setTimeout(tryPlay,750);
  }
  tryPlay();
})();"""

/** Duración mínima (s) para tratar un <video> web como el capítulo y no como publicidad/intro corta. */
private const val MIN_WEB_EPISODE_SECONDS = 120.0

@Composable
private fun WebEmbedPlayer(
    url: String,
    contentKey: String = url,
    nextEpisode: Title? = null,
    onPlayNextEpisode: (String) -> Unit = {},
    onExit: () -> Unit = {},
) {
    val context = LocalContext.current
    val playbackPrefs = remember(context) { context.getSharedPreferences("xuper-playback", Context.MODE_PRIVATE) }
    val stableContentKey = contentKey.ifBlank { url }
    val subtitlePreferenceKey = "web-subtitle:$stableContentKey"
    val positionPreferenceKey = "web-position:$stableContentKey"
    val savedSubtitleIndex = remember(url, stableContentKey) { playbackPrefs.getInt(subtitlePreferenceKey, -1) }
    val savedResumePosition = remember(url, stableContentKey) { playbackPrefs.getLong(positionPreferenceKey, 0L).coerceAtLeast(0L) }
    var saveSubtitleForContent by remember(url, stableContentKey) { mutableStateOf(savedSubtitleIndex >= 0) }
    var progress by remember(url) { mutableStateOf(0) }
    var pageError by remember(url) { mutableStateOf<String?>(null) }
    var pageFinished by remember(url) { mutableStateOf(false) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    // Prueba: URL de VideoAPI marcada con "#xuper-prueba". Se trata como página web normal (navegación de mando de
    // fábrica, igual que Vidly/VidLux), SIN el hub ni el agente de frames de Latino.
    val isPrueba = remember(url) { WebSourceRegistry.isVideoApiPruebaUrl(url) }
    val isVideoApi = remember(url) { !isPrueba && runCatching { WebSourceRegistry.isVideoApiHost(URI(url).host) }.getOrDefault(false) }
    // Vidly (botón "Reproducir"): reproductor principal. El control remoto lo maneja
    // XUPER_REMOTE_NAV_JS (navegación entre los controles que muestra la propia página).
    // CineSrc (categoría LAB) usa el mismo esquema web que Vidly: navegación de mando de Xuper sobre la página
    // y consumo de las flechas (consumeDpad). Se agrupa acá para no duplicar ramas en el resto del reproductor.
    val isCineSrc = remember(url) { runCatching { WebSourceRegistry.isCineSrcHost(URI(url).host) }.getOrDefault(false) }
    val isVidly = remember(url) { runCatching { WebSourceRegistry.isVidlyHost(URI(url).host) }.getOrDefault(false) || isCineSrc }
    val isVidLux = remember(url) { runCatching { WebSourceRegistry.isVidLuxHost(URI(url).host) }.getOrDefault(false) }
    val isLatinoFamily = isVideoApi
    val remoteNavSupported = remember {
        (isVidly || isVidLux || isPrueba) && runCatching { WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) }.getOrDefault(false)
    }
    // Script de navegación: Prueba le suma el modo reposo (ver IDLE_MS en XUPER_REMOTE_NAV_JS); el resto, sin cambios.
    val remoteNavScript = remember(isPrueba) { if (isPrueba) "window.__xuperIdleHideMs=4000;\n$XUPER_REMOTE_NAV_JS" else XUPER_REMOTE_NAV_JS }
    val isOkRu = remember(url) { runCatching { URI(url).host?.lowercase()?.let { it == "ok.ru" || it.endsWith(".ok.ru") } == true }.getOrDefault(false) }
    val isSimuladores = remember(url) { runCatching { URI(url).host?.lowercase()?.let { it == "los-simuladores.bringfeel.com.ar" || it.endsWith(".bringfeel.com.ar") } == true }.getOrDefault(false) }
    val hasCustomHub = isLatinoFamily || isOkRu || isSimuladores
    // Latino/VideoAPI: su API pública no documenta ningún canal de control (post_message/communication
    // figura como "bloqueado"). El hub de Xuper solo se ofrece cuando una sonda confirma que hay un
    // <video> accesible desde el documento; si no, el mando va directo al reproductor original.
    var latinoDomControl by remember(url) { mutableStateOf(false) }
    // Agente por frame (ver XUPER_FRAME_AGENT_JS): permite controlar el <video> aunque esté en un iframe de
    // otro origen. Latino, OK.ru y Simuladores lo usan.
    val useFrameAgent = isLatinoFamily || isOkRu || isSimuladores
    val agentSupported = remember { useFrameAgent && runCatching { WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) }.getOrDefault(false) }
    var agentInstalled by remember { mutableStateOf(false) }
    var agentTarget by remember { mutableStateOf<String?>(null) }
    val agentReports = remember { HashMap<String, AgentVideoReport>() }
    val agentActive = agentInstalled && agentTarget != null
    val hubActive = hasCustomHub && (!isLatinoFamily || latinoDomControl || agentActive)
    var frameCurrentTime by remember(url) { mutableStateOf(savedResumePosition.toDouble()) }
    var frameDuration by remember(url) { mutableStateOf(0.0) }
    var nextEpisodeTriggered by remember(stableContentKey, nextEpisode?.id) { mutableStateOf(false) }
    var nextEpisodeDismissed by remember(stableContentKey, nextEpisode?.id) { mutableStateOf(false) }
    var videoEnded by remember(url, nextEpisode?.id) { mutableStateOf(false) }
    var framePaused by remember(url) { mutableStateOf(false) }
    var frameMuted by remember(url) { mutableStateOf(false) }
    var frameSubtitleIndex by remember(url, stableContentKey) { mutableStateOf(savedSubtitleIndex.coerceAtLeast(0)) }
    var latinoAudioTracks by remember(url) { mutableStateOf(emptyList<String>()) }
    var latinoSubtitleTracks by remember(url) { mutableStateOf(emptyList<String>()) }
    var latinoAudioSelected by remember(url) { mutableStateOf(-1) }
    var latinoSubtitleSelected by remember(url) { mutableStateOf(-1) }
    // Latino arranca sin hub (solo aparece si la sonda confirma que puede controlarse).
    var frameControlsVisible by remember(url) { mutableStateOf(!isLatinoFamily) }
    var frameInteraction by remember(url) { mutableStateOf(0) }
    // A TV remote often sends only one key-down on long press. Keep seeking while held.
    var heldSeekAction by remember(url) { mutableStateOf<String?>(null) }
    // Avance sostenido: se acumula mientras la tecla está apretada y se aplica al soltarla (ver SeekRamp).
    val scrubber = remember { SeekScrubber() }
    var scrubPending by remember { mutableStateOf(0) }
    var scrubFlash by remember { mutableStateOf<String?>(null) }
    var hubHasFocus by remember(url) { mutableStateOf(false) }
    var showLatinoHint by remember(url) { mutableStateOf(true) }
    val focusManager = LocalFocusManager.current
    val frameControlsFocus = remember(url) { FocusRequester() }
    val hiddenHubFocus = remember(url) { FocusRequester() }
    val nextEpisodePlayFocus = remember(url, nextEpisode?.id) { FocusRequester() }
    val nextEpisodeDismissFocus = remember(url, nextEpisode?.id) { FocusRequester() }

    // Video principal = el de mayor área entre los frames que informaron en los últimos 6 s.
    val refreshAgentPrimary: () -> Unit = refresh@{
        val now = SystemClock.uptimeMillis()
        val iter = agentReports.entries.iterator()
        while (iter.hasNext()) if (now - iter.next().value.atMs > 6_000L) iter.remove()
        var bestId: String? = null
        var best: AgentVideoReport? = null
        for ((id, r) in agentReports) {
            if (r.area < 2_500.0) continue
            val b = best
            if (b == null || r.area > b.area || (r.area == b.area && r.duration > b.duration)) { bestId = id; best = r }
        }
        if (agentTarget != bestId) {
            agentTarget = bestId
            android.util.Log.i("XuperAgent", "video principal: $bestId")
        }
        if (best != null) {
            if (framePaused != best.paused) framePaused = best.paused
            if (frameMuted != best.muted) frameMuted = best.muted
            if (latinoAudioTracks != best.audioTracks) latinoAudioTracks = best.audioTracks
            if (latinoSubtitleTracks != best.subtitleTracks) latinoSubtitleTracks = best.subtitleTracks
            if (latinoAudioSelected != best.audioSelected) latinoAudioSelected = best.audioSelected
            if (latinoSubtitleSelected != best.subtitleSelected) latinoSubtitleSelected = best.subtitleSelected
            frameCurrentTime = best.current
            if (best.duration > 0.0) frameDuration = best.duration
            if (best.duration >= MIN_WEB_EPISODE_SECONDS && best.duration - best.current <= 1.0) videoEnded = true
        }
    }
    val handleAgentReport: (String, String) -> Unit = report@{ frameId, raw ->
        val json = runCatching { org.json.JSONObject(raw) }.getOrNull() ?: return@report
        if (json.optBoolean("none", false)) {
            agentReports.remove(frameId)
        } else {
            val isNew = !agentReports.containsKey(frameId)
            fun readLabels(key: String): List<String> {
                val array = json.optJSONArray(key) ?: return emptyList()
                return (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
            }
            val r = AgentVideoReport(
                area = json.optDouble("area", 0.0),
                current = json.optDouble("cur", 0.0),
                duration = json.optDouble("dur", 0.0),
                paused = json.optBoolean("paused", false),
                muted = json.optBoolean("muted", false),
                audioTracks = readLabels("audio"),
                audioSelected = json.optInt("audioSelected", -1),
                subtitleTracks = readLabels("subs"),
                subtitleSelected = json.optInt("subtitleSelected", -1),
                atMs = SystemClock.uptimeMillis(),
            )
            agentReports[frameId] = r
            if (isNew) android.util.Log.i("XuperAgent", "frame $frameId: video de ${r.area.toInt()} px², duración ${r.duration.toInt()} s")
        }
        refreshAgentPrimary()
    }
    // El puente JS del WebView se crea una sola vez: se lee siempre la versión más reciente.
    val handleAgentReportState = rememberUpdatedState(handleAgentReport)

    // No imponer un timeout artificial: algunos proveedores tardan más de 30 segundos en iniciar.
    // Los errores reales de WebView siguen mostrándose mediante onReceivedError/onReceivedHttpError.
    LaunchedEffect(hubActive, frameControlsVisible) {
        if (hubActive && frameControlsVisible) {
            delay(180)
            runCatching { frameControlsFocus.requestFocus() }
        } else if (hubActive) {
            hubHasFocus = false
            delay(100)
            runCatching {
                webViewRef?.evaluateJavascript("Array.from(document.querySelectorAll('iframe')).forEach(function(f){try{f.blur()}catch(e){}}); if(document.activeElement&&document.activeElement.blur)document.activeElement.blur();", null)
                hiddenHubFocus.requestFocus()
            }
        }
    }
    // El hub se oculta 5 s después de la última interacción (tecla, comando o cambio de foco);
    // cualquier interacción reinicia la cuenta porque cambia `frameInteraction`.
    LaunchedEffect(hubActive, frameControlsVisible, frameInteraction) {
        if (hubActive && frameControlsVisible) {
            delay(5_000)
            frameControlsVisible = false
        }
    }

    // Latino: sonda periódica. Con 3 fallos seguidos se considera que ya no es controlable.
    LaunchedEffect(isLatinoFamily, pageFinished) {
        // Con el agente instalado la sonda del documento principal sobra (el agente también corre en él).
        if (!isLatinoFamily || !pageFinished || agentInstalled) return@LaunchedEffect
        var misses = 0
        while (true) {
            webViewRef?.evaluateJavascript(videoApiCommandScript("probe", 0, 0)) { raw ->
                if (raw == "true") {
                    misses = 0
                    latinoDomControl = true
                } else if (++misses >= 3) {
                    latinoDomControl = false
                }
            }
            delay(1_500)
        }
    }
    // Latino sin control por DOM: el WebView conserva el foco para que el mando llegue al reproductor original.
    LaunchedEffect(isLatinoFamily, hubActive) {
        if (isLatinoFamily && !hubActive) {
            frameControlsVisible = false
            delay(150)
            runCatching { webViewRef?.requestFocus() }
        }
    }
    // Latino: cuando el video pasa a ser controlable el hub se muestra unos segundos (luego se oculta solo).
    LaunchedEffect(isLatinoFamily, hubActive) {
        if (isLatinoFamily && hubActive) frameControlsVisible = true
    }
    // El aviso de Latino se cuenta desde que la página terminó de cargar (antes podía vencer durante la carga).
    LaunchedEffect(url, pageFinished) {
        if (pageFinished) {
            delay(9_000)
            showLatinoHint = false
        }
    }
    // Un frame que se fue deja de informar: se purga el video principal aunque no llegue ningún aviso.
    LaunchedEffect(agentInstalled) {
        if (!agentInstalled) return@LaunchedEffect
        while (true) {
            delay(2_000)
            refreshAgentPrimary()
        }
    }
    LaunchedEffect(videoEnded, nextEpisode?.id, nextEpisodeDismissed, nextEpisodeTriggered) {
        if (videoEnded && nextEpisode != null && !nextEpisodeDismissed && !nextEpisodeTriggered) {
            delay(2_000)
            if (!nextEpisodeDismissed && !nextEpisodeTriggered) {
                nextEpisodeTriggered = true
                onPlayNextEpisode(nextEpisode.id)
            }
        }
    }
    val frameRemainingSeconds = (frameDuration - frameCurrentTime).coerceAtLeast(0.0)
    // Latino/VideoAPI, OK.ru y Simuladores informan tiempo/duración reales del <video> (agente de frames o
    // listener timeupdate), así que también usan la cuenta regresiva de 30 s. Se exige una duración mínima
    // para no confundir un pre-roll/publicidad corta con el final del capítulo.
    val showFrameCountdown = frameDuration > 0.0 && frameRemainingSeconds <= 30.0 &&
        (hasCustomHub && frameDuration >= MIN_WEB_EPISODE_SECONDS)
    val showNextEpisodeCard = nextEpisode != null && (videoEnded || showFrameCountdown) && !nextEpisodeDismissed && !nextEpisodeTriggered

    // Give the mini-hub real TV focus; otherwise the WebView keeps consuming remote input.
    LaunchedEffect(showNextEpisodeCard, nextEpisode?.id) {
        if (showNextEpisodeCard) {
            delay(250)
            runCatching { nextEpisodePlayFocus.requestFocus() }
        }
    }

    // Algunos reproductores dejan de enviar timeupdate antes del último cuadro. Start a real
    // fallback timer when the displayed remaining time first reaches 30 seconds.
    LaunchedEffect(showFrameCountdown, nextEpisode?.id, nextEpisodeDismissed, nextEpisodeTriggered, framePaused) {
        if (showFrameCountdown && nextEpisode != null && !nextEpisodeDismissed && !nextEpisodeTriggered) {
            val waitMs = (frameRemainingSeconds * 1_000.0).toLong().coerceIn(0L, 30_000L)
            var elapsedMs = 0L
            while (elapsedMs < waitMs) {
                delay(250L)
                // Don't switch episodes while the user has explicitly paused playback.
                if (!framePaused) elapsedMs = (elapsedMs + 250L).coerceAtMost(waitMs)
            }
            if (!nextEpisodeDismissed && !nextEpisodeTriggered) {
                nextEpisodeTriggered = true
                onPlayNextEpisode(nextEpisode.id)
            }
        }
    }

    // All web commands pass through this function so the UI only reflects commands that
    // were handled in an accessible HTML5 player. Cross-origin embeds may still reject them.
    fun runWebCommand(action: String, subtitleIndex: Int, seconds: Int) {
        val webView = webViewRef ?: return
        frameInteraction++

        // Los menús de VideoAPI pueden estar en otro frame y no necesitan que la sonda haya
        // encontrado un <video>. Mandar CC/Idioma a todos los frames aunque agentTarget sea null.
        if (isVideoApi && (action == "audioNext" || action == "subtitleNext") && agentInstalled) {
            val menuCmd = if (action == "audioNext") "audioNext" else "subtitleNext"
            webView.evaluateJavascript(frameAgentCommandScript("*", menuCmd, 0)) { raw ->
                if (raw != "true") android.util.Log.w("XuperAgent", "No se pudo repartir la orden de menú $menuCmd")
            }
            return
        }

        // Agente de frames (Latino / OK.ru / Simuladores): la orden llega al <video> aunque esté en un iframe de
        // otro origen. El estado de pausa/silencio NO se supone: lo informa el propio agente.
        val agentTargetNow = agentTarget
        if (agentInstalled && agentTargetNow != null) {
            val (cmd, sec) = when (action) {
                "playPause" -> "toggle" to 0
                "seekBack" -> "seek" to -kotlin.math.abs(seconds)
                "seekForward" -> "seek" to kotlin.math.abs(seconds)
                "mute" -> "mute" to 0
                "audioNext" -> "audioNext" to 0
                "subtitleNext" -> "subtitleNext" to 0
                else -> null to 0
            }
            if (cmd != null) {
                webView.evaluateJavascript(frameAgentCommandScript(if (cmd == "audioNext" || cmd == "subtitleNext") "*" else agentTargetNow, cmd, sec)) { raw ->
                    if (raw != "true") android.util.Log.w("XuperAgent", "El frame principal no pudo repartir la orden $cmd")
                }
            }
            return
        }

        if (isLatinoFamily) {
            // Latino/VideoAPI (y Vidly): solo control directo sobre el <video> accesible. NO se inyectan teclas:
            // la API pública no define atajos y una flecha/Espacio inyectado puede terminar moviendo el
            // volumen o activando un control cualquiera del reproductor original.
            webView.evaluateJavascript(videoApiCommandScript(action, subtitleIndex, seconds)) { rawResult ->
                if (rawResult == "true") {
                    when (action) {
                        "playPause" -> framePaused = !framePaused
                        "mute" -> frameMuted = !frameMuted
                    }
                }
            }
            return
        }

        // OK.ru / Simuladores: comportamiento anterior (DOM y, si no alcanza, teclas estándar).
        webView.evaluateJavascript(videoApiCommandScript(action, subtitleIndex, seconds)) { rawResult ->
            if (rawResult == "true") {
                when (action) {
                    "playPause" -> framePaused = !framePaused
                    "mute" -> frameMuted = !frameMuted
                }
                return@evaluateJavascript
            }

            val fallbackKey = when (action) {
                "playPause" -> AndroidKeyEvent.KEYCODE_DPAD_CENTER
                "seekBack" -> AndroidKeyEvent.KEYCODE_DPAD_LEFT
                "seekForward" -> AndroidKeyEvent.KEYCODE_DPAD_RIGHT
                "mute" -> AndroidKeyEvent.KEYCODE_M
                "subtitle" -> AndroidKeyEvent.KEYCODE_CAPTIONS
                else -> return@evaluateJavascript
            }

            // Focus the iframe first. Cross-origin policy still means this is best-effort:
            // Android/WebView may not route the key into every embedded player.
            webView.evaluateJavascript(
                "Array.from(document.querySelectorAll('iframe')).forEach(function(f){try{f.focus()}catch(e){}});"
            ) {
                dispatchWebKey(webView, fallbackKey)
                // The event is only a request to the embedded player. We deliberately
                // don't toggle framePaused here because we cannot confirm that it worked.
            }
        }
    }

    // Avance/retroceso sostenido, estilo plataforma de streaming: mientras la tecla está apretada se ACUMULA un
    // salto cada vez mayor (SeekRamp) y se aplica de una sola vez al soltarla. Así no se le mandan seeks
    // encadenados a un reproductor embebido cuya reacción desconocemos, y no depende de que el mando repita teclas.
    fun scrubText(seconds: Int): String =
        (if (seconds >= 0) "⏩ +" else "⏪ −") + SeekRamp.formatDelta(seconds)

    fun commitScrub() {
        val raw = scrubber.consume()
        scrubPending = 0
        if (raw == 0) return
        val delta: Int = raw
        scrubFlash = scrubText(if (delta != 0) delta else raw)
        if (delta == 0) return
        runWebCommand(if (delta < 0) "seekBack" else "seekForward", frameSubtitleIndex, kotlin.math.abs(delta))
    }

    fun scrubStep(forward: Boolean) {
        // Si se invierte la dirección, primero se aplica lo acumulado hacia el otro lado.
        if (scrubber.pendingSeconds != 0 && (scrubber.pendingSeconds > 0) != forward) commitScrub()
        scrubPending = scrubber.step(forward, SystemClock.uptimeMillis())
        scrubFlash = null
        frameInteraction++
    }

    fun startHeldSeek(forward: Boolean) {
        val seekAction = if (forward) "seekForward" else "seekBack"
        if (heldSeekAction != null && heldSeekAction != seekAction) {
            heldSeekAction = null
            commitScrub()
        }
        scrubStep(forward)
        heldSeekAction = seekAction
    }

    fun stopHeldSeek(): Boolean {
        if (heldSeekAction == null) return false
        heldSeekAction = null
        commitScrub()
        return true
    }

    // Tecla OK sostenida sobre los botones "↶ 10 s" / "10 s ↷" del hub: mismo avance acelerado.
    fun holdSeekKeyModifier(forward: Boolean): Modifier = Modifier.onPreviewKeyEvent { event ->
        val ev = event.nativeKeyEvent
        val isOk = ev.keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
            ev.keyCode == AndroidKeyEvent.KEYCODE_ENTER ||
            ev.keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER
        if (!isOk) return@onPreviewKeyEvent false
        when (ev.action) {
            AndroidKeyEvent.ACTION_DOWN -> {
                if (ev.repeatCount == 0) startHeldSeek(forward)
                true
            }
            AndroidKeyEvent.ACTION_UP -> {
                stopHeldSeek()
                true
            }
            else -> false
        }
    }

    // Mientras la tecla sigue apretada se suma un paso cada SeekRamp.TICK_MS (el primero se sumó al apretar).
    LaunchedEffect(heldSeekAction) {
        val action = heldSeekAction ?: return@LaunchedEffect
        val forward = action == "seekForward"
        delay(SeekRamp.HOLD_START_MS)
        var elapsedMs = 0L
        while (heldSeekAction == action && elapsedMs < SeekRamp.MAX_HOLD_MS) {
            scrubStep(forward)
            delay(SeekRamp.TICK_MS)
            elapsedMs += SeekRamp.TICK_MS
        }
        if (heldSeekAction == action) stopHeldSeek()
    }
    // Red de seguridad: si el mando nunca avisa que soltaste la tecla, lo acumulado se aplica igual.
    LaunchedEffect(scrubPending) {
        if (scrubPending != 0) {
            delay(SeekRamp.COMMIT_IDLE_MS)
            if (heldSeekAction == null) commitScrub()
        }
    }
    LaunchedEffect(scrubFlash) {
        if (scrubFlash != null) {
            delay(900)
            scrubFlash = null
        }
    }

    BackHandler(enabled = true) {
        when {
            hubActive && frameControlsVisible -> {
                frameControlsVisible = false
                runCatching {
                    webViewRef?.evaluateJavascript("document.activeElement && document.activeElement.blur && document.activeElement.blur();", null)
                    webViewRef?.requestFocus()
                }
            }
            else -> onExit()
        }
    }

    // ÚNICO manejador de teclas del hub. Lo usan tanto Compose (onPreviewKeyEvent, cuando el foco está
    // en Compose) como el WebView (HubAwareWebView, cuando el foco de Android quedó en el WebView).
    // Se lee siempre vía rememberUpdatedState: el lambda del `factory` del AndroidView se crea una sola
    // vez y, sin esto, vería valores viejos de showNextEpisodeCard/nextEpisode/hubActive.
    val onHubKey: (AndroidKeyEvent) -> Boolean = hubKey@{ ev ->
        if (!hubActive) return@hubKey false
        val key = when (ev.keyCode) {
            AndroidKeyEvent.KEYCODE_DPAD_UP -> HubKey.UP
            AndroidKeyEvent.KEYCODE_DPAD_DOWN -> HubKey.DOWN
            AndroidKeyEvent.KEYCODE_DPAD_LEFT -> HubKey.LEFT
            AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> HubKey.RIGHT
            AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER, AndroidKeyEvent.KEYCODE_NUMPAD_ENTER -> HubKey.OK
            AndroidKeyEvent.KEYCODE_MEDIA_REWIND -> HubKey.REWIND
            AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> HubKey.FAST_FORWARD
            AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> HubKey.PLAY_PAUSE
            else -> HubKey.OTHER
        }

        // Soltar la tecla corta el seek sostenido (hay mandos que no envían repeticiones).
        if (ev.action == AndroidKeyEvent.ACTION_UP) {
            val released = when (key) {
                HubKey.LEFT, HubKey.REWIND -> "seekBack"
                HubKey.RIGHT, HubKey.FAST_FORWARD -> "seekForward"
                else -> null
            }
            return@hubKey if (released != null && heldSeekAction == released) {
                stopHeldSeek()
                true
            } else false
        }
        if (ev.action != AndroidKeyEvent.ACTION_DOWN) return@hubKey false

        when (val action = WebHubKeyRouter.route(key, frameControlsVisible, showNextEpisodeCard && nextEpisode != null)) {
            HubKeyAction.PassThrough -> {
                if (frameControlsVisible) frameInteraction++
                false
            }
            HubKeyAction.OpenHub -> {
                hubHasFocus = false
                frameControlsVisible = true
                frameInteraction++
                runCatching { frameControlsFocus.requestFocus() } // si aún no está compuesto, lo repite el efecto de foco
                true
            }
            is HubKeyAction.MoveFocus -> {
                frameInteraction++
                if (hubHasFocus) {
                    focusManager.moveFocus(
                        when (action.direction) {
                            HubDirection.UP -> FocusDirection.Up
                            HubDirection.DOWN -> FocusDirection.Down
                            HubDirection.LEFT -> FocusDirection.Left
                            HubDirection.RIGHT -> FocusDirection.Right
                        }
                    )
                } else {
                    // El foco se escapó del hub (p. ej. volvió al WebView): lo recuperamos.
                    runCatching { frameControlsFocus.requestFocus() }
                }
                true
            }
            is HubKeyAction.Seek -> {
                // Solo el primer key-down arranca el avance; LaunchedEffect(heldSeekAction) lo acelera mientras se mantenga.
                if (ev.repeatCount == 0) startHeldSeek(action.forward)
                true
            }
            HubKeyAction.TogglePlayPause -> {
                if (ev.repeatCount == 0) {
                    runWebCommand("playPause", frameSubtitleIndex, 0)
                    // Igual que el reproductor nativo: al pausar con el panel oculto, el panel vuelve a mostrarse.
                    if (!frameControlsVisible) {
                        hubHasFocus = false
                        frameControlsVisible = true
                        runCatching { frameControlsFocus.requestFocus() }
                    }
                }
                true
            }
        }
    }
    val onHubKeyState = rememberUpdatedState(onHubKey)

    Box(Modifier.fillMaxSize().background(Color.Black).onPreviewKeyEvent { event ->
        onHubKeyState.value(event.nativeKeyEvent)
    }) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { viewContext ->
                val container = FrameLayout(viewContext).apply { setBackgroundColor(AndroidColor.BLACK) }
                val webView = HubAwareWebView(viewContext, consumeDpad = isVidly || isVidLux || isPrueba, hoverExitDelayMs = if (isPrueba) 4_000L else 0L) { keyEvent -> onHubKeyState.value(keyEvent) }.apply {
                    setBackgroundColor(AndroidColor.BLACK)
                    setLayerType(View.LAYER_TYPE_HARDWARE, null)
                    settings.cacheMode = WebSettings.LOAD_DEFAULT
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    settings.javaScriptCanOpenWindowsAutomatically = false
                    settings.setSupportMultipleWindows(false)
                    settings.loadsImagesAutomatically = true
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    settings.userAgentString = settings.userAgentString + " XuperQuilmesTV/1.0"
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) settings.safeBrowsingEnabled = true
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    isFocusable = true
                    isFocusableInTouchMode = true
                    layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    webChromeClient = object : WebChromeClient() {
                        private var fullscreenView: View? = null
                        private var fullscreenCallback: CustomViewCallback? = null
                        private var originalUi = 0
                        override fun onProgressChanged(view: WebView?, newProgress: Int) { progress = newProgress }
                        override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                            val text = consoleMessage?.message()
                            if (text != null && text.startsWith("[XuperVidLux]")) android.util.Log.i("XuperVidLux", text.take(300))
                            return super.onConsoleMessage(consoleMessage)
                        }
                        override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean = false
                        override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                            if (view == null || fullscreenView != null) { callback?.onCustomViewHidden(); return }
                            val activity = generateSequence(context) { (it as? ContextWrapper)?.baseContext }.filterIsInstance<Activity>().firstOrNull()
                            val decor = activity?.window?.decorView as? ViewGroup
                            if (decor == null) { callback?.onCustomViewHidden(); return }
                            fullscreenView = view
                            fullscreenCallback = callback
                            originalUi = decor.systemUiVisibility
                            decor.addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                            decor.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        }
                        override fun onHideCustomView() {
                            val activity = generateSequence(context) { (it as? ContextWrapper)?.baseContext }.filterIsInstance<Activity>().firstOrNull()
                            val decor = activity?.window?.decorView as? ViewGroup
                            fullscreenView?.let { decor?.removeView(it) }
                            fullscreenView = null
                            fullscreenCallback?.onCustomViewHidden()
                            fullscreenCallback = null
                            decor?.systemUiVisibility = originalUi
                        }
                    }
                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                            val requestUrl = request?.url?.toString() ?: return null
                            return runCatching {
                                if (XuperBraveAdblock.shouldBlock(request, view?.url)) XuperBraveAdblock.blockedResponse() else null
                            }.onFailure { android.util.Log.w("XuperWebView", "Falló el filtro de una solicitud; se permite continuar", it) }
                                .getOrNull()
                        }
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            val target = request?.url?.toString() ?: return true
                            val scheme = runCatching { URI(target).scheme?.lowercase() }.getOrNull()
                            if (scheme != "http" && scheme != "https") return true
                            val blocked = runCatching { XuperBraveAdblock.shouldBlockUrl(target, view?.url, "document") }
                                .onFailure { android.util.Log.w("XuperWebView", "Falló el filtro de navegación; se permite continuar", it) }
                                .getOrDefault(false)
                            if (blocked) {
                                pageError = "Se bloqueó una redirección publicitaria o de seguimiento. Volvé al reproductor e intentá reproducir otra vez."
                                return true
                            }
                            return false
                        }
                        override fun onPageStarted(view: WebView?, pageUrl: String?, favicon: android.graphics.Bitmap?) {
                            progress = 0
                            pageFinished = false
                            pageError = null
                            agentReports.clear()
                            agentTarget = null
                        }
                        override fun onPageFinished(view: WebView?, pageUrl: String?) {
                            progress = 100
                            pageFinished = true
                            view?.evaluateJavascript("""(function(){
                              try{
                                var css='html,body{outline:0!important;box-shadow:none!important;background:#000!important} iframe:focus{outline:0!important;box-shadow:none!important;border:0!important}';
                                var st=document.getElementById('xuper-tv-focus-style');
                                if(!st){st=document.createElement('style');st.id='xuper-tv-focus-style';(document.head||document.documentElement).appendChild(st);}st.textContent=css;
                                Array.from(document.querySelectorAll('iframe')).forEach(function(f){if(!f.getAttribute('title'))f.setAttribute('title','Reproductor de video');});
                              }catch(e){}
                            })();""", null)
                            if (nextEpisode != null) view?.evaluateJavascript("""(function(){
                              try{
                                if(!window.__xuperEndDetector){
                                  window.__xuperEndDetector=true;
                                  var notify=function(){try{XuperEndBridge.onVideoEnded()}catch(e){}};
                                  var bind=function(){document.querySelectorAll('video').forEach(function(v){if(!v.__xuperEnded){v.__xuperEnded=true;v.addEventListener('ended',notify);}})};
                                  bind();new MutationObserver(bind).observe(document.documentElement,{childList:true,subtree:true});
                                  window.addEventListener('message',function(e){var d=e.data;try{if(typeof d==='string')d=JSON.parse(d);var x=JSON.stringify(d||{}).toLowerCase();if(/ended|video.?end|playback.?complete|finished/.test(x))notify();}catch(err){}});
                                }
                              }catch(e){}
                            })();""", null)
                            view?.evaluateJavascript(webPlaybackResumeScript(savedResumePosition, autoSelectSpanish = isVideoApi), null)
                            // Sin soporte de scripts de inicio de documento: al menos el documento principal.
                            if ((isVidly || isVidLux || isPrueba) && !remoteNavSupported) view?.evaluateJavascript(remoteNavScript, null)
                            if ((isVidLux || isCineSrc) && !remoteNavSupported) view?.evaluateJavascript(XUPER_VIDLUX_AUTOPLAY_JS, null)
                            if (isSimuladores || isOkRu) {
                                view?.evaluateJavascript(simuladoresAutoplayScript(), null)
                            }
                            if (isVideoApi) view?.evaluateJavascript("""(function(){
                              try{
                                var css='html,body{margin:0!important;padding:0!important;border:0!important;outline:0!important;box-shadow:none!important;background:#000!important} iframe,video,html:focus,body:focus,iframe:focus,video:focus{border:0!important;outline:0!important;box-shadow:none!important}';
                                var st=document.getElementById('xuper-videoapi-edge-cleanup');
                                if(!st){st=document.createElement('style');st.id='xuper-videoapi-edge-cleanup';(document.head||document.documentElement).appendChild(st);}st.textContent=css;
                              }catch(e){}
                            })();""", null)
                            if (view != null && !pageUrl.isNullOrBlank()) {
                                runCatching { XuperBraveAdblock.injectCosmetics(view, pageUrl) }
                                    .onFailure { android.util.Log.w("XuperWebView", "No se pudieron aplicar reglas cosméticas", it) }
                            }
                            view?.evaluateJavascript("""(function(){
                              try{
                                if(!window.__xuperAdGuardInstalled){
                                  window.__xuperAdGuardInstalled=true;window.open=function(){return null;};
                                  var adHref=/doubleclick|googlesyndication|googleadservices|popunder|popcash|popads|adsterra|exoclick|onclickads|clickadu|trafficjunky|ad-maven|admaven|hilltopads|monetag/i;
                                  document.addEventListener('click',function(ev){var a=ev.target&&ev.target.closest?ev.target.closest('a[href]'):null;if(a&&adHref.test(a.href||'')){ev.preventDefault();ev.stopImmediatePropagation();}},true);
                                }
                                var css='[id*="ad-" i],[id^="ads" i],[class*="popunder" i],[class*="popup-ad" i],[class*="advertisement" i],[class*="ad-banner" i],[class*="adsbox" i],[class*="ad-container" i],iframe[src*="doubleclick" i],iframe[src*="googlesyndication" i]{display:none!important;visibility:hidden!important;pointer-events:none!important;max-height:0!important;max-width:0!important}';
                                var st=document.getElementById('xuper-ad-cosmetics');if(!st){st=document.createElement('style');st.id='xuper-ad-cosmetics';(document.head||document.documentElement).appendChild(st);}st.textContent=css;
                              }catch(e){}
                            })();""", null)
                        }
                        override fun onRenderProcessGone(view: WebView?, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
                            pageError = "El reproductor web se cerró inesperadamente. Volvé atrás e intentá otra fuente."
                            if (view != null) {
                                runCatching { (view.parent as? ViewGroup)?.removeView(view) }
                                runCatching { view.destroy() }
                            }
                            webViewRef = null
                            return true
                        }
                        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: android.webkit.WebResourceError?) {
                            if (request?.isForMainFrame == true) {
                                val description = error?.description?.toString()?.takeIf(String::isNotBlank)
                                pageError = if (description != null) "Falló la carga de la página: $description" else "No se pudo cargar la página. Revisá la conexión e intentá de nuevo."
                            }
                        }
                        override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, response: WebResourceResponse?) {
                            if (request?.isForMainFrame == true && (response?.statusCode ?: 200) >= 400) pageError = "El sitio respondió HTTP ${response?.statusCode}. Puede que el contenido no esté disponible."
                        }
                    }
                    setDownloadListener { _, _, _, _, _ -> pageError = "El sitio ofreció una descarga en lugar de reproducir el video." }
                    // Agente de frames: debe registrarse ANTES de cargar la página para correr también en los iframes.
                    if (agentSupported) {
                        agentInstalled = runCatching {
                            WebViewCompat.addDocumentStartJavaScript(this, XUPER_FRAME_AGENT_JS, setOf("*"))
                        }.onFailure { android.util.Log.w("XuperAgent", "No se pudo instalar el agente de frames", it) }.isSuccess
                    }
                    // Vidly: navegación con el mando dentro de la página (debe registrarse ANTES de cargar para cubrir los iframes).
                    if (remoteNavSupported) {
                        runCatching { WebViewCompat.addDocumentStartJavaScript(this, remoteNavScript, setOf("*")) }
                            .onFailure { android.util.Log.w("XuperRemoteNav", "No se pudo instalar la navegación con el mando", it) }
                    }
                    // VidLux: arranque automático con sonido (la URL no lleva autoPlay; ver XUPER_VIDLUX_AUTOPLAY_JS).
                    if ((isVidLux || isCineSrc) && remoteNavSupported) {
                        runCatching { WebViewCompat.addDocumentStartJavaScript(this, XUPER_VIDLUX_AUTOPLAY_JS, setOf("*")) }
                            .onFailure { android.util.Log.w("XuperVidLux", "No se pudo instalar el arranque automático", it) }
                    }
                    tag = url
                    if (nextEpisode != null) {
                        addJavascriptInterface(object {
                            @JavascriptInterface fun onVideoEnded() {
                                Handler(Looper.getMainLooper()).post { videoEnded = true }
                            }
                        }, "XuperEndBridge")
                    }
                    if (hasCustomHub) {
                        addJavascriptInterface(object {
                            private var lastPersistedPosition = -1L

                            /** El agente de cada frame informa su video. Corre en un hilo del puente: se pasa al principal. */
                            @JavascriptInterface fun onAgentReport(frameId: String, json: String) {
                                val id = frameId.take(32)
                                val data = json.take(600)
                                Handler(Looper.getMainLooper()).post { handleAgentReportState.value(id, data) }
                            }

                            @JavascriptInterface fun onTimeUpdate(current: Double, duration: Double) {
                                Handler(Looper.getMainLooper()).post {
                                    val safeCurrent = current.coerceAtLeast(0.0)
                                    val safeDuration = duration.coerceAtLeast(0.0)
                                    frameCurrentTime = safeCurrent
                                    frameDuration = safeDuration
                                    if (safeDuration > 0.0 && safeDuration - safeCurrent <= 1.0 && safeDuration >= MIN_WEB_EPISODE_SECONDS) {
                                        videoEnded = true
                                    }
                                    val savePosition = if (safeDuration > 0 && safeCurrent >= safeDuration - 15.0) 0L else safeCurrent.toLong()
                                    if ((savePosition == 0L && lastPersistedPosition != 0L) ||
                                        lastPersistedPosition < 0L ||
                                        savePosition >= lastPersistedPosition + 5L ||
                                        savePosition <= lastPersistedPosition - 5L
                                    ) {
                                        playbackPrefs.edit().putLong(positionPreferenceKey, savePosition).apply()
                                        lastPersistedPosition = savePosition
                                    }
                                }
                            }
                        }, "XuperBridge")
                    }
                    if (isVideoApi || isPrueba) {
                        // Prueba carga la MISMA URL que Latino (skin=netflix, hide_controls=0, start_at...), sin el fragmento.
                        loadUrl(videoApiEmbedUrl(url.substringBefore('#'), savedResumePosition))
                    } else if (isSimuladores || isOkRu) loadUrl(simuladoresAutoplayUrl(url)) else loadUrl(url)
                    requestFocus()
                }
                webViewRef = webView
                container.addView(webView)
                container
            },
            update = { container ->
                val webView = container.getChildAt(0) as? WebView
                if (webView != null && webView.tag != url) {
                    webView.tag = url
                    if (isVideoApi || isPrueba) {
                        webView.loadUrl(videoApiEmbedUrl(url.substringBefore('#'), playbackPrefs.getLong(positionPreferenceKey, savedResumePosition).coerceAtLeast(0L)))
                    } else if (isSimuladores || isOkRu) webView.loadUrl(simuladoresAutoplayUrl(url)) else webView.loadUrl(url)
                }
            },
            onRelease = { container ->
                (container.getChildAt(0) as? WebView)?.let { webView ->
                    runCatching { webView.stopLoading() }
                    runCatching { webView.loadUrl("about:blank") }
                    runCatching { webView.clearHistory() }
                    runCatching { webView.removeAllViews() }
                    runCatching { webView.destroy() }
                }
                webViewRef = null
            },
        )

        if (showNextEpisodeCard && nextEpisode != null) {
            NextEpisodeOverlay(
                episode = nextEpisode,
                secondsLeft = if (frameDuration > 0.0) kotlin.math.ceil(frameRemainingSeconds).toInt().coerceIn(0, 30) else 0,
                onPlayNow = {
                    if (!nextEpisodeTriggered) {
                        nextEpisodeTriggered = true
                        onPlayNextEpisode(nextEpisode.id)
                    }
                },
                onDismiss = { nextEpisodeDismissed = true },
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 22.dp, bottom = if (hubActive && frameControlsVisible) 200.dp else 22.dp),
                playFocusRequester = nextEpisodePlayFocus,
                dismissFocusRequester = nextEpisodeDismissFocus,
            )
        }

        if (hubActive && frameControlsVisible && pageError == null) {
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp)
                    .background(Color(0xE6101820), RoundedCornerShape(14.dp)).padding(12.dp)
                    .onFocusChanged { hubHasFocus = it.hasFocus },
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when {
                            isOkRu -> "OK.RU · controles"
                            isSimuladores -> "SIMULADORES · controles"
                            else -> "LATINO · controles"
                        },
                        color = Color(0xFF65DCE8), fontSize = 13.sp, fontWeight = FontWeight.Bold
                    )
                    // El agente informa posición y duración del video principal: se muestra en la barra del hub.
                    if (agentActive && frameDuration > 0.0) {
                        Text("${SeekRamp.formatClock(frameCurrentTime)} / ${SeekRamp.formatClock(frameDuration)}", color = Color.White, fontSize = 13.sp, modifier = Modifier.widthIn(min = 104.dp))
                        Box(Modifier.weight(1f).height(4.dp).background(Color(0xFF52616B), RoundedCornerShape(4.dp))) {
                            Box(Modifier.fillMaxWidth((frameCurrentTime / frameDuration).toFloat().coerceIn(0f, 1f)).height(4.dp).background(Color(0xFF65DCE8), RoundedCornerShape(4.dp)))
                        }
                    }
                }

                // Fila 1: transporte. Fila 2 (solo VideoAPI): idioma y subtítulos.
                // Antes eran hasta 8 botones en una sola fila, que no entran en pantalla.
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(modifier = Modifier.focusRequester(frameControlsFocus), onClick = {
                        runWebCommand("playPause", frameSubtitleIndex, 0)
                    }) { Text(if (framePaused) "▶ Reproducir" else "Ⅱ Pausar") }

                    // Tocar = 10 s; mantener OK apretado acelera el avance (se aplica al soltar).
                    Button(modifier = holdSeekKeyModifier(false), onClick = { runWebCommand("seekBack", frameSubtitleIndex, 10) }) { Text("↶ 10 s") }
                    Button(modifier = holdSeekKeyModifier(true), onClick = { runWebCommand("seekForward", frameSubtitleIndex, 10) }) { Text("10 s ↷") }
                    Button(onClick = { runWebCommand("mute", frameSubtitleIndex, 0) }) { Text(if (frameMuted) "🔊 Sonido" else "🔇 Silencio") }

                }

                if (isVideoApi) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            enabled = true,
                            onClick = { runWebCommand("audioNext", frameSubtitleIndex, 0) }
                        ) {
                            val label = latinoAudioTracks.getOrNull(latinoAudioSelected).orEmpty()
                            Text(if (latinoAudioTracks.isEmpty()) "Idioma · abrir/cambiar ▶" else "Idioma · ${label.take(18)} ▶")
                        }
                        Button(
                            enabled = true,
                            onClick = { runWebCommand("subtitleNext", frameSubtitleIndex, 0) }
                        ) {
                            val label = latinoSubtitleTracks.getOrNull(latinoSubtitleSelected).orEmpty()
                            Text(if (latinoSubtitleTracks.isEmpty()) "CC · abrir/cambiar ▶" else if (latinoSubtitleSelected < 0) "CC · Desactivados ▶" else "CC · ${label.take(18)} ▶")
                        }
                    }
                }
            }
        }
        // Indicador del avance sostenido: delta acumulado y, si se conoce la duración, la posición de destino.
        val scrubLabel: String? = when {
            scrubPending != 0 -> scrubText(scrubPending) + if (frameDuration > 0.0)
                "  ·  " + SeekRamp.formatClock((frameCurrentTime + scrubPending).coerceIn(0.0, frameDuration))
            else ""
            else -> scrubFlash
        }
        if (hubActive && scrubLabel != null && pageError == null) {
            Text(
                scrubLabel,
                Modifier.align(Alignment.Center).background(Color(0xCC101820), RoundedCornerShape(12.dp)).padding(horizontal = 26.dp, vertical = 14.dp),
                color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold
            )
        }
        if (progress < 100 && pageError == null) {
            Text("Cargando reproductor web… $progress%", Modifier.align(Alignment.TopCenter).padding(16.dp).background(Color(0xCC101820), RoundedCornerShape(8.dp)).padding(12.dp), color = Color.White, fontSize = 14.sp)
        }
        if (pageError != null) {
            Column(Modifier.align(Alignment.Center).fillMaxWidth(0.78f).background(Color(0xF014202B), RoundedCornerShape(14.dp)).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("No se pudo reproducir", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(pageError.orEmpty(), color = Color(0xFFD8E3EA), fontSize = 15.sp)
                Text("El sitio de reproducción no pudo cargar este título en el reproductor integrado. Revisá la conexión e intentá nuevamente.", color = Color(0xFFB5C6D2), fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { pageError = null; progress = 0; pageFinished = false; webViewRef?.reload() }) { Text("↻ Reintentar") }
                    Button(onClick = { onExit() }) { Text("← Volver") }
                }
            }
        }
        if (hubActive && !frameControlsVisible) {
            Box(Modifier.align(Alignment.TopStart).width(1.dp).height(1.dp).alpha(0f).focusRequester(hiddenHubFocus).focusable())
        }
    }

}
