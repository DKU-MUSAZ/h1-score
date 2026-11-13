package ai.musaz.h1score

import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MyProfileUiTest {
    @get:Rule val ui=createAndroidComposeRule<ComponentActivity>()

    @Test fun connectedProfileShowsOnlyReceivedUsefulVehicleValues() {
        val vm = (ui.activity.application as H1Application).model
        ui.runOnIdle { vm.enterForeground() }
        ui.waitUntil(10_000) { vm.live.parked }
        ui.runOnIdle {
            if (vm.demo) vm.switchSource()
            vm.navigate(Screen.PROFILE)
        }
        ui.setContent { H1App(vm) }
        val values = kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { vm.connection.readProfileEnergy() }
        }
        ui.activity.filesDir.resolve("profile-live-values.txt").writeText(values.toString())
        ui.onNodeWithText("예상 주행 가능거리").assertExists()
        ui.onNodeWithText("트렁크").assertExists()
        ui.onNodeWithText("창문").assertExists()
        ui.onNodeWithText("핸들 각도").assertDoesNotExist()
        ui.onNodeWithText("타이어 공기압").assertDoesNotExist()
        ui.onNodeWithText("연료 잔량").assertDoesNotExist()
        ui.onNodeWithText("창문").performScrollTo()
        SystemClock.sleep(4000)
        val screenshot = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        ui.activity.filesDir.resolve("profile-live.png").outputStream().use {
            screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        screenshot.recycle()
        ui.runOnIdle { vm.leaveForeground() }
    }

    @Test fun profileShowsLabeledDemoDataAndPersistsNicknameWithoutChangingVehicleData() {
        val app=ui.activity.application as H1Application
        val vm=app.model
        val prefs=app.getSharedPreferences("h1-demo-v1",0)
        val previous=prefs.getString("profile-nickname",null)
        try {
            ui.runOnIdle {
                val now=SystemClock.elapsedRealtime()
                vm.receiveVehicle(LiveVehicle(speedMps=0.0,speedAt=now,gearPark=true,uxRestricted=false))
                if(!vm.demo) vm.switchSource()
                vm.receiveVehicle(LiveVehicle(speedMps=0.0,gearPark=null,uxRestricted=false))
                vm.navigate(Screen.PROFILE)
            }
            ui.setContent { H1App(vm) }
            ui.onNodeWithContentDescription("무사즈 프로필 이미지").assertIsDisplayed()
            ui.onNodeWithText("내 차 상태").assertIsDisplayed()
            ui.onNodeWithText("시연용 예시 차량 정보").assertIsDisplayed()
            ui.onNodeWithText("78%").assertIsDisplayed()
            ui.onNodeWithText("320 km").assertIsDisplayed()
            ui.onNodeWithText("12,345.0 km").assertIsDisplayed()
            ui.onNodeWithText("핸들 각도").assertDoesNotExist()
            ui.runOnIdle {
                assertEquals(null, vm.live.odometer)
                assertEquals(null, vm.live.belt)
            }
            ui.onNodeWithText("연료 잔량").assertDoesNotExist()
            ui.onNodeWithText("닉네임 변경").performClick()
            val input=ui.onNode(hasSetTextAction())
            input.performTextReplacement("   ")
            ui.onNodeWithText("저장").assertIsNotEnabled()
            input.performTextReplacement("Profile QA")
            ui.onNodeWithText("저장").performClick()
            ui.onNodeWithText("Profile QA").assertExists()
            assertEquals("Profile QA",prefs.getString("profile-nickname",null))
            SystemClock.sleep(350) // Let the system finish the dialog exit animation.
            val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            val screenshot = instrumentation.uiAutomation.takeScreenshot()
            ui.activity.filesDir.resolve("profile-qa.png").outputStream().use {
                screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            screenshot.recycle()
        } finally {
            prefs.edit().putString("profile-nickname",previous).commit()
        }
    }
}
