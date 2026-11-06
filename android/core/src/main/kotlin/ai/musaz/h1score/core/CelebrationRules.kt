package ai.musaz.h1score.core

import kotlin.math.sin

/** Display bands for the prototype, not a validated safety classification. */
enum class DriveMood {
    NEUTRAL, TEARY, ENCOURAGING, CELEBRATING;
    companion object {
        fun forScore(score: Int?) = when {
            score == null -> NEUTRAL
            score >= 90 -> CELEBRATING
            score >= 75 -> ENCOURAGING
            else -> TEARY
        }
    }
}

data class BadgeAward(val id: String, val title: String, val description: String)
object BadgeRules {
    fun newAwards(valid: Boolean, destination: String, score: Int?, rank: Int?, owned: Set<String>): List<BadgeAward> {
        if(!valid || score == null) return emptyList()
        return buildList {
            add(BadgeAward("first-drive", "첫 무사 도착", "첫 번째 유효 주행 기록을 남겼어요."))
            add(BadgeAward("visit:$destination", "첫 발자국", "$destination 첫 방문을 기록했어요."))
            if(score >= 85 && rank != null && rank in 1..3)
                add(BadgeAward("top-three", "무사 TOP 3", "85점 이상으로 그룹 상위 3위에 올랐어요."))
        }.filter { it.id !in owned }
    }
}

/** Accelerated fixtures only; every visible score still goes through ScoreEngine. */
enum class DemoDrive(val label: String) {
    GOOD("축하하는 무사즈"), STEADY("응원하는 무사즈"), RETRY("울먹이는 무사즈");
    fun sample(index: Int): Sample {
        val speed=16.6 + sin(index*.03)*1.5
        // Repeat the same mixture on both short and full-length demo routes.
        val phase=index % 200
        val lowLimit=when(this) { GOOD -> phase in 60..80; STEADY -> phase in 0..59; RETRY -> true }
        return Sample(index*1000L,speed,if(lowLimit) 50 else 80)
    }
}
