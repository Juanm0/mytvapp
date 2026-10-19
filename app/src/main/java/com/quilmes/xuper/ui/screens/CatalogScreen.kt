package com.quilmes.xuper.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.quilmes.xuper.data.ContentRepository
import com.quilmes.xuper.ui.components.ContentRow
import com.quilmes.xuper.ui.components.SkeletonRows
import com.quilmes.xuper.ui.theme.XuperTextPrimary

/** Todas las filas de una lista (un ítem de la barra lateral). */
@Composable
fun CatalogScreen(
    repository: ContentRepository,
    sectionId: String,
    onPlay: (String) -> Unit,
) {
    val sections by repository.sections.collectAsState()
    val section = sections.firstOrNull { it.id == sectionId }

    if (section == null) {
        SkeletonRows()
    } else {
        LazyColumn(contentPadding = PaddingValues(top = 28.dp, bottom = 48.dp)) {
            item {
                Text(
                    text = section.name,
                    color = XuperTextPrimary,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 48.dp, bottom = 8.dp),
                )
            }
            items(section.categories, key = { it.id }) { category ->
                ContentRow(
                    category = category,
                    onItemClick = onPlay,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }
}
