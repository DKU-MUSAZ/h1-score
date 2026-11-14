package ai.musaz.h1score.core

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class RankBadgeProgressTest {
    private val firstDay = LocalDate.of(2026, 11, 24)

    @Test fun everyTierHasThreeQuests() {
        assertEquals(12, RankBadgeGoal.entries.size)
        RankTier.entries.forEach { tier ->
            assertEquals(3, RankBadgeGoal.entries.count { it.tier == tier })
        }
    }

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
        repeat(10) { index ->
            progress = progress.record(firstDay.plusDays(10L + index), 1, 10)
        }
        assertEquals(RankTier.GOLD, progress.activeTier)
        assertEquals(0, progress.days(RankBadgeGoal.TOP_REGULAR))
        repeat(10) { index ->
            progress = progress.record(firstDay.plusDays(20L + index), 1, 10)
        }
        assertEquals(RankTier.PLATINUM, progress.activeTier)
        assertEquals(0, progress.days(RankBadgeGoal.ACE))
        repeat(15) { index ->
            progress = progress.record(firstDay.plusDays(30L + index), 1, 10)
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

    @Test fun twoBronzeQuestsCannotOpenSilverAndPromotionDoesNotReuseTheSameDay() {
        val progress = RankBadgeProgress(mapOf(
            RankBadgeGoal.FIRST_RANK to 5, RankBadgeGoal.RANK_ADAPTED to 10,
            RankBadgeGoal.CALM_START to 4))
        assertFalse(progress.isUnlocked(RankTier.SILVER))
        val promoted = progress.record(firstDay, 1, 10)
        assertEquals(RankTier.SILVER, promoted.activeTier)
        assertEquals(0, promoted.days(RankBadgeGoal.MIDFIELD))
        assertEquals(promoted, promoted.record(firstDay, 1, 10))
        assertEquals(1, promoted.record(firstDay.plusDays(1), 1, 10).days(RankBadgeGoal.MIDFIELD))
    }

    @Test fun platinumWeeklyQuestCountsDistinctMondayWeeksOnlyAfterItOpens() {
        var progress = RankBadgeProgress(RankBadgeGoal.entries
            .filter { it.tier < RankTier.PLATINUM }.associateWith { it.targetDays })
        val sunday = LocalDate.of(2026, 12, 6)
        progress = progress.record(sunday.minusDays(1), 1, 10)
        progress = progress.record(sunday, 1, 10)
        assertEquals(2, progress.days(RankBadgeGoal.ACE))
        assertEquals(1, progress.days(RankBadgeGoal.CONSISTENT_FLOW))
        progress = progress.record(sunday.plusDays(1), 1, 10)
        assertEquals(2, progress.days(RankBadgeGoal.CONSISTENT_FLOW))
        progress = progress.record(sunday.plusDays(8), 2, 10)
        assertEquals(2, progress.days(RankBadgeGoal.CONSISTENT_FLOW))
        progress = progress.record(sunday.plusDays(9), 1, 10)
        assertEquals(3, progress.days(RankBadgeGoal.CONSISTENT_FLOW))
        assertEquals(RankTier.PLATINUM, progress.activeTier)
        assertEquals(setOf(sunday.minusDays(6), sunday.plusDays(1), sunday.plusDays(8)),
            progress.weeksByGoal[RankBadgeGoal.CONSISTENT_FLOW])
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
        repeat(20) { index ->
            progress = progress.record(firstDay.plusDays(index.toLong()), 1, 5, demo = true)
        }
        assertEquals(RankTier.GOLD, progress.activeTier)
        val nextDay = firstDay.plusDays(20)
        assertEquals(0, progress.record(nextDay, 2, 5, demo = true).days(RankBadgeGoal.TOP_REGULAR))
        assertEquals(1, progress.record(nextDay, 1, 5, demo = true).days(RankBadgeGoal.TOP_REGULAR))
    }
}
