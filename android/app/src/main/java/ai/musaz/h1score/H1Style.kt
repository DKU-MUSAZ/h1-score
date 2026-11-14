package ai.musaz.h1score

import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** Shared with the existing GitHub home/result screens. */
internal object H1Style {
    val Ink=Color(0xFF152D4C)
    val Muted=Color(0xFF73818F)
    val Paper=Color(0xFFF5F6F8)
    val Line=Color(0xFFE2E7EB)
    // Shared navy palette, including light accents for dark surfaces.
    val Mint=Color(0xFFB9CDE5)
    val Green=Ink
    val PaleMint=Color(0xFFE9EFF7)
    val Gold=Color(0xFFBC8624)
    val heroBackground get()=Brush.horizontalGradient(listOf(Color(0xFFF0F4FA),Color(0xFFF7F9FC)))
    val colorScheme get()=lightColorScheme(
        primary=Ink,onPrimary=Color.White,primaryContainer=PaleMint,onPrimaryContainer=Ink,
        secondary=Ink,onSecondary=Color.White,secondaryContainer=PaleMint,onSecondaryContainer=Ink,
        tertiary=Ink,onTertiary=Color.White,tertiaryContainer=PaleMint,onTertiaryContainer=Ink,
        background=Paper,onBackground=Ink,surface=Color.White,onSurface=Ink,
        // Set every surface role: unspecified Material defaults carry a lavender tint.
        surfaceVariant=Paper,onSurfaceVariant=Muted,surfaceTint=Color.Transparent,
        surfaceBright=Color.White,surfaceDim=Color(0xFFE1E5E9),
        surfaceContainerLowest=Color.White,surfaceContainerLow=Color(0xFFFAFBFC),
        surfaceContainer=Color.White,surfaceContainerHigh=Color.White,
        surfaceContainerHighest=Color(0xFFF0F2F4),
        outline=Muted,outlineVariant=Line,inverseSurface=Ink,inverseOnSurface=Color.White,inversePrimary=Mint,
    )
}
