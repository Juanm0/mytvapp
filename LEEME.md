XuperQuilmesTV — ajustes de navegación y pausa web

Archivo incluido:
- PlayerScreen.kt

Cambios:
1. En los reproductores con hub personalizado, Atrás cierra el hub si está visible.
2. Con el hub oculto, Atrás ejecuta onExit() directamente y ya no recorre el historial interno del WebView. Esto evita que el botón vuelva por los proveedores anteriores antes de regresar a la ficha.
3. Para VideoAPI/Latino, el botón de pausa conserva el intento de control JavaScript y añade un fallback de tecla multimedia + barra espaciadora cuando el vídeo no es accesible desde la página contenedora.

Importante:
- La pausa de un iframe de otro origen no se puede garantizar desde JavaScript si el proveedor no expone un comando de control. Este cambio añade un fallback, no reemplaza una API oficial.
- No se pudo compilar el proyecto en este entorno porque no se ejecutó una build de Android/Gradle.
- No se cambió la lista de proveedores FrameXTV; esperamos los identificadores adicionales que se validen durante la investigación.
