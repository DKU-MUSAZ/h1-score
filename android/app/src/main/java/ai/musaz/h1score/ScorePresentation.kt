package ai.musaz.h1score

import ai.musaz.h1score.core.DrivingEventType
import ai.musaz.h1score.core.ScoreEngine
import ai.musaz.h1score.core.ScoreReport
import java.util.Locale

/** A saved score keeps the explanation of the algorithm that produced it. */
internal val ScoreReport.usesDistanceBonus: Boolean
    get() = scoringVersion == ScoreEngine.VERSION

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
