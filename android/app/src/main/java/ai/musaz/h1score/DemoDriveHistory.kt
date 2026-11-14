package ai.musaz.h1score

import ai.musaz.h1score.core.ChallengeRound
import ai.musaz.h1score.core.ChallengeSession
import ai.musaz.h1score.core.DrivingEvent
import ai.musaz.h1score.core.DrivingEventType
import ai.musaz.h1score.core.Entry
import ai.musaz.h1score.core.Phase
import ai.musaz.h1score.core.ScoreEngine
import ai.musaz.h1score.core.ScoreReport
import ai.musaz.h1score.core.Settlement
import ai.musaz.h1score.core.TurnDirection
import ai.musaz.h1score.core.TurnEvent
import ai.musaz.h1score.core.TurnVerdict
import java.time.LocalDate
import kotlin.math.roundToInt

private const val DemoHistoryPrefix = "history-example:"

internal val CompletedDrive.isHistoryExample: Boolean
    get() = demo && session.id.startsWith(DemoHistoryPrefix)

/** Read-only examples. They never enter the drive store or the reward ledger. */
internal fun demoHistoryExamples(today: LocalDate = LocalDate.now(ChallengeRound.seoul)): List<CompletedDrive> {
    fun example(
        daysAgo: Long, hour: Int, name: String, overLimitMeters: Double, speedPoints: Double, smoothPoints: Double,
        accelerations: Int = 0, decelerations: Int = 0, missedTurnSignal: Boolean = false, badgeMileage: Int = 0,
    ): CompletedDrive {
        val arrival = today.minusDays(daysAgo).atTime(hour, 20).atZone(ChallengeRound.seoul)
        val turns = listOf(TurnEvent(310_000, 313_000, TurnDirection.LEFT,
            if (missedTurnSignal) TurnVerdict.UNUSED else TurnVerdict.MATCHED))
        val score = (ScoreEngine.START_POINTS + speedPoints + smoothPoints - turns.sumOf { it.penalty }).roundToInt()
        val events = buildList {
            if (overLimitMeters > 0) add(DrivingEvent(DrivingEventType.OVERSPEED, 125_000, 2_100.0, 77.0, 91.0, 80))
            repeat(accelerations) { index ->
                add(DrivingEvent(DrivingEventType.RAPID_ACCELERATION, 210_000L + index * 10_000,
                    3_400.0 + index * 150, 40.0, 64.0, 80))
            }
            repeat(decelerations) { index ->
                add(DrivingEvent(DrivingEventType.RAPID_DECELERATION, 360_000L + index * 10_000,
                    5_800.0 + index * 150, 63.0, 38.0, 80))
            }
        }
        val ranking = Settlement.rank(listOf(
            Entry("나", score), Entry("느긋한 고래", 94), Entry("민트 드라이버", 90),
            Entry("차분한 오후", 85), Entry("파란 구름", 80),
        ))
        return CompletedDrive(
            session = ChallengeSession("$DemoHistoryPrefix${arrival.toInstant()}", name, 14_000.0, Phase.FINISHED),
            report = ScoreReport(
                score, speedPoints * 4, smoothPoints / 15 * 100, 10_000.0, .98, true,
                accelerations, overLimitMeters, events, speedPoints = speedPoints, accelerationPoints = smoothPoints,
                brakingEvents = decelerations, turns = turns, longitudinalCoverage = .98,
                longitudinalReady = true, longitudinalSampleRateHz = 10.0, scoringVersion = ScoreEngine.VERSION,
            ),
            name = name, demo = true, ranking = ranking, earned = ranking.first { it.id == "나" }.miles,
            missedDeadline = false, completedAtEpochMs = arrival.toInstant().toEpochMilli(), badgeMileage = badgeMileage,
        )
    }
    return listOf(
        example(0, 9, "여의도한강공원", 1_133.0, 22.17, 13.30, badgeMileage = 15),
        example(1, 17, "단국대학교 죽전캠퍼스", 420.0, 23.95, 13.40, decelerations = 1),
        example(2, 8, "서울숲", 2_000.0, 20.0, 10.0, accelerations = 1, missedTurnSignal = true),
        example(3, 13, "현대 모터스튜디오 고양", 0.0, 25.0, 15.0),
        example(4, 14, "잠실종합운동장", 0.0, 0.0, 0.0).let { drive ->
            drive.copy(
                session = drive.session.copy(phase = Phase.DISQUALIFIED),
                report = drive.report.copy(score = null, valid = false, validDistanceM = 2_400.0,
                    turns = emptyList()),
                ranking = emptyList(), earned = 0, badgeMileage = 0,
                outcome = DriveOutcome.DISQUALIFIED,
                beltFailureReason = BeltFailureReason.DRIVER_UNBUCKLED,
                participationDay = today.minusDays(4).toString(),
                recognition = ResultRecognition.DISQUALIFIED,
            )
        },
        example(5, 11, "올림픽공원", 2_500.0, 18.75, 9.25, accelerations = 2, decelerations = 1),
        example(8, 16, "판교 테크노밸리", 900.0, 22.75, 11.00, missedTurnSignal = true),
        example(10, 10, "광교호수공원", 0.0, 25.0, 12.0, decelerations = 1),
    )
}
