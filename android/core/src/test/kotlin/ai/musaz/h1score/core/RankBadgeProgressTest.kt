package ai.musaz.h1score.core

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class RankBadgeProgressTest {
    private val firstDay = LocalDate.of(2026, 11, 24)

    @Test fun laterTiersStartOnTheNextEligibleDay() {
        var progress = RankBadgeProgress()
        repeat(10) { index ->
            progress = progress.record(firstDay.plusDays(index.toLong()), rank = 1, participants = 10)
        }
        assertEquals(RankTier.SILVER, progress.activeTier)
        assertEquals(5, progress.days(RankBadgeGoal.FIRST_RANK))
        assertEquals(10, progress.days(RankBadgeGoal.RANK_ADAPTED))
        assertEquals(0, progress.days(RankBadgeGoal.MIDFIELD))
        assertEquals(progress, progress.record(firstDay.plusDays(9), 1, 10))
        repeat(5) { index ->
            progress = progress.record(firstDay.plusDays(10L + index), 1, 10)
        }
        assertEquals(RankTier.GOLD, progress.activeTier)
        assertEquals(0, progress.days(RankBadgeGoal.TOP_REGULAR))
        repeat(3) { index ->
            progress = progress.record(firstDay.plusDays(15L + index), 1, 10)
        }
        assertEquals(RankTier.PLATINUM, progress.activeTier)
        assertEquals(0, progress.days(RankBadgeGoal.ACE))
        repeat(10) { index ->
            progress = progress.record(firstDay.plusDays(18L + index), 1, 10)
        }
        assertNull(progress.activeTier)
    }

    @Test fun aSingleBronzeBadgeCannotOpenSilver() {
        var progress = RankBadgeProgress()
        repeat(5) { index ->
            progress = progress.record(firstDay.plusDays(index.toLong()), rank = 9, participants = 10)
        }
        assertEquals(5, progress.days(RankBadgeGoal.FIRST_RANK))
        assertEquals(0, progress.days(RankBadgeGoal.RANK_ADAPTED))
        assertEquals(RankTier.BRONZE, progress.activeTier)
        assertFalse(progress.isUnlocked(RankTier.SILVER))
    }

    @Test fun smallGroupsOnlyCountForDemoAndInvalidRanksNeverCount() {
        val progress = RankBadgeProgress()
        assertEquals(progress, progress.record(firstDay, 1, 5))
        assertEquals(progress, progress.record(firstDay, 0, 10))
        assertEquals(progress, progress.record(firstDay, 11, 10))
        assertEquals(1, progress.record(firstDay, 1, 5, demo = true).days(RankBadgeGoal.FIRST_RANK))
    }

    @Test fun firstPlaceCanAdvanceTopTenPercentGoalInFivePersonDemo() {
        var progress = RankBadgeProgress()
        repeat(15) { index ->
            progress = progress.record(firstDay.plusDays(index.toLong()), 1, 5, demo = true)
        }
        assertEquals(RankTier.GOLD, progress.activeTier)
        val nextDay = firstDay.plusDays(15)
        assertEquals(progress, progress.record(nextDay, 2, 5, demo = true))
        assertEquals(1, progress.record(nextDay, 1, 5, demo = true).days(RankBadgeGoal.TOP_REGULAR))
    }
}
