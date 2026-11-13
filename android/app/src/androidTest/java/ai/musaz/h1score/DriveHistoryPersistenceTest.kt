package ai.musaz.h1score

import android.os.SystemClock

import ai.musaz.h1score.core.ChallengeRound
import ai.musaz.h1score.core.ChallengeSession
import ai.musaz.h1score.core.DrivingEvent
import ai.musaz.h1score.core.DrivingEventType
import ai.musaz.h1score.core.Phase
import ai.musaz.h1score.core.RankedEntry
import ai.musaz.h1score.core.ScoreReport
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.delay
import kotlin.math.min

class DriveHistoryPersistenceTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()
    private val applications = mutableListOf<HistoryTestApplication>()
    private val models = mutableListOf<ViewModelStore>()

    @After fun cleanIsolatedState() {
        ui.runOnIdle { models.forEach(ViewModelStore::clear) }
        applications.forEach { it.prefs.edit().clear().commit() }
    }

    @Test fun newStoreRestoresBothDrivesWithTheirOwnEventsAndResults() {
        val app = application()
        val first = drive("first", 1_000L, false, 91)
        val second = drive("second", 2_000L, true, 78)
        CompletedDriveStore(app.prefs).apply { save(first); save(second) }

        val restored = CompletedDriveStore(app.prefs)
        assertEquals(listOf(second, first), restored.readAll())
        assertEquals(second, restored.read())
        assertEquals(setOf(false, true), restored.readAll().map { it.demo }.toSet())
    }

    @Test fun savingTheSameSessionAgainUpdatesItWithoutLosingAnotherDriveOrItsDate() {
        val app = application()
        val first = drive("first", 1_000L)
        val second = drive("second", 2_000L)
        val store = CompletedDriveStore(app.prefs)
        store.save(first)
        store.save(second)
        store.save(first.copy(name = "다시 저장된 첫 목적지", completedAtEpochMs = null))

        val restored = CompletedDriveStore(app.prefs).readAll()
        assertEquals(listOf("second", "first"), restored.map { it.session.id })
        assertEquals(1_000L, restored.last().completedAtEpochMs)
        assertEquals("다시 저장된 첫 목적지", restored.last().name)
    }

    @Test fun legacyResultWithoutEventsOrDateSurvivesTheFirstNewSave() {
        val app = application()
        val old = drive("legacy", null).copy(report = drive("legacy", null).report.copy(events = emptyList()))
        app.prefs.edit().putString("last-completed-drive-v1", legacyJson(old).toString()).commit()

        val store = CompletedDriveStore(app.prefs)
        assertEquals(listOf(old), store.readAll())
        assertNull(store.readAll().single().completedAtEpochMs)
        val next = drive("new", 3_000L)
        store.save(next)

        val restored = CompletedDriveStore(app.prefs).readAll()
        assertEquals(listOf("new", "legacy"), restored.map { it.session.id })
        assertNull(restored.last().completedAtEpochMs)
        assertTrue(restored.last().report.events.isEmpty())
        assertEquals("speed70-accel30-v1", restored.last().scoringVersion)
    }

    @Test fun aMalformedRecordDoesNotHideOtherSavedDrives() {
        val app = application()
        val first = drive("first", 1_000L)
        val second = drive("second", 2_000L)
        CompletedDriveStore(app.prefs).apply { save(first); save(second) }
        val rows = JSONArray(app.prefs.getString("completed-drives-v1", null)!!)
        rows.put(JSONObject().put("id", "damaged-record"))
        app.prefs.edit().putString("completed-drives-v1", rows.toString()).commit()

        assertEquals(listOf(second, first), CompletedDriveStore(app.prefs).readAll())
    }

    @Test fun ordinaryHistoryEntryOnANewModelShowsBothModesAndEachSelectedResult() {
        val app = application()
        val first = drive("first", 1_000L, false, 91)
        val second = drive("second", 2_000L, true, 78)
        CompletedDriveStore(app.prefs).apply { save(first); save(second) }
        val vm = ui.runOnIdle { model(app).apply { receiveVehicle(parkedVehicle()); navigate(Screen.HISTORY) } }
        val originalPreferences = app.prefs.all.toMap()
        ui.setContent { H1App(vm) }

        ui.onNodeWithText(first.name).assertIsDisplayed()
        ui.runOnIdle { vm.receiveVehicle(parkedVehicle()) }
        ui.onNodeWithText(second.name).assertIsDisplayed().performClick()
        ui.runOnIdle {
            assertEquals(second, vm.selectedHistoryRecord)
            assertEquals(Screen.RESULT, vm.screen)
            assertNull(vm.session)
            assertNull(vm.report)
            assertFalse(vm.demo)
        }
        ui.onNodeWithText(second.name).assertIsDisplayed()
        ui.onNodeWithText("78").assertIsDisplayed()

        ui.runOnIdle { vm.receiveVehicle(parkedVehicle()); vm.navigate(Screen.HISTORY) }
        ui.onNodeWithText(first.name).performClick()
        ui.runOnIdle { assertEquals(first, vm.selectedHistoryRecord) }
        ui.onNodeWithText(first.name).assertIsDisplayed()
        ui.onNodeWithText("91").assertIsDisplayed()
        assertEquals(originalPreferences, app.prefs.all)
    }

    @Test fun historyDatesAndSortControlsPreserveRecordSelectionAndSavedData() {
        val app = application()
        val first = drive("earlier", Instant.parse("2026-11-10T23:35:00Z").toEpochMilli(), false, 91)
        val second = drive("later", Instant.parse("2026-11-11T00:10:00Z").toEpochMilli(), true, 78)
        CompletedDriveStore(app.prefs).apply { save(first); save(second) }
        val vm = ui.runOnIdle { model(app).apply { receiveVehicle(parkedVehicle()); navigate(Screen.HISTORY) } }
        val originalPreferences = app.prefs.all.toMap()
        val originalRecords = vm.completedDrives.toList()
        ui.setContent {
            LaunchedEffect(vm) { while (true) { vm.receiveVehicle(parkedVehicle()); delay(200) } }
            H1App(vm)
        }

        val dates = listOf("2026.11.11 08:35", "2026.11.11 09:10")
        val dateHeader = ui.onNodeWithText("주행일").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        dates.forEach {
            val cell = ui.onNodeWithText(it, useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertEquals(dateHeader.left, cell.left, 1f)
        }
        ui.onNodeWithText("목적지").assertIsDisplayed()
        ui.onNodeWithText("주행거리").assertIsDisplayed()
        val score = ui.onNodeWithText("78", useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(score.left > dateHeader.right)
        ui.onNodeWithText("시연", substring = true).assertDoesNotExist()
        ui.onNodeWithText("최신순").assertIsSelected()
        assertHistoryAbove(second, first)

        selectHistoryOrder("오래된순")
        assertHistoryAbove(first, second)
        ui.runOnIdle { vm.receiveVehicle(parkedVehicle()) }
        ui.onNodeWithText(first.name).performClick()
        ui.runOnIdle {
            assertEquals(first, vm.selectedHistoryRecord)
            assertEquals(Screen.RESULT, vm.screen)
        }
        ui.onNodeWithText(first.name).assertIsDisplayed()
        ui.onNodeWithText("91").assertIsDisplayed()

        ui.runOnIdle { vm.receiveVehicle(parkedVehicle()) }
        ui.onNodeWithText("내 기록").performClick()
        ui.onNodeWithText("오래된순").assertIsSelected()
        assertHistoryAbove(first, second)
        selectHistoryOrder("최신순")
        assertHistoryAbove(second, first)
        ui.runOnIdle { assertEquals(originalRecords, vm.completedDrives) }
        assertEquals(originalPreferences, app.prefs.all)
    }

    @Test fun weeklyListResetsDayAndFollowsDisplayedWeekWithoutChangingSavedData() {
        val app = application()
        val a = drive("wednesday", Instant.parse("2026-11-11T00:10:00Z").toEpochMilli(), false, 95)
        val b = drive("friday", Instant.parse("2026-11-13T00:10:00Z").toEpochMilli(), false, 89)
        val previous = drive("previous-sunday", Instant.parse("2026-11-08T00:10:00Z").toEpochMilli(), false, 97)
        val unknown = drive("undated", null)
        CompletedDriveStore(app.prefs).apply { listOf(a, b, previous, unknown).forEach(::save) }
        val vm = ui.runOnIdle { model(app).apply { receiveVehicle(parkedVehicle()); navigate(Screen.HISTORY) } }
        val original = app.prefs.all.toMap()
        ui.setContent {
            LaunchedEffect(vm) { while (true) { vm.receiveVehicle(parkedVehicle()); delay(200) } }
            MinimumHistoryFrame(vm)
        }
        ui.onNodeWithText("이 주 기록 · 2건").assertIsDisplayed()
        ui.onNodeWithText(previous.name).assertDoesNotExist()
        ui.onNodeWithText(unknown.name).assertDoesNotExist()
        ui.onNodeWithContentDescription("2026년 11월 11일").performClick()
        ui.onNodeWithText(a.name).assertIsDisplayed()
        ui.onNodeWithText(b.name).assertDoesNotExist()
        captureHistoryExample("day-selected")
        ui.onNodeWithText("이 주 전체 보기").performClick()
        ui.onNodeWithText("이 주 기록 · 2건").assertIsDisplayed()
        ui.onNodeWithText(b.name).assertIsDisplayed()
        ui.onNodeWithContentDescription("2026년 11월 11일").assertIsNotSelected()
        ui.onNodeWithContentDescription("2026년 11월 11일").performClick()
        ui.onNodeWithTag("history-week-strip").performTouchInput { swipeRight(startX = width * .25f, endX = width * .75f) }
        ui.onNodeWithText("이 주 기록 · 1건").assertIsDisplayed()
        ui.onNodeWithText("이 주 전체 보기").assertDoesNotExist()
        ui.onNodeWithText(previous.name).assertIsDisplayed()
        ui.onNodeWithContentDescription("다음 주").performClick()
        ui.onNodeWithText("이 주 기록 · 2건").assertIsDisplayed()
        ui.onNodeWithTag("history-view-month").performClick()
        ui.onNodeWithContentDescription("2026년 11월 8일").performClick()
        ui.onNodeWithContentDescription("2026년 11월 8일").assertIsSelected()
        ui.onNodeWithText(previous.name).assertIsDisplayed()
        ui.onNodeWithText("이 주 전체 보기").performClick()
        ui.onNodeWithText("이 주 기록 · 1건").assertIsDisplayed()
        assertEquals(original, app.prefs.all)
    }

    @Test fun transitioningCalendarHidesListInteractionAndFitsThreeScores() {
        val app = application()
        val time = Instant.parse("2026-11-11T00:10:00Z").toEpochMilli()
        CompletedDriveStore(app.prefs).apply {
            save(drive("a", time, false, 95)); save(drive("b", time + 1000, false, 89)); save(drive("c", time + 2000, false, 80))
            val august = Instant.parse("2026-08-15T00:10:00Z").toEpochMilli()
            save(drive("d", august, false, 95)); save(drive("e", august + 1000, false, 89)); save(drive("f", august + 2000, false, 80))
        }
        val vm = ui.runOnIdle { model(app).apply { receiveVehicle(parkedVehicle()); navigate(Screen.HISTORY) } }
        ui.setContent {
            LaunchedEffect(vm) { while (true) { vm.receiveVehicle(parkedVehicle()); delay(200) } }
            MinimumHistoryFrame(vm)
        }
        ui.mainClock.autoAdvance = false
        ui.onNodeWithTag("history-view-month").performClick()
        ui.mainClock.advanceTimeBy(240)
        ui.onNodeWithTag("history-record-list").assertDoesNotExist()
        captureHistoryExample("month-transition")
        ui.mainClock.advanceTimeBy(600)
        ui.onNodeWithText("95 · 89점\n외 1경기", useUnmergedTree = true).assertIsDisplayed()
        captureHistoryExample("month-compact")
        ui.mainClock.autoAdvance = true
        repeat(3) { ui.onNodeWithContentDescription("이전 달").performClick() }
        ui.onNodeWithText("2026년 8월").assertIsDisplayed()
        ui.onNodeWithText("95 · 89점\n외 1경기", useUnmergedTree = true).assertIsDisplayed()
        captureHistoryExample("month-six-weeks")
        ui.onNodeWithTag("history-view-week").performClick()
        ui.onNodeWithText("2026년 11월 9일 – 11월 15일").assertIsDisplayed()
        ui.onNodeWithContentDescription("2026년 11월 7일").assertIsDisplayed()
        ui.onNodeWithContentDescription("2026년 11월 17일").assertIsDisplayed()
        ui.onNodeWithTag("history-view-month").performClick()
        ui.onNodeWithTag("history-view-week").performClick()
        ui.onNodeWithTag("history-week-strip").assertIsDisplayed()
    }

    @Test fun monthCalendarSelectsDayRestoresWheelAndKeepsAllRecords() {
        val app = application()
        val a = drive("morning", Instant.parse("2026-11-11T00:10:00Z").toEpochMilli(), false, 95)
        val b = drive("evening", Instant.parse("2026-11-11T09:10:00Z").toEpochMilli(), false, 89)
        val october = drive("october", Instant.parse("2026-10-31T00:10:00Z").toEpochMilli(), false, 0)
        val legacy = drive("undated", null)
        CompletedDriveStore(app.prefs).apply { listOf(a,b,october,legacy).forEach(::save) }
        val vm = ui.runOnIdle { model(app).apply { receiveVehicle(parkedVehicle()); navigate(Screen.HISTORY) } }
        val original = app.prefs.all.toMap()
        ui.setContent {
            LaunchedEffect(vm) { while (true) { vm.receiveVehicle(parkedVehicle()); delay(200) } }
            H1App(vm)
        }
        ui.onNodeWithTag("history-view-week").assertIsSelected()
        val fixedControl = ui.onNodeWithTag("history-view-month").fetchSemanticsNode().boundsInRoot
        ui.onNodeWithContentDescription("2026년 11월 7일").assertIsDisplayed()
        ui.onNodeWithContentDescription("2026년 11월 17일").assertIsDisplayed()
        ui.onNodeWithTag("history-view-month").performClick()
        ui.onNodeWithTag("history-month-grid").assertIsDisplayed()
        ui.onNodeWithTag("history-view-month").assertIsSelected()
        ui.onNodeWithTag("history-view-month").performClick()
        ui.onNodeWithTag("history-month-grid").assertIsDisplayed()
        assertEquals(fixedControl, ui.onNodeWithTag("history-view-month").fetchSemanticsNode().boundsInRoot)
        ui.onNodeWithTag("history-record-list").assertDoesNotExist()
        ui.onNodeWithText("95 · 89점", useUnmergedTree = true).assertIsDisplayed()
        captureHistoryExample("month")
        ui.onNodeWithContentDescription("2026년 11월 11일").performClick()
        ui.onNodeWithTag("history-month-grid").assertDoesNotExist()
        ui.onNodeWithTag("history-view-week").assertIsSelected()
        ui.onNodeWithTag("history-view-week").performClick()
        ui.onNodeWithTag("history-week-strip").assertIsDisplayed()
        ui.onNodeWithContentDescription("2026년 11월 11일").assertIsSelected()
        ui.onNodeWithContentDescription("2026년 11월 7일").assertIsDisplayed()
        ui.onNodeWithContentDescription("2026년 11월 17일").assertIsDisplayed()
        assertHistoryAbove(b, a)
        captureHistoryExample("month-selected-week")
        ui.onNodeWithText(a.name).performClick()
        ui.onNodeWithText("내 기록").performClick()
        ui.onNodeWithContentDescription("2026년 11월 11일").assertIsSelected()
        ui.onNodeWithTag("history-view-month").performClick()
        ui.onNodeWithContentDescription("이전 달").performClick()
        ui.onNodeWithText("2026년 10월").assertIsDisplayed()
        ui.onNodeWithText("0점", useUnmergedTree = true).assertIsDisplayed()
        ui.onNodeWithContentDescription("2026년 10월 31일").performClick()
        ui.onNodeWithText("2026년 10월 26일 – 11월 1일").assertIsDisplayed()
        ui.onNodeWithContentDescription("2026년 10월 31일").assertIsSelected()
        ui.onNodeWithText(october.name).assertIsDisplayed()
        ui.onNodeWithTag("history-view-month").performClick()
        ui.onNodeWithText("2026년 10월").assertIsDisplayed()
        ui.onNodeWithContentDescription("2026년 10월 30일").performClick()
        ui.onNodeWithText("이날은 주행 기록이 없어요").assertIsDisplayed()
        ui.onNodeWithTag("history-sort").performClick()
        ui.onNodeWithText("전체 기록").assertDoesNotExist()
        ui.onNodeWithText("정렬 기준").assertIsDisplayed()
        assertEquals(original, app.prefs.all)
    }

    @Test fun demoHistoryExamplesOpenWithoutChangingSavedRecordsOrRewards() {
        val app = application()
        val vm = ui.runOnIdle { model(app).apply { receiveVehicle(parkedVehicle()); navigate(Screen.HISTORY) } }
        val original = app.prefs.all.toMap()
        ui.setContent { H1App(vm) }

        ui.onNodeWithText("예시 기록", substring = true).assertDoesNotExist()
        ui.runOnIdle { vm.receiveVehicle(parkedVehicle()); vm.switchSource(); vm.navigate(Screen.HISTORY) }
        ui.onNodeWithText("여의도한강공원").assertIsDisplayed()
        captureHistoryExample("list")
        ui.onNodeWithTag("history-sort").performClick()
        ui.onNodeWithTag("history-sort-menu").assertIsDisplayed()
        ui.waitForIdle()
        captureHistoryExample("sort-menu")
        ui.onNode(hasText("최신순") and hasAnyAncestor(isPopup())).performClick()
        ui.onNodeWithTag("history-view-month").performClick()
        ui.onNodeWithTag("history-month-grid").assertIsDisplayed()
        ui.waitForIdle()
        captureHistoryExample("month-demo")
        ui.onNodeWithTag("history-view-week").performClick()
        ui.runOnIdle { vm.receiveVehicle(parkedVehicle()) }
        ui.onNodeWithText("여의도한강공원").performClick()
        ui.onNodeWithText("예시 기록", substring = true).assertIsDisplayed()
        ui.onNodeWithText("95").assertIsDisplayed()
        val score = ui.onNodeWithContentDescription("주행습관 점수 원").fetchSemanticsNode().boundsInRoot
        val metric = ui.onNodeWithText("1,133 m").fetchSemanticsNode().boundsInRoot
        val good = ui.onNodeWithText("이어갈 습관").fetchSemanticsNode().boundsInRoot
        val improve = ui.onNodeWithText("개선할 점").fetchSemanticsNode().boundsInRoot
        assertTrue(metric.left > score.right)
        assertTrue(good.top > score.bottom)
        assertTrue(improve.top > good.bottom)
        captureHistoryExample("detail")
        ui.onNodeWithText("마일리지 자세히 보기").performClick()
        dialogText("마일리지 적립 내역").assertIsDisplayed()
        dialogText("순위 보상 +100M · 배지 보상 +15M").assertIsDisplayed()
        ui.onNodeWithContentDescription("닫기").performClick()
        ui.runOnIdle {
            assertTrue(vm.completedDrives.isEmpty())
            assertEquals(0, vm.balance)
            assertEquals(original, app.prefs.all)
            vm.receiveVehicle(parkedVehicle())
            vm.switchSource()
            vm.navigate(Screen.HISTORY)
        }
        ui.onNodeWithText("첫 드라이브를 기다리고 있어요").assertIsDisplayed()
        ui.onNodeWithText("여의도한강공원").assertDoesNotExist()
    }

    @Test fun calendarFiltersDaysAndPreservesSortDetailSelectionAndSavedData() {
        val app = application()
        val first = drive("earlier", Instant.parse("2026-11-10T23:35:00Z").toEpochMilli(), false, 91)
        val second = drive("later", Instant.parse("2026-11-11T00:10:00Z").toEpochMilli(), true, 78)
        val otherDay = drive("other-day", Instant.parse("2026-11-10T14:59:59Z").toEpochMilli(), true, 65)
        val priorMonth = drive("prior-month", Instant.parse("2026-10-31T00:10:00Z").toEpochMilli())
        val unknown = drive("legacy", null)
        val saved = listOf(first, second, otherDay, priorMonth, unknown)
        CompletedDriveStore(app.prefs).apply { saved.forEach(::save) }
        val vm = ui.runOnIdle { model(app).apply { receiveVehicle(parkedVehicle()); navigate(Screen.HISTORY) } }
        val originalPreferences = app.prefs.all.toMap()
        val originalRecords = vm.completedDrives.toList()
        ui.setContent {
            LaunchedEffect(vm) { while (true) { vm.receiveVehicle(parkedVehicle()); delay(200) } }
            H1App(vm)
        }

        ui.onNodeWithText("2026년 11월 9일 – 11월 15일").assertIsDisplayed()
        val previousEdge = ui.onNodeWithContentDescription("2026년 11월 7일").assertIsDisplayed()
        val nextEdge = ui.onNodeWithContentDescription("2026년 11월 17일").assertIsDisplayed()
        val middle = ui.onNodeWithContentDescription("2026년 11월 12일").fetchSemanticsNode().boundsInRoot
        assertEquals(middle.center.y, previousEdge.fetchSemanticsNode().boundsInRoot.center.y, 1f)
        assertEquals(middle.center.y, nextEdge.fetchSemanticsNode().boundsInRoot.center.y, 1f)
        ui.onNodeWithContentDescription("2026년 11월 11일").performClick().assertIsSelected()
        assertHistoryAbove(second, first)
        ui.onNodeWithText(otherDay.name).assertDoesNotExist()
        ui.onNodeWithText(priorMonth.name).assertDoesNotExist()
        ui.onNodeWithText(unknown.name).assertDoesNotExist()
        selectHistoryOrder("오래된순")
        assertHistoryAbove(first, second)
        ui.runOnIdle { vm.receiveVehicle(parkedVehicle()) }
        ui.onNodeWithText(first.name).performClick()
        ui.onNodeWithText(first.name).assertIsDisplayed()
        ui.onNodeWithText("91").assertIsDisplayed()
        ui.runOnIdle { assertEquals(first, vm.selectedHistoryRecord) }

        ui.runOnIdle { vm.receiveVehicle(parkedVehicle()) }
        ui.onNodeWithText("내 기록").performClick()
        ui.onNodeWithContentDescription("2026년 11월 11일").assertIsSelected()
        ui.onNodeWithText("오래된순").assertIsSelected()
        assertHistoryAbove(first, second)
        ui.onNodeWithContentDescription("2026년 11월 12일").performClick().assertIsSelected()
        ui.onNodeWithText("이날은 주행 기록이 없어요").assertIsDisplayed()
        saved.forEach { ui.onNodeWithText(it.name).assertDoesNotExist() }

        val calendar = ui.onNodeWithTag("history-week-strip")
        calendar.performTouchInput { swipeRight(startX = width * .25f, endX = width * .75f) }
        ui.onNodeWithText("2026년 11월 2일 – 11월 8일").assertIsDisplayed()
        ui.onNodeWithContentDescription("이전 주").performClick()
        ui.onNodeWithContentDescription("2026년 10월 31일").performClick().assertIsSelected()
        ui.onNodeWithText(priorMonth.name).assertIsDisplayed()
        ui.onNodeWithText("이날은 주행 기록이 없어요").assertDoesNotExist()
        ui.onNodeWithText(first.name).assertDoesNotExist()
        calendar.performTouchInput { swipeLeft(startX = width * .75f, endX = width * .25f) }
        ui.onNodeWithText("2026년 11월 2일 – 11월 8일").assertIsDisplayed()
        ui.onNodeWithContentDescription("2026년 11월 10일").performClick().assertIsSelected()
        ui.onNodeWithText("2026년 11월 9일 – 11월 15일").assertIsDisplayed()
        ui.onNodeWithText(otherDay.name).assertIsDisplayed()

        ui.onNodeWithTag("history-sort").performClick()
        ui.onNodeWithText("전체 기록").assertDoesNotExist()
        ui.onNodeWithText("정렬 기준").assertIsDisplayed()
        ui.runOnIdle { assertEquals(originalRecords, vm.completedDrives) }
        assertEquals(originalPreferences, app.prefs.all)
    }

    @Test fun savedFeedbackDoesNotChangeTheOrdinaryResultOrStoredDrive() {
        val app = application()
        val saved = drive("feedback", 1_000L, false, 91)
        CompletedDriveStore(app.prefs).save(saved)
        app.prefs.edit().putInt("balance-live", 140).commit()
        val vm = ui.runOnIdle { model(app).apply { receiveVehicle(parkedVehicle()); openCompletedResult() } }
        val originalPreferences = app.prefs.all.toMap()
        val originalSession = vm.session
        val originalReport = vm.report
        val originalBalance = vm.balance
        ui.setContent { H1App(vm) }

        ui.onNodeWithText("이번 주행 피드백").assertIsDisplayed()
        ui.onNodeWithText("70% 반영", substring = true).performScrollTo().assertIsDisplayed()
        ui.onNodeWithText("30% 반영", substring = true).performScrollTo().assertIsDisplayed()
        listOf("유효 주행", "데이터 수신", "급제동").forEach {
            ui.onNodeWithText(it).performScrollTo().assertIsDisplayed()
        }
        ui.onNodeWithText("운전 습관 요약").assertDoesNotExist()
        ui.onNodeWithText("이어갈 습관").assertDoesNotExist()
        ui.onNodeWithText("개선할 점").assertDoesNotExist()

        ui.runOnIdle { vm.receiveVehicle(parkedVehicle()) }
        ui.onNodeWithText("내 기록").performClick()
        ui.onNodeWithTag("history-record-list").performScrollToNode(hasText(saved.name))
        ui.runOnIdle { vm.receiveVehicle(parkedVehicle()) }
        ui.onNodeWithText(saved.name).performClick()
        ui.onNodeWithText("운전 습관 요약").assertIsDisplayed()
        ui.onNodeWithText("이번 주행 피드백").assertDoesNotExist()
        ui.onNodeWithText("91").assertIsDisplayed()
        ui.onNodeWithText("과속").assertIsDisplayed()
        ui.onNodeWithText("급가속").assertIsDisplayed()
        ui.onNodeWithText("급감속").assertIsDisplayed()
        assertSavedOverviewVisible()
        listOf("유효 주행", "데이터 수신", "급제동", "잘한 점").forEach {
            ui.onNodeWithText(it).assertDoesNotExist()
        }
        ui.runOnIdle {
            assertEquals(saved, vm.selectedHistoryRecord)
            assertEquals(originalSession, vm.session)
            assertEquals(originalReport, vm.report)
            assertEquals(originalBalance, vm.balance)
            vm.openCompletedResult()
            assertNull(vm.selectedHistoryRecord)
        }

        ui.onNodeWithText("이번 주행 피드백").assertIsDisplayed()
        ui.onNodeWithText("70% 반영", substring = true).performScrollTo().assertIsDisplayed()
        ui.onNodeWithText("30% 반영", substring = true).performScrollTo().assertIsDisplayed()
        ui.onNodeWithText("운전 습관 요약").assertDoesNotExist()
        ui.onNodeWithText("이어갈 습관").assertDoesNotExist()
        ui.onNodeWithText("개선할 점").assertDoesNotExist()
        listOf("유효 주행", "데이터 수신", "급제동").forEach {
            ui.onNodeWithText(it).performScrollTo().assertIsDisplayed()
        }
        assertEquals(saved, CompletedDriveStore(app.prefs).read())
        assertEquals(originalPreferences, app.prefs.all)
    }

    @Test fun savedOverviewOpensAllDetailsAndReturnsWithoutChangingTheSelectedHistory() {
        val app = application()
        val base = drive("details", Instant.parse("2026-11-11T00:10:00Z").toEpochMilli())
        val events = List(10) { index ->
            DrivingEvent(DrivingEventType.entries[index % 3], index * 60_000L,
                (index + 1) * 1_000.0, 70.0, 83.0, 80)
        }
        val saved = base.copy(
            report = base.report.copy(accelerationEvents = 3, overLimitDistanceM = 1_234.0, events = events),
            ranking = listOf(RankedEntry("나", 91, 1, 80), RankedEntry("함께 달린 무사즈", 91, 1, 80)),
            earned = 0,
        )
        CompletedDriveStore(app.prefs).save(saved)
        val vm = ui.runOnIdle { model(app).apply { receiveVehicle(parkedVehicle()); navigate(Screen.HISTORY) } }
        val originalPreferences = app.prefs.all.toMap()
        ui.setContent {
            LaunchedEffect(vm) {
                while (true) {
                    vm.receiveVehicle(parkedVehicle())
                    delay(200)
                }
            }
            MinimumHistoryFrame(vm)
        }

        ui.onNodeWithContentDescription("2026년 11월 11일").performClick()
        selectHistoryOrder("오래된순")
        ui.runOnIdle { vm.receiveVehicle(parkedVehicle()) }
        ui.onNodeWithText(saved.name).performSemanticsAction(SemanticsActions.OnClick)
        ui.runOnIdle { assertEquals(saved, vm.selectedHistoryRecord) }
        assertSavedOverviewVisible()
        ui.onNodeWithText("+0M", substring = true).assertIsDisplayed()
        ui.onNodeWithText("+80 M").assertDoesNotExist()
        ui.onNodeWithText("공동 1위", substring = true).assertIsDisplayed()
        ui.onNodeWithText("9분 0초", substring = true).assertDoesNotExist()

        ui.onNodeWithText("점수 자세히 보기").performClick()
        dialogText("운전점수 산정 내역").assertIsDisplayed()
        dialogText("기록과 점수 안내").assertIsDisplayed()
        dialogText("전체 횟수는 확인할 수 없어요", substring = true).performScrollTo().assertIsDisplayed()
        ui.onNodeWithContentDescription("닫기").performClick()
        assertSavedOverviewVisible()

        ui.onNodeWithText("주행 구간 10건 보기").assertDoesNotExist()

        ui.onNodeWithText("순위 자세히 보기").performClick()
        dialogText("참가자 순위").assertIsDisplayed()
        dialogText("함께 달린 무사즈").performScrollTo().assertIsDisplayed()
        dialogText("+0 M").performScrollTo().assertIsDisplayed()
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val screenshot = instrumentation.uiAutomation.takeScreenshot()
        ui.activity.filesDir.resolve("ranking-comparison.png").outputStream().use {
            screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        screenshot.recycle()
        ui.onNodeWithContentDescription("닫기").performClick()
        assertSavedOverviewVisible()
        ui.runOnIdle {
            assertEquals(saved, vm.selectedHistoryRecord)
            assertNull(vm.session)
            assertNull(vm.report)
        }

        ui.runOnIdle { vm.receiveVehicle(parkedVehicle()) }
        ui.onNodeWithContentDescription("기록 목록으로").performClick()
        ui.runOnIdle { assertEquals(Screen.HISTORY, vm.screen) }
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("history-week-strip").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithContentDescription("2026년 11월 11일").assertIsSelected()
        ui.onNodeWithText("오래된순").assertIsSelected()
        ui.onNodeWithText(saved.name).assertIsDisplayed()
        assertEquals(saved, CompletedDriveStore(app.prefs).read())
        assertEquals(originalPreferences, app.prefs.all)
    }

    @Test fun openingHistoryDoesNotReplaceAnActiveJourneyOrCreditRewardsAgain() {
        val app = application()
        val saved = drive("saved", 1_000L, false)
        CompletedDriveStore(app.prefs).save(saved)
        app.prefs.edit().putInt("balance", 140)
            .putStringSet("settled", setOf("saved"))
            .putStringSet("badges-demo", setOf("first-drive"))
            .putLong("recorded-distance-demo", 12_345L).commit()
        ui.runOnIdle {
            val vm = model(app).apply { receiveVehicle(parkedVehicle()); switchSource() }
            val now = Instant.now()
            vm.accept(ChallengeRound("진행 중", now.minusSeconds(30), now.plusSeconds(600)))
            assertTrue(vm.activeJourney)
            val session = vm.session
            val report = vm.report
            val remaining = vm.remainingM
            val preferences = app.prefs.all.toMap()

            vm.openHistoryRecord(saved.session.id)
            assertEquals(saved, vm.selectedHistoryRecord)
            assertEquals(session, vm.session)
            assertEquals(report, vm.report)
            assertEquals(remaining, vm.remainingM, 0.0)
            assertTrue(vm.demo)
            assertTrue(vm.activeJourney)
            assertEquals(140, vm.balance)
            assertEquals(preferences, app.prefs.all)
        }
    }

    @Test fun historySelectionStillRequiresParking() {
        val app = application()
        val saved = drive("saved", 1_000L)
        CompletedDriveStore(app.prefs).save(saved)
        ui.runOnIdle {
            val vm = model(app).apply { receiveVehicle(parkedVehicle().copy(gearPark = false)) }
            vm.openHistoryRecord(saved.session.id)
            assertNull(vm.selectedHistoryRecord)
            assertEquals(Screen.HOME, vm.screen)
            vm.receiveVehicle(parkedVehicle())
            vm.openHistoryRecord(saved.session.id)
            assertEquals(saved, vm.selectedHistoryRecord)
        }
    }

    private fun drive(id: String, time: Long?, demo: Boolean = true, score: Int = 91): CompletedDrive {
        val events = listOf(DrivingEvent(DrivingEventType.OVERSPEED, 15_000, 321.0, 70.0, 83.0, 80))
        return CompletedDrive(
            ChallengeSession(id, "destination-$id", 14_000.0, Phase.FINISHED,
                venueId = "venue-$id", competitionId = "round-$id"),
            ScoreReport(score, 88.0, 98.0, 10_300.0, 1.0, true, 0, 321.0, events),
            "저장 목적지 $id", demo, listOf(RankedEntry("나", score, 1, 80)), 80, false,
            completedAtEpochMs = time,
        )
    }

    private fun assertHistoryAbove(above: CompletedDrive, below: CompletedDrive) {
        // Dropdown dismissal can finish before the lazy list's placement frame.
        ui.waitUntil(3_000) {
            ui.onNodeWithText(above.name).fetchSemanticsNode().boundsInRoot.top <
                ui.onNodeWithText(below.name).fetchSemanticsNode().boundsInRoot.top
        }
        val first = ui.onNodeWithText(above.name).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val second = ui.onNodeWithText(below.name).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("${above.name} $first should appear before ${below.name} $second", first.top < second.top)
    }

    private fun selectHistoryOrder(label: String) {
        ui.onNodeWithTag("history-sort").performClick()
        ui.onNodeWithText(label).performClick()
        ui.onNodeWithText(label).assertIsSelected()
    }

    private fun captureHistoryExample(section: String) {
        val screenshot = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        ui.activity.filesDir.resolve("history-example-$section.png").outputStream().use {
            screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        screenshot.recycle()
    }

    private fun assertSavedOverviewVisible() {
        listOf("운전 습관 요약", "이어갈 습관", "개선할 점", "방향지시등",
            "점수 자세히 보기", "순위 자세히 보기").forEach {
            ui.onNodeWithText(it).assertIsDisplayed()
        }
        ui.onNodeWithContentDescription("기록 목록으로").assertIsDisplayed()
    }

    private fun dialogText(text: String, substring: Boolean = false) =
        ui.onNode(hasText(text, substring = substring) and hasAnyAncestor(isDialog()))

    /** Exercise the 555dp content area, even when the emulator offers a taller window. */
    @Composable private fun MinimumHistoryFrame(vm: ChallengeViewModel) {
        BoxWithConstraints {
            val density = LocalDensity.current
            val scale = min(maxWidth.value / 1180f, maxHeight.value / 680f)
            CompositionLocalProvider(LocalDensity provides Density(density.density * scale, density.fontScale)) {
                Box(Modifier.requiredSize(1180.dp, 680.dp)) { H1App(vm) }
            }
        }
    }

    /** Exact legacy shape: no history fields and no PR4 event array. */
    private fun legacyJson(value: CompletedDrive) = JSONObject().apply {
        val session = value.session
        val report = value.report
        put("id", session.id); put("destinationId", session.destinationId)
        put("initialDistanceM", session.initialDistanceM)
        put("venueId", session.venueId); put("competitionId", session.competitionId)
        put("name", value.name); put("demo", value.demo); put("earned", value.earned)
        put("missedDeadline", value.missedDeadline)
        put("score", report.score); put("compliance", report.compliance); put("smoothness", report.smoothness)
        put("distance", report.validDistanceM); put("coverage", report.coverage); put("valid", report.valid)
        put("accelerationEvents", report.accelerationEvents); put("overLimitDistance", report.overLimitDistanceM)
        put("ranking", JSONArray().apply { value.ranking.forEach { row ->
            put(JSONObject().put("id", row.id).put("score", row.score).put("rank", row.rank).put("miles", row.miles))
        } })
    }

    private fun parkedVehicle() = LiveVehicle(speedMps = 0.0, speedAt = SystemClock.elapsedRealtime(),
        gearPark = true, uxRestricted = false, belt = true, beltAt = SystemClock.elapsedRealtime())

    private fun application() = HistoryTestApplication(ui.activity.applicationContext).also(applications::add)

    private fun model(app: Application) = ChallengeViewModel(app).also { vm ->
        models += ViewModelStore().apply { put("history-test", vm) }
    }

    private class HistoryTestApplication(context: Context) : Application() {
        init { attachBaseContext(context) }
        val prefs: SharedPreferences = context.getSharedPreferences("test-history-${UUID.randomUUID()}", MODE_PRIVATE)
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = prefs
        override fun startForegroundService(service: Intent): ComponentName? = service.component
        override fun stopService(name: Intent): Boolean = true
        override fun startActivity(intent: Intent) = Unit
    }
}
