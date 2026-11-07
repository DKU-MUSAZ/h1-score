package ai.musaz.h1score.core

import java.time.Instant
import org.junit.Test
import kotlin.test.*

class ChallengeRoundTest {
    @Test fun closesAtNineSeoulTime() {
        val opening = Instant.parse("2026-11-06T21:00:00Z")
        val closing = Instant.parse("2026-11-07T00:00:00Z")
        val round = ChallengeRound.currentOrNext(opening)
        assertTrue(round.contains(opening))
        assertTrue(round.contains(closing.minusMillis(1)))
        assertFalse(round.contains(closing))
        assertFalse(ChallengeRound.currentOrNext(closing).contains(closing))
    }

    @Test fun groupsDestinationWithinOneKilometer() {
        val venue = GeoPoint(37.52639977, 126.93360958)
        assertTrue(venue.includes(GeoPoint(37.53439977, 126.93360958)))
        assertFalse(venue.includes(GeoPoint(37.53639977, 126.93360958)))
        assertFalse(venue.includes(GeoPoint(Double.NaN, 126.93360958)))
    }
}
