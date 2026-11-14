package ai.musaz.h1score

import ai.musaz.h1score.core.*
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class RankingBadgeRewardsTest {
    @Test fun twelveRankingQuestsKeepEachExistingRewardAndDoNotAwardTwice() {
        var ranks = RankBadgeProgress()
        val rewards = mutableMapOf<String, Int>()
        val firstDay = LocalDate.of(2026, 11, 2)
        repeat(45) { index ->
            val next = ranks.record(firstDay.plusDays(index.toLong()), 1, 10)
            newlyEarnedCollectionBadges(CollectionProgress(), ranks, CollectionProgress(), next).forEach {
                assertNull(rewards.put(it.id, it.mileage))
            }
            ranks = next
        }
        assertEquals(mapOf(
            "rank-90" to 5, "score-80-5" to 10, "rank-80" to 10,
            "rank-50" to 15, "rank-30" to 20, "score-80-10" to 15,
            "rank-10-3" to 25, "score-90-5" to 15, "score-90-10" to 25,
            "rank-10-10" to 35, "score-95-5" to 35, "routine-score" to 20), rewards)
        assertNull(ranks.activeTier)
        assertTrue(newlyEarnedCollectionBadges(CollectionProgress(), ranks, CollectionProgress(),
            ranks.record(firstDay.plusDays(45), 1, 10)).isEmpty())
    }

    @Test fun oldHighScoresDoNotBecomeRankingQuests() {
        val before = CollectionProgress()
        val after = CollectionProgress((0L..29L).associate { LocalDate.of(2026, 10, 1).plusDays(it) to 100 })
        val awards = newlyEarnedCollectionBadges(before, RankBadgeProgress(), after, RankBadgeProgress())
        assertTrue(awards.none { award -> RankBadgeGoal.entries.any { it.id == award.id } })
    }
}
