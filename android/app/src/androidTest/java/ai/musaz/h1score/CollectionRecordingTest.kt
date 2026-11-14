package ai.musaz.h1score

import ai.musaz.h1score.core.ChallengeRound
import ai.musaz.h1score.core.CollectionProgress
import ai.musaz.h1score.core.RankBadgeGoal
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
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.DayOfWeek
import java.util.UUID

class CollectionRecordingTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()

    @Test fun platinumWeeklyQuestPersistsAndAwardsOnlyOnceInDemo() {
        val app = isolatedApplication()
        lateinit var model: ChallengeViewModel
        val goal = RankBadgeGoal.CONSISTENT_FLOW
        val monday = LocalDate.now(ChallengeRound.seoul).with(DayOfWeek.MONDAY)
        ui.runOnIdle {
            app.prefs.edit().apply {
                RankBadgeGoal.entries.filter { it.tier < ai.musaz.h1score.core.RankTier.PLATINUM }
                    .forEach { putInt("rank-badges-demo-${it.id}", it.targetDays) }
                putStringSet("rank-badges-demo-${goal.id}-weeks",
                    setOf(monday.minusWeeks(1).toString(), monday.minusWeeks(2).toString()))
            }.commit()
            model = ChallengeViewModel(app).apply {
                receiveVehicle(parkedVehicle())
                switchSource()
            }
            assertEquals(2, model.rankBadgeProgress.days(goal))
            val now = Instant.now()
            model.accept(ChallengeRound("주간 순위 검증", now.minusSeconds(60), now.plusSeconds(120)))
            model.runDemo()
        }
        ui.waitUntil(40_000) { model.report != null }
        ui.runOnIdle {
            assertEquals(3, model.rankBadgeProgress.days(goal))
            assertEquals(20, model.badgeMileageEarned)
            assertEquals(120, model.balance)
            val restored = ChallengeViewModel(app)
            assertEquals(0, restored.rankBadgeProgress.days(goal))
            restored.openCompletedResult()
            assertEquals(3, restored.rankBadgeProgress.days(goal))
            assertEquals(3, restored.rankBadgeProgress.weeksByGoal[goal]?.size)
            assertEquals(120, restored.balance)
            restored.openCompletedResult()
            assertEquals(120, restored.balance)
        }
    }

    @Test fun spotProgressRequiresMatchingArrivalAndParkAndPersistsOnce() {
        val app = isolatedApplication()
        ui.runOnIdle {
            val spot = SpotTour.SEOUL
            val day = LocalDate.now(ChallengeRound.seoul)
            val editor = app.prefs.edit()
            CollectionProgressStore(app.prefs).writeVisit(editor, false, spot,
                CollectionProgress().recordVisit(spot, day.minusDays(2)).recordVisit(spot, day.minusDays(1)))
            editor.commit()
            val model = ChallengeViewModel(app)
            val parked = parkedVehicle()
            model.receiveVehicle(parked)
            model.startSpotVisit(spot)
            assertEquals(spot, model.trackedSpot)

            val route = parked.copy(destination = spot.destinationName, destinationId = "seoul",
                destinationPoint = spot.location, remainingM = 1_000.0, destinationAt = SystemClock.elapsedRealtime())
            model.receiveVehicle(route)
            val arrival = route.copy(arrived = true, arrivedAt = Instant.now(), remainingM = 0.0)
            model.receiveVehicle(arrival.copy(destinationId = "other"))
            assertEquals(2, model.collectionProgress.visits(spot))
            model.receiveVehicle(arrival.copy(gearPark = false))
            assertEquals(2, model.collectionProgress.visits(spot))
            assertEquals(0, model.balance)
            model.receiveVehicle(arrival)
            model.receiveVehicle(arrival)
            assertNull(model.trackedSpot)
            assertEquals(3, model.collectionProgress.visits(spot))
            assertEquals(50, model.balance)
            assertEquals(50, ChallengeViewModel(app).balance)
            assertEquals(3, CollectionProgressStore(app.prefs).read(false).visits(spot))
        }
    }

    @Test fun completedDemoChallengeUsesOnlyTheDemoCollection() {
        val app = isolatedApplication()
        lateinit var model: ChallengeViewModel
        ui.runOnIdle {
            val lastMonday = LocalDate.now(ChallengeRound.seoul).minusWeeks(1).with(DayOfWeek.MONDAY)
            val previous = CollectionProgress((0L..3L).associate { lastMonday.plusDays(it) to 92 })
            val editor = app.prefs.edit().putInt("rank-badges-demo-${RankBadgeGoal.FIRST_RANK.id}", 4)
                .putInt("rank-badges-demo-${RankBadgeGoal.CALM_START.id}", 4)
            CollectionProgressStore(app.prefs).writeChallenge(editor, true, previous)
            editor.commit()
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
            assertEquals(5, model.collectionProgress.challengeDays)
            assertEquals(5, store.read(true).challengeDays)
            assertEquals(0, store.read(false).challengeDays)
            assertEquals(100, model.earned)
            assertEquals(30, model.badgeMileageEarned) // attendance 15 + first rank 5 + calm start 10
            assertEquals(130, model.balance)
            assertEquals(30, CompletedDriveStore(app.prefs).read()?.badgeMileage)
            val restored = ChallengeViewModel(app)
            assertEquals(0, restored.balance)
            restored.openCompletedResult()
            assertEquals(30, restored.badgeMileageEarned)
            assertEquals(130, restored.balance)
            assertTrue(model.recordedDistanceMeters >= 9_000)
            assertEquals(model.recordedDistanceMeters, app.prefs.getLong("recorded-distance-demo", 0))
            assertEquals(0L, app.prefs.getLong("recorded-distance-live", 0))
        }
    }

    @Test fun demoSpotMileageRequiresThreeVisitsAndStaysInTheDemoWallet() {
        val app = isolatedApplication()
        val model = ui.runOnIdle {
            ChallengeViewModel(app).apply {
                receiveVehicle(parkedVehicle())
                switchSource()
                receiveVehicle(LiveVehicle(speedMps = 0.0, gearPark = null, uxRestricted = false))
                navigate(Screen.BADGES)
            }
        }
        ui.setContent { H1App(model) }
        val spot = SpotTour.SEOUL
        val dialog = hasAnyAncestor(isDialog())
        ui.onNode(hasText("스팟 투어") and isSelectable()).performClick()
        ui.onNodeWithText("시연용 - 달성 예시보기").performClick()
        ui.onNodeWithText("서울 오토 라이브러리").performClick()
        ui.onNode(dialog and hasText("획득 보상 +50M")).assertIsDisplayed()
        ui.onNode(dialog and hasText("시연 방문 추가")).assertDoesNotExist()
        ui.onNode(dialog and hasText("확인")).performClick()
        ui.onNodeWithText("예시 종료").performClick()
        ui.onNodeWithText("서울 오토 라이브러리").performClick()
        val visit = ui.onNode(dialog and hasText("시연 방문 추가"))
        repeat(3) { index ->
            visit.performClick()
            ui.runOnIdle {
                assertEquals(index + 1, model.collectionProgress.visits(spot))
                assertEquals(if (index == 2) 50 else 0, model.balance)
            }
        }
        ui.onNode(dialog and hasText("3 / 3회 · 획득")).assertIsDisplayed()
        ui.onNode(dialog and hasText("시연 마일리지 적립이 완료됐어요.")).assertIsDisplayed()
        visit.assertDoesNotExist()
        ui.onNode(dialog and hasText("확인")).performClick()
        ui.onNode(hasText("마일리지") and isSelectable()).performClick()
        ui.onNodeWithText("시연 잔액").assertIsDisplayed()
        ui.onNodeWithText("50").assertIsDisplayed()
        ui.runOnIdle {
            model.startSpotVisit(spot)
            assertEquals(50, model.balance)
            assertEquals(3, model.collectionProgress.visits(spot))
            assertEquals(0, CollectionProgressStore(app.prefs).read(false).visits(spot))
            model.receiveVehicle(parkedVehicle())
            model.switchSource()
            assertEquals(0, model.balance)
            assertEquals(0, model.collectionProgress.visits(spot))
            val restored = ChallengeViewModel(app)
            restored.receiveVehicle(parkedVehicle())
            restored.switchSource()
            assertEquals(50, restored.balance)
            assertEquals(3, restored.collectionProgress.visits(spot))
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
            app.prefs.edit().putString("destination-shortcuts-v2", org.json.JSONArray().put(
                org.json.JSONObject()
                    .put("id", id).put("label", "학교").put("name", place.name)
                    .put("lat", place.point.latitude).put("lon", place.point.longitude)
            ).toString()).apply()
            val restored = model()
            assertEquals(place.name, restored.destinationShortcuts.single().searchQuery)
            assertTrue(restored.saveShortcut(id, "캠퍼스", "단국대학교"))
            assertEquals("단국대학교", model().destinationShortcuts.single().searchQuery)
            assertEquals("캠퍼스", model().destinationShortcuts.single().label)
            restored.receiveVehicle(parkedVehicle().copy(gearPark=false))
            assertNull(restored.addShortcut("운동장"))
            restored.removeShortcut(id)
            assertTrue(restored.destinationShortcuts.any { it.id==id })
            restored.receiveVehicle(parkedVehicle())
            restored.removeShortcut(id)
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

    @Test fun homeShortcutSavesTypedPlaceAndUsesNormalSearch() {
        val app = isolatedApplication()
        val vm = ui.runOnIdle {
            ChallengeViewModel(app).apply {
                receiveVehicle(parkedVehicle())
                switchSource()
            }
        }
        ui.setContent { MaterialTheme { ChallengeHomeScreen(vm) } }
        ui.onNode(hasSetTextAction()).performClick()
        ui.onNodeWithText("집").performClick()
        ui.onNodeWithText("장소 이름 또는 주소").performTextReplacement("여의도 한강공원")
        ui.runOnIdle { vm.receiveVehicle(parkedVehicle()) }
        ui.onNodeWithText("저장").performClick()
        ui.onNode(hasText("닫기") and hasAnyAncestor(isDialog())).performClick()
        ui.runOnIdle {
            assertEquals("여의도 한강공원", ChallengeViewModel(app).destinationShortcuts.first().searchQuery)
            vm.receiveVehicle(parkedVehicle())
        }

        ui.onNodeWithText("집").performClick()
        ui.waitUntil(5_000) {
            ui.onAllNodesWithText("이 목적지로 챌린지에 참가할까요?").fetchSemanticsNodes().isNotEmpty()
        }
        ui.onNode(hasText("내비 목적지") and hasAnyAncestor(isDialog())).assertIsDisplayed()
        ui.onAllNodes(hasText("여의도 한강공원") and hasAnyAncestor(isDialog())).onFirst().assertIsDisplayed()
        ui.runOnIdle { assertEquals(0, app.navigationLaunches) }
    }

    @Test fun resolvedPlaceOutsideVenueOffersNormalNavigation() {
        val app = isolatedApplication()
        val vm = ui.runOnIdle { ChallengeViewModel(app).apply { receiveVehicle(parkedVehicle()) } }
        val place = checkNotNull(ai.musaz.h1score.core.SavedPlace.from(
            "서울역", ai.musaz.h1score.core.GeoPoint(37.5547, 126.9707),
        ))
        ui.setContent { MaterialTheme { ResolvedDestinationDialog(vm, place) {} } }

        ui.onNodeWithText("일반 길 안내를 시작할까요?").assertIsDisplayed()
        ui.onNodeWithText("이 목적지로 챌린지에 참가할까요?").assertDoesNotExist()
        ui.runOnIdle { assertEquals(0, app.navigationLaunches) }
        val screenshot = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            .uiAutomation.takeScreenshot()
        ui.activity.filesDir.resolve("outside-venue-directions.png").outputStream().use {
            screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        screenshot.recycle()
    }

    @Test fun placeSuggestionChecksChallengeBeforeOpeningNavigation() {
        val app = isolatedApplication()
        val vm = ui.runOnIdle { ChallengeViewModel(app).apply { receiveVehicle(parkedVehicle()) } }
        ui.setContent { MaterialTheme { ChallengeHomeScreen(vm) } }

        val search = ui.onNode(hasSetTextAction())
        search.performTextReplacement("더현대")
        ui.waitUntil(5_000) { ui.onAllNodesWithText("더현대 서울").fetchSemanticsNodes().isNotEmpty() }
        ui.runOnIdle { vm.receiveVehicle(parkedVehicle()) }
        ui.onNodeWithText("더현대 서울").performClick()
        ui.onNodeWithText("이 목적지로 챌린지에 참가할까요?").assertIsDisplayed()
        ui.onNodeWithText("더현대 서울에서 1km 이내에 있는 가장 가까운 개최지예요.").assertIsDisplayed()
        ui.onNode(hasText("내비 목적지") and hasAnyAncestor(isDialog())).assertIsDisplayed()
        ui.onNode(hasText("더현대 서울") and hasAnyAncestor(isDialog())).assertIsDisplayed()
        for (number in listOf("①", "②", "③")) {
            ui.onNodeWithText(number).assertExists()
        }
        ui.runOnIdle { assertEquals(0, app.navigationLaunches) }
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
            fun model(point: ai.musaz.h1score.core.GeoPoint, id: String,
                participant: Application = app)=ChallengeViewModel(participant).apply {
                receiveVehicle(parkedVehicle().copy(destination="사용자가 고른 목적지",destinationId=id,
                    destinationPoint=point,destinationAt=SystemClock.elapsedRealtime(),remainingM=12000.0))
            }
            val venue=challengeSpots.single {it.venueId=="olympic-park"}
            val a=model(requireNotNull(venue.point),"poi-a")
            a.accept(round)
            assertEquals("olympic-park",a.session?.venueId)
            assertEquals("poi-a",a.session?.destinationId)
            assertEquals("사용자가 고른 목적지",a.destinationName)
            // Compare two participants, not a second same-day entry by the first driver.
            val b=model(requireNotNull(venue.point),"poi-b",isolatedApplication())
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
            receiveVehicle(parkedVehicle())
            switchSource()
            receiveVehicle(LiveVehicle(speedMps = 0.0, gearPark = null, uxRestricted = false))
            navigate(Screen.WALLET)
        } }
        ui.setContent { H1App(model) }
        ui.onNodeWithText("차곡차곡, 내 마일리지").assertIsDisplayed()
        ui.onNodeWithText("와이퍼",useUnmergedTree=true).assertIsDisplayed()
        ui.onNodeWithText("에어컨 필터",useUnmergedTree=true).assertIsDisplayed()
        ui.onNodeWithText("엔진오일",useUnmergedTree=true).assertIsDisplayed()
        ui.onNodeWithText("나의 쿠폰함").performScrollTo().performClick()
        ui.onNodeWithText("시연 예시 쿠폰").assertIsDisplayed()
        ui.onNodeWithText("AJ-7C29-4B16-8305").assertIsDisplayed()
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
        val number = ui.runOnIdle { model.couponNumbers.getValue("wiper") }
        assertTrue(number.matches(Regex("AJ-(?:[0-9A-F]{4}-){2}[0-9A-F]{4}")))
        ui.onNodeWithText("쿠폰 교환이 완료됐어요").assertIsDisplayed()
        ui.onNode(hasText("교환 완료") and hasAnyAncestor(isDialog())).assertIsNotEnabled()
        ui.onNodeWithText("닫기").performClick()
        ui.onNodeWithContentDescription("와이퍼 상세").performClick()
        ui.onNode(hasText("교환 완료") and hasAnyAncestor(isDialog())).assertIsNotEnabled()
        ui.runOnIdle {model.redeemDemoReward(mileageRewards.first());assertEquals(20,model.balance)}
        ui.onNodeWithText("닫기").performClick()
        ui.onNodeWithContentDescription("에어컨 필터 상세").performClick()
        ui.onNodeWithText("마일리지가 부족해요").performScrollTo().assertIsDisplayed()
        ui.onNodeWithText("280 M 더 모으면 교환할 수 있어요.").performScrollTo().assertIsDisplayed()
        ui.onNodeWithText("교환하기").assertIsNotEnabled()
        ui.runOnIdle {model.redeemDemoReward(mileageRewards[1]);assertEquals(20,model.balance);assertTrue("filter" !in model.redeemedRewards)}
        ui.onNodeWithText("취소").performClick()
        ui.onNodeWithText("나의 쿠폰함").performScrollTo().performClick()
        ui.onNodeWithText("와이퍼 교환").assertIsDisplayed()
        ui.onNodeWithText("시연 교환 완료").assertIsDisplayed()
        ui.onNodeWithText(number).assertIsDisplayed()
        ui.onNodeWithText("AJ-7C29-4B16-8305").assertIsDisplayed()
        val couponImage = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        ui.activity.filesDir.resolve("mileage-coupon-numbers.png").outputStream().use {
            couponImage.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        couponImage.recycle()
        ui.onNodeWithText("확인").performClick()
        ui.runOnIdle {
            assertEquals(20,model.balance)
            val restored = ChallengeViewModel(app)
            restored.receiveVehicle(parkedVehicle())
            restored.switchSource()
            assertTrue("wiper" in restored.redeemedRewards)
            assertEquals(number, restored.couponNumbers["wiper"])
            restored.switchSource()
            assertTrue(restored.couponNumbers.isEmpty())
        }
        ui.runOnIdle {model.navigate(Screen.PROFILE)}
        ui.onAllNodes(hasText("마이페이지") and isHeading()).assertCountEquals(1)
        ui.runOnIdle {model.navigate(Screen.BADGES)}
        ui.onAllNodesWithText("배지 컬렉션").assertCountEquals(1)
    }

    @Test fun existingDemoCouponGetsAStableNumberWithoutSpendingMileage() {
        val app = isolatedApplication()
        app.prefs.edit().putInt("balance", 100).putStringSet("redeemed-rewards", setOf("filter")).commit()

        ui.runOnIdle {
            fun demoModel() = ChallengeViewModel(app).apply {
                receiveVehicle(parkedVehicle())
                switchSource()
            }
            val first = demoModel()
            val number = first.couponNumbers.getValue("filter")
            val restored = demoModel()
            assertEquals(number, restored.couponNumbers["filter"])
            assertEquals(setOf("filter"), restored.redeemedRewards)
            assertEquals(100, restored.balance)
        }
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
