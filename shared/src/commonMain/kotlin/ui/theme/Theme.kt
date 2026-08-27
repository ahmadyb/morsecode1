package net.morsecode.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/**
 * Material 3 theme, implemented once and shared by Android + Desktop
 * (Section A). Light/dark/system switching is driven by [darkTheme], which the
 * app's ThemeSettings state holder supplies.
 */
private val LightColors = lightColorScheme(
    primary = BrandPrimary,
    onPrimary = BrandOnPrimary,
    primaryContainer = BrandPrimaryContainer,
    onPrimaryContainer = BrandOnPrimaryContainer,
    secondary = BrandSecondary,
    onSecondary = BrandOnSecondary,
    secondaryContainer = BrandSecondaryContainer,
    onSecondaryContainer = BrandOnSecondaryContainer,
    surface = SurfaceLight,
    onSurface = OnSurfaceLight,
    error = Error,
    onError = OnError,
)

private val DarkColors = darkColorScheme(
    primary = BrandPrimaryContainer,
    onPrimary = BrandOnPrimaryContainer,
    primaryContainer = BrandPrimary,
    onPrimaryContainer = BrandOnPrimaryContainer,
    secondary = BrandSecondaryContainer,
    onSecondary = BrandOnSecondaryContainer,
    surface = SurfaceDark,
    onSurface = OnSurfaceDark,
    error = Error,
    onError = OnError,
)

@Composable
fun MorseCodeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = Typography(),
        content = content,
    )
}
