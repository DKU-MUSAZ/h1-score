package ai.musaz.h1score

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/** Decorative member card; does not imply a verified driving qualification. */
@Composable
internal fun ProfileDriverLicense(
    nickname: String, enabled: Boolean, onEditNickname: () -> Unit, modifier: Modifier = Modifier,
) {
    Box(modifier.heightIn(min = 460.dp).clip(RoundedCornerShape(24.dp))) {
        Canvas(Modifier.matchParentSize()) {
            drawRect(Brush.linearGradient(listOf(Color(0xFF142B46), Color(0xFF29465F))))
            val silver = Path().apply {
                moveTo(0f, size.height * .68f)
                lineTo(size.width, size.height * .40f)
                lineTo(size.width, size.height)
                lineTo(0f, size.height)
                close()
            }
            drawPath(silver, Brush.linearGradient(
                listOf(Color(0xFFD5DCE3), Color(0xFFF6F8FA), Color(0xFFE1E6EC)),
                start = Offset.Zero, end = Offset(size.width, size.height),
            ))
        }
        Column(Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 20.dp)) {
            Box(Modifier.align(Alignment.CenterHorizontally).size(width = 58.dp, height = 6.dp)
                .clip(RoundedCornerShape(3.dp)).background(Color(0xFF0C1B2D)))
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Image(painterResource(R.drawable.app_icon), null, Modifier.size(26.dp))
                Text("안운잘부", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            }
            Text("DRIVER LICENSE", Modifier.padding(top = 7.dp), fontSize = 12.sp,
                letterSpacing = 2.sp, color = Color(0xFFCAD5DF), fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            LicensePortrait(Modifier.fillMaxWidth().padding(top = 16.dp).aspectRatio(1f))
            Spacer(Modifier.height(18.dp))
            Text("무사즈 드라이버", fontSize = 14.sp, color = Color(0xFF526479))
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(nickname, Modifier.weight(1f, fill = false),
                    fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, color = H1Style.Ink)
                IconButton(onClick = onEditNickname, enabled = enabled, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Outlined.Edit, "닉네임 변경", Modifier.size(22.dp),
                        tint = H1Style.Ink.copy(alpha = if (enabled) .5f else .25f))
                }
            }
        }
    }
}

@Composable
private fun LicensePortrait(modifier: Modifier) {
    val mascot = ImageBitmap.imageResource(R.drawable.musaz_profile_license)
    Canvas(modifier.semantics { contentDescription = "무사즈 프로필 이미지" }) {
        // Remove transparent side margins only; retain the complete helmet-to-waist portrait.
        val sourceWidth = (mascot.width * .66f).roundToInt()
        val sourceHeight = mascot.height
        val scale = minOf(size.width / sourceWidth, size.height / sourceHeight) * .90f
        val width = (sourceWidth * scale).roundToInt().coerceAtLeast(1)
        val height = (sourceHeight * scale).roundToInt().coerceAtLeast(1)
        drawImage(mascot,
            srcOffset = IntOffset((mascot.width * .17f).roundToInt(), 0),
            srcSize = IntSize(sourceWidth, sourceHeight),
            dstOffset = IntOffset(((size.width - width) / 2).roundToInt(), ((size.height - height) / 2).roundToInt()),
            dstSize = IntSize(width, height), filterQuality = FilterQuality.High)
    }
}
