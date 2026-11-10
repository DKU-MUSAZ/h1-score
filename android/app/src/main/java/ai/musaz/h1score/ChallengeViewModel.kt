package ai.musaz.h1score

import android.app.Application
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
internal data class DestinationShortcut(val id: String, val label: String, val place: SavedPlace? = null, val legacyQuery: String = "")
private const val HomeShortcutKey = "destination-shortcut-home"
private const val WorkShortcutKey = "destination-shortcut-work"
enum class Screen { HOME, JOURNEY, RESULT, WALLET, BADGES, HISTORY, CONNECTION, PROFILE }

class ChallengeViewModel(app: Application): AndroidViewModel(app) {
    val connection = PleosConnection(app)
    var live by mutableStateOf(LiveVehicle()); private set
    var demo by mutableStateOf(false); private set
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
    var report by mutableStateOf<ScoreReport?>(null); private set
    var ranking by mutableStateOf<List<RankedEntry>>(emptyList()); private set
    var earned by mutableIntStateOf(0); private set
    var notice by mutableStateOf<String?>(null)
    var completed by mutableIntStateOf(0); private set
    private val prefs = app.getSharedPreferences("h1-demo-v1",0)
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
    internal fun renameShortcut(id: String, label: String): Boolean {
        val clean=shortcutLabel(label) ?: return false
        if (!canChooseDestination || destinationShortcuts.none { it.id==id } || destinationShortcuts.any { it.id!=id && it.label==clean }) return false
        writeShortcuts(destinationShortcuts.map { if(it.id==id) it.copy(label=clean) else it })
        return true
    }
    internal fun removeShortcut(id: String) {
        if (!canChooseDestination) return
        writeShortcuts(destinationShortcuts.filterNot { it.id==id })
    }
    internal val currentNavigationPlace get() = if (!live.routeCancelled && SystemClock.elapsedRealtime()-live.destinationAt in 0..10_000)
        SavedPlace.from(live.destination.orEmpty(),live.destinationPoint) else null
    internal fun saveShortcutPlace(id: String, place: SavedPlace): Boolean {
        if (!canChooseDestination || destinationShortcuts.none { it.id==id } || SavedPlace.from(place.name,place.point)==null) return false
        writeShortcuts(destinationShortcuts.map { if(it.id==id) it.copy(place=place,legacyQuery="") else it })
        return true
    }
    internal fun navigateShortcut(id: String) {
        if (!canChooseDestination || !live.parked) return
        val place=destinationShortcuts.find { it.id==id }?.place ?: return
        if (connection.requestSavedRoute(place)) { rememberDestination(place.name); openBuiltInNavigation() }
        else notice="내비 연결을 확인해 주세요. 저장한 목적지는 유지돼요."
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
    internal val destinationVenueMatch get() = nearestEligibleVenue(
        if (demo) selected.point else currentNavigationPlace?.point,
        challengeSpots.map { ChallengeVenue(it.venueId, requireNotNull(it.point)) })
    internal val matchedVenue get() = destinationVenueMatch?.let { match -> challengeSpots.find { it.venueId == match.venue.id } }
    val destinationInChallengeGroup get() = destinationVenueMatch != null
    private var requestedVenueId: String? = null

    internal fun continueWithoutChallenge() {
        if (!canChooseDestination || !live.parked) return
        if (currentNavigationPlace == null) { notice="내비 목적지 좌표를 기다리고 있어요"; return }
        openBuiltInNavigation()
        notice="선택한 목적지로 일반 길 안내를 계속해요"
    }


    private fun canJoinRound(round: ChallengeRound): Boolean {
        if (demo || round.contains(Instant.now())) return true
        notice = "현재 참가할 수 없는 시간이에요. 다음 회차를 확인해 주세요."
        return false
    }

    fun joinMapChallenge(round: ChallengeRound, venue: Destination = hangangChallenge) {
        val venuePlace = SavedPlace.from(venue.name,venue.point) ?: return
        if (!parked || activeJourney || awaitingMapRoute || !spotRecordingIdle() || !canJoinRound(round)) return
        if (demo) {
            selectDestination(venue)
            accept(round)
            if (activeJourney) {
                if (live.parked && connection.requestSavedRoute(venuePlace)) {
                    openBuiltInNavigation()
                } else {
                    notice = "시연 예약 완료 · 내장 내비 연결이나 P단 상태를 확인해 주세요"
                }
            }
            return
        }
        if (!live.beltConditionMet) {
            notice = "운전석 벨트를 착용한 뒤 참가해 주세요"
            return
        }
        if (!beginService()) return
        activeRound = round
        requestedVenueId = venue.venueId
        routeRequestedAt = SystemClock.elapsedRealtime()
        awaitingMapRoute = true
        if (!connection.requestSavedRoute(venuePlace)) {
            awaitingMapRoute = false
            endService()
            notice = "내비 경로를 요청하지 못했어요. 연결 상태를 확인해 주세요."
            return
        }
        routeTimeout = viewModelScope.launch {
            delay(20_000)
            awaitingMapRoute = false
            endService()
            notice = "내비에서 경로 응답을 받지 못했어요. 주차 후 다시 시도해 주세요."
        }
        openBuiltInNavigation()
    }

    fun navigateToChallengeWithoutJoining(venue: Destination = hangangChallenge) {
        if (!parked || activeJourney || awaitingMapRoute) return
        if (connection.requestSavedRoute(SavedPlace.from(venue.name, venue.point) ?: return)) openBuiltInNavigation()
        else searchInNavigation(venue.name)
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
        if (demo || !parked || activeJourney || awaitingMapRoute || !spotRecordingIdle() || !beginService()) return
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
        val progress = collectionProgress.recordVisit(spot, arrivedAt.atZone(ChallengeRound.seoul).toLocalDate())
        val changed = progress != collectionProgress
        if (changed) {
            collectionStore.saveVisit(spot, progress)
            collectionProgress = progress
        }
        val visits = progress.visits(spot)
        finishSpotVisit(when {
            changed && visits == 3 -> "${spot.city} 스팟투어 배지를 획득했어요"
            changed -> "${spot.city} 스팟투어 방문을 기록했어요 · $visits / 3회"
            visits == 3 -> "이미 획득한 스팟투어 배지예요"
            else -> "오늘 이 스팟의 방문은 이미 기록됐어요"
        })
    }

    private val rankBadgeKey get() = if (demo) "rank-badges-demo" else "rank-badges-live"

    private fun readRankBadgeProgress(key: String): RankBadgeProgress = RankBadgeProgress(
        RankBadgeGoal.entries.associateWith { prefs.getInt("$key-${it.id}", 0) },
        prefs.getStringSet("$key-days", emptySet()).orEmpty()
            .mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }.toSet()
    )

    private fun recordRankBadgeProgress(arrivedAt: Instant, rank: Int, participants: Int) {
        val day = arrivedAt.atZone(ChallengeRound.seoul).toLocalDate()
        val updated = rankBadgeProgress.record(day, rank, participants, demo)
        if (updated == rankBadgeProgress) return
        val key = rankBadgeKey
        prefs.edit().apply {
            RankBadgeGoal.entries.forEach { putInt("$key-${it.id}", updated.days(it)) }
            putStringSet("$key-days", updated.countedDays.map { it.toString() }.toSet())
        }.apply()
        rankBadgeProgress = updated
    }
    fun chooseDemoDrive(profile: DemoDrive) { if(demo && parked && !activeJourney) demoDrive=profile }
    fun acknowledgeBadge() { if(parked) pendingBadges=pendingBadges.drop(1) }
    private val ledger = MileageLedger(prefs.getInt("balance", 0),prefs.getStringSet("settled", emptySet()) ?: emptySet(),prefs.getStringSet("daily", emptySet()) ?: emptySet())
    var balance by mutableIntStateOf(ledger.balance); private set
    var redeemedRewards by mutableStateOf(prefs.getStringSet("redeemed-rewards", emptySet())?.toSet().orEmpty())
        private set
    internal fun redeemDemoReward(reward: MileageReward) {
        if (!parked || reward.id in redeemedRewards || !ledger.spend(reward.cost)) return
        balance = ledger.balance
        redeemedRewards = redeemedRewards + reward.id
        prefs.edit().putInt("balance", balance).putStringSet("redeemed-rewards", redeemedRewards).apply()
    }
    private var demoJob: Job? = null
    private val samples = mutableListOf<Sample>()
    private var lastSampleAt = 0L
    private var uiVisible = false
    private val isEmulator = android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.HARDWARE in listOf("ranchu","goldfish")
    val parked get() = if (demo) !demoMoving && (live.parked || (isEmulator && live.gearPark == null && live.uxRestricted != true && (live.speedMps ?: 0.0) < .1)) else live.parked
    val destinationName get() = if(demo) selected.name else live.destination ?: "내비게이션 목적지를 설정해 주세요"
    val distanceKm get() = if(demo) selected.distanceKm else (live.remainingM ?: 0.0)/1000
    val phase get() = session?.phase ?: Phase.IDLE
    val activeJourney get() = phase in listOf(Phase.RESERVED,Phase.RECORDING,Phase.AWAITING_PARK)
    val resultUnlocked get() = phase == Phase.FINISHED && parked

    init {
        viewModelScope.launch { connection.flow.collect { receiveVehicle(it) } }
    }

    internal fun receiveVehicle(next: LiveVehicle) {
        live=next
        if (!demo) {
            if (awaitingMapRoute && next.destinationAt >= routeRequestedAt && hasLiveRoute &&
                destinationVenueMatch?.venue?.id == requestedVenueId && next.destinationId != null && !next.arrived) {
                awaitingMapRoute = false
                routeTimeout?.cancel()
                startSession()
            }
            consumeLive(next)
            consumeSpotVisit(next)
        }
    }
    fun enterForeground() { uiVisible=true; RecordingService.clearResult(getApplication()); connection.start(); if (voiceEnabled) voice.start() }
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
        screen=to
    }
    fun selectDestination(d: Destination) { if (!parked || activeJourney) return; selected=d; remainingM=d.distanceKm*1000; report=null; session=null }
    fun switchSource() {
        if(activeJourney || awaitingMapRoute || !parked || !spotRecordingIdle()) return
        if(demo && !live.parked) { notice="실제 기기의 속도·P단·화면 제한을 먼저 확인해 주세요"; return }
        demo=!demo
        pendingBadges=emptyList()
        ownedBadges=prefs.getStringSet(if(demo) "badges-demo" else "badges-live",emptySet())?.toSet() ?: emptySet()
        rankBadgeProgress = readRankBadgeProgress(rankBadgeKey)
        collectionProgress = collectionStore.read(demo)
        recordedDistanceMeters = prefs.getLong(distanceKey(demo), 0)
        session=null; report=null; ranking=emptyList(); earned=0
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
        if(!demo && !live.beltConditionMet) { notice="운전석 벨트 상태를 확인한 뒤 참가해 주세요"; return }
        if(!beginService()) return
        activeRound = round
        demoRoundStart = maxOf(Instant.now(), round.opensAt)
        demoStartedAt = SystemClock.elapsedRealtime()
        startSession()
    }
    private fun startSession() {
        val assigned = destinationVenueMatch ?: return
        if (!demo) selected=challengeSpots.first { it.venueId==assigned.venue.id }
        missedDeadline = false
        liveArrivalAt = null
        samples.clear(); report=null; ranking=emptyList(); earned=0; lastSampleAt=0; pendingBadges=emptyList()
        remainingM=distanceKm*1000
        session=ChallengeSession(UUID.randomUUID().toString(),if(demo) selected.name else live.destinationId!!,remainingM, venueId=assigned.venue.id, competitionId="${assigned.venue.id}:${activeRound?.opensAt}").accept(true)
        screen=Screen.JOURNEY
    }
    fun cancel() {
        if(!parked) return
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
                    repeat(24) {
                        // Accelerated replay: 24 one-second samples per UI tick.
                        val i=fixtureIndex++
                        samples += demoDrive.sample(i)
                    }
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
    private fun consumeLive(v: LiveVehicle) {
        val s=session ?: return
        if(!activeJourney) return
        if (s.phase == Phase.AWAITING_PARK) {
            if (v.parked) {
                session = s.update(0.0, true, true)
                finish(liveArrivalAt)
            }
            return
        }
        if(v.routeCancelled) {
            session=s.copy(phase=Phase.CANCELLED); notice="경로 안내가 종료되어 기록을 취소했어요"; endService(); return
        }
        if(v.destinationId != null && v.destinationId != s.destinationId) {
            session=s.changeDestination(v.destinationId); notice="목적지가 변경되어 이번 기록을 종료했어요"; endService(); return
        }
        if (v.arrived && v.arrivedAt != null && v.destinationId == s.destinationId) {
            liveArrivalAt = v.arrivedAt
            remainingM = 0.0
            session = s.update(0.0, true, v.parked)
            if (phase == Phase.FINISHED) finish(liveArrivalAt)
            return
        }
        val now=SystemClock.elapsedRealtime()
        val remaining=v.remainingM?.takeIf { now-v.destinationAt <=10_000 } ?: return
        remainingM=remaining
        session=s.update(remaining,false,v.parked)
        announceRecording(s.phase)
        if(phase==Phase.RECORDING && now-lastSampleAt >=900) {
            samples += Sample(now,v.freshSpeed(now),v.freshLimit(now)); lastSampleAt=now
        }
    }
    private fun finish(arrivedAt: Instant?) {
        val s=session ?: return
        val r=ScoreEngine.evaluate(samples)
        report=r
        missedDeadline = arrivedAt != null && activeRound?.let { arrivedAt >= it.closesAt } == true
        val withinRound = arrivedAt?.let { activeRound?.contains(it) } == true
        val score = r.score
        if (arrivedAt != null && score != null) {
            recordedDistanceMeters += r.validDistanceM.roundToLong()
            prefs.edit().putLong(distanceKey(demo), recordedDistanceMeters).apply()
        }
        if (arrivedAt != null && withinRound && s.rankingEligible && r.valid && score != null) {
            val day = arrivedAt.atZone(ChallengeRound.seoul).toLocalDate()
            val progress = collectionProgress.recordChallenge(day, score)
            if (progress != collectionProgress) {
                collectionStore.saveChallenge(demo, progress)
                collectionProgress = progress
            }
        }
        if(demo && withinRound && s.rankingEligible && r.valid && r.score!=null) {
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
            earned=ledger.credit(s.id,allocation,s.destinationId,requireNotNull(arrivedAt).atZone(ChallengeRound.seoul).toLocalDate().toString())
            balance=ledger.balance
            prefs.edit().putInt("balance",balance).putStringSet("settled",ledger.settlementIds).putStringSet("daily",ledger.dailyKeys).apply()
            if(allocation>0 && earned==0) notice="오늘의 보상 한도를 적용했어요 · 목적지별 1회, 하루 2회"
        } else { ranking=emptyList(); earned=0 }
        val playerRank = ranking.firstOrNull { it.id == "나" }?.rank
        if (playerRank != null && arrivedAt != null) {
            recordRankBadgeProgress(arrivedAt, playerRank, ranking.size)
        }
        pendingBadges=BadgeRules.newAwards(r.valid,s.destinationId,r.score,playerRank,ownedBadges)
        ownedBadges=ownedBadges+pendingBadges.map { it.id }
        prefs.edit().putStringSet(if(demo) "badges-demo" else "badges-live",ownedBadges).apply()
        if (voiceEnabled && uiVisible && parked && pendingBadges.isNotEmpty())
            voice.say("무사히 도착했어요. 새 배지 ${pendingBadges.size}개를 받았어요.")
        completed++
        screen=Screen.RESULT
        if (!uiVisible && parked) RecordingService.notifyResult(getApplication())
        endService()
    }
    override fun onCleared() { demoJob?.cancel(); connection.stop(); voice.stop() }
}
