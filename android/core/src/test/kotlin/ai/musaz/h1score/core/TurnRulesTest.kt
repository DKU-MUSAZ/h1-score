package ai.musaz.h1score.core

import org.junit.Test
import kotlin.test.*

class TurnRulesTest {
    private fun route(direction: TurnDirection=TurnDirection.LEFT, signal: SignalState?=SignalState.OFF,
                      startBearing: Double=0.0): List<TurnObservation> = (0..28).map { i ->
        val at=i*250L
        val sign=if(direction==TurnDirection.LEFT)-1 else 1
        val progress=((i-12)/12.0).coerceIn(0.0,1.0)
        TurnObservation(at,GpsFix(at,37.0+i*.00001,127.0+sign*progress*.0003,3.0,
            (startBearing+sign*90*progress+360)%360,5.0),8.0,true,
            if(i in 12..23)sign*8.0 else 0.0,signal,direction,20.0,at)
    }
    private fun detect(input: List<TurnObservation>): List<TurnEvent> = TurnDetector().also { d->input.forEach(d::accept) }.events
    @Test fun leftAndRightUnusedAreOnePointEach() {
        TurnDirection.entries.forEach { dir -> val event=detect(route(dir)).single()
            assertEquals(dir,event.direction);assertEquals(TurnVerdict.UNUSED,event.verdict);assertEquals(1.0,event.penalty) }
    }
    @Test fun oppositeIsTwoPoints() { assertEquals(2.0,detect(route(signal=SignalState.RIGHT)).single().penalty) }
    @Test fun correctSignalBeforeTurnMayAutoCancelDuringTurn() {
        val input=route().map { if(it.atMs in 1500..2750)it.copy(signal=SignalState.LEFT) else it }
        assertEquals(TurnVerdict.MATCHED,detect(input).single().verdict)
    }
    @Test fun matchingRightSignalIsAccepted() { assertEquals(TurnVerdict.MATCHED,detect(route(TurnDirection.RIGHT,SignalState.RIGHT)).single().verdict) }
    @Test fun missingSignalHoldsJudgment() { assertTrue(detect(route(signal=null)).isEmpty()) }
    @Test fun signalGapDuringTurnPreventsPenalty() {
        val events=detect(route().map { if(it.atMs==4000L)it.copy(signal=null) else it })
        assertTrue(events.isNotEmpty());assertTrue(events.all { it.penalty==0.0 })
    }
    @Test fun badAccuracyAndMissingBearingPreventPenalty() {
        listOf(30.0,Double.NaN).forEach { accuracy ->
            assertTrue(detect(route().map { it.copy(gps=it.gps!!.copy(accuracyM=accuracy)) }).isEmpty())
        }
        assertTrue(detect(route().map { it.copy(gps=it.gps!!.copy(bearingAccuracyDeg=90.0)) }).isEmpty())
    }
    @Test fun curvedRoadWithoutTurnInstructionDoesNotDeduct() {
        assertTrue(detect(route().map { it.copy(instruction=null) }).isEmpty())
    }
    @Test fun staleOrFarInstructionDoesNotDeduct() {
        assertTrue(detect(route().map { it.copy(instructionAtMs=it.atMs-4000) }).isEmpty())
        assertTrue(detect(route().map { it.copy(instructionDistanceM=100.0) }).isEmpty())
    }
    @Test fun steeringWithoutGpsTurnIsNotEnough() {
        assertEquals(TurnVerdict.UNVERIFIABLE,detect(route().map { it.copy(gps=it.gps!!.copy(bearingDeg=0.0)) }).single().verdict)
    }
    @Test fun gpsAndSteeringDisagreementDoesNotDeduct() {
        assertEquals(TurnVerdict.UNVERIFIABLE,detect(route().map { it.copy(gps=it.gps!!.copy(bearingDeg=(360-it.gps.bearingDeg)%360)) }).single().verdict)
    }
    @Test fun parkingReversingAndLowSpeedAreExcluded() {
        assertTrue(detect(route().map { it.copy(forward=false) }).isEmpty())
        assertTrue(detect(route().map { it.copy(speedMps=.5) }).isEmpty())
    }
    @Test fun headingWrapIsHandled() { assertEquals(TurnVerdict.UNUSED,detect(route(TurnDirection.RIGHT,startBearing=350.0)).single().verdict) }
    @Test fun staleGpsAndTelemetryGapsDoNotDeduct() {
        assertTrue(detect(route().map { it.copy(gps=it.gps!!.copy(atMs=it.atMs-2000)) }).isEmpty())
        assertTrue(detect(route().filterNot { it.atMs in 3750..5500 }).all { it.penalty==0.0 })
    }
    @Test fun repeatedSameObservationCannotCreateMultipleEvents() {
        val d=TurnDetector();route().forEach { d.accept(it);d.accept(it) }
        assertEquals(1,d.events.size)
        d.reset();assertTrue(d.events.isEmpty())
    }
    @Test fun unfinishedTurnAndInterruptedInputCannotDeduct() {
        val d=TurnDetector();route().take(20).forEach(d::accept)
        assertTrue(d.events.isEmpty());d.interrupt();assertEquals(0.0,d.events.single().penalty)
    }
    @Test fun hardBrakingReducesOnlySmoothnessBonus() {
        val smooth=ScoreEngine.evaluate((0..1000).map { Sample(it*1000L,10.0,60) })
        val harsh=ScoreEngine.evaluate((0..1000).map { Sample(it*1000L,if(it%4<2)10.0 else 6.0,60) })
        assertTrue(harsh.brakingEvents>0);assertTrue(harsh.accelerationEvents>0)
        assertEquals(100.0,harsh.compliance);assertTrue(harsh.smoothness<smooth.smoothness)
    }
    @Test fun gentleBrakingAndThresholdBoundaryStillEarnBonus() {
        val r=ScoreEngine.evaluate(listOf(Sample(0,10.0,60),Sample(1000,7.5,60)))
        assertEquals(100.0,r.smoothness);assertEquals(0,r.brakingEvents)
    }
    @Test fun turnDeductionsAreCappedAndOnlyScoredDistanceCounts() {
        val samples=(0..1100).map { Sample(it*1000L,10.0,50) }
        val events=(1..20).map { TurnEvent(it*10000L,it*10000L+7000,TurnDirection.LEFT,TurnVerdict.MISMATCHED) }
        val report=ScoreEngine.evaluate(samples,events+TurnEvent(1001000,1008000,TurnDirection.RIGHT,TurnVerdict.UNUSED))
        assertEquals(10.0,report.turnPenalty);assertEquals(90,report.score)
        assertEquals(20,report.turns.size);assertEquals(90.0,report.totalPoints,1e-8)
    }
    @Test fun penaltyCanLowerScoreBelowSixty() {
        val r=ScoreEngine.evaluate((0..1000).map{Sample(it*1000L,10.0,30)}, listOf(TurnEvent(100,7000,TurnDirection.LEFT,TurnVerdict.MISMATCHED)))
        assertEquals(58,r.score)
    }
}
