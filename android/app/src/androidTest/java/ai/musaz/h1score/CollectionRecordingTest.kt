package ai.musaz.h1score

import ai.musaz.h1score.core.ChallengeRound
import ai.musaz.h1score.core.SpotTour
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.util.UUID

class CollectionRecordingTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()

    @Test fun spotProgressRequiresMatchingArrivalAndParkAndPersistsOnce() {
        val app = isolatedApplication()
        ui.runOnIdle {
            val model = ChallengeViewModel(app)
            val parked = parkedVehicle()
            val spot = SpotTour.SEOUL
            model.receiveVehicle(parked)
            model.startSpotVisit(spot)
            assertEquals(spot, model.trackedSpot)

            val route = parked.copy(destination = spot.destinationName, destinationId = "seoul",
                destinationPoint = spot.location, remainingM = 1_000.0, destinationAt = SystemClock.elapsedRealtime())
            model.receiveVehicle(route)
            val arrival = route.copy(arrived = true, arrivedAt = Instant.now(), remainingM = 0.0)
            model.receiveVehicle(arrival.copy(destinationId = "other"))
            assertEquals(0, model.collectionProgress.visits(spot))
            model.receiveVehicle(arrival.copy(gearPark = false))
            assertEquals(0, model.collectionProgress.visits(spot))
            model.receiveVehicle(arrival)
            model.receiveVehicle(arrival)
            assertNull(model.trackedSpot)
            assertEquals(1, model.collectionProgress.visits(spot))
            assertEquals(1, CollectionProgressStore(app.prefs).read(false).visits(spot))
        }
    }

    @Test fun completedDemoChallengeUsesOnlyTheDemoCollection() {
        val app = isolatedApplication()
        lateinit var model: ChallengeViewModel
        ui.runOnIdle {
            model = ChallengeViewModel(app)
            model.receiveVehicle(parkedVehicle())
            model.switchSource()
            val now = Instant.now()
            model.accept(ChallengeRound("시연", now.minusSeconds(60), now.plusSeconds(120)))
            model.runDemo()
        }
        ui.waitUntil(40_000) { model.report != null }
        ui.runOnIdle {
            assertNotNull(model.report?.score)
            assertEquals(minOf(model.selected.demoPeers, 4) + 1, model.ranking.size)
            val store = CollectionProgressStore(app.prefs)
            assertEquals(1, model.collectionProgress.challengeDays)
            assertEquals(1, store.read(true).challengeDays)
            assertEquals(0, store.read(false).challengeDays)
            assertTrue(model.recordedDistanceMeters >= 9_000)
            assertEquals(model.recordedDistanceMeters, app.prefs.getLong("recorded-distance-demo", 0))
            assertEquals(0L, app.prefs.getLong("recorded-distance-live", 0))
        }
    }

    @Test fun recentDestinationsKeepTheThreeMostRecentDistinctSearches() {
        val app = isolatedApplication()
        ui.runOnIdle {
            val model = ChallengeViewModel(app)
            model.receiveVehicle(parkedVehicle())
            listOf("서울역", "한강공원", "광화문", "강남역", " 한강공원 ").forEach(model::rememberDestination)
            val expected = listOf("한강공원", "강남역", "광화문")
            assertEquals(expected, model.recentDestinations)
            assertEquals(expected, ChallengeViewModel(app).recentDestinations)
        }
    }

    @Test fun destinationShortcutsCanBeSavedReopenedAndRemoved() {
        val app=isolatedApplication()
        ui.runOnIdle {
            fun model()=ChallengeViewModel(app).apply { receiveVehicle(parkedVehicle()) }
            val vm=model()
            val id=vm.addShortcut("학교")!!
            assertNull(vm.addShortcut("학교"))
            val place=ai.musaz.h1score.core.SavedPlace.from("단국대학교 죽전캠퍼스", ai.musaz.h1score.core.GeoPoint(37.3217,127.1268))!!
            assertTrue(vm.saveShortcutPlace(id,place))
            assertEquals(place,model().destinationShortcuts.single { it.id==id }.place)
            assertTrue(vm.renameShortcut(id,"캠퍼스"))
            assertEquals("캠퍼스",model().destinationShortcuts.single { it.id==id }.label)
            vm.receiveVehicle(parkedVehicle().copy(gearPark=false))
            assertNull(vm.addShortcut("운동장"))
            vm.removeShortcut(id)
            assertTrue(vm.destinationShortcuts.any { it.id==id })
            vm.receiveVehicle(parkedVehicle())
            vm.removeShortcut(id)
            assertTrue(model().destinationShortcuts.none { it.id==id })
        }
    }

    @Test fun popularSpotsUseTenPersonBoundaryAndShareMapData() {
        val base=hangangChallenge
        assertEquals(listOf(11,10),popularChallengeSpots(listOf(base.copy(demoPeers=9),base.copy(demoPeers=10),base.copy(demoPeers=11))).map {it.demoPeers})
        assertTrue(popularChallengeSpots(listOf(base.copy(demoPeers=9))).isEmpty())
        assertEquals(listOf(24,18,10),popularSpots.map {it.demoPeers})
        assertTrue(popularSpots.all {it in challengeSpots})
        assertTrue(challengeSpots.any {it.demoPeers==9})
    }

    @Test fun customShortcutEditorAddsSchoolAndRequiresRealPlace() {
        val vm=ui.runOnIdle { ChallengeViewModel(isolatedApplication()).apply { receiveVehicle(parkedVehicle()); switchSource() } }
        ui.setContent { MaterialTheme { DestinationShortcutEditor(vm,null) {} } }
        ui.onNode(hasSetTextAction()).performTextReplacement("학교")
        ui.runOnIdle { vm.receiveVehicle(parkedVehicle()) }
        ui.onNodeWithText("바로가기 추가").performSemanticsAction(SemanticsActions.OnClick)
        ui.onNodeWithText("학교 목적지").assertExists()
        val screenshot=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        java.io.File(ui.activity.filesDir, "shortcut-editor.png").outputStream().use {
            screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
        }
        screenshot.recycle()
        ui.onNodeWithText("저장").assertIsNotEnabled()
        ui.runOnIdle { vm.receiveVehicle(parkedVehicle().copy(destination="서울역",destinationPoint=ai.musaz.h1score.core.GeoPoint(37.5547,126.9707),destinationAt=SystemClock.elapsedRealtime())) }
        ui.waitUntil(3000) { ui.onAllNodesWithText("현재 내비 목적지 선택: 서울역").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("현재 내비 목적지 선택: 서울역").performScrollTo().performClick()
        ui.runOnIdle { vm.receiveVehicle(vm.live.copy(speedAt = SystemClock.elapsedRealtime())) }
        ui.onNodeWithText("저장").performClick()
        ui.runOnIdle { assertEquals("서울역",vm.destinationShortcuts.single {it.label=="학교"}.place?.name) }
    }

    @Test fun demoSearchFindsDankookUniversityWithoutTheCampusSuffix() {
        val app = isolatedApplication()
        val model = ui.runOnIdle {
            ChallengeViewModel(app).apply {
                receiveVehicle(parkedVehicle())
                switchSource()
            }
        }
        ui.setContent { MaterialTheme { ChallengeHomeScreen(model) } }

        val search = ui.onNode(hasSetTextAction())
        search.performClick()
        search.performTextInput("단국대학교")
        val result = hasText("“단국대학교” 검색")
        ui.onNode(hasScrollToIndexAction()).performScrollToNode(result)
        ui.onNode(result).performClick()

        ui.onNodeWithText("챌린지 지도").assertExists()
        ui.runOnIdle { assertEquals(listOf("단국대학교 죽전캠퍼스"), model.recentDestinations) }
    }

    @Test fun nearestVenueAssignsNonHangangWithoutChangingDestinationAndOutsideOpensNavigation() {
        val app=isolatedApplication()
        ui.runOnIdle {
            val now=Instant.now()
            val round=ChallengeRound("배정 검증",now.minusSeconds(30),now.plusSeconds(3600))
            fun model(point: ai.musaz.h1score.core.GeoPoint, id: String)=ChallengeViewModel(app).apply {
                receiveVehicle(parkedVehicle().copy(destination="사용자가 고른 목적지",destinationId=id,
                    destinationPoint=point,destinationAt=SystemClock.elapsedRealtime(),remainingM=12000.0))
            }
            val venue=challengeSpots.single {it.venueId=="olympic-park"}
            val a=model(requireNotNull(venue.point),"poi-a")
            a.accept(round)
            assertEquals("olympic-park",a.session?.venueId)
            assertEquals("poi-a",a.session?.destinationId)
            assertEquals("사용자가 고른 목적지",a.destinationName)
            val b=model(requireNotNull(venue.point),"poi-b")
            b.accept(round)
            assertEquals(a.session?.competitionId,b.session?.competitionId)
            val outside=model(ai.musaz.h1score.core.GeoPoint(37.5547,126.9707),"seoul-station")
            val starts=app.navigationLaunches
            outside.accept(round)
            assertNull(outside.session)
            assertEquals("seoul-station",outside.live.destinationId)
            assertEquals(starts+1,app.navigationLaunches)
            val invalid=model(ai.musaz.h1score.core.GeoPoint(Double.NaN,0.0),"invalid")
            invalid.accept(round)
            assertNull(invalid.session)
            assertEquals(starts+1,app.navigationLaunches)
        }
    }

    @Test fun eligibleNavigationDestinationOffersChallengeOnce() {
        val app = isolatedApplication()
        val model = ui.runOnIdle { ChallengeViewModel(app).apply { receiveVehicle(parkedVehicle()) } }
        ui.setContent { H1App(model) }

        fun route(id: String, name: String, point: ai.musaz.h1score.core.GeoPoint) = parkedVehicle().copy(
            destination = name,
            destinationId = id,
            destinationPoint = point,
            destinationAt = SystemClock.elapsedRealtime(),
            remainingM = 12_000.0
        )
        val eligible = route("near", "더현대서울", ai.musaz.h1score.core.GeoPoint(37.5259, 126.9284))
        val outside = route("far", "서울역", ai.musaz.h1score.core.GeoPoint(37.5547, 126.9707))
        val invitation = ui.onNodeWithText("내비 목적지 · 더현대서울")

        ui.runOnIdle { model.receiveVehicle(eligible.copy(speedAt = SystemClock.elapsedRealtime())) }
        invitation.assertIsDisplayed()
        ui.onNodeWithContentDescription("닫기").performClick()
        ui.runOnIdle { model.receiveVehicle(eligible.copy(remainingM = 11_900.0)) }
        invitation.assertDoesNotExist()

        ui.runOnIdle { model.receiveVehicle(outside) }
        ui.onNodeWithText("내비 목적지 · 서울역").assertDoesNotExist()
        ui.runOnIdle { model.receiveVehicle(eligible.copy(speedAt = SystemClock.elapsedRealtime())) }
        invitation.assertIsDisplayed()
        ui.onNodeWithText("현재 목적지로 챌린지 참가").assertDoesNotExist()
    }

    @Test fun mileageCardsPreserveBalanceAndHeadersAreNotDuplicated() {
        val app=isolatedApplication()
        app.prefs.edit().putInt("balance",100).commit()
        val model=ui.runOnIdle { ChallengeViewModel(app).apply {
            receiveVehicle(parkedVehicle());switchSource();navigate(Screen.WALLET)
        } }
        ui.setContent { H1App(model) }
        ui.onNodeWithText("차곡차곡, 내 마일리지").assertIsDisplayed()
        ui.onNodeWithText("와이퍼",useUnmergedTree=true).assertIsDisplayed()
        ui.onNodeWithText("에어컨 필터",useUnmergedTree=true).assertIsDisplayed()
        ui.onNodeWithText("엔진오일",useUnmergedTree=true).assertIsDisplayed()
        ui.onNodeWithText("나의 쿠폰함").performScrollTo().performClick()
        ui.onNodeWithText("아직 교환한 혜택이 없어요.").assertIsDisplayed()
        ui.onNodeWithText("확인").performClick()
        ui.runOnIdle {assertEquals(100,model.balance)}
        val image=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        java.io.File(ui.activity.filesDir,"mileage-redesign.png").outputStream().use {image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
        image.recycle()
        ui.onNodeWithContentDescription("와이퍼 상세").performClick()
        ui.onNodeWithText("와이퍼 쿠폰을 교환하시겠습니까?").assertIsDisplayed()
        ui.onNodeWithText("교환하기").assertIsEnabled()
        ui.runOnIdle {assertEquals(100,model.balance);assertTrue(model.redeemedRewards.isEmpty())}
        ui.onNodeWithText("취소").performClick()
        ui.runOnIdle {assertEquals(100,model.balance);assertTrue(model.redeemedRewards.isEmpty())}
        ui.onNodeWithContentDescription("와이퍼 상세").performClick()
        ui.onNodeWithText("교환하기").performClick()
        ui.runOnIdle {assertEquals(20,model.balance);assertTrue("wiper" in model.redeemedRewards)}
        ui.onNodeWithText("쿠폰 교환이 완료됐어요").assertIsDisplayed()
        ui.onNode(hasText("교환 완료") and hasClickAction() and hasAnyAncestor(isDialog())).assertIsNotEnabled()
        ui.onNodeWithText("닫기").performClick()
        ui.onNodeWithContentDescription("와이퍼 상세").performClick()
        ui.onNode(hasText("교환 완료") and hasClickAction() and hasAnyAncestor(isDialog())).assertIsNotEnabled()
        ui.runOnIdle {model.redeemDemoReward(mileageRewards.first());assertEquals(20,model.balance)}
        ui.onNodeWithText("닫기").performClick()
        ui.onNodeWithContentDescription("에어컨 필터 상세").performClick()
        ui.onNodeWithText("마일리지가 부족해요").assertIsDisplayed()
        ui.onNodeWithText("280 M 더 모으면 교환할 수 있어요.").assertIsDisplayed()
        ui.onNodeWithText("교환하기").assertIsNotEnabled()
        ui.runOnIdle {model.redeemDemoReward(mileageRewards[1]);assertEquals(20,model.balance);assertTrue("filter" !in model.redeemedRewards)}
        ui.onNodeWithText("취소").performClick()
        ui.onNodeWithText("나의 쿠폰함").performScrollTo().performClick()
        ui.onNodeWithText("와이퍼 교환").assertIsDisplayed()
        ui.onNodeWithText("시연 교환 완료").assertIsDisplayed()
        ui.onNodeWithText("확인").performClick()
        ui.runOnIdle {
            assertEquals(20,model.balance)
            assertTrue("wiper" in ChallengeViewModel(app).redeemedRewards)
        }
        ui.runOnIdle {model.navigate(Screen.PROFILE)}
        ui.onAllNodes(hasText("마이페이지") and isHeading()).assertCountEquals(1)
        ui.runOnIdle {model.navigate(Screen.BADGES)}
        ui.onAllNodesWithText("배지 컬렉션").assertCountEquals(1)
    }

    @Test fun resultFeedbackPersistsAndScoreDialStaysSquare() {
        val app=isolatedApplication()
        val report=ai.musaz.h1score.core.ScoreEngine.evaluate((0..5_000).map { index ->
            ai.musaz.h1score.core.Sample(index * 100L, if (index in 1_000..1_100) 25.0 else 20.0, 80)
        })
        val store=CompletedDriveStore(app.prefs)
        store.save(CompletedDrive(
            ai.musaz.h1score.core.ChallengeSession("feedback-drive","route",14000.0,ai.musaz.h1score.core.Phase.FINISHED),
            report,"피드백 테스트",true,listOf(ai.musaz.h1score.core.RankedEntry("나",report.score!!,1,0)),0,false))
        assertEquals(report,store.read()!!.report)
        val model=ui.runOnIdle {ChallengeViewModel(app).apply {
            receiveVehicle(parkedVehicle())
            openCompletedResult()
        }}
        ui.setContent {H1App(model)}
        ui.onNodeWithText("이번 주행 피드백").assertIsDisplayed()
        val bounds=ui.onNodeWithContentDescription("주행습관 점수 원").fetchSemanticsNode().boundsInRoot
        assertEquals(bounds.width,bounds.height,0.1f)
        ui.onAllNodesWithText("제한속도 초과",substring=true).onFirst().assertExists()
        val bitmap=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        java.io.File(ui.activity.filesDir,"result-feedback.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
        }
        bitmap.recycle()
        ui.onNodeWithText("함께 도착한 무사즈").performScrollTo().assertIsDisplayed()
        val legacy=org.json.JSONObject(app.prefs.getString("last-completed-drive-v1",null)!!)
        legacy.remove("events")
        app.prefs.edit().putString("last-completed-drive-v1",legacy.toString()).commit()
        assertEquals(report.copy(events=emptyList()),store.read()!!.report)
    }

    @Test fun completedResultRestoresAfterProcessStateLossAndWaitsForFreshPark() {
        val app=isolatedApplication()
        val report=ai.musaz.h1score.core.ScoreReport(null,0.0,0.0,0.0,0.0,false,0,0.0)
        val session=ai.musaz.h1score.core.ChallengeSession("saved-drive","route",900.0,ai.musaz.h1score.core.Phase.FINISHED)
        CompletedDriveStore(app.prefs).save(CompletedDrive(session,report,"저장된 목적지",false,emptyList(),0,false))
        ui.runOnIdle {
            val restored=ChallengeViewModel(app)
            restored.openCompletedResult()
            assertEquals(Screen.RESULT,restored.screen)
            assertEquals(report,restored.report)
            assertEquals("저장된 목적지",restored.destinationName)
            assertTrue(!restored.resultUnlocked)
            restored.receiveVehicle(parkedVehicle())
            assertTrue(restored.resultUnlocked)
        }
    }

    private fun parkedVehicle() = SystemClock.elapsedRealtime().let { now ->
        LiveVehicle(speedMps = 0.0, speedAt = now, gearPark = true, uxRestricted = false, belt = true, beltAt = now)
    }

    private fun isolatedApplication(): RecordingTestApplication =
        RecordingTestApplication(ui.activity.applicationContext)

    private class RecordingTestApplication(context: Context) : Application() {
        init { attachBaseContext(context) }
        val prefs: SharedPreferences =
            context.getSharedPreferences("test-recording-${UUID.randomUUID()}", MODE_PRIVATE)
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = prefs
        override fun startForegroundService(service: Intent): ComponentName? = service.component
        override fun stopService(name: Intent): Boolean = true
        var navigationLaunches=0
        override fun startActivity(intent: Intent) { navigationLaunches++ }
    }
}
