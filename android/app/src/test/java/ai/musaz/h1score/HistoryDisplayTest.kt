package ai.musaz.h1score

import ai.musaz.h1score.core.ChallengeSession
import ai.musaz.h1score.core.Phase
import ai.musaz.h1score.core.ScoreReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class HistoryDisplayTest {
    @Test fun weekIncludesOnlyMondayThroughSundayInKoreanTime() {
        val before = drive("before", time("2026-12-27T14:59:59Z"))
        val first = drive("monday", time("2026-12-27T15:00:00Z"))
        val last = drive("sunday", time("2027-01-03T14:59:59Z"))
        val after = drive("after", time("2027-01-03T15:00:00Z"))
        val unknown = drive("unknown", null)
        assertEquals(listOf(first, last), historyForWeek(listOf(before, first, last, after, unknown), LocalDate.of(2026, 12, 28)))
    }
    @Test fun dateLabelUsesKoreanTimeAcrossMidnight() {
        assertEquals("주행일 2026.11.10 23:59", historyDateLabel(time("2026-11-10T14:59:00Z")))
        assertEquals("주행일 2026.11.11 00:00", historyDateLabel(time("2026-11-10T15:00:00Z")))
        assertEquals("주행 날짜 미상", historyDateLabel(null))
    }

    @Test fun bothDirectionsUseTheFullTimestampAndKeepUnknownDatesLast() {
        val earlier = drive("earlier", time("2026-11-11T00:10:00Z"))
        val later = drive("later", time("2026-11-11T00:35:00Z"))
        val previousDay = drive("previous-day", time("2026-11-10T14:59:00Z"))
        val unknown = drive("legacy", null)
        val input = mutableListOf(unknown, earlier, previousDay, later)
        val original = input.toList()

        assertEquals(listOf(later, earlier, previousDay, unknown), historyByDate(input, HistoryDateOrder.NEWEST_FIRST))
        assertEquals(listOf(previousDay, earlier, later, unknown), historyByDate(input, HistoryDateOrder.OLDEST_FIRST))
        assertEquals(original, input)
    }

    @Test fun equalTimestampsAndUnknownDatesKeepTheirRelativeOrder() {
        val timestamp = time("2026-11-11T01:00:00Z")
        val first = drive("first", timestamp)
        val second = drive("second", timestamp)
        val unknownFirst = drive("unknown-first", null)
        val unknownSecond = drive("unknown-second", null)
        val input = listOf(unknownFirst, first, unknownSecond, second)

        HistoryDateOrder.entries.forEach { order ->
            assertEquals(listOf(first, second, unknownFirst, unknownSecond), historyByDate(input, order))
        }
    }

    @Test fun selectedDayUsesKoreanMidnightAndAllKeepsUndatedRecords() {
        val previous = drive("previous-day", time("2026-11-10T14:59:59.999Z"))
        val midnight = drive("midnight", time("2026-11-10T15:00:00Z"))
        val evening = drive("evening", time("2026-11-11T14:59:59.999Z"))
        val next = drive("next-day", time("2026-11-11T15:00:00Z"))
        val unknown = drive("legacy", null)
        val input = mutableListOf(unknown, next, evening, previous, midnight)
        val original = input.toList()
        val selected = historyForDay(input, LocalDate.of(2026, 11, 11))

        assertEquals(listOf(evening, midnight), selected)
        assertEquals(listOf(midnight, evening), historyByDate(selected, HistoryDateOrder.OLDEST_FIRST))
        assertEquals(original, historyForDay(input, null))
        assertEquals(emptyList<CompletedDrive>(), historyForDay(input, LocalDate.of(2026, 11, 9)))
        assertEquals(original, input)
        assertEquals(LocalDate.of(2026, 11, 11), historyDate(midnight.completedAtEpochMs))
        assertNull(historyDate(null))
    }

    @Test fun initialWeekUsesLatestDatedRecordOrKoreanToday() {
        val old = drive("old", time("2025-12-31T14:59:00Z"))
        val latest = drive("latest", time("2025-12-31T15:00:00Z"))
        val unknown = drive("unknown", null)
        val today = LocalDate.of(2026, 11, 11)

        assertEquals(LocalDate.of(2025, 12, 29), initialHistoryWeek(listOf(unknown, latest, old), today))
        assertEquals(LocalDate.of(2026, 11, 9), initialHistoryWeek(listOf(unknown), today))
        assertEquals(LocalDate.of(2026, 11, 9), initialHistoryWeek(emptyList(), today))
    }

    @Test fun weeksStartOnMondayAcrossLeapMonthAndYearBoundary() {
        assertEquals(LocalDate.of(2024, 2, 26), historyWeekStart(LocalDate.of(2024, 2, 29)))
        assertEquals(LocalDate.of(2024, 2, 26), historyWeekStart(LocalDate.of(2024, 3, 3)))
        assertEquals(LocalDate.of(2026, 12, 28), historyWeekStart(LocalDate.of(2027, 1, 1)))
        assertEquals(LocalDate.of(2027, 1, 4), historyWeekStart(LocalDate.of(2027, 1, 4)))
    }

    private fun time(iso: String) = Instant.parse(iso).toEpochMilli()

    private fun drive(id: String, time: Long?) = CompletedDrive(
        session = ChallengeSession(id, "destination-$id", 14_000.0, Phase.FINISHED),
        report = ScoreReport(91, 88.0, 98.0, 10_300.0, 1.0, true, 0, 321.0),
        name = id,
        demo = true,
        ranking = emptyList(),
        earned = 0,
        missedDeadline = false,
        completedAtEpochMs = time,
    )
}
