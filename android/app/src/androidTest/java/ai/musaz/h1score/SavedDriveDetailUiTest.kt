package ai.musaz.h1score

import ai.musaz.h1score.core.ChallengeSession
import ai.musaz.h1score.core.DrivingEvent
import ai.musaz.h1score.core.DrivingEventType
import ai.musaz.h1score.core.Phase
import ai.musaz.h1score.core.RankedEntry
import ai.musaz.h1score.core.ScoreEngine
import ai.musaz.h1score.core.ScoreReport
import ai.musaz.h1score.core.TurnDirection
import ai.musaz.h1score.core.TurnEvent
import ai.musaz.h1score.core.TurnVerdict
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import kotlin.math.min

/** Exercises dense saved reports without changing real preferences, rewards or recording state. */
class SavedDriveDetailUiTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()

    @Test fun busyOverviewFitsAndEveryCardAreaOpensItsFullView() {
        val saved = drive(busyReport())
        ui.setContent { MinimumDetailFrame(saved) }

        capture("busy")
        assertOverviewDoesNotScroll()
        assertFeedbackFits("saved-good-feedback")
        assertFeedbackFits("saved-improvement-feedback")
        ui.onNodeWithTag("saved-improvement-feedback", useUnmergedTree = true)
            .assertTextEquals(historyFeedback(saved.report).improvements.take(2).joinToString("\n"))
        ui.onNodeWithText("회전 감점", substring = true, useUnmergedTree = true).assertIsDisplayed()
        repeat(3) { ui.onNodeWithTag("saved-event-$it", useUnmergedTree = true).assertIsDisplayed() }
        ui.onNodeWithTag("saved-event-3", useUnmergedTree = true).assertDoesNotExist()
        // The overview keeps the user's row even when it falls below the first three places.
        ui.onNodeWithTag("saved-rank-나", useUnmergedTree = true).assertIsDisplayed()
        ui.onNodeWithText("+0 M", useUnmergedTree = true).assertIsDisplayed()
        ui.onNodeWithText("+80 M", useUnmergedTree = true).assertDoesNotExist()

        val feedback = hasAnyAncestor(hasTestTag("saved-feedback-card"))
        val targets = listOf(
            hasText("운전 습관 요약"),
            hasText("과속") and feedback,
            hasText("급가속") and feedback,
            hasText("급감속") and feedback,
            hasTestTag("saved-good-feedback"),
            hasTestTag("saved-improvement-feedback"),
            hasText("피드백 전체보기"),
        )
        targets.forEach { target ->
            // Physical taps also cover non-button text inside the clickable card.
            ui.onNode(target, useUnmergedTree = true).performTouchInput { click() }
            assertDialogAndClose("주행 피드백 전체보기")
        }
        ui.onNodeWithTag("saved-feedback-card").performTouchInput {
            click(Offset(width - 12f, height - 12f))
        }
        assertDialogAndClose("주행 피드백 전체보기")

        val ranking = hasAnyAncestor(hasTestTag("saved-ranking-card"))
        listOf(
            hasText("함께 도착한 무사즈") and ranking,
            hasTestTag("saved-rank-나"),
            hasText("+0 M") and ranking,
            hasText("순위 전체보기"),
        ).forEach { target ->
            ui.onNode(target, useUnmergedTree = true).performTouchInput { click() }
            assertDialogAndClose("함께 도착한 무사즈")
        }
        ui.onNodeWithTag("saved-ranking-card").performTouchInput {
            click(Offset(width / 2f, height - 10f))
        }
        assertDialogAndClose("함께 도착한 무사즈")

        val events = hasAnyAncestor(hasTestTag("saved-events-card"))
        listOf(
            hasText("주요 주행 구간") and events,
            hasTestTag("saved-event-0"),
            hasTestTag("saved-event-2"),
            hasText("구간 전체보기"),
        ).forEach { target ->
            ui.onNode(target, useUnmergedTree = true).performTouchInput { click() }
            assertDialogAndClose("저장된 주행 구간")
        }
        ui.onNodeWithTag("saved-events-card").performTouchInput {
            click(Offset(width / 2f, height - 10f))
        }
        assertDialogAndClose("저장된 주행 구간")
        assertOverviewDoesNotScroll()
    }

    @Test fun perfectScoreHasRoomForThreeDigitsAndBothPositiveFeedbackPoints() {
        val saved = drive(cleanReport()).copy(ranking = emptyList(), demo = false)
        ui.setContent { MinimumDetailFrame(saved) }

        capture("perfect")
        assertOverviewDoesNotScroll()
        assertFeedbackFits("saved-good-feedback")
        assertFeedbackFits("saved-improvement-feedback")
        ui.onNodeWithTag("saved-good-feedback", useUnmergedTree = true)
            .assertTextEquals(historyFeedback(saved.report).goodPoints.take(2).joinToString("\n"))
        val dial = ui.onNodeWithContentDescription("주행습관 점수 원").fetchSemanticsNode().boundsInRoot
        val score = ui.onNodeWithText("100", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val caption = ui.onNodeWithText("100점 만점", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("Three-digit score stays within its dial", dial.contains(score.topLeft) && dial.contains(score.bottomRight))
        assertTrue("Score and maximum caption must not overlap", score.bottom <= caption.top)
        assertTrue("Maximum caption stays within its dial", caption.bottom <= dial.bottom)
        ui.onNodeWithText("저장된 구간 정보가 없어요.", useUnmergedTree = true).assertIsDisplayed()
        ui.onNodeWithText("+0 M", useUnmergedTree = true).assertIsDisplayed()
    }

    private fun assertOverviewDoesNotScroll() {
        listOf("주행습관 점수", "운전 습관 요약", "이어갈 습관", "개선할 점", "함께 도착한 무사즈", "주요 주행 구간")
            .forEach { ui.onNodeWithText(it, useUnmergedTree = true).assertIsDisplayed() }
        ui.onAllNodes(hasScrollAction()).assertCountEquals(0)
    }

    private fun assertFeedbackFits(tag: String) {
        val results = mutableListOf<TextLayoutResult>()
        ui.onNodeWithTag(tag, useUnmergedTree = true).assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        assertEquals(1, results.size)
        val layout = results.single()
        val diagnostics = buildString {
            append("$tag: size=${layout.size}, constraints=${layout.layoutInput.constraints}, ")
            append("lineCount=${layout.lineCount}, maxLines=${layout.layoutInput.maxLines}, ")
            append("didOverflowWidth=${layout.didOverflowWidth}, didOverflowHeight=${layout.didOverflowHeight}, ")
            append("fontSize=${layout.layoutInput.style.fontSize}, lineHeight=${layout.layoutInput.style.lineHeight}, ")
            append("textLength=${layout.layoutInput.text.length}, text=${layout.layoutInput.text.text}")
            repeat(layout.lineCount) { line ->
                append("\nline[$line]: start=${layout.getLineStart(line)}, end=${layout.getLineEnd(line)}, ")
                append("visibleEnd=${layout.getLineEnd(line, visibleEnd = true)}, ellipsized=${layout.isLineEllipsized(line)}, ")
                append("top=${layout.getLineTop(line)}, bottom=${layout.getLineBottom(line)}, ")
                append("left=${layout.getLineLeft(line)}, right=${layout.getLineRight(line)}")
            }
        }
        android.util.Log.i("SavedDetailLayoutTest", diagnostics)
        assertFalse("Full summary must fit at the minimum frame size. $diagnostics", layout.hasVisualOverflow)
    }

    private fun assertDialogAndClose(title: String) {
        ui.onNode(hasText(title) and hasAnyAncestor(isDialog())).assertIsDisplayed()
        ui.onNodeWithContentDescription("닫기").performClick()
        ui.onAllNodes(isDialog()).assertCountEquals(0)
    }

    private fun capture(name: String) {
        ui.waitForIdle()
        val bitmap = ui.onRoot().captureToImage().asAndroidBitmap()
        File(ui.activity.filesDir, "history-detail-$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Composable private fun MinimumDetailFrame(saved: CompletedDrive) {
        MaterialTheme(colorScheme = H1Style.colorScheme) {
            BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val density = LocalDensity.current
                val scale = min(maxWidth.value / 963f, maxHeight.value / 555f)
                CompositionLocalProvider(LocalDensity provides Density(density.density * scale, density.fontScale)) {
                    Box(Modifier.requiredSize(963.dp, 555.dp).background(H1Style.Paper)) {
                        SavedDriveDetailScreen(saved) {}
                    }
                }
            }
        }
    }

    private fun cleanReport() = ScoreReport(
        score = 100, compliance = 100.0, smoothness = 100.0,
        validDistanceM = 10_000.0, coverage = 1.0, valid = true,
        accelerationEvents = 0, overLimitDistanceM = 0.0,
        speedPoints = 25.0, accelerationPoints = 15.0,
        longitudinalCoverage = 1.0, longitudinalReady = true, longitudinalSampleRateHz = 10.0,
        scoringVersion = ScoreEngine.VERSION,
    )

    private fun busyReport() = cleanReport().copy(
        score = 91, compliance = 87.66, smoothness = 82.67,
        accelerationEvents = 3, overLimitDistanceM = 1_234.0,
        speedPoints = 21.915, accelerationPoints = 12.4, brakingEvents = 3,
        events = List(10) { index ->
            DrivingEvent(DrivingEventType.entries[index % 3], (index + 1) * 60_000L,
                (index + 1) * 900.0, if (index % 3 == 2) 83.0 else 50.0,
                if (index % 3 == 2) 30.0 else 83.0, 80)
        },
        turns = listOf(
            TurnEvent(100_000, 103_000, TurnDirection.RIGHT, TurnVerdict.UNUSED),
            TurnEvent(200_000, 203_000, TurnDirection.LEFT, TurnVerdict.MISMATCHED),
        ),
    )

    private fun drive(report: ScoreReport) = CompletedDrive(
        session = ChallengeSession("detail-layout-only", "detail-destination", 12_000.0, Phase.FINISHED),
        report = report, name = "현대 모터스튜디오 고양", demo = true,
        ranking = listOf(
            RankedEntry("차분한 무사즈", 99, 1, 80),
            RankedEntry("함께 달린 무사즈", 97, 2, 50),
            RankedEntry("부드러운 드라이버", 95, 3, 30),
            RankedEntry("든든한 동행", 93, 4, 0),
            RankedEntry("나", report.score ?: 0, 5, 80),
        ),
        earned = 0, missedDeadline = false, completedAtEpochMs = 1_794_456_000_000L,
    )
}
