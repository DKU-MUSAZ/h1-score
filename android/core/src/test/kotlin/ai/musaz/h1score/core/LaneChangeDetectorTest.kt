package ai.musaz.h1score.core

import kotlin.math.*
import kotlin.test.*
import org.junit.Test

class LaneChangeDetectorTest {
    private fun trace(sign: Int=-1, accuracy: Double=.5): List<TurnObservation> = (0..40).map { i ->
        val t=i*250L
        val p=((i-8)/16.0).coerceIn(0.0,1.0)
        val x=sign*3.5*(1-cos(PI*p))/2
        val bearing=(Math.toDegrees(atan2(sign*3.5*PI/32*sin(PI*p),2.0))+360)%360
        TurnObservation(t,GpsFix(t,37+i*2.0/111195,127+x/(111195*cos(Math.toRadians(37.0))),accuracy,bearing,2.0),
            8.0,true,when(i){in 8..15->sign*8.0;in 16..23->-sign*8.0;else->0.0},
            SignalState.OFF,null,null,t)
    }
    private fun detect(input: List<TurnObservation>)=LaneChangeDetector().also { d->input.forEach(d::accept) }.events
    @Test fun leftAndRightCandidatesAreRecordedWithoutPenalty() {
        for(sign in listOf(-1,1)) {
            val event=detect(trace(sign)).single()
            assertEquals(if(sign<0)TurnDirection.LEFT else TurnDirection.RIGHT,event.direction)
            assertEquals(ManeuverKind.LANE_CHANGE,event.kind)
            assertEquals(TurnVerdict.UNVERIFIABLE,event.verdict)
            assertEquals(TurnVerdict.UNUSED,event.observedSignal)
            assertEquals(0.0,event.penalty)
        }
    }
    @Test fun inaccurateGpsCannotEstablishLateralDisplacement() { assertTrue(detect(trace(accuracy=3.0)).isEmpty()) }
    @Test fun straightRoadIsNotLaneChange() { assertTrue(detect(trace().map{it.copy(steeringDeg=0.0)}).isEmpty()) }
    @Test fun oneSidedCurveIsNotLaneChange() { assertTrue(detect(trace().map{if(it.atMs in 2000..5750) it.copy(steeringDeg=-8.0) else it}).isEmpty()) }
    @Test fun navigationTurnIsNotAlsoLaneChange() { assertTrue(detect(trace().map{it.copy(instruction=TurnDirection.LEFT,instructionDistanceM=20.0)}).isEmpty()) }
    @Test fun reverseAndHazardAreExcluded() {
        assertTrue(detect(trace().map{it.copy(forward=false)}).isEmpty())
        assertTrue(detect(trace().map{it.copy(hazardOn=true)}).isEmpty())
    }
    @Test fun gapCannotJoinTwoPartsOfAManoeuvre() { assertTrue(detect(trace().filterNot{it.atMs in 3250..5000}).isEmpty()) }
    @Test fun gpsJumpCannotProduceCandidate() {
        assertTrue(detect(trace().map{if(it.atMs==3500L) it.copy(gps=it.gps!!.copy(longitude=128.0)) else it}).isEmpty())
    }
    @Test fun consoleAcknowledgementGapDoesNotInventMissingNeutralEvidence() {
        for(sign in listOf(-1,1)) {
            val delayed=delayedTrace(sign)
            // Same observed console failure: 1.25s gap leaves <1.75s neutral evidence in the 2.5s window.
            assertTrue(detect(delayed).isEmpty())
        }
    }
    @Test fun observingDuringConsoleWaitPreservesBothCandidatesWithoutPenalty() {
        for(sign in listOf(-1,1)) {
            val source=trace(sign)
            val duringWait=(8..11).map { i ->
                val t=i*250L
                source[7].copy(atMs=t,signalAtMs=t,steeringAtMs=t,
                    gps=source[7].gps!!.copy(atMs=t,latitude=37+i*2.0/111195))
            }
            val complete=(delayedTrace(sign)+duringWait).sortedBy{it.atMs}
            val event=detect(complete).single()
            assertEquals(if(sign<0)TurnDirection.LEFT else TurnDirection.RIGHT,event.direction)
            assertEquals(ManeuverKind.LANE_CHANGE,event.kind)
            assertEquals(0.0,event.penalty)
        }
    }
    private fun delayedTrace(sign:Int)=trace(sign).map { o ->
        if(o.atMs<2000)o else o.copy(atMs=o.atMs+1000,signalAtMs=o.signalAtMs+1000,
            steeringAtMs=o.steeringAtMs+1000,gps=o.gps!!.copy(atMs=o.gps.atMs+1000,
                latitude=o.gps.latitude+8.0/111195))
    }
}
