package ai.musaz.h1score

import ai.musaz.h1score.core.*
import android.app.Application
import android.car.VehicleGear
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
            assertEquals(10_000L, vm.recordedDistanceMeters)
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
            assertEquals(10_000L, restored.recordedDistanceMeters)
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
        ui.onNodeWithText("10.0 km").assertIsDisplayed()
        captureDownstream("compact-home")
        ui.onNodeWithText("일별 최고점 평균").assertIsDisplayed()
        ui.runOnIdle { restored.navigate(Screen.BADGES) }
        val filter = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)
        ui.onNode(hasText("명예 훈장") and filter).performClick()
        val goodHabit = hasText("좋은 습관") and hasText("티어 잠금")
        ui.onNode(hasScrollToIndexAction()).performScrollToNode(goodHabit)
        ui.onNode(goodHabit).assertIsDisplayed()
        val preciseDriving = hasText("정교한 운전") and hasText("티어 잠금")
        ui.onNode(hasScrollToIndexAction()).performScrollToNode(preciseDriving)
        ui.onNode(preciseDriving).assertIsDisplayed()
        assertEquals(legacy, CompletedDriveStore(app.prefs).readAll().single { it.session.id == legacy.session.id })
    }

    @Test fun anotherGameOnTheSameDayCannotReplaceTheRecordedScore() {
        val app = application()
        ui.runOnIdle {
            val vm = model(app)
            val day = LocalDate.now(ChallengeRound.seoul)
            assertEquals(80, completeLiveTrip(vm, compliantMeters = 5_000).score)
            assertEquals(1, vm.collectionProgress.scoreDays(80))
            assertEquals(0, vm.collectionProgress.scoreDays(90))
            assertEquals(0, vm.collectionProgress.scoreDays(95))

            assertRetryBlocked(vm)
            assertEquals(mapOf(day to 80), vm.collectionProgress.scoresByDay)
            assertEquals(80, vm.collectionProgress.weeklyScoreAverage(day))
            assertEquals(vm.collectionProgress, CollectionProgressStore(app.prefs).read(false))
            assertEquals(1, CompletedDriveStore(app.prefs).readAll().size)
            assertEquals(0, CollectionProgressStore(app.prefs).read(true).challengeDays)
        }
    }

    @Test fun unavailableScoreAndBeltDisqualificationCannotOverwriteExistingProgress() {
        // Each failure is a separate participant fixture; a finished game consumes the day.
        for (beltFailure in listOf(false, true)) {
            val app = application()
            val editor = app.prefs.edit()
            CollectionProgressStore(app.prefs).writeChallenge(editor, false,
                CollectionProgress().recordChallenge(LocalDate.now(ChallengeRound.seoul).minusDays(1), 95))
            editor.commit()
            ui.runOnIdle {
                val vm = model(app)
                val progress = vm.collectionProgress
                val distance = vm.recordedDistanceMeters
                val badges = vm.ownedBadges
                val balance = vm.balance

                val beforeDisqualification = CompletedDriveStore(app.prefs).readAll()
                if (!beltFailure) {
                    val coarse = beginLiveTrip(vm)
                    for (i in 0..1_000) vm.receiveSample(Sample(coarse.startedAt + i * 1_000L, 10.0, 50))
                    arrive(vm, coarse)
                    assertNull(vm.report!!.score)
                } else {
                    val disqualified = beginLiveTrip(vm)
                    val now = SystemClock.elapsedRealtime()
                    val moving = disqualified.route.copy(belt = false, beltAt = now, speedAt = now,
                        gearCode = VehicleGear.GEAR_DRIVE, gearAt = now, gearPark = false,
                        motionSpeedMps = 1.0, motionSpeedAt = now, passengerOccupied = false, passengerOccupiedAt = now)
                    vm.receiveVehicle(moving, now)
                    assertEquals(Phase.RECORDING, vm.phase)
                    vm.receiveVehicle(moving.copy(beltAt = now + 500, motionSpeedAt = now + 500,
                        gearAt = now + 500, passengerOccupiedAt = now + 500), now + 500)
                    assertEquals(Phase.DISQUALIFIED, vm.phase)
                    assertNull(vm.report!!.score)
                    assertFalse(vm.report!!.valid)
                    assertTrue(vm.ranking.isEmpty())
                    assertTrue(vm.pendingBadges.isEmpty())
                    // Re-buckling and an arrival event cannot restore the disqualified session.
                    arrive(vm, disqualified)
                    assertEquals(Phase.DISQUALIFIED, vm.phase)
                    val saved = CompletedDriveStore(app.prefs).readAll()
                    assertEquals(beforeDisqualification.size + 1, saved.size)
                    assertTrue(saved.containsAll(beforeDisqualification))
                    assertEquals(DriveOutcome.DISQUALIFIED, saved.single { it.session.id == vm.session!!.id }.outcome)
                }
                assertEquals(progress, vm.collectionProgress)
                assertEquals(progress, CollectionProgressStore(app.prefs).read(false))
                assertEquals(distance, vm.recordedDistanceMeters)
                assertEquals(badges, vm.ownedBadges)
                assertEquals(balance, vm.balance)
            }
        }
    }

    @Test fun weeklySummaryAveragesDistinctDailyHighScoresAndRestoresTheSameValue() {
        val app = application()
        val today = LocalDate.now(ChallengeRound.seoul)
        // An explicit week-end reference keeps this aggregation test independent of
        // the test runner's weekday. The other day is only an isolated store fixture.
        val referenceDate = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
        val otherDay = if (today.dayOfWeek == DayOfWeek.MONDAY) today.plusDays(1) else today.minusDays(1)
        val editor = app.prefs.edit()
        CollectionProgressStore(app.prefs).writeChallenge(editor, false,
            CollectionProgress().recordChallenge(otherDay, 100).recordChallenge(referenceDate.minusDays(7), 61))
        editor.commit()
        ui.runOnIdle {
            val vm = model(app)
            assertEquals(95, completeLiveTrip(vm, compliantMeters = 8_750).score)
            assertEquals(mapOf(otherDay to 100, today to 95, referenceDate.minusDays(7) to 61), vm.collectionProgress.scoresByDay)
            assertEquals(98, vm.collectionProgress.weeklyScoreAverage(referenceDate))
            assertEquals(2, vm.collectionProgress.scoreDays(95))
            assertRetryBlocked(vm)
            assertEquals(98, vm.collectionProgress.weeklyScoreAverage(referenceDate))
            assertEquals(3, vm.collectionProgress.challengeDays)
            assertEquals(10_000L, vm.recordedDistanceMeters)
            assertNull(vm.collectionProgress.weeklyScoreAverage(referenceDate.plusDays(1)))
            val restored = model(app)
            assertEquals(vm.collectionProgress, restored.collectionProgress)
            assertEquals(98, restored.collectionProgress.weeklyScoreAverage(referenceDate))
            assertEquals(10_000L, restored.recordedDistanceMeters)
            assertEquals(2, restored.collectionProgress.scoreDays(95))
        }
    }

    @Test fun liveChallengeCreditsNewBadgesWithoutPlacementMileage() {
        val app = application()
        val lastMonday = LocalDate.now(ChallengeRound.seoul).minusWeeks(1).with(DayOfWeek.MONDAY)
        val previous = CollectionProgress((0L..3L).associate { lastMonday.plusDays(it) to 92 })
        val editor = app.prefs.edit()
        CollectionProgressStore(app.prefs).writeChallenge(editor, false, previous)
        editor.commit()

        ui.runOnIdle {
            val vm = model(app)
            assertEquals(95, completeLiveTrip(vm, compliantMeters = 8_750).score)
            assertEquals(0, vm.earned)
            assertEquals(15, vm.badgeMileageEarned)
            assertEquals(15, vm.balance)
            assertEquals(15, CompletedDriveStore(app.prefs).read()?.badgeMileage)

            assertRetryBlocked(vm)
            assertEquals(15, vm.badgeMileageEarned) // The original result remains on screen.
            assertEquals(15, model(app).balance)
        }
    }

    @Test fun demoScoreCreditsMileageAndBadgesOnceThenRestoresTheSameHistory() {
        val app = application()
        val legacy = legacyDrive()
        CompletedDriveStore(app.prefs).save(legacy)
        val production = ui.activity.applicationContext.getSharedPreferences("h1-demo-v1", 0)
        val productionBefore = production.all.toMap()
        val day = LocalDate.now(ChallengeRound.seoul)
        val vm = ui.runOnIdle {
            model(app).apply {
                receiveVehicle(parked())
                switchSource()
                chooseDemoDrive(DemoDrive.GOOD)
                val now = Instant.now()
                accept(ChallengeRound("적립 연동 검증", now.minusSeconds(60), now.plusSeconds(600)))
                runDemo()
            }
        }
        // Exercise the production demo recorder and settlement, not a prefilled score or ledger.
        ui.waitUntil(40_000) { vm.phase == Phase.FINISHED && !vm.demoRunning }
        val first = ui.runOnIdle {
            val report = requireNotNull(vm.report)
            assertEquals(95, report.score)
            assertEquals(10_000.0, report.validDistanceM, 1e-8)
            assertEquals(ScoreEngine.VERSION, report.scoringVersion)
            assertTrue(report.longitudinalReady)
            assertTrue(report.longitudinalSampleRateHz >= 8.0)
            val own = vm.ranking.single { it.id == "나" }
            assertEquals(1, own.rank)
            assertEquals(100, own.miles)
            assertEquals(100, vm.earned)
            assertEquals(100, vm.balance)
            assertEquals(95, vm.collectionProgress.weeklyScoreAverage(day))
            assertEquals(1, vm.collectionProgress.scoreDays(95))
            assertEquals(1, vm.rankBadgeProgress.days(RankBadgeGoal.FIRST_RANK))
            assertTrue("top-three" in vm.ownedBadges)
            assertEquals(0, CollectionProgressStore(app.prefs).read(false).challengeDays)
            CompletedDriveStore(app.prefs).readAll().single { it.session.id != legacy.session.id }.also {
                assertEquals(report, it.report)
                assertEquals(100, it.earned)
            }
        }
        val restored = ui.runOnIdle {
            stores.last().clear()
            model(app).apply {
                receiveVehicle(parked())
                openCompletedResult()
                assertTrue(demo)
                assertEquals(first.report, report)
                assertEquals(100, earned)
                assertEquals(100, balance)
                assertEquals(95, collectionProgress.weeklyScoreAverage(day))
                assertEquals(1, rankBadgeProgress.days(RankBadgeGoal.FIRST_RANK))
                val beforeOpening = app.prefs.all.toMap()
                openCompletedResult()
                navigate(Screen.HISTORY)
                openHistoryRecord(first.session.id)
                assertEquals(first, selectedHistoryRecord)
                assertEquals(beforeOpening, app.prefs.all)
            }
        }
        ui.setContent {
            LaunchedEffect(restored) {
                while (true) {
                    restored.receiveVehicle(parked())
                    delay(200)
                }
            }
            H1App(restored)
        }
        ui.onNodeWithText("+100M").assertIsDisplayed(); ui.onNodeWithText("적립 마일리지").assertIsDisplayed()
        ui.runOnIdle { restored.navigate(Screen.WALLET) }
        ui.onNodeWithText("차곡차곡, 내 마일리지").assertIsDisplayed()
        ui.onNodeWithText("100", useUnmergedTree = true).assertIsDisplayed()
        captureDownstream("mileage-100")
        ui.runOnIdle { restored.navigate(Screen.BADGES) }
        val filter = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)
        ui.onNode(hasText("명예 훈장") and filter).performClick()
        val badge = hasText("차분한 출발") and hasText("1 / 5일")
        ui.onNode(hasScrollToIndexAction()).performScrollToNode(badge)
        ui.onNode(badge).assertIsDisplayed()
        captureDownstream("safety-badge-progress")

        val badges = ui.runOnIdle { restored.ownedBadges.toSet() }
        ui.runOnIdle {
            val now = Instant.now()
            restored.accept(ChallengeRound("같은 목적지 재검증", now.minusSeconds(60), now.plusSeconds(600)))
            restored.runDemo()
        }
        ui.waitUntil(40_000) { restored.phase == Phase.FINISHED && !restored.demoRunning }
        ui.runOnIdle {
            assertEquals(95, restored.report!!.score)
            assertEquals(100, restored.ranking.single { it.id == "나" }.miles)
            assertTrue(restored.notice.orEmpty().contains("하루 한 경기"))
            assertEquals("The rejected retry keeps the original result", 100, restored.earned)
            assertEquals(100, restored.balance)
            assertEquals(1, restored.collectionProgress.scoreDays(95))
            assertEquals(1, restored.rankBadgeProgress.days(RankBadgeGoal.FIRST_RANK))
            assertEquals(badges, restored.ownedBadges)
            assertTrue(restored.pendingBadges.isEmpty())
            val records = CompletedDriveStore(app.prefs).readAll()
            assertEquals(2, records.size)
            assertEquals(first, records.single { it.session.id == first.session.id })
            assertEquals(legacy, records.single { it.session.id == legacy.session.id })
            assertEquals(first.session.id, restored.session!!.id)
            stores.last().clear()
            val reopened = model(app).apply { receiveVehicle(parked()); openCompletedResult() }
            assertEquals(100, reopened.balance)
            assertEquals(100, reopened.earned)
            assertEquals(records, reopened.completedDrives)
            assertEquals(restored.collectionProgress, reopened.collectionProgress)
            assertEquals(restored.rankBadgeProgress, reopened.rankBadgeProgress)
            assertEquals(productionBefore, production.all)
        }
    }

    private fun captureDownstream(name: String) {
        val image = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        java.io.File(ui.activity.filesDir, "scoring-downstream-$name.png").outputStream().use {
            image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        image.recycle()
    }

    private data class Recording(val route: LiveVehicle, val startedAt: Long, val round: ChallengeRound)

    private fun assertRetryBlocked(vm: ChallengeViewModel) {
        val session = vm.session
        val now = Instant.now()
        val spot = challengeSpots.first()
        vm.receiveVehicle(parked().copy(destination = spot.name, destinationId = "retry-${UUID.randomUUID()}",
            destinationPoint = spot.point, remainingM = 12_000.0, destinationAt = SystemClock.elapsedRealtime()))
        vm.accept(ChallengeRound("같은 날 재참가", now.minusSeconds(60), now.plusSeconds(600)))
        assertFalse(vm.activeJourney)
        assertTrue(vm.notice.orEmpty().contains("하루 한 경기"))
        assertEquals(session, vm.session)
    }

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
            speedAt = startedAt, beltAt = startedAt, gearAt = startedAt,
            motionSpeedAt = startedAt, passengerOccupiedAt = startedAt)
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
            speedMps = 0.0, speedAt = now, gearPark = true, belt = true, beltAt = now,
            gearCode = VehicleGear.GEAR_PARK, gearAt = now, motionSpeedMps = 0.0, motionSpeedAt = now,
            passengerOccupied = false, passengerOccupiedAt = now), now)
    }

    private fun parked(): LiveVehicle {
        val now = SystemClock.elapsedRealtime()
        return LiveVehicle(speedMps = 0.0, speedAt = now, gearPark = true, uxRestricted = false,
            belt = true, beltAt = now, gearCode = VehicleGear.GEAR_PARK, gearAt = now,
            motionSpeedMps = 0.0, motionSpeedAt = now, passengerOccupied = false, passengerOccupiedAt = now)
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
