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
    val battery: Int? = null,
    val rangeMeters: Float? = null,
    val doorOpen: Map<String, Boolean?> = emptyMap(),
    val windowOpen: Map<String, Boolean?> = emptyMap(),
    val doorLocked: Map<String, Boolean?> = emptyMap(),
    val chargePortOpen: Boolean? = null,
    val lowBeam: String? = null,
    val highBeam: String? = null,
)

// Presentation-only examples: never fed into the vehicle/scoring/collection state.
internal fun demoProfileEnergy() = ProfileEnergy(
    battery = 78,
    rangeMeters = 320_000f,
    doorOpen = listOf("ROW_1_LEFT", "ROW_1_RIGHT", "ROW_2_LEFT", "ROW_2_RIGHT", "HOOD", "REAR").associateWith { false },
    windowOpen = listOf("ROW_1_LEFT", "ROW_1_RIGHT", "ROW_2_LEFT", "ROW_2_RIGHT").associateWith { false },
    doorLocked = listOf("ROW_1_LEFT", "ROW_1_RIGHT", "ROW_2_LEFT", "ROW_2_RIGHT").associateWith { true },
    chargePortOpen = false,
    lowBeam = "ON",
    highBeam = "OFF",
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
        energy = if (vm.demo) demoProfileEnergy() else ProfileEnergy()
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
                    val source = if (vm.demo) "시연용 예시 차량 정보"
                        else if (Build.HARDWARE in listOf("ranchu", "goldfish")) "시뮬레이터 차량 정보"
                        else "연결된 차량 정보"
                    Text(source, fontSize = 14.sp, color = ProfileText)
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 16.dp), color = H1Style.Line)

            run {
                val live = vm.live
                val belt = if (vm.demo) true else live.belt.takeIf { observedAt - live.beltAt in 0..2500 }
                val odometer = if (vm.demo) 12_345f else live.odometer.takeIf { observedAt - live.odometerAt in 0..5000 }
                val safety = listOf(
                    ProfileMetric("운전석 안전벨트", belt?.let { if (it) "착용" else "미착용" }, belt == false),
                    ProfileMetric("문", openingSummary(energy.doorOpen.filterKeys { it.startsWith("ROW_") }), energy.doorOpen.any { it.key.startsWith("ROW_") && it.value == true }),
                    ProfileMetric("문 잠금", doorLockSummary(energy.doorLocked.filterKeys { it.startsWith("ROW_") })),
                    ProfileMetric("트렁크", openLabel(energy.doorOpen["REAR"]), energy.doorOpen["REAR"] == true),
                    ProfileMetric("보닛", openLabel(energy.doorOpen["HOOD"]), energy.doorOpen["HOOD"] == true),
                    ProfileMetric("창문", openingSummary(energy.windowOpen), energy.windowOpen.values.any { it == true }),
                ).filter { it.value != null }
                val driving = listOf(
                    ProfileMetric("배터리 잔량", energy.battery?.let { "$it%" }),
                    ProfileMetric("예상 주행 가능거리", energy.rangeMeters?.let { "%,.0f km".format(it / 1000f) }),
                    ProfileMetric("누적 주행거리", odometer?.let { "%,.1f km".format(it) }),
                    ProfileMetric("충전구 덮개", openLabel(energy.chargePortOpen), energy.chargePortOpen == true),
                    ProfileMetric("전조등", lightLabel(energy.lowBeam)),
                    ProfileMetric("상향등", lightLabel(energy.highBeam), energy.highBeam == "ON"),
                ).filter { it.value != null }
                Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    if (safety.isNotEmpty()) ProfileMetrics("출발 전 확인", safety, Modifier.weight(1f).fillMaxHeight())
                    if (driving.isNotEmpty()) ProfileMetrics("주행 및 충전", driving, Modifier.weight(1.1f).fillMaxHeight())
                }
                if (safety.isEmpty() && driving.isEmpty()) {
                    Text("차량 정보를 기다리고 있어요", fontSize = 19.sp, color = ProfileText)
                }
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

internal fun openLabel(open: Boolean?): String? = open?.let { if (it) "열림" else "닫힘" }

internal fun lightLabel(state: String?): String? = when (state) {
    "ON" -> "켜짐"
    "OFF" -> "꺼짐"
    "DAYTIME_RUNNING" -> "주간 주행등"
    else -> null
}

internal fun doorLockSummary(states: Map<String, Boolean?>): String? {
    if (states.values.none { it != null }) return null
    val unlocked = states.values.count { it == false }
    val unknown = states.values.any { it == null }
    return when {
        unlocked > 0 && unknown -> "${unlocked}곳 잠금 해제 · 일부 미확인"
        unlocked > 0 -> "${unlocked}곳 잠금 해제"
        unknown -> "일부 미확인"
        else -> "모두 잠김"
    }
}

internal fun openingSummary(states: Map<String, Boolean?>): String? {
    if (states.values.none { it != null }) return null
    val open = states.values.count { it == true }
    val unknown = states.values.count { it == null }
    return when {
        open > 0 && unknown > 0 -> "${open}곳 열림 · 일부 미확인"
        open > 0 -> "${open}곳 열림"
        unknown > 0 -> "일부 미확인"
        else -> "모두 닫힘"
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
            val rowHeight = if (metrics.size >= 4) Modifier.weight(1f) else Modifier
            Row(
                rowHeight.fillMaxWidth().heightIn(min = 60.dp).padding(vertical = 10.dp),
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
