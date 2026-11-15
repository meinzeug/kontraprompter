package de.kontrakurs.kontraprompter

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Immutable
internal data class KontraSpacing(
    val extraSmall: Dp = 4.dp,
    val small: Dp = 8.dp,
    val medium: Dp = 16.dp,
    val large: Dp = 24.dp,
    val extraLarge: Dp = 32.dp,
    val touchTarget: Dp = 48.dp,
)
internal val LocalKontraSpacing = staticCompositionLocalOf { KontraSpacing() }

private val fallbackLight = lightColorScheme(
    primary = Color(ThemePalette.LIGHT_PRIMARY), onPrimary = Color(ThemePalette.LIGHT_ON_PRIMARY),
    primaryContainer = Color(ThemePalette.LIGHT_PRIMARY_CONTAINER), onPrimaryContainer = Color(ThemePalette.LIGHT_ON_PRIMARY_CONTAINER),
    secondary = Color(ThemePalette.LIGHT_SECONDARY), onSecondary = Color(ThemePalette.LIGHT_ON_SECONDARY),
    background = Color(ThemePalette.LIGHT_BACKGROUND), onBackground = Color(ThemePalette.LIGHT_ON_SURFACE),
    surface = Color(ThemePalette.LIGHT_SURFACE), onSurface = Color(ThemePalette.LIGHT_ON_SURFACE),
    surfaceVariant = Color(ThemePalette.LIGHT_SURFACE_VARIANT), onSurfaceVariant = Color(ThemePalette.LIGHT_ON_SURFACE_VARIANT),
    error = Color(ThemePalette.LIGHT_ERROR), onError = Color(ThemePalette.LIGHT_ON_ERROR),
)
private val fallbackDark = darkColorScheme(
    primary = Color(ThemePalette.DARK_PRIMARY), onPrimary = Color(ThemePalette.DARK_ON_PRIMARY),
    primaryContainer = Color(ThemePalette.DARK_PRIMARY_CONTAINER), onPrimaryContainer = Color(ThemePalette.DARK_ON_PRIMARY_CONTAINER),
    secondary = Color(ThemePalette.DARK_SECONDARY), onSecondary = Color(ThemePalette.DARK_ON_SECONDARY),
    background = Color(ThemePalette.DARK_BACKGROUND), onBackground = Color(ThemePalette.DARK_ON_SURFACE),
    surface = Color(ThemePalette.DARK_SURFACE), onSurface = Color(ThemePalette.DARK_ON_SURFACE),
    surfaceVariant = Color(ThemePalette.DARK_SURFACE_VARIANT), onSurfaceVariant = Color(ThemePalette.DARK_ON_SURFACE_VARIANT),
    error = Color(ThemePalette.DARK_ERROR), onError = Color(ThemePalette.DARK_ON_ERROR),
)

private val adaptiveTypography = Typography().let { base ->
    base.copy(
        headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-.8).sp, lineHeight = 42.sp),
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-.4).sp, lineHeight = 36.sp),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-.2).sp),
        bodyLarge = base.bodyLarge.copy(lineHeight = 24.sp),
        bodyMedium = base.bodyMedium.copy(lineHeight = 21.sp),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = .1.sp),
    )
}
private val adaptiveShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp), large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(28.dp),
)

/**
 * Opt-in only. Existing screens use fixed light-on-dark colors and must not
 * inherit a light scheme until they have been migrated and contrast-tested.
 */
@Composable
internal fun KontraAdaptiveTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && darkTheme -> dynamicDarkColorScheme(context)
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context)
        darkTheme -> fallbackDark
        else -> fallbackLight
    }
    CompositionLocalProvider(LocalKontraSpacing provides KontraSpacing()) {
        MaterialExpressiveTheme(
            colorScheme = colors,
            motionScheme = MotionScheme.expressive(),
            typography = adaptiveTypography,
            shapes = adaptiveShapes,
            content = content,
        )
    }
}
