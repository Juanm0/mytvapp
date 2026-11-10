package com.quilmes.xuper.data

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import java.io.File

/**
 * Sonido del primer paso del Easter Egg (3 toques en INICIO).
 *
 * El archivo NO está en el proyecto: hay que copiarlo como `app/src/main/assets/sonido.mp3`.
 * Se lee en tiempo de ejecución (no por `R.raw`), así que compila y corre igual sin el archivo: si falta, solo deja
 * una advertencia en Logcat (`adb logcat -s XuperDev`) y el Easter Egg sigue su curso sin sonido.
 */
object DeveloperUnlockSound {
    const val ASSET_NAME = "sonido.mp3"
    private const val TAG = "XuperDev"

    private var player: MediaPlayer? = null

    @Synchronized
    fun play(context: Context) {
        release(null)
        val appContext = context.applicationContext
        val mp = MediaPlayer()
        try {
            setSource(mp, appContext)
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            mp.setOnPreparedListener { it.start() }
            mp.setOnCompletionListener { release(it) }
            mp.setOnErrorListener { failed, what, extra ->
                Log.w(TAG, "Error al reproducir $ASSET_NAME (what=$what, extra=$extra)")
                release(failed)
                true
            }
            player = mp
            mp.prepareAsync()
        } catch (e: Exception) {
            // Lo normal acá: el archivo todavía no se copió a assets.
            Log.w(TAG, "No se pudo reproducir $ASSET_NAME (¿falta app/src/main/assets/$ASSET_NAME?)", e)
            release(mp)
        }
    }

    private fun setSource(mp: MediaPlayer, context: Context) {
        try {
            context.assets.openFd(ASSET_NAME).use { afd ->
                mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            }
        } catch (e: java.io.IOException) {
            // openFd falla si el asset está comprimido en el APK: se copia a caché y se lee de ahí.
            // Si el archivo no existe, assets.open() lanza FileNotFoundException y lo atrapa play().
            val tmp = File(context.cacheDir, ASSET_NAME)
            context.assets.open(ASSET_NAME).use { input -> tmp.outputStream().use { input.copyTo(it) } }
            mp.setDataSource(tmp.absolutePath)
        }
    }

    @Synchronized
    private fun release(mp: MediaPlayer?) {
        val target = mp ?: player
        if (target == null) return
        if (player === target) player = null
        runCatching { target.release() }
    }
}
