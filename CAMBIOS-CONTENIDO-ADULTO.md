# Ajuste "Mostrar contenido adulto" (+18) — 21/10/2026

Copiar estos archivos respetando las rutas (todo bajo `app/src/main/java/com/quilmes/xuper/`).
**No se compiló ni se probó en el TV** (sin Android SDK en el entorno de edición).

## Qué hace
- **Perfil**: check "Mostrar contenido adulto (+18)", apagado por defecto. Activarlo pide PIN de 4 dígitos
  (la primera vez lo crea y lo repite para confirmar). Desactivarlo no pide PIN. También hay "Cambiar PIN".
  Teclado numérico en pantalla, acepta teclas numéricas del mando. 5 errores seguidos = bloqueo de 60 s.
  El PIN se guarda con sal + SHA-256 (no en texto plano).
- **Barra lateral**: con el ajuste activado aparece **+18** (antes de PERFIL); al desactivarlo desaparece.
- **Sección +18**: filas Populares / Estrenos / Mejor valoradas (solo títulos que TMDb marca como adultos)
  y buscador propio. Todo en memoria, nada se guarda en el caché de disco.

## Un único lugar para el ajuste
`data/AdultContentSettings.kt` (SharedPreferences `xuper-settings`). Los servicios solo llaman a
`AdultContentSettings.isEnabled()`. `applyIncludeAdult()` deja **un solo** `include_adult` en la URL
(si ya había uno lo reemplaza).

## Dónde se aplica
- `TmdbCatalogService.request(path, includeAdult)` — el parámetro es obligatorio (sin valor por defecto);
  se sacó el `include_adult=false` fijo (antes de línea ~256) y el duplicado de `search/multi` (~126).
- Buscador (`search`), fichas (`loadRelatedTitles`, `loadSeasons`, `loadEpisodes`) y
  `TmdbMetadataService.lookup` (~60) usan el ajuste.
- **Filas de catálogo de Inicio**: siempre `include_adult=false`, y además se descarta cualquier ítem `adult=true`.
- Con el ajuste apagado, se filtra todo lo marcado como adulto en Buscar, Mi lista y Continuar viendo
  (se recuerdan los ids adultos vistos), y la fila "Descubiertos recientemente" de Inicio nunca los muestra.
- `Title` suma el campo `adult` (por defecto false).
- El caché de metadata de listas M3U separa las entradas con el ajuste activado (prefijo `+18|`).

## Decisiones / límites
- Con el ajuste activado, el buscador general **también** incluye resultados adultos (como pedía la tarea);
  la sección +18 es una vista adicional. Si preferís que Buscar nunca los muestre, hay que filtrar `it.adult`
  en `SearchScreen.kt`.
- TMDb no tiene un filtro "solo adultos": las filas de +18 salen de `discover` filtrando `adult == true`,
  así que pueden venir cortas. La búsqueda en +18 es la vía más confiable.
- Si se olvida el PIN: borrar los datos de la app (no hay recuperación).
- Las listas M3U locales no traen contenido adulto; esta sección es solo metadata/carátulas de TMDb.

## Corrección (21/10/2026): al tocar un título +18 no abría la ficha
Causa: `M3uRepository.find(id)` dejó de encontrar los títulos +18 porque se excluyeron de la fila
"Descubiertos recientemente" (para que no aparezcan en Inicio). Ahora `find` también busca en los
títulos registrados por el buscador/+18 (solo con el ajuste activado). Además, cada fila de +18
consulta 8 páginas de `discover` en vez de 4.
