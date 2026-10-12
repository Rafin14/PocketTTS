package org.pockettts.android.engine

import android.app.Activity
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.core.view.WindowCompat
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.background
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween

// Shared by every screen, sheet and dialog. Text stays opaque; only surfaces blend.
internal data class GlassPalette(val card: Color, val sheet: Color, val border: Color, val success: Color)
private val DarkColors = darkColorScheme(
    primary = Color(0xFFB4C4FF), onPrimary = Color(0xFF152554),
    primaryContainer = Color(0xFF293E72), onPrimaryContainer = Color(0xFFE6ECFF),
    secondary = Color(0xFFC4C5D5), onSecondary = Color(0xFF2D303D),
    secondaryContainer = Color(0xFF333640), onSecondaryContainer = Color(0xFFE2E3EC),
    background = Color(0xFF0B0E19), onBackground = Color(0xFFF4F6FF),
    surface = Color(0xFF1B2337), onSurface = Color(0xFFF4F6FF),
    surfaceVariant = Color(0xFF28324A), onSurfaceVariant = Color(0xFFBFCBE3),
    surfaceContainer = Color(0xFF1B2337), surfaceContainerLow = Color(0xFF151D30),
    surfaceContainerHigh = Color(0xFF263149), surfaceContainerHighest = Color(0xFF303D59),
    outline = Color(0xFF9EAFCF), outlineVariant = Color(0xFF40516D),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF532D30), onErrorContainer = Color(0xFFFFDAD6)
)
private val LightColors = lightColorScheme(
    primary = Color(0xFF4056C7), onPrimary = Color.White,
    primaryContainer = Color(0xFFE2E8FF), onPrimaryContainer = Color(0xFF182D67),
    secondary = Color(0xFF585D71), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE2E4F0), onSecondaryContainer = Color(0xFF1C2030),
    background = Color(0xFFF0F3FB), onBackground = Color(0xFF17213A),
    surface = Color(0xFFFCFDFF), onSurface = Color(0xFF17213A),
    surfaceVariant = Color(0xFFE5EAF5), onSurfaceVariant = Color(0xFF4C5973),
    surfaceContainer = Color(0xFFEAF0FC), surfaceContainerLow = Color(0xFFF5F7FD),
    surfaceContainerHigh = Color(0xFFE4EAF7), surfaceContainerHighest = Color(0xFFDBE3F3),
    outline = Color(0xFF697990), outlineVariant = Color(0xFFCBD5E8),
    error = Color(0xFFBA1A1A), onError = Color.White,
    errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002)
)
private val DarkGlass = GlassPalette(Color(0xF51B2337), Color(0xFF182136), Color(0xFF435473), Color(0xFF72E5BC))
private val LightGlass = GlassPalette(Color(0xFDFCFDFF), Color(0xFFFAFCFF), Color(0xFFD0DAED), Color(0xFF116743))
private val StudioTypography = Typography(
    displayLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 48.sp, letterSpacing = (-1.4).sp, lineHeight = 52.sp),
    headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 30.sp, letterSpacing = (-.6).sp, lineHeight = 35.sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, letterSpacing = (-.5).sp, lineHeight = 33.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, letterSpacing = (-.3).sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, letterSpacing = (-.15).sp, lineHeight = 23.sp),
    titleSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 19.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, letterSpacing = .1.sp, lineHeight = 18.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 12.sp, letterSpacing = .25.sp, lineHeight = 17.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = .3.sp, lineHeight = 15.sp)
)
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
        MaterialTheme(colorScheme = colors, typography = StudioTypography, shapes = Shapes(
            extraSmall = RoundedCornerShape(12.dp), small = RoundedCornerShape(12.dp), medium = StudioTokens.ButtonRadius,
            large = StudioTokens.CardRadius, extraLarge = RoundedCornerShape(34.dp)
        )) { StudioPreferences(content) }
    }
}

@Composable
internal fun GlassCard(modifier: Modifier = Modifier, selected: Boolean = false, padding: androidx.compose.ui.unit.Dp = StudioTokens.PageInset,
    spacing: androidx.compose.ui.unit.Dp = StudioTokens.ControlGap, onSelect: (() -> Unit)? = null, enabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit) {
    val base by animateColorAsState(if (selected) MaterialTheme.colorScheme.primaryContainer else LocalGlass.current.card,
        tween(if (LocalStudioMotion.current) StudioTokens.ColorMillis else 0), label = "material-selection")
    val highlight = MaterialTheme.colorScheme.primary.copy(alpha = if (selected) .13f else .035f)
    val selection = if (onSelect == null) Modifier else Modifier.selectable(selected, enabled, Role.RadioButton, onSelect)
    Surface(modifier.then(selection), shape = MaterialTheme.shapes.large,
        color = base, shadowElevation = if (selected) StudioTokens.SelectedDepth else StudioTokens.SurfaceDepth,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(if (selected) 1.5.dp else .75.dp, if (selected) MaterialTheme.colorScheme.primary else LocalGlass.current.border)) {
        Column(Modifier.background(Brush.verticalGradient(listOf(highlight, Color.Transparent))).padding(padding),
            verticalArrangement = Arrangement.spacedBy(spacing), content = content)
    }
}

/** Advanced guidance is available on demand, never in the way of the main action. */
@Composable
internal fun ContextHelp(text: String) {
    var expanded by rememberSaveable(text) { mutableStateOf(false) }
    val label = stringResource(if (expanded) R.string.ui_help_hide else R.string.ui_help_show)
    TextButton(onClick = { expanded = !expanded }, modifier = Modifier.semantics { stateDescription = label }) {
        Text(label)
    }
    if (expanded) Text(text, Modifier.fillMaxWidth().padding(top = 4.dp),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
