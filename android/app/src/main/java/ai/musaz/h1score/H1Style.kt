package ai.musaz.h1score

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** Shared with the existing GitHub home/result screens. */
internal object H1Style {
    val Ink=Color(0xFF152D4C)
    val Muted=Color(0xFF73818F)
    val Paper=Color(0xFFF5F6F8)
    val Line=Color(0xFFE2E7EB)
    val Mint=Color(0xFF74DFB2)
    val Green=Color(0xFF176B54)
    val PaleMint=Color(0xFFEAF7F0)
    val Gold=Color(0xFFBC8624)
    val heroBackground get()=Brush.horizontalGradient(listOf(Color(0xFFE9EFE5),Color(0xFFE7EFF2)))
}
