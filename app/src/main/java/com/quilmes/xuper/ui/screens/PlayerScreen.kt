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
import android.view.KeyEvent as AndroidKeyEvent
import android.view.View
import android.view.ViewGroup
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
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.media3.ui.TrackSelectionDialogBuilder
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import coil.compose.AsyncImage
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
    private val onRemoteKey: (AndroidKeyEvent) -> Boolean,
) : WebView(context) {
    /** Dispatch a fallback key without re-entering our own remote-key interceptor. */
    fun dispatchInjectedKeyEvent(event: AndroidKeyEvent): Boolean = super.dispatchKeyEvent(event)

    override fun dispatchKeyEvent(event: AndroidKeyEvent): Boolean {
        if ((event.action == AndroidKeyEvent.ACTION_DOWN || event.action == AndroidKeyEvent.ACTION_UP) && onRemoteKey(event)) return true
        return super.dispatchKeyEvent(event)
    }
}

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
      if(d.__xuper!==1||d.target!==ID) return;
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
          var ats=v.audioTracks;if(ats&&ats.length){var current=-1;for(var ai=0;ai<ats.length;ai++)if(ats[ai].enabled)current=ai;var next=(current+1)%ats.length;for(var aj=0;aj<ats.length;aj++)ats[aj].enabled=(aj===next);}
        }else if(d.cmd==='subtitleNext'){
          var tts=v.textTracks;if(tts&&tts.length){var current=-1;for(var ti=0;ti<tts.length;ti++)if(tts[ti].mode==='showing')current=ti;var next=current+1;if(next>=tts.length)next=-1;for(var tj=0;tj<tts.length;tj++)tts[tj].mode=(tj===next?'showing':'disabled');}
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
    val safeTarget = target.filter { it.isLetterOrDigit() }
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
    onChangeChannel: (String) -> Unit = {},
    onChangePlayer: (String) -> Unit = {},
    onPlayNextEpisode: (String) -> Unit = {},
    onExit: () -> Unit = {},
    directOnly: Boolean = false,
) {
    val isKnownEmbed = WebSourceRegistry.isVideoApiEmbedUrl(streamUrl) ||
        (streamUrl.startsWith("https://framextv.xyz/embed/") || streamUrl.startsWith("https://framextv.cc/embed/")) ||
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
        onChangeChannel = onChangeChannel,
        onChangePlayer = onChangePlayer,
        onPlayNextEpisode = onPlayNextEpisode,
        onExit = onExit,
        directOnly = directOnly,
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
    onChangeChannel: (String) -> Unit,
    onChangePlayer: (String) -> Unit,
    onPlayNextEpisode: (String) -> Unit,
    onExit: () -> Unit,
    directOnly: Boolean,
    onResolveAsWebPage: () -> Unit,
) {
    val context = LocalContext.current
    var error by remember(streamUrl) { mutableStateOf<String?>(null) }
    var showControls by remember(streamUrl) { mutableStateOf(true) }
    var showNextEpisode by remember(streamUrl, nextEpisode?.id) { mutableStateOf(false) }
    var nextEpisodeTriggered by remember(streamUrl, nextEpisode?.id) { mutableStateOf(false) }
    var sourceIndex by remember(streamUrl, alternatePlayers) {
        mutableStateOf(alternatePlayers.indexOfFirst { it.streamUrl == streamUrl }.coerceAtLeast(0))
    }
    var position by remember(streamUrl) { mutableStateOf(0L) }
    var duration by remember(streamUrl) { mutableStateOf(0L) }
    var isPlaying by remember(streamUrl) { mutableStateOf(true) }
    var failedSources by remember(streamUrl) { mutableStateOf(0) }
    var attemptedUrls by remember(streamUrl) { mutableStateOf(setOf(streamUrl)) }

    val buttonCount = 4
    var focusedButton by remember(streamUrl) { mutableStateOf(0) }
    val rootFocusRequester = remember(streamUrl) { FocusRequester() }
    val buttonFocusRequesters = remember { List(buttonCount) { FocusRequester() } }

    val dataSourceFactory = remember {
        DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent("Mozilla/5.0 (Linux; Android TV) XuperQuilmesTV")
    }
    val exoPlayer = remember {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build().apply {
                trackSelectionParameters = trackSelectionParameters.buildUpon()
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
        }.build()
        attemptedUrls = attemptedUrls + url
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
        showControls = true
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
    LaunchedEffect(exoPlayer, showControls) {
        if (showControls) {
            delay(6_000)
            showControls = false
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

    DisposableEffect(exoPlayer, alternatePlayers, sourceIndex, directOnly, onResolveAsWebPage) {
        val listener = object : Player.Listener {
            override fun onPlayerError(e: PlaybackException) {
                val currentUrl = exoPlayer.currentMediaItem?.localConfiguration?.uri?.toString().orEmpty()
                if (!directOnly && currentUrl == streamUrl && isWebPageUrl(streamUrl)) {
                    onResolveAsWebPage()
                    return
                }
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
                    error = "No se encontró otro enlace coincidente que no se haya probado. Puede que este contenido no esté disponible en las listas cargadas."
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
        if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
        showControls = true
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

    Box(
        Modifier.fillMaxSize().background(Color.Black).focusRequester(rootFocusRequester).focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.nativeKeyEvent.keyCode) {
                    AndroidKeyEvent.KEYCODE_CHANNEL_UP -> { changeChannelBy(1); return@onPreviewKeyEvent true }
                    AndroidKeyEvent.KEYCODE_CHANNEL_DOWN -> { changeChannelBy(-1); return@onPreviewKeyEvent true }
                    AndroidKeyEvent.KEYCODE_MEDIA_REWIND -> {
                        val repeat = event.nativeKeyEvent.repeatCount
                        val jumpSeconds = when { repeat >= 5 -> 60; repeat >= 3 -> 30; repeat >= 1 -> 20; else -> 10 }
                        seekBy(-jumpSeconds * 1_000L)
                        return@onPreviewKeyEvent true
                    }
                    AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                        val repeat = event.nativeKeyEvent.repeatCount
                        val jumpSeconds = when { repeat >= 5 -> 60; repeat >= 3 -> 30; repeat >= 1 -> 20; else -> 10 }
                        seekBy(jumpSeconds * 1_000L)
                        return@onPreviewKeyEvent true
                    }
                    AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { togglePlayPause(); return@onPreviewKeyEvent true }
                    AndroidKeyEvent.KEYCODE_MEDIA_PLAY -> { exoPlayer.play(); isPlaying = true; return@onPreviewKeyEvent true }
                    AndroidKeyEvent.KEYCODE_MEDIA_PAUSE -> { exoPlayer.pause(); isPlaying = false; return@onPreviewKeyEvent true }
                }
                val repeat = event.nativeKeyEvent.repeatCount
                val jumpSeconds = when { repeat >= 5 -> 60; repeat >= 3 -> 30; repeat >= 1 -> 20; else -> 10 }
                when (event.key) {
                    Key.DirectionLeft -> { seekBy(-jumpSeconds * 1_000L); true }
                    Key.DirectionRight -> { seekBy(jumpSeconds * 1_000L); true }
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
                    setOnClickListener { showControls = true }
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                }
            },
            update = { it.player = exoPlayer },
        )
        if (showControls) {
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
                Text("◀/▶  saltar 10 s · ▲/▼  cambiar control · OK  ejecutar · Atrás  salir", color = Color(0x99FFFFFF), fontSize = 11.sp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(modifier = Modifier.focusRequester(buttonFocusRequesters[0]), onClick = { togglePlayPause() }) {
                        Text(if (isPlaying) "Ⅱ  Pausar" else "▶  Reproducir")
                    }
                    Button(modifier = Modifier.focusRequester(buttonFocusRequesters[1]), onClick = { changeSource() }) { Text("⟳  Cambiar reproductor") }
                    Button(modifier = Modifier.focusRequester(buttonFocusRequesters[2]), onClick = {
                        showControls = true
                        TrackSelectionDialogBuilder(context, "Audio", exoPlayer, C.TRACK_TYPE_AUDIO).build().show()
                    }) { Text("♫  Audio") }
                    Button(modifier = Modifier.focusRequester(buttonFocusRequesters[3]), onClick = {
                        showControls = true
                        TrackSelectionDialogBuilder(context, "Subtítulos", exoPlayer, C.TRACK_TYPE_TEXT).build().show()
                    }) { Text("CC  Subtítulos") }
                }
                if (error != null) {
                    Text(error!!, color = Color.White, fontSize = 15.sp, modifier = Modifier.background(Color(0xCC14202B), RoundedCornerShape(8.dp)).padding(12.dp))
                    Button(onClick = { setUrl(exoPlayer.currentMediaItem?.localConfiguration?.uri?.toString() ?: streamUrl, streamHeaders) }) { Text("Reintentar") }
                }
            }
        }
        if (showNextEpisode && nextEpisode != null) {
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

private val frameXtvProviders = listOf("auto", "barbarian", "archer", "goblin", "pekka")
private fun frameXtvProvider(serverIndex: Int): String =
    frameXtvProviders.getOrElse(serverIndex.coerceIn(0, frameXtvProviders.lastIndex)) { "auto" }
private fun frameXtvProviderLabel(serverIndex: Int): String = when (frameXtvProvider(serverIndex)) {
    "barbarian" -> "Barbarian"
    "archer" -> "Archer"
    "goblin" -> "Goblin"
    "pekka" -> "P.E.K.K.A."
    else -> "Auto"
}

private fun frameXtvWrapper(rawUrl: String, resumePositionSeconds: Long = 0L, subtitleIndex: Int = 0, serverIndex: Int = 0): String {
    var embedUrl = rawUrl
    if (!Regex("(?:[?&])autoplay=").containsMatchIn(embedUrl)) embedUrl += if (embedUrl.contains('?')) "&autoplay=1" else "?autoplay=1"
    if (!Regex("(?:[?&])muted=").containsMatchIn(embedUrl)) embedUrl += "&muted=0"
    if (!Regex("(?:[?&])controls=").containsMatchIn(embedUrl)) embedUrl += "&controls=1"
    val safeEmbed = embedUrl.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")
    val providerName = frameXtvProvider(serverIndex)
    return """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no"><style>html,body{margin:0;width:100%;height:100%;overflow:hidden;background:#000}iframe{width:100%;height:100%;border:0;display:block}</style></head><body><iframe id="framextv-player" src="$safeEmbed" allow="autoplay; encrypted-media; fullscreen; picture-in-picture" allowfullscreen></iframe><script>
      (function(){var f=document.getElementById('framextv-player');window.xuperFrameCommand=function(action,extra){try{f.contentWindow.postMessage(Object.assign({action:action},extra||{}),'*')}catch(e){}};
      f.addEventListener('load',function(){
        setTimeout(function(){window.xuperFrameCommand('setSubtitle',{index:$subtitleIndex})},2200);
        if($serverIndex>0) setTimeout(function(){window.xuperFrameCommand('setProvider',{provider:'$providerName'});},2600);
        if($resumePositionSeconds>5) setTimeout(function(){window.xuperFrameCommand('seekBy',{seconds:$resumePositionSeconds})},3200);
      });
      window.addEventListener('message',function(e){var d=e.data;if(d&&typeof d==='object'&&d.event==='frameXTV:timeupdate'){try{window.XuperBridge.onTimeUpdate(Number(d.currentTime)||0,Number(d.duration)||0)}catch(x){}return;}try{window.XuperBridge.onFrameMessage(typeof d==='string'?d:JSON.stringify(d))}catch(x){}});})();</script></body></html>"""
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
    val serverPreferenceKey = "web-server:$stableContentKey"
    val positionPreferenceKey = "web-position:$stableContentKey"
    val savedSubtitleIndex = remember(url, stableContentKey) { playbackPrefs.getInt(subtitlePreferenceKey, -1) }
    val savedResumePosition = remember(url, stableContentKey) { playbackPrefs.getLong(positionPreferenceKey, 0L).coerceAtLeast(0L) }
    val savedServerIndex = remember(url, stableContentKey) { playbackPrefs.getInt(serverPreferenceKey, 0).coerceAtLeast(0) }
    var saveSubtitleForContent by remember(url, stableContentKey) { mutableStateOf(savedSubtitleIndex >= 0) }
    var progress by remember(url) { mutableStateOf(0) }
    var pageError by remember(url) { mutableStateOf<String?>(null) }
    var pageFinished by remember(url) { mutableStateOf(false) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    val isFrameXtv = remember(url) { runCatching { URI(url).host?.lowercase()?.let { it == "framextv.xyz" || it.endsWith(".framextv.xyz") || it == "framextv.cc" || it.endsWith(".framextv.cc") } == true }.getOrDefault(false) }
    val isVideoApi = remember(url) { runCatching { WebSourceRegistry.isVideoApiHost(URI(url).host) }.getOrDefault(false) }
    val isOkRu = remember(url) { runCatching { URI(url).host?.lowercase()?.let { it == "ok.ru" || it.endsWith(".ok.ru") } == true }.getOrDefault(false) }
    val isSimuladores = remember(url) { runCatching { URI(url).host?.lowercase()?.let { it == "los-simuladores.bringfeel.com.ar" || it.endsWith(".bringfeel.com.ar") } == true }.getOrDefault(false) }
    val hasCustomHub = isFrameXtv || isVideoApi || isOkRu || isSimuladores
    // Latino/VideoAPI: su API pública no documenta ningún canal de control (post_message/communication
    // figura como "bloqueado"). El hub de Xuper solo se ofrece cuando una sonda confirma que hay un
    // <video> accesible desde el documento; si no, el mando va directo al reproductor original.
    var latinoDomControl by remember(url) { mutableStateOf(false) }
    // Agente por frame (ver XUPER_FRAME_AGENT_JS): permite controlar el <video> aunque esté en un iframe de
    // otro origen. Latino, OK.ru y Simuladores lo usan; FrameXTV conserva su canal postMessage propio.
    val useFrameAgent = isVideoApi || isOkRu || isSimuladores
    val agentSupported = remember { useFrameAgent && runCatching { WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) }.getOrDefault(false) }
    var agentInstalled by remember { mutableStateOf(false) }
    var agentTarget by remember { mutableStateOf<String?>(null) }
    val agentReports = remember { HashMap<String, AgentVideoReport>() }
    val agentActive = agentInstalled && agentTarget != null
    val hubActive = hasCustomHub && (!isVideoApi || latinoDomControl || agentActive)
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
    var frameServerIndex by remember(url, stableContentKey) { mutableStateOf(savedServerIndex) }
    var saveServerForContent by remember(url, stableContentKey) { mutableStateOf(playbackPrefs.getBoolean("web-save-server:$stableContentKey", true)) }
    // Latino arranca sin hub (solo aparece si la sonda confirma que puede controlarse).
    var frameControlsVisible by remember(url) { mutableStateOf(!isVideoApi) }
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

    LaunchedEffect(url) {
        delay(30_000)
        if (!pageFinished && pageError == null) {
            pageError = "El sitio no terminó de cargar en 30 segundos. Puede estar caído, bloqueando la app o esperando una verificación incompatible."
        }
    }
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
    LaunchedEffect(isVideoApi, pageFinished) {
        // Con el agente instalado la sonda del documento principal sobra (el agente también corre en él).
        if (!isVideoApi || !pageFinished || agentInstalled) return@LaunchedEffect
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
    LaunchedEffect(isVideoApi, hubActive) {
        if (isVideoApi && !hubActive) {
            frameControlsVisible = false
            delay(150)
            runCatching { webViewRef?.requestFocus() }
        }
    }
    // Latino: cuando el video pasa a ser controlable el hub se muestra unos segundos (luego se oculta solo).
    LaunchedEffect(isVideoApi, hubActive) {
        if (isVideoApi && hubActive) frameControlsVisible = true
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
    // FrameXTV emits timeupdate events, so its mini-hub can start 30 seconds before the end.
    // Latino/VideoAPI, OK.ru y Simuladores informan tiempo/duración reales del <video> (agente de frames o
    // listener timeupdate), así que también usan la cuenta regresiva de 30 s. Se exige una duración mínima
    // para no confundir un pre-roll/publicidad corta con el final del capítulo.
    val showFrameCountdown = frameDuration > 0.0 && frameRemainingSeconds <= 30.0 &&
        (isFrameXtv || (hasCustomHub && frameDuration >= MIN_WEB_EPISODE_SECONDS))
    val showNextEpisodeCard = nextEpisode != null && (videoEnded || showFrameCountdown) && !nextEpisodeDismissed && !nextEpisodeTriggered

    // Give the mini-hub real TV focus; otherwise the WebView keeps consuming remote input.
    LaunchedEffect(showNextEpisodeCard, nextEpisode?.id) {
        if (showNextEpisodeCard) {
            delay(250)
            runCatching { nextEpisodePlayFocus.requestFocus() }
        }
    }

    // FrameXTV sometimes stops sending timeupdate before the final frame. Start a real
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

        if (isFrameXtv) {
            val js = when (action) {
                "playPause" -> "window.xuperFrameCommand('${if (framePaused) "play" else "pause"}');"
                "seekBack" -> "window.xuperFrameCommand('seekBy',{seconds:-${kotlin.math.abs(seconds)}});"
                "seekForward" -> "window.xuperFrameCommand('seekBy',{seconds:${kotlin.math.abs(seconds)}});"
                "mute" -> if (frameMuted) {
                    "window.xuperFrameCommand('setVolume',{volume:1});"
                } else {
                    "window.xuperFrameCommand('mute');"
                }
                "subtitle" -> "window.xuperFrameCommand('setSubtitle',{index:$subtitleIndex});"
                else -> return
            }
            webView.evaluateJavascript(js, null)
            when (action) {
                "playPause" -> framePaused = !framePaused
                "mute" -> frameMuted = !frameMuted
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
                webView.evaluateJavascript(frameAgentCommandScript(agentTargetNow, cmd, sec)) { raw ->
                    if (raw != "true") android.util.Log.w("XuperAgent", "El frame principal no pudo repartir la orden $cmd")
                }
            }
            return
        }

        if (isVideoApi) {
            // Latino/VideoAPI: solo control directo sobre el <video> accesible. NO se inyectan teclas:
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
        val delta = if (isFrameXtv) SeekRamp.clampDelta(raw, frameCurrentTime, frameDuration) else raw
        scrubFlash = scrubText(if (delta != 0) delta else raw)
        if (delta == 0) return
        if (isFrameXtv && frameDuration > 0.0) frameCurrentTime = (frameCurrentTime + delta).coerceIn(0.0, frameDuration)
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

    fun applyFrameSubtitle(newIndex: Int) {
        frameSubtitleIndex = newIndex
        if (saveSubtitleForContent) playbackPrefs.edit().putInt(subtitlePreferenceKey, newIndex).apply()
        webViewRef?.evaluateJavascript("window.xuperFrameCommand('setSubtitle',{index:$newIndex});", null)
        frameInteraction++
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
                val webView = HubAwareWebView(viewContext) { keyEvent -> onHubKeyState.value(keyEvent) }.apply {
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
                            private val seenFrameMessages = HashSet<String>()

                            /**
                             * Diagnóstico: FrameXTV no documenta qué mensajes emite además de `frameXTV:timeupdate`.
                             * Se registra en logcat (tag XuperFrameXTV) la primera aparición de cada tipo de mensaje
                             * para poder ver, con un dispositivo real, si el player informa sus pistas de subtítulos.
                             * Corre en un hilo del puente de WebView, no en el principal.
                             */
                            @JavascriptInterface fun onFrameMessage(raw: String) {
                                val text = raw.take(400)
                                val name = runCatching {
                                    val json = org.json.JSONObject(raw)
                                    json.optString("event").ifBlank { json.optString("type") }.ifBlank { json.optString("action") }
                                }.getOrDefault("")
                                val key = name.ifBlank { text.take(40) }
                                val first = synchronized(seenFrameMessages) { seenFrameMessages.add(key) }
                                if (first) android.util.Log.i("XuperFrameXTV", "mensaje del iframe: $text")
                            }

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
                                    if (safeDuration > 0.0 && safeDuration - safeCurrent <= 1.0 && (isFrameXtv || safeDuration >= MIN_WEB_EPISODE_SECONDS)) {
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
                    if (isFrameXtv) {
                        loadDataWithBaseURL("https://xuper.local/", frameXtvWrapper(url, savedResumePosition, frameSubtitleIndex, frameServerIndex), "text/html", "UTF-8", null)
                    } else if (isVideoApi) {
                        loadUrl(videoApiEmbedUrl(url, savedResumePosition))
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
                    if (isFrameXtv) {
                        webView.loadDataWithBaseURL("https://xuper.local/", frameXtvWrapper(url, savedResumePosition, frameSubtitleIndex, frameServerIndex), "text/html", "UTF-8", null)
                    } else if (isVideoApi) {
                        webView.loadUrl(videoApiEmbedUrl(url, playbackPrefs.getLong(positionPreferenceKey, savedResumePosition).coerceAtLeast(0L)))
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
                secondsLeft = if (isFrameXtv && frameDuration > 0.0) kotlin.math.ceil(frameRemainingSeconds).toInt().coerceIn(0, 30) else 0,
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
                if (isFrameXtv) {
                    val timeText = { value: Double ->
                        val total = value.toInt().coerceAtLeast(0)
                        "%02d:%02d".format(total / 60, total % 60)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${timeText(frameCurrentTime)} / ${timeText(frameDuration)}", color = Color.White, fontSize = 13.sp, modifier = Modifier.widthIn(min = 104.dp))
                        Box(Modifier.weight(1f).height(4.dp).background(Color(0xFF52616B), RoundedCornerShape(4.dp))) {
                            Box(Modifier.fillMaxWidth(if (frameDuration > 0) (frameCurrentTime / frameDuration).toFloat().coerceIn(0f, 1f) else 0f).height(4.dp).background(Color(0xFF65DCE8), RoundedCornerShape(4.dp)))
                        }
                    }
                } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when {
                            isOkRu -> "OK.RU · controles"
                            isSimuladores -> "SIMULADORES · controles"
                            else -> "LATINO · controles"
                        },
                        color = Color(0xFF65DCE8), fontSize = 13.sp, fontWeight = FontWeight.Bold
                    )
                    // El agente informa posición y duración del video principal: se muestra igual que en FrameXTV.
                    if (agentActive && frameDuration > 0.0) {
                        Text("${SeekRamp.formatClock(frameCurrentTime)} / ${SeekRamp.formatClock(frameDuration)}", color = Color.White, fontSize = 13.sp, modifier = Modifier.widthIn(min = 104.dp))
                        Box(Modifier.weight(1f).height(4.dp).background(Color(0xFF52616B), RoundedCornerShape(4.dp))) {
                            Box(Modifier.fillMaxWidth((frameCurrentTime / frameDuration).toFloat().coerceIn(0f, 1f)).height(4.dp).background(Color(0xFF65DCE8), RoundedCornerShape(4.dp)))
                        }
                    }
                }

                // Fila 1: transporte (+ subtítulos en FrameXTV). Fila 2: opciones de FrameXTV.
                // Antes eran hasta 8 botones en una sola fila, que no entran en pantalla.
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(modifier = Modifier.focusRequester(frameControlsFocus), onClick = {
                        runWebCommand("playPause", frameSubtitleIndex, 0)
                    }) { Text(if (framePaused) "▶ Reproducir" else "Ⅱ Pausar") }

                    // Tocar = 10 s; mantener OK apretado acelera el avance (se aplica al soltar).
                    Button(modifier = holdSeekKeyModifier(false), onClick = { runWebCommand("seekBack", frameSubtitleIndex, 10) }) { Text("↶ 10 s") }
                    Button(modifier = holdSeekKeyModifier(true), onClick = { runWebCommand("seekForward", frameSubtitleIndex, 10) }) { Text("10 s ↷") }
                    Button(onClick = { runWebCommand("mute", frameSubtitleIndex, 0) }) { Text(if (frameMuted) "🔊 Sonido" else "🔇 Silencio") }

                    if (isFrameXtv) {
                        // FrameXTV no permite listar las pistas ni sus nombres: solo existe setSubtitle{index}.
                        Button(onClick = { applyFrameSubtitle(FrameXtvSubtitleSlots.previous(frameSubtitleIndex)) }) { Text("◀ CC") }
                        Button(onClick = { applyFrameSubtitle(FrameXtvSubtitleSlots.next(frameSubtitleIndex)) }) { Text("CC · Pista ${frameSubtitleIndex + 1} ▶") }
                    }
                }

                if (isVideoApi) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            enabled = agentActive && latinoAudioTracks.size > 1,
                            onClick = { runWebCommand("audioNext", frameSubtitleIndex, 0) }
                        ) {
                            val label = latinoAudioTracks.getOrNull(latinoAudioSelected).orEmpty()
                            Text(if (latinoAudioTracks.isEmpty()) "Idioma · no detectado" else "Idioma · ${label.take(18)} ▶")
                        }
                        Button(
                            enabled = agentActive && latinoSubtitleTracks.isNotEmpty(),
                            onClick = { runWebCommand("subtitleNext", frameSubtitleIndex, 0) }
                        ) {
                            val label = latinoSubtitleTracks.getOrNull(latinoSubtitleSelected).orEmpty()
                            Text(if (latinoSubtitleTracks.isEmpty()) "Subtítulos · no detectados" else if (latinoSubtitleSelected < 0) "CC · Desactivados ▶" else "CC · ${label.take(18)} ▶")
                        }
                    }
                    if (agentActive && latinoAudioTracks.isEmpty() && latinoSubtitleTracks.isEmpty()) {
                        Text("VideoAPI no expone pistas de audio/subtítulos al control de Xuper; probá las opciones del reproductor original.", color = Color.LightGray, fontSize = 11.sp)
                    }
                }

                if (isFrameXtv) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = {
                            frameServerIndex = if (frameServerIndex >= 4) 1 else frameServerIndex + 1
                            frameSubtitleIndex = 0
                            saveSubtitleForContent = false
                            playbackPrefs.edit().apply {
                                if (saveServerForContent) putInt(serverPreferenceKey, frameServerIndex) else remove(serverPreferenceKey)
                                remove(subtitlePreferenceKey)
                            }.apply()
                            webViewRef?.loadDataWithBaseURL("https://xuper.local/", frameXtvWrapper(url, 0L, 0, frameServerIndex), "text/html", "UTF-8", null)
                            frameInteraction++
                        }) { Text("Proveedor · ${frameXtvProviderLabel(frameServerIndex)}") }
                        Button(onClick = {
                            saveServerForContent = !saveServerForContent
                            playbackPrefs.edit().apply {
                                putBoolean("web-save-server:$stableContentKey", saveServerForContent)
                                if (saveServerForContent) putInt(serverPreferenceKey, frameServerIndex) else remove(serverPreferenceKey)
                            }.apply()
                            frameInteraction++
                        }) { Text(if (saveServerForContent) "✓ Guardar servidor" else "□ Guardar servidor") }
                        Button(onClick = {
                            saveSubtitleForContent = !saveSubtitleForContent
                            if (saveSubtitleForContent) playbackPrefs.edit().putInt(subtitlePreferenceKey, frameSubtitleIndex).apply()
                            else playbackPrefs.edit().remove(subtitlePreferenceKey).apply()
                            frameInteraction++
                        }) { Text(if (saveSubtitleForContent) "✓ Guardar pista" else "□ Guardar pista") }
                    }
                }
            }
        }
        if (isVideoApi && !hubActive && showLatinoHint && pageError == null && progress >= 100) {
            Text(
                when {
                    !agentSupported -> "Latino: este WebView no permite controlar el video dentro de iframes (actualizá «Android System WebView»). El mando maneja el reproductor original."
                    !agentInstalled -> "Latino: no se pudo activar el control del video. El mando maneja el reproductor original."
                    else -> "Latino: buscando el video para controlarlo… mientras tanto el mando maneja el reproductor original."
                },
                Modifier.align(Alignment.TopCenter).padding(16.dp).background(Color(0xCC101820), RoundedCornerShape(8.dp)).padding(12.dp),
                color = Color.White, fontSize = 14.sp
            )
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
