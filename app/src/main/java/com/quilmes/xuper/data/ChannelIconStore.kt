package com.quilmes.xuper.data

import android.content.Context

/** Persiste los logos elegidos/copìados manualmente para los canales de Argentina. */
class ChannelIconStore(context: Context) {
    private val prefs = context.getSharedPreferences("channel_icon_overrides", Context.MODE_PRIVATE)

    fun get(url: String): String? = prefs.getString(url, null)

    fun set(url: String, iconUrl: String) {
        prefs.edit().putString(url, iconUrl).apply()
    }

    fun apply(title: Title): Title = get(title.streamUrl)?.let {
        title.copy(posterUrl = it, backdropUrl = it)
    } ?: title
}
