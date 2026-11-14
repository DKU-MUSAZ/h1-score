package ai.musaz.h1score

import ai.musaz.h1score.core.BeltVerdict
import ai.musaz.h1score.core.ChallengeRound
import ai.musaz.h1score.core.Phase
import ai.musaz.h1score.core.Sample
import android.app.Application
import android.car.VehicleGear
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
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.util.UUID

/** Belt telemetry has its own clock and signed speed; scoring fixtures remain unchanged. */
class SeatBeltSessionTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()
    private val stores = mutableListOf<ViewModelStore>()
    private val apps = mutableListOf<TestApplication>()

    @After fun cleanup() {
        ui.runOnIdle { stores.forEach(ViewModelStore::clear) }
        apps.forEach { it.prefs.edit().clear().commit() }
    }

    @Test fun reservationRequiresFreshDriverBuckleWithoutConsumingTheDay() = ui.runOnIdle {
        val variants = listOf<(LiveVehicle) -> LiveVehicle>(
            { it.copy(belt = false) }, { it.copy(belt = null) },
            { it.copy(beltAt = it.beltAt - 2501) },
            { it.copy(belt = null, beltWarningOffAt = it.beltAt) },
        )
        for (missing in variants) {
            val app = application()
            val vm = model(app)
            val now = SystemClock.elapsedRealtime()
            val spot = challengeSpots.first()
            val route = telemetry(LiveVehicle(), now).copy(destination = spot.name,
                destinationId = "entry-belt-${UUID.randomUUID()}", destinationPoint = spot.point,
                destinationAt = now, remainingM = 12_000.0)
            val instant = Instant.now()
            val round = ChallengeRound("참가 조건 검증", instant.minusSeconds(60), instant.plusSeconds(3600))
            vm.receiveVehicle(missing(route), now)
            vm.accept(round)
            assertFalse("Unbuckled, missing or stale driver belt must block reservation", vm.activeJourney)
            vm.joinMapChallenge(round, spot)
            assertFalse(vm.awaitingMapRoute)
            assertTrue(DailyParticipationStore(app.prefs).available(false,
                instant.atZone(ChallengeRound.seoul).toLocalDate()))
            begin(vm, recording = false)
            assertEquals(Phase.RESERVED, vm.phase)
        }
    }

    @Test fun acceptedReservationAllowsUnbucklingWhileParkedOrStoppedInDriveAndReverse() = ui.runOnIdle {
        val vm = model(application())
        begin(vm, recording = false)
        val start = SystemClock.elapsedRealtime()
        listOf(VehicleGear.GEAR_PARK, VehicleGear.GEAR_DRIVE, VehicleGear.GEAR_REVERSE).forEachIndexed { index, gear ->
            val at = start + index * 250L
            vm.receiveVehicle(telemetry(vm.live, at, gear, 0.0, driver = false), at)
            assertEquals(Phase.RESERVED, vm.phase)
            assertEquals(BeltVerdict.SAFE, vm.beltDecision.verdict)
            assertFalse(vm.beltDecision.hadUnverifiedMotion)
        }
        assertTrue(vm.completedDrives.isEmpty())
        assertEquals(0, vm.earned)
    }

    @Test fun bothSeatsDisqualifyOnlyAfterDistinctHalfSecondEvidenceInDriveAndReverse() = ui.runOnIdle {
        val motions = listOf(VehicleGear.GEAR_DRIVE to 1.0,
            VehicleGear.GEAR_REVERSE to -1.0, VehicleGear.GEAR_REVERSE to 1.0)
        for ((gear, speed) in motions) for (passengerViolation in listOf(false, true)) {
            val app = application()
            val vm = model(app)
            begin(vm)
            val at = SystemClock.elapsedRealtime()
            val first = telemetry(vm.live, at, gear, speed,
                driver = passengerViolation, occupied = passengerViolation, passenger = false)
            vm.receiveVehicle(first, at)
            assertEquals(Phase.RECORDING, vm.phase)
            assertEquals(BeltVerdict.PENDING, vm.beltDecision.verdict)
            assertTrue(CompletedDriveStore(app.prefs).readAll().isEmpty())
            val second = telemetry(first, at + 500, gear, speed,
                driver = passengerViolation, occupied = passengerViolation, passenger = false)
            vm.receiveVehicle(second, at + 500)
            assertEquals(Phase.DISQUALIFIED, vm.phase)
            assertFalse(vm.activeJourney)
            val saved = CompletedDriveStore(app.prefs).readAll().single()
            assertEquals(DriveOutcome.DISQUALIFIED, saved.outcome)
            assertEquals(if (passengerViolation) BeltFailureReason.PASSENGER_UNBUCKLED else BeltFailureReason.DRIVER_UNBUCKLED,
                saved.beltFailureReason)
            assertNull(saved.report.score)
            assertFalse(saved.report.valid)
            assertNoSettlement(vm)
        }
    }

    @Test fun emptyPassengerNeverRequiresBuckleInDriveOrReverse() = ui.runOnIdle {
        for ((gear, speed) in listOf(VehicleGear.GEAR_DRIVE to 1.0, VehicleGear.GEAR_REVERSE to -1.0)) {
            val vm = model(application())
            begin(vm)
            val at = SystemClock.elapsedRealtime()
            for (offset in listOf(0L, 250L, 500L)) {
                vm.receiveVehicle(telemetry(vm.live, at + offset, gear, speed, passenger = false), at + offset)
                assertEquals(BeltVerdict.SAFE, vm.beltDecision.verdict)
                assertEquals(Phase.RECORDING, vm.phase)
            }
            assertFalse(vm.beltDecision.hadUnverifiedMotion)
        }
    }

    @Test fun cachedUnbuckledReadingCannotConfirmAndFasteningClearsPending() = ui.runOnIdle {
        val vm = model(application())
        begin(vm)
        val at = SystemClock.elapsedRealtime()
        vm.receiveVehicle(telemetry(vm.live, at, VehicleGear.GEAR_DRIVE, 1.0, driver = false), at)
        vm.receiveVehicle(telemetry(vm.live, at + 500, VehicleGear.GEAR_DRIVE, 1.0, driver = false)
            .copy(beltAt = at), at + 500)
        assertEquals(BeltVerdict.PENDING, vm.beltDecision.verdict)
        assertEquals(Phase.RECORDING, vm.phase)
        vm.receiveVehicle(telemetry(vm.live, at + 750, VehicleGear.GEAR_DRIVE, 1.0), at + 750)
        assertEquals(BeltVerdict.SAFE, vm.beltDecision.verdict)
        assertFalse(vm.beltDecision.hadUnverifiedMotion)
        assertTrue(vm.completedDrives.isEmpty())
    }

    @Test fun movingOccupancyChangeCannotExemptPreviouslyOccupiedPassenger() = ui.runOnIdle {
        val vm = model(application())
        begin(vm)
        val at = SystemClock.elapsedRealtime()
        vm.receiveVehicle(telemetry(vm.live, at, VehicleGear.GEAR_DRIVE, 1.0, occupied = true, passenger = true), at)
        vm.receiveVehicle(telemetry(vm.live, at + 250, VehicleGear.GEAR_DRIVE, 1.0, occupied = false, passenger = false), at + 250)
        assertEquals(BeltVerdict.UNVERIFIABLE, vm.beltDecision.verdict)
        assertTrue(vm.beltDecision.hadUnverifiedMotion)
        assertEquals(Phase.RECORDING, vm.phase)
        arrive(vm, at + 500)
        assertEquals(DriveOutcome.UNVERIFIABLE, vm.completedDrives.single().outcome)
        assertNoSettlement(vm)
    }

    @Test fun unknownMotionDataFinishesAsRetainedUnverifiableHistoryWithoutSettlement() = ui.runOnIdle {
        val missingSignals = listOf<(LiveVehicle) -> LiveVehicle>(
            { it.copy(belt = null) }, { it.copy(passengerOccupied = null, passengerBelt = true) },
            { it.copy(motionSpeedMps = null) }, { it.copy(gearCode = null) },
            { it.copy(beltAt = it.beltAt - 2501) }, { it.copy(motionSpeedAt = it.motionSpeedAt - 501) },
        )
        for (missing in missingSignals) {
            val app = application()
            val vm = model(app)
            begin(vm)
            val at = SystemClock.elapsedRealtime()
            vm.receiveVehicle(missing(telemetry(vm.live, at, VehicleGear.GEAR_DRIVE, 1.0)), at)
            assertEquals(BeltVerdict.UNVERIFIABLE, vm.beltDecision.verdict)
            assertEquals(Phase.RECORDING, vm.phase)
            assertTrue(vm.beltDecision.hadUnverifiedMotion)
            arrive(vm, at + 250)
            assertEquals(Phase.FINISHED, vm.phase)
            val saved = CompletedDriveStore(app.prefs).readAll().single()
            assertEquals(DriveOutcome.UNVERIFIABLE, saved.outcome)
            assertEquals(BeltFailureReason.TELEMETRY_UNAVAILABLE, saved.beltFailureReason)
            assertNull(saved.report.score)
            assertFalse(saved.report.valid)
            assertNoSettlement(vm)
            val persisted = app.prefs.all.toMap()
            repeat(2) {
                vm.openCompletedResult()
                assertEquals(saved, vm.selectedHistoryRecord)
                assertEquals(Phase.FINISHED, vm.phase)
                assertNull(vm.report!!.score)
                assertEquals(DriveOutcome.UNVERIFIABLE, vm.selectedHistoryRecord!!.outcome)
            }
            assertEquals(persisted, app.prefs.all)
        }
    }

    @Test fun disqualificationPersistsImmediatelyAndCannotBeRestoredByRebucklingArrivalOrReopening() = ui.runOnIdle {
        val app = application()
        val vm = model(app)
        begin(vm)
        val at = SystemClock.elapsedRealtime()
        vm.receiveVehicle(telemetry(vm.live, at, VehicleGear.GEAR_DRIVE, 1.0, driver = false), at)
        vm.receiveVehicle(telemetry(vm.live, at + 500, VehicleGear.GEAR_DRIVE, 1.0, driver = false), at + 500)
        val saved = CompletedDriveStore(app.prefs).readAll().single()
        val persisted = app.prefs.all.toMap()
        arrive(vm, at + 750)
        assertEquals(Phase.DISQUALIFIED, vm.phase)
        assertEquals(listOf(saved), CompletedDriveStore(app.prefs).readAll())
        stores.last().clear()
        val reopened = model(app)
        reopened.receiveVehicle(telemetry(LiveVehicle(), SystemClock.elapsedRealtime()))
        reopened.openCompletedResult()
        assertEquals(Phase.DISQUALIFIED, reopened.phase)
        assertEquals(saved, reopened.selectedHistoryRecord)
        assertNull(reopened.report!!.score)
        assertFalse(reopened.report!!.valid)
        assertEquals(persisted, app.prefs.all)
        assertNoSettlement(reopened)
    }

    @Test fun pendingAndUnknownBeltsSuppressAnOtherwiseAvailableProgressScore() = ui.runOnIdle {
        for (driver in listOf<Boolean?>(false, null)) {
            val vm = model(application())
            begin(vm)
            val at = SystemClock.elapsedRealtime()
            // This is the existing 10 Hz / 10 km score fixture; belt timing is independent.
            for (i in 0..10_000) vm.receiveSample(Sample(at + i * 100L, 10.0, 50))
            vm.receiveSample(Sample(at + 1_000_100, 10.0, 50), at + 500)
            assertEquals(100, vm.progressReport.score)
            vm.receiveVehicle(telemetry(vm.live, at, VehicleGear.GEAR_DRIVE, 1.0, driver = driver), at)
            assertEquals(if (driver == false) BeltVerdict.PENDING else BeltVerdict.UNVERIFIABLE, vm.beltDecision.verdict)
            assertNull(vm.progressReport.score)
            assertTrue(vm.ranking.isEmpty())
            assertEquals(0, vm.earned)
            if (driver == null) {
                arrive(vm, at + 250)
                assertEquals(DriveOutcome.UNVERIFIABLE, vm.completedDrives.single().outcome)
                assertNull(vm.report!!.score)
                assertNoSettlement(vm)
            }
        }
    }

    @Test fun watchdogDetectsLostMotionStreamWithoutConfirmingCachedUnbuckledValue() {
        val vm = ui.runOnIdle {
            model(application()).also { model ->
                begin(model)
                val at = SystemClock.elapsedRealtime()
                model.receiveVehicle(telemetry(model.live, at, VehicleGear.GEAR_DRIVE, 1.0, driver = false), at)
                assertEquals(BeltVerdict.PENDING, model.beltDecision.verdict)
            }
        }
        ui.waitUntil(4000) { vm.beltDecision.verdict == BeltVerdict.UNVERIFIABLE }
        ui.runOnIdle {
            assertEquals(Phase.RECORDING, vm.phase)
            assertTrue(vm.beltDecision.hadUnverifiedMotion)
            assertTrue(vm.completedDrives.isEmpty())
            arrive(vm, SystemClock.elapsedRealtime())
            assertEquals(DriveOutcome.UNVERIFIABLE, vm.completedDrives.single().outcome)
            assertNoSettlement(vm)
        }
    }

    private fun begin(vm: ChallengeViewModel, recording: Boolean = true, driver: Boolean? = true) {
        val now = SystemClock.elapsedRealtime()
        val spot = challengeSpots.first()
        val route = telemetry(LiveVehicle(), now, driver = driver).copy(destination = spot.name,
            destinationId = "belt-session-${UUID.randomUUID()}", destinationPoint = spot.point,
            destinationAt = now, remainingM = 12_000.0)
        vm.receiveVehicle(route, now)
        val instant = Instant.now()
        vm.accept(ChallengeRound("벨트 세션 검증", instant.minusSeconds(60), instant.plusSeconds(3600)))
        assertEquals(Phase.RESERVED, vm.phase)
        if (recording) {
            val at = SystemClock.elapsedRealtime()
            vm.receiveVehicle(telemetry(route, at, driver = driver).copy(remainingM = 10_000.0), at)
            assertEquals(Phase.RECORDING, vm.phase)
        }
    }

    private fun arrive(vm: ChallengeViewModel, at: Long) {
        vm.receiveVehicle(telemetry(vm.live, at).copy(arrived = true, arrivedAt = Instant.now(), remainingM = 0.0), at)
    }

    private fun telemetry(base: LiveVehicle, at: Long, gear: Int = VehicleGear.GEAR_PARK,
        speed: Double? = 0.0, driver: Boolean? = true, occupied: Boolean? = false,
        passenger: Boolean? = null) = base.copy(
        speedMps = speed?.coerceAtLeast(0.0), speedAt = SystemClock.elapsedRealtime(),
        gearCode = gear, gearAt = at, gearPark = gear == VehicleGear.GEAR_PARK, uxRestricted = false,
        motionSpeedMps = speed, motionSpeedAt = at, belt = driver, beltAt = at,
        passengerOccupied = occupied, passengerOccupiedAt = at, passengerBelt = passenger, passengerBeltAt = at,
    )

    private fun assertNoSettlement(vm: ChallengeViewModel) {
        assertTrue(vm.ranking.isEmpty())
        assertEquals(0, vm.earned)
        assertEquals(0, vm.badgeMileageEarned)
        assertEquals(0, vm.balance)
        assertTrue(vm.pendingBadges.isEmpty())
        assertTrue(vm.ownedBadges.isEmpty())
        assertEquals(0, vm.collectionProgress.challengeDays)
        assertEquals(0L, vm.recordedDistanceMeters)
    }

    private fun application() = TestApplication(ui.activity.applicationContext).also(apps::add)
    private fun model(app: Application) = ChallengeViewModel(app).also { vm ->
        stores += ViewModelStore().apply { put("belt-session-test", vm) }
    }

    private class TestApplication(context: Context) : Application() {
        init { attachBaseContext(context) }
        val prefs: SharedPreferences = context.getSharedPreferences("test-belt-session-${UUID.randomUUID()}", MODE_PRIVATE)
        override fun getSharedPreferences(name: String, mode: Int) = prefs
        override fun startForegroundService(service: Intent): ComponentName? = service.component
        override fun stopService(name: Intent) = true
        override fun startActivity(intent: Intent) = Unit
    }
}
