package ai.musaz.h1score

import ai.musaz.h1score.core.*
import org.junit.Test
import org.junit.Assert.*

class TurnHistoryTest {
    private fun report()=ScoreEngine.evaluate(emptyList()).copy(score=98,valid=true)
    @Test fun lateSignalIsAnIssueAndFeedbackExplainsIt() {
        val feedback=historyFeedback(report().copy(turns=listOf(TurnEvent(1,2,TurnDirection.LEFT,TurnVerdict.LATE))))
        assertEquals("1건",feedback.metrics.last().value)
        assertTrue(feedback.improvements.any{it.contains("늦게")})
    }
    @Test fun previousBonusVersionKeepsItsFormula() {
        assertTrue(report().copy(scoringVersion="distance-bonus-longitudinal-v2-turn-v1").usesDistanceBonus)
    }
    @Test fun interruptedTurnReasonIsVisibleEvenWithoutFollowingObservation() {
        val r=report().copy(turns=listOf(TurnEvent(1,2,TurnDirection.LEFT,TurnVerdict.UNVERIFIABLE,
            reason=TurnReason.INCOMPLETE)))
        assertTrue(turnAssessmentLines(r).any{it.contains("동작 확인 불충분")})
    }
    @Test fun unassessedAndLaneCandidatesAreNotPresentedAsSafeDriving() {
        val r=report().copy(turns=listOf(TurnEvent(1,2,TurnDirection.LEFT,TurnVerdict.UNVERIFIABLE,
            ManeuverKind.LANE_CHANGE,TurnReason.LANE_OBSERVATION_ONLY)),
            turnDiagnostics=mapOf(TurnReason.GPS_UNAVAILABLE to 10))
        val lines=turnAssessmentLines(r)
        assertTrue(lines.any{it.contains("차선변경 후보 1건")})
        assertTrue(lines.any{it.contains("GPS")})
        assertTrue(lines.any{it.contains("뜻은 아니에요")})
        assertEquals(0.0,r.turnPenalty,0.0)
    }
}
