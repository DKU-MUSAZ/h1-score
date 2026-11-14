package ai.musaz.h1score

import ai.musaz.h1score.core.ChallengeRound
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

internal enum class HistoryDateOrder { NEWEST_FIRST, OLDEST_FIRST }

internal fun historyResultLabel(drive: CompletedDrive): String = when (drive.outcome) {
    DriveOutcome.COMPLETED -> drive.report.score?.let { "${it}점" } ?: "미확정"
    else -> drive.outcome.label
}

internal val CompletedDrive.beltOutcomeReason: String?
    get() = when (outcome) {
        DriveOutcome.COMPLETED -> null
        DriveOutcome.DISQUALIFIED -> beltFailureReason?.label ?: "주행 중 안전벨트 미착용이 확인되었어요."
        DriveOutcome.UNVERIFIABLE -> beltFailureReason?.label ?: "주행 중 안전벨트 상태를 확인할 수 없었어요."
    }

internal fun historyDayResults(records: List<CompletedDrive>): String {
    if (records.all { it.outcome == DriveOutcome.COMPLETED }) return historyDayScores(records.map { it.report.score })
    val shown = records.take(2)
    val text = if (shown.all { it.outcome == DriveOutcome.COMPLETED && it.report.score != null }) {
        historyDayScores(shown.map { it.report.score })
    } else shown.joinToString(" · ", transform = ::historyResultLabel)
    return text + if (records.size > 2) "\n외 ${records.size - 2}경기" else ""
}

internal fun historyMonthDays(month: YearMonth): List<LocalDate> {
    val first = month.atDay(1).with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))
    val last = month.atEndOfMonth().with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY))
    return List(java.time.temporal.ChronoUnit.DAYS.between(first, last).toInt() + 1) { first.plusDays(it.toLong()) }
}

internal fun historyDayScores(scores: List<Int?>): String {
    if (scores.isEmpty()) return ""
    val shown = scores.take(2)
    val text = if (shown.all { it != null }) shown.joinToString(" · ") + "점"
        else shown.joinToString(" · ") { it?.let { score -> "${score}점" } ?: "미확정" }
    return text + if (scores.size > 2) "\n외 ${scores.size - 2}경기" else ""
}

private val historyDateFormat = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm", Locale.KOREAN)
    .withZone(ChallengeRound.seoul)

internal fun historyDateLabel(completedAtEpochMs: Long?): String =
    historyDateValue(completedAtEpochMs)?.let { "주행일 $it" }
        ?: "주행 날짜 미상"

internal fun historyDateValue(completedAtEpochMs: Long?): String? =
    completedAtEpochMs?.let { historyDateFormat.format(Instant.ofEpochMilli(it)) }

internal fun historyDate(completedAtEpochMs: Long?): LocalDate? =
    completedAtEpochMs?.let { Instant.ofEpochMilli(it).atZone(ChallengeRound.seoul).toLocalDate() }

/** Calendar grouping and daily progress share the reservation date; the row retains actual arrival time. */
internal fun historyRecordDay(drive: CompletedDrive): LocalDate? =
    drive.participationDay?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        ?: historyDate(drive.completedAtEpochMs)

internal fun historyForDay(records: List<CompletedDrive>, date: LocalDate?): List<CompletedDrive> =
    if (date == null) records else records.filter { historyRecordDay(it) == date }

internal fun historyForWeek(records: List<CompletedDrive>, week: LocalDate): List<CompletedDrive> {
    val start = historyWeekStart(week)
    val end = start.plusDays(7)
    return records.filter { record ->
        historyRecordDay(record)?.let { !it.isBefore(start) && it.isBefore(end) } == true
    }
}

internal fun historyWeekStart(day: LocalDate): LocalDate =
    day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

internal fun initialHistoryWeek(records: List<CompletedDrive>, today: LocalDate = LocalDate.now(ChallengeRound.seoul)): LocalDate =
    historyWeekStart(records.mapNotNull(::historyRecordDay).maxOrNull() ?: today)

internal fun historyByDate(records: List<CompletedDrive>, order: HistoryDateOrder): List<CompletedDrive> =
    records.sortedWith { first, second ->
        val firstTime = first.completedAtEpochMs
        val secondTime = second.completedAtEpochMs
        // Legacy records have no completion date and stay last in either direction.
        when {
            firstTime == null && secondTime == null -> 0
            firstTime == null -> 1
            secondTime == null -> -1
            order == HistoryDateOrder.NEWEST_FIRST -> secondTime.compareTo(firstTime)
            else -> firstTime.compareTo(secondTime)
        }
    }
