# Botón "Prueba" (reproductor Vidly) — 21/10/2026

Reemplazar estos 4 archivos (rutas bajo `app/src/main/java/com/quilmes/xuper/`). No se compiló ni se probó en el TV.

## Qué hace
- Nuevo botón **▶ Prueba** al lado de **▶ Latino**: en la ficha (películas / series sin temporadas) y en cada
  episodio de las series.
- URLs (id de TMDb, según https://vidly.pro/#api):
  - película: `https://vidly.pro/movie/{id}?autoplay=1`
  - episodio: `https://vidly.pro/tv/{id}/{temporada}/{episodio}?autoplay=1`
- El siguiente episodio (mini-hub) también genera la URL de Vidly cuando se está viendo con Prueba.
- Anime: sin tratamiento especial (como pediste); una serie de anime con id de TMDb usa `/tv/` igual que cualquier serie.

## Cambios por archivo
- `data/WebSourceRegistry.kt`: `vidlyUrl()`, `isVidlyHost()`, `isVidlyEmbedUrl()`.
- `MainActivity.kt`: prefijo `vidly:` en el pedido de reproducción + siguiente episodio con Vidly.
- `ui/screens/DetailScreen.kt`: los dos botones "Prueba".
- `ui/screens/PlayerScreen.kt`: Vidly se reconoce como embed conocido y se controla igual que Latino
  (agente de frames; el hub propio solo aparece cuando se confirma un <video> controlable, si no el mando va
  directo al reproductor de Vidly). No hereda lo específico de VideoAPI (parámetros de URL, auto-selección de
  español, botones Idioma/CC, limpieza de bordes).

## A tener en cuenta al probar
- Si el hub propio no aparece, el mando queda en el reproductor de Vidly (tiene sus propios controles).
- Vidly muestra su propio botón "Siguiente episodio"; si molesta junto al de Xuper, agregar `&nextEpisode=0` en
  `vidlyUrl()`. Otros parámetros útiles de la doc: `theme`, `color`, `t` (inicio en segundos), `audioPicker=1`.
- Vidly también acepta anime por id de AniList/MAL (no se usó).
