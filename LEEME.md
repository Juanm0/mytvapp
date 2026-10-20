# XuperQuilmesTV — prueba 4: persistencia del servidor

Reemplazar únicamente:
- `app/src/main/java/com/quilmes/xuper/MainActivity.kt`
- `app/src/main/java/com/quilmes/xuper/ui/screens/PlayerScreen.kt`

## Cambios
- El índice elegido de servidor ahora se aplica también al abrir/recrear el WebView, no solo al tocar el botón.
- El botón `Guardar servidor` es independiente de `Guardar pista` (subtítulos).
- `Guardar servidor` guarda la selección por contenido; al desactivarlo, elimina la preferencia guardada.
- Al cambiar servidor, se reinicia el índice de subtítulos y se borra su preferencia, porque las pistas pueden ser distintas.
- La etiqueta muestra el índice que se está probando (`Pista servidor · 2`, etc.), acorde al mensaje `Connecting to 2` que se observó.

## Limitación
Los parámetros `server`, `source` y `line` siguen siendo intentos exploratorios, no una API confirmada de FrameXTV. Esta versión corrige el guardado/aplicación del índice, pero no puede garantizar que el sitio acepte ese índice como servidor real. No se ejecutó compilación Gradle en este entorno.
