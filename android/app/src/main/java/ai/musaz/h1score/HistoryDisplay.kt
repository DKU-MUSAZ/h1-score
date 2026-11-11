package ai.musaz.h1score

import ai.musaz.h1score.core.ChallengeRound
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

internal enum class HistoryDateOrder { NEWEST_FIRST, OLDEST_FIRST }

private val historyDateFormat = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm", Locale.KOREAN)
    .withZone(ChallengeRound.seoul)

internal fun historyDateLabel(completedAtEpochMs: Long?): String =
    completedAtEpochMs?.let { "주행일 ${historyDateFormat.format(Instant.ofEpochMilli(it))}" }
        ?: "주행 날짜 미상"

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
