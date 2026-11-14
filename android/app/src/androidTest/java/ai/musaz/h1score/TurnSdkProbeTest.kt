package ai.musaz.h1score

import android.os.Bundle
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.Assert.assertTrue
import ai.musaz.h1score.core.ManeuverMonitor
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule

/** Diagnostic only: records actual SDK input; does not inject GPS, navigation or belt data. */
class TurnSdkProbeTest {
    // The app has foreground location permission; a headless test is not a valid GPS integration check.
    @get:Rule val ui=createAndroidComposeRule<ComponentActivity>()
    @Test fun reportLiveTurnSignals() {
        val runner = InstrumentationRegistry.getInstrumentation()
        lateinit var connection: PleosConnection
        val seconds=InstrumentationRegistry.getArguments().getString("probeSeconds","35").toInt().coerceIn(10,120)
        val monitor=ManeuverMonitor()
        runner.runOnMainSync { connection = PleosConnection(runner.targetContext); connection.start() }
        try {
            Thread.sleep(8000)
            runner.sendStatus(0,Bundle().apply{putString("console-command","Off,0,false")})
            val readyDeadline=SystemClock.elapsedRealtime()+30_000
            var moving=false
            while(!moving && SystemClock.elapsedRealtime()<readyDeadline) {
                runner.runOnMainSync {
                    val o=connection.flow.value.turnObservation(SystemClock.elapsedRealtime())
                    moving=o.forward && o.speedMps?.let{kotlin.math.abs(it-30.0/3.6)<.2}==true
                }
                if(!moving)Thread.sleep(100)
            }
            assertTrue("Console D / 30km/h did not reach SDK within 30 seconds",moving)
            runner.sendStatus(0, Bundle().apply { putString("turn-probe", "ready") })
            repeat(seconds*4) {
                runner.runOnMainSync {
                    val live = connection.flow.value
                    val now = SystemClock.elapsedRealtime()
                    val observation = live.turnObservation(now)
                    monitor.accept(observation)
                    runner.sendStatus(0, Bundle().apply {
                        putString("turn-sdk", "at=$now raw=${live.turnSignal} parsed=${observation.signal} " +
                            "rawSteering=${live.steeringDeg} steering=${observation.steeringDeg} hazard=${observation.hazardOn} signalAge=${now-live.turnAt} " +
                            "steeringAge=${now-live.steeringAt} forward=${observation.forward} " +
                            "speed=${observation.speedMps} gpsUsable=${live.gps?.usable(now)} " +
                            "gpsAge=${live.gps?.let{now-it.atMs}} gpsAccuracy=${live.gps?.accuracyM} bearingAccuracy=${live.gps?.bearingAccuracyDeg} " +
                            "instruction=${observation.instruction} distance=${observation.instructionDistanceM} " +
                            "instructionAge=${now-live.instructionAt} belt=${live.belt} navi=${live.statuses["NaviHelper"]} signalStatus=${live.statuses["TurnSignal"]}")
                    })
                }
                Thread.sleep(250)
            }
            runner.sendStatus(0,Bundle().apply {putString("actual-input-summary","events=${monitor.events} diagnostics=${monitor.diagnostics}")})
        } finally { runner.runOnMainSync { connection.stop() } }
    }
}
