package ai.musaz.h1score

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class DemoDriveHistoryTest {
    @Test fun examplesHaveOneGameAndOneScorePerKoreanDay() {
        listOf(LocalDate.of(2026, 11, 13), LocalDate.of(2027, 1, 1), LocalDate.of(2028, 3, 1)).forEach { today ->
            val examples = demoHistoryExamples(today)
            val dates = examples.map { historyDate(it.completedAtEpochMs) }
            assertEquals(8, examples.size)
            assertTrue(examples.all { it.isHistoryExample })
            assertEquals(7, examples.count { it.outcome == DriveOutcome.COMPLETED && it.report.score != null })
            val disqualified = examples.single { it.outcome == DriveOutcome.DISQUALIFIED }
            assertEquals(BeltFailureReason.DRIVER_UNBUCKLED, disqualified.beltFailureReason)
            assertNull(disqualified.report.score)
            assertFalse(disqualified.report.valid)
            assertTrue(disqualified.ranking.isEmpty())
            assertEquals(0, disqualified.earned + disqualified.badgeMileage)
            assertEquals(today.minusDays(4), historyRecordDay(disqualified))
            assertEquals("실격", historyResultLabel(disqualified))
            assertTrue(dates.all { it != null && !it.isAfter(today) })
            assertEquals("One game per Korean calendar day", dates.size, dates.toSet().size)
        }
    }
}
