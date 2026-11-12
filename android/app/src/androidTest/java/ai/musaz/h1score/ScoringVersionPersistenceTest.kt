package ai.musaz.h1score

import ai.musaz.h1score.core.*
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class ScoringVersionPersistenceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val prefs = context.getSharedPreferences("score-version-test-${UUID.randomUUID()}", Context.MODE_PRIVATE)

    @After fun clearIsolatedPreferences() { prefs.edit().clear().commit() }

    @Test fun newBonusReportRoundTripsAllEvidenceBesideUnchangedLegacyScores() {
        val old81 = legacy("old-81", 81, 71.0, 3479.0, 1_000L)
        val old92 = legacy("old-92", 92, 89.0, 1134.0, 2_000L)
        val store = CompletedDriveStore(prefs)
        store.save(old81); store.save(old92)
        val oldRows = JSONArray(prefs.getString("completed-drives-v1", null)!!)
        for (i in 0 until oldRows.length()) oldRows.getJSONObject(i).remove("scoringVersion")
        prefs.edit().putString("completed-drives-v1", oldRows.toString())
            .remove("last-completed-drive-v1").commit()
        assertEquals(listOf(old92, old81), CompletedDriveStore(prefs).readAll())
        val samples = (0..10_000).map { i ->
            val phase = i % 100
            val speed = when {
                phase < 20 -> 10.0 + phase * 0.3
                phase < 40 -> 16.0 - (phase - 20) * 0.3
                else -> 10.0
            }
            Sample(i * 100L, speed, 50, brakePressed = phase in 20..39)
        }
        val turns = listOf(TurnEvent(2_000, 6_000, TurnDirection.RIGHT, TurnVerdict.UNUSED))
        val report = ScoreEngine.evaluate(samples, turns)
        assertNotNull(report.score)
        assertTrue(report.longitudinalEvents.isNotEmpty())
        assertTrue(report.events.isNotEmpty())
        assertTrue(report.kilometers.isNotEmpty())
        assertEquals(1.0, report.turnPenalty, 0.0)
        val fresh = CompletedDrive(ChallengeSession("fresh", "새 목적지", 14000.0, Phase.FINISHED), report,
            "새 목적지", true, emptyList(), 0, false, 3_000L)
        assertEquals(ScoreEngine.VERSION, fresh.scoringVersion)
        store.save(fresh)
        val restored = CompletedDriveStore(prefs).readAll()
        assertEquals(listOf(fresh, old92, old81), restored)
        assertEquals(fresh, CompletedDriveStore(prefs).read())
        assertEquals(listOf(ScoreEngine.VERSION, LEGACY_SCORING_VERSION, LEGACY_SCORING_VERSION), restored.map { it.scoringVersion })
        assertEquals(listOf(92, 81), restored.drop(1).map { it.report.score })
        assertEquals(restored.map { it.scoringVersion }, restored.map { it.report.scoringVersion })
    }

    @Test fun missingLegacyEventsRemainUnknownWithoutInventingNewScores() {
        val record = legacy("legacy", 92, 89.0, 1134.0, 1_000L)
        CompletedDriveStore(prefs).save(record)
        val rows = JSONArray(prefs.getString("completed-drives-v1", null)!!)
        rows.getJSONObject(0).apply { remove("events"); remove("scoringVersion") }
        prefs.edit().putString("completed-drives-v1", rows.toString()).remove("last-completed-drive-v1").commit()
        val restored = CompletedDriveStore(prefs).readAll().single()
        assertEquals(record, restored)
        assertFalse(restored.report.longitudinalReady)
        assertTrue(restored.report.longitudinalEvents.isEmpty())
        assertEquals(92, restored.report.score)
        assertEquals(LEGACY_SCORING_VERSION, restored.report.scoringVersion)
    }

    private fun legacy(id: String, score: Int, compliance: Double, over: Double, at: Long) = CompletedDrive(
        ChallengeSession(id, id, 14000.0, Phase.FINISHED),
        ScoreReport(score, compliance, 100.0, 10300.0, 1.0, true, 0, over),
        id, true, emptyList(), if (score == 92) 80 else 0, false, at)
}
