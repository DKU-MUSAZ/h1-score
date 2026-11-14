package ai.musaz.h1score

import ai.musaz.h1score.core.CollectionProgress
import ai.musaz.h1score.core.SpotTour
import android.content.SharedPreferences
import java.time.LocalDate

internal class CollectionProgressStore(private val prefs: SharedPreferences) {
    private fun scoresKey(demo: Boolean) = if (demo) "collection-scores-demo" else "collection-scores-live"
    private fun visitsKey(demo: Boolean, spot: SpotTour) =
        if (demo) "collection-visit-demo-${spot.id}" else "collection-visit-${spot.id}"
    private fun parseDay(value: String) = runCatching { LocalDate.parse(value) }.getOrNull()

    fun read(demo: Boolean): CollectionProgress {
        val scores = prefs.getStringSet(scoresKey(demo), emptySet()).orEmpty().mapNotNull { entry ->
            val day = parseDay(entry.substringBefore('='))
            val score = entry.substringAfter('=', "").toIntOrNull()
            if (day != null && score != null && score in 0..100) day to score else null
        }.toMap()
        val visits = SpotTour.entries.associateWith { spot ->
            prefs.getStringSet(visitsKey(demo, spot), emptySet()).orEmpty().mapNotNull(::parseDay).toSet()
        }
        return CollectionProgress(scores, visits)
    }

    fun writeChallenge(editor: SharedPreferences.Editor, demo: Boolean, progress: CollectionProgress) {
        editor.putStringSet(scoresKey(demo), progress.scoresByDay.map { "${it.key}=${it.value}" }.toSet())
    }

    fun writeVisit(editor: SharedPreferences.Editor, demo: Boolean, spot: SpotTour, progress: CollectionProgress) {
        editor.putStringSet(visitsKey(demo, spot), progress.visitsBySpot.getValue(spot).map(LocalDate::toString).toSet())
    }
}
