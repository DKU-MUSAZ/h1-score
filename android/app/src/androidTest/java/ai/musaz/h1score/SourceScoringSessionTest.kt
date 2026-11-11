package ai.musaz.h1score

import ai.musaz.h1score.core.ChallengeRound
import ai.musaz.h1score.core.Phase
import ai.musaz.h1score.core.Sample
import ai.musaz.h1score.core.ScoreEngine
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.util.UUID

/** Source-branch session policies, isolated from persisted user history and collection data. */
class SourceScoringSessionTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()
    private val stores = mutableListOf<ViewModelStore>()
    private val apps = mutableListOf<TestApplication>()

    @After fun cleanup() {
        ui.runOnIdle { stores.forEach(ViewModelStore::clear) }
        apps.forEach { it.prefs.edit().clear().commit() }
    }

    @Test fun unbucklingPermanentlyDisqualifiesReservedAndRecordingSessions() {
        ui.runOnIdle {
            for (recording in listOf(false, true)) {
                val app = application()
                val vm = model(app)
                startChallenge(vm, recording)
                val before = app.prefs.all.toMap()
                val now = SystemClock.elapsedRealtime()
                vm.receiveVehicle(vm.live.copy(belt = false, beltAt = now), now)
                assertEquals(Phase.DISQUALIFIED, vm.phase)
                assertFalse(vm.activeJourney)
                assertNull(vm.report)
                assertTrue(vm.ranking.isEmpty())
                assertEquals(0, vm.earned)
                assertTrue(vm.pendingBadges.isEmpty())

                // Re-buckling and a later arrival cannot restore or settle the disqualified trip.
                vm.receiveVehicle(vm.live.copy(belt = true, beltAt = now, arrived = true,
                    arrivedAt = Instant.now(), remainingM = 0.0), now)
                assertEquals(Phase.DISQUALIFIED, vm.phase)
                assertEquals(0, vm.completed)
                assertTrue(CompletedDriveStore(app.prefs).readAll().isEmpty())
                assertEquals(before, app.prefs.all)
            }
        }
    }

    @Test fun staleOrUnknownBeltIsNotProofOfUnbuckling() {
        ui.runOnIdle {
            val vm = model(application())
            startChallenge(vm, recording = true)
            val now = SystemClock.elapsedRealtime()
            vm.receiveVehicle(vm.live.copy(belt = false, beltAt = now - 2_501,
                beltWarningOnAt = null, beltWarningOffAt = null), now)
            assertEquals(Phase.RECORDING, vm.phase)
            vm.receiveVehicle(vm.live.copy(belt = null, beltAt = now), now)
            assertEquals(Phase.RECORDING, vm.phase)
            assertNull(vm.report)
            assertEquals(0, vm.completed)
        }
    }

    @Test fun consoleUsesFrozenTestLimitAndNeverWritesProductionProgress() {
        ui.runOnIdle {
            val app = application()
            app.prefs.edit().putInt("balance", 80).putLong("recorded-distance-live", 500)
                .putStringSet("collection-scores-live", setOf("2026-11-01=92"))
                .putStringSet("badges-live", setOf("existing-badge")).commit()
            val vm = model(app)
            assumeTrue("Explicit console tests are available only on a debug emulator", vm.consoleTestAvailable)
            vm.receiveVehicle(parked())
            vm.chooseConsoleLimit(80)
            val before = app.prefs.all.toMap()
            vm.startConsoleTest()
            assertTrue(vm.consoleTest)
            assertEquals(Phase.RECORDING, vm.phase)
            assertNull(vm.progressReport.score)
            vm.chooseConsoleLimit(50)
            assertEquals(80, vm.consoleSessionLimitKph)

            // A real navigation cancellation or unbuckled reading is not the test context.
            vm.receiveVehicle(parked().copy(routeCancelled = true, belt = false))
            assertEquals(Phase.RECORDING, vm.phase)
            val start = SystemClock.elapsedRealtime() + 10
            // 36km/h exceeds the supplied raw 10km/h limit, but the explicit test limit is 80.
            for (i in 0..10_000) vm.receiveSample(Sample(start + i * 100L, 10.0, 10))
            vm.receiveVehicle(parked())
            vm.finishConsoleTest()

            assertEquals(Phase.FINISHED, vm.phase)
            assertEquals(100, vm.report?.score)
            assertEquals(ScoreEngine.VERSION, vm.report?.scoringVersion)
            assertEquals(25.0, vm.report!!.speedPoints, 1e-8)
            assertEquals(15.0, vm.report!!.accelerationPoints, 1e-8)
            assertEquals(0, vm.completed)
            assertEquals(80, vm.balance)
            assertEquals(500L, vm.recordedDistanceMeters)
            assertEquals(1, vm.collectionProgress.challengeDays)
            assertEquals(setOf("existing-badge"), vm.ownedBadges)
            assertTrue(vm.pendingBadges.isEmpty())
            assertTrue(vm.ranking.isEmpty())
            assertEquals(0, vm.earned)
            assertTrue(CompletedDriveStore(app.prefs).readAll().isEmpty())
            assertEquals(before, app.prefs.all)
        }
    }

    @Test fun emptyConsoleResultStaysUnscored() {
        ui.runOnIdle {
            val app = application()
            val vm = model(app)
            assumeTrue(vm.consoleTestAvailable)
            vm.receiveVehicle(parked())
            vm.startConsoleTest()
            vm.finishConsoleTest()
            assertEquals(Phase.FINISHED, vm.phase)
            assertNull(vm.report?.score)
            assertFalse(vm.report!!.valid)
            assertEquals(0, vm.completed)
            assertEquals(0, vm.collectionProgress.challengeDays)
            assertTrue(CompletedDriveStore(app.prefs).readAll().isEmpty())
        }
    }

    private fun startChallenge(vm: ChallengeViewModel, recording: Boolean) {
        val now = SystemClock.elapsedRealtime()
        val spot = challengeSpots.first()
        val route = parked().copy(destination = spot.name, destinationId = "source-session-route",
            destinationPoint = spot.point, remainingM = 12_000.0, destinationAt = now)
        vm.receiveVehicle(route, now)
        val instant = Instant.now()
        vm.accept(ChallengeRound("원본 점수 세션 검증", instant.minusSeconds(60), instant.plusSeconds(3_600)))
        assertEquals(Phase.RESERVED, vm.phase)
        if (recording) {
            vm.receiveVehicle(route.copy(remainingM = 10_000.0), now)
            assertEquals(Phase.RECORDING, vm.phase)
        }
    }

    private fun parked(): LiveVehicle {
        val now = SystemClock.elapsedRealtime()
        return LiveVehicle(speedMps = 0.0, speedAt = now, gearPark = true,
            uxRestricted = false, belt = true, beltAt = now)
    }

    private fun application() = TestApplication(ui.activity.applicationContext).also(apps::add)
    private fun model(app: Application) = ChallengeViewModel(app).also { vm ->
        stores += ViewModelStore().apply { put("source-score-test", vm) }
    }

    private class TestApplication(context: Context) : Application() {
        init { attachBaseContext(context) }
        val prefs: SharedPreferences = context.getSharedPreferences("test-source-score-${UUID.randomUUID()}", MODE_PRIVATE)
        override fun getSharedPreferences(name: String, mode: Int) = prefs
        override fun startForegroundService(service: Intent): ComponentName? = service.component
        override fun stopService(name: Intent) = true
        override fun startActivity(intent: Intent) = Unit
    }
}
