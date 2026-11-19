package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

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
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> MobHarnDarkColorScheme
        else -> MobHarnLightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = MobHarnShapes,
        content = content
    )
}
