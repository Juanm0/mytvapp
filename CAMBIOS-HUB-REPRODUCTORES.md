# Hub de los reproductores web (FrameXTV / Latino) — 20/10/2026

> Este documento describe el estado **real** del código. `FIX-REPRODUCTORES-V3.md` quedó obsoleto
> (decía que el hub de Latino estaba quitado y que el selector de subtítulos alternaba dos opciones;
> ninguna de las dos cosas era cierta en el código actual).

## Iteración 2 — devolución de la prueba en el televisor (20/10/2026)

**Lo que se vio en el TV:** FrameXTV bien; Latino sin hub, sin pausa, y adelantar/atrasar poco; Simuladores
reproduce el episodio pero sus botones (pausa, ±10 s, silencio) no hacen nada; mantener apretado adelantar no
avanza "mucho" como en una plataforma de streaming.

**Causas (por lectura del código; no verificadas en dispositivo):**
- *Avance sostenido:* repetía 10 s fijos (sin aceleración) y solo con el hub oculto; con el hub visible ←/→ mueven
  el foco y los botones `↶ 10 s` / `10 s ↷` hacían un único salto por OK.
- *Latino:* el hub solo aparece si una sonda ve un `<video>`, pero `evaluateJavascript` solo corre en el frame
  principal. Con el video en un iframe de otro origen la sonda da falso, no hay hub y el mando va al reproductor
  original (por eso adelanta/atrasa con sus teclas pero OK no pausa). Aun con sonda positiva, el hub de Latino
  arrancaba oculto.
- *Simuladores / OK.ru:* mismo motivo (video en iframe de ok.ru) y el respaldo inyectaba `DPAD_CENTER` (no es la
  tecla de pausa de un reproductor HTML5) con el foco de Android en un botón Compose, no en el WebView.
- *OK con el hub oculto* no hacía nada en los reproductores web (el nativo sí pausa).

**Limitación de la API documentada:** VideoAPI solo documenta parámetros de URL y marca `post_message` /
`communication` como bloqueados; FrameXTV no tiene documentación. No existe una forma documentada de pausar o
adelantar Latino desde la app.

**Workaround (explícito, no depende de ninguna API de terceros):** agente de control inyectado por la app.
- `WebViewCompat.addDocumentStartJavaScript(webView, XUPER_FRAME_AGENT_JS, setOf("*"))` (dependencia
  `androidx.webkit:webkit:1.11.0`) corre el script en todos los frames, también de otro origen, antes que la página.
  Se registra antes de cargar la URL y solo si `WebViewFeature.DOCUMENT_START_SCRIPT` está soportado.
- Cada frame ubica su `<video>` más grande (incluye shadow DOM abierto), lo informa cada segundo y ante
  `play/pause/volumechange/seeked/loadedmetadata/ended` (`XuperBridge.onAgentReport`; los iframes se lo mandan al
  frame principal por `postMessage` y este lo entrega al puente). Kotlin elige como principal el de mayor área
  (≥ 2 500 px²) entre los que informaron en los últimos 6 s.
- Las órdenes (`toggle`, `seek ±N`, `mute`) las reparte el frame principal a todos los frames por `postMessage` y solo
  obedece el que tiene el id elegido. El estado de pausa/silencio/posición/duración que muestra el hub es el
  informado por el video.
- Alcance: Latino, OK.ru y Simuladores. FrameXTV no lo usa (su canal propio sigue igual).
- Si no hay soporte o falla la instalación: Latino queda sin hub y con el mando en el reproductor original, con un
  aviso que dice por qué; Simuladores/OK.ru usan el respaldo anterior.

**Cambios:**
1. **Avance sostenido** (`SeekRamp` / `SeekScrubber` en `WebHubKeyRouter.kt`): al apretar se suma un paso de 10 s; si
   se mantiene, desde los 450 ms se suma un paso cada 300 ms con tamaño creciente (10, 20, 30, 60, 120, 180 s por
   paso). Se acumula y se aplica con **un solo** seek al soltar (o 700 ms después del último paso si el mando
   pierde el "soltar"; tope 15 s por pulsación y 4 h acumuladas). Indicador central con el delta y, si se conoce
   la duración, la posición de destino. Tras 1/2/3/4/5 s apretado se acumulan ≈ 40/140/260/440/860 s.
   Pulsaciones sueltas muy seguidas (< 700 ms, mandos que repiten pulsaciones) también aceleran.
   En FrameXTV el salto se limita a lo que cabe entre el inicio y el final (con `timeupdate`).
2. **Hub visible:** OK sostenido sobre `↶ 10 s` / `10 s ↷` usa el mismo avance sostenido; tocar sigue siendo 10 s.
3. **OK con el hub oculto = reproducir/pausar** (y muestra el hub), también con las teclas de medios. Solo en el
   primer key-down (no repite con OK sostenido). El mini-hub de "Episodio siguiente" sigue teniendo prioridad sobre OK.
4. **Latino:** el hub se muestra unos segundos cuando el agente detecta el video (antes arrancaba oculto), con barra
   de posición; el aviso sin control se cuenta desde que la página terminó de cargar y explica la causa.
5. **Simuladores / OK.ru:** los botones del hub usan el agente (con respaldo anterior si no hay agente).

**No se tocó:** FrameXTV (`postMessage`, subtítulos, proveedores), `NativePlayerScreen`, listas IPTV, navegación,
`MainActivity`, ni la firma pública de `PlayerScreen`.

**Verificación:** la lógica de teclas y la rampa (`WebHubKeyRouter.kt`) se compiló con `kotlinc 1.9.24` y pasa 121
comprobaciones en la JVM (tabla de teclas, rampa, límites, formatos). `PlayerScreen.kt` pasa por `kotlinc` sin errores
de sintaxis y con el mismo perfil de errores del original (solo referencias de Android/Compose sin resolver, más 2 de
"tipo de parámetro no inferido" de las dos lambdas nuevas). **Nada de esto está probado en el televisor.**

**Pendiente de probar:** hold de ←/→ en FrameXTV y en Latino; hub de Latino (aparece, pausa, ±10 s, silencio);
Simuladores/OK.ru (pausa, ±10 s, silencio, hold); `adb logcat -s XuperAgent:I XuperFrameXTV:I` para ver qué frames
informan un `<video>`. Si Latino sigue sin hub, el logcat y el aviso en pantalla dicen si es falta de soporte del
WebView, instalación fallida o que no se encontró ningún `<video>` en ningún frame.

---

## Iteración 1 (lo que sigue describe el estado previo; donde contradiga a la iteración 2, vale la 2)

## Qué se encontró

- El `PlayerScreen.kt` de la **raíz del proyecto** es una copia vieja del archivo completo (commit
  `e3e55f4`), no un parche parcial, y no contiene `runWebCommand()`. Gradle lo ignora porque no está
  bajo `src/`. El archivo real es `app/src/main/java/com/quilmes/xuper/ui/screens/PlayerScreen.kt`,
  que ya tenía una `runWebCommand()` más completa. **No se integró nada de la copia de la raíz.**
  Conviene borrarla del repositorio para que no confunda.
- Había **dos manejadores de teclas duplicados** (Compose y `HubAwareWebView`) que ya habían
  divergido. El del WebView se crea una sola vez en el `factory` del `AndroidView`, así que usaba
  valores viejos de `showNextEpisodeCard`/`nextEpisode`.
- Con el hub visible, ↑/↓ forzaban el foco al botón "Pausar" y el hub tenía hasta 8 botones en una
  sola fila (probablemente no entraban en pantalla).
- Para Latino, el código inyectaba teclas (Espacio, flechas, `M`) al WebView cuando no podía tocar el
  `<video>`. Esas teclas llegan al reproductor original, que las interpreta a su manera.

## Qué permite realmente cada API

**VideoAPI (Latino)** — documentación oficial: <https://videoapi.la/api>
- Solo documenta parámetros de URL: `autoplay`, `auto_next`, `start_at`, `skip_intro`, `thumbnails`,
  `subtitles`, `quality_selector`, `hide_controls`, `hide_title`, `skin` y colores.
- **No documenta ningún comando de control ni eventos.** Al contrario: `post_message` y
  `communication` figuran como parámetros *bloqueados* ("la query pública no habilita comunicación").
  Tampoco documenta atajos de teclado.
- Conclusión: no hay una forma documentada de pausar, adelantar, cambiar subtítulos ni leer la
  posición desde afuera. Lo único posible es actuar sobre el `<video>` **si** está en un documento
  accesible desde el WebView (no dentro de un iframe de otro origen).

**FrameXTV** — no encontré documentación oficial.
- El código usa `play`, `pause`, `seekBy`, `mute`, `setVolume`, `setSubtitle {index}`,
  `setProvider` y el evento `frameXTV:timeupdate`. Un proyecto de terceros (Homeflix_2, PR #12) usa
  `timeupdate` y play/pause/±10 s por `postMessage`, pero tampoco lo probó contra el servicio real.
  El resto (`setSubtitle`, `setProvider`, `mute`, `setVolume`) no pude corroborarlo.
- **No hay un comando conocido para listar las pistas de subtítulos ni sus nombres.**

## Cambios

### `PlayerScreen.kt`
1. **Un solo manejador de teclas** (`onHubKey`), usado por Compose y por el WebView, leído con
   `rememberUpdatedState` (corrige los valores viejos del `factory`).
2. **Hub oculto:** ↓ (y ↑) lo abre y enfoca el primer botón; ←/→ adelantan/atrasan 10 s (se repite
   mientras se mantiene la tecla).
3. **Hub visible:** las cuatro flechas mueven el foco *dentro* del hub y se consumen, así el foco ya
   no escapa al WebView ni vuelve siempre a "Pausar". Cualquier tecla, comando o clic reinicia los
   5 s; sin interacción se oculta y se puede reabrir con ↓.
4. **FrameXTV — subtítulos:** botones `◀ CC` y `CC · Pista N ▶`. `▶` da la vuelta al llegar a 32;
   `◀` no baja de la pista 1. Los nombres siguen sin conocerse: la interfaz solo dice "Pista N".
5. **Hub en dos filas** (transporte + subtítulos / proveedor + guardados) para que todos los botones
   entren en pantalla.
6. **Latino:** una sonda cada 1,5 s comprueba si hay un `<video>` accesible.
   - Si lo hay: aparece el hub y los comandos actúan **directo sobre el `<video>`** (sin teclas inyectadas).
   - Si no: no se muestra el hub, no se intercepta ni se inyecta ninguna tecla y el WebView conserva
     el foco: el mando maneja el reproductor original de VideoAPI. Se muestra un aviso de 7 s.
   - Se eliminó el envío de Espacio/flechas/`M` al WebView para Latino. OK.ru y Simuladores
     conservan su comportamiento anterior.
7. **Diagnóstico FrameXTV:** los mensajes del iframe que no son `timeupdate` se registran en logcat
   (la primera vez de cada tipo) para descubrir, con un dispositivo real, si el reproductor informa
   sus pistas:

   ```
   adb logcat -s XuperFrameXTV:I
   ```

### `WebHubKeyRouter.kt` (nuevo)
Decisión "qué hace cada tecla según el estado del hub" en Kotlin puro, más la lógica del selector de
subtítulos. Se ejecutó en la JVM (ver abajo).

## Verificación

- **No se compiló con Gradle ni se probó en un dispositivo.** El entorno no tiene Android SDK ni
  Gradle (y no puede descargarlos), así que **no se ejecutó `gradle assembleRelease --no-daemon`.**
- Se compiló `WebHubKeyRouter.kt` con `kotlinc 1.9.24` y se ejecutaron 36 comprobaciones de la lógica
  de teclas y subtítulos: todas pasan.
- `PlayerScreen.kt` se pasó por `kotlinc` sin el classpath de Android/Compose: no hay errores de
  sintaxis y el perfil de errores es el del archivo original (solo referencias sin resolver), con
  +3 de "tipo de parámetro no inferido" que corresponden a las 3 lambdas nuevas. Las llaves cuadran.
  Esto **no** reemplaza una compilación real.
- No se tocaron `NativePlayerScreen`, `MainActivity`, las listas IPTV ni la navegación; la firma
  pública de `PlayerScreen` no cambió.

## Pendiente de probar en el televisor

1. FrameXTV: con el hub oculto, ↓ lo abre; navegar con ←/→/↑/↓ lo mantiene abierto; a los 5 s sin
   tocar nada se oculta y ↓ lo vuelve a abrir.
2. FrameXTV: `◀ CC` / `CC · Pista N ▶` y capturar el logcat (`XuperFrameXTV`) para ver si el player
   informa pistas con nombre. Con ese dato se puede reemplazar "Pista N" por nombres reales.
3. Latino: ver si aparece el hub (sonda positiva) o el aviso (reproductor original). Si el mando
   sigue sin permitir navegar el reproductor original, la causa está en el propio reproductor de
   VideoAPI y no hay API para corregirlo desde la app; habría que pedirle soporte a VideoAPI.
