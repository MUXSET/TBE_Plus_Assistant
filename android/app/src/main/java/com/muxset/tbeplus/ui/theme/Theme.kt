package com.muxset.tbeplus.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.muxset.tbeplus.data.ThemeMode

data class TbePalette(
    val isDark: Boolean,
    val bgCanvas: Color,
    val bgCard: Color,
    val bgInset: Color,
    val border: Color,
    val borderSubtle: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val accent: Color,
    val accentSoft: Color,
    val accentText: Color,
    val emerald: Color,
    val emeraldSoft: Color,
    val emeraldText: Color,
    val amber: Color,
    val amberSoft: Color,
    val amberText: Color,
    val rose: Color,
    val roseSoft: Color,
    val roseText: Color,
    val blue: Color,
    val blueSoft: Color,
    val purple: Color,
    val purpleSoft: Color,
)

private val DarkPalette = TbePalette(
    isDark = true,
    bgCanvas = Color(0xFF0C0E12),
    bgCard = Color(0xFF161922),
    bgInset = Color(0xFF0E1117),
    border = Color(0x14FFFFFF),
    borderSubtle = Color(0x0DFFFFFF),
    textPrimary = Color(0xFFF3F4F6),
    textSecondary = Color(0xFF9CA3AF),
    textMuted = Color(0xFF6B7280),
    accent = Color(0xFF6366F1),
    accentSoft = Color(0x266366F1),
    accentText = Color(0xFFA5B4FC),
    emerald = Color(0xFF10B981),
    emeraldSoft = Color(0x2410B981),
    emeraldText = Color(0xFF34D399),
    amber = Color(0xFFF59E0B),
    amberSoft = Color(0x26F59E0B),
    amberText = Color(0xFFFBBF24),
    rose = Color(0xFFEF4444),
    roseSoft = Color(0x26EF4444),
    roseText = Color(0xFFF87171),
    blue = Color(0xFF60A5FA),
    blueSoft = Color(0x263B82F6),
    purple = Color(0xFFA78BFA),
    purpleSoft = Color(0x268B5CF6),
)

private val LightPalette = TbePalette(
    isDark = false,
    bgCanvas = Color(0xFFF5F7FA),
    bgCard = Color(0xFFFFFFFF),
    bgInset = Color(0xFFF1F5F9),
    border = Color(0x140F172A),
    borderSubtle = Color(0x0D0F172A),
    textPrimary = Color(0xFF0F172A),
    textSecondary = Color(0xFF475569),
    textMuted = Color(0xFF94A3B8),
    accent = Color(0xFF4F46E5),
    accentSoft = Color(0x1A4F46E5),
    accentText = Color(0xFF4F46E5),
    emerald = Color(0xFF059669),
    emeraldSoft = Color(0x1A059669),
    emeraldText = Color(0xFF059669),
    amber = Color(0xFFD97706),
    amberSoft = Color(0x1CD97706),
    amberText = Color(0xFFD97706),
    rose = Color(0xFFDC2626),
    roseSoft = Color(0x1ADC2626),
    roseText = Color(0xFFDC2626),
    blue = Color(0xFF2563EB),
    blueSoft = Color(0x1A2563EB),
    purple = Color(0xFF7C3AED),
    purpleSoft = Color(0x1A7C3AED),
)

val LocalTbeColors = staticCompositionLocalOf { DarkPalette }

@Composable
fun TbeTheme(
    themeMode: ThemeMode,
    content: @Composable () -> Unit,
) {
    val isDark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    val palette = if (isDark) DarkPalette else LightPalette
    val colorScheme = if (isDark) {
        darkColorScheme(
            primary = palette.accent,
            onPrimary = Color.White,
            secondary = palette.emerald,
            background = palette.bgCanvas,
            surface = palette.bgCard,
            surfaceVariant = palette.bgInset,
            onBackground = palette.textPrimary,
            onSurface = palette.textPrimary,
            onSurfaceVariant = palette.textSecondary,
            outline = palette.border,
            error = palette.rose,
        )
    } else {
        lightColorScheme(
            primary = palette.accent,
            onPrimary = Color.White,
            secondary = palette.emerald,
            background = palette.bgCanvas,
            surface = palette.bgCard,
            surfaceVariant = palette.bgInset,
            onBackground = palette.textPrimary,
            onSurface = palette.textPrimary,
            onSurfaceVariant = palette.textSecondary,
            outline = palette.border,
            error = palette.rose,
        )
    }

    CompositionLocalProvider(LocalTbeColors provides palette) {
        MaterialTheme(
            colorScheme = colorScheme,
            content = content,
        )
    }
}
