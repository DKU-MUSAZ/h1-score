package ai.musaz.h1score

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test

class MapChallengeTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test fun searchZoomConsentAndCompleteDemoDrive() {
        fun waitFor(label: String, timeout: Long = 15_000) {
            ui.waitUntil(timeout) { ui.onAllNodesWithText(label, substring=true).fetchSemanticsNodes().isNotEmpty() }
        }
        waitFor("Pleos 연결")
        ui.onNodeWithText("Pleos 연결",substring=true).performClick()
        ui.onNodeWithText("시연 모드 켜기").performClick()
        waitFor("챌린지 지도")
        ui.onNodeWithContentDescription("지도 확대").performClick()
        ui.onNodeWithContentDescription("지도 축소").assertIsEnabled().performClick()
        ui.onNode(hasSetTextAction()).performTextInput("없는장소")
        ui.onNodeWithText("검색과 일치하는 챌린지가 없어요").assertExists()
        ui.onNode(hasSetTextAction()).performTextReplacement("한강")
        ui.onNodeWithText("4명",useUnmergedTree=true).performClick()
        waitFor("이 목적지로 챌린지에 참가할까요?")
        ui.onNodeWithText("참가 예약").performClick()
        waitFor("참가 예약 완료")
        ui.onNodeWithText("시연 주행 재생",substring=true).performClick()
        waitFor("주행 기록 중")
        waitFor("오늘의 주행습관 점수",45_000)
        ui.onNodeWithText("100점 만점").assertExists()
    }
}
