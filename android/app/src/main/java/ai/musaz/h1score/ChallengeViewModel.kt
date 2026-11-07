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
import java.util.UUID

data class Destination(val name: String, val area: String, val distanceKm: Double, val minutes: Int, val demoPeers: Int = 0)
val demoDestinations = listOf(
    Destination("현대 모터스튜디오 고양", "경기 고양 · 킨텍스 접근 구간", 12.4, 23, 4),
    Destination("판교 테크노밸리", "경기 성남 · 대왕판교로 접근 구간", 18.6, 32, 2),
    Destination("여의도한강공원", "서울 영등포 · 한강 챌린지", 14.0, 28, 4),
    Destination("우리 동네 공원", "가까운 이동 · 개인 기록", 3.2, 9),
)
enum class Screen { HOME, JOURNEY, RESULT, WALLET, BADGES, HISTORY, CONNECTION }

class ChallengeViewModel(app: Application): AndroidViewModel(app) {
    val connection = PleosConnection(app)
    var live by mutableStateOf(LiveVehicle()); private set
    var demo by mutableStateOf(false); private set
    var screen by mutableStateOf(Screen.HOME); private set
    var selected by mutableStateOf(demoDestinations[0]); private set
    var session by mutableStateOf<ChallengeSession?>(null); private set
    var remainingM by mutableDoubleStateOf(12_400.0); private set
    var demoMoving by mutableStateOf(false); private set
    var demoRunning by mutableStateOf(false); private set
    var progressReport by mutableStateOf(ScoreEngine.evaluate(emptyList())); private set
    var report by mutableStateOf<ScoreReport?>(null); private set
    var ranking by mutableStateOf<List<RankedEntry>>(emptyList()); private set
    var earned by mutableIntStateOf(0); private set
    var notice by mutableStateOf<String?>(null)
    var completed by mutableIntStateOf(0); private set
    private val prefs = app.getSharedPreferences("h1-demo-v1",0)
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

    fun joinMapChallenge() {
        if (!parked || activeJourney || awaitingMapRoute) return
        if (demo) {
            selectDestination(demoDestinations.first { it.name == "여의도한강공원" })
            accept()
            return
        }
        if (!live.beltConditionMet) {
            notice = "운전석 벨트를 착용한 뒤 참가해 주세요"
            return
        }
        if (!beginService()) return
        routeRequestedAt = SystemClock.elapsedRealtime()
        awaitingMapRoute = true
        if (!connection.requestChallengeRoute()) {
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

    fun navigateToChallengeWithoutJoining() {
        if (!parked || activeJourney || awaitingMapRoute) return
        if (connection.requestChallengeRoute()) openBuiltInNavigation()
        else searchInNavigation("여의도한강공원")
    }

    fun searchInNavigation(keyword: String) {
        if (!parked || activeJourney || awaitingMapRoute || keyword.isBlank()) return
        if (!connection.openNavigation(search = true, keyword = keyword.trim())) {
            openBuiltInNavigation()
            notice = "내비 검색창에서 장소를 선택해 주세요"
        }
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
            if (demo) switchSource()
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
    private val turnDetector=TurnDetector()
    private var lastTurnAt=0L
    private fun currentReport()=ScoreEngine.evaluate(samples,turnDetector.events)
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
        viewModelScope.launch { connection.flow.collect { next -> receiveVehicle(next) } }
    }
    // Explicit monotonic receipt time supports deterministic recorded-telemetry replay in tests.
    internal fun receiveVehicle(next: LiveVehicle, receivedAt: Long = SystemClock.elapsedRealtime()) {
        live=next
        if (!demo) {
            if (awaitingMapRoute && next.destinationAt >= routeRequestedAt && hasLiveRoute &&
                next.destination?.filter(Char::isLetterOrDigit) == "여의도한강공원") {
                awaitingMapRoute=false; routeTimeout?.cancel(); startSession()
            }
            consumeLive(next,receivedAt)
        }
    }
    fun enterForeground() { uiVisible=true; connection.start(); if (voiceEnabled) voice.start() }
    fun leaveForeground() { uiVisible=false; if(!activeJourney && !awaitingMapRoute) { connection.stop(); voice.stop() } }
    private fun endService() {
        getApplication<Application>().stopService(android.content.Intent(getApplication(), RecordingService::class.java))
        if(!uiVisible) { connection.stop(); voice.stop() }
    }
    fun serviceEnded() { if(!uiVisible && !activeJourney && !awaitingMapRoute) connection.stop() }
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
        if(activeJourney || awaitingMapRoute || !parked) return
        if(demo && !live.parked) { notice="실제 기기의 속도·P단·화면 제한을 먼저 확인해 주세요"; return }
        demo=!demo
        pendingBadges=emptyList()
        ownedBadges=prefs.getStringSet(if(demo) "badges-demo" else "badges-live",emptySet())?.toSet() ?: emptySet()
        session=null; report=null; ranking=emptyList(); earned=0
        remainingM = if(demo) selected.distanceKm*1000 else live.remainingM ?: 0.0
        screen=Screen.HOME
    }
    fun accept() {
        if(!parked || awaitingMapRoute || activeJourney) return
        if(!demo && (live.destinationId == null || live.remainingM == null || SystemClock.elapsedRealtime()-live.destinationAt > 10_000)) {
            notice="내비게이션 목적지 정보를 기다리고 있어요"; return
        }
        if(!demo && !live.beltConditionMet) { notice="운전석 벨트 상태를 확인한 뒤 참가해 주세요"; return }
        if(!beginService()) return
        startSession()
    }
    private fun startSession() {
        samples.clear(); turnDetector.reset();lastTurnAt=0; progressReport=ScoreEngine.evaluate(emptyList()); report=null; ranking=emptyList(); earned=0; lastSampleAt=0; pendingBadges=emptyList()
        remainingM=distanceKm*1000
        session=ChallengeSession(UUID.randomUUID().toString(),if(demo) selected.name else live.destinationId!!,remainingM).accept(true)
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
                    progressReport=currentReport()
                }
            }
            session=session?.update(0.0,true,false)
            delay(2200)
            demoMoving=false
            session=session?.update(0.0,true,true)
            finish()
            demoRunning=false
        }
    }
    private fun consumeLive(v: LiveVehicle, now: Long) {
        val s=session ?: return
        if(!activeJourney) return
        val beltChecked=s.enforceBelt(v.freshBelt(now))
        if (beltChecked.phase == Phase.DISQUALIFIED) {
            session=beltChecked
            report=null; ranking=emptyList(); earned=0; pendingBadges=emptyList()
            notice="안전벨트 미착용으로 실격 처리했어요. 이번 점수·순위·보상은 지급하지 않습니다."
            endService(); return
        }
        if(v.routeCancelled) {
            session=s.copy(phase=Phase.CANCELLED); notice="경로 안내가 종료되어 기록을 취소했어요"; endService(); return
        }
        if(v.destinationId != null && v.destinationId != s.destinationId) {
            session=s.changeDestination(v.destinationId); notice="목적지가 변경되어 이번 기록을 종료했어요"; endService(); return
        }
        if(phase==Phase.RECORDING && !demo && now-lastTurnAt>=200) {
            if(v.freshBelt(now)==true) turnDetector.accept(v.turnObservation(now)) else turnDetector.interrupt()
            lastTurnAt=now
        }
        val remaining=v.remainingM?.takeIf { now-v.destinationAt <=10_000 } ?: return
        remainingM=remaining
        session=s.update(remaining,v.arrived,v.isParked(now))
        announceRecording(s.phase)
        if(phase==Phase.RECORDING && now-lastSampleAt >=900) {
            samples += Sample(now,if(v.freshBelt(now)==true)v.freshSpeed(now) else null,v.freshLimit(now)); lastSampleAt=now
            progressReport=currentReport()
        }
        if(phase==Phase.FINISHED) finish()
    }
    private fun finish() {
        val s=session ?: return
        val r=currentReport()
        report=r
        if(demo && s.rankingEligible && r.valid && r.score!=null) {
            ranking=Settlement.rank(listOf(Entry("나",requireNotNull(r.score))) + listOf(Entry("느긋한 고래",92),Entry("민트 드라이버",89),Entry("차분한 오후",86),Entry("파란 구름",82)).take(selected.demoPeers))
            val allocation=ranking.first { it.id=="나" }.miles
            earned=ledger.credit(s.id,allocation,s.destinationId,java.time.LocalDate.now().toString())
            balance=ledger.balance
            prefs.edit().putInt("balance",balance).putStringSet("settled",ledger.settlementIds).putStringSet("daily",ledger.dailyKeys).apply()
            if(allocation>0 && earned==0) notice="오늘의 보상 한도를 적용했어요 · 목적지별 1회, 하루 2회"
        } else { ranking=emptyList(); earned=0 }
        pendingBadges=BadgeRules.newAwards(r.valid,s.destinationId,r.score,ranking.firstOrNull { it.id=="나" }?.rank,ownedBadges)
        ownedBadges=ownedBadges+pendingBadges.map { it.id }
        prefs.edit().putStringSet(if(demo) "badges-demo" else "badges-live",ownedBadges).apply()
        if (voiceEnabled && uiVisible && parked && pendingBadges.isNotEmpty())
            voice.say("무사히 도착했어요. 새 배지 ${pendingBadges.size}개를 받았어요.")
        completed++
        screen=Screen.RESULT
        endService()
    }
    override fun onCleared() { demoJob?.cancel(); connection.stop(); voice.stop() }
}
