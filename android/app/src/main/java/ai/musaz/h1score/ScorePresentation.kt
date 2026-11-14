package ai.musaz.h1score

import ai.musaz.h1score.core.DrivingEventType
import ai.musaz.h1score.core.ScoreEngine
import ai.musaz.h1score.core.ScoreReport
import ai.musaz.h1score.core.ManeuverKind
import ai.musaz.h1score.core.TurnReason
import ai.musaz.h1score.core.TurnVerdict
import java.util.Locale

/** A saved score keeps the explanation of the algorithm that produced it. */
internal val ScoreReport.usesDistanceBonus: Boolean
    get() = scoringVersion in setOf(ScoreEngine.VERSION,"distance-bonus-longitudinal-v2-turn-v1")

internal val ScoreReport.recordedDecelerations: Int
    get() = if (usesDistanceBonus) decelerationEvents
        else events.count { it.type == DrivingEventType.RAPID_DECELERATION }

internal fun scorePoints(points: Double): String = String.format(Locale.KOREA, "%.2f", points)

internal fun scoreFormula(report: ScoreReport): String = if (report.usesDistanceBonus)
    "점수 기준 · 기본 60점 + 속도 가점 최대 25점 + 가감속 가점 최대 15점 − 회전 감점 최대 10점"
else "점수 기준 · 제한속도 준수 70% + 부드러운 가속 30%"

internal fun scorePendingMessage(report: ScoreReport): String = if (report.usesDistanceBonus)
    "거리 또는 수신 품질이 부족해 점수를 확정하지 못했어요. 유효 거리 500m·자료 확보율 90%·가감속 수신 빈도 8Hz 이상이 필요해요."
else "거리 또는 수신 데이터가 부족해 점수를 확정하지 못했어요. 기록된 구간만 참고해 주세요."

internal fun decelerationEventLabel(report: ScoreReport): String = if (report.usesDistanceBonus)
    "급감속 · 가점 제외"
else "급감속 · 감점 제외"

internal fun turnAssessmentLines(report: ScoreReport): List<String> = buildList {
    val turns=report.turns.filter{it.kind==ManeuverKind.TURN}
    add("좌·우회전 판정 ${turns.count{it.verdict!=TurnVerdict.UNVERIFIABLE}}건 · 판단 보류 ${turns.count{it.verdict==TurnVerdict.UNVERIFIABLE}}건")
    val lanes=report.turns.count{it.kind==ManeuverKind.LANE_CHANGE}
    if(lanes>0) add("차선변경 후보 ${lanes}건 · 도로 문맥 확인 전 관찰 기록이며 감점하지 않아요.")
    val labels=(report.turnDiagnostics.keys+turns.map{it.reason}).mapNotNull { when(it) {
        TurnReason.GPS_UNAVAILABLE -> "GPS 정확도·진행방향 부족"
        TurnReason.MOTION_UNAVAILABLE -> "주행 상태 확인 불가"
        TurnReason.STEERING_UNAVAILABLE -> "조향 정보 부족"
        TurnReason.SIGNAL_UNAVAILABLE -> "방향지시등 정보 부족"
        TurnReason.SIGNAL_HISTORY_INSUFFICIENT -> "회전 전 방향지시등 관측 부족"
        TurnReason.HAZARD_OR_UNKNOWN -> "비상등 켜짐 또는 확인 불가"
        TurnReason.INCOMPLETE,TurnReason.GEOMETRY_UNCERTAIN -> "동작 확인 불충분"
        else -> null
    } }.distinct()
    if(labels.isNotEmpty()) add("일부 구간 평가 보류 · ${labels.joinToString(" / ")}")
    if(turns.none{it.verdict!=TurnVerdict.UNVERIFIABLE}) add("판정 내역이 없다는 것이 방향지시등을 모두 올바르게 사용했다는 뜻은 아니에요.")
}
