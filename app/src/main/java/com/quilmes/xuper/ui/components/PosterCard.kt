package com.quilmes.xuper.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.quilmes.xuper.data.Title
import com.quilmes.xuper.data.ContentKind
import com.quilmes.xuper.ui.theme.XuperAccentBright
import com.quilmes.xuper.ui.theme.XuperLogoBackground
import com.quilmes.xuper.ui.theme.XuperSurfaceVariant

val CardWidth = 168.dp
val CardHeight = 132.dp
private val VODCardHeight = 252.dp

/**
 * Tarjeta de canal: logo sobre fondo claro (así se ve cualquier logo) y el
 * nombre en una franja oscura abajo con texto blanco. Al recibir foco con el
 * control remoto se agranda y muestra borde celeste.
 */
@Composable
fun PosterCard(
    title: Title,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier
            .width(CardWidth)
            .height(if (title.isLive || title.contentKind == ContentKind.LIVE) CardHeight else VODCardHeight),
        scale = CardDefaults.scale(focusedScale = 1.11f),
        border = CardDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(4.dp, XuperAccentBright),
                shape = RoundedCornerShape(8.dp),
            )
        ),
        shape = CardDefaults.shape(shape = RoundedCornerShape(8.dp)),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .weight(if (title.isLive || title.contentKind == ContentKind.LIVE) 1f else 4f)
                    .fillMaxWidth()
                    .background(XuperLogoBackground)
                    .padding(if (title.isLive || title.contentKind == ContentKind.LIVE) 10.dp else 0.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (title.posterUrl.isNotBlank()) {
                    AsyncImage(
                        model = title.posterUrl,
                        contentDescription = null,
                        contentScale = if (title.isLive || title.contentKind == ContentKind.LIVE) ContentScale.Fit else ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text(
                        text = title.name.take(2).uppercase(),
                        color = Color(0xFF1B2433),
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                // Solo marcamos como sin video los elementos no TMDB sin stream asociado.
                val noVideo = !title.id.startsWith("tmdb-") && !title.isLive && title.contentKind != ContentKind.LIVE && title.streamUrl.isBlank()
                if (noVideo) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xCC14202B))
                            .padding(horizontal = 6.dp, vertical = 3.dp)
                    ) {
                        Text(text = "SIN VIDEO", color = Color(0xFFE0A33E), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (title.isLive || title.contentKind == ContentKind.LIVE) 46.dp else 58.dp)
                    .background(XuperSurfaceVariant)
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = title.name,
                    color = Color.White,
                    fontSize = 13.sp,
                    lineHeight = 16.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
