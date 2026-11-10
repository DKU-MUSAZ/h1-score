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
        val app = isolatedApplication()
        // This isolated screen has no live speed updates to refresh the parked reading.
        fun parkedModel() = ChallengeViewModel(app).also {
            it.receiveVehicle(parkedVehicle().copy(speedAt = Long.MAX_VALUE))
        }
        var model by mutableStateOf(ui.runOnIdle { parkedModel() })
        ui.setContent { MaterialTheme { key(model) { ChallengeHomeScreen(model) } } }

        ui.onNode(hasSetTextAction()).performClick()
        ui.onNodeWithText("집").performClick()
        val home = ui.onNode(hasSetTextAction() and hasText("집 주소 또는 장소"))
        val work = ui.onNode(hasSetTextAction() and hasText("회사 주소 또는 장소"))
        home.performTextReplacement("  서울역  ")
        work.performTextReplacement("시청역")
        ui.onNodeWithText("저장").performClick()
        ui.onNodeWithText("목적지 바로가기").assertDoesNotExist()

        ui.runOnIdle {
            model = parkedModel()
            assertEquals(DestinationShortcuts("서울역", "시청역"), model.destinationShortcuts)
        }
        ui.onNode(hasSetTextAction()).performClick()
        ui.onNodeWithText("서울역").assertIsDisplayed()
        ui.onNodeWithText("시청역").assertIsDisplayed()
        ui.onNodeWithText("집").performClick()
        ui.runOnIdle { assertEquals(listOf("서울역"), model.recentDestinations) }

        ui.onNode(hasSetTextAction()).performClick()
        ui.onNodeWithText("편집").performClick()
        home.performTextClearance()
        ui.onNodeWithText("저장").performClick()
        ui.runOnIdle { model = parkedModel() }
        ui.onNode(hasSetTextAction()).performClick()
        ui.onNodeWithText("집").performClick()
        home.assert(!hasText("서울역"))
        work.assertTextContains("시청역")
    }

    @Test fun demoSearchFindsDankookUniversityWithoutTheCampusSuffix() {
        val app = isolatedApplication()
        val model = ui.runOnIdle {
            ChallengeViewModel(app).apply {
                receiveVehicle(parkedVehicle().copy(speedAt = Long.MAX_VALUE))
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

        ui.onNodeWithText("시연 참가 예약").assertExists()
        ui.runOnIdle { assertEquals(listOf("단국대학교 죽전캠퍼스"), model.recentDestinations) }
    }

    private fun parkedVehicle() = LiveVehicle(speedMps = 0.0, speedAt = SystemClock.elapsedRealtime(),
        gearPark = true, uxRestricted = false, belt = true)

    private fun isolatedApplication(): RecordingTestApplication =
        RecordingTestApplication(ui.activity.applicationContext)

    private class RecordingTestApplication(context: Context) : Application() {
        init { attachBaseContext(context) }
        val prefs: SharedPreferences =
            context.getSharedPreferences("test-recording-${UUID.randomUUID()}", MODE_PRIVATE)
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = prefs
        override fun startForegroundService(service: Intent): ComponentName? = service.component
        override fun stopService(name: Intent): Boolean = true
        override fun startActivity(intent: Intent) = Unit
    }
}
