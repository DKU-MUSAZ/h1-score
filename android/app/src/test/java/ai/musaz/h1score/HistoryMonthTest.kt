package ai.musaz.h1score

import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.*
import org.junit.Test

class HistoryMonthTest {
    @Test fun sixWeekMonthIncludesBothBoundaryWeeks() {
        val days = historyMonthDays(YearMonth.of(2026, 8))
        assertEquals(42, days.size)
        assertEquals(LocalDate.of(2026, 7, 26), days.first())
        assertEquals(LocalDate.of(2026, 9, 5), days.last())
    }
    @Test fun leapDayAndYearBoundaryRemainSelectable() {
        assertTrue(LocalDate.of(2028, 2, 29) in historyMonthDays(YearMonth.of(2028, 2)))
        val days = historyMonthDays(YearMonth.of(2026, 12))
        assertEquals(LocalDate.of(2026, 11, 29), days.first())
        assertEquals(LocalDate.of(2027, 1, 2), days.last())
        assertEquals(days.size, days.toSet().size)
    }
    @Test fun scoresKeepZeroUnknownAndMultipleGamesDistinct() {
        assertEquals("", historyDayScores(emptyList()))
        assertEquals("0점", historyDayScores(listOf(0)))
        assertEquals("미확정", historyDayScores(listOf(null)))
        assertEquals("95 · 89점", historyDayScores(listOf(95, 89)))
        assertEquals("95 · 89점\n외 1경기", historyDayScores(listOf(95, 89, 76)))
    }
}
