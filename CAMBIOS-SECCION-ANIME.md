# Sección ANIME (AniList + TMDb) — 21/10/2026

Este zip es ACUMULATIVO: trae todos los archivos modificados respecto del proj_1.zip original
(contenido adulto +18, VideoAPI/FrameXTV de anime y la sección ANIME). Copiar respetando rutas.
No se compiló ni se probó en el TV (sin Android SDK, y sin acceso a las APIs desde el entorno de edición).

## Qué hace
- Barra lateral: nueva sección **ANIME** (antes de +18/PERFIL). Filas de AniList: Tendencias, En emisión,
  Populares y Mejor valoradas (siempre sin contenido adulto), más buscador. Las filas aparecen una por una.
- **Prioridad TMDb**: cada anime de AniList se busca en TMDb; si hay coincidencia segura (género Animación,
  título casi idéntico, año ±1) se muestra la ficha de TMDb (temporadas/episodios reales) y se le asocia el id de
  AniList para reproducir por la ruta de anime de FrameXTV. Si no, se muestra la ficha de AniList.
- **Ficha de AniList** (`anilist-<id>`): episodios 1..N (N = episodios de AniList, o los emitidos hasta ahora,
  o 12 si no se sabe). Solo botón "Reproducir episodio" (FrameXTV, ruta de anime). Sin Latino: no se sabe si el id
  de videoapp.zip es de AniList o de TMDb.
- Siguiente episodio automático también para fichas de AniList.
- Caché en disco: `anime_tmdb_map.json` (equivalencias AniList→TMDb; la primera carga tarda más porque
  hace una búsqueda en TMDb por cada anime) y `anilist_ids.json`.

## Archivos nuevos
`data/AnimeCatalogService.kt`, `data/TitleMatch.kt`, `data/AniListResolver.kt`, `ui/screens/AnimeScreen.kt`
(+ los de +18: `data/AdultContentSettings.kt`, `ui/components/PinDialog.kt`, `ui/screens/AdultScreen.kt`)

## Límites / a verificar
- Entradas de AniList que son una temporada ("X 2nd Season") normalmente NO coinciden con TMDb (título y año
  distintos) y salen como ficha de AniList; es lo esperado.
- Un anime de TMDb usa el id de AniList de la entrada que se vio en la sección (o, si abrís la ficha por otro
  camino, el que resuelva la búsqueda por título). Temporadas 2+: se manda temporada/episodio de TMDb con ese id;
  si no reproduce bien, pasame un link que funcione y lo ajusto.
- Solo series (formatos TV, TV corto y ONA). No hay películas de anime.
- Las fichas de AniList no aparecen en Mi lista/Continuar viendo tras reiniciar la app (solo se pueden abrir
  desde la sección ANIME).
