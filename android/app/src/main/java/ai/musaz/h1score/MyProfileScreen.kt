package ai.musaz.h1score

import android.os.Build
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

private val ProfileBlue = Color(0xFFDDEBFA)
private val ProfileText = Color(0xFF526479)
private val ProfileWarning = Color(0xFFA62B25)

internal data class ProfileEnergy(
    val battery: Int?=null, val fuel: Int?=null, val oil: String?=null, val steering: Float?=null,
    val washerFluidLow: Boolean?=null, val evEfficiency: Float?=null, val fuelEconomy: Float?=null,
)

private data class ProfileMetric(val label: String, val value: String?, val needsAttention: Boolean = false)

@Composable
internal fun MyProfileScreen(vm: ChallengeViewModel, enabled: Boolean) {
    var editing by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var energy by remember { mutableStateOf(ProfileEnergy()) }
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(enabled, vm.demo) {
        if (!enabled) editing = false
        energy = ProfileEnergy()
        if (!vm.demo) {
            while (enabled) {
                now = SystemClock.elapsedRealtime()
                energy = vm.connection.readProfileEnergy()
                delay(2000)
            }
        }
    }

    Row(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 8.dp)
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Column(
            Modifier.weight(.34f).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(
                Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(28.dp))
                    .background(H1Style.Ink).padding(horizontal = 20.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                MusazAvatar()
                Spacer(Modifier.height(14.dp))
                Text(
                    vm.nickname, color = Color.White, fontSize = 28.sp, lineHeight = 32.sp,
                    fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { draft = vm.nickname; editing = true },
                    enabled = enabled,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ProfileBlue, contentColor = H1Style.Ink,
                        disabledContainerColor = Color.White.copy(alpha = .14f),
                        disabledContentColor = Color.White.copy(alpha = .6f),
                    ),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Outlined.Edit, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("닉네임 변경", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Surface(
                onClick = { vm.restartTutorial() },
                enabled = enabled && vm.canChooseDestination && vm.trackedSpot == null,
                shape = RoundedCornerShape(18.dp),
                color = Color.White,
                contentColor = H1Style.Ink,
            ) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 62.dp).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(Icons.Outlined.HelpOutline, null, tint = ProfileText)
                    Text("사용법 다시 보기", Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(19.dp), tint = ProfileText)
                }
            }
        }

        val observedAt = maxOf(now, SystemClock.elapsedRealtime())
        Column(
            Modifier.weight(.66f).fillMaxHeight().heightIn(min = 395.dp)
                .clip(RoundedCornerShape(24.dp)).background(Color.White).padding(24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    Modifier.size(46.dp).clip(RoundedCornerShape(14.dp)).background(H1Style.PaleMint),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Outlined.DirectionsCar, null, tint = H1Style.Ink) }
                Column {
                    Text("내 차 상태", fontSize = 23.sp, fontWeight = FontWeight.Bold, color = H1Style.Ink)
                    if (!vm.demo) {
                        val source = if (Build.HARDWARE in listOf("ranchu", "goldfish")) "시뮬레이터 차량 정보"
                            else "연결된 차량 정보"
                        Text(source, fontSize = 14.sp, color = ProfileText)
                    }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 16.dp), color = H1Style.Line)

            if (vm.demo) {
                Text("시연 모드가 켜져 있어요", fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = H1Style.Ink)
                Spacer(Modifier.height(6.dp))
                Text("차량 상태는 Pleos 연결 모드에서 확인할 수 있어요.", fontSize = 15.sp, color = ProfileText)
            } else {
                val live = vm.live
                val belt = live.belt.takeIf { observedAt - live.beltAt in 0..2500 }
                val steering = energy.steering?.let { angle ->
                    // Pleos reports clockwise steering-wheel rotation as negative.
                    val magnitude = "%.1f°".format(abs(angle))
                    when {
                        abs(angle) < 0.05f -> "중앙 0.0°"
                        angle < 0f -> "오른쪽 $magnitude"
                        else -> "왼쪽 $magnitude"
                    }
                }
                val odometer = live.odometer.takeIf { observedAt - live.odometerAt in 0..5000 }
                val oilLevel = when (energy.oil) {
                    "NORMAL" -> "정상"
                    "LOW" -> "낮음"
                    "CRITICALLY_LOW" -> "매우 낮음"
                    "HIGH" -> "높음"
                    else -> null
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    ProfileMetrics(
                        "운전 상태",
                        listOf(
                            ProfileMetric("운전석 안전벨트", belt?.let { if (it) "착용" else "미착용" }, belt == false),
                            ProfileMetric("핸들 각도", steering),
                            ProfileMetric("누적 주행거리", odometer?.let { "%,.1f km".format(it) }),
                        ),
                        Modifier.weight(1f),
                    )
                    ProfileMetrics(
                        "에너지 및 관리",
                        listOf(
                            ProfileMetric("전기차 배터리", energy.battery?.let { "$it%" }),
                            ProfileMetric("연료 잔량", energy.fuel?.let { "$it%" }),
                            ProfileMetric("이번 주행 전비", energy.evEfficiency?.let { "%.1f km/kWh".format(it) }),
                            ProfileMetric("이번 주행 연비", energy.fuelEconomy?.let { "%.1f L/100 km".format(it) }),
                            ProfileMetric("엔진오일 수위", oilLevel, energy.oil in setOf("LOW", "CRITICALLY_LOW", "HIGH")),
                            ProfileMetric("워셔액", energy.washerFluidLow?.let { if (it) "보충 필요" else "경고 없음" }, energy.washerFluidLow == true),
                        ),
                        Modifier.weight(1.1f),
                    )
                }
                Text(
                    "차종과 연결 권한에 따라 표시되는 항목이 달라요.",
                    Modifier.padding(top = 12.dp), fontSize = 13.sp, color = ProfileText,
                )
            }
        }
    }

    if (editing && enabled) {
        val trimmed = draft.trim()
        val valid = trimmed.isNotBlank() && trimmed.codePointCount(0, trimmed.length) <= 16 &&
            trimmed.none { it.isISOControl() }
        AlertDialog(
            onDismissRequest = { editing = false },
            containerColor = Color.White,
            title = { Text("닉네임 변경", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    draft, { draft = it }, Modifier.fillMaxWidth(),
                    singleLine = true, label = { Text("닉네임") },
                    supportingText = { Text("1~16자로 입력해 주세요") },
                    isError = draft.isNotEmpty() && !valid,
                )
            },
            confirmButton = {
                Button(enabled = valid, onClick = { if (vm.updateNickname(draft)) editing = false }) { Text("저장") }
            },
            dismissButton = { TextButton(onClick = { editing = false }) { Text("취소") } },
        )
    }
}

@Composable
private fun MusazAvatar() {
    val mascot = ImageBitmap.imageResource(R.drawable.musaz_challenge_home)
    Canvas(
        Modifier.size(146.dp).clip(CircleShape).background(ProfileBlue)
            .semantics { contentDescription = "무사즈 프로필 이미지" },
    ) {
        // Zoom the existing full-body illustration to frame the helmet and face.
        val width = (size.width * 2.22f).roundToInt().coerceAtLeast(1)
        val height = (width * mascot.height.toFloat() / mascot.width).roundToInt().coerceAtLeast(1)
        drawImage(
            image = mascot,
            dstOffset = IntOffset(((size.width - width) / 2).roundToInt(), (size.height * .09f).roundToInt()),
            dstSize = IntSize(width, height),
            filterQuality = FilterQuality.High,
        )
    }
}

@Composable
private fun ProfileMetrics(title: String, metrics: List<ProfileMetric>, modifier: Modifier) {
    Column(modifier) {
        Text(
            title, Modifier.padding(bottom = 6.dp), fontSize = 16.sp, color = H1Style.Ink,
            fontWeight = FontWeight.Bold,
        )
        metrics.forEachIndexed { index, metric ->
            if (index > 0) HorizontalDivider(color = H1Style.Line)
            Row(
                Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(metric.label, Modifier.weight(1f), fontSize = 16.sp, lineHeight = 21.sp, color = ProfileText)
                Text(
                    metric.value ?: "미확인", fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    color = when {
                        metric.needsAttention -> ProfileWarning
                        metric.value == null -> ProfileText
                        else -> H1Style.Ink
                    },
                )
            }
        }
    }
}
