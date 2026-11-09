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
            ui.onNodeWithText("기본 프로필").assertExists()
            ui.onNodeWithText("나의 차 상태").assertExists()
            ui.onAllNodesWithText("미확인").onFirst().assertExists()
            ui.onNodeWithText("닉네임 변경").performClick()
            val input=ui.onNode(hasSetTextAction())
            input.performTextReplacement("   ")
            ui.onNodeWithText("저장").assertIsNotEnabled()
            input.performTextReplacement("Profile QA")
            ui.onNodeWithText("저장").performClick()
            ui.onNodeWithText("Profile QA").assertExists()
            assertEquals("Profile QA",prefs.getString("profile-nickname",null))
        } finally {
            prefs.edit().putString("profile-nickname",previous).commit()
        }
    }
}
