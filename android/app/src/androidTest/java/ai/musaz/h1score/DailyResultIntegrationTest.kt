package ai.musaz.h1score

import ai.musaz.h1score.core.*
import android.app.Application
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.util.UUID

/** Emulator integration with isolated preferences; exercises the real demo runner and settlement. */
class DailyResultIntegrationTest {
    @get:Rule val ui=createAndroidComposeRule<ComponentActivity>()
    private val prefName="daily-integration-${UUID.randomUUID()}"
    private val models=ViewModelStore()
    private lateinit var vm:ChallengeViewModel
    private var now=Instant.now()
    private val day get()=now.atZone(ChallengeRound.seoul).toLocalDate()
    private val prefs get()=ui.activity.getSharedPreferences(prefName,0)

    private fun parked() = LiveVehicle(speedMps=0.0, speedAt=SystemClock.elapsedRealtime(),
        gearPark=true, uxRestricted=false, belt=true)
    private fun round()=ChallengeRound("연동 검증",now.minusSeconds(60),now.plusSeconds(3600))
    private fun createModel() {
        vm=ChallengeViewModel(ui.activity.application as Application,prefName) { now }
        models.put(UUID.randomUUID().toString(),vm)
        vm.receiveVehicle(parked())
        vm.switchSource()
        assertTrue(vm.demo)
    }
    @After fun clean() {
        ui.runOnIdle { models.clear(); prefs.edit().clear().commit() }
    }
    private fun capture(name:String) {
        val bitmap=ui.onRoot().captureToImage().asAndroidBitmap()
        java.io.File(ui.activity.filesDir,"daily-$name.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
        }
    }

    @Test fun demoCompletionCreditsBadgesAndWalletOnceAndSurvivesRestart() {
        ui.runOnIdle {
            val progress=(1L..4L).fold(CollectionProgress()) { p,ago -> p.recordChallenge(day.minusDays(ago),100) }
            val editor=prefs.edit()
            CollectionProgressStore(prefs).writeChallenge(editor,true,progress)
            editor.commit()
            createModel()
        }
        ui.setContent { H1App(vm) }
        ui.runOnIdle { vm.accept(round()); assertTrue(vm.activeJourney); vm.runDemo() }
        ui.waitUntil(60_000) { vm.completedDrives.any { it.demo } }
        lateinit var result:CompletedDrive
        var balance=0
        ui.runOnIdle {
            result=CompletedDriveStore(prefs).read()!!
            assertEquals(ResultRecognition.ACCEPTED,result.recognition)
            assertEquals(day.toString(),result.participationDay)
            assertEquals(5,vm.collectionProgress.challengeDays)
            assertTrue(result.badgeMileage>0)
            assertTrue(result.awardedBadges.any { it.id=="join-5" })
            assertEquals(result.badgeMileage,result.awardedBadges.sumOf { it.mileage })
            balance=prefs.getInt("balance",0)
            assertEquals(result.earned+result.badgeMileage,balance)
            assertEquals(0,prefs.getInt("balance-live",0))
            assertEquals(0,CollectionProgressStore(prefs).read(false).challengeDays)
            // Re-delivered completion must not replace the originally credited record with zeros.
            ChallengeViewModel::class.java.getDeclaredMethod("finish",Instant::class.java).apply { isAccessible=true }.invoke(vm,now)
            assertEquals(result,CompletedDriveStore(prefs).read())
            assertEquals(balance,prefs.getInt("balance",0))
            assertEquals(1,vm.historyDrives.count { historyDate(it.completedAtEpochMs)==day })
            vm.openHistoryRecord(result.session.id)
        }
        ui.waitForIdle()
        ui.onAllNodesWithText("가감속 공통 +13.30점").assertCountEquals(0)
        capture("accepted-history")
        ui.onNodeWithText("제한속도를 초과한 거리가", substring=true).performScrollTo().assertIsDisplayed()
        capture("feedback-values")
        ui.onNode(hasText("마일리지 자세히 보기") or hasContentDescription("마일리지 자세히 보기")).performScrollTo().performClick()
        ui.onNodeWithText("첫 발걸음 · 획득 보상 +15M").assertExists()
        val mileage=ui.onNode(isDialog()).captureToImage().asAndroidBitmap()
        java.io.File(ui.activity.filesDir,"daily-badge-rewards.png").outputStream().use {
            mileage.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
        }
        ui.onNodeWithContentDescription("닫기").performClick()
        ui.runOnIdle {
            vm.receiveVehicle(parked())
            vm.accept(round())
            assertTrue(vm.notice.orEmpty().contains("하루 한 경기"))
            createModel()
            vm.accept(round())
            assertFalse(vm.activeJourney)
            assertTrue(vm.notice.orEmpty().contains("하루 한 경기"))
            assertEquals(balance,prefs.getInt("balance",0))
            assertEquals(result,CompletedDriveStore(prefs).read())
        }
    }

    @Test fun disqualifiedDayCannotReenterAndDoesNotEarnProgressOrRewards() {
        ui.runOnIdle { createModel(); vm.accept(round()); assertTrue(vm.activeJourney) }
        // Let Android deliver the foreground service start before simulating an immediate failure.
        Thread.sleep(1000)
        ui.setContent { H1App(vm) }
        ui.runOnIdle {
            val session=vm.session!!.copy(phase=Phase.DISQUALIFIED)
            ChallengeViewModel::class.java.getDeclaredMethod("saveBeltOutcome",ChallengeSession::class.java,
                DriveOutcome::class.java,BeltFailureReason::class.java).apply { isAccessible=true }
                .invoke(vm,session,DriveOutcome.DISQUALIFIED,BeltFailureReason.PASSENGER_UNBUCKLED)
            val saved=CompletedDriveStore(prefs).read()!!
            assertEquals(ResultRecognition.DISQUALIFIED,saved.recognition)
            assertNull(saved.report.score)
            assertEquals(0,saved.earned+saved.badgeMileage)
            assertEquals(0,vm.collectionProgress.challengeDays)
            assertTrue(saved.awardedBadges.isEmpty())
            vm.openHistoryRecord(saved.session.id)
        }
        ui.waitForIdle()
        ui.onNodeWithText("조수석 안전벨트 미착용").assertExists()
        capture("disqualified-history")
        ui.runOnIdle {
            vm.accept(round())
            assertFalse(vm.activeJourney)
            assertTrue(vm.notice.orEmpty().contains("하루 한 경기"))
            createModel()
            vm.accept(round())
            assertFalse(vm.activeJourney)
            now=now.plusSeconds(86400)
            vm.receiveVehicle(parked())
            vm.accept(round())
            assertTrue(vm.activeJourney)
        }
        Thread.sleep(1000)
        ui.runOnIdle { vm.receiveVehicle(parked()); vm.cancel() }
    }

    @Test fun crossingMidnightKeepsTheReservationDayForProgressAndMileage() {
        val reservedDay=day
        now=reservedDay.atTime(23,59,55).atZone(ChallengeRound.seoul).toInstant()
        ui.runOnIdle { createModel(); vm.accept(round()); assertTrue(vm.activeJourney) }
        Thread.sleep(1000)
        ui.setContent { H1App(vm) }
        ui.runOnIdle { vm.receiveVehicle(parked()); vm.runDemo(); assertTrue(vm.demoRunning); now=now.plusSeconds(120) }
        ui.waitUntil(60_000) { vm.completedDrives.any { it.demo } }
        ui.runOnIdle {
            val saved=CompletedDriveStore(prefs).read()!!
            assertEquals(ResultRecognition.ACCEPTED,saved.recognition)
            assertEquals(reservedDay.toString(),saved.participationDay)
            assertEquals(reservedDay.plusDays(1),historyDate(saved.completedAtEpochMs))
            assertEquals(setOf(reservedDay),vm.collectionProgress.scoresByDay.keys)
            assertTrue(prefs.getStringSet("daily",emptySet())!!.all { it.startsWith("$reservedDay|") })
            assertFalse(DailyParticipationStore(prefs).available(true,reservedDay))
            assertTrue(DailyParticipationStore(prefs).available(true,reservedDay.plusDays(1)))
        }
    }

    @Test fun lateDemoArrivalDisqualifiesAndKeepsTheDayConsumedAfterReload() {
        ui.runOnIdle { createModel() }
        ui.setContent { H1App(vm) }
        ui.runOnIdle {
            vm.receiveVehicle(parked())
            vm.accept(ChallengeRound("마감 시험",now.minusSeconds(60),now.plusSeconds(1)))
            assertTrue(vm.activeJourney)
            vm.runDemo()
            assertTrue(vm.demoRunning)
        }
        ui.waitUntil(60_000) { vm.completedDrives.any { it.demo } }
        ui.onNodeWithText("시간 초과 실격").assertExists()
        capture("deadline-disqualified")
        ui.runOnIdle {
            val saved=CompletedDriveStore(prefs).read()!!
            assertEquals(DriveOutcome.DISQUALIFIED,saved.outcome)
            assertEquals(ResultRecognition.DEADLINE,saved.recognition)
            assertEquals("실격",historyResultLabel(saved))
            assertNull(saved.report.score)
            assertEquals(0,saved.earned+saved.badgeMileage)
            assertEquals(0,vm.collectionProgress.challengeDays)
            assertTrue(saved.awardedBadges.isEmpty())
            assertTrue(saved.ranking.isEmpty())
            assertEquals(0,prefs.getInt("balance",0))
            createModel()
            vm.accept(round())
            assertFalse(vm.activeJourney)
            assertTrue(vm.notice.orEmpty().contains("하루 한 경기"))
            assertEquals(saved,CompletedDriveStore(prefs).read())
        }
    }
}
