package com.quilmes.xuper.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import coil.compose.AsyncImage
import com.quilmes.xuper.data.Title
import com.quilmes.xuper.ui.theme.XuperAccentBright

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
        Box(modifier = Modifier.clip(RoundedCornerShape(8.dp))) {
            AsyncImage(
                model = title.posterUrl,
                contentDescription = title.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .width(140.dp)
                    .height(210.dp),
            )
            if (title.isLive) {
                LiveBadge(modifier = Modifier)
            }
        }
    }
}

@Composable
private fun LiveBadge(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .padding(6.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(XuperAccentBright)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        androidx.tv.material3.Text(
            text = "EN VIVO",
            color = Color.Black,
            style = androidx.tv.material3.MaterialTheme.typography.labelSmall,
        )
    }
}
