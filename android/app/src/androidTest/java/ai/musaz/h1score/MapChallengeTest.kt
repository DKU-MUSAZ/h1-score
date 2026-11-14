package ai.musaz.h1score

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test

class MapChallengeTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test fun popularListShowsOnlyEligibleFixtureCounts() {
        ui.waitUntil(15_000) { ui.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        ui.onNode(hasSetTextAction()).performClick()
        ui.onNodeWithText("참가 예시 24명").assertExists()
        ui.onNodeWithText("참가 예시 18명").assertExists()
        ui.onNodeWithText("참가 예시 10명").assertExists()
        ui.onNodeWithText("참가 예시 9명").assertDoesNotExist()
        ui.onNodeWithText("참가 예시 6명").assertDoesNotExist()
        screenshot("popular-spots")
    }

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
        for (spot in listOf("여의도 한강공원", "현대 모터스튜디오 고양", "단국대학교 죽전캠퍼스")) {
            val popularSpot = hasText(spot) and hasText("참가 예시", substring = true)
            suggestions.performScrollToNode(popularSpot)
            ui.onNode(popularSpot).assertIsDisplayed()
        }
        screenshot("popular-spots")
        val dankook = hasText("단국대학교 죽전캠퍼스") and hasText("참가 예시 10명")
        ui.onNode(dankook).performClick()
        waitFor("챌린지 지도")
        ui.onNodeWithText("이 목적지로 챌린지에 참가할까요?").assertDoesNotExist()
        ui.onNodeWithText("목적지 검색으로 돌아가기").performClick()
        ui.onNode(hasSetTextAction()).performTextInput("한강")
        suggestions.performScrollToNode(hasText("“한강” 검색"))
        ui.onNodeWithText("“한강” 검색").assertIsDisplayed()
        suggestions.performScrollToNode(hasText("챌린지 지도 보기"))
        ui.onNodeWithText("챌린지 지도 보기").performClick()
        waitFor("챌린지 지도")
        ui.waitForIdle()
        screenshot("challenge-map")
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
    @Test fun nearbyDestinationShowsChallengeBeforeOpeningNavigation() {
        searchFor("더현대 서울")
        val title = ui.onNodeWithText("이 목적지로 챌린지에 참가할까요?")
        ui.waitUntil(5_000) { title.isDisplayed() }
        ui.onNode(hasText("내비 목적지") and hasAnyAncestor(isDialog())).assertIsDisplayed()
        ui.onNode(hasText("더현대 서울") and hasAnyAncestor(isDialog())).assertIsDisplayed()
        ui.onNodeWithText("더현대 서울에서 1km 이내에 있는 가장 가까운 개최지예요.").assertIsDisplayed()
        ui.onNodeWithText("내장 내비에서 검색할까요?").assertDoesNotExist()
        screenshot("nearby-destination")
    }

    @Test fun distantDestinationShowsDirectionsBeforeOpeningNavigation() {
        searchFor("서울역")
        ui.onNodeWithText("일반 길 안내를 시작할까요?").assertIsDisplayed()
        ui.onNodeWithText("‘서울역’에서 1km 안에 챌린지 개최지가 없어요.", substring = true).assertIsDisplayed()
        ui.onNodeWithText("이 목적지로 챌린지에 참가할까요?").assertDoesNotExist()
        screenshot("distant-destination")
    }

    @Test fun unresolvedDestinationKeepsSearchFallback() {
        val keyword = "무사즈_없는_장소_54813"
        searchFor(keyword)
        ui.onNodeWithText("내장 내비에서 검색할까요?").assertIsDisplayed()
        ui.onNodeWithText("검색 계속").performClick()
        ui.onNode(hasSetTextAction()).assertTextContains(keyword)
        ui.onNodeWithText("내장 내비에서 검색할까요?").assertDoesNotExist()
    }

    @Test fun liveKakaoSuggestionsUpdateWhileTypingAndOpenNearbyChallenge() {
        org.junit.Assume.assumeTrue(BuildConfig.KAKAO_LOCAL_REST_API_KEY.isNotBlank())
        val skipTutorial = ui.onNodeWithText("바로 시작하기")
        if (skipTutorial.isDisplayed()) skipTutorial.performClick()
        val search = ui.onNode(hasSetTextAction())
        ui.waitUntil(15_000) { ui.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        search.performClick().performTextInput("한")

        fun waitForSuggestion(name: String) {
            ui.waitUntil(10_000) { ui.onAllNodesWithText(name).fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText(name).assertIsDisplayed()
        }

        waitForSuggestion("한 끗 본점")
        search.performTextInput("강")
        waitForSuggestion("여의도한강공원 1주차장")
        ui.onNodeWithText("한 끗 본점").assertDoesNotExist()
        screenshot("live-kakao-hangang")

        search.performTextReplacement("더현대")
        waitForSuggestion("더현대 서울")
        ui.onNodeWithText("더현대 서울").performClick()
        val title = ui.onNodeWithText("이 목적지로 챌린지에 참가할까요?")
        ui.waitUntil(5_000) { title.isDisplayed() }
        ui.onNode(hasText("여의도 한강공원") and hasAnyAncestor(isDialog())).assertIsDisplayed()
        ui.onNode(hasText("내비 목적지") and hasAnyAncestor(isDialog())).assertIsDisplayed()
        ui.onNode(hasText("더현대 서울") and hasAnyAncestor(isDialog())).assertIsDisplayed()
        ui.onNodeWithText("더현대 서울에서 1km 이내에 있는 가장 가까운 개최지예요.").assertIsDisplayed()
        screenshot("live-kakao-hyundai-challenge")
    }

    private fun searchFor(keyword: String) {
        val search = ui.onNode(hasSetTextAction())
        ui.waitUntil(15_000) { ui.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        search.performClick().performTextInput(keyword)
        ui.onNodeWithText("“${keyword}” 검색").performClick()
    }

    private fun screenshot(name: String) {
        val instrumentation=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val image=instrumentation.uiAutomation.takeScreenshot()
        java.io.File(ui.activity.filesDir, "$name.png").outputStream().use {
            image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
        }
        image.recycle()
    }

}
