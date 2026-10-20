# XuperQuilmesTV — progreso estimado, mini-hub y pista de servidor

## Archivos
- `app/src/main/java/com/quilmes/xuper/MainActivity.kt`
- `app/src/main/java/com/quilmes/xuper/ui/screens/PlayerScreen.kt`

Copiar estos dos archivos respetando las rutas en el proyecto existente.

## Cambios
- El mini-hub de siguiente episodio aparece a 20 segundos del final (cuando el reproductor informa duración y tiempo).
- VideoAPI guarda una posición estimada mientras la pantalla está abierta y no está pausada desde los controles de Xuper. Al volver, añade `start_at=<segundos>` a la URL.
- FrameXTV incorpora el botón `Pista servidor` junto a la pista de subtítulos y guarda la selección por contenido.
- Se mantienen los arreglos de imports de compilación y el registro de historial externo de la iteración previa.

## Limitaciones importantes
- VideoAPI no publica en la documentación consultada un callback de progreso para leer el tiempo real. Por eso el avance de VideoAPI es aproximado: si se pausa usando los controles propios del proveedor, se cambia de app o hay buffering, la estimación puede desviarse.
- El mini-hub a 20 segundos solo se puede mostrar cuando el reproductor informa duración y tiempo; no se puede calcular el final de un VideoAPI sin duración real.
- El mensaje `setServer`/`changeServer` para FrameXTV se envía como mejor esfuerzo. No encontramos una especificación pública verificable de ese comando; si el reproductor no lo admite, el botón guardará el índice pero no cambiará realmente el servidor. Probarlo en el dispositivo es necesario.
- No se pudo ejecutar una compilación Gradle/Android en este entorno.
