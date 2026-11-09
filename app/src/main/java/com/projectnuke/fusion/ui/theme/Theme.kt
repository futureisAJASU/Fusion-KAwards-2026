package com.projectnuke.fusion.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

data class FusionSemanticColors(
    val background: Color,
    val panel: Color,
    val menu: Color,
    val bubble: Color,
    val divider: Color,
    val onBackground: Color,
    val onSurface: Color,
    val onSurfaceVariant: Color,
    val primary: Color,
    val onPrimary: Color,
    val error: Color,
    val warning: Color,
)

val LocalFusionSemanticColors = staticCompositionLocalOf {
    FusionSemanticColors(
        background = Color.Black,
        panel = Color(0xFF171717),
        menu = Color(0xFF202020),
        bubble = Color(0xFF2A2A2A),
        divider = Color(0xFF2B2B2B),
        onBackground = Color(0xFFF5F5F5),
        onSurface = Color(0xFFF5F5F5),
        onSurfaceVariant = Color(0xFF9E9E9E),
        primary = FusionBlueLight,
        onPrimary = Color.Black,
        error = FusionErrorDark,
        warning = Color(0xFFFFD38A),
    )
}

private val BlackColorScheme = darkColorScheme(
    primary = FusionBlueLight,
    onPrimary = Color.Black,
    primaryContainer = FusionBlueContainerDark,
    onPrimaryContainer = FusionBlueLight,
    secondary = Color(0xFFBBC7D2),
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF26323D),
    onSecondaryContainer = Color(0xFFD7E3EF),
    background = Color.Black,
    onBackground = Color(0xFFE6E1E5),
    surface = Color(0xFF101010),
    onSurface = Color(0xFFE6E1E5),
    surfaceVariant = Color(0xFF222222),
    onSurfaceVariant = Color(0xFFC9C5C9),
    outline = Color(0xFF918F92),
    error = FusionErrorDark,
    onError = Color(0xFF690005),
)

private val DarkColorScheme = darkColorScheme(
    primary = FusionBlueLight,
    onPrimary = Color.Black,
    primaryContainer = FusionBlueContainerDark,
    onPrimaryContainer = FusionBlueLight,
    secondary = Color(0xFFBBC7D2),
    onSecondary = Color(0xFF26323D),
    secondaryContainer = Color(0xFF2A333C),
    onSecondaryContainer = Color(0xFFD7E3EF),
    background = Color(0xFF121416),
    onBackground = Color(0xFFE2E2E5),
    surface = Color(0xFF191C1F),
    onSurface = Color(0xFFE2E2E5),
    surfaceVariant = Color(0xFF2A2E32),
    onSurfaceVariant = Color(0xFFC4C7CA),
    outline = Color(0xFF8E9295),
    error = FusionErrorDark,
    onError = Color(0xFF690005),
)

private val LightColorScheme = lightColorScheme(
    primary = FusionBlue,
    onPrimary = Color.White,
    primaryContainer = FusionBlueContainerLight,
    onPrimaryContainer = Color(0xFF001D36),
    secondary = Color(0xFF53606B),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD7E3EF),
    onSecondaryContainer = Color(0xFF101C26),
    background = Color(0xFFFAFAFA),
    onBackground = Color(0xFF1A1C1E),
    surface = Color.White,
    onSurface = Color(0xFF1A1C1E),
    surfaceVariant = Color(0xFFE2E7EB),
    onSurfaceVariant = Color(0xFF42474C),
    outline = Color(0xFF72777D),
    error = FusionErrorLight,
    onError = Color.White,
)

@Composable
fun FusionTheme(
    themeMode: FusionThemeMode = FusionThemeMode.AUTO,
    content: @Composable () -> Unit,
) {
    val resolvedMode = when (themeMode) {
        FusionThemeMode.AUTO -> {
            val context = LocalContext.current
            resolveThemeMode(context, themeMode)
        }
        else -> themeMode
    }
    val colorScheme = when (resolvedMode) {
        FusionThemeMode.BLACK -> BlackColorScheme
        FusionThemeMode.DARK -> DarkColorScheme
        FusionThemeMode.LIGHT -> LightColorScheme
        FusionThemeMode.AUTO -> LightColorScheme // should be resolved above
    }

    val semanticColors = when (resolvedMode) {
        FusionThemeMode.BLACK -> FusionSemanticColors(
            background = Color.Black,
            panel = Color(0xFF171717),
            menu = Color(0xFF202020),
            bubble = Color(0xFF2A2A2A),
            divider = Color(0xFF2B2B2B),
            onBackground = Color(0xFFF5F5F5),
            onSurface = Color(0xFFF5F5F5),
            onSurfaceVariant = Color(0xFF9E9E9E),
            primary = FusionBlueLight,
            onPrimary = Color.Black,
            error = FusionErrorDark,
            warning = Color(0xFFFFD38A),
        )
        FusionThemeMode.DARK -> FusionSemanticColors(
            background = Color(0xFF121416),
            panel = Color(0xFF191C1F),
            menu = Color(0xFF2A2E32),
            bubble = Color(0xFF30363B),
            divider = Color(0xFF42474C),
            onBackground = Color(0xFFE2E2E5),
            onSurface = Color(0xFFE2E2E5),
            onSurfaceVariant = Color(0xFFC4C7CA),
            primary = FusionBlueLight,
            onPrimary = Color.Black,
            error = FusionErrorDark,
            warning = Color(0xFFFFD38A),
        )
        FusionThemeMode.LIGHT -> FusionSemanticColors(
            background = Color(0xFFFAFAFA),
            panel = Color.White,
            menu = Color(0xFFE2E7EB),
            bubble = Color(0xFFDDEBFA),
            divider = Color(0xFFBFC7CE),
            onBackground = Color(0xFF1A1C1E),
            onSurface = Color(0xFF1A1C1E),
            onSurfaceVariant = Color(0xFF42474C),
            primary = FusionBlue,
            onPrimary = Color.White,
            error = FusionErrorLight,
            warning = Color(0xFF8A5700),
        )
        FusionThemeMode.AUTO -> FusionSemanticColors(
            background = Color(0xFFFAFAFA),
            panel = Color.White,
            menu = Color(0xFFE2E7EB),
            bubble = Color(0xFFDDEBFA),
            divider = Color(0xFFBFC7CE),
            onBackground = Color(0xFF1A1C1E),
            onSurface = Color(0xFF1A1C1E),
            onSurfaceVariant = Color(0xFF42474C),
            primary = FusionBlue,
            onPrimary = Color.White,
            error = FusionErrorLight,
            warning = Color(0xFF8A5700),
        )
    }

    androidx.compose.runtime.CompositionLocalProvider(LocalFusionSemanticColors provides semanticColors) {
        MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
    }
}
