package ai.musaz.h1score

import ai.musaz.h1score.core.GeoPoint
import ai.musaz.h1score.core.SavedPlace
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test

class ChallengeEntryUiTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()

    @Test fun navigationRequiresBothSeatBeltAndConsent() {
        val vm = ui.runOnIdle { ChallengeViewModel(ui.activity.application) }
        val place = checkNotNull(SavedPlace.from("더현대 서울", GeoPoint(37.5259, 126.9284)))
        setBelt(vm, false)
        ui.setContent {
            H1App(vm)
            ChallengeEntryDialog(vm, hangangChallenge, place) {}
        }

        ui.onNodeWithText("더현대 서울에서 1km 이내에 있는 가장 가까운 개최지예요.").assertIsDisplayed()
        ui.onNodeWithText("(KST)", substring = true).assertIsDisplayed()
        for (step in listOf("③", "④", "⑤")) ui.onNodeWithText(step).assertIsDisplayed()
        val consent = ui.onNodeWithText("마감 및 참가 조건에 동의합니다")
        consent.performClick()
        val beltWarning = ui.onNodeWithText("운전석 안전벨트를 착용해야 챌린지를 시작할 수 있어요.")
        beltWarning.assertIsDisplayed()
        ui.onNodeWithText("챌린지 시작").assertIsNotEnabled().assertIsDisplayed()
        ui.onNodeWithText("길 안내만 열기").assertIsEnabled().assertIsDisplayed()
        capture("challenge-entry-belt-unbuckled")

        setBelt(vm, true)
        beltWarning.assertDoesNotExist()
        ui.onNodeWithText("챌린지 시작").assertIsEnabled().assertIsDisplayed()
        ui.onNodeWithText("길 안내만 열기").assertIsEnabled().assertIsDisplayed()
        capture("challenge-entry-belt-buckled")
        consent.performClick()
        ui.onNodeWithText("챌린지 시작").assertIsNotEnabled()
    }

    private fun setBelt(vm: ChallengeViewModel, buckled: Boolean) {
        ui.runOnIdle {
            val now = SystemClock.elapsedRealtime()
            vm.receiveVehicle(LiveVehicle(
                speedMps = 0.0, speedAt = now, gearPark = true, uxRestricted = false,
                belt = buckled, beltAt = now,
            ))
        }
    }

    private fun capture(name: String) {
        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        ui.activity.filesDir.resolve("$name.png").outputStream().use {
            screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        screenshot.recycle()
    }
}
