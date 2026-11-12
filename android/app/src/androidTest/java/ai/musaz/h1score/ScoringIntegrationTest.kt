package ai.musaz.h1score

import ai.musaz.h1score.core.*
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.delay
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.io.File
import java.util.UUID
import kotlin.math.min

/** Replay source-timestamped signals through the same recorder used by the vehicle adapter. */
class ScoringIntegrationTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()
    private val stores = mutableListOf<ViewModelStore>()
    private val apps = mutableListOf<TestApplication>()

    @After fun cleanup() {
        ui.runOnIdle { stores.forEach(ViewModelStore::clear) }
        apps.forEach { it.prefs.edit().clear().commit() }
    }

    @Test fun liveHighRateTripUsesNewFormulaAndPreservesLegacyHistory() {
        val app = application()
        val legacy = legacyDrive()
        CompletedDriveStore(app.prefs).save(legacy)
        ui.runOnIdle {
            val vm = model(app)
            val start = startRecording(vm)
            // An old queue sample must not be attributed to the new recording.
            vm.receiveSample(Sample(start - 1_000, 70.0, 10))
            for (i in 0..10_000) vm.receiveSample(Sample(start + i * 100L, 10.0, 50))
            finish(vm, start + 1_000_000)
            val result = requireNotNull(vm.report)
            assertEquals(100, result.score)
            assertEquals(25.0, result.speedPoints, 1e-8)
            assertEquals(15.0, result.accelerationPoints, 1e-8)
            assertEquals(10_000.0, result.validDistanceM, 1e-8)
            assertEquals(ScoreEngine.VERSION, result.scoringVersion)
            assertTrue(result.longitudinalReady)
            assertEquals(Phase.FINISHED, vm.phase)
            val restored = CompletedDriveStore(app.prefs).readAll()
            assertEquals(2, restored.size)
            assertEquals(legacy, restored.single { it.session.id == legacy.session.id })
            assertEquals(result, restored.single { it.session.id != legacy.session.id }.report)
        }
    }

    @Test fun coarseOrEmptyInputCannotProduceAConfirmedStartingScore() {
        val app = application()
        ui.runOnIdle {
            val coarse = model(app)
            val start = startRecording(coarse)
            for (i in 0..1_000) coarse.receiveSample(Sample(start + i * 1_000L, 10.0, 50))
            finish(coarse, start + 1_000_000)
            assertNull(coarse.report!!.score)
            assertFalse(coarse.report!!.longitudinalReady)
            assertTrue(coarse.ranking.isEmpty())

            val empty = model(app)
            val emptyStart = startRecording(empty)
            finish(empty, emptyStart + 100)
            assertNull(empty.report!!.score)
            assertFalse(empty.report!!.valid)
        }
    }

    @Test fun newScoreDetailsKeepOverviewAndShowVersionSpecificExplanation() {
        val app = application()
        val legacy = legacyDrive()
        val start = 1_000L
        val report = ScoreEngine.evaluate((0..10_000).map { Sample(start + it * 100L, 10.0, 50) },
            listOf(TurnEvent(start + 2_000, start + 4_000, TurnDirection.RIGHT, TurnVerdict.UNUSED)))
        val newDrive = legacy.copy(session = legacy.session.copy(id = "new-score"), name = "새 산식 주행",
            report = report, completedAtEpochMs = 2_000, scoringVersion = ScoreEngine.VERSION)
        CompletedDriveStore(app.prefs).apply { save(legacy); save(newDrive) }
        val vm = ui.runOnIdle { model(app).apply { receiveVehicle(parked()); navigate(Screen.HISTORY) } }
        ui.setContent {
            // This UI-only model has no vehicle stream. Refresh the simulated park
            // state while opening dialogs and returning to the history list.
            LaunchedEffect(vm) {
                while (true) {
                    vm.receiveVehicle(parked())
                    delay(200)
                }
            }
            MinimumFrame(vm)
        }
        ui.onNodeWithText("새 산식 주행").performScrollTo().performClick()
        ui.onNodeWithText("운전 습관 요약").assertIsDisplayed()
        ui.onNodeWithText("주요 주행 구간").assertIsDisplayed()
        ui.onNodeWithText("함께 도착한 무사즈").assertIsDisplayed()
        ui.onAllNodes(hasScrollAction()).assertCountEquals(0)
        ui.onAllNodesWithText("70% 반영", substring = true).assertCountEquals(0)
        val dial = ui.onNodeWithContentDescription("주행습관 점수 원").fetchSemanticsNode().boundsInRoot
        val formula = ui.onNodeWithTag("saved-score-breakdown").fetchSemanticsNode().boundsInRoot
        assertTrue("Score dial must not overlap the formula at minimum height", dial.bottom <= formula.top + 1f)
        ui.onNodeWithText("피드백 전체보기").performClick()
        ui.onNode(hasText("기본 60", substring = true) and hasAnyAncestor(isDialog())).assertExists()
        ui.onNodeWithContentDescription("닫기").performClick()
        ui.onNodeWithContentDescription("기록 목록으로").performClick()
        ui.runOnIdle { assertEquals(Screen.HISTORY, vm.screen) }
        ui.onNodeWithText(legacy.name).performScrollTo().performClick()
        val legacyBitmap = ui.onRoot().captureToImage().asAndroidBitmap()
        File(ui.activity.filesDir, "history-detail-legacy.png").outputStream().use {
            legacyBitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        val legacyLayout = "Legacy detail bounds: " +
            "70%=${ui.onNodeWithText("70% 반영", substring = true).fetchSemanticsNode().boundsInRoot}, " +
            "30%=${ui.onNodeWithText("30% 반영", substring = true).fetchSemanticsNode().boundsInRoot}, " +
            "scoreCard=${ui.onNodeWithTag("saved-score-card").fetchSemanticsNode().boundsInRoot}, " +
            "dial=${ui.onNodeWithContentDescription("주행습관 점수 원").fetchSemanticsNode().boundsInRoot}, " +
            "root=${ui.onRoot().fetchSemanticsNode().boundsInRoot}"
        android.util.Log.i("SavedDetailLayoutTest", legacyLayout)
        try {
            ui.onNodeWithText("70% 반영", substring = true).assertIsDisplayed()
            ui.onNodeWithText("30% 반영", substring = true).assertIsDisplayed()
        } catch (failure: AssertionError) {
            throw AssertionError("$legacyLayout\n${failure.message}", failure)
        }
        assertEquals(legacy, CompletedDriveStore(app.prefs).readAll().single { it.session.id == "legacy-92" })
    }

    private fun startRecording(vm: ChallengeViewModel): Long {
        val base = SystemClock.elapsedRealtime()
        val spot = challengeSpots.first()
        val route = parked().copy(destination = spot.name, destinationId = "scoring-route", destinationPoint = spot.point,
            remainingM = 12_000.0, destinationAt = base, belt = true, beltAt = base)
        vm.receiveVehicle(route, base)
        val now = Instant.now()
        vm.accept(ChallengeRound("새 산식 검증", now.minusSeconds(60), now.plusSeconds(3_600)))
        assertEquals(Phase.RESERVED, vm.phase)
        val start = base + 100
        vm.receiveVehicle(route.copy(remainingM = 10_000.0, destinationAt = start), start)
        assertEquals(Phase.RECORDING, vm.phase)
        return start
    }

    private fun finish(vm: ChallengeViewModel, now: Long) {
        vm.receiveVehicle(vm.live.copy(arrived = true, arrivedAt = Instant.now(), remainingM = 0.0,
            speedMps = 0.0, speedAt = SystemClock.elapsedRealtime(), gearPark = true), now)
    }

    private fun parked() = LiveVehicle(speedMps = 0.0, speedAt = SystemClock.elapsedRealtime(), gearPark = true,
        uxRestricted = false, belt = true, beltAt = SystemClock.elapsedRealtime())

    private fun legacyDrive() = CompletedDrive(
        ChallengeSession("legacy-92", "legacy-route", 12_000.0, Phase.FINISHED),
        ScoreReport(92, 89.0, 100.0, 10_300.0, 1.0, true, 0, 1_134.0),
        "기존 92점 주행", true, emptyList(), 80, false, completedAtEpochMs = 1_000)

    private fun application() = TestApplication(ui.activity.applicationContext).also(apps::add)
    private fun model(app: Application) = ChallengeViewModel(app).also { vm ->
        stores += ViewModelStore().apply { put("score-test", vm) }
    }

    @Composable private fun MinimumFrame(vm: ChallengeViewModel) {
        BoxWithConstraints {
            val density = LocalDensity.current
            val scale = min(maxWidth.value / 1180f, maxHeight.value / 680f)
            CompositionLocalProvider(LocalDensity provides Density(density.density * scale, density.fontScale)) {
                Box(Modifier.requiredSize(1180.dp, 680.dp)) { H1App(vm) }
            }
        }
    }

    private class TestApplication(context: Context) : Application() {
        init { attachBaseContext(context) }
        val prefs: SharedPreferences = context.getSharedPreferences("test-score-integration-${UUID.randomUUID()}", MODE_PRIVATE)
        override fun getSharedPreferences(name: String, mode: Int) = prefs
        override fun startForegroundService(service: Intent): ComponentName? = service.component
        override fun stopService(name: Intent) = true
        override fun startActivity(intent: Intent) = Unit
    }
}
