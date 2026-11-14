package ai.musaz.h1score

import ai.musaz.h1score.core.DriveMood
import ai.musaz.h1score.core.ScoreEngine
import ai.musaz.h1score.core.ScoreReport
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.TipsAndUpdates
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun SavedDriveDetailScreen(drive: CompletedDrive, onBack: () -> Unit) {
    var details by remember(drive.session.id) { mutableStateOf<SavedDriveDetailSection?>(null) }
    val report = drive.report
    val feedback = remember(report) { historyFeedback(report) }

    Column(
        Modifier.fillMaxSize().padding(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "기록 목록으로") }
            Column(Modifier.weight(1f)) {
                Text(drive.name, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = H1Style.Ink,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${historyDateLabel(drive.completedAtEpochMs)}  /  ${"%.1f".format(report.validDistanceM / 1000)} km  /  ${if (drive.demo) "시연 기록" else "차량 기록"}",
                    fontSize = 16.sp, color = H1Style.Muted,
                )
            }
            Spacer(Modifier.width(12.dp))
            SavedDriveRewards(drive) { details = SavedDriveDetailSection.RANKING }
        }

        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val availableHeight = maxHeight
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = availableHeight),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Surface(Modifier.weight(.38f).fillMaxHeight(), color = Color.White, shape = RoundedCornerShape(24.dp)) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("주행습관 점수", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = H1Style.Ink)
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                ScoreDial(report.score, H1Style.Ink, Modifier.weight(1f).height(120.dp),
                                    numberSize = 51.sp, pendingLabel = "점수 미확정")
                                ExpressionMascot(DriveMood.forScore(report.score), Modifier.size(100.dp))
                            }
                            Surface(color = H1Style.Paper, shape = RoundedCornerShape(16.dp)) {
                                Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Text("점수 요약", Modifier.weight(1f), fontSize = 17.sp,
                                            fontWeight = FontWeight.Bold, color = H1Style.Ink)
                                        TextButton(onClick = { details = SavedDriveDetailSection.FEEDBACK }) {
                                            Text("점수 자세히 보기", fontSize = 15.sp)
                                        }
                                    }
                                    SavedScoreSummary(report)
                                }
                            }
                        }
                    }
                    Surface(Modifier.weight(.62f).fillMaxHeight(), color = Color.White, shape = RoundedCornerShape(24.dp)) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("운전 습관 요약", Modifier.weight(1f), fontSize = 24.sp,
                                    fontWeight = FontWeight.Bold, color = H1Style.Ink)
                                if (report.events.isNotEmpty()) {
                                    TextButton(onClick = { details = SavedDriveDetailSection.EVENTS }) {
                                        Text("주행 구간 ${report.events.size}건 보기", fontSize = 15.sp)
                                    }
                                }
                            }
                            feedback.metrics.chunked(2).forEach { pair ->
                                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    pair.forEach { metric ->
                                        Surface(Modifier.weight(1f).fillMaxHeight(), color = H1Style.Paper,
                                            shape = RoundedCornerShape(16.dp)) {
                                            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                                verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                                Text(metric.label, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = H1Style.Ink)
                                                Text(metric.value, fontSize = 27.sp, fontWeight = FontWeight.Bold, color = H1Style.Ink)
                                                Text(metric.detail, fontSize = 15.sp, color = H1Style.Muted)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Row(
                    Modifier.weight(1f).fillMaxWidth().height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    SavedHabitPanel(
                        "이어갈 습관", feedback.goodPoints, Icons.Outlined.CheckCircle, SavedDriveDetailStyle.Good,
                        Modifier.weight(.38f).fillMaxHeight(),
                    )
                    SavedHabitPanel(
                        "개선할 점", feedback.improvements, Icons.Outlined.TipsAndUpdates, SavedDriveDetailStyle.Improve,
                        Modifier.weight(.62f).fillMaxHeight(),
                    )
                }
            }
        }
    }
    details?.let { SavedDriveDetailDialog(drive, it) { details = null } }
}

@Composable
private fun SavedDriveRewards(drive: CompletedDrive, onRanking: () -> Unit) {
    val own = drive.ranking.firstOrNull { it.id == "나" }
    val tied = own != null && drive.ranking.count { it.rank == own.rank } > 1
    val rank = when {
        own != null -> "${if (drive.demo) "시연 순위" else "내 순위"} ${if (tied) "공동 " else ""}${own.rank}위"
        drive.missedDeadline -> "마감 후 도착"
        else -> "순위 미산정"
    }
    Surface(color = Color.White, shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.padding(start = 14.dp, end = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("$rank    이번 적립 +${drive.earned}M", fontSize = 16.sp,
                fontWeight = FontWeight.Bold, color = H1Style.Ink)
            TextButton(onClick = onRanking) { Text("순위 자세히 보기", fontSize = 15.sp) }
        }
    }
}

@Composable
private fun SavedScoreSummary(report: ScoreReport) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (report.usesDistanceBonus) {
            SavedScoreBar("기본 점수", "${ScoreEngine.START_POINTS.toInt()}점",
                ScoreEngine.START_POINTS, H1Style.Ink, report.score != null)
            SavedScoreBar("가점", "+${scorePoints(report.earnedPoints)}점",
                report.earnedPoints, SavedDriveDetailStyle.Good, report.score != null)
            SavedScoreBar("감점", "−${scorePoints(report.turnPenalty)}점",
                report.turnPenalty, SavedDriveDetailStyle.Improve, report.score != null)
        } else {
            SavedScoreBar("제한속도 준수", "${scorePoints(report.compliance)}점",
                report.compliance, H1Style.Ink, report.score != null)
            SavedScoreBar("부드러운 가속", "${scorePoints(report.smoothness)}점",
                report.smoothness, SavedDriveDetailStyle.Good, report.score != null)
        }
    }
}

@Composable
private fun SavedScoreBar(label: String, value: String, points: Double, accent: Color, hasScore: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(98.dp), fontSize = 15.sp, color = H1Style.Ink)
        LinearProgressIndicator(
            progress = { if (hasScore) (points / 100.0).toFloat().coerceIn(0f, 1f) else 0f },
            modifier = Modifier.weight(1f).height(7.dp).clip(CircleShape),
            color = accent,
            trackColor = H1Style.Line,
            gapSize = 0.dp,
            drawStopIndicator = {},
        )
        Text(if (hasScore) value else "—", Modifier.width(77.dp), fontSize = 16.sp,
            fontWeight = FontWeight.Bold, color = H1Style.Ink, textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
}

@Composable
private fun SavedHabitPanel(title: String, points: List<String>, icon: ImageVector, accent: Color, modifier: Modifier) {
    Surface(modifier, color = accent.copy(alpha = .085f), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Icon(icon, null, Modifier.size(24.dp), tint = accent)
                Text(title, fontSize = 24.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold, color = accent)
            }
            for (point in points) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("•", fontSize = 19.sp, lineHeight = 27.sp, color = accent)
                    Text(point, Modifier.weight(1f), fontSize = 19.sp, lineHeight = 27.sp, color = H1Style.Ink)
                }
            }
        }
    }
}
