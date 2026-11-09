package ai.musaz.h1score

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test

class MapChallengeTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test fun challengeHomeOpensSearchSuggestionsAndExistingMap() {
        fun waitFor(label: String, timeout: Long = 15_000) {
            ui.waitUntil(timeout) { ui.onAllNodesWithText(label, substring=true).fetchSemanticsNodes().isNotEmpty() }
        }
        val greeting = hasText("오늘도 무사즈와 달려볼까요?") or hasText("오늘도 무사히, 함께 출발해요.")
        ui.waitUntil(15_000) { ui.onAllNodes(greeting).fetchSemanticsNodes().isNotEmpty() }
        val initialGreeting = ui.onNode(greeting).fetchSemanticsNode().config[SemanticsProperties.Text].single().text
        ui.onNodeWithText("이번 주 안전점수").assertIsDisplayed()
        ui.onNode(hasSetTextAction()).performClick()
        ui.onNodeWithText("최근 검색한 목적지").assertIsDisplayed()
        ui.onNodeWithText("지금 인기 챌린지 스팟").assertIsDisplayed()
        val suggestions = ui.onNode(hasScrollToIndexAction())
        for (spot in listOf("여의도 한강공원", "현대 모터스튜디오 고양", "판교 테크노밸리")) {
            suggestions.performScrollToNode(hasText(spot))
            ui.onNodeWithText(spot).assertIsDisplayed()
        }
        ui.onNode(hasSetTextAction()).performTextInput("한강")
        suggestions.performScrollToNode(hasText("“한강” 검색"))
        ui.onNodeWithText("“한강” 검색").assertIsDisplayed()
        suggestions.performScrollToNode(hasText("챌린지 지도 보기"))
        ui.onNodeWithText("챌린지 지도 보기").performClick()
        waitFor("챌린지 지도")
        ui.onNodeWithText("목적지 검색으로 돌아가기").performClick()
        ui.onNodeWithText("지금 인기 챌린지 스팟").assertExists()
        ui.onNodeWithText("닫기").performClick()
        ui.onNodeWithText(initialGreeting).assertIsDisplayed()
        val sidebarTab = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
        ui.onNode(hasText("배지") and sidebarTab).performClick()
        waitFor("배지 컬렉션")
        ui.onNode(hasText("챌린지") and sidebarTab).performClick()
        waitFor(initialGreeting)
        ui.onNodeWithText(initialGreeting).assertIsDisplayed()
    }
}
