package ai.musaz.h1score

import ai.musaz.h1score.core.*
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import kotlin.math.min

class BeltOutcomeDetailUiTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()

    @Test fun disqualifiedDetailShowsReasonWithoutScoreOrPositiveFeedback() {
        show(drive(DriveOutcome.DISQUALIFIED, BeltFailureReason.DRIVER_UNBUCKLED))
        ui.onNodeWithText("실격").assertIsDisplayed()
        ui.onNodeWithText("운전석 안전벨트 미착용").assertIsDisplayed()
        assertNoScoreOrReward()
        listOf("판정 자세히 보기", "순위 자세히 보기", "마일리지 자세히 보기").forEach { action ->
            ui.onNodeWithText(action).performClick()
            ui.onNode(hasText("운전석 안전벨트 미착용") and hasAnyAncestor(isDialog())).assertIsDisplayed()
            ui.onNode(hasText("마일리지·배지 적립에서 제외", substring = true) and hasAnyAncestor(isDialog())).assertIsDisplayed()
            assertNoScoreOrReward()
            ui.onNodeWithContentDescription("닫기").performClick()
        }
    }

    @Test fun unverifiableDetailKeepsUnknownDataDistinctFromABeltViolation() {
        show(drive(DriveOutcome.UNVERIFIABLE, BeltFailureReason.TELEMETRY_UNAVAILABLE))
        ui.onNodeWithText("확인 불가").assertIsDisplayed()
        ui.onNodeWithText("안전벨트·주행 데이터 확인 불가").assertIsDisplayed()
        ui.onNodeWithText("실격").assertDoesNotExist()
        assertNoScoreOrReward()
    }

    private fun assertNoScoreOrReward() {
        listOf("100", "0", "점수 미확정", "이어갈 습관", "+80M", "+80 M", "1위").forEach {
            ui.onAllNodesWithText(it).assertCountEquals(0)
        }
    }

    private fun show(drive: CompletedDrive) {
        ui.setContent {
            MaterialTheme(colorScheme = H1Style.colorScheme) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val density = LocalDensity.current
                    val scale = min(maxWidth.value / 963f, maxHeight.value / 555f)
                    CompositionLocalProvider(LocalDensity provides Density(density.density * scale, density.fontScale)) {
                        Box(Modifier.requiredSize(963.dp, 555.dp)) { SavedDriveDetailScreen(drive) {} }
                    }
                }
            }
        }
    }

    // Even a stale scored snapshot must not display rewards for an invalid outcome.
    private fun drive(outcome: DriveOutcome, reason: BeltFailureReason) = CompletedDrive(
        ChallengeSession("belt-ui", "destination", 14_000.0, Phase.DISQUALIFIED),
        ScoreReport(100, 100.0, 100.0, 10_000.0, 1.0, true, 0, 0.0),
        "저장 목적지", true, listOf(RankedEntry("나", 100, 1, 80)), 80, false,
        completedAtEpochMs = 1_794_456_000_000L, outcome = outcome, beltFailureReason = reason,
    )
}
