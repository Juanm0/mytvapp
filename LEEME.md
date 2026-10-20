# XuperQuilmesTV — prueba 3 de cambio de servidor

Reemplazar únicamente:
- `app/src/main/java/com/quilmes/xuper/MainActivity.kt`
- `app/src/main/java/com/quilmes/xuper/ui/screens/PlayerScreen.kt`

## Qué cambia
- El botón `Pista servidor` ahora cicla servidores 1 a 5.
- En cada cambio reinicia la selección de subtítulos y elimina la preferencia guardada de subtítulos para ese contenido.
- Además de enviar mensajes `postMessage` alternativos, recarga el iframe probando parámetros URL `server`, `source` y `line`.

## Importante
Es una prueba exploratoria, no una integración confirmada: no hay una especificación pública verificada de FrameXTV que confirme esos nombres de parámetros o mensajes. El servidor puede ignorarlos. Si el video vuelve a cargar pero el servidor no cambia, necesitaremos conocer la URL real de los servidores o inspeccionar el selector del sitio. No se ejecutó Gradle/Android build en este entorno.
