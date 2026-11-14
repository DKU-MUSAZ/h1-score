package ai.musaz.h1score

import android.os.SystemClock
import ai.musaz.h1score.core.CollectionProgress
import ai.musaz.h1score.core.SpotTour
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import java.time.LocalDate

class BadgeCollectionUiTest {
    @get:Rule val ui=createAndroidComposeRule<ComponentActivity>()
    private fun capture(name: String, node: SemanticsNodeInteraction = ui.onRoot()) {
        ui.waitForIdle()
        android.util.Log.i("BadgeUiTest","capture: $name")
        val bitmap=node.captureToImage().asAndroidBitmap()
        java.io.File(ui.activity.filesDir,"badge-$name.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
        }
    }

    @Test fun actualProgressAndSelectedGuideUseTheStoredRecord() {
        val day = LocalDate.of(2026, 11, 30)
        val prefs = ui.activity.getSharedPreferences("test-collection-${java.util.UUID.randomUUID()}", 0)
        val store = CollectionProgressStore(prefs)
        val progress = CollectionProgress().recordChallenge(day, 92).recordVisit(SpotTour.SEOUL, day)
        val editor = prefs.edit()
        store.writeChallenge(editor, false, progress)
        store.writeVisit(editor, false, SpotTour.SEOUL, progress)
        editor.commit()
        assertEquals(0, store.read(true).challengeDays)
        assertEquals(0, store.read(true).visits(SpotTour.SEOUL))
        var started: SpotTour? = null
        ui.setContent {
            BadgeCollectionScreen(legacyOwned = emptySet(), collectionProgress = store.read(false),
                onStartSpot = { started = it }, onChallenge = {})
        }
        val filter = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)
        val expanded = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "펼쳐짐")
        val dialog = hasAnyAncestor(isDialog())
        ui.onNode(hasText("명예 훈장") and filter).performClick()
        ui.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("좋은 습관"))
        ui.onNode(hasText("좋은 습관") and hasText("티어 잠금")).assertIsDisplayed()
        ui.onNodeWithText("배지 안내").performClick()
        ui.onNode(dialog and hasText("명예 훈장") and expanded).assertIsDisplayed()
        ui.onNode(dialog and hasText("획득 조건")).assertIsDisplayed()
        ui.onNode(dialog and hasText("집계 시점")).assertIsDisplayed()
        ui.onNode(dialog and hasScrollToIndexAction()).performScrollToNode(hasText("차분한 출발"))
        val badgeTitle = ui.onNode(dialog and hasText("차분한 출발")).fetchSemanticsNode().boundsInRoot
        val badgeCondition = ui.onNode(dialog and hasText("상위 80% 이내 5일")).fetchSemanticsNode().boundsInRoot
        assertEquals(badgeTitle.left, badgeCondition.left, 1f)
        assertTrue(badgeCondition.top >= badgeTitle.bottom)
        capture("collection-guide", ui.onNode(isDialog()))
        ui.onNode(dialog and hasText("닫기")).performClick()
        ui.onNode(hasText("명예 훈장") and filter).performClick()
        ui.onNodeWithText("배지 안내").performClick()
        ui.onNode(dialog and hasText("명예 훈장") and expanded and hasText("실제 순위 연동 준비 중")).assertIsDisplayed()
        ui.onNode(dialog and hasText("각 티어의 배지 3개를 모두 모으면 다음 티어가 열려요.", substring = true)).assertIsDisplayed()
        ui.onNode(dialog and hasText("닫기")).performClick()
        ui.onNode(hasText("스팟 투어") and filter).performClick()
        ui.onNode(hasText("서울 오토 라이브러리") and hasText("1 / 3회")).assertIsDisplayed().performClick()
        val confirm = ui.onNode(dialog and hasText("확인")).fetchSemanticsNode().boundsInRoot
        val popup = ui.onNode(isDialog()).fetchSemanticsNode().boundsInRoot
        assertEquals(popup.center.x, confirm.center.x, 1f)
        capture("collection-detail", ui.onNode(isDialog()))
        ui.onNode(dialog and hasText("방문 기록 시작")).performClick()
        ui.runOnIdle { assertEquals(SpotTour.SEOUL, started) }
    }

    @Test fun welcomeBadgeIsOwnedWithoutDrivingOrPreview() {
        ui.setContent { BadgeCollectionScreen(legacyOwned = emptySet(), onChallenge = {}) }
        val title = "당신도 이제 무사즈!"
        val owned = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "획득")
        ui.onNode(hasText(title) and owned).assertIsDisplayed().performClick()
        ui.onNodeWithText("앱을 처음 실행한 모든 무사즈에게 주는 환영 배지예요.").assertIsDisplayed()
        ui.onNode(hasAnyAncestor(isDialog()) and hasText("획득 보상", substring = true)).assertDoesNotExist()
        ui.onNodeWithText("확인").performClick()
        ui.onNode(hasText("전체") and isSelectable()).assertIsSelected()

        ui.onNode(hasText("출석 도장") and isSelectable()).performClick()
        ui.onNodeWithText(title).assertDoesNotExist()
        ui.onNodeWithText("전체").performClick()
        ui.onNode(hasText(title) and owned).assertIsDisplayed()
    }

    @Test fun eachCollectionBadgeDisplaysDistinctArtwork() {
        ui.setContent { BadgeCollectionScreen(legacyOwned = emptySet(), onChallenge = {}) }
        val badgesByCategory = mapOf(
            "출석 도장" to listOf("첫 발걸음", "꾸준한 드라이버", "출근길 동반자", "함께 달린 무사즈", "주간 루틴", "네 주의 동행"),
            "명예 훈장" to listOf("차분한 출발", "안정적인 흐름", "좋은 습관", "믿음직한 드라이버", "정교한 운전", "꾸준한 좋은 흐름",
                "순위 첫걸음", "순위 적응 완료", "중위권 돌파", "꾸준한 상위권", "상위권 단골", "무사즈 에이스"),
            "스팟 투어" to listOf("서울 오토 라이브러리", "고양 모빌리티 탐험", "부산 디자인 산책")
        )
        val grid = ui.onNode(hasScrollToIndexAction())
        val dialogArtwork = ui.onNode(
            hasAnyAncestor(isDialog()) and hasContentDescription("무사즈 배지", substring = true),
            useUnmergedTree = true
        )
        for ((category, titles) in badgesByCategory) {
            ui.onNode(hasText(category) and isSelectable()).performClick()
            val artworkByTitle = mutableMapOf<List<Int>, String>()
            for (title in titles) {
                grid.performScrollToNode(hasText(title))
                ui.onNode(hasText(title) and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
                ui.onNode(hasAnyAncestor(isDialog()) and hasText(title)).assertIsDisplayed()
                val image = dialogArtwork.captureToImage().asAndroidBitmap()
                // Sample the center of the emblem.
                val pixels = (2..8).flatMap { y ->
                    (2..8).map { x -> image.getPixel(image.width * x / 10, image.height * y / 10) }
                }
                val previous = artworkByTitle.put(pixels, title)
                if (category != "명예 훈장") assertNull("$title and $previous render the same badge artwork", previous)
                ui.onNodeWithText("확인").performClick()
                ui.waitUntil(5_000) { ui.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty() }
            }
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
        ui.onNodeWithText("0 / 4개 달성").assertDoesNotExist()
        ui.onNodeWithText("배지 컬렉션").assertExists()
        ui.onNodeWithText("배지 유형").assertDoesNotExist()
        ui.onNodeWithText("전체").assertIsSelected()
        ui.onNodeWithText("18종 · 미리보기", substring = true).assertDoesNotExist()
        ui.onNodeWithText("0 / 18").assertDoesNotExist()
        val locked = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "잠금")
        val earned = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "획득")
        ui.onNodeWithText("첫 발걸음").assertIsDisplayed()
        val headingBounds = ui.onNodeWithText("배지 컬렉션").fetchSemanticsNode().boundsInRoot
        val rankChip = ui.onNodeWithText("명예 훈장").fetchSemanticsNode().boundsInRoot
        assertTrue("The collection title should be above the filters", headingBounds.bottom <= rankChip.top)
        val grid = ui.onNode(hasScrollToIndexAction())
        val heading = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)
        val filter = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)
        val navigationTab = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
        capture("tour-all")
        for (category in listOf("출석 도장", "명예 훈장", "스팟 투어")) {
            val categoryHeading = hasText(category) and heading
            grid.performScrollToNode(categoryHeading)
            ui.onNode(categoryHeading).assertIsDisplayed()
        }
        ui.onNode(hasText("명예 훈장") and filter).performClick().assertIsSelected()
        ui.onNode(hasText("브론즈") and heading).assertIsDisplayed()
        ui.onNodeWithText("순위 첫걸음").assertIsDisplayed()
        capture("honor-rank-first")
        val silverGate = hasText("브론즈 배지 3개를 모두 획득하면 열려요")
        grid.performScrollToNode(silverGate)
        ui.onNode(silverGate).assertIsDisplayed()
        capture("tour-rank")
        ui.onNode(hasText("스팟 투어") and filter).performClick().assertIsSelected()
        ui.onNodeWithText("스팟 투어 배지").assertIsDisplayed()
        ui.onNodeWithText("3종 중 ", substring = true).assertIsDisplayed()
        capture("tour-spot")
        ui.onNodeWithText("서울 오토 라이브러리").performClick()
        ui.onNode(hasAnyAncestor(isDialog()) and hasText("현대 모터스튜디오 서울 · 3회 방문")).assertExists()
        ui.onNodeWithText("시연 방문 추가").assertExists()
        ui.onNodeWithText("확인").performClick()
        ui.onNode(hasText("출석 도장") and filter).performClick().assertIsSelected()
        ui.onNodeWithText("출석 도장 배지").assertIsDisplayed()
        ui.onNodeWithText("6종 중 ", substring = true).assertIsDisplayed()
        ui.onNodeWithText("시연용 - 달성 예시보기").performClick()
        ui.onNodeWithText("예시 기록").assertIsDisplayed()
        ui.onNodeWithText("6종 중 3개 획득").assertIsDisplayed()
        ui.onNode(hasText("첫 발걸음") and hasText("5 / 5일 · 획득") and earned).assertIsDisplayed()
        ui.onNode(hasText("출근길 동반자") and hasText("12 / 15일") and locked).assertIsDisplayed()
        ui.onNodeWithText("첫 발걸음").performClick()
        ui.onNodeWithText("획득 보상 +15M").assertExists()
        ui.onNodeWithText("확인").performClick()
        for(category in listOf("명예 훈장", "출석 도장")) {
            ui.onNode(hasText(category) and filter).performClick().assertIsSelected()
            ui.onNodeWithText("$category 배지").assertIsDisplayed()
            if(category=="명예 훈장") {
                grid.performScrollToNode(hasText("중위권 돌파"))
                ui.onNode(silverGate).assertDoesNotExist()
                ui.onNode(hasText("꾸준한 상위권") and hasText("4 / 5일")).assertIsDisplayed()
                for((title, tier) in listOf("순위 첫걸음" to "브론즈", "중위권 돌파" to "실버",
                    "상위권 단골" to "골드", "무사즈 에이스" to "플래티넘")) {
                    val card = hasText(title) and hasContentDescription("$tier 트로피", substring = true)
                    grid.performScrollToNode(card)
                    ui.onNode(card).assertIsDisplayed()
                }
            }
        }
        android.util.Log.i("BadgeUiTest","opening routine details")
        grid.performScrollToNode(hasText("주간 루틴"))
        ui.onNodeWithText("주간 루틴").performClick()
        ui.onNodeWithText("월요일~일요일 중 3일",substring=true).assertExists()
        ui.onNodeWithText("확인").performClick()
        ui.onNodeWithText("배지 안내").performClick()
        val guide = ui.onNode(hasAnyAncestor(isDialog()) and hasScrollToIndexAction())
        guide.performScrollToNode(hasText("이전 배지"))
        ui.onNodeWithText("이전 배지").performClick()
        ui.onNodeWithText("이전 여정의 배지").assertIsDisplayed()
        ui.onNodeWithText("확인").performClick()
        ui.onNodeWithText("전체").performClick().assertIsSelected()
        ui.onNode(hasText("출석 도장") and heading).assertIsDisplayed()
        ui.onNodeWithText("6종 중 3개 획득").assertDoesNotExist()
        ui.onNodeWithText("예시 종료").performClick()
        ui.onNodeWithText("예시 기록").assertDoesNotExist()
        ui.onNodeWithText("엔진오일 교환권 안내").assertDoesNotExist()
        ui.onNode(hasText("마일리지") and navigationTab).performClick().assertIsSelected()
        ui.onNodeWithText("엔진오일 교환권 안내").performClick()
        ui.onNodeWithText("목표 600M").assertIsDisplayed()
        ui.onNodeWithText("챌린지 순위 보상과 배지 보상으로 마일리지를 모을 수 있어요.").assertExists()
        ui.onNodeWithText("확인").performClick()
        ui.runOnIdle {
            val vm=(ui.activity.application as H1Application).model
            assertEquals(balance,vm.balance);assertEquals(badges,vm.ownedBadges)
        }
    }
}
