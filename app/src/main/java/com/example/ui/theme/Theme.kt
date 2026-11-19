package com.example.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import kotlin.math.abs

private val MobHarnDarkColorScheme = darkColorScheme(
    primary = SagePrimaryDark,
    onPrimary = SageOnPrimaryDark,
    primaryContainer = SagePrimaryContainerDark,
    onPrimaryContainer = SageOnPrimaryContainerDark,
    secondary = SlateSecondaryDark,
    onSecondary = SlateOnSecondaryDark,
    secondaryContainer = SlateSecondaryContainerDark,
    onSecondaryContainer = SlateOnSecondaryContainerDark,
    tertiary = AmberTertiaryDark,
    onTertiary = AmberOnTertiaryDark,
    tertiaryContainer = AmberTertiaryContainerDark,
    onTertiaryContainer = AmberOnTertiaryContainerDark,
    background = ObsidianBackground,
    onBackground = ObsidianOnSurface,
    surface = ObsidianSurface,
    onSurface = ObsidianOnSurface,
    surfaceVariant = ObsidianSurfaceContainerHigh,
    onSurfaceVariant = ObsidianOnSurfaceVariant,
    surfaceContainerLowest = ObsidianSurfaceContainerLowest,
    surfaceContainerLow = ObsidianSurfaceContainerLow,
    surfaceContainer = ObsidianSurfaceContainer,
    surfaceContainerHigh = ObsidianSurfaceContainerHigh,
    surfaceContainerHighest = ObsidianSurfaceContainerHighest,
    outline = ObsidianOutline,
    outlineVariant = ObsidianOutlineVariant,
    error = StatusError
)

private val MobHarnLightColorScheme = lightColorScheme(
    primary = SagePrimaryLight,
    onPrimary = SageOnPrimaryLight,
    primaryContainer = SagePrimaryContainerLight,
    onPrimaryContainer = SageOnPrimaryContainerLight,
    secondary = SlateSecondaryLight,
    onSecondary = SlateOnSecondaryLight,
    secondaryContainer = SlateSecondaryContainerLight,
    onSecondaryContainer = SlateOnSecondaryContainerLight,
    tertiary = AmberTertiaryLight,
    onTertiary = AmberOnTertiaryLight,
    tertiaryContainer = AmberTertiaryContainerLight,
    onTertiaryContainer = AmberOnTertiaryContainerLight,
    background = AlabasterBackground,
    onBackground = AlabasterOnSurface,
    surface = AlabasterSurface,
    onSurface = AlabasterOnSurface,
    surfaceVariant = AlabasterSurfaceContainerHigh,
    onSurfaceVariant = AlabasterOnSurfaceVariant,
    surfaceContainerLowest = AlabasterSurfaceContainerLowest,
    surfaceContainerLow = AlabasterSurfaceContainerLow,
    surfaceContainer = AlabasterSurfaceContainer,
    surfaceContainerHigh = AlabasterSurfaceContainerHigh,
    surfaceContainerHighest = AlabasterSurfaceContainerHighest,
    outline = AlabasterOutline,
    outlineVariant = AlabasterOutlineVariant
)

val MobHarnShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(30.dp)
)

enum class ThemeMode {
    SYSTEM, DARK, LIGHT
}

/**
 * Guards against Material 3 1.3.x + Android 16 (API 36) dynamic color polarity bugs
 * where `background` can invert to pure white in dark mode or pure black in light mode
 * while `surfaceContainer*` and `onBackground` stay on the intended polarity.
 */
private fun sanitizeDynamicColorScheme(raw: ColorScheme, darkTheme: Boolean): ColorScheme {
    val fallback = if (darkTheme) MobHarnDarkColorScheme else MobHarnLightColorScheme

    val surfaceOk = if (darkTheme) raw.surface.luminance() < 0.28f else raw.surface.luminance() > 0.65f
    val containerLowOk = if (darkTheme) raw.surfaceContainerLow.luminance() < 0.30f else raw.surfaceContainerLow.luminance() > 0.62f
    val onSurfaceOk = if (darkTheme) raw.onSurface.luminance() > 0.55f else raw.onSurface.luminance() < 0.35f
    val onSurfaceVariantOk = if (darkTheme) raw.onSurfaceVariant.luminance() > 0.40f else raw.onSurfaceVariant.luminance() < 0.45f

    val safeSurface = if (surfaceOk) raw.surface else fallback.surface
    val safeOnSurface = if (onSurfaceOk) raw.onSurface else fallback.onSurface
    val safeOnSurfaceVariant = if (onSurfaceVariantOk) raw.onSurfaceVariant else fallback.onSurfaceVariant

    val primaryContrastOk = abs(raw.primaryContainer.luminance() - raw.onPrimaryContainer.luminance()) >= 0.25f

    return raw.copy(
        // Always unify background with verified surface polarity so text is guaranteed crisp
        background = safeSurface,
        onBackground = safeOnSurface,
        surface = safeSurface,
        onSurface = safeOnSurface,
        onSurfaceVariant = safeOnSurfaceVariant,
        surfaceContainerLowest = if (containerLowOk) raw.surfaceContainerLowest else fallback.surfaceContainerLowest,
        surfaceContainerLow = if (containerLowOk) raw.surfaceContainerLow else fallback.surfaceContainerLow,
        surfaceContainer = if (containerLowOk) raw.surfaceContainer else fallback.surfaceContainer,
        surfaceContainerHigh = if (containerLowOk) raw.surfaceContainerHigh else fallback.surfaceContainerHigh,
        surfaceContainerHighest = if (containerLowOk) raw.surfaceContainerHighest else fallback.surfaceContainerHighest,
        primaryContainer = if (primaryContrastOk) raw.primaryContainer else fallback.primaryContainer,
        onPrimaryContainer = if (primaryContrastOk) raw.onPrimaryContainer else fallback.onPrimaryContainer
    )
}

@Composable
fun MobHarnTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }

    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            val raw = if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            sanitizeDynamicColorScheme(raw, darkTheme)
        }
        darkTheme -> MobHarnDarkColorScheme
        else -> MobHarnLightColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = !darkTheme
                controller.isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = MobHarnShapes,
        content = content
    )
}
