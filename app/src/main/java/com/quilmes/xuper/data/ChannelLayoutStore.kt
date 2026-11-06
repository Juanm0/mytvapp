package com.quilmes.xuper.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Canales principales y secundarios de un grupo de la pestaña Argentina ("popular" o "sports").
 * Los canales se identifican por la URL de su stream (el id cambia si cambia el orden de las listas).
 *
 * - [principals]: URLs de los canales principales, en el orden en que se muestran.
 * - [secondaries]: para cada principal (clave = su URL), las URLs de sus secundarios en orden.
 */
data class SavedChannelGroup(
    val principals: List<String>,
    val secondaries: Map<String, List<String>>,
) {
    /** Agrega [url] al final de los principales (si ya está, no cambia) y lo saca de cualquier lista de secundarios. */
    fun withPrincipal(url: String): SavedChannelGroup {
        val cleaned = secondaries.mapValues { (_, list) -> list.filter { it != url } }.filterValues { it.isNotEmpty() }
        val newPrincipals = if (url in principals) principals else principals + url
        return SavedChannelGroup(newPrincipals, cleaned)
    }

    /**
     * Deja [url] como secundario de [principalUrl]. Si [url] era principal, deja de serlo y sus propios
     * secundarios pasan al nuevo principal.
     */
    fun withSecondary(principalUrl: String, url: String): SavedChannelGroup {
        if (principalUrl == url) return this
        val carried = secondaries[url].orEmpty()
        val cleaned = secondaries.filterKeys { it != url }
            .mapValues { (_, list) -> list.filter { it != url } }
        val merged = (cleaned[principalUrl].orEmpty() + url + carried).distinct().filter { it != principalUrl }
        val result = LinkedHashMap<String, List<String>>()
        result.putAll(cleaned.filterValues { it.isNotEmpty() })
        result[principalUrl] = merged
        return SavedChannelGroup(principals.filter { it != url }, result)
    }

    /** Saca [url] de los principales (con sus secundarios). */
    fun withoutPrincipal(url: String): SavedChannelGroup =
        SavedChannelGroup(principals.filter { it != url }, secondaries.filterKeys { it != url })

    /** Saca [url] de los secundarios de cualquier principal. */
    fun withoutSecondary(url: String): SavedChannelGroup =
        SavedChannelGroup(
            principals,
            secondaries.mapValues { (_, list) -> list.filter { it != url } }.filterValues { it.isNotEmpty() },
        )

    /** Mueve el principal [url] una posición ([delta] = -1 izquierda, +1 derecha). */
    fun movedPrincipal(url: String, delta: Int): SavedChannelGroup {
        val index = principals.indexOf(url)
        val target = index + delta
        if (index < 0 || target < 0 || target >= principals.size) return this
        val list = principals.toMutableList()
        list.removeAt(index)
        list.add(target, url)
        return SavedChannelGroup(list, secondaries)
    }
}

/** Guarda en el teléfono/tele la personalización de principales y secundarios (SharedPreferences, JSON). */
class ChannelLayoutStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("xuper-channel-layout", Context.MODE_PRIVATE)

    /** null = la persona todavía no personalizó este grupo (se usan los principales por defecto). */
    fun load(groupId: String): SavedChannelGroup? {
        val raw = prefs.getString(groupId, null) ?: return null
        return runCatching {
            val root = JSONObject(raw)
            val principalsJson = root.optJSONArray("principals") ?: JSONArray()
            val principals = (0 until principalsJson.length()).map { principalsJson.getString(it) }
            val secondariesJson = root.optJSONObject("secondaries") ?: JSONObject()
            val secondaries = LinkedHashMap<String, List<String>>()
            val keys = secondariesJson.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val array = secondariesJson.optJSONArray(key) ?: continue
                secondaries[key] = (0 until array.length()).map { array.getString(it) }
            }
            SavedChannelGroup(principals, secondaries)
        }.getOrNull()
    }

    fun save(groupId: String, group: SavedChannelGroup) {
        val root = JSONObject()
        root.put("principals", JSONArray(group.principals))
        val secondaries = JSONObject()
        group.secondaries.forEach { (key, list) -> secondaries.put(key, JSONArray(list)) }
        root.put("secondaries", secondaries)
        prefs.edit().putString(groupId, root.toString()).apply()
    }

    /** Vuelve a los principales por defecto de este grupo. */
    fun reset(groupId: String) {
        prefs.edit().remove(groupId).apply()
    }
}
