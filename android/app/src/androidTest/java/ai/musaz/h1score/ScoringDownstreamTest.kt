package ai.musaz.h1score

import ai.musaz.h1score.core.*
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.delay
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.DayOfWeek
import java.time.temporal.TemporalAdjusters
import java.util.UUID

/** Source-timestamped replay through the recorder and PR #2's real aggregation path. */
class ScoringDownstreamTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()
    private val applications = mutableListOf<TestApplication>()
    private val stores = mutableListOf<ViewModelStore>()

    @After fun cleanIsolatedState() {
        ui.runOnIdle { stores.forEach(ViewModelStore::clear) }
        applications.forEach { it.prefs.edit().clear().commit() }
    }

    @Test fun newScoreReachesResultHomeSafetyBadgesAndSurvivesModelRecreation() {
        val app = application()
        val legacy = legacyDrive()
        CompletedDriveStore(app.prefs).save(legacy)
        lateinit var restored: ChallengeViewModel
        ui.runOnIdle {
            val vm = model(app)
            // 8.75 km compliant + 1.25 km speeding = 60 + 21.875 + 13.125 = 95.
            val report = completeLiveTrip(vm, compliantMeters = 8_750)
            assertEquals(95, report.score)
            assertEquals(ScoreEngine.VERSION, report.scoringVersion)
            assertEquals(21.875, report.speedPoints, 1e-8)
            assertEquals(13.125, report.accelerationPoints, 1e-8)
            assertTrue(report.valid)
            assertEquals(Screen.RESULT, vm.screen)
            val day = LocalDate.now(ChallengeRound.seoul)
            assertEquals(95, vm.collectionProgress.weeklyScoreAverage(day))
            assertEquals(1, vm.collectionProgress.scoreDays(95))
            assertEquals(0, CollectionProgressStore(app.prefs).read(true).challengeDays)

            val saved = CompletedDriveStore(app.prefs).readAll()
            assertEquals(2, saved.size)
            assertEquals(legacy, saved.single { it.session.id == legacy.session.id })
            assertEquals(report, saved.single { it.session.id != legacy.session.id }.report)
            stores.last().clear()

            // A fresh model must read all three downstream results from persisted state.
            restored = model(app)
            restored.receiveVehicle(parked())
            restored.openCompletedResult()
            assertEquals(report, restored.report)
            assertEquals(saved, restored.completedDrives)
            assertEquals(95, restored.collectionProgress.weeklyScoreAverage(day))
            assertEquals(1, restored.collectionProgress.scoreDays(95))
        }
        ui.setContent {
            // These UI checks do not depend on a physical vehicle updating the fake input.
            LaunchedEffect(restored) {
                while (true) {
                    restored.receiveVehicle(parked())
                    delay(200)
                }
            }
            H1App(restored)
        }
        ui.onNodeWithText("95").assertExists()
        ui.onNodeWithText("기본 60점 + 속도 21.", substring = true).assertExists()
        ui.runOnIdle { restored.navigate(Screen.HOME) }
        ui.onNodeWithText("이번 주 안전점수").assertIsDisplayed()
        ui.onNodeWithText("95점").assertIsDisplayed()
        ui.onNodeWithText("일별 최고점 평균").assertIsDisplayed()
        ui.runOnIdle { restored.navigate(Screen.BADGES) }
        val filter = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)
        ui.onNode(hasText("안전운전") and filter).performClick()
        val goodHabit = hasText("좋은 습관") and hasText("1 / 5일")
        ui.onNode(hasScrollToIndexAction()).performScrollToNode(goodHabit)
        ui.onNode(goodHabit).assertIsDisplayed()
        val preciseDriving = hasText("정교한 운전") and hasText("1 / 5일")
        ui.onNode(hasScrollToIndexAction()).performScrollToNode(preciseDriving)
        ui.onNode(preciseDriving).assertIsDisplayed()
        assertEquals(legacy, CompletedDriveStore(app.prefs).readAll().single { it.session.id == legacy.session.id })
    }

    @Test fun newScoresKeepTheDailyBestAndLateArrivalDoesNotAdvanceSafetyProgress() {
        val app = application()
        ui.runOnIdle {
            val vm = model(app)
            val day = LocalDate.now(ChallengeRound.seoul)
            assertEquals(80, completeLiveTrip(vm, compliantMeters = 5_000).score)
            assertEquals(1, vm.collectionProgress.scoreDays(80))
            assertEquals(0, vm.collectionProgress.scoreDays(90))
            assertEquals(0, vm.collectionProgress.scoreDays(95))

            assertEquals(95, completeLiveTrip(vm, compliantMeters = 8_750).score)
            assertEquals(1, vm.collectionProgress.scoreDays(90))
            assertEquals(1, vm.collectionProgress.scoreDays(95))
            assertEquals(90, completeLiveTrip(vm, compliantMeters = 7_500).score)
            assertEquals(mapOf(day to 95), vm.collectionProgress.scoresByDay)
            assertEquals(95, vm.collectionProgress.weeklyScoreAverage(day))

            // A measured 100 is still saved as a result, but a late challenge is not a
            // qualifying day for the home summary or the safety collection.
            assertEquals(100, completeLiveTrip(vm, compliantMeters = 10_000, late = true).score)
            assertTrue(vm.missedDeadline)
            assertEquals(mapOf(day to 95), vm.collectionProgress.scoresByDay)
            assertEquals(vm.collectionProgress, CollectionProgressStore(app.prefs).read(false))
            assertEquals(4, CompletedDriveStore(app.prefs).readAll().size)
            assertEquals(0, CollectionProgressStore(app.prefs).read(true).challengeDays)
        }
    }

    @Test fun unavailableScoreAndBeltDisqualificationCannotOverwriteExistingProgress() {
        val app = application()
        ui.runOnIdle {
            val vm = model(app)
            completeLiveTrip(vm, compliantMeters = 8_750)
            val progress = vm.collectionProgress
            val distance = vm.recordedDistanceMeters
            val badges = vm.ownedBadges
            val balance = vm.balance

            val coarse = beginLiveTrip(vm)
            for (i in 0..1_000) vm.receiveSample(Sample(coarse.startedAt + i * 1_000L, 10.0, 50))
            arrive(vm, coarse)
            assertNull(vm.report!!.score)
            assertEquals(progress, vm.collectionProgress)
            assertEquals(distance, vm.recordedDistanceMeters)
            assertEquals(badges, vm.ownedBadges)
            assertEquals(balance, vm.balance)

            val beforeDisqualification = CompletedDriveStore(app.prefs).readAll()
            val disqualified = beginLiveTrip(vm)
            val now = SystemClock.elapsedRealtime()
            vm.receiveVehicle(disqualified.route.copy(belt = false, beltAt = now, speedAt = now), now)
            assertEquals(Phase.DISQUALIFIED, vm.phase)
            assertNull(vm.report)
            assertTrue(vm.ranking.isEmpty())
            assertTrue(vm.pendingBadges.isEmpty())
            // Re-buckling and an arrival event cannot restore the disqualified session.
            arrive(vm, disqualified)
            assertEquals(Phase.DISQUALIFIED, vm.phase)
            assertEquals(beforeDisqualification, CompletedDriveStore(app.prefs).readAll())
            assertEquals(progress, vm.collectionProgress)
            assertEquals(progress, CollectionProgressStore(app.prefs).read(false))
            assertEquals(distance, vm.recordedDistanceMeters)
            assertEquals(badges, vm.ownedBadges)
            assertEquals(balance, vm.balance)
        }
    }

    @Test fun weeklySummaryAveragesDistinctDailyHighScoresAndRestoresTheSameValue() {
        val app = application()
        val today = LocalDate.now(ChallengeRound.seoul)
        // An explicit week-end reference keeps this aggregation test independent of
        // the test runner's weekday. The other day is only an isolated store fixture.
        val referenceDate = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
        val otherDay = if (today.dayOfWeek == DayOfWeek.MONDAY) today.plusDays(1) else today.minusDays(1)
        CollectionProgressStore(app.prefs).saveChallenge(false,
            CollectionProgress().recordChallenge(otherDay, 100))
        ui.runOnIdle {
            val vm = model(app)
            assertEquals(95, completeLiveTrip(vm, compliantMeters = 8_750).score)
            assertEquals(mapOf(otherDay to 100, today to 95), vm.collectionProgress.scoresByDay)
            assertEquals(98, vm.collectionProgress.weeklyScoreAverage(referenceDate))
            assertEquals(2, vm.collectionProgress.scoreDays(95))
            // Another lower-scoring trip on today's date must not weight the average
            // by trip count or add a third qualifying day to safety badges.
            assertEquals(80, completeLiveTrip(vm, compliantMeters = 5_000).score)
            assertEquals(98, vm.collectionProgress.weeklyScoreAverage(referenceDate))
            assertEquals(2, vm.collectionProgress.challengeDays)
            val restored = model(app)
            assertEquals(vm.collectionProgress, restored.collectionProgress)
            assertEquals(98, restored.collectionProgress.weeklyScoreAverage(referenceDate))
            assertEquals(2, restored.collectionProgress.scoreDays(95))
        }
    }

    private data class Recording(val route: LiveVehicle, val startedAt: Long, val round: ChallengeRound)

    private fun beginLiveTrip(vm: ChallengeViewModel): Recording {
        val now = SystemClock.elapsedRealtime()
        val spot = challengeSpots.first()
        val route = parked().copy(destination = spot.name, destinationId = "downstream-${UUID.randomUUID()}",
            destinationPoint = spot.point, remainingM = 12_000.0, destinationAt = now)
        vm.receiveVehicle(route, now)
        val instant = Instant.now()
        val round = ChallengeRound("점수 전달 검증", instant.minusSeconds(60), instant.plusSeconds(3_600))
        vm.accept(round)
        assertEquals(Phase.RESERVED, vm.phase)
        val startedAt = SystemClock.elapsedRealtime()
        val recording = route.copy(remainingM = 10_000.0, destinationAt = startedAt,
            speedAt = startedAt, beltAt = startedAt)
        vm.receiveVehicle(recording, startedAt)
        assertEquals(Phase.RECORDING, vm.phase)
        return Recording(recording, startedAt, round)
    }

    private fun completeLiveTrip(vm: ChallengeViewModel, compliantMeters: Int, late: Boolean = false): ScoreReport {
        val recording = beginLiveTrip(vm)
        // At 10 m/s, each 100 ms interval covers exactly one metre. The limit is the
        // only varying input, so expected source-branch scores are calculable by hand.
        for (meter in 0..10_000) {
            vm.receiveSample(Sample(recording.startedAt + meter * 100L, 10.0,
                if (meter <= compliantMeters) 50 else 30))
        }
        arrive(vm, recording, if (late) recording.round.closesAt.plusSeconds(1) else Instant.now())
        assertEquals(Phase.FINISHED, vm.phase)
        return requireNotNull(vm.report)
    }

    private fun arrive(vm: ChallengeViewModel, recording: Recording, arrival: Instant = Instant.now()) {
        val now = SystemClock.elapsedRealtime()
        vm.receiveVehicle(recording.route.copy(arrived = true, arrivedAt = arrival, remainingM = 0.0,
            speedMps = 0.0, speedAt = now, gearPark = true, belt = true, beltAt = now), now)
    }

    private fun parked(): LiveVehicle {
        val now = SystemClock.elapsedRealtime()
        return LiveVehicle(speedMps = 0.0, speedAt = now, gearPark = true, uxRestricted = false,
            belt = true, beltAt = now)
    }

    private fun legacyDrive() = CompletedDrive(
        ChallengeSession("downstream-legacy", "old-route", 12_000.0, Phase.FINISHED),
        ScoreReport(92, 89.0, 100.0, 10_300.0, 1.0, true, 0, 1_134.0),
        "보존할 기존 주행", true, emptyList(), 80, false, completedAtEpochMs = 1_000)

    private fun application() = TestApplication(ui.activity.applicationContext).also(applications::add)
    private fun model(app: Application) = ChallengeViewModel(app).also { vm ->
        stores += ViewModelStore().apply { put("downstream-test", vm) }
    }

    private class TestApplication(context: Context) : Application() {
        init { attachBaseContext(context) }
        val prefs: SharedPreferences = context.getSharedPreferences("test-score-downstream-${UUID.randomUUID()}", MODE_PRIVATE)
        override fun getSharedPreferences(name: String, mode: Int) = prefs
        override fun startForegroundService(service: Intent): ComponentName? = service.component
        override fun stopService(name: Intent) = true
        override fun startActivity(intent: Intent) = Unit
    }
}
