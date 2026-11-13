package com.quilmes.xuper.data

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform

/** One process-wide, thread-safe Python runtime shared by all yt-dlp resolvers. */
object ChaquopyRuntime {
    private val startLock = Any()

    @Volatile
    private var started = false

    fun ensureStarted(context: Context) {
        if (started || Python.isStarted()) {
            started = true
            return
        }
        synchronized(startLock) {
            if (!started && !Python.isStarted()) {
                Python.start(AndroidPlatform(context.applicationContext))
            }
            started = true
        }
    }
}
