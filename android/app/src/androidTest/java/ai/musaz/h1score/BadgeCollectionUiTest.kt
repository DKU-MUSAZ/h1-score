package ai.musaz.h1score

import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.asAndroidBitmap
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*

class BadgeCollectionUiTest {
    @get:Rule val ui=createAndroidComposeRule<ComponentActivity>()
    private fun capture(name: String) {
        ui.waitForIdle()
        android.util.Log.i("BadgeUiTest","capture: $name")
        val bitmap=ui.onRoot().captureToImage().asAndroidBitmap()
        java.io.File(ui.activity.filesDir,"badge-$name.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
        }
    }
    @Test fun categoriesDetailsAndRewardsSharePreviewProgressWithoutCreditingPoints() {
        val vm=(ui.activity.application as H1Application).model
        ui.runOnIdle { vm.connection.stop() }
        Thread.sleep(1000)
        var balance=0
        var badges=emptySet<String>()
        ui.runOnIdle {
            val now=SystemClock.elapsedRealtime()
            vm.receiveVehicle(LiveVehicle(speedMps=0.0,speedAt=now,gearPark=true,uxRestricted=false,belt=true,beltAt=now))
            if(!vm.demo)vm.switchSource()
            vm.receiveVehicle(LiveVehicle(speedMps=0.0,gearPark=null,uxRestricted=false))
            balance=vm.balance;badges=vm.ownedBadges
            vm.navigate(Screen.BADGES)
        }
        ui.setContent { H1App(vm) }
        ui.onNodeWithText("나의 배지 여정").assertExists()
        capture("empty")
        ui.onNodeWithText("달성 예시 보기").performClick()
        ui.onNodeWithText("1,750").assertExists()
        capture("participation")
        ui.onNodeWithText("첫 발걸음").performClick()
        ui.onNodeWithText("달성 보너스 +200P").assertExists()
        ui.onNodeWithText("확인").performClick()
        for(category in listOf("점수","순위","꾸준함")) {
            ui.onNodeWithText(category).performClick()
            if(category=="순위") {
                for(tier in listOf("브론즈","실버","골드","플래티넘")) {
                    assertTrue(ui.onAllNodesWithContentDescription("$tier 트로피",substring=true).fetchSemanticsNodes().isNotEmpty())
                }
            }
            capture(when(category){"점수"->"score";"순위"->"rank";else->"routine"})
        }
        android.util.Log.i("BadgeUiTest","opening routine details")
        ui.onNodeWithText("주간 루틴").performClick()
        ui.onNodeWithText("월요일~일요일 중 3일",substring=true).assertExists()
        ui.onNodeWithText("확인").performClick()
        android.util.Log.i("BadgeUiTest","opening reward details")
        ui.onNodeWithText("엔진오일 교환권").performClick()
        ui.onNodeWithText("예시: 대회 1,500P + 배지 1,750P = 3,250P").assertExists()
        ui.onNodeWithText("확인").performClick()
        android.util.Log.i("BadgeUiTest","opening rules")
        ui.onNodeWithText("배지·포인트 지급 기준").performClick()
        ui.onNodeWithText("월간 배지 보너스는 최대 4,000P",substring=true).assertExists()
        ui.onNodeWithText("확인").performClick()
        ui.onNodeWithText("예시 기록 보는 중").performClick()
        ui.onNodeWithText("0 / 18").assertExists()
        ui.runOnIdle {
            val vm=(ui.activity.application as H1Application).model
            assertEquals(balance,vm.balance);assertEquals(badges,vm.ownedBadges)
        }
    }
}
