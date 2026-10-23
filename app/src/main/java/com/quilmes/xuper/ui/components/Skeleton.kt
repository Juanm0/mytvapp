package com.quilmes.xuper.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.quilmes.xuper.ui.theme.XuperSurface

/**
 * Filas "fantasma" que se ven mientras llegan los datos: la app abre directo
 * con la estructura final en vez de un cartel de "Cargando…".
 */
@Composable
fun SkeletonRows(modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxSize().padding(top = 32.dp)) {
        repeat(3) {
            Box(
                modifier = Modifier
                    .padding(start = 48.dp, bottom = 12.dp)
                    .width(200.dp)
                    .height(20.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(XuperSurface)
            )
            Row(
                modifier = Modifier.padding(start = 48.dp, bottom = 28.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                repeat(7) {
                    Box(
                        modifier = Modifier
                            .width(CardWidth)
                            .height(CardHeight)
                            .clip(RoundedCornerShape(8.dp))
                            .background(XuperSurface)
                    )
                }
            }
        }
    }
}
