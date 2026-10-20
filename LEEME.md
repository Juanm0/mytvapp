# XuperQuilmesTV — Los Simuladores por OK.ru y siguiente episodio

Reemplazá estos tres archivos en el proyecto Android, conservando las rutas:
- `app/src/main/java/com/quilmes/xuper/MainActivity.kt`
- `app/src/main/java/com/quilmes/xuper/ui/screens/DetailScreen.kt`
- `app/src/main/java/com/quilmes/xuper/ui/screens/PlayerScreen.kt`

Cambios:
- Los 24 IDs aportados para Los Simuladores se usan como `https://ok.ru/videoembed/ID`.
- La ficha mantiene el selector de temporada y episodio.
- Para Los Simuladores se calcula el siguiente episodio con el catálogo local de IDs, sin depender de TMDb para resolver el video siguiente.
- El mini-hub ya no se dispara por una estimación del tiempo restante: espera una señal de finalización del video accesible en la página o un evento de finalización enviado por el reproductor. Si el proveedor mantiene el video dentro de un iframe de otro origen y no comunica el final, la página puede no exponer ese evento.

No se pudo ejecutar una compilación Android en este entorno; probar en Android Studio. La reproducción de OK.ru y la detección del evento final quedan pendientes de prueba real en el dispositivo.
