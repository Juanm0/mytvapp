package com.quilmes.xuper.ui.components

import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import coil.compose.AsyncImage
import com.quilmes.xuper.data.Title
import com.quilmes.xuper.ui.theme.XuperAccentBright
import com.quilmes.xuper.ui.theme.XuperSurfaceVariant

/**
 * Póster individual de una fila. Se agranda levemente y muestra un borde
 * celeste cuando recibe foco con el control remoto — el equivalente al
 * "hover" de Netflix pero pensado para D-pad en vez de mouse.
 */
@Composable
fun PosterCard(
    title: Title,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.08f else 1f, label = "posterScale")

    Card(
        onClick = onClick,
        modifier = modifier
            .width(140.dp)
            .height(210.dp)
            .scale(scale)
            .onFocusChanged { focused = it.isFocused },
        border = CardDefaults.border(
            focusedBorder = Border(
                border = androidx.compose.foundation.BorderStroke(3.dp, XuperAccentBright),
                shape = RoundedCornerShape(8.dp),
            )
        ),
        shape = CardDefaults.shape(shape = RoundedCornerShape(8.dp)),
    ) {
        if (title.isLive) {
            // Canal en vivo: logo centrado (sin recortar) sobre fondo oscuro + nombre abajo
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(XuperSurfaceVariant)
                    .padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    if (title.posterUrl.isNotBlank()) {
                        AsyncImage(
                            model = title.posterUrl,
                            contentDescription = title.name,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                androidx.tv.material3.Text(
                    text = title.name,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    style = androidx.tv.material3.MaterialTheme.typography.labelSmall,
                )
            }
        } else {
            Box(modifier = Modifier.clip(RoundedCornerShape(8.dp))) {
                AsyncImage(
                    model = title.posterUrl,
                    contentDescription = title.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .width(140.dp)
                        .height(210.dp),
                )
            }
        }
    }
}
