package ai.musaz.h1score.core

import java.time.LocalDate
import org.junit.Test
import kotlin.test.*

class ChallengeRoundTest {
    @Test fun weekdaysUseFiveRounds() {
        val slots = listOf(
            Triple("새벽", 0, 7),
            Triple("출근", 7, 10),
            Triple("주간", 10, 17),
            Triple("퇴근", 17, 20),
            Triple("야간", 20, 24),
        )
        assertDailyRounds(LocalDate.of(2026, 11, 12), slots)
        assertDailyRounds(LocalDate.of(2026, 11, 13), slots)
        assertDailyRounds(LocalDate.of(2026, 11, 16), slots)
    }

    @Test fun saturdayAndSundayUseThreeRounds() {
        val slots = listOf(
            Triple("주말 새벽", 0, 8),
            Triple("주말 주간", 8, 16),
            Triple("주말 야간", 16, 24),
        )
        assertDailyRounds(LocalDate.of(2026, 11, 14), slots)
        assertDailyRounds(LocalDate.of(2026, 11, 15), slots)
    }

    private fun assertDailyRounds(date: LocalDate, slots: List<Triple<String, Int, Int>>) {
        val midnight = date.atStartOfDay(ChallengeRound.seoul)
        for ((name, start, end) in slots) {
            val opensAt = midnight.plusHours(start.toLong()).toInstant()
            val closesAt = midnight.plusHours(end.toLong()).toInstant()
            val round = ChallengeRound.currentOrNext(opensAt)
            assertEquals(ChallengeRound(name, opensAt, closesAt), round)
            assertEquals("%02d:00–%02d:00".format(start, end % 24), round.hoursLabel)
            assertEquals(round, ChallengeRound.currentOrNext(closesAt.minusMillis(1)))
            assertFalse(round.contains(closesAt))
            val next = ChallengeRound.currentOrNext(closesAt)
            assertEquals(closesAt, next.opensAt)
            assertTrue(next.contains(closesAt))
        }
    }

    @Test fun groupsDestinationWithinOneKilometer() {
        val venue = GeoPoint(37.52639977, 126.93360958)
        assertTrue(venue.includes(GeoPoint(37.53439977, 126.93360958)))
        assertFalse(venue.includes(GeoPoint(37.53639977, 126.93360958)))
        assertFalse(venue.includes(GeoPoint(Double.NaN, 126.93360958)))
    }
}
