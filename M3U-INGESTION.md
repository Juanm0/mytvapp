# Ingesta M3U y respaldo por contenido

## Cuentas IPTV definidas manualmente

Editar `config/iptv-accounts-raw.txt` desde VS Code. Se aceptan bloques de texto con `Host`, `User`, `Pass`, `Status`, `Expires` y `M3U`; los separadores de guiones son opcionales. La aplicación toma la URL `M3U` si está presente. Si falta, intenta construir una URL `get.php` a partir de host, usuario y contraseña.

Gradle genera `app/build/generated/iptv-assets/iptv-accounts-raw.txt` antes de `preBuild`. Los registros cuya fecha `Expires` sea anterior al día de compilación se eliminan tanto del TXT fuente como de la copia empaquetada en el APK. La app también filtra vencimientos al leer la configuración como protección adicional.

## Prioridad de reproducción

Las cuentas del TXT se cargan antes que las listas integradas. Para un contenido coincidente, el repositorio devuelve como máximo una alternativa por cuenta, en el orden configurado. El reproductor intenta la primera coincidencia; si Media3 informa un error, intenta la siguiente URL sin repetir URLs ya probadas. Una reproducción nueva vuelve a priorizar la primera cuenta disponible. Si ninguna cuenta manual tiene coincidencia, se conserva la reproducción normal de la fuente elegida.

El matching se basa en los nombres y, para episodios, temporada/episodio. La coincidencia de títulos no garantiza que dos proveedores tengan exactamente la misma edición o idioma.
