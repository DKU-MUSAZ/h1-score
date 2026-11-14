package ai.musaz.h1score

import ai.musaz.h1score.core.CollectionProgress
import ai.musaz.h1score.core.SpotTour
import android.content.SharedPreferences
import java.time.LocalDate

internal class CollectionProgressStore(private val prefs: SharedPreferences) {
    private fun scoresKey(demo: Boolean) = if (demo) "collection-scores-demo" else "collection-scores-live"
    private fun visitsKey(spot: SpotTour) = "collection-visit-${spot.id}"
    private fun parseDay(value: String) = runCatching { LocalDate.parse(value) }.getOrNull()

    fun read(demo: Boolean): CollectionProgress {
        val scores = prefs.getStringSet(scoresKey(demo), emptySet()).orEmpty().mapNotNull { entry ->
            val day = parseDay(entry.substringBefore('='))
            val score = entry.substringAfter('=', "").toIntOrNull()
            if (day != null && score != null && score in 0..100) day to score else null
        }.toMap()
        val visits = if (demo) emptyMap() else SpotTour.entries.associateWith { spot ->
            prefs.getStringSet(visitsKey(spot), emptySet()).orEmpty().mapNotNull(::parseDay).toSet()
        }
        return CollectionProgress(scores, visits)
    }

    fun saveChallenge(demo: Boolean, progress: CollectionProgress) {
        prefs.edit().putStringSet(scoresKey(demo), progress.scoresByDay.map { "${it.key}=${it.value}" }.toSet()).apply()
    }

    fun saveVisit(spot: SpotTour, progress: CollectionProgress) {
        prefs.edit().putStringSet(visitsKey(spot), progress.visitsBySpot.getValue(spot).map(LocalDate::toString).toSet()).apply()
    }
}
