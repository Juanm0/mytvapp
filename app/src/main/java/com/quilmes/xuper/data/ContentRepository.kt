package com.quilmes.xuper.data

import kotlinx.coroutines.flow.StateFlow

/**
 * Punto único de entrada para el contenido de la app.
 *
 * - `sections` se va llenando de a poco: primero desde el caché del disco
 *   (instantáneo) y después con lo que baja de internet. La UI solo observa.
 * - `refresh()` se llama una vez al arrancar (MainActivity).
 *
 * Para otra fuente (Xtream Codes, backend propio) implementá esta interfaz
 * y cambiá `buildRepository()` en MainActivity.kt.
 */
interface ContentRepository {
    val sections: StateFlow<List<Section>>
    val loading: StateFlow<Boolean>
    suspend fun refresh()
    fun find(id: String): Title?
    fun search(query: String, limit: Int = 120): List<Title>
}
