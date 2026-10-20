# XuperQuilmesTV — cambios de reproductor (20/10/2026)

Esta entrega contiene solo los tres archivos Kotlin modificados; no es el proyecto completo.

## Series
- Se ocultan los botones generales `Reproducir` y `Latino` en fichas que cargan temporadas/episodios de TMDB.
- Cada episodio conserva `Reproducir episodio` y suma `Latino`, ambos con el ID de temporada y episodio correspondiente.

## Reproductor principal (FrameXTV)
- La pista seleccionada se puede guardar por contenido con `✓ Guardar pista`, junto al selector `CC · Pista N`.
- La selección guardada se persiste por película o episodio y se vuelve a aplicar al reabrirlo.
- Se guarda la posición de reproducción y se intenta restaurar al volver a entrar.

## Reproductor Latino (VideoAPI)
- Se ocultan los controles de subtítulos de Xuper en el hub Latino.
- Se intenta guardar y restaurar la posición usando el puente de WebView cuando el reproductor expone un video HTML5 accesible.
- Se añade CSS para quitar bordes/contornos del documento, iframe o video que pudieran dibujarse en amarillo en el borde de la pantalla.

## Límite importante
VideoAPI/FrameXTV usan reproductores embebidos. Android no puede controlar de forma fiable pistas o posición dentro de un iframe de otro origen si el proveedor no expone una API para ello. La restauración se aplica cuando el reproductor permite el acceso o recibe el comando; no se puede garantizar para todos los streams.

## Archivos incluidos
- `app/src/main/java/com/quilmes/xuper/MainActivity.kt`
- `app/src/main/java/com/quilmes/xuper/ui/screens/DetailScreen.kt`
- `app/src/main/java/com/quilmes/xuper/ui/screens/PlayerScreen.kt`

No se incluye `vod-original.m3u` ni ningún otro archivo de datos del proyecto.
