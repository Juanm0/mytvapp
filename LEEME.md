# XuperQuilmesTV — prueba de hub y Los Simuladores

Este ZIP contiene únicamente los tres archivos Kotlin modificados, basados en el proyecto actualizado que compartiste.

## Cambios

- **Atrás en FrameXTV/Latino:** con el hub abierto, Atrás lo cierra; con el hub cerrado, sale directamente a la ficha y no recorre el historial interno del WebView (donde las recargas de proveedor podían quedar apiladas).
- **Foco/borde del iframe:** se elimina el auto-foco forzado del iframe y el CSS que agrandaba/añadía contornos a elementos enfocados. Al ocultar el hub se intenta quitar el foco del iframe exterior antes de devolverlo al WebView.
- **Pausa de Latino:** se mantiene el intento por JavaScript y, si el documento no puede controlar el video, se envía la tecla multimedia de play/pausa como fallback. Los iframes de otro origen pueden impedir un control directo; se necesita probarlo en el dispositivo.
- **Los Simuladores:** cuando la ficha corresponde a la serie TMDb 332, muestra dos temporadas con 13 y 11 episodios. Cada episodio abre `https://los-simuladores.bringfeel.com.ar/reproductor?temporada=N&capitulo=N`. Usa el reproductor del sitio, sin hub propio ni botones FrameXTV/Latino.

## Cómo probar

1. Hacé una copia del proyecto.
2. Reemplazá los archivos por los que están en este ZIP respetando sus rutas.
3. Compilá e instalá.
4. En Latino: abrí el hub, probá Pausar/Reproducir y observá si el borde amarillo desaparece al ocultarse el hub.
5. En FrameXTV: cambiá de proveedor, cerrá el hub y pulsá Atrás. Debe volver directamente a la ficha.
6. Abrí Los Simuladores (ID 332), elegí temporada/episodio y comprobá que la URL contiene los dos números correctos.

## Limitación

No pude ejecutar una compilación Android en este entorno (no hay Gradle/SDK configurado), así que esta versión necesita validación en tu equipo. El fallback de pausa no puede superar las restricciones de origen cruzado del reproductor externo si este no acepta teclas multimedia.

## Investigación de la API de Los Simuladores

Para descubrir qué solicita el sitio:
1. Abrí la página del episodio en Chrome de escritorio.
2. F12 → Network/Red → activá Preserve log.
3. Recargá y filtrá primero por Fetch/XHR; si no aparece la fuente, mirá All y Media.
4. Anotá las solicitudes que aparezcan al cargar el episodio. En particular, buscá JSON, manifiestos `.m3u8`/`.mpd` o una URL de iframe. Revisá Initiator para saber qué archivo JS disparó cada solicitud.
5. Cambiá `temporada` o `capitulo` y observá qué solicitud cambia.

Compartí URL, método, status y una respuesta no sensible. No compartas cookies, tokens, cabeceras de autorización ni datos de sesión. Puede que el sitio no tenga una API REST pública: también podría cargar el reproductor desde un iframe de otro proveedor.
