package com.quilmes.xuper.data

import android.content.Context

/**
 * Canales relacionados del que se está viendo, según lo que la persona armó en la pestaña ARGENTINA
 * (principales y secundarios guardados en [ChannelLayoutStore]).
 *
 * Si el canal es un principal con secundarios, o es uno de esos secundarios, devuelve el grupo completo:
 * el principal primero y después sus secundarios, en orden. Si no tiene ninguno, lista vacía.
 */
object ChannelRelations {
    private val GROUP_IDS = listOf("popular", "sports")

    fun groupFor(context: Context, sections: List<Section>, streamUrl: String): List<Title> {
        if (streamUrl.isBlank()) return emptyList()
        val store = ChannelLayoutStore(context)
        var principalUrl: String? = null
        var secondaryUrls: List<String> = emptyList()
        for (groupId in GROUP_IDS) {
            val saved = store.load(groupId) ?: continue
            val hit = saved.secondaries.entries.firstOrNull { entry -> entry.key == streamUrl || streamUrl in entry.value } ?: continue
            principalUrl = hit.key
            secondaryUrls = hit.value
            break
        }
        val principal = principalUrl ?: return emptyList()
        val wanted = (listOf(principal) + secondaryUrls).distinct()
        val wantedSet = wanted.toSet()
        val lookup = HashMap<String, Title>()
        for (section in sections) {
            for (category in section.categories) {
                for (title in category.items) {
                    if (title.isLive && title.streamUrl in wantedSet) lookup.putIfAbsent(title.streamUrl, title)
                }
            }
        }
        return wanted.mapNotNull { lookup[it] }
    }
}
