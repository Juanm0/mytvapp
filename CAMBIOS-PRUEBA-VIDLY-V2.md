# Prueba (Vidly) sin hub — corrección: solo navegaba sobre la pantalla de pausa — 23/10/2026

Archivo a reemplazar: `app/src/main/java/com/quilmes/xuper/ui/screens/PlayerScreen.kt` (pegarlo DENTRO de la carpeta
existente). No se compiló ni se probó en el TV.

## Qué pasaba
Pausa y saltos andaban, pero las flechas solo marcaban elementos de la pantalla "You're watching" (la que aparece
tras unos segundos en pausa) y nunca los de la barra de controles. No era un problema de capas (z-index): la barra
de controles no se estaba detectando por dos causas probables:
1. **Controles que solo aparecen con el mouse encima (:hover).** Con el control remoto no hay mouse, y un mouse
   "falso" hecho desde JavaScript no activa :hover, así que la barra seguía invisible (opacidad 0) y se descartaba.
2. **Descarte por contenedores con `pointer-events: none`.** Es muy común que el contenedor de la barra lo tenga y los
   botones adentro estén en "auto"; el script descartaba el botón por culpa del contenedor.

## Qué cambió
- `HubAwareWebView.nudgeHover()`: en cada flecha/OK (solo en Vidly) la app mueve un mouse REAL (evento nativo de hover)
  sobre el centro del reproductor, así Vidly muestra su barra de controles y la mantiene a la vista mientras navegás.
- Script de navegación:
  - `pointer-events` y `visibility` se miran solo en el propio elemento; `display` y `opacity` sí en los ancestros.
  - Recorre también shadow DOM abierto.
  - Al arrancar la navegación (↑/↓ sin nada marcado) espera ~0,3 s a que aparezcan los controles antes de elegir, para
    no quedarse con lo primero que haya (la pantalla de pausa); empieza por lo más cercano al centro inferior.

## Si sigue sin marcar la barra
`adb logcat -s chromium:I` o pasame una captura con los controles visibles: puede que estén en un canvas/shadow DOM
cerrado, o que el reproductor use otro mecanismo para mostrarlos (por ejemplo, requerir foco de teclado en un elemento).
