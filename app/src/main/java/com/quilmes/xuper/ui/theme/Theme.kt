package com.quilmes.xuper.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

// Paleta "Xuper Quilmes TV": fondo azul noche + acento celeste/azul eléctrico
val XuperBackground = Color(0xFF0B0F17)
val XuperSurface = Color(0xFF131A27)
val XuperSurfaceVariant = Color(0xFF1C2635)
val XuperAccent = Color(0xFF1E90C8)
val XuperAccentBright = Color(0xFF4FC3F7)
val XuperTextPrimary = Color(0xFFF2F5F8)
val XuperTextSecondary = Color(0xFFB4C0CE)

// Fondo claro para los logos de canales (muchos logos son oscuros o transparentes)
val XuperLogoBackground = Color(0xFFEEF2F7)

private val XuperColorScheme = darkColorScheme(
    primary = XuperAccent,
    onPrimary = Color.White,
    secondary = XuperAccentBright,
    background = XuperBackground,
    onBackground = XuperTextPrimary,
    surface = XuperSurface,
    surfaceVariant = XuperSurfaceVariant,
    onSurface = XuperTextPrimary,
    onSurfaceVariant = XuperTextSecondary,
    border = XuperAccentBright,
)

@Composable
fun XuperTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = XuperColorScheme) {
        // Sin esto el texto que no está dentro de un Surface/Card puede salir oscuro sobre fondo oscuro
        CompositionLocalProvider(LocalContentColor provides XuperTextPrimary) {
            content()
        }
    }
}
