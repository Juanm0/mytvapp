# Cuentas IPTV para XuperQuilmesTV

Editá `iptv-accounts-raw.txt` en VS Code y pegá los bloques de texto que te entrega tu proveedor.
No hace falta convertirlos a JSON ni cambiar las etiquetas `Host`, `User`, `Pass`, `Status`, `Expires` o `M3U`.

Al compilar, Gradle genera una copia para el APK y excluye automáticamente los bloques cuya fecha `Expires` sea anterior a la fecha de compilación. Las fechas ausentes o mal formadas se conservan para evitar borrar cuentas por error. La app vuelve a filtrar fechas vencidas al cargar las fuentes.

El orden de los bloques define la prioridad de reproducción. Si un contenido coincide en varias cuentas, Xuper prueba primero la primera cuenta que tenga una coincidencia; ante un error de reproducción, prueba la siguiente cuenta y así sucesivamente. Cada nueva reproducción vuelve a empezar por la primera coincidencia disponible.

**Privacidad:** este archivo contiene credenciales y se empaqueta indirectamente en el APK generado. No compartas el TXT ni el APK con terceros si no querés compartir esas cuentas.
