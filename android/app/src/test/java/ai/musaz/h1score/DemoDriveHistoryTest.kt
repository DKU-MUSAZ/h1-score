package ai.musaz.h1score

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class DemoDriveHistoryTest {
    @Test fun examplesHaveOneGameAndOneScorePerKoreanDay() {
        listOf(LocalDate.of(2026, 11, 13), LocalDate.of(2027, 1, 1), LocalDate.of(2028, 3, 1)).forEach { today ->
            val examples = demoHistoryExamples(today)
            val dates = examples.map { historyDate(it.completedAtEpochMs) }
            assertEquals(7, examples.size)
            assertTrue(examples.all { it.isHistoryExample && it.report.score != null })
            assertTrue(dates.all { it != null && !it.isAfter(today) })
            assertEquals("One game per Korean calendar day", dates.size, dates.toSet().size)
        }
    }
}
