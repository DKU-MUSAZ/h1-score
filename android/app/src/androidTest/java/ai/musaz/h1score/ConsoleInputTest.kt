package ai.musaz.h1score

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import ai.musaz.h1score.core.*
import java.time.Instant
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain

/** Deterministic replay through the console session and the unchanged scoring engine.
 * Vehicle timestamps/speeds are fixtures; live Virtual Console delivery is a separate smoke test. */
class ConsoleInputTest {
    private val ui = createAndroidComposeRule<MainActivity>()
    private val permissions = object : ExternalResource() {
        override fun before() {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            val requested = buildList {
                add("android.car.permission.CAR_SPEED")
                add("android.car.permission.CAR_POWERTRAIN")
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            }
            requested.filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
                .forEach { instrumentation.uiAutomation.grantRuntimePermission(context.packageName, it) }
        }
    }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(permissions).around(ui)

    private val model get() = (ui.activity.application as H1Application).model

    private fun parkedVehicle(belt: Boolean? = false): LiveVehicle {
        val now = SystemClock.elapsedRealtime()
        return LiveVehicle(speedMps = 0.0, speedAt = now, gearPark = true,
            uxRestricted = false, belt = belt, beltAt = now)
    }

    @Before fun isolateReplayFromLiveSdk() {
        ui.runOnIdle { model.connection.stop() }
        // Let the UI collector consume the adapter's stop notification before injecting fixtures.
        ui.waitForIdle()
        ui.runOnIdle {
            model.receiveVehicle(parkedVehicle())
            model.cancel()
            if (model.demo) model.switchSource()
            model.receiveVehicle(parkedVehicle())
            assertTrue("Console testing is available only on a debug emulator", model.consoleTestAvailable)
        }
    }

    @After fun leaveNoReplaySessionForTheNextTest() {
        ui.runOnIdle {
            model.connection.stop()
            model.receiveVehicle(parkedVehicle())
            model.cancel()
        }
    }

    private fun startConsole(limitKph: Int = 80) {
        ui.runOnIdle {
            model.receiveVehicle(parkedVehicle())
            model.chooseConsoleLimit(limitKph)
            model.startConsoleTest()
            assertTrue(model.consoleTest)
            assertFalse(model.demo)
            assertEquals(Phase.RECORDING, model.phase)
            // Stop in the same main-thread turn, before asynchronous SDK callbacks can mix
            // live zero-speed samples with this test's deterministic replay.
            model.connection.stop()
        }
        // Android must create/promote the real recording service before this test can stop it.
        Thread.sleep(1500)
        ui.runOnIdle { model.connection.stop() }
        ui.waitForIdle()
    }

    @Test fun actualInputSessionUsesExistingScoreAndResultWithoutRewardSideEffects() {
        var balanceBefore = 0
        var completedBefore = 0
        var badgesBefore = emptySet<String>()
        ui.runOnIdle {
            balanceBefore = model.balance
            completedBefore = model.completed
            badgesBefore = model.ownedBadges.toSet()
        }
        startConsole(100)
        ui.runOnIdle {
            val vm = model
            // Navigation and buckle metadata must not interrupt an explicitly labelled test.
            val now = SystemClock.elapsedRealtime()
            vm.receiveVehicle(LiveVehicle(speedMps = 12.0, speedAt = now, gearPark = false,
                uxRestricted = true, belt = false, beltAt = now, routeCancelled = true,
                destinationId = "unrelated-real-navigation", arrived = true, arrivedAt = Instant.now()))
            assertEquals(Phase.RECORDING, vm.phase)
            vm.finishConsoleTest()
            assertEquals("Moving sessions cannot reveal a result", Phase.RECORDING, vm.phase)
            assertNull(vm.report)
            vm.chooseConsoleLimit(50)
            assertEquals("The test's limit is fixed for the whole recording", 100, vm.consoleLimitKph)

            val base = SystemClock.elapsedRealtimeNanos() + 1_000_000L
            // A pending sample from before the session must never be counted.
            vm.receiveSample(Sample((base - 10_000_000_000L) / 1_000_000L, 60.0, 100,
                speedTimestampNanos = base - 10_000_000_000L))
            val input = (0..450).map { index ->
                val seconds = index / 10.0
                val speed = when {
                    seconds < 2.0 -> 12.0
                    seconds < 4.0 -> 12.0 + 3.0 * (seconds - 2.0)
                    seconds < 6.0 -> 18.0
                    seconds < 7.0 -> 18.0 - 3.0 * (seconds - 6.0)
                    else -> 15.0
                }
                val ns = base + index * 100_000_000L
                // No road-limit SDK event is available in the console fixture.
                Sample(ns / 1_000_000L, speed, null, speedTimestampNanos = ns,
                    brakePressed = seconds in 6.0..7.0)
            }
            input.forEach { vm.receiveSample(it, it.atMs) }
            vm.receiveVehicle(parkedVehicle())
            vm.finishConsoleTest()

            assertEquals(Phase.FINISHED, vm.phase)
            assertEquals(Screen.RESULT, vm.screen)
            assertTrue(vm.resultUnlocked)
            val actual = requireNotNull(vm.report)
            val expected = ScoreEngine.evaluate(input.map { it.copy(limitKph = 100) })
            assertEquals("The production score engine receives the real trace unchanged", expected, actual)
            assertEquals(1, actual.accelerationEvents)
            assertEquals(1, actual.decelerationEvents)
            assertEquals(676.5, actual.validDistanceM, 0.00001)
            assertNotNull(actual.score)
            assertTrue(actual.longitudinalReady)
            assertEquals(10.0, actual.longitudinalSampleRateHz, 0.00001)
            assertEquals(actual.validDistanceM / 1000 * 2.5, actual.speedPoints, 0.00001)
            assertTrue("Confirmed abrupt distance loses only its smooth bonus",
                actual.accelerationPoints < actual.validDistanceM / 1000 * 1.5)
            assertEquals(60 + actual.speedPoints + actual.accelerationPoints, actual.totalPoints, 0.00001)
            assertEquals(0.0, actual.turnPenalty, 0.00001)
            assertTrue(vm.ranking.isEmpty())
            assertEquals(0, vm.earned)
            assertEquals(balanceBefore, vm.balance)
            assertEquals(completedBefore, vm.completed)
            assertEquals(badgesBefore, vm.ownedBadges)
            assertTrue(vm.pendingBadges.isEmpty())
            vm.chooseConsoleLimit(50)
            assertEquals(50, vm.consoleLimitKph)
            assertEquals("A new test configuration cannot relabel the completed trace", 100, vm.consoleSessionLimitKph)
            assertTrue(vm.destinationName.contains("100km/h"))
            assertEquals(actual, vm.report)
        }
        ui.onNodeWithText("오늘의 주행습관 점수").assertExists()
        ui.onNodeWithText("1 / 1건").assertExists()
        ui.onNodeWithText("1km별 가점 보기").performClick()
        ui.onNodeWithText("급가속 1건 · 급감속(급제동 포함) 1건", substring = true).assertExists()
        ui.onNodeWithText("닫기").performClick()
    }

    @Test fun missingSpeedBreaksTheOneSecondWindowAndShortRunStaysProvisional() {
        startConsole()
        ui.runOnIdle {
            val base = SystemClock.elapsedRealtimeNanos() + 1_000_000L
            val input = (0..50).map { index ->
                val ns = base + index * 100_000_000L
                val speed = when {
                    index == 4 -> null // Two sub-second deceleration sections, not one full second.
                    index <= 10 -> 20.0 - index * .4
                    else -> 16.0
                }
                Sample(ns / 1_000_000L, speed, 30, speedTimestampNanos = ns)
            }
            input.forEach { model.receiveSample(it, it.atMs) }
            model.receiveVehicle(parkedVehicle(belt = null))
            model.finishConsoleTest()
            val actual = requireNotNull(model.report)
            val expected = ScoreEngine.evaluate(input.map { it.copy(limitKph = 80) })
            assertEquals(expected, actual)
            assertEquals(0, actual.accelerationEvents)
            assertEquals(0, actual.decelerationEvents)
            assertTrue(actual.longitudinalCoverage < 1.0)
            assertTrue(actual.validDistanceM < 500)
            assertNull("The minimum distance requirement is unchanged", actual.score)
            assertTrue("Measured bonuses remain inspectable without fabricating a final score",
                actual.earnedPoints > 0)
        }
    }

    @Test fun cancellingConsoleRestoresNormalNavigationAndBeltRequirements() {
        startConsole()
        ui.runOnIdle {
            model.receiveVehicle(parkedVehicle())
            model.cancel()
            assertFalse(model.consoleTest)
            assertEquals(Phase.IDLE, model.phase)
            val round = ChallengeRound("검증", Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600))
            model.accept(round)
            assertEquals("Normal mode still requires a real destination", Phase.IDLE, model.phase)
            val now = SystemClock.elapsedRealtime()
            model.receiveVehicle(parkedVehicle().copy(destination = "여의도한강공원",
                destinationId = "normal-after-console", destinationPoint = hangangVenue,
                remainingM = 14_000.0, destinationAt = now))
            model.accept(round)
            assertEquals("Normal mode still requires the driver's belt", Phase.IDLE, model.phase)
            assertFalse(model.activeJourney)
        }
    }
}
