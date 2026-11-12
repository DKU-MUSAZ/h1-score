package ai.musaz.h1score

import ai.musaz.h1score.core.ChallengeRound
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

internal enum class HistoryDateOrder { NEWEST_FIRST, OLDEST_FIRST }

private val historyDateFormat = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm", Locale.KOREAN)
    .withZone(ChallengeRound.seoul)

internal fun historyDateLabel(completedAtEpochMs: Long?): String =
    completedAtEpochMs?.let { "주행일 ${historyDateFormat.format(Instant.ofEpochMilli(it))}" }
        ?: "주행 날짜 미상"

internal fun historyDate(completedAtEpochMs: Long?): LocalDate? =
    completedAtEpochMs?.let { Instant.ofEpochMilli(it).atZone(ChallengeRound.seoul).toLocalDate() }

internal fun historyForDay(records: List<CompletedDrive>, date: LocalDate?): List<CompletedDrive> =
    if (date == null) records else records.filter { historyDate(it.completedAtEpochMs) == date }

internal fun initialHistoryMonth(records: List<CompletedDrive>, today: LocalDate = LocalDate.now(ChallengeRound.seoul)): YearMonth =
    YearMonth.from(records.mapNotNull { historyDate(it.completedAtEpochMs) }.maxOrNull() ?: today)

internal fun historyCalendarDays(month: YearMonth): List<LocalDate?> {
    val leadingDays = month.atDay(1).dayOfWeek.value % 7
    return List(42) { index ->
        val day = index - leadingDays + 1
        if (day in 1..month.lengthOfMonth()) month.atDay(day) else null
    }
}

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
