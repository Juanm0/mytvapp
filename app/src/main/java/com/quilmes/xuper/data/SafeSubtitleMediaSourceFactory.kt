@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.quilmes.xuper.data

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.SingleSampleMediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

/**
 * Igual que [DefaultMediaSourceFactory], pero con los subtítulos externos (.srt/.vtt) cargados de forma segura.
 *
 * Problema que resuelve: Media3 trata cada subtítulo externo como una fuente más dentro de un `MergingMediaSource`.
 * Mientras un subtítulo se está descargando su "posición buffereada" es 0, así que TODO el reproductor queda en
 * "cargando" (pantalla negra) hasta que termina, y si falla (servidor caído, enlace vencido, 403) después de los
 * reintentos de fábrica la reproducción entera se corta con error. Con 5 o 6 pistas de CineSrc más las de
 * alcanza con que una sola tarde o falle para que la película se trabe.
 *
 * Acá cada subtítulo se carga con [SingleSampleMediaSource.Factory.setTreatLoadErrorsAsEndOfStream]: si no se puede
 * bajar, esa pista simplemente queda vacía y el video sigue; y se reintenta una sola vez (rápido) en vez de varias
 * veces con esperas crecientes. El video/audio sigue usando la política normal de [DefaultMediaSourceFactory].
 */
class SafeSubtitleMediaSourceFactory(
    private val dataSourceFactory: DataSource.Factory,
    private val delegate: DefaultMediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory),
) : MediaSource.Factory {

    /** Un reintento y listo: un subtítulo que no responde no puede demorar la película. */
    private val subtitlePolicy: LoadErrorHandlingPolicy = object : DefaultLoadErrorHandlingPolicy() {
        override fun getMinimumLoadableRetryCount(dataType: Int): Int = 1
        override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long =
            if (loadErrorInfo.errorCount <= 1) 600L else C.TIME_UNSET
    }

    override fun setDrmSessionManagerProvider(drmSessionManagerProvider: DrmSessionManagerProvider): MediaSource.Factory {
        delegate.setDrmSessionManagerProvider(drmSessionManagerProvider)
        return this
    }

    /** La política recibida vale para el video y el audio; los subtítulos usan la suya. */
    override fun setLoadErrorHandlingPolicy(loadErrorHandlingPolicy: LoadErrorHandlingPolicy): MediaSource.Factory {
        delegate.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
        return this
    }

    override fun getSupportedTypes(): IntArray = delegate.supportedTypes

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val subtitles = mediaItem.localConfiguration?.subtitleConfigurations.orEmpty()
        if (subtitles.isEmpty()) return delegate.createMediaSource(mediaItem)

        // El video/audio se arma sin subtítulos; cada subtítulo se agrega aparte con su propia política.
        val bare = mediaItem.buildUpon().setSubtitleConfigurations(emptyList()).build()
        val sources = ArrayList<MediaSource>(subtitles.size + 1)
        sources += delegate.createMediaSource(bare)
        for (subtitle in subtitles) {
            sources += SingleSampleMediaSource.Factory(dataSourceFactory)
                .setLoadErrorHandlingPolicy(subtitlePolicy)
                .setTreatLoadErrorsAsEndOfStream(true)
                .createMediaSource(subtitle, C.TIME_UNSET)
        }
        return MergingMediaSource(*sources.toTypedArray())
    }
}
