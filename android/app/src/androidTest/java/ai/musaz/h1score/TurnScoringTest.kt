package ai.musaz.h1score

import android.location.Location
import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import ai.musaz.h1score.core.*
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*

/** Recorded telemetry replay through the real location adapter and session/scoring pipeline.
 * Synthetic GPS/TBT/vehicle input; this is not a road or live SDK delivery validation. */
class TurnScoringTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    @Test fun gpsTurnsSignalsAndBrakingReachTheResultScreen() {
        ui.runOnIdle { (ui.activity.application as H1Application).model.connection.stop() }
        Thread.sleep(1000)
        ui.runOnIdle {
            val vm=(ui.activity.application as H1Application).model
            val now=SystemClock.elapsedRealtime()
            val parked=LiveVehicle(destination="GPS 회전 검증",destinationId="turn-test",remainingM=14000.0,
                destinationAt=now,speedMps=0.0,speedAt=now,gearPark=true,uxRestricted=false,belt=true,beltAt=now)
            vm.receiveVehicle(parked)
            if(vm.demo)vm.switchSource()
            vm.receiveVehicle(parked);vm.accept()
            assertEquals(Phase.RESERVED,vm.phase)
        }
        Thread.sleep(1500)
        ui.runOnIdle {
            val vm=(ui.activity.application as H1Application).model
            val base=SystemClock.elapsedRealtime()
            val adapter=PleosConnection(ui.activity)
            for(i in 0..480) {
                val now=base+i*250L
                val segment=i/48 // 12 seconds per test manoeuvre
                val k=i%48
                val turn=segment<4 && k in 12..23
                val progress=((k-12)/12.0).coerceIn(0.0,1.0)
                val right=segment==1
                val sign=if(right)1 else -1
                val location=Location("gps").apply {
                    latitude=37.0+i*.00001;longitude=127.0+sign*progress*.0003
                    elapsedRealtimeNanos=now*1_000_000
                    accuracy=if(segment==3)50f else 3f
                    bearing=((sign*90*progress+360)%360).toFloat();bearingAccuracyDegrees=5f
                }
                adapter.receiveLocation(location)
                val gps=adapter.flow.value.gps
                assertNotNull(gps)
                val signal=when(segment) {0->"OFF";1->"LEFT";2->"LEFT";else->"OFF"}
                val speed=if(i in 360..363)6.0 else 10.0
                vm.receiveVehicle(LiveVehicle(destination="GPS 회전 검증",destinationId="turn-test",
                    remainingM=10000.0-i*2.5,destinationAt=now,speedMps=speed,speedAt=now,limitKph=50,limitAt=now,
                    gearPark=false,forward=true,uxRestricted=true,belt=true,beltAt=now,
                    gps=gps,steeringDeg=if(turn)sign*8.0 else 0.0,steeringAt=now,turnSignal=signal,turnAt=now,
                    instruction=if(right)TurnDirection.RIGHT else TurnDirection.LEFT,instructionDistanceM=20.0,instructionAt=now),now)
            }
            val end=base+121000
            vm.receiveVehicle(LiveVehicle(destination="GPS 회전 검증",destinationId="turn-test",remainingM=0.0,
                destinationAt=end,speedMps=0.0,speedAt=end,gearPark=true,uxRestricted=false,belt=true,beltAt=end,arrived=true),end)
            assertEquals(Phase.FINISHED,vm.phase)
            val r=vm.report!!
            assertEquals(3.0,r.turnPenalty,0.0001)
            assertEquals(listOf(TurnVerdict.UNUSED,TurnVerdict.MISMATCHED,TurnVerdict.MATCHED),r.turns.map {it.verdict})
            assertTrue(r.brakingEvents>0);assertTrue(r.accelerationEvents>0)
            assertTrue(r.smoothness<100);assertNotNull(r.score)
            assertEquals(60+r.earnedPoints-3,r.totalPoints,0.0001)
            val parkedNow=SystemClock.elapsedRealtime()
            vm.receiveVehicle(vm.live.copy(speedAt=parkedNow,beltAt=parkedNow,destinationAt=parkedNow))
        }
        ui.onNodeWithText("오늘의 주행습관 점수").assertExists()
        ui.onNodeWithText("부드러운 가감속").assertExists()
        ui.onNodeWithText("회전 감점 3.0점",substring=true).assertExists()
        ui.onNodeWithText("1km별 가점 보기").performClick()
        ui.onNodeWithText("좌회전 · 신호 미사용 −1점").assertExists()
        ui.onNodeWithText("우회전 · 반대 신호 −2점").assertExists()
        ui.onNodeWithText("좌회전 · 신호 일치").assertExists()
        ui.onNodeWithText("닫기").performClick()
    }
}
