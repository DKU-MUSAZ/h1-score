package ai.musaz.h1score

import ai.musaz.h1score.core.DriveMood
import ai.musaz.h1score.core.DrivingEventType
import ai.musaz.h1score.core.ScoreReport
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The overview stays on one page; only the explicit full views scroll. */
@Composable internal fun SavedDriveDetailScreen(drive: CompletedDrive, onBack: () -> Unit) {
    var details by remember(drive.session.id) { mutableStateOf<SavedDriveDetailSection?>(null) }
    val feedback = remember(drive.report) { historyFeedback(drive.report) }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "기록 목록으로", tint = H1Style.Ink) }
            Column(Modifier.weight(1f).padding(start = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(drive.name, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = H1Style.Ink,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${historyDateLabel(drive.completedAtEpochMs)} · 분석 거리 ${"%.1f".format(drive.report.validDistanceM / 1000)} km · ${if (drive.demo) "시연 기록" else "SDK 기록"}",
                    fontSize = 12.sp, color = H1Style.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(if (drive.report.score == null) "점수 미확정" else if (drive.missedDeadline) "마감 이후 도착" else "기록 확인 완료",
                fontSize = 12.sp, color = H1Style.Ink, modifier = Modifier.padding(start = 12.dp))
        }
        Row(Modifier.weight(1.25f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SavedScoreCard(drive.report, Modifier.width(264.dp).fillMaxHeight())
            SavedFeedbackOverview(drive.report, feedback, Modifier.weight(1f).fillMaxHeight()) {
                details = SavedDriveDetailSection.FEEDBACK
            }
        }
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SavedEventPreview(drive.report, Modifier.weight(1f).fillMaxHeight()) { details = SavedDriveDetailSection.EVENTS }
            SavedRankOverview(drive, Modifier.width(276.dp).fillMaxHeight()) { details = SavedDriveDetailSection.RANKING }
        }
    }
    details?.let { SavedDriveDetailDialog(drive, it) { details = null } }
}

@Composable private fun SavedScoreCard(report: ScoreReport, modifier: Modifier) {
    val mood = DriveMood.forScore(report.score)
    Surface(modifier, color = Color.White, shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text("주행습관 점수", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = H1Style.Ink)
            Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                ScoreDial(report.score, if (mood == DriveMood.TEARY) Color(0xFFA8654C) else H1Style.Ink, Modifier.size(112.dp))
                ExpressionMascot(mood, Modifier.size(88.dp))
            }
            if (report.score != null) {
                ScoreMetricBars(report)
            } else Text(if (report.usesDistanceBonus) "거리·수신 품질이 부족해요. 전체보기에서 확인해 주세요."
                else "거리 또는 데이터가 부족해 점수를 확정하지 못했어요.",
                fontSize = 13.sp, lineHeight = 18.sp, color = H1Style.Muted)
        }
    }
}

@Composable private fun SavedFeedbackOverview(report: ScoreReport, feedback: HistoryFeedback, modifier: Modifier, onDetails: () -> Unit) {
    Surface(modifier, color = Color.White, shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("운전 습관 요약", Modifier.weight(1f), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = H1Style.Ink)
                TextButton(onClick = onDetails) { Text("피드백 전체보기", fontSize = 12.sp) }
            }
            Row(Modifier.fillMaxWidth().height(80.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                feedback.metrics.forEach { metric ->
                    Column(Modifier.weight(1f).fillMaxHeight().background(H1Style.PaleMint, RoundedCornerShape(12.dp)).padding(10.dp),
                        verticalArrangement = Arrangement.SpaceBetween) {
                        Text(metric.label, fontSize = 13.sp, color = H1Style.Muted)
                        Text(metric.value, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = H1Style.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(metric.detail, fontSize = 11.sp, color = H1Style.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                SavedBrief("이어갈 습관", feedback.goodPoints.last(), Modifier.weight(1f))
                val nextTip = when {
                    report.overLimitDistanceM > 0 -> "제한속도 표지를 확인하며 주행해 주세요."
                    report.accelerationEvents > 0 -> "가속 페달을 조금 더 천천히 밟아 보세요."
                    report.recordedDecelerations > 0 -> "앞차와 거리를 두고 도로 상황을 미리 살펴보세요."
                    report.usesDistanceBonus && report.turnPenalty > 0 -> "회전할 방향에 맞춰 방향지시등을 켜 주세요."
                    report.score == null -> "충분한 기록을 모은 뒤 다시 확인해 주세요."
                    else -> "지금의 운전 습관을 이어가 보세요."
                }
                SavedBrief("개선할 점", nextTip, Modifier.weight(1f))
            }
        }
    }
}

@Composable private fun SavedBrief(title: String, message: String, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = H1Style.Ink)
        Text(message, fontSize = 14.sp, lineHeight = 20.sp, color = H1Style.Ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable private fun SavedEventPreview(report: ScoreReport, modifier: Modifier, onDetails: () -> Unit) {
    Surface(modifier, color = Color.White, shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("주요 주행 구간", Modifier.weight(1f), fontSize = 17.sp, fontWeight = FontWeight.Bold, color = H1Style.Ink)
                TextButton(onClick = onDetails) { Text("구간 전체보기", fontSize = 12.sp) }
            }
            if (report.events.isEmpty()) {
                Text("저장된 구간 정보가 없어요.", fontSize = 14.sp, color = H1Style.Muted, modifier = Modifier.padding(top = 12.dp))
            } else {
                Text("저장된 ${report.events.size}건 중 처음 ${minOf(2, report.events.size)}건", fontSize = 12.sp, color = H1Style.Muted)
                report.events.take(2).forEach { event ->
                    Row(Modifier.fillMaxWidth().height(38.dp).background(H1Style.Paper, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(when (event.type) {
                            DrivingEventType.OVERSPEED -> "제한속도 초과"
                            DrivingEventType.RAPID_ACCELERATION -> "급가속"
                            DrivingEventType.RAPID_DECELERATION -> decelerationEventLabel(report)
                        }, Modifier.weight(1f), fontSize = 13.sp, fontWeight = FontWeight.Medium, color = H1Style.Ink)
                        Text("${event.elapsedMs / 60000}분 ${(event.elapsedMs / 1000) % 60}초 · 약 ${"%.1f".format(event.distanceM / 1000)} km 부근",
                            fontSize = 12.sp, color = H1Style.Muted)
                    }
                }
                Text("시간·거리는 기록 시작 기준이에요.", fontSize = 11.sp, color = H1Style.Muted)
            }
        }
    }
}

@Composable private fun SavedRankOverview(drive: CompletedDrive, modifier: Modifier, onDetails: () -> Unit) {
    val me = drive.ranking.firstOrNull { it.id == "나" }
    val rank = if (me == null) "순위 없음" else
        "${if (drive.ranking.count { it.rank == me.rank } > 1) "공동 " else ""}${me.rank}위"
    Surface(modifier, color = Color.White, shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text("함께 도착한 무사즈", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = H1Style.Ink)
            Text(when {
                !drive.demo -> "SDK 개인 기록"
                drive.missedDeadline -> "마감 이후 도착 · 순위 제외"
                drive.ranking.isEmpty() -> "비교조 순위 미산정"
                else -> "시연 비교조 ${drive.ranking.size}명"
            }, fontSize = 12.sp, color = H1Style.Muted, modifier = Modifier.padding(top = 8.dp))
            Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("내 순위", fontSize = 12.sp, color = H1Style.Muted)
                    Text(rank, fontSize = 25.sp, fontWeight = FontWeight.Bold, color = H1Style.Ink)
                }
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("이번 적립", fontSize = 12.sp, color = H1Style.Muted)
                    Text("+${drive.earned} M", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = H1Style.Ink)
                }
            }
            TextButton(onClick = onDetails, modifier = Modifier.fillMaxWidth()) { Text("순위 전체보기", fontSize = 13.sp) }
        }
    }
}
