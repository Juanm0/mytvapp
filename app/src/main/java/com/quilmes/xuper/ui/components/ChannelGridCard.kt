package com.quilmes.xuper.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.quilmes.xuper.data.Title
import com.quilmes.xuper.ui.theme.XuperAccent
import com.quilmes.xuper.ui.theme.XuperAccentBright
import com.quilmes.xuper.ui.theme.XuperLogoBackground

val ChannelGridCardHeight = 178.dp

/**
 * Tarjeta de la grilla "Todos los canales": etiqueta de país arriba, logo, nombre y botón "Ver canal".
 * Es UNA sola tarjeta seleccionable (un solo foco con el mando; un toque en el celular).
 */
@Composable
fun ChannelGridCard(
    title: Title,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badge: String = "Argentina",
) {
    Card(
        onClick = onClick,
        modifier = modifier.height(ChannelGridCardHeight),
        scale = CardDefaults.scale(focusedScale = 1.06f),
        border = CardDefaults.border(
            border = Border(BorderStroke(1.dp, Color(0xFF263042)), shape = RoundedCornerShape(12.dp)),
            focusedBorder = Border(BorderStroke(3.dp, XuperAccentBright), shape = RoundedCornerShape(12.dp)),
        ),
        shape = CardDefaults.shape(shape = RoundedCornerShape(12.dp)),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().background(Color(0xFF111418)).padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Color.White)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Text(badge, color = Color(0xFF111418), fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            }
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)).background(XuperLogoBackground).padding(6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (title.posterUrl.isNotBlank()) {
                        AsyncImage(
                            model = title.posterUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Text(title.name.take(2).uppercase(), color = Color(0xFF1B2433), fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Text(
                text = title.name,
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
            )
            Box(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(XuperAccent).padding(vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("Ver canal", color = Color.White, fontSize = 13.sp)
            }
        }
    }
}
