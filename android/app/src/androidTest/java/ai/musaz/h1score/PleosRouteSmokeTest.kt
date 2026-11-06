package ai.musaz.h1score

import android.os.SystemClock
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class PleosRouteSmokeTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test fun requestYeouidoRouteAndReceiveDestination() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveRoute") == "true")
        val connection = PleosConnection(ui.activity.applicationContext)
        ui.runOnIdle { connection.start() }
        try {
            ui.waitUntil(15_000) { connection.flow.value.statuses["NaviHelper"]?.let { "연결됨" in it || "목적지 수신" in it } == true }
            val started = SystemClock.elapsedRealtime()
            ui.runOnIdle { assertTrue(connection.requestChallengeRoute()) }
            val deadline = started + 20_000
            fun matched() = connection.flow.value.let {
                it.destinationAt >= started && (it.remainingM ?: 0.0) > 0 &&
                    it.destination?.filter(Char::isLetterOrDigit) == "여의도한강공원"
            }
            ui.waitUntil(22_000) { matched() || SystemClock.elapsedRealtime() >= deadline }
            assertTrue("No matching route; NaviHelper: ${connection.flow.value.statuses["NaviHelper"]}", matched())
        } finally {
            ui.runOnIdle { connection.stop() }
        }
    }
}
