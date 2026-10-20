# XuperQuilmesTV — Los Simuladores, foco y mini-hub

## Archivos
Reemplazá estos archivos en el proyecto Android, conservando las rutas:
- `app/src/main/java/com/quilmes/xuper/ui/screens/DetailScreen.kt`
- `app/src/main/java/com/quilmes/xuper/ui/screens/PlayerScreen.kt`
- `app/src/main/java/com/quilmes/xuper/MainActivity.kt`

## Cambios
- Los Simuladores carga temporadas, nombres, sinopsis, imágenes y duración de episodios desde TMDb (`tmdb-tv-332`). Los IDs de OK.ru siguen siendo los usados para reproducir cada episodio.
- Si TMDb no devuelve temporadas/episodios, conserva un fallback básico con los números de capítulo.
- Al ocultar el hub de Latino/FrameXTV/OK.ru se intenta transferir el foco a un elemento invisible y desenfocar el iframe para evitar que quede el borde amarillo.
- El hub también aparece en OK.ru e intenta usar pausa/seek mediante el elemento de vídeo accesible o teclas estándar. Al ser un iframe de otro origen, el proveedor puede bloquear el control externo.
- El mini-hub ya no usa un contador estimado para declarar que terminó el video. Se basa en el evento `ended` del vídeo accesible o mensajes del reproductor; si Latino no emite una señal de finalización, todavía puede no detectarlo.

## Importante
No pude compilar en este entorno porque no hay ejecutable de Gradle disponible. Probar en el dispositivo/Android Studio. No se garantiza que el foco invisible elimine el contorno que el propio reproductor de terceros dibuja dentro de su iframe.
