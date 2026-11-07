package ai.musaz.h1score

import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import ai.musaz.h1score.core.*
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*

class BeltDisqualificationTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    @Test fun unbucklingDisqualifiesAndCannotBeUndoneByRebuckling() {
        ui.runOnIdle {
            val vm=(ui.activity.application as H1Application).model
            vm.connection.stop()
            val now=SystemClock.elapsedRealtime()
            val parked=LiveVehicle(destination="벨트 검증",destinationId="belt-test",remainingM=14000.0,
                destinationAt=now,speedMps=0.0,speedAt=now,gearPark=true,uxRestricted=false,belt=true,beltAt=now)
            vm.receiveVehicle(parked)
            if(vm.demo)vm.switchSource()
            vm.receiveVehicle(parked)
            vm.accept()
            assertEquals(Phase.RESERVED,vm.phase)
        }
        // Let Android create/promote the foreground service before ending the session.
        Thread.sleep(1500)
        ui.runOnIdle {
            val vm=(ui.activity.application as H1Application).model
            val now=SystemClock.elapsedRealtime()
            val parked=LiveVehicle(destination="벨트 검증",destinationId="belt-test",remainingM=14000.0,
                destinationAt=now,speedMps=0.0,speedAt=now,gearPark=true,uxRestricted=false,belt=true,beltAt=now)
            val moving=parked.copy(remainingM=10000.0,speedMps=10.0,gearPark=false,uxRestricted=true)
            vm.receiveVehicle(moving)
            assertEquals(Phase.RECORDING,vm.phase)
            val balance=vm.balance
            vm.receiveVehicle(moving.copy(belt=null))
            assertEquals(Phase.RECORDING,vm.phase) // unknown is not proof of non-compliance
            vm.receiveVehicle(moving.copy(belt=false))
            assertEquals(Phase.DISQUALIFIED,vm.phase)
            vm.receiveVehicle(parked.copy(remainingM=0.0,arrived=true))
            assertEquals(Phase.DISQUALIFIED,vm.phase)
            assertNull(vm.report);assertTrue(vm.ranking.isEmpty());assertEquals(0,vm.earned)
            assertEquals(balance,vm.balance);assertTrue(vm.pendingBadges.isEmpty())
        }
        ui.onAllNodesWithText("실격",substring=true).fetchSemanticsNodes().let{assertTrue(it.isNotEmpty())}
        val inst=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        inst.uiAutomation.takeScreenshot().let{bitmap->
            java.io.File(ui.activity.filesDir,"belt-disqualified.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
            bitmap.recycle()
        }
    }
}
