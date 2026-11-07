package ai.musaz.h1score

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain

class MapChallengeTest {
    val ui = createAndroidComposeRule<MainActivity>()
    private val permissions = object : ExternalResource() {
        override fun before() {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            // Grant before ActivityScenario launches: the system permission activity otherwise
            // hides Compose on a fresh install. This test exercises the challenge consent flow.
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

    @Test fun searchZoomConsentAndCompleteDemoDrive() {
        fun waitFor(label: String, timeout: Long = 15_000) {
            ui.waitUntil(timeout) { ui.onAllNodesWithText(label, substring=true).fetchSemanticsNodes().isNotEmpty() }
        }
        ui.runOnIdle { (ui.activity.application as H1Application).model.connection.stop() }
        Thread.sleep(1000)
        ui.runOnIdle {
            val now=android.os.SystemClock.elapsedRealtime()
            (ui.activity.application as H1Application).model.receiveVehicle(LiveVehicle(
                speedMps=0.0,speedAt=now,gearPark=true,uxRestricted=false,belt=true,beltAt=now))
        }
        waitFor("Pleos 연결")
        ui.onNodeWithText("Pleos 연결",substring=true).performClick()
        waitFor("시연 모드 켜기")
        ui.onNodeWithText("시연 모드 켜기").performClick()
        ui.runOnIdle {
            // The emulator-only demo fallback holds a stationary state without aging live telemetry.
            (ui.activity.application as H1Application).model.receiveVehicle(LiveVehicle(
                speedMps=0.0,gearPark=null,uxRestricted=false,belt=true))
        }
        waitFor("챌린지 지도")
        ui.onNodeWithContentDescription("지도 확대").performClick()
        ui.onNodeWithContentDescription("지도 축소").assertIsEnabled().performClick()
        ui.onNode(hasSetTextAction()).performTextInput("없는장소")
        ui.onNodeWithText("검색과 일치하는 챌린지가 없어요").assertExists()
        ui.onNode(hasSetTextAction()).performTextReplacement("한강")
        ui.onNodeWithText("4명",useUnmergedTree=true).performClick()
        waitFor("이 목적지로 챌린지에 참가할까요?")
        ui.onNodeWithText("참가 예약 · 내비 시작").assertIsNotEnabled()
        ui.onNodeWithText("마감 및 참가 조건에 동의합니다").performClick()
        ui.onNodeWithText("참가 예약 · 내비 시작").assertIsEnabled().performClick()
        waitFor("참가 예약 완료")
        ui.onNodeWithText("기본 60점",substring=true).assertExists()
        ui.onNodeWithText("시연 주행 재생",substring=true).performClick()
        waitFor("주행 기록 중")
        waitFor("오늘의 주행습관 점수",45_000)
        ui.onNodeWithText("100점 만점").assertExists()
        ui.runOnIdle {
            val report=(ui.activity.application as H1Application).model.report!!
            org.junit.Assert.assertTrue(report.score!! in 60..100)
            org.junit.Assert.assertEquals(60.0+report.speedPoints+report.accelerationPoints,report.totalPoints,0.00001)
            org.junit.Assert.assertTrue(report.speedPoints<=25 && report.accelerationPoints<=15)
        }
        ui.onNodeWithText("1km별 가점 보기").performClick()
        waitFor("1km별 가점 · 시작 60점")
        ui.onNodeWithText("1km 구간",substring=true).assertExists()
        val instrumentation=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val file=java.io.File(ui.activity.filesDir,"bonus-result.png")
        instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
        }
        ui.onNodeWithText("닫기").performClick()
    }
}
