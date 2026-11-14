package ai.musaz.h1score.core

import java.time.LocalDate
import java.time.DayOfWeek
import java.time.temporal.TemporalAdjusters

enum class RankTier(val title: String) {
    BRONZE("브론즈"),
    SILVER("실버"),
    GOLD("골드"),
    PLATINUM("플래티넘")
}

enum class RankBadgeGoal(val id: String, val tier: RankTier, val topPercent: Int, val targetDays: Int,
    val weekly: Boolean = false) {
    FIRST_RANK("rank-90", RankTier.BRONZE, 90, 5),
    CALM_START("score-80-5", RankTier.BRONZE, 80, 5),
    RANK_ADAPTED("rank-80", RankTier.BRONZE, 80, 10),
    MIDFIELD("rank-50", RankTier.SILVER, 50, 5),
    UPPER_RANK("rank-30", RankTier.SILVER, 30, 5),
    STEADY_FLOW("score-80-10", RankTier.SILVER, 50, 10),
    TOP_REGULAR("rank-10-3", RankTier.GOLD, 10, 3),
    GOOD_HABIT("score-90-5", RankTier.GOLD, 30, 10),
    TRUSTED_DRIVER("score-90-10", RankTier.GOLD, 10, 5),
    ACE("rank-10-10", RankTier.PLATINUM, 10, 10),
    PRECISE_DRIVER("score-95-5", RankTier.PLATINUM, 10, 15),
    CONSISTENT_FLOW("routine-score", RankTier.PLATINUM, 10, 3, weekly = true)
}

data class RankBadgeProgress(
    private val daysByGoal: Map<RankBadgeGoal, Int> = emptyMap(),
    val countedDays: Set<LocalDate> = emptySet(),
    val weeksByGoal: Map<RankBadgeGoal, Set<LocalDate>> = emptyMap()
) {
    // Progress units are distinct days, or distinct Monday-based weeks for weekly goals.
    fun days(goal: RankBadgeGoal): Int =
        (if (goal.weekly) weeksByGoal[goal].orEmpty().size else daysByGoal[goal] ?: 0)
            .coerceIn(0, goal.targetDays)

    fun completed(tier: RankTier): Boolean =
        RankBadgeGoal.entries.filter { it.tier == tier }.all { days(it) >= it.targetDays }

    val activeTier: RankTier?
        get() = RankTier.entries.firstOrNull { !completed(it) }

    fun isUnlocked(tier: RankTier): Boolean = activeTier?.let { tier <= it } ?: true

    fun record(day: LocalDate, rank: Int, participants: Int, demo: Boolean = false): RankBadgeProgress {
        val minimumGroupSize = if (demo) 5 else 10
        if (participants < minimumGroupSize || rank !in 1..participants || day in countedDays) return this
        val tier = activeTier ?: return this
        val week = day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val eligible = RankBadgeGoal.entries.filter { goal ->
            val cutoff = if (demo) (goal.topPercent.toLong() * participants + 99) / 100
                else goal.topPercent.toLong() * participants / 100
            goal.tier == tier && days(goal) < goal.targetDays &&
                rank <= cutoff && (!goal.weekly || week !in weeksByGoal[goal].orEmpty())
        }
        if (eligible.isEmpty()) return this
        return copy(
            daysByGoal = daysByGoal + eligible.associateWith { days(it) + 1 },
            countedDays = countedDays + day,
            weeksByGoal = weeksByGoal + eligible.filter { it.weekly }
                .associateWith { weeksByGoal[it].orEmpty() + week }
        )
    }
}
