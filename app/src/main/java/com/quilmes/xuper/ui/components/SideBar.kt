package com.quilmes.xuper.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Text
import com.quilmes.xuper.R
import com.quilmes.xuper.ui.theme.XuperAccent
import com.quilmes.xuper.ui.theme.XuperSurface
import com.quilmes.xuper.ui.theme.XuperSurfaceVariant
import com.quilmes.xuper.ui.theme.XuperTextPrimary

data class NavItem(val id: String, val icon: String, val label: String)

/**
 * Barra lateral estilo Netflix: angosta (solo íconos) y se expande con el
 * texto cuando recibe foco. Se dibuja encima del contenido, así el contenido
 * no se reacomoda al abrirla.
 *
 * Celular: la lista de ítems se desplaza con el dedo (en pantallas bajas los últimos ítems quedaban
 * fuera de la vista y no se podían tocar), en pantallas bajas los ítems son más compactos, y tocar el
 * logo muestra/oculta los nombres (sin mando no hay foco que los expanda).
 */
@Composable
fun SideBar(
    items: List<NavItem>,
    selectedId: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var focusExpanded by remember { mutableStateOf(false) }
    var touchExpanded by remember { mutableStateOf(false) }
    val expanded = focusExpanded || touchExpanded
    val width by animateDpAsState(if (expanded) 230.dp else 72.dp, label = "sidebarWidth")
    val compact = LocalConfiguration.current.screenHeightDp < 500

    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(width)
            .background(XuperSurface)
            .onFocusChanged { focusExpanded = it.hasFocus }
            .focusGroup()
            .padding(horizontal = 8.dp, vertical = if (compact) 8.dp else 20.dp),
    ) {
        Image(
            painter = painterResource(id = R.drawable.logo_splash),
            contentDescription = "Xuper Quilmes TV",
            modifier = Modifier
                .padding(start = 8.dp, bottom = if (compact) 6.dp else 14.dp)
                .size(if (compact) 30.dp else 40.dp)
                .pointerInput(Unit) { detectTapGestures(onTap = { touchExpanded = !touchExpanded }) },
        )
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 6.dp),
        ) {
            items.forEach { item ->
                NavRow(
                    item = item,
                    selected = item.id == selectedId,
                    expanded = expanded,
                    compact = compact,
                    onClick = {
                        touchExpanded = false
                        onSelect(item.id)
                    },
                )
            }
        }
    }
}

@Composable
private fun NavRow(item: NavItem, selected: Boolean, expanded: Boolean, compact: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) XuperSurfaceVariant else Color.Transparent,
            contentColor = XuperTextPrimary,
            focusedContainerColor = XuperAccent,
            focusedContentColor = Color.White,
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = if (compact) 7.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = item.icon, fontSize = if (compact) 20.sp else 22.sp)
            if (expanded) {
                Spacer(modifier = Modifier.width(14.dp))
                Text(
                    text = item.label,
                    fontSize = 17.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
