package ai.musaz.h1score

import ai.musaz.h1score.core.ChallengeRound
import ai.musaz.h1score.core.Phase
import java.time.Instant

/** Arrival determines eligibility, not the later time the vehicle parks or the report is saved. */
internal fun deadlineOutcome(drive: CompletedDrive, arrivedAt: Instant?, round: ChallengeRound?): CompletedDrive? {
    if (arrivedAt == null || round == null || arrivedAt < round.closesAt) return null
    return drive.copy(session=drive.session.copy(phase=Phase.DISQUALIFIED),
        report=drive.report.copy(score=null,valid=false), outcome=DriveOutcome.DISQUALIFIED,
        recognition=ResultRecognition.DEADLINE, missedDeadline=true, beltFailureReason=null,
        ranking=emptyList(),earned=0,badgeMileage=0,awardedBadges=emptyList())
}
