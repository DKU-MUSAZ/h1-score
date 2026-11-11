package ai.musaz.h1score

import ai.musaz.h1score.core.LongitudinalDetector
import ai.musaz.h1score.core.LongitudinalRules
import ai.musaz.h1score.core.Sample
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Reads the emulator's actual AAOS speed feed without starting a trip or changing preferences. */
class ScoringSensorInputTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()

    @Test fun arrivalDrainKeepsTheSampleWhoseWakeupHasAlreadyBeenReceived() {
        val observed = mutableListOf<Sample>()
        lateinit var collector: Job
        val beforeArrival = Sample(1_000, 10.0, 50)
        val afterDrain = Sample(1_100, 10.0, 50)
        ui.runOnIdle {
            val buffer = ScoringSampleBuffer()
            // Main (not Main.immediate) deliberately delays the resumed collector while an
            // arrival in this same main-thread turn drains the real pending data.
            collector = CoroutineScope(Dispatchers.Main).launch(start = CoroutineStart.UNDISPATCHED) {
                buffer.samples.collect { observed += it }
            }
            buffer.offer(beforeArrival)
            assertEquals(listOf(beforeArrival), buffer.drain())
            buffer.offer(afterDrain)
        }
        try {
            ui.runOnIdle { assertEquals(listOf(afterDrain), observed) }
        } finally {
            ui.runOnIdle { collector.cancel() }
        }
    }

    @Test fun bufferOverflowIsExplicitAndStoppingClearsPendingSamples() {
        ui.runOnIdle {
            val buffer = ScoringSampleBuffer()
            repeat(513) { buffer.offer(Sample(it * 100L, 10.0, 50)) }
            val drained = buffer.drain()
            assertEquals(512, drained.count { it.speedMps != null })
            assertEquals(1, drained.count { it.speedMps == null })
            assertTrue(drained.last().speedTimestampNanos > 0)
            buffer.offer(Sample(60_000, 10.0, 50))
            buffer.clear()
            assertTrue(buffer.drain().isEmpty())
        }
    }

    @Test fun scoringInputsExpireRatherThanTreatStaleBeltOrLimitAsConfirmed() {
        val now = 10_000L
        val vehicle = LiveVehicle(belt = true, beltAt = now, limitKph = 50, limitAt = now,
            speedMps = 0.0, speedAt = now, gearPark = true, uxRestricted = false)
        assertEquals(true, vehicle.freshBelt(now + 2_500))
        assertNull(vehicle.freshBelt(now + 2_501))
        assertNull(vehicle.freshBelt(now - 1))
        assertEquals(50, vehicle.freshLimit(now + 2_500))
        assertNull(vehicle.freshLimit(now + 2_501))
        assertNull(vehicle.freshLimit(now - 1))
        assertTrue(vehicle.isParked(now))
        assertFalse(vehicle.isParked(now - 1))
        assertFalse(vehicle.isParked(now + 2_501))
        assertEquals(false, vehicle.copy(belt = null, beltWarningOnAt = now).freshBelt(now))
        assertEquals(true, vehicle.copy(belt = null, beltWarningOffAt = now).freshBelt(now))
        assertNull(vehicle.copy(belt = null, beltWarningOnAt = now).freshBelt(now + 2_501))
        assertEquals(false, vehicle.copy(belt = false, beltWarningOffAt = now).freshBelt(now))
    }

    @Test fun consoleVehicleFeedPreservesSourceTimeAndSupportsLongitudinalScoring() {
        val context = ui.activity.applicationContext
        assertEquals("Install the validation APK with its declared runtime permissions granted",
            PackageManager.PERMISSION_GRANTED, context.checkSelfPermission("android.car.permission.CAR_SPEED"))
        val preferences = context.getSharedPreferences("h1-demo-v1", 0)
        val before = preferences.all.toMap()
        val connection = PleosConnection(context)
        var observations = emptyList<Sample>()
        try {
            ui.runOnIdle { connection.setConsoleCapture(true); connection.start() }
            observations = runBlocking {
                withContext(Dispatchers.Main) {
                    withTimeout(15_000) {
                        connection.samples.filter { it.speedMps != null }.take(40).toList()
                    }
                }
            }
            assertEquals(40, observations.size)
            assertTrue("Every sample retains the vehicle source timestamp",
                observations.all { it.speedTimestampNanos > 0 && it.atMs == it.speedTimestampNanos / 1_000_000 })
            assertTrue("Source timestamps must increase rather than repeat cached values",
                observations.zipWithNext().all { (a, b) -> b.speedTimestampNanos > a.speedTimestampNanos })
            assertTrue("A source timestamp cannot be manufactured in the future",
                observations.last().speedTimestampNanos <= SystemClock.elapsedRealtimeNanos())
            val analysis = LongitudinalDetector.evaluate(observations)
            assertTrue("Actual speed feed was ${analysis.sampleRateHz} Hz; status=${connection.flow.value.statuses["차량 속도"]}",
                analysis.sampleRateHz >= LongitudinalRules.MIN_SAMPLE_RATE_HZ)
            assertTrue("Speed intervals must remain continuous enough for scoring: ${analysis.coverage}",
                analysis.ready)
        } finally {
            ui.runOnIdle { connection.stop(); connection.setConsoleCapture(false) }
        }
        assertEquals("Reading vehicle telemetry must not change saved trips or preferences", before, preferences.all)
        android.util.Log.i("H1ScoringInputTest", "actualSamples=${observations.size}, hz=${LongitudinalDetector.evaluate(observations).sampleRateHz}")
    }

    /** Diagnostic, not a claim that this vehicle satisfies the engine's >=8 Hz requirement. */
    @Suppress("DEPRECATION")
    @Test fun normalTenHzRequestReportsActualSourceFrequency() {
        val context = ui.activity.applicationContext
        assertEquals(PackageManager.PERMISSION_GRANTED,
            context.checkSelfPermission("android.car.permission.CAR_SPEED"))
        val preferences = context.getSharedPreferences("h1-demo-v1", 0)
        val before = preferences.all.toMap()
        val received = Channel<Sample>(Channel.UNLIMITED)
        val subscribed = CompletableDeferred<Float>()
        var car: Car? = null
        var manager: CarPropertyManager? = null
        var callback: CarPropertyManager.CarPropertyEventCallback? = null
        try {
            ui.runOnIdle {
                car = Car.createCar(context, Handler(Looper.getMainLooper()),
                    Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { connectedCar, ready ->
                    if (ready && callback == null) {
                        runCatching {
                            val source = connectedCar.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
                            manager = source
                            val config = requireNotNull(source.getCarPropertyConfig(VehiclePropertyIds.PERF_VEHICLE_SPEED))
                            check(config.maxSampleRate > 0f)
                            val rate = 10f.coerceIn(config.minSampleRate, config.maxSampleRate)
                            val listener = object : CarPropertyManager.CarPropertyEventCallback {
                                override fun onChangeEvent(value: CarPropertyValue<*>) {
                                    if (value.propertyId != VehiclePropertyIds.PERF_VEHICLE_SPEED) return
                                    val timestamp = value.timestamp
                                    val now = SystemClock.elapsedRealtimeNanos()
                                    val speed = (value.value as? Float)?.toDouble()?.takeIf {
                                        value.status == CarPropertyValue.STATUS_AVAILABLE && it.isFinite() && it in 0.0..80.0 &&
                                            timestamp > 0 && timestamp <= now && now - timestamp <= 500_000_000
                                    }
                                    // Direct callback observation avoids belt-gated nulls, generated
                                    // timeout barriers and conflated UI state. Keep original time.
                                    received.trySend(Sample(timestamp / 1_000_000, speed, 50,
                                        speedTimestampNanos = timestamp))
                                }

                                override fun onErrorEvent(propertyId: Int, areaId: Int) {
                                    if (propertyId == VehiclePropertyIds.PERF_VEHICLE_SPEED)
                                        received.close(IllegalStateException("Vehicle speed callback error"))
                                }
                            }
                            callback = listener
                            check(source.registerCallback(listener, VehiclePropertyIds.PERF_VEHICLE_SPEED, rate))
                            subscribed.complete(rate)
                        }.onFailure { subscribed.completeExceptionally(it) }
                    }
                }
            }
            val (requestedHz, observations) = runBlocking {
                withTimeout(15_000) {
                    subscribed.await() to received.receiveAsFlow().take(40).toList()
                }
            }
            assertEquals(40, observations.size)
            assertTrue(observations.all { it.speedTimestampNanos > 0 })
            val sourceSeconds = (observations.last().speedTimestampNanos - observations.first().speedTimestampNanos) / 1e9
            assertTrue("The vehicle source clock must advance", sourceSeconds > 0.0)
            val analysis = LongitudinalDetector.evaluate(observations)
            android.util.Log.i("H1ScoringInputTest",
                "normalRequestedHz=$requestedHz, sourceCallbacks=${observations.size}, " +
                    "observedHz=${(observations.size - 1) / sourceSeconds}, " +
                    "validHz=${analysis.sampleRateHz}, coverage=${analysis.coverage}, " +
                    "longitudinalReady=${analysis.ready}")
        } finally {
            ui.runOnIdle {
                callback?.let { listener -> manager?.unregisterCallback(listener) }
                car?.disconnect()
            }
            received.close()
        }
        assertEquals("The frequency diagnostic must not change saved data", before, preferences.all)
    }
}
