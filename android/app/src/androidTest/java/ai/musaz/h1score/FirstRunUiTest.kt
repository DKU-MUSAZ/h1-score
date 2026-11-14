package ai.musaz.h1score

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import kotlinx.coroutines.delay
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID
import androidx.test.platform.app.InstrumentationRegistry
import android.os.SystemClock
import android.graphics.Bitmap
import java.io.File

class FirstRunUiTest {
    @get:Rule val ui=createAndroidComposeRule<ComponentActivity>()

    @Test fun consentNicknameTourAndRestartAreOneTime() {
        val video=InstrumentationRegistry.getArguments().getString("demoVideo")=="true"
        val prefs=ui.activity.getSharedPreferences("onboarding-test-${UUID.randomUUID()}",Context.MODE_PRIVATE)
        if(!video) prefs.edit().putInt("balance-live",240).putLong("recorded-distance-live",13500L).commit()
        val app=object: android.app.Application() {
            init {attachBaseContext(ui.activity.applicationContext)}
            override fun getSharedPreferences(name:String,mode:Int)=prefs
        }
        var driving by mutableStateOf(false)
        fun vehicle() = LiveVehicle(
            speedMps = if (driving) 2.0 else 0.0,
            speedAt = SystemClock.elapsedRealtime(),
            gearPark = !driving,
            uxRestricted = driving,
        )
        val vm=ui.runOnIdle { ChallengeViewModel(app).apply {
            receiveVehicle(vehicle())
        } }
        if(!video) ui.runOnIdle {vm.addShortcut("학교")}
        var generation by mutableIntStateOf(0)
        ui.setContent {
            LaunchedEffect(Unit) {
                while (true) {
                    vm.receiveVehicle(vehicle())
                    delay(500)
                }
            }
            key(generation) { FirstRunHost(vm, prefs, {}) }
        }
        fun capture(name:String) {
            ui.waitForIdle()
            if(video) {
                SystemClock.sleep(2500)
                ui.runOnIdle { vm.receiveVehicle(vehicle()) }
                ui.waitForIdle()
                File(ui.activity.filesDir,"first-run-video-timing.txt").appendText("$name ${SystemClock.elapsedRealtime()}\n")
                InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let {bitmap ->
                    File(ui.activity.filesDir,"first-run-$name.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
                }
            }
        }
        if(video) File(ui.activity.filesDir,"first-run-video-timing.txt").writeText("")
        capture("01-welcome")
        ui.onNodeWithText("무사즈와 시작하기").performClick()
        capture("02-consent")
        ui.onNodeWithText("동의하고 계속").assertIsNotEnabled()
        ui.onNodeWithTag("data-consent").performClick()
        if(!video) {
            ui.onNodeWithText("이용 항목과 보관 방법 자세히 보기").performClick()
            ui.runOnIdle { driving = true; vm.receiveVehicle(vehicle()) }
            ui.onNodeWithText("확인").assertDoesNotExist()
            ui.onNodeWithText("동의하고 계속").assertIsNotEnabled()
            ui.runOnIdle { driving = false; vm.receiveVehicle(vehicle()) }
        }
        ui.onNodeWithText("동의하고 계속").performClick()
        ui.runOnIdle {generation++}
        ui.onNodeWithText("어떻게 불러드릴까요?").assertIsDisplayed()
        capture("03-nickname")
        ui.onNodeWithTag("first-nickname").performTextClearance()
        ui.onNodeWithText("닉네임으로 시작").assertIsNotEnabled()
        ui.onNodeWithTag("first-nickname").performTextInput("무사즈 친구")
        capture("04-nickname-filled")
        ui.onNodeWithText("닉네임으로 시작").performClick()
        ui.onNodeWithText("목적지를 정해볼까요?").assertIsDisplayed()
        capture("05-tour-search")
        repeat(4) { step ->
            ui.onNodeWithText("다음").performClick()
            ui.onNodeWithText("무사즈의 첫 안내  ${step + 2} / 5").assertIsDisplayed()
            capture("tour-${step + 2}")
        }
        ui.onNodeWithText("무사즈와 출발하기").performClick()
        capture("10-complete")
        ui.runOnIdle {assertEquals("무사즈 친구",vm.nickname);generation++}
        capture("11-reopen")
        ui.onNodeWithText("무사즈와 시작하기").assertDoesNotExist()
        ui.onNodeWithText("목적지를 정해볼까요?").assertDoesNotExist()
        ui.onAllNodesWithText("챌린지").assertCountEquals(2)
        if(!video) {
            val balance=vm.balance
            val shortcuts=vm.destinationShortcuts
            val setupBefore=prefs.all.filterKeys {it.startsWith("first-run-")}
            ui.runOnIdle {vm.navigate(Screen.PROFILE)}
            ui.onNodeWithText("사용법 다시 보기").performClick()
            ui.onNodeWithText("목적지를 정해볼까요?").assertIsDisplayed()
            ui.onNodeWithText("무사즈와 시작하기").assertDoesNotExist()
            ui.onNodeWithText("동의하고 계속").assertDoesNotExist()
            ui.onNodeWithText("어떻게 불러드릴까요?").assertDoesNotExist()
            // Recreating the host during replay must reopen the menu tour, not setup.
            ui.runOnIdle {generation++}
            ui.onNodeWithText("목적지를 정해볼까요?").assertIsDisplayed()
            repeat(4) {ui.onNodeWithText("다음").performClick()}
            ui.onNodeWithText("무사즈와 출발하기").performClick()
            ui.runOnIdle {generation++;vm.navigate(Screen.PROFILE)}
            ui.onNodeWithText("목적지를 정해볼까요?").assertDoesNotExist()
            // A second replay starts at the first menu and can be skipped.
            ui.onNodeWithText("사용법 다시 보기").performClick()
            ui.onNodeWithText("목적지를 정해볼까요?").assertIsDisplayed()
            ui.onNodeWithText("바로 시작하기").performClick()
            ui.runOnIdle {generation++}
            ui.onNodeWithText("목적지를 정해볼까요?").assertDoesNotExist()
            ui.runOnIdle {
                assertEquals(setupBefore,prefs.all.filterKeys {it.startsWith("first-run-")})
                assertFalse(prefs.getBoolean("tutorial-replay-v1",false))
                assertEquals(balance,vm.balance)
                assertEquals(shortcuts,vm.destinationShortcuts)
                assertEquals("무사즈 친구",vm.nickname)
                val fresh=ChallengeViewModel(app)
                assertEquals(240,fresh.balance)
                assertEquals(13500L,fresh.recordedDistanceMeters)
                assertEquals(shortcuts,fresh.destinationShortcuts)
            }
        }
        prefs.edit().clear().commit()
    }
}
