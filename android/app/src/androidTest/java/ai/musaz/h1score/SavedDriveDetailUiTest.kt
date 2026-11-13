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
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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

    @Test fun busyOverviewShowsMetricsAndEachDetailOpens() {
        val saved = drive(busyReport())
        ui.setContent { MinimumDetailFrame(saved) }

        capture("busy")
        listOf("운전 습관 요약", "과속", "급가속", "급감속", "방향지시등", "이어갈 습관", "개선할 점")
            .forEach { ui.onNodeWithText(it).assertIsDisplayed() }
        ui.onNodeWithText("속도 가점 +21.92점").assertIsDisplayed()
        ui.onAllNodesWithText("가감속 공통 +12.40점").assertCountEquals(1)
        ui.onNodeWithText("감점 −3.00점").assertIsDisplayed()
        ui.onNodeWithText("주행 구간", substring = true).assertDoesNotExist()
        listOf(
            "점수 자세히 보기" to "운전점수 산정 내역",
            "순위 자세히 보기" to "참가자 순위",
            "마일리지 자세히 보기" to "마일리지 적립 내역",
        ).forEach { (button, title) ->
            ui.onNodeWithText(button).performClick()
            assertDialogAndClose(title)
        }
    }

    @Test fun perfectScoreHasRoomForThreeDigitsAndBothPositiveFeedbackPoints() {
        val saved = drive(cleanReport()).copy(ranking = emptyList(), demo = false)
        ui.setContent { MinimumDetailFrame(saved) }

        capture("perfect")
        historyFeedback(saved.report).goodPoints.forEach { ui.onNodeWithText(it).assertIsDisplayed() }
        val dial = ui.onNodeWithContentDescription("주행습관 점수 원").fetchSemanticsNode().boundsInRoot
        val score = ui.onNodeWithText("100", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val caption = ui.onNodeWithText("100점 만점", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("Three-digit score stays within its dial", dial.contains(score.topLeft) && dial.contains(score.bottomRight))
        assertTrue("Score and caption must not overlap", score.bottom <= caption.top)
        ui.onNodeWithText("+0M").assertIsDisplayed()
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
