# Corrección de reproductores V3

## FrameXTV
- El foco vuelve al WebView cuando el hub se oculta; esto permite volver a abrirlo con el mando.
- El listener del WebView deja de reiniciar continuamente el temporizador mientras el hub está visible.
- El hub se oculta tras 5 segundos y recupera el foco del hub al volver a abrirse.
- El selector alterna únicamente entre las dos opciones españolas solicitadas.
- La orden `setSubtitle` se envía después de esperar a que el iframe inicialice el reproductor y se reintenta una vez.

## VideoAPI / Latino
- Se quitó el hub externo que simulaba controles enviando teclas al WebView. Esa técnica no garantiza que las órdenes lleguen al reproductor embebido y podía capturar el mando sin controlar el video.
- Se conserva el embed de VideoAPI con reproducción automática, controles y subtítulos habilitados, para que el reproductor original reciba la navegación.

## Limitaciones de verificación
El proyecto se modificó estáticamente. Este entorno no tiene un wrapper de Gradle ni SDK Android disponibles para compilar e instalar el APK. FrameXTV no documenta un método para enumerar las pistas y obtener sus nombres reales; los rótulos Spanish / Spanish (2) son las dos opciones solicitadas, no una lectura confirmada de los metadatos del stream.
