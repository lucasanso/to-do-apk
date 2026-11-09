package com.taskflow.presentation.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.taskflow.data.settings.ThemeMode
import com.taskflow.domain.model.Priority

private val Accent = Color(0xFF5B6CF0)

private val Light = lightColorScheme(
    primary = Accent, onPrimary = Color.White,
    primaryContainer = Color(0xFFE6E8FD), onPrimaryContainer = Color(0xFF1E2470),
    secondary = Color(0xFF52525B), onSecondary = Color.White,
    secondaryContainer = Color(0xFFF1F1F3), onSecondaryContainer = Color(0xFF27272A),
    tertiary = Color(0xFF0EA5A4),
    background = Color(0xFFFBFBFC), onBackground = Color(0xFF18181B),
    surface = Color(0xFFFBFBFC), onSurface = Color(0xFF18181B),
    surfaceVariant = Color(0xFFF1F1F3), onSurfaceVariant = Color(0xFF63636B),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF7F7F8),
    surfaceContainer = Color(0xFFF3F3F5), surfaceContainerHigh = Color(0xFFEDEDF0), surfaceContainerHighest = Color(0xFFE7E7EA),
    outline = Color(0xFFD4D4D8), outlineVariant = Color(0xFFE7E7EA),
    error = Color(0xFFDC2626),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF8C98FF), onPrimary = Color(0xFF111433),
    primaryContainer = Color(0xFF2A2F66), onPrimaryContainer = Color(0xFFE0E3FF),
    secondary = Color(0xFFA1A1AA), onSecondary = Color(0xFF18181B),
    secondaryContainer = Color(0xFF27272A), onSecondaryContainer = Color(0xFFE4E4E7),
    tertiary = Color(0xFF2DD4BF),
    background = Color(0xFF0E0E10), onBackground = Color(0xFFEDEDEF),
    surface = Color(0xFF0E0E10), onSurface = Color(0xFFEDEDEF),
    surfaceVariant = Color(0xFF1C1C1F), onSurfaceVariant = Color(0xFFA1A1AA),
    surfaceContainerLowest = Color(0xFF0A0A0B), surfaceContainerLow = Color(0xFF141416),
    surfaceContainer = Color(0xFF18181B), surfaceContainerHigh = Color(0xFF1F1F23), surfaceContainerHighest = Color(0xFF27272A),
    outline = Color(0xFF3F3F46), outlineVariant = Color(0xFF27272A),
    error = Color(0xFFF87171),
)

private val AppTypography = Typography().let { t ->
    t.copy(
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = t.labelLarge.copy(fontWeight = FontWeight.Medium),
    )
}

object TaskFlowColors {
    val critical = Color(0xFFE5484D)
    val high = Color(0xFFF59E0B)
    val medium = Color(0xFF3B82F6)
    val low = Color(0xFF9CA3AF)
    val success = Color(0xFF16A34A)
    fun priority(p: Priority) = when (p) { Priority.LOW -> low; Priority.MEDIUM -> medium; Priority.HIGH -> high; Priority.CRITICAL -> critical }
}

val SectionLabel = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp)

@Composable
fun TaskFlowTheme(mode: ThemeMode = ThemeMode.SYSTEM, dynamic: Boolean = false, content: @Composable () -> Unit) {
    val dark = when (mode) { ThemeMode.SYSTEM -> isSystemInDarkTheme(); ThemeMode.LIGHT -> false; ThemeMode.DARK -> true }
    val ctx = LocalContext.current
    val scheme = when {
        dynamic && Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = scheme, typography = AppTypography, content = content)
}
