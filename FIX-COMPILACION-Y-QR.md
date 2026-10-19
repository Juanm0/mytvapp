# Corrección de compilación y editor QR

## Error de Stremio (`Redeclaration`)
Esta versión usa el editor QR de fuentes web y no necesita las clases Stremio duplicadas. Los dos archivos problemáticos están incluidos como stubs sin declaraciones para que, al copiar el ZIP encima de la carpeta existente, sobrescriban la versión vieja que causaba `Redeclaration`. Revisá que el diff de GitHub muestre esos dos archivos modificados y que ya no declaren `StremioStream` ni `StremioRepository` antes de compilar.

## Comportamiento del QR
- Las URLs de FrameXTV y otras fuentes ya no aparecen permanentemente en la ficha.
- Al ampliar el QR, se muestran las fuentes candidatas para ese título/episodio, con botones para reproducir o quitar una fuente.
- Al cerrar el QR, las URLs quedan ocultas otra vez.
- Al escanear el QR desde un teléfono en la misma red Wi-Fi, se abre el editor local para reemplazar o agregar enlaces; los cambios se guardan en el dispositivo Android.

## Listas IPTV
`config/iptv-accounts-raw.txt` sigue siendo el archivo editable para las cuentas. El extractor acepta bloques Host/User/Pass/Expires/M3U y URLs M3U completas pegadas una por línea. La tarea de Gradle excluye las cuentas con una fecha `Expires` anterior al día de compilación.

## Compilación
Este entorno no dispone de un Android SDK/Gradle completo, por lo que hay que validar con `gradle assembleRelease --no-daemon` en el entorno de compilación. No se afirma que el APK haya sido compilado aquí.
