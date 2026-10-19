# Corrección del error de compilación Stremio

El proyecto debe contener una sola implementación de `StremioStream` y `StremioRepository`.

La implementación correcta para este proyecto está en:
`app/src/main/java/com/quilmes/xuper/data/StremioAddonApi.kt`

Si estás aplicando estos cambios encima de un repositorio existente en GitHub, elimina el archivo antiguo duplicado:
`app/src/main/java/com/quilmes/xuper/data/StremioRepository.kt`

No borres `StremioAddonApi.kt`: `DetailScreen.kt` usa `StremioRepository` y `StremioStream` definidos ahí. Los errores `qualityRank`, los tipos nullable y el parámetro `Int?` estaban en el archivo antiguo que duplica esas declaraciones.

Después de borrar el archivo antiguo, ejecuta `gradle assembleRelease --no-daemon` otra vez. Si aparece un error distinto, revisa el nuevo log; esta corrección elimina la duplicación, pero no afirma que se haya ejecutado una compilación completa en este entorno.
