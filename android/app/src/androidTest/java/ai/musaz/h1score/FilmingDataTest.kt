package ai.musaz.h1score

import ai.musaz.h1score.core.*
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.util.UUID

class FilmingDataTest {
    @Test fun historyMatchesCollectionAndFifthDayAwardsOnce() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "filming-test-${UUID.randomUUID()}"
        val prefs = context.getSharedPreferences(name, 0)
        try {
            val today = LocalDate.of(2026, 11, 14)
            seedFilmingHistory(prefs)
            val before = CollectionProgressStore(prefs).read(false)
            val records = CompletedDriveStore(prefs).readAll()
            assertEquals(15, records.size)
            assertTrue(records.none { "시연" in it.name })
            assertTrue(records.filter { it.outcome == DriveOutcome.COMPLETED }.all { it.ranking.size == 5 })
            assertTrue(records.filter { it.outcome == DriveOutcome.DISQUALIFIED }.all { it.ranking.isEmpty() })
            val own = filmingRanking(61).single { it.id == "나" }
            assertEquals(5, own.rank)
            assertEquals(0, own.miles)
            assertEquals(4, before.challengeDays)
            assertTrue(records.filter { it.outcome == DriveOutcome.COMPLETED }.all { it.report.valid && it.report.score != null })
            records.filter { it.outcome == DriveOutcome.COMPLETED }.groupBy { it.participationDay }.forEach { (day, daily) ->
                assertEquals(3, daily.size)
                assertEquals(daily.minBy { it.completedAtEpochMs!! }.report.score, before.scoresByDay[LocalDate.parse(day)])
            }
            val ranks = RankBadgeProgress()
            val after = before.recordChallenge(today, 80)
            val awards = newlyEarnedCollectionBadges(before, ranks, after, ranks)
            assertEquals(listOf("join-5"), awards.map { it.id })
            assertEquals(15, awards.sumOf { it.mileage })
            assertTrue(newlyEarnedCollectionBadges(after, ranks, after.recordChallenge(today, 95), ranks).isEmpty())
            val failed = records.first().copy(outcome = DriveOutcome.DISQUALIFIED)
            assertEquals(ResultRecognition.DISQUALIFIED, recognizeResult(failed, true, true))
            seedFilmingHistory(prefs)
            assertEquals(records, CompletedDriveStore(prefs).readAll())
            assertEquals(before, CollectionProgressStore(prefs).read(false))
            val approach = ScoreEngine.evaluate(filmingApproachSamples(1_000_000L))
            assertTrue(approach.valid)
            assertEquals(0, approach.accelerationEvents)
            assertEquals(0, approach.decelerationEvents)
            assertEquals(3, records.count { it.outcome == DriveOutcome.DISQUALIFIED })
            assertEquals(setOf("2026-11-06", "2026-11-08", "2026-11-09", "2026-11-10", "2026-11-11", "2026-11-12", "2026-11-13"), records.map { it.participationDay }.toSet())
            assertTrue(records.map { it.name }.distinct().size >= 3)
            assertEquals(0, prefs.getInt("balance-live", 0))
            assertEquals(0.0, filmingApproachSamples(1_000_000L).last().speedMps!!, 0.0)
        } finally { context.deleteSharedPreferences(name) }
    }
}
