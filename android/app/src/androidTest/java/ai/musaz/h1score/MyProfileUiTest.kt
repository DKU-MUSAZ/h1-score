package ai.musaz.h1score

import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class MyProfileUiTest {
    @get:Rule val ui=createAndroidComposeRule<ComponentActivity>()

    @Test fun profileValidatesAndPersistsNicknameWithoutShowingSyntheticCarData() {
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
            ui.onNodeWithText("시연 모드가 켜져 있어요").assertIsDisplayed()
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
