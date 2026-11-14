package ai.musaz.h1score.core

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectionProgressTest {
    private val monday = LocalDate.of(2026, 11, 30)

    @Test fun weeklyAverageUsesFirstAcceptedScoreOfEachDay() {
        val progress = CollectionProgress()
            .recordChallenge(monday.minusDays(1), 100)
            .recordChallenge(monday, 70)
            .recordChallenge(monday, 90)
            .recordChallenge(monday.plusDays(1), 84)
            .recordChallenge(monday.plusDays(2), 100)
        assertEquals(77, progress.weeklyScoreAverage(monday.plusDays(1)))
        assertEquals(null, progress.weeklyScoreAverage(monday.plusWeeks(1)))
    }

    @Test fun oneDayCountsOnceAndKeepsItsFirstScore() {
        val progress = CollectionProgress()
            .recordChallenge(monday, 80)
            .recordChallenge(monday, 95)
            .recordChallenge(monday, 85)
            .recordChallenge(monday.plusDays(1), 82)
        assertEquals(2, progress.challengeDays)
        assertEquals(2, progress.scoreDays(80))
        assertEquals(0, progress.scoreDays(95))
    }

    @Test fun weeksStartOnMondayAndCompletedRoutinesKeepTheirProgress() {
        val progress = CollectionProgress()
            .recordChallenge(monday.minusDays(1), 90)
            .recordChallenge(monday, 90)
            .recordChallenge(monday.plusDays(1), 80)
            .recordChallenge(monday.plusDays(2), 80)
            .recordChallenge(monday.plusWeeks(1), 75)
        assertEquals(3, progress.bestWeekDays)
        assertEquals(3, progress.challengeWeeks)
        assertEquals(2, progress.scoreWeeks(90))
    }

    @Test fun spotVisitsAreIndependentAndOncePerKoreanCalendarDay() {
        val progress = CollectionProgress()
            .recordVisit(SpotTour.SEOUL, monday)
            .recordVisit(SpotTour.SEOUL, monday)
            .recordVisit(SpotTour.SEOUL, monday.plusDays(1))
            .recordVisit(SpotTour.GOYANG, monday)
        assertEquals(2, progress.visits(SpotTour.SEOUL))
        assertEquals(1, progress.visits(SpotTour.GOYANG))
        assertEquals(0, progress.visits(SpotTour.BUSAN))
        assertEquals(0, progress.challengeDays)
    }

    @Test fun spotMatchingUsesTheDestinationLocationOrExactNormalizedName() {
        val spot = SpotTour.GOYANG
        assertTrue(spot.matches("현대모터스튜디오 고양", null))
        assertTrue(spot.matches("현대 모터스튜디오 고양 주차장", spot.location))
        assertFalse(spot.matches("현대모터스튜디오 고양", SpotTour.BUSAN.location))
        assertFalse(spot.matches("현대 모터스튜디오 서울", null))
    }
}
