# XuperQuilmesTV — prueba de proveedores FrameXTV

## Archivo incluido
- `app/src/main/java/com/quilmes/xuper/ui/screens/PlayerScreen.kt`

## Cambios
- Sustituye los parámetros de URL experimentales (`server`, `source`, `line`) por el mensaje documentado `postMessage({ action: 'setProvider', provider: '...' }, '*')`.
- El botón recorre Auto, Barbarian, Archer, Goblin y P.E.K.K.A.
- Conserva el guardado de la selección por contenido y aplica el proveedor al cargar el iframe.
- Reinicia la selección de subtítulos cuando se cambia de proveedor.
- Conserva el listener `frameXTV:timeupdate` existente para guardar progreso real cuando el reproductor emite ese evento.

## Importante
El ejemplo de la documentación confirma explícitamente `archer`. Los identificadores `barbarian`, `goblin` y `pekka` se infieren de los nombres publicados y necesitan validación. No se ejecutó una compilación Android en este entorno.
