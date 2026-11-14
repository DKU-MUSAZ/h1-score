package ai.musaz.h1score

import android.app.Application
import android.content.SharedPreferences
import android.os.SystemClock
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ai.musaz.h1score.core.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToLong
import org.json.JSONArray

data class Destination(val name: String, val area: String, val distanceKm: Double, val minutes: Int, val demoPeers: Int = 0, val point: GeoPoint? = null, val venueId: String = name)
val demoDestinations = listOf(
    Destination("현대 모터스튜디오 고양", "경기 고양 · 킨텍스 접근 구간", 12.4, 23, 18, GeoPoint(37.6661, 126.8224), "goyang-motorstudio"),
    Destination("단국대학교 죽전캠퍼스", "경기 용인 · 죽전캠퍼스", 13.6, 26, 10, GeoPoint(37.3217, 127.1268), "dankook-jukjeon"),
    Destination("여의도한강공원", "서울 영등포 · 한강 챌린지", 14.0, 28, 24, hangangVenue, "yeouido-hangang"),
    Destination("서울숲", "서울 성동 · 서울숲", 11.2, 25, 9, GeoPoint(37.5445, 127.0374), "seoul-forest"),
    Destination("올림픽공원", "서울 송파 · 평화의 광장", 16.3, 32, 6, GeoPoint(37.5207, 127.1155), "olympic-park"),
    Destination("우리 동네 공원", "가까운 이동 · 개인 기록", 3.2, 9),
)
internal val challengeSpots get() = demoDestinations.filter { it.point != null }
internal val hangangChallenge = demoDestinations.first { it.name == "여의도한강공원" }
internal data class DestinationShortcut(val id: String, val label: String, val place: SavedPlace? = null, val legacyQuery: String = "") {
    val searchQuery: String get() = place?.name ?: legacyQuery
}
private const val HomeShortcutKey = "destination-shortcut-home"
private const val WorkShortcutKey = "destination-shortcut-work"
enum class Screen { HOME, JOURNEY, RESULT, WALLET, BADGES, HISTORY, CONNECTION, PROFILE }

class ChallengeViewModel @JvmOverloads constructor(app: Application,
    preferenceName: String = "h1-demo-v1", private val clock: () -> Instant = { Instant.now() },
): AndroidViewModel(app) {
    val connection = PleosConnection(app)
    var live by mutableStateOf(LiveVehicle()); private set
    var demo by mutableStateOf(false); private set
    var consoleTest by mutableStateOf(false); private set
    var consoleLimitKph by mutableIntStateOf(80); private set
    var consoleSessionLimitKph by mutableIntStateOf(80); private set
    var screen by mutableStateOf(Screen.HOME); private set
    internal val homeGreeting = listOf(
        "오늘도 무사즈와 달려볼까요?",
        "오늘도 무사히, 함께 출발해요."
    ).random()
    var selected by mutableStateOf(demoDestinations[0]); private set
    var session by mutableStateOf<ChallengeSession?>(null); private set
    var remainingM by mutableDoubleStateOf(12_400.0); private set
    var demoMoving by mutableStateOf(false); private set
    var demoRunning by mutableStateOf(false); private set
    var progressReport by mutableStateOf(ScoreEngine.evaluate(emptyList())); private set
    var report by mutableStateOf<ScoreReport?>(null); private set
    var ranking by mutableStateOf<List<RankedEntry>>(emptyList()); private set
    var earned by mutableIntStateOf(0); private set
    internal var badgeMileageEarned by mutableIntStateOf(0); private set
    var notice by mutableStateOf<String?>(null)
    var completed by mutableIntStateOf(0); private set
    private val prefs = app.getSharedPreferences(preferenceName,0)
    private val completedDriveStore = CompletedDriveStore(prefs)
    private val participationStore = DailyParticipationStore(prefs)
    internal var completedDrives by mutableStateOf(completedDriveStore.readAll()); private set
    private val historyExamples by lazy { demoHistoryExamples() }
    internal val historyDrives get(): List<CompletedDrive> {
        val records = completedDrives.filter { it.demo == demo }
        val days = records.mapNotNull(::historyRecordDay).toSet()
        return if (demo) records + historyExamples.filter { historyRecordDay(it) !in days } else records
    }
    internal var selectedHistoryRecord by mutableStateOf<CompletedDrive?>(null); private set
    internal var recentDestinations by mutableStateOf(readRecentDestinations())
        private set
    internal var destinationShortcuts by mutableStateOf(readShortcuts())
        private set
    private fun readShortcuts(): List<DestinationShortcut> = runCatching {
        val json = prefs.getString("destination-shortcuts-v2", null)
        if (json == null) listOf(
            DestinationShortcut("home", "집", legacyQuery=prefs.getString(HomeShortcutKey, "").orEmpty()),
            DestinationShortcut("work", "회사", legacyQuery=prefs.getString(WorkShortcutKey, "").orEmpty()))
        else JSONArray(json).let { rows -> List(rows.length()) { index ->
            val row=rows.getJSONObject(index)
            DestinationShortcut(row.getString("id"), row.getString("label"),
                SavedPlace.from(row.optString("name"), GeoPoint(row.optDouble("lat"), row.optDouble("lon"))),
                row.optString("query"))
        } }
    }.getOrElse { listOf(DestinationShortcut("home", "집"), DestinationShortcut("work", "회사")) }
    private fun writeShortcuts(items: List<DestinationShortcut>) {
        val rows=JSONArray()
        items.forEach { item -> rows.put(org.json.JSONObject().apply {
            put("id",item.id); put("label",item.label); put("query",item.legacyQuery)
            item.place?.let { put("name",it.name); put("lat",it.point.latitude); put("lon",it.point.longitude) }
        }) }
        prefs.edit().putString("destination-shortcuts-v2", rows.toString()).apply()
        destinationShortcuts=items
    }
    internal fun addShortcut(label: String): String? {
        val clean=shortcutLabel(label) ?: return null
        if (!canChooseDestination || destinationShortcuts.size >= 10 || destinationShortcuts.any { it.label == clean }) return null
        val item=DestinationShortcut(UUID.randomUUID().toString(), clean)
        writeShortcuts(destinationShortcuts + item)
        return item.id
    }
    internal fun removeShortcut(id: String) {
        if (!canChooseDestination) return
        writeShortcuts(destinationShortcuts.filterNot { it.id==id })
    }
    internal val currentNavigationPlace get() = if (!live.routeCancelled && SystemClock.elapsedRealtime()-live.destinationAt in 0..10_000)
        SavedPlace.from(live.destination.orEmpty(),live.destinationPoint) else null
    internal fun saveShortcut(id: String, label: String, query: String): Boolean {
        val cleanLabel = shortcutLabel(label) ?: return false
        val cleanQuery = query.trim()
        if (!canChooseDestination || cleanQuery.isBlank() ||
            destinationShortcuts.none { it.id == id } ||
            destinationShortcuts.any { it.id != id && it.label == cleanLabel }
        ) return false

        writeShortcuts(destinationShortcuts.map { item ->
            if (item.id == id) item.copy(label = cleanLabel, place = null, legacyQuery = cleanQuery) else item
        })
        return true
    }
    internal var recordedDistanceMeters by mutableLongStateOf(prefs.getLong(distanceKey(false), 0))
        private set

    private fun distanceKey(demo: Boolean) = if (demo) "recorded-distance-demo" else "recorded-distance-live"

    private fun readRecentDestinations(): List<String> = runCatching {
        val saved = JSONArray(prefs.getString("recent-destinations", "[]"))
        List(saved.length()) { saved.optString(it) }.filter(String::isNotBlank).take(3)
    }.getOrDefault(emptyList())

    internal fun rememberDestination(keyword: String) {
        val name = keyword.trim()
        if (!parked || name.isBlank()) return
        recentDestinations = (listOf(name) + recentDestinations.filterNot { it.equals(name, ignoreCase = true) }).take(3)
        prefs.edit().putString("recent-destinations", JSONArray(recentDestinations).toString()).apply()
    }
    internal var onboardingRevision by mutableIntStateOf(0); private set
    internal fun restartTutorial(): Boolean {
        if(!parked || activeJourney || awaitingMapRoute || !spotRecordingIdle()) return false
        if(!prefs.getBoolean("first-run-complete-v1",false)) return false
        prefs.edit().putBoolean("tutorial-replay-v1",true).commit()
        onboardingRevision++
        screen=Screen.HOME
        return true
    }
    var nickname by mutableStateOf(prefs.getString("profile-nickname",null) ?: "무사즈 드라이버"); private set
    fun updateNickname(input: String): Boolean {
        val value=input.trim()
        if(!parked || value.isBlank() || value.codePointCount(0,value.length)>16 || value.any { it.isISOControl() }) return false
        prefs.edit().putString("profile-nickname",value).apply()
        nickname=value
        return true
    }
    private val voice = ChallengeVoice(app)
    var voiceEnabled by mutableStateOf(prefs.getBoolean("voice", false)); private set
    fun toggleVoice() {
        if (!parked) return
        voiceEnabled = !voiceEnabled
        prefs.edit().putBoolean("voice", voiceEnabled).apply()
        if (voiceEnabled) voice.start() else voice.stop()
    }
    val hasLiveRoute get() = live.destination != null && (live.remainingM ?: 0.0) > 0 &&
        SystemClock.elapsedRealtime() - live.destinationAt <= 10_000
    var awaitingMapRoute by mutableStateOf(false)
        private set
    private var routeRequestedAt = 0L
    private var routeTimeout: Job? = null

    private var activeRound: ChallengeRound? = null
    private var liveArrivalAt: Instant? = null
    private var demoRoundStart = Instant.EPOCH
    private var demoStartedAt = 0L
    var missedDeadline by mutableStateOf(false); private set
    private fun matchVenue(point: GeoPoint?) = nearestEligibleVenue(
        point, challengeSpots.map { ChallengeVenue(it.venueId, requireNotNull(it.point)) })

    internal fun venueFor(place: SavedPlace): Destination? = matchVenue(place.point)?.let { match ->
        challengeSpots.first { it.venueId == match.venue.id }
    }

    internal val destinationVenueMatch get() = matchVenue(
        if (demo) selected.point else currentNavigationPlace?.point)
    internal val matchedVenue get() = destinationVenueMatch?.let { match -> challengeSpots.find { it.venueId == match.venue.id } }
    val destinationInChallengeGroup get() = destinationVenueMatch != null
    private var requestedVenueId: String? = null
    private var requestedRoutePoint: GeoPoint? = null
    private var dismissedRouteInvitation by mutableStateOf<String?>(null)

    internal val routeInvitationVenue: Destination?
        get() {
            val routeId = live.destinationId ?: return null
            return matchedVenue.takeIf {
                !demo && canChooseDestination && hasLiveRoute && !live.arrived &&
                    trackedSpot == null && routeId != dismissedRouteInvitation
            }
        }

    internal fun dismissRouteInvitation() {
        dismissedRouteInvitation = live.destinationId
    }

    internal fun continueWithoutChallenge() {
        if (!canChooseDestination || !live.parked) return
        if (currentNavigationPlace == null) { notice="내비 목적지 좌표를 기다리고 있어요"; return }
        openBuiltInNavigation()
        notice="선택한 목적지로 일반 길 안내를 계속해요"
    }


    private fun driverBeltAllowsEntry(): Boolean {
        if (demo || live.beltConditionMet) return true
        notice = "운전석 안전벨트 착용이 확인돼야 챌린지를 시작할 수 있어요."
        return false
    }

    private fun canJoinRound(round: ChallengeRound): Boolean {
        if (!driverBeltAllowsEntry()) return false
        if (!participationStore.available(demo, clock().atZone(ChallengeRound.seoul).toLocalDate())) {
            notice = "오늘은 이미 참가했어요. 실격·취소를 포함해 하루 한 경기만 참여할 수 있어요. 내일 다시 만나요."
            return false
        }
        if (demo || round.contains(clock())) return true
        notice = "현재 참가할 수 없는 시간이에요. 다음 회차를 확인해 주세요."
        return false
    }

    fun joinMapChallenge(round: ChallengeRound, venue: Destination = hangangChallenge) {
        val place = SavedPlace.from(venue.name, venue.point) ?: return
        joinDestinationChallenge(round, venue, place)
    }

    internal fun joinDestinationChallenge(round: ChallengeRound, venue: Destination, place: SavedPlace) {
        if (!parked || activeJourney || awaitingMapRoute || !spotRecordingIdle() || !canJoinRound(round)) return
        if (demo) {
            selectDestination(venue)
            accept(round)
            if (activeJourney) {
                if (live.parked && connection.requestSavedRoute(place)) {
                    openBuiltInNavigation()
                } else {
                    notice = "시연 예약 완료 · 내장 내비 연결이나 P단 상태를 확인해 주세요"
                }
            }
            return
        }
        if (!beginService()) return
        activeRound = round
        requestedVenueId = venue.venueId
        requestedRoutePoint = place.point
        routeRequestedAt = SystemClock.elapsedRealtime()
        awaitingMapRoute = true
        if (!connection.requestSavedRoute(place)) {
            awaitingMapRoute = false
            endService()
            notice = "내비 경로를 요청하지 못했어요. 연결 상태를 확인해 주세요."
            return
        }
        rememberDestination(place.name)
        routeTimeout = viewModelScope.launch {
            delay(20_000)
            awaitingMapRoute = false
            endService()
            notice = "내비에서 경로 응답을 받지 못했어요. 주차 후 다시 시도해 주세요."
        }
        openBuiltInNavigation()
    }

    fun navigateToChallengeWithoutJoining(venue: Destination = hangangChallenge) {
        SavedPlace.from(venue.name, venue.point)?.let(::navigateToPlace)
    }

    internal fun navigateToPlace(place: SavedPlace) {
        if (!canChooseDestination) return
        rememberDestination(place.name)
        if (connection.requestSavedRoute(place)) openBuiltInNavigation()
        else searchInNavigation(place.name)
    }

    fun searchInNavigation(keyword: String) {
        if (!parked || activeJourney || awaitingMapRoute || keyword.isBlank()) return
        if (!connection.openNavigation(search = true, keyword = keyword.trim())) {
            openBuiltInNavigation()
            notice = "내비 검색창에서 장소를 선택해 주세요"
        }
    }

    internal val canChooseDestination get() = parked && !activeJourney && !awaitingMapRoute

    internal fun searchDestination(keyword: String) {
        if (!canChooseDestination || keyword.isBlank()) return
        rememberDestination(keyword)
        searchInNavigation(keyword)
    }

    fun openBuiltInNavigation() {
        if (!parked) return
        val app = getApplication<Application>()
        val intent = app.packageManager.getLaunchIntentForPackage("ai.umos.maps.android.navigation.app")
        if (demo && !live.parked) {
            notice = "주차 상태를 확인하고 Pleos 하단의 내비 아이콘을 열어 주세요"
            return
        }
        runCatching {
            if (intent != null) {
                app.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            } else if (!connection.openNavigation(search = !activeJourney && !awaitingMapRoute)) {
                error("No navigation launcher")
            }
        }.onFailure { notice = "Pleos 하단의 내비 아이콘에서 목적지를 검색해 주세요" }
    }
    // Display fixtures only. The real destination is supplied by NaviHelper; counts are not live.
    private val destinationFixture get() = if (demo) selected else live.destination?.let { name ->
        fun key(value: String) = value.filter(Char::isLetterOrDigit).lowercase()
        demoDestinations.firstOrNull { key(it.name) == key(name) }
    }
    val participantLabel get() = when {
        !demo && !hasLiveRoute -> "내비에서 길 안내 시작"
        !demo && (destinationFixture?.demoPeers ?: 0) > 0 -> "참가 예시 ${destinationFixture?.demoPeers}명 · 시연"
        !demo -> "실시간 참가 현황 미연결"
        distanceKm < 10 -> "개인 기록"
        activeJourney -> "시연 ${selected.demoPeers + 1}명 · 나 포함"
        else -> "시연 ${selected.demoPeers}명 참여 중"
    }
    private fun announceRecording(previous: Phase) {
        if (voiceEnabled && previous == Phase.RESERVED && phase == Phase.RECORDING)
            voice.say("마지막 십 킬로미터입니다. 무사즈 기록을 시작해요.")
    }
    var demoDrive by mutableStateOf(DemoDrive.GOOD); private set
    var pendingBadges by mutableStateOf<List<BadgeAward>>(emptyList()); private set
    var ownedBadges by mutableStateOf(prefs.getStringSet("badges-live", emptySet())?.toSet() ?: emptySet()); private set
    internal var rankBadgeProgress by mutableStateOf(readRankBadgeProgress("rank-badges-live"))
        private set
    private val collectionStore = CollectionProgressStore(prefs)
    internal var collectionProgress by mutableStateOf(collectionStore.read(demo = false))
        private set
    internal var trackedSpot by mutableStateOf<SpotTour?>(null)
        private set
    private var spotStartedAt: Instant? = null
    private var spotRouteId: String? = null
    private var spotTimeout: Job? = null

    private fun spotRecordingIdle(): Boolean {
        if (trackedSpot == null) return true
        notice = "스팟투어 방문 기록을 종료한 뒤 다시 시도해 주세요"
        return false
    }

    internal fun startSpotVisit(spot: SpotTour) {
        if (!parked || activeJourney || awaitingMapRoute || !spotRecordingIdle()) return
        if (demo) {
            val recordedDays = collectionProgress.visitsBySpot[spot].orEmpty()
            val today = LocalDate.now(ChallengeRound.seoul)
            val nextDay = generateSequence(today) { it.minusDays(1) }.first { it !in recordedDays }
            notice = recordSpotVisit(spot, nextDay)
            return
        }
        if (!beginService()) return
        trackedSpot = spot
        spotStartedAt = Instant.now()
        spotRouteId = null
        spotTimeout = viewModelScope.launch {
            delay(4 * 60 * 60 * 1000L)
            finishSpotVisit("스팟투어 방문 기록 시간이 끝났어요")
        }
        consumeSpotVisit(live)
        if (spotRouteId != null) openBuiltInNavigation() else searchInNavigation(spot.destinationName)
    }

    internal fun cancelSpotVisit() {
        if (parked && trackedSpot != null) finishSpotVisit("스팟투어 방문 기록을 종료했어요")
    }

    private fun finishSpotVisit(message: String) {
        trackedSpot = null
        spotStartedAt = null
        spotRouteId = null
        spotTimeout?.cancel()
        spotTimeout = null
        notice = message
        endService()
    }

    private fun consumeSpotVisit(vehicle: LiveVehicle) {
        val spot = trackedSpot ?: return
        if (vehicle.routeCancelled) {
            if (spotRouteId != null) finishSpotVisit("길 안내가 취소되어 방문 기록을 종료했어요")
            return
        }
        val route = vehicle.destinationId ?: return
        val matches = spot.matches(vehicle.destination, vehicle.destinationPoint)
        if (!vehicle.arrived) {
            spotRouteId = route.takeIf { matches && hasLiveRoute }
            return
        }
        val arrivedAt = vehicle.arrivedAt ?: return
        val startedAt = spotStartedAt ?: return
        if (!matches || route != spotRouteId || arrivedAt < startedAt || !vehicle.parked) return
        finishSpotVisit(recordSpotVisit(spot, arrivedAt.atZone(ChallengeRound.seoul).toLocalDate()))
    }

    private fun recordSpotVisit(spot: SpotTour, day: LocalDate): String {
        val previous = collectionProgress
        val progress = previous.recordVisit(spot, day)
        val visits = progress.visits(spot)
        if (progress == previous) {
            return if (visits >= SpotTour.REQUIRED_VISITS) "이미 획득한 스팟투어 배지예요"
                else "오늘 이 스팟의 방문은 이미 기록됐어요"
        }
        val editor = prefs.edit()
        collectionStore.writeVisit(editor, demo, spot, progress)
        val award = creditNewBadges(previous, rankBadgeProgress, progress, rankBadgeProgress).singleOrNull()
        writeMileage(editor)
        editor.apply()
        collectionProgress = progress
        balance = ledger.balance
        return if (award != null) "${award.title} 배지를 획득했어요 · +${award.mileage}M"
            else "${spot.city} 스팟투어 방문을 기록했어요 · $visits / ${SpotTour.REQUIRED_VISITS}회"
    }

    private val rankBadgeKey get() = if (demo) "rank-badges-demo" else "rank-badges-live"

    private fun readRankBadgeProgress(key: String): RankBadgeProgress = RankBadgeProgress(
        RankBadgeGoal.entries.associateWith { prefs.getInt("$key-${it.id}", 0) },
        prefs.getStringSet("$key-days", emptySet()).orEmpty()
            .mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }.toSet()
    )

    private fun recordRankBadgeProgress(editor: SharedPreferences.Editor, arrivedAt: Instant, rank: Int, participants: Int) {
        val day = arrivedAt.atZone(ChallengeRound.seoul).toLocalDate()
        val updated = rankBadgeProgress.record(day, rank, participants, demo)
        if (updated == rankBadgeProgress) return
        val key = rankBadgeKey
        editor.apply {
            RankBadgeGoal.entries.forEach { putInt("$key-${it.id}", updated.days(it)) }
            putStringSet("$key-days", updated.countedDays.map { it.toString() }.toSet())
        }
        rankBadgeProgress = updated
    }
    fun chooseDemoDrive(profile: DemoDrive) { if(demo && parked && !activeJourney) demoDrive=profile }
    fun acknowledgeBadge() { if(parked) pendingBadges=pendingBadges.drop(1) }
    // The original wallet was for demonstration rewards. Keep those entries in the demo wallet.
    private fun mileageKey(key: String) = if (demo) key else "$key-live"
    private fun readMileage() = MileageLedger(
        prefs.getInt(mileageKey("balance"), 0),
        prefs.getStringSet(mileageKey("settled"), emptySet()).orEmpty(),
        prefs.getStringSet(mileageKey("daily"), emptySet()).orEmpty(),
    )
    private fun readRedeemedRewards() = prefs.getStringSet(mileageKey("redeemed-rewards"), emptySet()).orEmpty().toSet()
    private var ledger = readMileage()
    var balance by mutableIntStateOf(ledger.balance); private set
    var redeemedRewards by mutableStateOf(readRedeemedRewards())
        private set
    var couponNumbers by mutableStateOf(readCouponNumbers())
        private set

    private fun couponNumberKey(rewardId: String) = mileageKey("coupon-number-$rewardId")

    private fun newCouponNumber(): String {
        val digits = UUID.randomUUID().toString().replace("-", "").take(12).uppercase(Locale.ROOT)
        return digits.chunked(4).joinToString("-", prefix = "AJ-")
    }

    private fun readCouponNumbers(): Map<String, String> {
        val missing = mutableMapOf<String, String>()
        val numbers = redeemedRewards.associateWith { rewardId ->
            val key = couponNumberKey(rewardId)
            prefs.getString(key, null)?.takeIf { it.isNotBlank() }
                ?: newCouponNumber().also { missing[key] = it }
        }
        if (missing.isNotEmpty()) {
            val editor = prefs.edit()
            missing.forEach { (key, number) -> editor.putString(key, number) }
            editor.apply()
        }
        return numbers
    }

    private fun reloadMileage() {
        ledger = readMileage()
        balance = ledger.balance
        redeemedRewards = readRedeemedRewards()
        couponNumbers = readCouponNumbers()
    }

    private fun writeMileage(editor: SharedPreferences.Editor) {
        editor.putInt(mileageKey("balance"), ledger.balance)
            .putStringSet(mileageKey("settled"), ledger.settlementIds)
            .putStringSet(mileageKey("daily"), ledger.dailyKeys)
    }

    private fun creditNewBadges(
        previous: CollectionProgress, previousRanks: RankBadgeProgress,
        progress: CollectionProgress, ranks: RankBadgeProgress,
    ): List<BadgeAward> = newlyEarnedCollectionBadges(previous, previousRanks, progress, ranks)
        .filter { ledger.creditBadge(it.id, it.mileage) > 0 }

    internal fun redeemDemoReward(reward: MileageReward) {
        if (!demo || !parked || reward.id in redeemedRewards || !ledger.spend(reward.cost)) return
        val number = newCouponNumber()
        balance = ledger.balance
        redeemedRewards = redeemedRewards + reward.id
        couponNumbers = couponNumbers + (reward.id to number)
        prefs.edit().putInt(mileageKey("balance"), balance)
            .putStringSet(mileageKey("redeemed-rewards"), redeemedRewards)
            .putString(couponNumberKey(reward.id), number).apply()
    }
    private var demoJob: Job? = null
    private val samples = mutableListOf<Sample>()
    private val turnDetector = ManeuverMonitor()
    private val beltMonitor = SeatBeltMonitor()
    internal var beltDecision by mutableStateOf(BeltDecision(BeltVerdict.SAFE)); private set
    private var lastTurnAt = 0L
    private fun currentReport(): ScoreReport {
        val measured=ScoreEngine.evaluate(samples, turnDetector.events).copy(turnDiagnostics=turnDetector.diagnostics)
        return if (!demo && !consoleTest && (beltDecision.hadUnverifiedMotion ||
                beltDecision.verdict != BeltVerdict.SAFE)) measured.copy(score=null, valid=false) else measured
    }
    private var lastReportAt = 0L
    private var recordingStartedAtNanos: Long? = null
    private var uiVisible = false
    private val isEmulator = android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.HARDWARE in listOf("ranchu","goldfish")
    val consoleTestAvailable get() = BuildConfig.DEBUG && isEmulator
    val parked get() = if (demo) !demoMoving && (live.parked || (isEmulator && live.gearPark == null && live.uxRestricted != true && (live.speedMps ?: 0.0) < .1)) else live.parked
    private var completedDestinationName: String? = null
    val destinationName get() = completedDestinationName?.takeIf { phase in listOf(Phase.FINISHED, Phase.DISQUALIFIED) } ?:
        if (consoleTest) "콘솔 입력 테스트 · 시험 제한속도 ${consoleSessionLimitKph}km/h"
        else if(demo) selected.name else live.destination ?: "내비게이션 목적지를 설정해 주세요"
    val distanceKm get() = if(demo) selected.distanceKm else (live.remainingM ?: 0.0)/1000
    val phase get() = session?.phase ?: Phase.IDLE
    val activeJourney get() = phase in listOf(Phase.RESERVED,Phase.RECORDING,Phase.AWAITING_PARK)
    val resultUnlocked get() = phase in listOf(Phase.FINISHED, Phase.DISQUALIFIED) && parked

    init {
        viewModelScope.launch { connection.flow.collect { receiveVehicle(it) } }
        // Preserve every source sample; the UI state stream may conflate fast updates.
        viewModelScope.launch { connection.samples.collect { receiveSample(it) } }
        viewModelScope.launch {
            while (true) {
                delay(250)
                if (!demo && !consoleTest && activeJourney) checkSeatBelts(live, SystemClock.elapsedRealtime())
            }
        }
    }

    internal fun receiveVehicle(next: LiveVehicle, receivedAt: Long = SystemClock.elapsedRealtime()) {
        if (next.routeCancelled || (next.destinationId != null && next.destinationId != dismissedRouteInvitation)) {
            dismissedRouteInvitation = null
        }
        live=next
        // Console tests use real speed with explicit test limit/belt assumptions. Navigation,
        // turn, collection and belt-disqualification events must not settle a test as a trip.
        if (consoleTest && activeJourney) return
        if (!demo) {
            if (awaitingMapRoute && next.destinationAt >= routeRequestedAt && hasLiveRoute &&
                destinationVenueMatch?.venue?.id == requestedVenueId && next.destinationId != null && !next.arrived &&
                requestedRoutePoint?.let { point -> next.destinationPoint?.distanceMetersTo(point) }?.let { it <= 150.0 } == true) {
                awaitingMapRoute = false
                routeTimeout?.cancel()
                startSession()
            }
            consumeLive(next, receivedAt)
            consumeSpotVisit(next)
        }
    }
    internal fun receiveSample(sample: Sample, receivedAt: Long = SystemClock.elapsedRealtime()) {
        if (demo || phase != Phase.RECORDING) return
        val start = recordingStartedAtNanos ?: return
        if (sample.speedTimestampNanos < start) return
        // Keep invalid, duplicate and reversed samples as barriers to continuous detection.
        samples += if (consoleTest) sample.copy(limitKph = consoleSessionLimitKph) else sample
        if (receivedAt - lastReportAt >= 500) {
            progressReport = currentReport()
            if (consoleTest) {
                remainingM = (ScoreEngine.MAX_DISTANCE_M - progressReport.kilometers.sumOf { it.distanceM }).coerceAtLeast(0.0)
                logConsoleReport("recording", progressReport)
            }
            lastReportAt = receivedAt
        }
    }

    fun chooseConsoleLimit(limitKph: Int) {
        if (consoleTestAvailable && parked && !activeJourney && !awaitingMapRoute && limitKph in listOf(50, 80, 100))
            consoleLimitKph = limitKph
    }

    fun startConsoleTest() {
        if (!consoleTestAvailable || !live.parked || activeJourney || awaitingMapRoute || !spotRecordingIdle()) return
        if (!beginService()) return
        RecordingService.clearResult(getApplication())
        demoJob?.cancel()
        demoRunning = false
        demoMoving = false
        demo = false
        reloadMileage()
        consoleTest = true
        consoleSessionLimitKph = consoleLimitKph
        activeRound = null
        liveArrivalAt = null
        missedDeadline = false
        completedDestinationName = null
        selectedHistoryRecord = null
        connection.drainPendingSamples()
        samples.clear()
        turnDetector.reset()
        lastTurnAt = 0
        lastReportAt = 0
        report = null
        progressReport = ScoreEngine.evaluate(emptyList())
        ranking = emptyList()
        earned = 0
        badgeMileageEarned = 0
        pendingBadges = emptyList()
        ownedBadges = prefs.getStringSet("badges-live", emptySet())?.toSet() ?: emptySet()
        rankBadgeProgress = readRankBadgeProgress(rankBadgeKey)
        collectionProgress = collectionStore.read(demo = false)
        recordedDistanceMeters = prefs.getLong(distanceKey(false), 0)
        remainingM = ScoreEngine.MAX_DISTANCE_M
        session = ChallengeSession(UUID.randomUUID().toString(), "debug-console", remainingM)
            .accept(true).update(remainingM, false, true)
        recordingStartedAtNanos = SystemClock.elapsedRealtimeNanos()
        connection.setConsoleCapture(true)
        screen = Screen.JOURNEY
        notice = "콘솔 실제 차속 기록 시작 · 제한속도 ${consoleLimitKph}km/h·벨트 착용은 시험 가정"
        logConsoleReport("started", progressReport)
    }

    fun finishConsoleTest() {
        if (!consoleTestAvailable || !consoleTest || phase != Phase.RECORDING || !live.parked) return
        drainRecordingSamples(SystemClock.elapsedRealtimeNanos())
        val result = currentReport()
        progressReport = result
        report = result
        remainingM = (ScoreEngine.MAX_DISTANCE_M - result.kilometers.sumOf { it.distanceM }).coerceAtLeast(0.0)
        session = session?.update(0.0, true, true)
        recordingStartedAtNanos = null
        connection.setConsoleCapture(false)
        ranking = emptyList()
        earned = 0
        badgeMileageEarned = 0
        pendingBadges = emptyList()
        // A console result is diagnostic only: no history, weekly score, badges, or rewards.
        screen = Screen.RESULT
        logConsoleReport("finished", result)
        endService()
    }

    private fun logConsoleReport(stage: String, result: ScoreReport) {
        if (!consoleTestAvailable) return
        android.util.Log.d("H1Console", java.lang.String.format(java.util.Locale.US,
            "%s samples=%d distance=%.1fm accel=%d decel=%d speedBonus=%.4f smoothBonus=%.4f total=%.4f score=%s rate=%.2fHz coverage=%.1f%% limit=%d(test) belt=assumed",
            stage, samples.size, result.kilometers.sumOf { it.distanceM }, result.accelerationEvents,
            result.decelerationEvents, result.speedPoints, result.accelerationPoints, result.totalPoints,
            result.score?.toString() ?: "pending", result.longitudinalSampleRateHz,
            result.longitudinalCoverage * 100, consoleSessionLimitKph))
    }

    private fun drainRecordingSamples(untilNanos: Long) {
        connection.drainPendingSamples().forEach { sample ->
            if (sample.speedTimestampNanos <= untilNanos) receiveSample(sample)
        }
    }

    internal fun openCompletedResult() {
        selectedHistoryRecord=null
        if(activeJourney) { screen=Screen.JOURNEY; return }
        completedDriveStore.read()?.takeIf { it.session.id == session?.id && it.outcome != DriveOutcome.COMPLETED }
            ?.let { selectedHistoryRecord=it }
        if(report == null || phase != Phase.FINISHED) {
            completedDriveStore.read()?.let { saved ->
                connection.setConsoleCapture(false)
                consoleTest=false
                demo=saved.demo
                reloadMileage()
                ownedBadges=prefs.getStringSet(if(demo) "badges-demo" else "badges-live",emptySet())?.toSet() ?: emptySet()
                rankBadgeProgress=readRankBadgeProgress(rankBadgeKey)
                collectionProgress=collectionStore.read(demo)
                recordedDistanceMeters=prefs.getLong(distanceKey(demo),0)
                session=saved.session; report=saved.report; ranking=saved.ranking
                if (saved.outcome != DriveOutcome.COMPLETED) selectedHistoryRecord=saved
                earned=saved.earned; missedDeadline=saved.missedDeadline
                badgeMileageEarned = saved.badgeMileage
                completedDestinationName=saved.name
                selected=challengeSpots.firstOrNull {it.venueId==saved.session.venueId} ?: selected
            }
        }
        // The frame/result UI still requires fresh parked telemetry before showing details.
        screen=Screen.RESULT
    }
    fun enterForeground() { uiVisible=true; connection.start(); if (voiceEnabled) voice.start() }
    fun leaveForeground() { uiVisible=false; if(!activeJourney && !awaitingMapRoute && trackedSpot == null) { connection.stop(); voice.stop() } }
    private fun endService() {
        getApplication<Application>().stopService(android.content.Intent(getApplication(), RecordingService::class.java))
        if(!uiVisible) { connection.stop(); voice.stop() }
    }
    fun serviceEnded() { if(!uiVisible && !activeJourney && !awaitingMapRoute && trackedSpot == null) connection.stop() }
    private fun beginService(): Boolean = runCatching {
        getApplication<Application>().startForegroundService(android.content.Intent(getApplication(), RecordingService::class.java))
        true
    }.getOrElse { notice="기록 서비스를 시작하지 못했어요. 다시 참가해 주세요."; false }
    fun navigate(to: Screen) {
        if(!parked && to != Screen.JOURNEY) { notice="주차 후 확인할 수 있어요"; return }
        selectedHistoryRecord=null
        if(to == Screen.HISTORY) completedDrives=completedDriveStore.readAll()
        screen=to
    }
    internal fun openHistoryRecord(id: String) {
        if(!parked) { notice="주차 후 확인할 수 있어요"; return }
        val saved=historyDrives.firstOrNull { it.session.id == id } ?: return
        // Viewing an old result must not restore or settle it as the current journey.
        selectedHistoryRecord=saved
        screen=Screen.RESULT
    }
    fun selectDestination(d: Destination) { if (!parked || activeJourney) return; selected=d; remainingM=d.distanceKm*1000; report=null; session=null }
    fun switchSource() {
        if(activeJourney || awaitingMapRoute || !parked || !spotRecordingIdle()) return
        if(demo && !live.parked) { notice="실제 기기의 속도·P단·화면 제한을 먼저 확인해 주세요"; return }
        connection.setConsoleCapture(false)
        consoleTest=false
        demo=!demo
        reloadMileage()
        selectedHistoryRecord=null
        pendingBadges=emptyList()
        ownedBadges=prefs.getStringSet(if(demo) "badges-demo" else "badges-live",emptySet())?.toSet() ?: emptySet()
        rankBadgeProgress = readRankBadgeProgress(rankBadgeKey)
        collectionProgress = collectionStore.read(demo)
        recordedDistanceMeters = prefs.getLong(distanceKey(demo), 0)
        session=null; report=null; ranking=emptyList(); earned=0
        badgeMileageEarned = 0
        remainingM = if(demo) selected.distanceKm*1000 else live.remainingM ?: 0.0
        screen=Screen.HOME
    }
    fun accept(round: ChallengeRound) {
        if(!parked || awaitingMapRoute || activeJourney || !spotRecordingIdle()) return
        if (!demo && currentNavigationPlace == null) { notice="내비 목적지 좌표를 기다리고 있어요"; return }
        if (!destinationInChallengeGroup) {
            if (!demo) continueWithoutChallenge() else notice="목적지 1km 안에 개최지가 없어요"
            return
        }
        if (!canJoinRound(round)) return
        if(!demo && (live.destinationId == null || live.remainingM == null || SystemClock.elapsedRealtime()-live.destinationAt > 10_000)) {
            notice="내비게이션 목적지 정보를 기다리고 있어요"; return
        }
        if(!beginService()) return
        activeRound = round
        demoRoundStart = maxOf(clock(), round.opensAt)
        demoStartedAt = SystemClock.elapsedRealtime()
        startSession()
    }
    private fun startSession() {
        // Recheck after asynchronous navigation; a failed entry must not consume today's game.
        if (!driverBeltAllowsEntry()) {
            endService()
            return
        }
        val assigned = destinationVenueMatch ?: return
        val id = UUID.randomUUID().toString()
        val day = clock().atZone(ChallengeRound.seoul).toLocalDate()
        if (!participationStore.claim(demo, day, id)) {
            notice = "오늘 참가 내역이 있거나 참가 정보를 저장하지 못했어요. 참가 상태를 확인해 주세요."
            endService()
            return
        }
        RecordingService.clearResult(getApplication())
        connection.setConsoleCapture(false)
        consoleTest=false
        completedDestinationName=null
        if (!demo) selected=challengeSpots.first { it.venueId==assigned.venue.id }
        missedDeadline = false
        liveArrivalAt = null
        connection.drainPendingSamples()
        samples.clear(); turnDetector.reset(); lastTurnAt=0; lastReportAt=0; recordingStartedAtNanos=null
        beltMonitor.reset(); beltDecision=beltMonitor.decision
        connection.drainBeltObservations()
        progressReport=ScoreEngine.evaluate(emptyList())
        report=null; ranking=emptyList(); earned=0; pendingBadges=emptyList()
        badgeMileageEarned = 0
        remainingM=distanceKm*1000
        session=ChallengeSession(id,if(demo) selected.name else live.destinationId!!,remainingM, venueId=assigned.venue.id, competitionId="${assigned.venue.id}:${activeRound?.opensAt}").accept(true)
        recordingStartedAtNanos = if (!demo && phase == Phase.RECORDING) SystemClock.elapsedRealtimeNanos() else null
        screen=Screen.JOURNEY
        if (!demo) checkSeatBelts(live, SystemClock.elapsedRealtime())
    }
    fun cancel() {
        if(!parked) return
        connection.setConsoleCapture(false)
        consoleTest=false
        recordingStartedAtNanos=null; turnDetector.reset()
        demoJob?.cancel(); demoRunning=false; session=null; report=null; remainingM=distanceKm*1000; screen=Screen.HOME; endService()
    }
    fun runDemo() {
        if(!demo || !activeJourney || demoRunning || !parked) return
        demoRunning=true; demoMoving=true
        demoJob=viewModelScope.launch {
            var fixtureIndex=0
            while(remainingM > 0) {
                delay(700)
                remainingM=(remainingM-400).coerceAtLeast(0.0)
                val previous=phase
                session=session?.update(remainingM,false,false)
                announceRecording(previous)
                if(phase==Phase.RECORDING) {
                    repeat(240) {
                        // Same 24 seconds per UI tick, preserving a genuine 10 Hz fixture.
                        val i=fixtureIndex++
                        samples += demoDrive.sample(i)
                    }
                    progressReport=currentReport()
                }
            }
            val arrival = demoRoundStart.plusMillis(SystemClock.elapsedRealtime() - demoStartedAt)
            session=session?.update(0.0,true,false)
            delay(2200)
            demoMoving=false
            session=session?.update(0.0,true,true)
            finish(arrival)
            demoRunning=false
        }
    }
    private fun consumeLive(v: LiveVehicle, now: Long) {
        val s=session ?: return
        if(!activeJourney) return
        if (!checkSeatBelts(v, now)) return
        if (s.phase == Phase.AWAITING_PARK) {
            if (v.parked) {
                session = s.update(0.0, true, true)
                finish(liveArrivalAt)
            }
            return
        }
        if(v.routeCancelled) {
            recordingStartedAtNanos=null; turnDetector.interrupt()
            session=s.copy(phase=Phase.CANCELLED); notice="경로 안내가 종료되어 기록을 취소했어요"; endService(); return
        }
        if(v.destinationId != null && v.destinationId != s.destinationId) {
            recordingStartedAtNanos=null; turnDetector.interrupt()
            session=s.changeDestination(v.destinationId); notice="목적지가 변경되어 이번 기록을 종료했어요"; endService(); return
        }
        if (phase == Phase.RECORDING && now - lastTurnAt >= 200) {
            turnDetector.accept(v.turnObservation(now))
            lastTurnAt = now
        }
        if (v.arrived && v.arrivedAt != null && v.destinationId == s.destinationId) {
            // Include pending samples before arrival closes the recording phase.
            drainRecordingSamples(now * 1_000_000L)
            turnDetector.interrupt()
            progressReport=currentReport()
            recordingStartedAtNanos=null
            liveArrivalAt = v.arrivedAt
            remainingM = 0.0
            session = s.update(0.0, true, v.parked)
            if (phase == Phase.FINISHED) finish(liveArrivalAt)
            return
        }
        val remaining=v.remainingM?.takeIf { now-v.destinationAt <=10_000 } ?: return
        remainingM=remaining
        session=s.update(remaining,false,v.parked)
        if (s.phase != Phase.RECORDING && phase == Phase.RECORDING) recordingStartedAtNanos = now * 1_000_000L
        announceRecording(s.phase)
    }
    private fun finish(arrivedAt: Instant?) {
        val s=session ?: return
        if (completedDriveStore.readAll().any { it.session.id == s.id }) return
        val deadline=deadlineOutcome(CompletedDrive(s,currentReport(),destinationName,demo,emptyList(),0,false,
            completedAtEpochMs=clock().toEpochMilli(),
            participationDay=participationStore.dayFor(demo,s.id)?.toString()),arrivedAt,activeRound)
        if (deadline != null) {
            session=deadline.session
            missedDeadline=true
            completedDestinationName=deadline.name
            report=deadline.report; progressReport=deadline.report
            ranking=emptyList(); earned=0; badgeMileageEarned=0; pendingBadges=emptyList()
            recordingStartedAtNanos=null; turnDetector.interrupt()
            completedDriveStore.save(deadline)
            completedDrives=completedDriveStore.readAll()
            selectedHistoryRecord=deadline
            notice="대회 종료 시각 이후에 도착해 시간 초과로 실격됐어요. 오늘 참가로 기록되며 배지·보상은 지급되지 않아요."
            completed++
            screen=Screen.RESULT
            if (parked) RecordingService.notifyResult(getApplication())
            endService()
            return
        }
        if (!demo && beltDecision.hadUnverifiedMotion) {
            saveBeltOutcome(s, DriveOutcome.UNVERIFIABLE, BeltFailureReason.TELEMETRY_UNAVAILABLE)
            return
        }
        val r=currentReport()
        progressReport=r
        selectedHistoryRecord=null
        report=r
        missedDeadline = arrivedAt != null && activeRound?.let { arrivedAt >= it.closesAt } == true
        val withinRound = arrivedAt?.let { activeRound?.contains(it) } == true
        val score = r.score
        val participationDay = participationStore.dayFor(demo, s.id)
        val result = CompletedDrive(s, r, destinationName, demo, emptyList(), 0, missedDeadline,
            completedAtEpochMs = clock().toEpochMilli(), participationDay = participationDay?.toString())
        val recognition = recognizeResult(result, participationDay != null, withinRound)
        val accepted = recognition == ResultRecognition.ACCEPTED
        val previousCollection = collectionProgress
        val previousRanks = rankBadgeProgress
        val editor = prefs.edit()
        if (arrivedAt != null && score != null) {
            recordedDistanceMeters += r.validDistanceM.roundToLong()
            editor.putLong(distanceKey(demo), recordedDistanceMeters)
        }
        if (accepted && score != null) {
            val day = requireNotNull(participationDay)
            val progress = collectionProgress.recordChallenge(day, score)
            if (progress != collectionProgress) {
                collectionStore.writeChallenge(editor, demo, progress)
                collectionProgress = progress
            }
        }
        if(demo && accepted) {
            // Popularity is a venue-wide count; prototype settlement uses a five-person group.
            val fixtures = listOf(
                Entry("느긋한 고래", 92) to GeoPoint(37.528, 126.934),
                Entry("민트 드라이버", 89) to GeoPoint(37.524, 126.934),
                Entry("차분한 오후", 86) to GeoPoint(37.530, 126.937),
                Entry("파란 구름", 82) to GeoPoint(37.523, 126.927),
                Entry("멀리 가는 무사즈", 95) to GeoPoint(37.540, 126.934),
            )
            val peers = if (selected.name == "여의도한강공원") {
                fixtures.filter { (_, point) -> hangangVenue.includes(point) }
            } else fixtures.take(selected.demoPeers)
            ranking = Settlement.rank(listOf(Entry("나", requireNotNull(r.score))) + peers.take(4).map { it.first })
            val allocation=ranking.first { it.id=="나" }.miles
            earned=ledger.credit(s.id,allocation,s.destinationId,requireNotNull(participationDay).toString())
            if(allocation>0 && earned==0) notice="이미 정산된 경기 또는 참가일의 보상이에요. 중복 지급하지 않아요."
        } else { ranking=emptyList(); earned=0 }
        val playerRank = ranking.firstOrNull { it.id == "나" }?.rank
        if (accepted && playerRank != null) {
            recordRankBadgeProgress(editor, requireNotNull(participationDay).atStartOfDay(ChallengeRound.seoul).toInstant(), playerRank, ranking.size)
        }
        val legacyAwards = if (accepted) BadgeRules.newAwards(r.valid, s.destinationId, r.score, playerRank, ownedBadges) else emptyList()
        val collectionAwards = if (accepted) creditNewBadges(previousCollection, previousRanks, collectionProgress, rankBadgeProgress) else emptyList()
        badgeMileageEarned = collectionAwards.sumOf { it.mileage }
        pendingBadges = legacyAwards + collectionAwards
        ownedBadges = ownedBadges + legacyAwards.map { it.id }
        editor.putStringSet(if (demo) "badges-demo" else "badges-live", ownedBadges)
        writeMileage(editor)
        if (voiceEnabled && uiVisible && parked)
            voice.say("무사히 도착했어요. 무사즈에서 주행 결과를 확인해 주세요.")
        completedDestinationName=destinationName
        completedDriveStore.save(result.copy(ranking=ranking, earned=earned, badgeMileage=badgeMileageEarned,
            recognition=recognition, awardedBadges=pendingBadges), editor)
        if (!accepted) notice="${recognition.label}으로 개인 기록만 보관해요. 경기 보상·배지 집계에서는 제외돼요."
        balance = ledger.balance
        completedDrives=completedDriveStore.readAll()
        completed++
        screen=Screen.RESULT
        if (parked) RecordingService.notifyResult(getApplication())
        endService()
    }
    private fun checkSeatBelts(v: LiveVehicle, now: Long): Boolean {
        if (!activeJourney) return false
        connection.drainBeltObservations().forEach { beltMonitor.accept(it) }
        beltDecision=beltMonitor.accept(v.beltObservation(now))
        if (beltDecision.hadUnverifiedMotion || beltDecision.verdict != BeltVerdict.SAFE)
            progressReport=progressReport.copy(score=null, valid=false)
        if (beltDecision.verdict != BeltVerdict.DISQUALIFIED) return true
        val failed=requireNotNull(session).enforceBelt(beltDecision)
        val reason=if (beltDecision.seat == BeltSeat.PASSENGER) BeltFailureReason.PASSENGER_UNBUCKLED
            else BeltFailureReason.DRIVER_UNBUCKLED
        saveBeltOutcome(failed, DriveOutcome.DISQUALIFIED, reason)
        return false
    }

    /** A belt-invalid journey is persisted before service shutdown; it never reaches settlement. */
    private fun saveBeltOutcome(s: ChallengeSession, outcome: DriveOutcome, reason: BeltFailureReason) {
        if (completedDriveStore.readAll().any { it.session.id == s.id }) return
        val name=destinationName
        drainRecordingSamples(SystemClock.elapsedRealtimeNanos())
        val invalid=currentReport().copy(score=null, valid=false)
        session=s
        completedDestinationName=name
        report=invalid; progressReport=invalid
        ranking=emptyList(); earned=0; badgeMileageEarned=0; pendingBadges=emptyList()
        recordingStartedAtNanos=null; turnDetector.interrupt()
        val saved=CompletedDrive(s, invalid, name, demo, emptyList(), 0, false,
            completedAtEpochMs=clock().toEpochMilli(), outcome=outcome, beltFailureReason=reason,
            participationDay=participationStore.dayFor(demo, s.id)?.toString(),
            recognition=if(outcome==DriveOutcome.DISQUALIFIED) ResultRecognition.DISQUALIFIED else ResultRecognition.UNVERIFIABLE)
        completedDriveStore.save(saved)
        completedDrives=completedDriveStore.readAll()
        selectedHistoryRecord=saved
        notice=if (outcome == DriveOutcome.DISQUALIFIED) "${reason.label}으로 실격 처리됐어요. 주차 후 내 기록에서 확인해 주세요."
            else "주행 중 안전벨트 확인에 필요한 정보가 부족해 이번 점수·순위·보상을 집계하지 않았어요."
        if (parked) { screen=Screen.RESULT; RecordingService.notifyResult(getApplication()) }
        endService()
    }
    override fun onCleared() { demoJob?.cancel(); connection.setConsoleCapture(false); connection.stop(); voice.stop() }
}
