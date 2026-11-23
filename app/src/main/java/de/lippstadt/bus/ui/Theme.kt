package de.lippstadt.bus.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Farbpalette nach Vorlage: dunkles Navy, abgesetzte Karten, orange Akzente, pinke Highlights. */
object AppColors {
    val Background = Color(0xFF1B2130)
    val Card = Color(0xFF262D3D)
    val CardRaised = Color(0xFF2F3749)
    val Outline = Color(0xFF3A4357)
    val Orange = Color(0xFFF7A43B)
    val OrangeDark = Color(0xFFE08A1E)
    val Pink = Color(0xFFE6488E)
    val Violet = Color(0xFF9B6BFF)
    val Text = Color(0xFFEDEFF5)
    val TextMuted = Color(0xFF8C95A8)
    val Error = Color(0xFFFF6B6B)
    val Success = Color(0xFF4CD39B)
}

private val colors = darkColorScheme(
    primary = AppColors.Orange,
    onPrimary = AppColors.Background,
    primaryContainer = AppColors.OrangeDark,
    onPrimaryContainer = AppColors.Background,
    secondary = AppColors.Pink,
    onSecondary = Color.White,
    tertiary = AppColors.Violet,
    background = AppColors.Background,
    onBackground = AppColors.Text,
    surface = AppColors.Card,
    onSurface = AppColors.Text,
    surfaceVariant = AppColors.CardRaised,
    onSurfaceVariant = AppColors.TextMuted,
    outline = AppColors.Outline,
    error = AppColors.Error,
    onError = Color.White
)

private val typography = Typography(
    headlineSmall = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.2.sp),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 15.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    bodySmall = TextStyle(fontSize = 12.sp),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp),
    labelSmall = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = typography, content = content)
}
