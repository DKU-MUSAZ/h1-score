package ai.musaz.h1score

import android.os.Bundle
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.Assert.assertEquals
import ai.musaz.h1score.core.SeatBeltMonitor

/** Read-only diagnostic: vehicle values come from Pleos, never from test fixtures. */
class SeatBeltSdkProbeTest {
    @Test fun reportLiveSeatSignals() {
        val runner=InstrumentationRegistry.getInstrumentation()
        lateinit var connection: PleosConnection
        runner.runOnMainSync { connection=PleosConnection(runner.targetContext); connection.start() }
        try {
            Thread.sleep(8000)
            lateinit var rawReader: PleosSeatReader
            runner.runOnMainSync { rawReader=PleosSeatReader(runner.targetContext); rawReader.start() }
            Thread.sleep(1000)
            runner.runOnMainSync {
                for (area in listOf(1, 4)) rawReader.readRaw("SEAT_BELT_BUCKLED", area,
                    { raw -> runner.sendStatus(0, Bundle().apply { putString("raw-buckle-$area", raw ?: "<null>") }) },
                    { error -> runner.sendStatus(0, Bundle().apply { putString("raw-buckle-$area", error.message) }) })
            }
            Thread.sleep(1000)
            runner.runOnMainSync { rawReader.stop() }
            val monitor=SeatBeltMonitor()
            repeat(30) {
                runner.runOnMainSync { monitor.accept(connection.flow.value.beltObservation(SystemClock.elapsedRealtime())) }
                Thread.sleep(100)
            }
            runner.runOnMainSync {
                val live=connection.flow.value
                val now=SystemClock.elapsedRealtime()
                runner.sendStatus(0, Bundle().apply { putString("belt-sdk", "gear=${live.gearCode}, motion=${live.motionSpeedMps}, " +
                    "driver=${live.belt}, passengerOccupied=${live.passengerOccupied}, passengerBelt=${live.passengerBelt}, " +
                    "warningOn=${live.beltWarningOnAt?.let { now-it in 0..2500 } == true}, " +
                    "warningOff=${live.beltWarningOffAt?.let { now-it in 0..2500 } == true}, " +
                    "ages=${now-live.gearAt}/${now-live.motionSpeedAt}/${now-live.beltAt}/${now-live.passengerOccupiedAt}/${now-live.passengerBeltAt}, " +
                    "verdict=${monitor.decision.verdict}, failedSeat=${monitor.decision.seat}, " +
                    "unverified=${monitor.decision.hadUnverifiedMotion}, " +
                    "seatStatus=${live.statuses["Seat"]}, occupancyStatus=${live.statuses["조수석 탑승"]}") })
                InstrumentationRegistry.getArguments().getString("expectedBelt")?.let {
                    assertEquals(it, monitor.decision.verdict.name)
                }
                val args=InstrumentationRegistry.getArguments()
                args.getString("expectedDriver")?.let { assertEquals(it.toBooleanStrict(), live.belt) }
                args.getString("expectedOccupied")?.let { assertEquals(it.toBooleanStrict(), live.passengerOccupied) }
                args.getString("expectedPassenger")?.let { assertEquals(it.toBooleanStrict(), live.passengerBelt) }
                args.getString("expectedSeat")?.let { assertEquals(it, monitor.decision.seat?.name ?: "NONE") }
            }
        } finally { runner.runOnMainSync { connection.stop() } }
    }
}
