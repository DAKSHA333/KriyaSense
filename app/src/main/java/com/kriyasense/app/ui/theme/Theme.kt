package com.kriyasense.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

@Composable
fun KriyaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = VibrantPurple, onPrimary = PrimaryText,
            primaryContainer = PurpleSurface, onPrimaryContainer = PaleLavender,
            secondary = Lavender, onSecondary = AppBackground,
            secondaryContainer = PurpleSurface, onSecondaryContainer = PaleLavender,
            tertiary = PaleLavender, onTertiary = DeepPurple,
            tertiaryContainer = DeepPurple, onTertiaryContainer = PaleLavender,
            background = AppBackground, onBackground = PrimaryText,
            surface = CardSurface, onSurface = PrimaryText,
            surfaceVariant = PurpleSurface, onSurfaceVariant = SecondaryText,
            surfaceTint = VibrantPurple, inverseSurface = PaleLavender,
            inverseOnSurface = AppBackground, inversePrimary = PrimaryPurple,
            surfaceDim = AppBackground, surfaceBright = PurpleSurface,
            surfaceContainerLowest = AppBackground, surfaceContainerLow = ElevatedBackground,
            surfaceContainer = CardSurface, surfaceContainerHigh = PurpleSurface,
            surfaceContainerHighest = DeepPurple,
            outline = MutedText, outlineVariant = PurpleSurface,
            error = TrackingError, onError = AppBackground,
            errorContainer = CardSurface, onErrorContainer = TrackingError,
            scrim = AppBackground
        ),
        typography = KriyaTypography,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(12.dp), small = RoundedCornerShape(16.dp),
            medium = RoundedCornerShape(24.dp), large = RoundedCornerShape(28.dp),
            extraLarge = RoundedCornerShape(32.dp)
        ),
        content = content
    )
}
