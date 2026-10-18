package com.quilmes.xuper.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

// Paleta "Xuper Quilmes TV": fondo azul noche muy oscuro + acento celeste/azul eléctrico
// (mismo logo de siempre — gato con anteojos — pero sin el rojo típico de Netflix)
val XuperBackground = Color(0xFF0B0F17)
val XuperSurface = Color(0xFF131A27)
val XuperSurfaceVariant = Color(0xFF1C2635)
val XuperAccent = Color(0xFF1E90C8)       // azul principal (el mismo del ícono)
val XuperAccentBright = Color(0xFF4FC3F7) // azul más claro para focus/hover
val XuperTextPrimary = Color(0xFFF2F5F8)
val XuperTextSecondary = Color(0xFFA9B4C2)

private val XuperColorScheme = darkColorScheme(
    primary = XuperAccent,
    onPrimary = Color.White,
    secondary = XuperAccentBright,
    background = XuperBackground,
    surface = XuperSurface,
    surfaceVariant = XuperSurfaceVariant,
    onSurface = XuperTextPrimary,
    onSurfaceVariant = XuperTextSecondary,
    border = XuperAccentBright,
)

@Composable
fun XuperTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = XuperColorScheme,
        content = content
    )
}
