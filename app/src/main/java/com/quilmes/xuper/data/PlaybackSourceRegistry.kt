package com.quilmes.xuper.data

import android.content.Context

/** Persiste la última fuente que ExoPlayer llegó a preparar para cada película, serie o episodio. */
object PlaybackSourceRegistry {
    private const val PREFS = "xuper-playback-sources"
    private const val PREFIX = "preferred:"

    fun getPreferredUrl(context: Context, contentKey: String): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(PREFIX + contentKey, null)
            ?.takeIf { it.isNotBlank() }

    fun savePreferredUrl(context: Context, contentKey: String, streamUrl: String) {
        if (contentKey.isBlank() || streamUrl.isBlank()) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(PREFIX + contentKey, streamUrl)
            .apply()
    }
}
