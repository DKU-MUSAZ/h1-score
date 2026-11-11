package ai.musaz.h1score.core

import org.junit.Test
import kotlin.test.*

class BonusScoreTest {
    private fun trip(meters: Int, speed: Double=10.0, limit: Int?=50) =
        (0..(meters / speed * 10).toInt()).map { Sample(it * 100L, speed, limit) }
    @Test fun emptyInputDoesNotFinalizeInitialSixty() {
        val report = ScoreEngine.evaluate(emptyList())
        assertNull(report.score); assertEquals(60.0, report.totalPoints)
    }
    @Test fun eachPerfectKmEarnsFourWithoutShortTripScaling() {
        for (km in 1..10) {
            val r=ScoreEngine.evaluate(trip(km*1000))
            assertEquals(60+4*km,r.score)
            assertEquals(2.5*km,r.speedPoints,1e-8)
            assertEquals(1.5*km,r.accelerationPoints,1e-8)
            assertEquals(km,r.kilometers.size)
            r.kilometers.forEach {assertEquals(4.0,it.points,1e-8)}
        }
    }
    @Test fun extraDistanceCannotEarnBeyondForty() {
        val r=ScoreEngine.evaluate(trip(15000))
        assertEquals(100,r.score);assertEquals(10000.0,r.validDistanceM,1e-8)
        assertEquals(10,r.kilometers.size);assertEquals(40.0,r.earnedPoints,1e-8)
    }
    @Test fun speedingDoesNotEarnSmoothnessBonus() {
        val r=ScoreEngine.evaluate(trip(10000,10.0,30))
        assertEquals(60,r.score);assertEquals(0.0,r.earnedPoints)
    }
    @Test fun partialKmIsProportional() {
        val r=ScoreEngine.evaluate(trip(1500))
        assertEquals(66,r.score);assertEquals(2.0,r.kilometers.last().points,1e-8)
    }
    @Test fun intervalSpanningKmBoundaryIsSplit() {
        val r=ScoreEngine.evaluate((0..1100).map { Sample(it * 100L, 9.5, 50) })
        assertEquals(2,r.kilometers.size)
        assertEquals(4.0,r.kilometers.first().points,1e-8)
        assertEquals(45.0/1000*4,r.kilometers.last().points,1e-8)
    }
    @Test fun missingLimitCannotBeRecoveredByDrivingExtraDistance() {
        val s=(0..11000).map { Sample(it * 100L, 10.0, if (it in 1001..1500) null else 50) }
        val r=ScoreEngine.evaluate(s)
        assertEquals(95.0,r.coverage*100,1e-8)
        assertEquals(38.0,r.earnedPoints,1e-8)
        assertEquals(98,r.score)
    }
    @Test fun missingDataDoesNotEarnPointsOrBecomeSixty() {
        val r=ScoreEngine.evaluate(trip(10000,10.0,null))
        assertNull(r.score);assertFalse(r.valid);assertEquals(0.0,r.earnedPoints)
    }
    @Test fun stoppedTimeCannotEarnPoints() {
        val r=ScoreEngine.evaluate((0..10000).map { Sample(it * 100L, 0.0, 50) })
        assertNull(r.score);assertEquals(0.0,r.earnedPoints)
    }
    @Test fun limitCrossingAllocatesOnlyCompliantDistance() {
        val r=ScoreEngine.evaluate((0..10).map { Sample(it * 100L, 10.0 + it, 54) })
        // Below limit until 15m/s: (10+15)/2 * 0.5 = 6.25 metres.
        assertEquals(6.25/1000*2.5,r.speedPoints,1e-8)
        assertEquals(0.0,r.accelerationPoints)
    }
    @Test fun removedBeltIsPermanentDisqualification() {
        val session=ChallengeSession("belt","poi",14000.0).accept(true).update(10000.0,false,false)
        val disqualified=session.enforceBelt(false)
        assertEquals(Phase.DISQUALIFIED,disqualified.phase)
        assertEquals(Phase.DISQUALIFIED,disqualified.enforceBelt(true).update(0.0,true,true).phase)
    }
    @Test fun unknownBeltIsNotAnUnbuckledReading() {
        val session=ChallengeSession("belt","poi",14000.0).accept(true)
        assertEquals(Phase.RESERVED,session.enforceBelt(null).phase)
        assertEquals(Phase.DISQUALIFIED,session.enforceBelt(false).phase)
    }
}
