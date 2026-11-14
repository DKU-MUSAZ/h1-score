package ai.musaz.h1score

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.TipsAndUpdates
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun SavedDriveDetailScreen(drive: CompletedDrive, onBack: () -> Unit) {
    if (drive.outcome != DriveOutcome.COMPLETED) {
        SavedBeltOutcomeScreen(drive, onBack)
        return
    }
    var details by remember(drive.session.id) { mutableStateOf<SavedDriveDetailSection?>(null) }
    val report = drive.report
    val feedback = remember(report) { historyFeedback(report) }
    val own = drive.ranking.firstOrNull { it.id == "나" }
    val tied = own != null && drive.ranking.count { it.rank == own.rank } > 1
    val rank = when {
        own != null -> "${if (tied) "공동 " else ""}${own.rank}위"
        drive.missedDeadline -> "마감 후 도착"
        else -> "순위 미산정"
    }
    val source = when {
        drive.isHistoryExample -> "시연용 예시 기록"
        drive.demo -> "시연 기록"
        else -> "차량 기록"
    }

    Column(Modifier.fillMaxSize().padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "기록 목록으로") }
            Column(Modifier.weight(1f)) {
                Text(drive.name, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = H1Style.Ink,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${historyDateLabel(drive.completedAtEpochMs)}  ·  ${"%.1f".format(report.validDistanceM / 1000)} km  ·  $source",
                    fontSize = 16.sp, color = H1Style.Muted,
                )
                if (drive.recognition != ResultRecognition.LEGACY) {
                    Text(if (drive.recognition == ResultRecognition.ACCEPTED)
                        "${drive.participationDay} 참가 · 경기 인정"
                    else "${drive.recognition.label} · 배지·경기 보상 제외",
                        fontSize = 15.sp, color = H1Style.Muted)
                }
            }
        }

        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val availableHeight = maxHeight
            Row(
                Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Surface(Modifier.weight(2.1f).fillMaxHeight(), color = Color.White, shape = RoundedCornerShape(24.dp)) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                        .heightIn(min = availableHeight).height(IntrinsicSize.Min).padding(20.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            SavedDriveSummaryColumn("주행습관 점수", "점수 자세히 보기", Modifier.weight(1f),
                                onClick = { details = SavedDriveDetailSection.FEEDBACK }) {
                                ScoreDial(report.score, H1Style.Ink, Modifier.size(140.dp), numberSize = 60.sp,
                                    pendingLabel = "점수 미확정")
                            }
                            SavedDriveSummaryColumn("순위", "순위 자세히 보기", Modifier.weight(1f),
                                onClick = { details = SavedDriveDetailSection.RANKING }) {
                                Text(rank, fontSize = if (rank.length > 5) 25.sp else 38.sp,
                                    lineHeight = 44.sp, fontWeight = FontWeight.Bold, color = H1Style.Ink,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                            }
                            SavedDriveSummaryColumn("적립 마일리지", "마일리지 자세히 보기", Modifier.weight(1f),
                                onClick = { details = SavedDriveDetailSection.MILEAGE }) {
                                Text("+${drive.earned + drive.badgeMileage}M", fontSize = 34.sp, lineHeight = 42.sp,
                                    fontWeight = FontWeight.Bold, color = H1Style.Ink)
                            }
                        }
                        HorizontalDivider(Modifier.padding(top = 12.dp, bottom = 18.dp), color = H1Style.Line)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterVertically)) {
                            SavedHabitPanel("이어갈 습관", feedback.goodPoints, Icons.Outlined.CheckCircle, SavedDriveDetailStyle.Good)
                            SavedHabitPanel("개선할 점", feedback.improvements, Icons.Outlined.TipsAndUpdates, SavedDriveDetailStyle.Improve)
                        }
                    }
                }
                Surface(
                    Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())
                        .heightIn(min = availableHeight).height(IntrinsicSize.Min),
                    color = Color.White,
                    shape = RoundedCornerShape(24.dp),
                ) {
                    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("운전 습관 요약", fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold)
                        feedback.metrics.forEach { metric ->
                            SavedMetricCard(metric, Modifier.weight(1f).fillMaxWidth())
                        }
                    }
                }
            }
        }
    }
    details?.let { SavedDriveDetailDialog(drive, it) { details = null } }
}

@Composable
private fun SavedBeltOutcomeScreen(drive: CompletedDrive, onBack: () -> Unit) {
    var details by remember(drive.session.id) { mutableStateOf<SavedDriveDetailSection?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "기록 목록으로") }
            Column(Modifier.weight(1f)) {
                Text(drive.name, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = H1Style.Ink)
                Text(historyDateLabel(drive.completedAtEpochMs), fontSize = 16.sp, color = H1Style.Muted)
            }
        }
        Surface(Modifier.fillMaxWidth(), color = Color.White, shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                SavedBeltOutcomeNotice(drive)
                Text("기록된 분석 거리 ${"%.1f".format(drive.report.validDistanceM / 1000)} km",
                    fontSize = 16.sp, color = H1Style.Muted)
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    TextButton(onClick = { details = SavedDriveDetailSection.FEEDBACK }) { Text("판정 자세히 보기") }
                    TextButton(onClick = { details = SavedDriveDetailSection.RANKING }) { Text("순위 자세히 보기") }
                    TextButton(onClick = { details = SavedDriveDetailSection.MILEAGE }) { Text("마일리지 자세히 보기") }
                }
            }
        }
    }
    details?.let { SavedDriveDetailDialog(drive, it) { details = null } }
}

@Composable
private fun SavedMetricCard(
    metric: HistoryFeedbackMetric, modifier: Modifier,
) {
    Surface(modifier, color = H1Style.Paper, shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(metric.label, Modifier.weight(1f).alignByBaseline(), fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold)
                Text(metric.value, Modifier.alignByBaseline(), fontSize = 24.sp, lineHeight = 28.sp,
                    fontWeight = FontWeight.Bold)
            }
            Text(when (metric.label) {
                "급가속" -> "급가속 발생 횟수"
                "급감속" -> "급감속 발생 횟수"
                else -> metric.detail
            }, fontSize = 13.sp, lineHeight = 18.sp, color = H1Style.Muted)
        }
    }
}

@Composable
private fun SavedDriveSummaryColumn(
    title: String, action: String, modifier: Modifier, onClick: () -> Unit, value: @Composable () -> Unit,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        TextButton(onClick = onClick, modifier = Modifier.semantics { contentDescription = action },
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)) {
            Text(title, fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold, color = H1Style.Ink)
            Spacer(Modifier.width(4.dp))
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, Modifier.size(18.dp))
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(140.dp), contentAlignment = Alignment.Center) { value() }
    }
}

@Composable
private fun SavedHabitPanel(title: String, points: List<String>, icon: ImageVector, accent: Color) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp),
        color = accent.copy(alpha = 0.055f)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(icon, null, Modifier.size(22.dp), tint = accent)
                Text(title, fontSize = 21.sp, lineHeight = 27.sp, fontWeight = FontWeight.Bold, color = accent)
            }
            points.forEach { point ->
                Text(point, Modifier.padding(start = 30.dp), fontSize = 18.sp, lineHeight = 26.sp, color = H1Style.Ink)
            }
        }
    }
}
