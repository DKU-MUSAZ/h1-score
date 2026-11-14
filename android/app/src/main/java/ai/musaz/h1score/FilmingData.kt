package ai.musaz.h1score

import android.content.SharedPreferences
import ai.musaz.h1score.core.*
import java.time.LocalDate

internal const val FIXED_FILMING_PROFILE = "h1-filming-fixed-v1"
internal val appPreferenceName: String
    get() = if (BuildConfig.DEBUG && BuildConfig.FILMING_BUILD) FIXED_FILMING_PROFILE else "h1-demo-v1"

/** Fixed approach fixture: gentle stopping creates no artificial harsh-driving event. */
internal fun filmingApproachSamples(endMs: Long): List<Sample> = (0..9050).map { index ->
    val speed = if (index <= 8950) 10.0 else (9050 - index) / 10.0
    Sample(endMs - 905_100L + index * 100L, speed, 80)
}

/** Fixed competitors for the filming build; the driver's measured score is never changed. */
internal fun filmingRanking(score: Int): List<RankedEntry> = Settlement.rank(listOf(
    Entry("나", score), Entry("느긋한 고래", 92), Entry("민트 드라이버", 89),
    Entry("차분한 오후", 86), Entry("파란 구름", 82),
))

/** Add comparison fixtures without replaying settlement or changing the wallet. */
internal fun enrichFilmingRankings(prefs: SharedPreferences) {
    val store = CompletedDriveStore(prefs)
    val latest = store.read()
    fun enriched(drive: CompletedDrive): CompletedDrive {
        val rank = if (drive.outcome == DriveOutcome.COMPLETED && drive.recognition == ResultRecognition.ACCEPTED &&
            !drive.missedDeadline && drive.report.valid && drive.report.score != null && drive.ranking.isEmpty())
            filmingRanking(requireNotNull(drive.report.score)) else drive.ranking
        return drive.copy(name = drive.name.removePrefix("[시연 기록] "), ranking = rank)
    }
    val changed = store.readAll().map { it to enriched(it) }.filter { it.first != it.second }
    changed.sortedBy { it.first.completedAtEpochMs }.forEach { store.save(it.second) }
    // Saving historical fixtures must not change which result a notification opens.
    if (changed.isNotEmpty() && latest != null) store.save(enriched(latest))
}

/** Versioned, immutable baseline. Reopening never overwrites a filmed result. */
internal fun seedFilmingHistory(prefs: SharedPreferences) {
    if (prefs.getBoolean("filming-seed-v1", false)) {
        enrichFilmingRankings(prefs)
        return
    }
    check(CompletedDriveStore(prefs).readAll().isEmpty()) { "Refusing to replace existing filming records" }
    val names = listOf("여의도 한강공원", "현대 모터스튜디오 고양", "단국대학교 죽전캠퍼스", "올림픽공원")
    val drives = listOf(DemoDrive.GOOD, DemoDrive.STEADY, DemoDrive.RETRY, DemoDrive.GOOD)
    var progress = CollectionProgress()
    var owned = emptySet<String>()
    var distance = 0.0
    names.forEachIndexed { index, _ ->
      repeat(3) { trip ->
        val name = names[(index + trip) % names.size]
        val day = LocalDate.of(2026, 11, 9 + index)
        val report = ScoreEngine.evaluate((0..6500).map { drives[(index + trip) % drives.size].sample(it) })
        check(report.valid && report.score != null)
        val destination = "fixed-venue-${(index + trip) % names.size + 1}"
        val awards = BadgeRules.newAwards(true, destination, report.score, null, owned)
        owned = owned + awards.map { it.id }
        progress = progress.recordChallenge(day, requireNotNull(report.score))
        distance += report.validDistanceM
        CompletedDriveStore(prefs).save(CompletedDrive(
            ChallengeSession("fixed-history-${index + 1}-$trip", destination, 22_000.0, Phase.FINISHED),
            report, name, false, emptyList(), 0, false,
            completedAtEpochMs = day.atTime(8 + trip * 4, 0).atZone(ChallengeRound.seoul).toInstant().toEpochMilli(),
            participationDay = day.toString(), recognition = ResultRecognition.ACCEPTED, awardedBadges = awards,
        ))
    }
    }
    listOf(6, 8, 13).forEach { dayOfMonth ->
    val failedDay = LocalDate.of(2026, 11, dayOfMonth)
    CompletedDriveStore(prefs).save(CompletedDrive(
        ChallengeSession("fixed-history-disqualified-$dayOfMonth", "fixed-failed-venue", 22_000.0, Phase.DISQUALIFIED),
        ScoreEngine.evaluate(filmingApproachSamples(1_000_000L)).copy(score=null, valid=false),
        "서울숲", false, emptyList(), 0, false,
        completedAtEpochMs=failedDay.atTime(12,0).atZone(ChallengeRound.seoul).toInstant().toEpochMilli(),
        participationDay=failedDay.toString(), outcome=DriveOutcome.DISQUALIFIED,
        beltFailureReason=BeltFailureReason.DRIVER_UNBUCKLED, recognition=ResultRecognition.DISQUALIFIED,
    ))
    }
    val editor = prefs.edit().putBoolean("filming-seed-v1", true)
        .putString("filming-dataset-version", "fixed-v2-15-records-2026-11-14")
        .putBoolean("first-run-complete-v1", true).putBoolean("voice", true)
        .putString("profile-nickname", "시연 드라이버")
        .putStringSet("badges-live", owned).putInt("balance-live", 0)
        .putLong("recorded-distance-live", distance.toLong())
        .putString("recent-destinations", "[]")
    CollectionProgressStore(prefs).writeChallenge(editor, false, progress)
    check(editor.commit())
    enrichFilmingRankings(prefs)
}
