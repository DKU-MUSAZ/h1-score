package ai.musaz.h1score

import ai.musaz.h1score.core.ChallengeRound
import ai.musaz.h1score.core.ChallengeSession
import ai.musaz.h1score.core.DrivingEvent
import ai.musaz.h1score.core.DrivingEventType
import ai.musaz.h1score.core.Phase
import ai.musaz.h1score.core.RankedEntry
import ai.musaz.h1score.core.ScoreReport
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelStore
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.util.UUID

class DriveHistoryPersistenceTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()
    private val applications = mutableListOf<HistoryTestApplication>()
    private val models = mutableListOf<ViewModelStore>()

    @After fun cleanIsolatedState() {
        ui.runOnIdle { models.forEach(ViewModelStore::clear) }
        applications.forEach { it.prefs.edit().clear().commit() }
    }

    @Test fun newStoreRestoresBothDrivesWithTheirOwnEventsAndResults() {
        val app = application()
        val first = drive("first", 1_000L, false, 91)
        val second = drive("second", 2_000L, true, 78)
        CompletedDriveStore(app.prefs).apply { save(first); save(second) }

        val restored = CompletedDriveStore(app.prefs)
        assertEquals(listOf(second, first), restored.readAll())
        assertEquals(second, restored.read())
        assertEquals(setOf(false, true), restored.readAll().map { it.demo }.toSet())
    }

    @Test fun savingTheSameSessionAgainUpdatesItWithoutLosingAnotherDriveOrItsDate() {
        val app = application()
        val first = drive("first", 1_000L)
        val second = drive("second", 2_000L)
        val store = CompletedDriveStore(app.prefs)
        store.save(first)
        store.save(second)
        store.save(first.copy(name = "다시 저장된 첫 목적지", completedAtEpochMs = null))

        val restored = CompletedDriveStore(app.prefs).readAll()
        assertEquals(listOf("second", "first"), restored.map { it.session.id })
        assertEquals(1_000L, restored.last().completedAtEpochMs)
        assertEquals("다시 저장된 첫 목적지", restored.last().name)
    }

    @Test fun legacyResultWithoutEventsOrDateSurvivesTheFirstNewSave() {
        val app = application()
        val old = drive("legacy", null).copy(report = drive("legacy", null).report.copy(events = emptyList()))
        app.prefs.edit().putString("last-completed-drive-v1", legacyJson(old).toString()).commit()

        val store = CompletedDriveStore(app.prefs)
        assertEquals(listOf(old), store.readAll())
        assertNull(store.readAll().single().completedAtEpochMs)
        val next = drive("new", 3_000L)
        store.save(next)

        val restored = CompletedDriveStore(app.prefs).readAll()
        assertEquals(listOf("new", "legacy"), restored.map { it.session.id })
        assertNull(restored.last().completedAtEpochMs)
        assertTrue(restored.last().report.events.isEmpty())
        assertEquals("speed70-accel30-v1", restored.last().scoringVersion)
    }

    @Test fun aMalformedRecordDoesNotHideOtherSavedDrives() {
        val app = application()
        val first = drive("first", 1_000L)
        val second = drive("second", 2_000L)
        CompletedDriveStore(app.prefs).apply { save(first); save(second) }
        val rows = JSONArray(app.prefs.getString("completed-drives-v1", null)!!)
        rows.put(JSONObject().put("id", "damaged-record"))
        app.prefs.edit().putString("completed-drives-v1", rows.toString()).commit()

        assertEquals(listOf(second, first), CompletedDriveStore(app.prefs).readAll())
    }

    @Test fun ordinaryHistoryEntryOnANewModelShowsBothModesAndEachSelectedResult() {
        val app = application()
        val first = drive("first", 1_000L, false, 91)
        val second = drive("second", 2_000L, true, 78)
        CompletedDriveStore(app.prefs).apply { save(first); save(second) }
        val vm = ui.runOnIdle { model(app).apply { receiveVehicle(parkedVehicle()); navigate(Screen.HISTORY) } }
        val originalPreferences = app.prefs.all.toMap()
        ui.setContent { H1App(vm) }

        ui.onNodeWithText(first.name).performScrollTo().assertIsDisplayed()
        ui.onNodeWithText(second.name).performScrollTo().assertIsDisplayed().performClick()
        ui.runOnIdle {
            assertEquals(second, vm.selectedHistoryRecord)
            assertEquals(Screen.RESULT, vm.screen)
            assertNull(vm.session)
            assertNull(vm.report)
            assertFalse(vm.demo)
        }
        ui.onNodeWithText(second.name).assertIsDisplayed()
        ui.onNodeWithText("78").assertIsDisplayed()

        ui.runOnIdle { vm.navigate(Screen.HISTORY) }
        ui.onNodeWithText(first.name).performScrollTo().performClick()
        ui.runOnIdle { assertEquals(first, vm.selectedHistoryRecord) }
        ui.onNodeWithText(first.name).assertIsDisplayed()
        ui.onNodeWithText("91").assertIsDisplayed()
        assertEquals(originalPreferences, app.prefs.all)
    }

    @Test fun historyDatesAndSortControlsPreserveRecordSelectionAndSavedData() {
        val app = application()
        val first = drive("earlier", Instant.parse("2026-11-10T23:35:00Z").toEpochMilli(), false, 91)
        val second = drive("later", Instant.parse("2026-11-11T00:10:00Z").toEpochMilli(), true, 78)
        CompletedDriveStore(app.prefs).apply { save(first); save(second) }
        val vm = ui.runOnIdle { model(app).apply { receiveVehicle(parkedVehicle()); navigate(Screen.HISTORY) } }
        val originalPreferences = app.prefs.all.toMap()
        val originalRecords = vm.completedDrives.toList()
        ui.setContent { H1App(vm) }

        ui.onNodeWithText("주행일 2026.11.11 08:35").assertIsDisplayed()
        ui.onNodeWithText("주행일 2026.11.11 09:10").assertIsDisplayed()
        ui.onNodeWithText("최신순").assertIsSelected()
        assertHistoryAbove(second, first)

        ui.onNodeWithText("오래된순").performClick().assertIsSelected()
        assertHistoryAbove(first, second)
        ui.onNodeWithText(first.name).performClick()
        ui.runOnIdle {
            assertEquals(first, vm.selectedHistoryRecord)
            assertEquals(Screen.RESULT, vm.screen)
        }
        ui.onNodeWithText(first.name).assertIsDisplayed()
        ui.onNodeWithText("91").assertIsDisplayed()

        ui.onNodeWithText("내 기록").performClick()
        ui.onNodeWithText("오래된순").assertIsSelected()
        assertHistoryAbove(first, second)
        ui.onNodeWithText("최신순").performClick().assertIsSelected()
        assertHistoryAbove(second, first)
        ui.runOnIdle { assertEquals(originalRecords, vm.completedDrives) }
        assertEquals(originalPreferences, app.prefs.all)
    }

    @Test fun openingHistoryDoesNotReplaceAnActiveJourneyOrCreditRewardsAgain() {
        val app = application()
        val saved = drive("saved", 1_000L, false)
        CompletedDriveStore(app.prefs).save(saved)
        app.prefs.edit().putInt("balance", 140)
            .putStringSet("settled", setOf("saved"))
            .putStringSet("badges-demo", setOf("first-drive"))
            .putLong("recorded-distance-demo", 12_345L).commit()
        ui.runOnIdle {
            val vm = model(app).apply { receiveVehicle(parkedVehicle()); switchSource() }
            val now = Instant.now()
            vm.accept(ChallengeRound("진행 중", now.minusSeconds(30), now.plusSeconds(600)))
            assertTrue(vm.activeJourney)
            val session = vm.session
            val report = vm.report
            val remaining = vm.remainingM
            val preferences = app.prefs.all.toMap()

            vm.openHistoryRecord(saved.session.id)
            assertEquals(saved, vm.selectedHistoryRecord)
            assertEquals(session, vm.session)
            assertEquals(report, vm.report)
            assertEquals(remaining, vm.remainingM, 0.0)
            assertTrue(vm.demo)
            assertTrue(vm.activeJourney)
            assertEquals(140, vm.balance)
            assertEquals(preferences, app.prefs.all)
        }
    }

    @Test fun historySelectionStillRequiresParking() {
        val app = application()
        val saved = drive("saved", 1_000L)
        CompletedDriveStore(app.prefs).save(saved)
        ui.runOnIdle {
            val vm = model(app).apply { receiveVehicle(parkedVehicle().copy(gearPark = false)) }
            vm.openHistoryRecord(saved.session.id)
            assertNull(vm.selectedHistoryRecord)
            assertEquals(Screen.HOME, vm.screen)
            vm.receiveVehicle(parkedVehicle())
            vm.openHistoryRecord(saved.session.id)
            assertEquals(saved, vm.selectedHistoryRecord)
        }
    }

    private fun drive(id: String, time: Long?, demo: Boolean = true, score: Int = 91): CompletedDrive {
        val events = listOf(DrivingEvent(DrivingEventType.OVERSPEED, 15_000, 321.0, 70.0, 83.0, 80))
        return CompletedDrive(
            ChallengeSession(id, "destination-$id", 14_000.0, Phase.FINISHED,
                venueId = "venue-$id", competitionId = "round-$id"),
            ScoreReport(score, 88.0, 98.0, 10_300.0, 1.0, true, 0, 321.0, events),
            "저장 목적지 $id", demo, listOf(RankedEntry("나", score, 1, 80)), 80, false,
            completedAtEpochMs = time,
        )
    }

    private fun assertHistoryAbove(above: CompletedDrive, below: CompletedDrive) {
        val first = ui.onNodeWithText(above.name).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val second = ui.onNodeWithText(below.name).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("${above.name} should appear before ${below.name}", first.top < second.top)
    }

    /** Exact legacy shape: no history fields and no PR4 event array. */
    private fun legacyJson(value: CompletedDrive) = JSONObject().apply {
        val session = value.session
        val report = value.report
        put("id", session.id); put("destinationId", session.destinationId)
        put("initialDistanceM", session.initialDistanceM)
        put("venueId", session.venueId); put("competitionId", session.competitionId)
        put("name", value.name); put("demo", value.demo); put("earned", value.earned)
        put("missedDeadline", value.missedDeadline)
        put("score", report.score); put("compliance", report.compliance); put("smoothness", report.smoothness)
        put("distance", report.validDistanceM); put("coverage", report.coverage); put("valid", report.valid)
        put("accelerationEvents", report.accelerationEvents); put("overLimitDistance", report.overLimitDistanceM)
        put("ranking", JSONArray().apply { value.ranking.forEach { row ->
            put(JSONObject().put("id", row.id).put("score", row.score).put("rank", row.rank).put("miles", row.miles))
        } })
    }

    private fun parkedVehicle() = LiveVehicle(speedMps = 0.0, speedAt = Long.MAX_VALUE,
        gearPark = true, uxRestricted = false, belt = true)

    private fun application() = HistoryTestApplication(ui.activity.applicationContext).also(applications::add)

    private fun model(app: Application) = ChallengeViewModel(app).also { vm ->
        models += ViewModelStore().apply { put("history-test", vm) }
    }

    private class HistoryTestApplication(context: Context) : Application() {
        init { attachBaseContext(context) }
        val prefs: SharedPreferences = context.getSharedPreferences("test-history-${UUID.randomUUID()}", MODE_PRIVATE)
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = prefs
        override fun startForegroundService(service: Intent): ComponentName? = service.component
        override fun stopService(name: Intent): Boolean = true
        override fun startActivity(intent: Intent) = Unit
    }
}
