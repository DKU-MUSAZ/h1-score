package ai.musaz.h1score

import ai.musaz.h1score.core.DriveMood
import ai.musaz.h1score.core.ScoreReport
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The overview stays on one page; only the explicit full views scroll. */
@Composable internal fun SavedDriveDetailScreen(drive: CompletedDrive, onBack: () -> Unit) {
    var details by remember(drive.session.id) { mutableStateOf<SavedDriveDetailSection?>(null) }
    val feedback = remember(drive.report) { historyFeedback(drive.report) }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "기록 목록으로", tint = H1Style.Ink) }
            Column(Modifier.weight(1f).padding(start = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(drive.name, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold, color = H1Style.Ink,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${historyDateLabel(drive.completedAtEpochMs)} · 분석 거리 ${"%.1f".format(drive.report.validDistanceM / 1000)} km · ${if (drive.demo) "시연 기록" else "SDK 기록"}",
                    fontSize = 12.sp, lineHeight = 16.sp, color = H1Style.Muted)
            }
            Text(if (drive.report.score == null) "점수 미확정" else if (drive.missedDeadline) "마감 이후 도착" else "기록 확인 완료",
                fontSize = 12.sp, color = H1Style.Ink, modifier = Modifier.padding(start = 12.dp))
        }
        Row(Modifier.weight(1.25f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SavedScoreCard(drive.report, Modifier.weight(0.4f).fillMaxHeight())
            SavedFeedbackOverview(feedback, Modifier.weight(0.6f).fillMaxHeight()) {
                details = SavedDriveDetailSection.FEEDBACK
            }
        }
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SavedRankOverview(drive, Modifier.weight(0.4f).fillMaxHeight()) { details = SavedDriveDetailSection.RANKING }
            SavedEventPreview(drive.report, Modifier.weight(0.6f).fillMaxHeight()) { details = SavedDriveDetailSection.EVENTS }
        }
    }
    details?.let { SavedDriveDetailDialog(drive, it) { details = null } }
}

@Composable private fun SavedCardHeader(title: String, actionLabel: String? = null, onDetails: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().height(28.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), fontSize = 17.sp, lineHeight = 22.sp, letterSpacing = 0.sp,
            fontWeight = FontWeight.Bold, color = H1Style.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (actionLabel != null && onDetails != null) {
            TextButton(onClick = onDetails, modifier = Modifier.height(28.dp), contentPadding = PaddingValues(0.dp)) {
                Text(actionLabel, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.sp)
            }
        }
    }
    Spacer(Modifier.height(12.dp))
}

@Composable private fun SavedScoreCard(report: ScoreReport, modifier: Modifier) {
    val mood = DriveMood.forScore(report.score)
    Surface(modifier.testTag("saved-score-card"), color = Color.White, shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.padding(16.dp)) {
            SavedCardHeader("주행습관 점수")
            Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly) {
                SavedScoreDial(report.score)
                ExpressionMascot(mood, Modifier.size(96.dp))
            }
            when {
                report.score == null -> Text("거리 또는 수신 품질이 부족해요. 피드백 전체보기에서 확인해 주세요.",
                    fontSize = 13.sp, lineHeight = 18.sp, color = H1Style.Muted)
                report.usesDistanceBonus -> SavedScoreBreakdown(report)
                else -> ScoreMetricBars(report)
            }
        }
    }
}

/** The result screen keeps its original dial; this smaller type scale is for saved records. */
@Composable private fun SavedScoreDial(score: Int?) {
    Box(Modifier.size(112.dp).semantics { contentDescription = "주행습관 점수 원" }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().padding(7.dp)) {
            val diameter = size.minDimension
            val origin = Offset((size.width - diameter) / 2, (size.height - diameter) / 2)
            val circle = Size(diameter, diameter)
            val stroke = Stroke(6.dp.toPx(), cap = StrokeCap.Round)
            drawArc(H1Style.Line, 135f, 270f, false, topLeft = origin, size = circle, style = stroke)
            if (score != null) drawArc(H1Style.Ink, 135f, 270f * (score / 100f).coerceIn(0f, 1f),
                false, topLeft = origin, size = circle, style = stroke)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(score?.toString() ?: "—", fontSize = 36.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold, color = H1Style.Ink)
            Text(if (score == null) "평가 대기" else "100점 만점", fontSize = 10.sp, lineHeight = 14.sp, color = H1Style.Muted)
        }
    }
}

@Composable private fun SavedScoreBreakdown(report: ScoreReport) {
    Column(Modifier.fillMaxWidth().testTag("saved-score-breakdown")
        .background(H1Style.Paper, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 6.dp)) {
        SavedScoreLine("기본 점수", "60점")
        SavedScoreLine("속도 가점", "+${scorePoints(report.speedPoints)}점")
        SavedScoreLine("가감속 가점", "+${scorePoints(report.accelerationPoints)}점")
        SavedScoreLine("회전 감점", "−${scorePoints(report.turnPenalty)}점",
            if (report.turnPenalty > 0) SavedDriveDetailStyle.Improve else H1Style.Ink)
    }
}

@Composable private fun SavedScoreLine(label: String, value: String, accent: Color = H1Style.Ink) {
    Row(Modifier.fillMaxWidth().height(18.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.sp, color = H1Style.Muted)
        Text(value, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.sp, fontWeight = FontWeight.Medium, color = accent)
    }
}

@Composable private fun SavedFeedbackOverview(feedback: HistoryFeedback, modifier: Modifier, onDetails: () -> Unit) {
    Surface(onClick = onDetails, modifier = modifier.testTag("saved-feedback-card"),
        color = Color.White, shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.padding(16.dp)) {
            SavedCardHeader("운전 습관 요약", "피드백 전체보기", onDetails)
            Row(Modifier.fillMaxWidth().height(80.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                feedback.metrics.forEach { metric ->
                    Column(Modifier.weight(1f).fillMaxHeight().background(H1Style.Paper, RoundedCornerShape(12.dp))
                        .padding(horizontal = 10.dp, vertical = 10.dp)) {
                        Text(metric.label, fontSize = 12.sp, lineHeight = 15.sp, color = H1Style.Ink, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(8.dp))
                        Text(metric.value, fontSize = 19.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold, color = H1Style.Ink,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(2.dp))
                        Text(metric.detail, fontSize = 10.sp, lineHeight = 13.sp, color = H1Style.Muted,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                SavedBrief("이어갈 습관", feedback.goodPoints.take(2).joinToString("\n"), SavedDriveDetailStyle.Good,
                    Modifier.weight(1f), "saved-good-feedback")
                SavedBrief("개선할 점", feedback.improvements.take(2).joinToString("\n"), SavedDriveDetailStyle.Improve,
                    Modifier.weight(1f), "saved-improvement-feedback", feedback.improvements.size - 2)
            }
        }
    }
}

@Composable private fun SavedBrief(title: String, message: String, accent: Color, modifier: Modifier, tag: String, more: Int = 0) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Bold, color = accent)
            if (more > 0) Text(" · 외 ${more}개", fontSize = 11.sp, lineHeight = 18.sp, color = H1Style.Muted)
        }
        Text(message, Modifier.fillMaxWidth().testTag(tag), fontSize = 13.sp, lineHeight = 17.sp,
            color = H1Style.Ink, maxLines = 4, overflow = TextOverflow.Ellipsis)
    }
}

@Composable private fun SavedEventPreview(report: ScoreReport, modifier: Modifier, onDetails: () -> Unit) {
    Surface(onClick = onDetails, modifier = modifier.testTag("saved-events-card"), color = Color.White, shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.padding(16.dp)) {
            SavedCardHeader("주요 주행 구간", "구간 전체보기", onDetails)
            if (report.events.isEmpty()) {
                Text("저장된 구간 정보가 없어요.", fontSize = 14.sp, color = H1Style.Muted, modifier = Modifier.padding(top = 12.dp))
            } else {
                report.events.take(3).forEachIndexed { index, event ->
                    if (index > 0) Spacer(Modifier.height(4.dp))
                    val accent = event.type.detailColor()
                    Row(Modifier.fillMaxWidth().height(38.dp).testTag("saved-event-$index")
                        .background(accent.copy(alpha = .06f), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(3.dp).height(23.dp).background(accent, RoundedCornerShape(2.dp)))
                        Column(Modifier.weight(1f).padding(start = 9.dp)) {
                            Text(event.type.detailLabel(), fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, color = accent)
                            Text(event.speedDetail(), fontSize = 11.sp, lineHeight = 14.sp, color = H1Style.Muted)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("${event.elapsedMs / 60000}분 ${(event.elapsedMs / 1000) % 60}초", fontSize = 12.sp, lineHeight = 16.sp, color = H1Style.Ink)
                            Text("약 ${"%.1f".format(event.distanceM / 1000)} km 부근", fontSize = 11.sp, lineHeight = 14.sp, color = H1Style.Muted)
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text("저장 ${report.events.size}건 중 ${minOf(3, report.events.size)}건 · 시간·거리는 기록 시작 기준",
                    fontSize = 10.sp, lineHeight = 14.sp, color = H1Style.Muted)
            }
        }
    }
}

@Composable private fun SavedRankOverview(drive: CompletedDrive, modifier: Modifier, onDetails: () -> Unit) {
    val me = drive.ranking.firstOrNull { it.id == "나" }
    val leaders = drive.ranking.take(3)
    val includeMe = me != null && me !in leaders
    val preview = if (includeMe) leaders.take(2) + listOfNotNull(me) else leaders
    Surface(onClick = onDetails, modifier = modifier.testTag("saved-ranking-card"), color = Color.White, shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.padding(16.dp)) {
            SavedCardHeader("함께 도착한 무사즈", "순위 전체보기", onDetails)
            Text(when {
                !drive.demo -> "SDK 개인 기록"
                drive.missedDeadline -> "마감 이후 도착 · 순위 제외"
                drive.ranking.isEmpty() -> "비교조 순위 미산정"
                else -> "시연 비교조 ${drive.ranking.size}명" + if (includeMe) " · 상위 2명과 나" else ""
            }, fontSize = 11.sp, lineHeight = 15.sp, color = H1Style.Muted)
            Column(Modifier.weight(1f).fillMaxWidth().padding(top = 6.dp).testTag("saved-ranking-preview"),
                verticalArrangement = Arrangement.spacedBy(3.dp)) {
                preview.forEach { entry ->
                    val own = entry.id == "나"
                    Row(Modifier.fillMaxWidth().height(25.dp).testTag("saved-rank-${entry.id}")
                        .background(if (own) H1Style.PaleMint else Color.Transparent, RoundedCornerShape(7.dp))
                        .padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        val tied = drive.ranking.count { it.rank == entry.rank } > 1
                        Text("${if (tied) "공동 " else ""}${entry.rank}위", Modifier.width(62.dp), fontSize = 12.sp, color = H1Style.Muted)
                        Text(entry.id, Modifier.weight(1f), fontSize = 12.sp, color = H1Style.Ink,
                            fontWeight = if (own) FontWeight.Bold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${entry.score}점", Modifier.width(44.dp), fontSize = 12.sp, textAlign = TextAlign.End,
                            fontWeight = if (own) FontWeight.Bold else FontWeight.Normal, color = H1Style.Ink)
                    }
                }
                if (preview.isEmpty()) Text("함께 비교할 순위가 없어요.", Modifier.padding(top = 8.dp), fontSize = 12.sp, color = H1Style.Muted)
            }
            HorizontalDivider(color = H1Style.Line)
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("이번 적립", Modifier.weight(1f), fontSize = 12.sp, color = H1Style.Muted)
                Text("+${drive.earned} M", fontSize = 20.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold, color = H1Style.Ink)
            }
        }
    }
}
