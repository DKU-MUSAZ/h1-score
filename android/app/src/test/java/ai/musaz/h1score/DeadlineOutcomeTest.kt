package ai.musaz.h1score

import ai.musaz.h1score.core.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class DeadlineOutcomeTest {
    private val close=Instant.parse("2026-11-14T12:00:00Z")
    private val round=ChallengeRound("대회",close.minusSeconds(3600),close)
    private val drive=CompletedDrive(ChallengeSession("test","spot",14000.0,Phase.FINISHED),
        ScoreReport(95,90.0,100.0,10000.0,1.0,true,0,200.0),"목적지",true,
        emptyList(),0,false,participationDay="2026-11-14")

    @Test fun deadlineAndLaterArrivalAreDisqualifiedWithNoScoreOrRewards() {
        listOf(close,close.plusMillis(1)).forEach { arrival ->
            val result=deadlineOutcome(drive,arrival,round)!!
            assertEquals(DriveOutcome.DISQUALIFIED,result.outcome)
            assertEquals(ResultRecognition.DEADLINE,result.recognition)
            assertEquals("실격",historyResultLabel(result))
            assertTrue(result.beltOutcomeReason!!.contains("대회 종료"))
            assertNull(result.report.score)
            assertFalse(result.report.valid)
            assertEquals(200.0,result.report.overLimitDistanceM,0.0)
            assertEquals(0,result.earned+result.badgeMileage)
            assertTrue(result.awardedBadges.isEmpty())
            assertTrue(result.ranking.isEmpty())
        }
    }

    @Test fun arrivalBeforeClosingIsNotDisqualifiedWhenParkingOccursLater() {
        assertNull(deadlineOutcome(drive.copy(completedAtEpochMs=close.plusSeconds(180).toEpochMilli()),
            close.minusMillis(1),round))
        assertNull(deadlineOutcome(drive,null,round))
        assertNull(deadlineOutcome(drive,close,null))
        assertEquals(95,drive.report.score)
    }
}
