package ai.musaz.h1score

import ai.musaz.h1score.core.ChallengeRound
import ai.musaz.h1score.core.Phase
import ai.musaz.h1score.core.ScoreEngine
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.util.UUID

/** Normal scoring path: no demo, console mode, synthetic speed, or user-data writes. */
class NormalScoringInputTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val connections = mutableListOf<PleosConnection>()
    private val stores = mutableListOf<ViewModelStore>()
    private val apps = mutableListOf<TestApplication>()
    private var navigationFixture: Job? = null
    private var beltFixtureJob: Job? = null

    @After fun cleanup() {
        ui.runOnIdle {
            navigationFixture?.cancel()
            beltFixtureJob?.cancel()
            stores.forEach(ViewModelStore::clear)
            connections.forEach(PleosConnection::stop)
        }
        apps.forEach { it.prefs.edit().clear().commit() }
    }

    @Test fun navigationCallbacksKeepOnlyAFiveSecondLeaseAndRejectInvalidLimits() {
        ui.runOnIdle {
            val connection = connection()
            connection.start()
            val listener = listener(connection)
            listener.onDestinationInfo(destination())
            listener.onCurrentRoadSpeedLimit(50)
            val first = connection.flow.value
            assertEquals(50, first.freshLimit(first.limitAt + 3_000))
            assertEquals(50, first.freshLimit(first.limitAt + 5_000))
            assertNull(first.freshLimit(first.limitAt + 5_001))

            // Destination and status updates do not pretend the road-limit sensor refreshed.
            listener.onDestinationInfo(destination(remaining = 9_000))
            listener.onNavigationStatus(NavigationStatus.ROUTE_DRIVE)
            listener.onNavigationStatus(NavigationStatus.FREE_DRIVE)
            listener.onError(NaviErrorCode.CAUSE_LOW_BATTERY)
            assertEquals(first.limitAt, connection.flow.value.limitAt)
            assertEquals(50, connection.flow.value.limitKph)
            assertNull(connection.flow.value.freshLimit(first.limitAt + 5_001))

            // The SDK may send the same value again: that genuine callback renews the lease.
            SystemClock.sleep(2)
            listener.onCurrentRoadSpeedLimit(50)
            val renewed = connection.flow.value
            assertTrue(renewed.limitAt > first.limitAt)
            assertEquals(50, renewed.freshLimit(renewed.limitAt + 5_000))
            for (invalid in listOf(0, -1, 151)) {
                listener.onCurrentRoadSpeedLimit(50)
                listener.onCurrentRoadSpeedLimit(invalid)
                assertNull(connection.flow.value.limitKph)
                assertEquals(0L, connection.flow.value.limitAt)
            }
            connection.stop()
        }
    }

    @Test fun routeAndConnectionTransitionsInvalidateThePreviousRoadLimit() {
        ui.runOnIdle {
            val connection = connection()
            connection.start()
            val listener = listener(connection)
            val invalidations: List<Pair<String, () -> Unit>> = listOf(
                "route start" to { listener.onRouteStarted(RouteStartInfo(600, 12_000, 0, "시험 경로", 0)) },
                "destination change" to { listener.onDestinationChanged() },
                "implicit destination change" to { listener.onDestinationInfo(destination(id = "different")) },
                "route cancel" to { listener.onRouteCancelled() },
                "reroute" to { listener.onReRouteEnded() },
                "arrival" to { listener.onDestinationArrived(DestinationArrivedInfo(600, 10_000, "시험 도착", "")) },
                "route error" to { listener.onError(NaviErrorCode.CAUSE_SEARCH_ROUTE) },
                "inactive navigation" to { listener.onNavigationStatus(NavigationStatus.INACTIVE) },
                "initializing navigation" to { listener.onNavigationStatus(NavigationStatus.INITIALIZING) },
            )
            for ((name, invalidate) in invalidations) {
                listener.onDestinationInfo(destination())
                listener.onCurrentRoadSpeedLimit(80)
                assertEquals(name, 80, connection.flow.value.limitKph)
                invalidate()
                assertNull(name, connection.flow.value.limitKph)
                assertEquals(name, 0L, connection.flow.value.limitAt)
            }
            listener.onCurrentRoadSpeedLimit(80)
            connection.stop()
            assertNull(connection.flow.value.limitKph)
            assertEquals(0L, connection.flow.value.limitAt)
            // A callback queued by a released SDK instance must not revive the old lease.
            listener.onCurrentRoadSpeedLimit(80)
            assertNull(connection.flow.value.limitKph)
            assertEquals(0L, connection.flow.value.limitAt)
        }
    }

    /**
     * Opt-in: the operator drives the Virtual Console after READY_DRIVE.
     * Speed always uses real AAOS callbacks; navigation uses explicit callback fixtures.
     * Belt is real by default. The separate beltFixture=true argument supplies confirmed
     * belt input only when this emulator cannot control its buckle property. It does not
     * disable the normal scoring gate, and must not be reported as a real-belt verification.
     * This is a >=600m input check, not a completed 10km competition or road validation.
     */
    @Test fun actualNormalSpeedProducesAScoreWithoutConsoleMode() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Manual Pleos drive is opt-in", arguments.getString("manualDrive") == "true")
        val useBeltFixture = arguments.getString("beltFixture") == "true"
        val inputDescription = if (useBeltFixture) "REAL speed; FIXTURE belt/navigation"
            else "REAL speed/belt; FIXTURE navigation"
        val context = ui.activity.applicationContext
        assertEquals(PackageManager.PERMISSION_GRANTED,
            context.checkSelfPermission("android.car.permission.CAR_SPEED"))
        val productionPreferences = context.getSharedPreferences("h1-demo-v1", 0)
        val before = productionPreferences.all.toMap()
        val app = TestApplication(context).also(apps::add)
        val vm = ui.runOnIdle {
            ChallengeViewModel(app).also { model ->
                stores += ViewModelStore().apply { put("normal-score-test", model) }
                model.enterForeground()
            }
        }
        ui.setContent { H1App(vm) }
        if (useBeltFixture) {
            stage("PREPARING_BELT_FIXTURE", "Explicit beltFixture=true; waiting for real SDK initialization before isolating belt input. Keep speed zero and gear P.")
            installBeltFixture(vm)
        } else {
            stage("AWAIT_BELT", "Confirm the REAL seat-belt buckle property; warning-lamp OFF alone cannot override an explicit unbuckled property. Keep speed zero and gear P.")
        }
        ui.waitUntil(60_000) {
            vm.live.beltConditionMet && vm.live.parked
        }
        // Ensure SDK initialization cannot reattach the listener after isolating navigation.
        ui.waitUntil(15_000) { field<Boolean>(vm.connection, "naviReady") }
        lateinit var events: NaviHelperEventListener
        ui.runOnIdle {
            events = listener(vm.connection)
            field<NaviHelper>(vm.connection, "navi").removeListener(events)
            events.onDestinationInfo(destination(remaining = 12_000))
            events.onCurrentRoadSpeedLimit(100)
        }
        ui.waitUntil(3_000) { vm.live.destinationId?.contains("normal-input-fixture") == true }
        ui.runOnIdle {
            assertFalse(vm.demo)
            assertFalse(vm.consoleTest)
            assertFalse(field<Boolean>(vm.connection, "consoleCapture"))
            val now = Instant.now()
            vm.accept(ChallengeRound("일반 모드 실제 입력 검증", now.minusSeconds(60), now.plusSeconds(600)))
            assertEquals(Phase.RESERVED, vm.phase)
            events.onDestinationInfo(destination(remaining = 10_000))
        }
        ui.waitUntil(3_000) { vm.phase == Phase.RECORDING }
        ui.runOnIdle {
            navigationFixture = CoroutineScope(Dispatchers.Main).launch {
                var tick = 0
                while (isActive) {
                    events.onDestinationInfo(destination(remaining = 10_000))
                    // One three-second callback interval exercises bounded delivery jitter.
                    if (tick !in 2..3) events.onCurrentRoadSpeedLimit(100)
                    tick++
                    delay(1_000)
                }
            }
        }
        stage("READY_DRIVE", "$inputDescription; normal mode; navigation limit fixture 100km/h. Drive >=600m.")
        ui.waitUntil(120_000) {
            vm.phase == Phase.DISQUALIFIED || vm.progressReport.validDistanceM >= 600.0
        }
        assertEquals("The normal belt gate must stay confirmed ($inputDescription)", Phase.RECORDING, vm.phase)
        stage("READY_PARK", "Distance reached. Set speed zero and gear P; belt must remain confirmed.")
        ui.waitUntil(30_000) { vm.live.parked && vm.live.beltConditionMet }
        ui.runOnIdle {
            navigationFixture?.cancel()
            navigationFixture = null
            events.onDestinationArrived(DestinationArrivedInfo(60,
                vm.progressReport.validDistanceM.toInt(), "일반 입력 검증", ""))
        }
        ui.waitUntil(5_000) { vm.phase == Phase.FINISHED && vm.report != null }
        val report = ui.runOnIdle { requireNotNull(vm.report) }
        assertNotNull("Actual normal-mode speed input must produce a score ($inputDescription)", report.score)
        assertEquals(ScoreEngine.VERSION, report.scoringVersion)
        assertTrue(report.validDistanceM >= 600)
        assertTrue("Measured source rate: ${report.longitudinalSampleRateHz}", report.longitudinalSampleRateHz >= 8.0)
        assertTrue(report.longitudinalReady)
        assertTrue(report.coverage >= .9)
        assertEquals(Screen.RESULT, vm.screen)
        assertEquals(1, CompletedDriveStore(app.prefs).readAll().size)
        assertEquals(report, CompletedDriveStore(app.prefs).readAll().single().report)
        assertEquals("Manual input test must not modify the user's saved records", before, productionPreferences.all)
        stage("RESULT", "score=${report.score}, distance=${report.validDistanceM}, hz=${report.longitudinalSampleRateHz}, " +
            "coverage=${report.coverage}, accel=${report.accelerationEvents}, decel=${report.decelerationEvents}; " +
            "$inputDescription; isolated history; partial >=600m validation.")
        instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            app.filesDir.resolve("normal-scoring-result.png").outputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            }
            bitmap.recycle()
        }
        // Leave the real result visible for the operator's in-app-browser observation.
        SystemClock.sleep(20_000)
        assertEquals(before, productionPreferences.all)
    }

    private fun installBeltFixture(vm: ChallengeViewModel) {
        // The adapter checks capabilities on cycles 1/3/7. Let these finish before
        // disabling only this test instance's real seat-buckle polling.
        ui.waitUntil(25_000) {
            field<Int>(vm.connection, "cycles") >= 9 && vm.live.beltAt > 0
        }
        ui.runOnIdle { setField(vm.connection, "driverSeat", null) }
        var lastBeltAt = vm.live.beltAt
        var quietSince = SystemClock.elapsedRealtime()
        ui.waitUntil(15_000) {
            val observed = vm.live.beltAt
            if (observed != lastBeltAt) {
                lastBeltAt = observed
                quietSince = SystemClock.elapsedRealtime()
            }
            // Drain any read already in flight. A late capability callback must not
            // silently reenable the real property while a fixture is being supplied.
            instrumentation.runOnMainSync {
                if (field<Any?>(vm.connection, "driverSeat") != null) {
                    setField(vm.connection, "driverSeat", null)
                    quietSince = SystemClock.elapsedRealtime()
                }
            }
            SystemClock.elapsedRealtime() - quietSince >= 3_000
        }
        ui.runOnIdle {
            val state = field<MutableStateFlow<LiveVehicle>>(vm.connection, "state")
            beltFixtureJob = CoroutineScope(Dispatchers.Main).launch {
                while (isActive) {
                    state.update { it.copy(belt = true, beltAt = SystemClock.elapsedRealtime()) }
                    delay(200)
                }
            }
        }
        stage("BELT_FIXTURE_ACTIVE", "Confirmed belt refreshed every200ms in test-only state; production belt policy is unchanged. Speed remains real AAOS input.")
    }

    private fun stage(name: String, detail: String) {
        Log.i("H1NormalScoring", "$name: $detail")
        instrumentation.sendStatus(2, Bundle().apply { putString("stream", "H1NormalScoring $name: $detail\n") })
    }

    private fun connection() = PleosConnection(ui.activity.applicationContext).also(connections::add)
    private fun listener(connection: PleosConnection) = field<NaviHelperEventListener>(connection, "naviEvents")
    @Suppress("UNCHECKED_CAST") private fun <T> field(instance: Any, name: String): T =
        instance.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(instance) as T
    private fun setField(instance: Any, name: String, value: Any?) {
        instance.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(instance, value)
    }

    private fun destination(id: String = "normal-input-fixture", remaining: Int = 12_000) = DestinationInfo(
        latitude = hangangVenue.latitude, longitude = hangangVenue.longitude,
        name = "여의도한강공원", address = "시험용 내비 콜백", remainDistance = remaining,
        remainTime = 600, tollFare = 0, poiId = id, poiSubId = null, poiProvider = "test")

    private class TestApplication(context: Context) : Application() {
        init { attachBaseContext(context) }
        val prefs: SharedPreferences = context.getSharedPreferences("test-normal-input-${UUID.randomUUID()}", MODE_PRIVATE)
        override fun getSharedPreferences(name: String, mode: Int) = prefs
        override fun startForegroundService(service: Intent): ComponentName? = service.component
        override fun stopService(name: Intent) = true
        override fun startActivity(intent: Intent) = Unit
    }
}
