package ai.musaz.h1score

import ai.musaz.h1score.core.BeltObservation
import ai.musaz.h1score.core.BeltVerdict
import ai.musaz.h1score.core.SeatBeltMonitor
import android.car.VehicleGear
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Exercises the real adapter queue without opening an SDK connection or changing vehicle state. */
class SeatBeltAdapterTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()

    @Test fun seatServiceLossInvalidatesAllSeatEvidenceBeforeRecovery() {
        ui.runOnIdle {
            val adapter = PleosConnection(ui.activity.applicationContext)
            val monitor = SeatBeltMonitor()
            publish(adapter, moving())
            adapter.drainBeltObservations().forEach { monitor.accept(it) }
            PleosConnection::class.java.getDeclaredMethod("onSeatSourceUnavailable")
                .apply { isAccessible=true }.invoke(adapter)
            publish(adapter, moving())
            val evidence=adapter.drainBeltObservations()
            assertNull(evidence.first().driverBuckled)
            assertNull(evidence.first().passengerBuckled)
            assertNull(evidence.first().passengerOccupied)
            assertEquals(BeltVerdict.UNVERIFIABLE, monitor.accept(evidence.first()).verdict)
            assertEquals(BeltVerdict.SAFE, monitor.accept(evidence.last()).verdict)
            assertTrue(monitor.decision.hadUnverifiedMotion)
        }
    }

    @Test fun nullThenRecoverySurvivesConflatedVehicleSnapshots() {
        val snapshots = mutableListOf<LiveVehicle>()
        val evidence = mutableListOf<BeltObservation>()
        val monitor = SeatBeltMonitor()
        lateinit var collector: Job
        ui.runOnIdle {
            val adapter = PleosConnection(ui.activity.applicationContext)
            publish(adapter, moving().copy(motionSpeedMps = 1.25))
            assertEquals(BeltVerdict.SAFE, monitor.accept(adapter.drainBeltObservations().single()).verdict)
            // A normal Main dispatcher deliberately leaves this collector suspended until
            // both callbacks below have published in the same main-thread turn.
            collector = CoroutineScope(Dispatchers.Main).launch(start = CoroutineStart.UNDISPATCHED) {
                adapter.flow.collect { snapshot ->
                    snapshots += snapshot
                    evidence += adapter.drainBeltObservations()
                }
            }
            publish(adapter, moving().copy(motionSpeedMps = null, motionSpeedAt = 0))
            publish(adapter, moving())
        }
        try {
            ui.runOnIdle {
                assertEquals(2, snapshots.size)
                assertEquals("The UI snapshots have already lost the transient null", listOf(1.25, 1.0), snapshots.map { it.motionSpeedMps })
                assertEquals(listOf(null, 1.0), evidence.map { it.speedMps })
                assertEquals(BeltVerdict.UNVERIFIABLE, monitor.accept(evidence[0]).verdict)
                assertEquals(BeltVerdict.SAFE, monitor.accept(evidence[1]).verdict)
                assertTrue("Recovery must not restore eligibility for the unverified interval", monitor.decision.hadUnverifiedMotion)
            }
        } finally {
            ui.runOnIdle { collector.cancel() }
        }
    }

    @Test fun overflowKeepsAnUnknownBarrierAfterTheOriginalMissingReadingIsEvicted() {
        ui.runOnIdle {
            val adapter = PleosConnection(ui.activity.applicationContext)
            publish(adapter, moving().copy(motionSpeedMps = null, motionSpeedAt = 0))
            repeat(513) { publish(adapter, moving()) }
            val evidence = adapter.drainBeltObservations()
            assertEquals(512, evidence.count { it.speedMps == 1.0 })
            assertEquals(1, evidence.count { it.speedMps == null })
            assertNull("The unknown barrier must precede recovered readings", evidence.first().speedMps)
            val monitor = SeatBeltMonitor()
            evidence.forEach { monitor.accept(it) }
            assertEquals(BeltVerdict.SAFE, monitor.decision.verdict)
            assertTrue("An overflow cannot silently restore trip eligibility", monitor.decision.hadUnverifiedMotion)
            assertTrue(adapter.drainBeltObservations().isEmpty())

            publish(adapter, moving())
            val afterDrain = adapter.drainBeltObservations()
            assertEquals(1, afterDrain.size)
            assertEquals(1.0, afterDrain.single().speedMps)
        }
    }

    @Test fun immediateCollectorCanDrainEvidenceDuringStatePublication() {
        val received = mutableListOf<Pair<LiveVehicle, List<BeltObservation>>>()
        lateinit var collector: Job
        ui.runOnIdle {
            val adapter = PleosConnection(ui.activity.applicationContext)
            publish(adapter, moving())
            collector = CoroutineScope(Dispatchers.Main.immediate).launch(start = CoroutineStart.UNDISPATCHED) {
                adapter.flow.collect { snapshot -> received += snapshot to adapter.drainBeltObservations() }
            }
            publish(adapter, moving().copy(belt = null, beltAt = 0))
        }
        try {
            ui.runOnIdle {
                assertEquals(2, received.size)
                assertEquals(listOf(true, null), received.map { it.first.belt })
                assertEquals(listOf(true, null), received.map { it.second.single().driverBuckled })
            }
        } finally {
            ui.runOnIdle { collector.cancel() }
        }
    }

    private fun moving(): LiveVehicle {
        val now = SystemClock.elapsedRealtime()
        return LiveVehicle(gearCode = VehicleGear.GEAR_DRIVE, gearAt = now,
            motionSpeedMps = 1.0, motionSpeedAt = now,
            belt = true, beltAt = now, passengerOccupied = false, passengerOccupiedAt = now)
    }

    private fun publish(adapter: PleosConnection, vehicle: LiveVehicle) {
        val update = PleosConnection::class.java.getDeclaredMethod("updateState", Function1::class.java)
            .apply { isAccessible = true }
        val change: (LiveVehicle) -> LiveVehicle = { vehicle }
        update.invoke(adapter, change)
    }
}
