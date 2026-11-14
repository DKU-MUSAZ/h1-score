package ai.musaz.h1score

import ai.musaz.h1score.core.*
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class BeltOutcomePersistenceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val prefs = context.getSharedPreferences("belt-outcome-${UUID.randomUUID()}", Context.MODE_PRIVATE)

    @After fun clearIsolatedPreferences() { prefs.edit().clear().commit() }

    @Test fun disqualificationSurvivesReopeningLatestAndHistoryWithoutAZeroScore() {
        val saved = drive(DriveOutcome.DISQUALIFIED, BeltFailureReason.PASSENGER_UNBUCKLED)
        CompletedDriveStore(prefs).save(saved)
        assertEquals(saved, CompletedDriveStore(prefs).read())
        assertEquals(saved, CompletedDriveStore(prefs).readAll().single())
        assertEquals(Phase.DISQUALIFIED, CompletedDriveStore(prefs).read()!!.session.phase)
        assertNull(CompletedDriveStore(prefs).read()!!.report.score)
    }

    @Test fun unverifiableTripRetainsReasonWithoutBecomingDisqualified() {
        val saved = drive(DriveOutcome.UNVERIFIABLE, BeltFailureReason.TELEMETRY_UNAVAILABLE)
        CompletedDriveStore(prefs).save(saved)
        assertEquals(saved, CompletedDriveStore(prefs).readAll().single())
        assertEquals(Phase.FINISHED, CompletedDriveStore(prefs).read()!!.session.phase)
    }

    @Test fun legacyRecordsWithoutOutcomeFieldsKeepTheirOriginalScore() {
        val saved = drive(DriveOutcome.COMPLETED, null).let {
            it.copy(report = it.report.copy(score = 91, valid = true))
        }
        CompletedDriveStore(prefs).save(saved)
        rewriteHistory { remove("outcome"); remove("beltFailureReason") }
        assertEquals(saved, CompletedDriveStore(prefs).readAll().single())
    }

    @Test fun unknownOrMissingReasonPreservesDisqualifiedRecord() {
        CompletedDriveStore(prefs).save(drive(DriveOutcome.DISQUALIFIED, BeltFailureReason.DRIVER_UNBUCKLED))
        rewriteHistory { put("beltFailureReason", "FUTURE_REASON") }
        val restored = CompletedDriveStore(prefs).readAll().single()
        assertEquals(DriveOutcome.DISQUALIFIED, restored.outcome)
        assertNull(restored.beltFailureReason)
        assertNotNull(restored.beltOutcomeReason)
        rewriteHistory { remove("beltFailureReason") }
        assertEquals(restored, CompletedDriveStore(prefs).readAll().single())
    }

    @Test fun unsupportedOutcomeRemainsInHistoryAsUnverifiable() {
        CompletedDriveStore(prefs).save(drive(DriveOutcome.UNVERIFIABLE, null))
        rewriteHistory { put("outcome", "FUTURE_OUTCOME") }
        val restored = CompletedDriveStore(prefs).readAll().single()
        assertEquals(DriveOutcome.UNVERIFIABLE, restored.outcome)
        assertNull(restored.report.score)
    }

    private fun rewriteHistory(change: org.json.JSONObject.() -> Unit) {
        val rows = JSONArray(prefs.getString("completed-drives-v1", null)!!)
        rows.getJSONObject(0).change()
        prefs.edit().putString("completed-drives-v1", rows.toString()).remove("last-completed-drive-v1").commit()
    }
    @Test fun maneuverReasonsAndObservationOnlyCandidatesSurviveReload() {
        val saved=drive(DriveOutcome.COMPLETED,null).let{it.copy(scoringVersion=ScoreEngine.VERSION,report=it.report.copy(
            scoringVersion=ScoreEngine.VERSION,
            turns=listOf(TurnEvent(1,2,TurnDirection.LEFT,TurnVerdict.LATE),
                TurnEvent(3,4,TurnDirection.RIGHT,TurnVerdict.UNVERIFIABLE,ManeuverKind.LANE_CHANGE,
                    TurnReason.LANE_OBSERVATION_ONLY,TurnVerdict.UNUSED)),
            turnDiagnostics=mapOf(TurnReason.GPS_UNAVAILABLE to 5)))}
        CompletedDriveStore(prefs).save(saved)
        val restored=CompletedDriveStore(prefs).readAll().single()
        assertEquals(saved,restored)
        assertEquals(1.0,restored.report.turnPenalty,0.0)
    }

    private fun drive(outcome: DriveOutcome, reason: BeltFailureReason?) = CompletedDrive(
        ChallengeSession("belt", "destination", 14_000.0,
            if (outcome == DriveOutcome.DISQUALIFIED) Phase.DISQUALIFIED else Phase.FINISHED),
        ScoreReport(null, 88.0, 98.0, 10_300.0, 1.0, false, 0, 321.0),
        "목적지", true, emptyList(), 0, false, completedAtEpochMs = 1_000L,
        outcome = outcome, beltFailureReason = reason,
    )
}
