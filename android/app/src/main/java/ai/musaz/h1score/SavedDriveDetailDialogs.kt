package ai.musaz.h1score

import ai.musaz.h1score.core.DrivingEventType
import ai.musaz.h1score.core.TurnVerdict
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.util.Locale
import kotlin.math.roundToInt

internal enum class SavedDriveDetailSection { FEEDBACK, EVENTS, RANKING }

@Composable internal fun SavedDriveDetailDialog(
    drive: CompletedDrive,
    section: SavedDriveDetailSection,
    onDismiss: () -> Unit,
) {
    val frameDensity = LocalDensity.current
    val title = when (section) {
        SavedDriveDetailSection.FEEDBACK -> "주행 피드백 전체보기"
        SavedDriveDetailSection.EVENTS -> "저장된 주행 구간"
        SavedDriveDetailSection.RANKING -> "함께 도착한 무사즈"
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        CompositionLocalProvider(LocalDensity provides frameDensity) {
            Surface(
                Modifier.widthIn(max = 860.dp).fillMaxWidth(.9f).fillMaxHeight(.88f),
                color = Color.White, shape = RoundedCornerShape(24.dp),
            ) {
                Column(Modifier.padding(24.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(title, Modifier.weight(1f).semantics { heading() },
                            fontSize = 24.sp, fontWeight = FontWeight.Bold, color = H1Style.Ink)
                        IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Outlined.Close, "닫기", tint = H1Style.Ink)
                        }
                    }
                    Text(drive.name, fontSize = 14.sp, color = H1Style.Muted)
                    HorizontalDivider(Modifier.padding(vertical = 16.dp), color = H1Style.Line)
                    key(drive.session.id, section) {
                        Column(
                            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            when (section) {
                                SavedDriveDetailSection.FEEDBACK -> SavedFeedbackDetails(drive)
                                SavedDriveDetailSection.EVENTS -> SavedEventDetails(drive)
                                SavedDriveDetailSection.RANKING -> SavedRankingDetails(drive)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun SavedFeedbackDetails(drive: CompletedDrive) {
    val report = drive.report
    val feedback = remember(report) { historyFeedback(report) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        feedback.metrics.forEach { metric ->
            Surface(Modifier.weight(1f), color = H1Style.Paper, shape = RoundedCornerShape(14.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(metric.label, fontSize = 14.sp, color = H1Style.Ink)
                    Text(metric.value, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = H1Style.Ink)
                    Text(metric.detail, fontSize = 12.sp, lineHeight = 18.sp, color = H1Style.Muted)
                }
            }
        }
    }
    SavedDetailPoints("이어갈 습관", feedback.goodPoints, SavedDriveDetailStyle.Good)
    SavedDetailPoints("개선할 점", feedback.improvements, SavedDriveDetailStyle.Improve)
    Surface(color = H1Style.Paper, shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("기록과 점수 안내", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = H1Style.Ink)
            Text("분석 거리 ${String.format(Locale.KOREA, "%.1f", report.validDistanceM / 1000)} km · 데이터 수신율 ${(report.coverage * 100).roundToInt()}%",
                fontSize = 14.sp, color = H1Style.Ink)
            Text(scoreFormula(report),
                fontSize = 14.sp, lineHeight = 22.sp, color = H1Style.Ink)
            if (report.usesDistanceBonus) {
                Text("속도 +${scorePoints(report.speedPoints)}점 · 가감속 +${scorePoints(report.accelerationPoints)}점 · 회전 −${scorePoints(report.turnPenalty)}점",
                    fontSize = 14.sp, lineHeight = 22.sp, color = H1Style.Ink)
                Text("가점은 소수 둘째 자리까지 표시해요. 최종 점수는 원래 가점의 합계를 반올림한 값이에요.",
                    fontSize = 14.sp, lineHeight = 22.sp, color = H1Style.Muted)
                Text("가감속 자료 확보율 ${(report.longitudinalCoverage * 100).roundToInt()}% · 유효 수신 빈도 ${scorePoints(report.longitudinalSampleRateHz)} Hz",
                    fontSize = 14.sp, lineHeight = 22.sp, color = H1Style.Muted)
                if (report.score == null) Text(scorePendingMessage(report),
                    fontSize = 14.sp, lineHeight = 22.sp, color = H1Style.Muted)
                Text("처음 10km까지 준수 거리 1km당 +2.5점, 그중 부드러운 가감속 거리 1km당 +1.5점이에요. 짧은 주행도 실제 거리만큼 가점을 쌓아요.",
                    fontSize = 14.sp, lineHeight = 22.sp, color = H1Style.Muted)
                Text("회전 방향지시등 미사용 −1점 · 반대 방향 −2점 · 주행당 최대 −10점",
                    fontSize = 14.sp, lineHeight = 22.sp, color = H1Style.Muted)
                val judgedTurns = report.turns.count { it.verdict != TurnVerdict.UNVERIFIABLE }
                Text("판정한 회전 ${judgedTurns}건 · 판단 보류 ${report.turns.size - judgedTurns}건",
                    fontSize = 14.sp, color = H1Style.Muted)
                if (report.kilometers.isNotEmpty()) {
                    Text("거리별 가점", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = H1Style.Ink)
                    report.kilometers.forEach { km ->
                        Text("${km.kilometer}번째 km · 분석 ${String.format(Locale.KOREA, "%.0f", km.validDistanceM)} m · 속도 +${scorePoints(km.speedPoints)} · 가감속 +${scorePoints(km.accelerationPoints)}점",
                            fontSize = 14.sp, lineHeight = 22.sp, color = H1Style.Muted)
                    }
                }
            }
            Text(feedback.note, fontSize = 14.sp, lineHeight = 22.sp, color = H1Style.Muted)
        }
    }
}

@Composable private fun SavedDetailPoints(title: String, points: List<String>, accent: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = accent)
        points.forEach { point ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("•", fontSize = 15.sp, lineHeight = 24.sp, color = H1Style.Ink)
                Text(point, Modifier.weight(1f), fontSize = 15.sp, lineHeight = 24.sp, color = H1Style.Ink)
            }
        }
    }
}

@Composable private fun SavedEventDetails(drive: CompletedDrive) {
    val events = drive.report.events
    Text("저장된 구간 ${events.size}건", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = H1Style.Ink)
    Text("시간과 거리는 기록 시작 이후의 경과 시간과 누적 유효 거리예요. 도로명이나 GPS 위치가 아닌 대략적인 구간입니다.",
        fontSize = 14.sp, lineHeight = 22.sp, color = H1Style.Muted)
    if (events.isEmpty()) {
        Text("저장된 주행 구간 정보가 없어요.", fontSize = 16.sp, color = H1Style.Ink)
    }
    events.forEachIndexed { index, event ->
        val accent = event.type.detailColor()
        Column(
            Modifier.fillMaxWidth().background(accent.copy(alpha = .06f), RoundedCornerShape(14.dp)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val label = when (event.type) {
                DrivingEventType.OVERSPEED -> "제한속도 초과"
                DrivingEventType.RAPID_ACCELERATION -> "급가속"
                DrivingEventType.RAPID_DECELERATION -> decelerationEventLabel(drive.report)
            }
            Text("${index + 1}. $label", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = accent)
            Text("${event.elapsedMs / 60000}분 ${(event.elapsedMs / 1000) % 60}초 · 약 ${String.format(Locale.KOREA, "%.1f", event.distanceM / 1000)} km 부근",
                fontSize = 14.sp, color = H1Style.Muted)
            Text(when (event.type) {
                DrivingEventType.OVERSPEED -> "제한 ${event.limitKph} km/h 구간에서 ${event.toKph.roundToInt()} km/h로 주행했어요."
                DrivingEventType.RAPID_ACCELERATION -> "${event.fromKph.roundToInt()} → ${event.toKph.roundToInt()} km/h로 빠르게 가속했어요."
                DrivingEventType.RAPID_DECELERATION -> "${event.fromKph.roundToInt()} → ${event.toKph.roundToInt()} km/h로 빠르게 감속했어요."
            }, fontSize = 15.sp, lineHeight = 22.sp, color = H1Style.Ink)
        }
    }
}

@Composable private fun SavedRankingDetails(drive: CompletedDrive) {
    val ranking = drive.ranking
    val own = ranking.firstOrNull { it.id == "나" }
    val comparison = when {
        !drive.demo -> "실제 서버 매칭은 준비 중이에요. 이번 주행은 개인 기록으로 확인해요."
        drive.missedDeadline -> "마감 이후에 도착해 개인 기록만 표시해요."
        ranking.isEmpty() -> "순위에 필요한 거리·데이터 조건을 채우지 못했어요."
        else -> "시연 비교조 ${ranking.size}명 · 같은 회차 · 챌린지 반경 1km"
    }
    Text(comparison, fontSize = 14.sp, lineHeight = 22.sp, color = H1Style.Muted)
    if (ranking.isNotEmpty()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
            Text("순위", Modifier.width(90.dp), fontSize = 12.sp, color = H1Style.Muted)
            Text("참가자", Modifier.weight(1f), fontSize = 12.sp, color = H1Style.Muted)
            Text("점수", Modifier.width(70.dp), textAlign = TextAlign.End, fontSize = 12.sp, color = H1Style.Muted)
            Text("배정 M", Modifier.width(90.dp), textAlign = TextAlign.End, fontSize = 12.sp, color = H1Style.Muted)
        }
        ranking.forEach { row ->
            val tied = ranking.count { it.rank == row.rank } > 1
            Row(
                Modifier.fillMaxWidth().background(if (row.id == "나") H1Style.PaleMint else H1Style.Paper, RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(if (tied) "공동 ${row.rank}위" else "${row.rank}위", Modifier.width(90.dp),
                    fontSize = 15.sp, fontWeight = FontWeight.Bold, color = H1Style.Ink)
                Text(row.id, Modifier.weight(1f), fontSize = 15.sp, fontWeight = if (row.id == "나") FontWeight.Bold else FontWeight.Normal, color = H1Style.Ink)
                Text("${row.score}점", Modifier.width(70.dp), textAlign = TextAlign.End, fontSize = 15.sp, color = H1Style.Ink)
                Text("${row.miles} M", Modifier.width(90.dp), textAlign = TextAlign.End, fontSize = 15.sp, color = H1Style.Ink)
            }
        }
    }
    Surface(color = H1Style.PaleMint, shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("이번에 쌓인 마일리지", fontSize = 14.sp, color = H1Style.Muted)
            Text("+${drive.earned} M", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = H1Style.Ink)
            if (own != null && own.miles != drive.earned) {
                Text("순위로 배정된 ${own.miles} M와 실제 적립된 ${drive.earned} M는 달라요. 적립에는 목적지별 하루 1회·하루 2회 한도가 적용돼요.",
                    fontSize = 14.sp, lineHeight = 22.sp, color = H1Style.Muted)
            }
        }
    }
    Text("시제품 평가 기준 · 순위 보상은 85점 이상 유효 기록에 적용 · 완료 보상 0M",
        fontSize = 12.sp, lineHeight = 18.sp, color = H1Style.Muted)
}
