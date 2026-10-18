package com.quilmes.xuper.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.quilmes.xuper.data.Category

/** Una fila tipo Netflix: título de categoría arriba + scroll horizontal de pósters. */
@Composable
fun ContentRow(
    category: Category,
    onItemClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = category.title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 48.dp, bottom = 12.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(category.items, key = { it.id }) { title ->
                PosterCard(title = title, onClick = { onItemClick(title.id) })
            }
        }
    }
}
