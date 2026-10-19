package com.quilmes.xuper.data

import com.google.gson.annotations.SerializedName
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import java.net.URI

/** DTOs compatibles con la respuesta /stream/{type}/{id}.json del protocolo Stremio. */
data class StremioStreamResponse(@SerializedName("streams") val streams: List<StremioStream> = emptyList())

data class StremioStream(
    val name: String? = null,
    val title: String? = null,
    val description: String? = null,
    val url: String? = null,
    val infoHash: String? = null,
    val fileIdx: Int? = null,
    val behaviorHints: StremioBehaviorHints? = null,
) {
    fun displayName(): String = title?.takeIf { it.isNotBlank() }
        ?: name?.takeIf { it.isNotBlank() }
        ?: description?.takeIf { it.isNotBlank() }
        ?: "Fuente directa"
}

data class StremioBehaviorHints(@SerializedName("notWebReady") val notWebReady: Boolean? = null)

internal interface StremioAddonApi {
    @GET("{resourcePath}.json")
    suspend fun getStreams(@Path(value = "resourcePath", encoded = true) resourcePath: String): StremioStreamResponse
}

/** Cliente genérico de addons HTTP de Stremio. Solo entrega URLs directas HTTP(S) a ExoPlayer;
 * infoHash/torrents y enlaces externos no se hacen pasar por URLs reproducibles. */
class StremioRepository {
    suspend fun getDirectStreams(manifestUrl: String, type: String, videoId: String): List<StremioStream> {
        require(type == "movie" || type == "series") { "Tipo Stremio no soportado" }
        require(videoId.matches(Regex("tt\\d{5,12}(?::\\d{1,3}:\\d{1,4})?"))) { "Identificador IMDb inválido" }
        val baseUrl = baseUrlFromManifest(manifestUrl)
        val api = Retrofit.Builder()
            .baseUrl(baseUrl)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(StremioAddonApi::class.java)
        val result = api.getStreams("stream/$type/$videoId")
        return result.streams.filter { stream ->
            val url = stream.url?.trim().orEmpty()
            (url.startsWith("https://", true) || url.startsWith("http://", true)) &&
                stream.infoHash.isNullOrBlank()
        }.distinctBy { it.url }
    }

    private fun baseUrlFromManifest(raw: String): String {
        val cleaned = raw.trim()
        val uri = URI(cleaned)
        require(uri.scheme == "https" || uri.scheme == "http") { "El addon debe usar una URL HTTP(S)" }
        require(uri.host != null) { "URL de manifiesto inválida" }
        val path = uri.path.orEmpty()
        val prefix = when {
            path.endsWith("/manifest.json") -> path.removeSuffix("manifest.json")
            path.endsWith("/manifest.json/") -> path.removeSuffix("manifest.json/")
            path.endsWith("/") -> path
            else -> "$path/"
        }
        return "${uri.scheme}://${uri.rawAuthority}$prefix"
    }
}
