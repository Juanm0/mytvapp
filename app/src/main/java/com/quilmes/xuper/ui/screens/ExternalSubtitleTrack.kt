package com.quilmes.xuper.ui.screens

/** Pista externa observada en las solicitudes del embed de CineSrc. */
data class ExternalSubtitleTrack(
    val url: String,
    val referer: String,
    val language: String,
    val label: String,
    val mimeType: String,
    val flags: Int = 0,
    val source: String? = null,
) {
    companion object {
        const val FLAG_FORCED = 1
        const val FLAG_HEARING_IMPAIRED = 2
    }
}
