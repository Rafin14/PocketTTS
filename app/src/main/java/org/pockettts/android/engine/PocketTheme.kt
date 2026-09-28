package org.pockettts.android.engine

import android.app.Activity
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

// Shared by every screen, sheet and dialog. Text stays opaque; only surfaces blend.
internal data class GlassPalette(val card: Color, val sheet: Color, val border: Color, val success: Color)
private val DarkColors = darkColorScheme(
    primary = Color(0xFFBEC2F3), onPrimary = Color(0xFF25294D),
    primaryContainer = Color(0xFF353957), onPrimaryContainer = Color(0xFFE2E3FF),
    secondary = Color(0xFFC4C5D5), onSecondary = Color(0xFF2D303D),
    secondaryContainer = Color(0xFF333640), onSecondaryContainer = Color(0xFFE2E3EC),
    background = Color(0xFF111216), onBackground = Color(0xFFF0F0F6),
    surface = Color(0xFF202127), onSurface = Color(0xFFF0F0F6),
    surfaceVariant = Color(0xFF30323B), onSurfaceVariant = Color(0xFFC1C2CE),
    surfaceContainer = Color(0xFF22232A), surfaceContainerLow = Color(0xFF1C1D23),
    surfaceContainerHigh = Color(0xFF2C2E37), surfaceContainerHighest = Color(0xFF343640),
    outline = Color(0xFF8F909F), outlineVariant = Color(0xFF42444F),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF532D30), onErrorContainer = Color(0xFFFFDAD6)
)
private val LightColors = lightColorScheme(
    primary = Color(0xFF515984), onPrimary = Color.White,
    primaryContainer = Color(0xFFE0E3FF), onPrimaryContainer = Color(0xFF191F44),
    secondary = Color(0xFF585D71), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE2E4F0), onSecondaryContainer = Color(0xFF1C2030),
    background = Color(0xFFF5F5FA), onBackground = Color(0xFF1B1C24),
    surface = Color(0xFFFAFAFF), onSurface = Color(0xFF1B1C24),
    surfaceVariant = Color(0xFFE5E5EF), onSurfaceVariant = Color(0xFF494B59),
    surfaceContainer = Color(0xFFEEEEF6), surfaceContainerLow = Color(0xFFF2F2FA),
    surfaceContainerHigh = Color(0xFFE8E8F2), surfaceContainerHighest = Color(0xFFE2E2EE),
    outline = Color(0xFF737584), outlineVariant = Color(0xFFCDCEDA),
    error = Color(0xFFBA1A1A), onError = Color.White,
    errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002)
)
private val DarkGlass = GlassPalette(Color(0xEB26272F), Color(0xFA22232A), Color(0xFF3E414C), Color(0xFF67E5A1))
private val LightGlass = GlassPalette(Color(0xFFFEFDFF), Color(0xFFFAFAFF), Color(0xFFD5D6E1), Color(0xFF126638))
internal val LocalGlass = staticCompositionLocalOf { DarkGlass }
internal fun pocketColors(mode: String, systemDark: Boolean) = when {
    mode == "amoled" -> DarkColors.copy(background = Color.Black)
    mode == "dark" || (mode == "system" && systemDark) -> DarkColors
    else -> LightColors
}
internal fun pocketGlass(dark: Boolean) = if (dark) DarkGlass else LightGlass

@Composable
internal fun PocketTheme(mode: String = "system", content: @Composable () -> Unit) {
    val dark = mode == "dark" || mode == "amoled" || (mode == "system" && isSystemInDarkTheme())
    val colors = pocketColors(mode, isSystemInDarkTheme())
    val view = LocalView.current
    if (!view.isInEditMode) SideEffect {
        val window = (view.context as? Activity)?.window
        if (window != null) {
            // Compose has installed the decor; do not request a controller before setContent.
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark
            }
            @Suppress("DEPRECATION")
            window.statusBarColor = AndroidColor.TRANSPARENT
            @Suppress("DEPRECATION")
            window.navigationBarColor = AndroidColor.TRANSPARENT
        }
    }
    CompositionLocalProvider(LocalGlass provides pocketGlass(dark), LocalContentColor provides colors.onSurface) {
        MaterialTheme(colorScheme = colors, shapes = Shapes(
            small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(20.dp),
            large = RoundedCornerShape(28.dp), extraLarge = RoundedCornerShape(32.dp)
        ), content = content)
    }
}

@Composable
internal fun GlassCard(modifier: Modifier = Modifier, selected: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    // Intentional no-blur fallback: no offscreen captures, text blur, or per-frame GPU effects.
    Surface(modifier, shape = MaterialTheme.shapes.large,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else LocalGlass.current.card,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else LocalGlass.current.border)) {
        Column(Modifier.padding(20.dp), content = content)
    }
}
