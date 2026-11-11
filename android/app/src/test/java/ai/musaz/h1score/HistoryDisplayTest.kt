package ai.musaz.h1score

import ai.musaz.h1score.core.ChallengeSession
import ai.musaz.h1score.core.Phase
import ai.musaz.h1score.core.ScoreReport
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class HistoryDisplayTest {
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
