package ai.musaz.h1score

import androidx.annotation.DrawableRes
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ai.musaz.h1score.core.*
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.delay
import kotlin.math.min
import kotlin.math.roundToInt

private val Ink=H1Style.Ink
private val Muted=H1Style.Muted
private val Paper=H1Style.Paper
private val Line=H1Style.Line
private val Mint=H1Style.Mint
private val Green=H1Style.Green
private val PaleMint=H1Style.PaleMint
private val Gold=H1Style.Gold

@Composable fun H1App(vm: ChallengeViewModel) {
    MaterialTheme(colorScheme=H1Style.colorScheme) {
        BoxWithConstraints(Modifier.fillMaxSize().background(Paper),contentAlignment=Alignment.Center) {
            val compact = maxWidth < maxHeight * 1.25f
            if (compact && (!vm.parked || vm.screen in listOf(Screen.HOME, Screen.JOURNEY))) {
                CompactNavigationPanel(vm)
            } else {
                val current=LocalDensity.current
                val scale=min(maxWidth.value/1180f,maxHeight.value/680f)
                val frameHeight=(maxHeight/scale).coerceAtMost(800.dp)
                CompositionLocalProvider(LocalDensity provides Density(current.density*scale,current.fontScale)) {
                    AppFrame(vm, Modifier.requiredSize(1180.dp,frameHeight))
                }
            }
        }
    }
}

@Composable private fun CompactNavigationPanel(vm: ChallengeViewModel) {
    val waiting = vm.phase == Phase.RESERVED
    Column(Modifier.fillMaxSize().background(Ink).verticalScroll(rememberScrollState()).padding(24.dp)) {
        Text(stringResource(R.string.app_name),color=Color.White,fontSize=23.sp,fontWeight=FontWeight.Bold)
        Spacer(Modifier.height(18.dp))
        Tag(when(vm.phase) {
            Phase.RESERVED -> "참가 예약 완료"
            Phase.RECORDING -> "주행 기록 중"
            Phase.AWAITING_PARK -> "주차 후 결과 공개"
            Phase.CANCELLED -> "기록 종료"
            Phase.DISQUALIFIED -> "안전벨트 미착용 · 실격"
            Phase.FINISHED -> "기록 완료"
            else -> "이번 목적지"
        },PaleMint,Green)
        Text(vm.destinationName,color=Color.White,fontSize=19.sp,modifier=Modifier.padding(top=14.dp))
        if (vm.demo || vm.hasLiveRoute || vm.phase == Phase.AWAITING_PARK) {
            val km = if (vm.activeJourney) vm.remainingM / 1000 else vm.distanceKm
            Text("${"%.1f".format(km)} km 남음",color=Color.White,fontSize=34.sp,modifier=Modifier.padding(top=6.dp))
        }
        Text(if (waiting) "마지막 10km부터 자동 기록" else if (vm.activeJourney) "순위와 보상은 주차 후 확인" else vm.participantLabel,
            color=Color(0xFFB7C7D8),fontSize=14.sp,modifier=Modifier.padding(top=8.dp))
        if (vm.parked) {
            Spacer(Modifier.height(14.dp))
            when {
                vm.demo && vm.activeJourney -> Button(onClick=vm::runDemo) { Text("시연 주행 재생") }
                !vm.activeJourney && !vm.demo && vm.hasLiveRoute && vm.currentNavigationPlace != null && !vm.destinationInChallengeGroup -> {
                    Button(onClick=vm::continueWithoutChallenge) { Text("일반 내비로 계속") }
                }
                !vm.activeJourney -> TextButton(onClick=vm::openBuiltInNavigation) { Text("내장 내비에서 검색",color=Mint) }
            }
        }
        vm.notice?.let { Text(it,color=Mint,fontSize=13.sp,modifier=Modifier.padding(top=10.dp)) }
    }
}

@Composable private fun AppFrame(vm: ChallengeViewModel, modifier: Modifier) {
    var destinations by remember { mutableStateOf(false) }
    var simulator by remember { mutableStateOf(false) }
    var badgeReveal by remember { mutableStateOf(false) }
    var historyDateOrder by rememberSaveable { mutableStateOf(HistoryDateOrder.NEWEST_FIRST) }
    var historySelectedDate by rememberSaveable { mutableStateOf<String?>(null) }
    var historyMonth by rememberSaveable { mutableStateOf(initialHistoryMonth(vm.completedDrives).toString()) }
    LaunchedEffect(vm.parked) { if(!vm.parked) { destinations=false; simulator=false; badgeReveal=false } }
    LaunchedEffect(vm.notice) { if(vm.notice!=null) { kotlinx.coroutines.delay(3500); vm.notice=null } }
    val display=if(!vm.parked) Screen.JOURNEY else vm.screen
    val historyResult=display==Screen.RESULT && vm.selectedHistoryRecord!=null
    Row(modifier.background(Paper)) {
        Column(
            Modifier.width(152.dp).fillMaxHeight().background(Color.White)
                .padding(start = 6.dp, end = 6.dp, top = 28.dp, bottom = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal=8.dp), verticalAlignment=Alignment.CenterVertically, horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(34.dp), contentAlignment=Alignment.Center) {
                    Image(painterResource(R.drawable.app_icon), null, Modifier.size(32.dp))
                }
                Text("안운잘부",color=Ink,fontSize=18.sp,fontWeight=FontWeight.Bold,letterSpacing=(-.6).sp)
            }
            Spacer(Modifier.height(30.dp))
            Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                NavItem(
                    "챌린지", R.drawable.ic_nav_challenge,
                    display in listOf(Screen.HOME, Screen.JOURNEY, Screen.RESULT) && !historyResult, vm.parked
                ) { vm.navigate(if (vm.activeJourney) Screen.JOURNEY else Screen.HOME) }
                NavItem("내 기록", R.drawable.ic_nav_history, display == Screen.HISTORY || historyResult, vm.parked) {
                    vm.navigate(Screen.HISTORY)
                }
                NavItem("마일리지", R.drawable.ic_nav_mileage, display == Screen.WALLET, vm.parked) {
                    vm.navigate(Screen.WALLET)
                }
                NavItem("배지", R.drawable.ic_nav_badge, display == Screen.BADGES, vm.parked) {
                    vm.navigate(Screen.BADGES)
                }
                NavItem("마이페이지", R.drawable.ic_nav_profile, display == Screen.PROFILE, vm.parked) {
                    vm.navigate(Screen.PROFILE)
                }
            }
            Spacer(Modifier.weight(1f))
            Text("MUSAZ", color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.5.sp)
        }
        VerticalDivider(color = Line)
        Column(Modifier.weight(1f).padding(horizontal=32.dp)) {
            Row(Modifier.fillMaxWidth().height(86.dp),verticalAlignment=Alignment.CenterVertically) {
                Text(when(display) {
                    Screen.HOME -> "챌린지"
                    Screen.JOURNEY -> "주행 기록"
                    Screen.RESULT -> if(historyResult) "내 기록 상세" else "챌린지 결과"
                    Screen.HISTORY -> "내 기록"
                    Screen.WALLET -> "마일리지"
                    Screen.BADGES -> "배지 컬렉션"
                    Screen.PROFILE -> "마이페이지"
                    Screen.CONNECTION -> "연결 상태"
                },modifier=Modifier.semantics { heading() },color=Ink,fontWeight=FontWeight.Bold,fontSize=32.sp,letterSpacing=(-.8).sp)
                Spacer(Modifier.weight(1f))
                Icon(if(vm.parked) Icons.Outlined.LocalParking else Icons.Outlined.DirectionsCar,null,Modifier.size(17.dp),tint=Green)
                Text(if(vm.parked) " 주차 중" else " 주행 모드",color=Green,fontSize=13.sp,fontWeight=FontWeight.Medium)
                Spacer(Modifier.width(18.dp))
                Surface(onClick={ if(vm.parked) simulator=true },enabled=vm.parked,color=if(vm.demo) Color(0xFFF1EBE0) else PaleMint,shape=RoundedCornerShape(20.dp)) {
                    Text(if(vm.demo) "●  시연 모드" else "●  Pleos 연결",Modifier.padding(horizontal=14.dp,vertical=9.dp),fontSize=12.sp,color=if(vm.demo) Color(0xFF846B40) else Green,fontWeight=FontWeight.SemiBold)
                }
            }
            AnimatedContent(targetState=display,modifier=Modifier.weight(1f).fillMaxWidth(),transitionSpec={ (fadeIn(tween(220))+slideInHorizontally { it/25 }) togetherWith fadeOut(tween(120)) },label="screen") { screen ->
                Box(Modifier.fillMaxSize()) {
                    when(screen) {
                        Screen.HOME -> ChallengeHomeScreen(vm)
                        Screen.JOURNEY -> Journey(vm)
                        Screen.RESULT -> ResultScreen(vm) { badgeReveal=true }
                        Screen.WALLET -> Wallet(vm)
                        Screen.BADGES -> BadgeCollectionScreen(
                            legacyOwned = vm.ownedBadges,
                            rankProgress = vm.rankBadgeProgress,
                            collectionProgress = vm.collectionProgress,
                            demo = vm.demo,
                            trackedSpot = vm.trackedSpot,
                            onStartSpot = vm::startSpotVisit,
                            onCancelSpot = vm::cancelSpotVisit,
                            onChallenge = { vm.navigate(Screen.HOME) }
                        )
                        Screen.HISTORY -> History(vm,historyDateOrder,{ historyDateOrder=it },
                            historySelectedDate?.let(LocalDate::parse),{ historySelectedDate=it?.toString() },
                            YearMonth.parse(historyMonth),{ historyMonth=it.toString() })
                        Screen.CONNECTION -> ConnectionScreen(vm)
                        Screen.PROFILE -> MyProfileScreen(vm,display==Screen.PROFILE && vm.parked)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().height(39.dp),verticalAlignment=Alignment.CenterVertically) {
                Text("무사즈가 함께하는 좋은 운전 습관",fontSize=11.sp,color=Muted)
            }
        }
    }
    if(destinations && vm.parked) Modal("어디로 갈까요?", "시연할 목적지를 선택해 주세요",{destinations=false}) {
        demoDestinations.forEach { d ->
            Surface(onClick={vm.selectDestination(d)},color=if(vm.selected==d) PaleMint else Paper,shape=RoundedCornerShape(16.dp),modifier=Modifier.fillMaxWidth().padding(bottom=12.dp)) {
                Row(Modifier.padding(20.dp),verticalAlignment=Alignment.CenterVertically) {
                    Icon(Icons.Outlined.LocationOn,null,tint=Green)
                    Column(Modifier.weight(1f).padding(start=12.dp)) { Text(d.name,fontSize=19.sp,fontWeight=FontWeight.Bold); Text(d.area,fontSize=13.sp,color=Muted) }
                    Text("${d.distanceKm} km",color=Green,fontWeight=FontWeight.Bold)
                }
            }
        }
        RoundConsent(vm, "선택한 장소로 시연 예약", true) {
            destinations=false
            vm.accept(it)
        }
    }
    if(simulator && vm.parked) Modal("프로토타입 제어", "사용자 화면과 개발 확인을 분리했어요",{simulator=false}) {
        Text("시연 모드는 가상의 목적지·주행·참가자 데이터를 사용합니다. Pleos 연결 모드는 실제 SDK 수신값만 표시합니다.",color=Muted,fontSize=16.sp,lineHeight=25.sp)
        Spacer(Modifier.height(22.dp))
        if(vm.demo && !vm.activeJourney) {
            TextButton(onClick={simulator=false;destinations=true}) { Text("시연 장소 선택") }
            Text("다음 시연의 결과 표정",fontSize=14.sp,fontWeight=FontWeight.Bold)
            Row(Modifier.fillMaxWidth().padding(top=10.dp,bottom=18.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                DemoDrive.entries.forEach { profile ->
                    FilterChip(selected=vm.demoDrive==profile,onClick={vm.chooseDemoDrive(profile)},label={Text(profile.label,fontSize=12.sp)})
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(bottom=12.dp),verticalAlignment=Alignment.CenterVertically) {
            Text("무사즈 음성 안내",Modifier.weight(1f),fontWeight=FontWeight.SemiBold)
            Switch(checked=vm.voiceEnabled,onCheckedChange={vm.toggleVoice()})
        }
        Primary("SDK 연결 상태 보기",Icons.Outlined.Sensors) { simulator=false; vm.navigate(Screen.CONNECTION) }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(onClick={simulator=false;vm.switchSource()},enabled=!vm.activeJourney,modifier=Modifier.fillMaxWidth().height(56.dp),shape=RoundedCornerShape(14.dp)) {
            Text(if(vm.demo) "Pleos 실제 입력으로 전환" else "시연 모드 켜기")
        }
        if(vm.activeJourney) Text("참가를 취소하거나 기록을 마친 뒤 모드를 바꿀 수 있어요.",fontSize=12.sp,color=Muted,modifier=Modifier.padding(top=12.dp))
    }
    if(badgeReveal && vm.parked && vm.pendingBadges.isNotEmpty()) {
        val badge=vm.pendingBadges.first()
        BadgeCelebration(badge,vm.pendingBadges.size) {
            vm.acknowledgeBadge()
            if(vm.pendingBadges.isEmpty()) badgeReveal=false
        }
    }
    vm.notice?.let { message -> Box(Modifier.fillMaxSize(),contentAlignment=Alignment.BottomCenter) {
        Surface(color=Ink,shape=RoundedCornerShape(16.dp),modifier=Modifier.padding(bottom=40.dp)) { Text(message,color=Color.White,modifier=Modifier.padding(20.dp)) }
    } }
}

@Composable private fun NavItem(
    label: String,
    @DrawableRes icon: Int,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 72.dp).tutorialTarget("nav-$label")
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected && enabled) PaleMint else Color.Transparent)
            .selectable(selected = selected, enabled = enabled, role = Role.Tab, onClick = onClick)
            .alpha(if (enabled) 1f else .45f)
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
            Image(painterResource(icon), contentDescription = null)
        }
        Text(label, fontSize = 16.sp, lineHeight = 20.sp, color = Ink, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
internal fun NavigationSearchDialog(
    keyword: String,
    onDismiss: () -> Unit,
    onSearch: () -> Unit,
    placeResolved: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        containerColor = Color.White,
        titleContentColor = Ink,
        textContentColor = Muted,
        title = {
            Text(if (placeResolved) "일반 길 안내를 시작할까요?" else "내장 내비에서 검색할까요?", fontWeight = FontWeight.Bold)
        },
        text = {
            Text(if (placeResolved) "‘$keyword’에서 1km 안에 챌린지 개최지가 없어요. 내장 내비에서 이 목적지로 안내할게요."
                else "앱에서 ‘$keyword’의 좌표를 확인하지 못했어요. 내장 내비에서 장소를 선택하면 목적지 주변 챌린지를 확인할 수 있어요.")
        },
        confirmButton = {
            Button(
                onClick = onSearch,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Ink, contentColor = Color.White)
            ) { Text(if (placeResolved) "내비 시작" else "내비에서 검색") }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Ink),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Ink)
            ) { Text("검색 계속") }
        }
    )
}

@Composable
internal fun LiveRouteChallengeDialog(
    vm: ChallengeViewModel,
    destinationName: String,
    venueName: String,
    onDismiss: () -> Unit,
) {
    Modal("$venueName 대회 참가", "내비 목적지 · $destinationName", onDismiss) {
        Text("현재 목적지로 길 안내를 계속하면서 이 챌린지에 참가해요.", color = Muted)
        if (!vm.live.beltConditionMet) Text("운전석 벨트 상태를 확인해 주세요.", color = Muted)
        RoundConsent(vm, "동의하고 참가", vm.live.beltConditionMet) { round ->
            vm.accept(round)
            if (vm.activeJourney) onDismiss()
        }
    }
}

@Composable internal fun ChallengeMap(vm: ChallengeViewModel, modifier: Modifier, initialSpot: Destination? = null) {
    var query by remember { mutableStateOf("") }
    var zoom by remember { mutableFloatStateOf(1f) }
    var confirm by remember { mutableStateOf(false) }
    var navigationQuery by remember { mutableStateOf<String?>(null) }
    var selectedMapSpot by remember(initialSpot) { mutableStateOf(initialSpot) }
    val application = androidx.compose.ui.platform.LocalContext.current.applicationContext as H1Application
    var showKakao by remember { mutableStateOf(application.kakaoMapEnabled) }
    var kakaoMap by remember { mutableStateOf<com.kakao.vectormap.KakaoMap?>(null) }
    val focus = LocalFocusManager.current
    val canJoin = vm.parked && !vm.activeJourney && !vm.awaitingMapRoute
    val key = query.filter(Char::isLetterOrDigit)
    val matchingSpots = challengeSpots.filter { query.isBlank() ||
        (it.name + it.area).filter(Char::isLetterOrDigit).contains(key, ignoreCase = true) }
    val matches = matchingSpots.isNotEmpty()
    fun confirmNavigationSearch() {
        if (canJoin && query.isNotBlank() && !matches) {
            focus.clearFocus()
            navigationQuery = query.trim()
        }
    }
    LaunchedEffect(query) { if (query.isNotBlank() && matchingSpots.size == 1) selectedMapSpot = matchingSpots.single() }
    val now = rememberChallengeTime()
    val deadlineLabel = ChallengeRound.currentOrNext(now).closingLabel + " 마감"
    Box(modifier.clipToBounds()) {
        if (showKakao) KakaoChallengeMap(matchingSpots, selectedMapSpot, deadlineLabel, { kakaoMap=it }, {
            kakaoMap=null
            showKakao=false
        }) {
            if (canJoin) { selectedMapSpot=it; focus.clearFocus(); confirm=true }
        }
        Column(Modifier.align(Alignment.TopStart).fillMaxWidth().padding(18.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Text("챌린지 지도",Modifier.weight(1f),fontSize=21.sp,fontWeight=FontWeight.Bold)
                Tag(if(showKakao) "카카오맵" else "장소 목록",Color.White,Muted)
            }
            OutlinedTextField(query,{query=it},enabled=vm.parked && !vm.activeJourney,singleLine=true,
                keyboardOptions=KeyboardOptions(imeAction=ImeAction.Search),
                keyboardActions=KeyboardActions(onSearch={confirmNavigationSearch()}),
                leadingIcon={Icon(Icons.Outlined.Search,null)},
                trailingIcon={if(query.isNotEmpty()) IconButton(onClick={query=""}) { Icon(Icons.Outlined.Close,"검색어 지우기") }},
                placeholder={Text("챌린지 장소 검색")},shape=RoundedCornerShape(28.dp),
                colors=OutlinedTextFieldDefaults.colors(focusedContainerColor=Color.White,unfocusedContainerColor=Color.White,
                    focusedBorderColor=Green,unfocusedBorderColor=Color.Transparent),
                modifier=Modifier.fillMaxWidth().padding(top=10.dp))
        }
        if (showKakao) Surface(Modifier.align(Alignment.BottomEnd).padding(14.dp),shape=RoundedCornerShape(20.dp),color=Color.White) {
            Column {
                IconButton(onClick={
                    if(showKakao) kakaoMap?.moveCamera(com.kakao.vectormap.camera.CameraUpdateFactory.zoomIn())
                    else zoom=(zoom+.25f).coerceAtMost(2.25f)
                },enabled=if(showKakao) kakaoMap!=null else zoom<2.25f) { Icon(Icons.Outlined.Add,"지도 확대") }
                IconButton(onClick={
                    if(showKakao) kakaoMap?.moveCamera(com.kakao.vectormap.camera.CameraUpdateFactory.zoomOut())
                    else zoom=(zoom-.25f).coerceAtLeast(1f)
                },enabled=if(showKakao) kakaoMap!=null else zoom>1f) { Icon(Icons.Outlined.Remove,"지도 축소") }
            }
        }
        if (!showKakao && matches) Column(Modifier.align(Alignment.Center).padding(top=90.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("지도를 불러오지 못했어요 · 장소 목록", color=Ink)
            matchingSpots.forEach { spot ->
                OutlinedButton(
                    onClick = { selectedMapSpot = spot; confirm = true },
                    enabled = canJoin,
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = if (spot == selectedMapSpot) PaleMint else Color.Transparent
                    )
                ) {
                    Text("${spot.name} · 참가 예시 ${spot.demoPeers}명")
                }
            }
        }
        if (!matches) Surface(Modifier.align(Alignment.Center).padding(top=55.dp),shape=RoundedCornerShape(20.dp),color=Color.White) {
            Column(Modifier.widthIn(max=360.dp).padding(18.dp)) {
                Text("챌린지 지도에서 찾지 못했어요", color=Ink, fontWeight=FontWeight.Bold)
                Text("장소를 검색해 목적지 주변 챌린지를 확인할 수 있어요.",
                    color=Muted, fontSize=13.sp, modifier=Modifier.padding(top=6.dp,bottom=14.dp))
                Primary("목적지 검색", enabled = canJoin, action = ::confirmNavigationSearch)
            }
        }
        Text("마커를 눌러 챌린지 참가",fontSize=12.sp,color=Ink,
            modifier=Modifier.align(Alignment.BottomStart).padding(20.dp))
    }
    LaunchedEffect(canJoin) {
        if (!canJoin) {
            confirm = false
            navigationQuery = null
        }
    }
    if (canJoin) navigationQuery?.let { keyword ->
        DestinationSearchDialog(vm, keyword) { navigationQuery = null }
    }
    if (confirm && vm.parked) selectedMapSpot?.let { spot ->
        ChallengeEntryDialog(vm, spot) { confirm=false }
    }
}

@Composable internal fun ChallengeEntryDialog(
    vm: ChallengeViewModel,
    destination: Destination,
    place: ai.musaz.h1score.core.SavedPlace? = null,
    onDismiss: () -> Unit,
) {
    val isHangang = destination == hangangChallenge
    val title = if (isHangang) "여의도 한강공원" else destination.name
    Modal("이 목적지로 챌린지에 참가할까요?", "$title · 참가 예시 ${destination.demoPeers}명", onDismiss) {
        if (place != null) Text("내비 목적지 · ${place.name}", color=Ink, fontWeight=FontWeight.SemiBold)
        Text(if (vm.demo && !isHangang && place == null) "예약 후 ‘시연 주행 재생’을 누르면 가상 주행이 시작돼요. 도착 후 결과를 볼 수 있어요."
            else "길 안내를 시작하고 남은 경로가 10km에 들어오면 자동으로 기록해요. 도착 후 주차하면 결과를 볼 수 있어요.",
            color=Muted,modifier=Modifier.padding(bottom=18.dp))
        val beltRequired = !vm.demo && !vm.live.beltConditionMet
        if (beltRequired) {
            Text(if(vm.live.belt == false) "운전석 벨트를 착용하면 참가할 수 있어요."
                else "운전석 벨트 상태를 확인하고 있어요.",color=Ink,
                modifier=Modifier.fillMaxWidth().background(PaleMint,RoundedCornerShape(12.dp)).padding(14.dp))
            Spacer(Modifier.height(12.dp))
        }
        if (beltRequired) {
            OutlinedButton(onClick={
                onDismiss()
                if (place != null) vm.navigateToPlace(place) else vm.navigateToChallengeWithoutJoining(destination)
            },modifier=Modifier.fillMaxWidth()) {
                Text("길 안내만 열기")
            }
            Spacer(Modifier.height(8.dp))
        }
        val canKeepRoute = place == null && !vm.demo && vm.hasLiveRoute && vm.destinationInChallengeGroup && !beltRequired
        if (canKeepRoute) {
            Text("현재 목적지는 ${vm.matchedVenue?.name} 대회에 배정돼요.", color=Green)
        }
        val action = when {
            !vm.demo -> "동의하고 내비 시작"
            isHangang || place != null -> "참가 예약 · 내비 시작"
            else -> "시연 참가 예약"
        }
        RoundConsent(vm, action, !beltRequired,
            if (canKeepRoute) {
                { round -> onDismiss(); vm.accept(round) }
            } else null) {
            onDismiss()
            if (place == null) vm.joinMapChallenge(it, destination)
            else vm.joinDestinationChallenge(it, destination, place)
        }
    }
}

@Composable private fun rememberChallengeTime(): Instant {
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            now = Instant.now()
        }
    }
    return now
}

@Composable internal fun RoundConsent(
    vm: ChallengeViewModel,
    action: String,
    enabled: Boolean,
    keepRoute: ((ChallengeRound) -> Unit)? = null,
    onJoin: (ChallengeRound) -> Unit,
) {
    val now = rememberChallengeTime()
    val round = ChallengeRound.currentOrNext(now)
    var consent by remember(round) { mutableStateOf(false) }
    val open = round.contains(now)
    Text("${round.name} 챌린지 · ${round.hoursLabel} (한국 시간)", color=Ink, fontWeight=FontWeight.Bold)
    Text("중간에 참가해도 마감은 ${round.closingLabel}예요. 마감 시각 전에 도착하지 못하면 순위와 마일리지 대상에서 제외돼요.",
        color=Ink, modifier=Modifier.padding(top=8.dp))
    Text("같은 회차에 챌린지 장소에서 직선거리 1km 이내를 목적지로 설정한 참가자끼리 비교해요.",
        color=Muted, modifier=Modifier.padding(top=6.dp))
    if (!open) Text(if (vm.demo) "시연은 다음 회차 시작 시각부터 재현해요." else "다음 회차 시작 전이에요.", color=Green)
    Row(Modifier.fillMaxWidth().toggleable(value=consent, role=androidx.compose.ui.semantics.Role.Checkbox,
        onValueChange={consent=it}), verticalAlignment=Alignment.CenterVertically) {
        Checkbox(consent, onCheckedChange=null, modifier=Modifier.padding(12.dp))
        Text("마감 및 참가 조건에 동의합니다", color=Ink)
    }
    val canSubmit = enabled && consent && (open || vm.demo)
    Primary(action, enabled=canSubmit) { onJoin(round) }
    if (keepRoute != null) OutlinedButton(onClick={keepRoute(round)}, enabled=canSubmit, modifier=Modifier.fillMaxWidth()) {
        Text("현재 목적지로 참가")
    }
}

@Composable private fun Journey(vm:ChallengeViewModel) {
    val waiting=vm.phase==Phase.RESERVED
    val arrived=vm.phase==Phase.AWAITING_PARK
    val disqualified=vm.phase==Phase.DISQUALIFIED
    val cancelled=vm.phase==Phase.CANCELLED || disqualified
    val idle=vm.phase==Phase.IDLE
    Column(Modifier.fillMaxSize().clip(RoundedCornerShape(28.dp)).background(Ink).padding(34.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(if(waiting) Color(0xFFD8C394) else Mint,CircleShape))
            Text(if(idle) "차량 상태 확인 중" else if(disqualified) "안전벨트 미착용 · 실격" else if(cancelled) "기록 종료" else if(arrived) "도착 확인" else if(waiting) "참가 예약 완료" else "주행 기록 중",color=Color.White,fontSize=16.sp,fontWeight=FontWeight.Medium,modifier=Modifier.padding(start=10.dp))
            Spacer(Modifier.weight(1f))
            Text(vm.destinationName,color=Color(0xFFB7C7D8),fontSize=15.sp)
        }
        Row(Modifier.weight(1f),verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1.2f)) {
                Text(when {idle->"안전한 주차 후\n시작해 주세요.";disqualified->"안전벨트 미착용으로\n실격 처리됐어요";cancelled->"기록을 종료했어요";arrived->"무사히 도착했어요.";waiting->"운전에만 집중하세요.";else->"좋은 흐름으로\n가고 있어요."},color=Color.White,fontSize=39.sp,lineHeight=52.sp,fontWeight=FontWeight.Bold,letterSpacing=(-1).sp)
                Text(when {idle->"P단·속도·차량 화면 제한을 확인하고 있어요.";disqualified->"이번 점수·순위·보상은 지급하지 않아요.";cancelled->"주차 후 새 목적지로 참가해 주세요.";arrived->"P단으로 주차하면 결과가 열려요.";waiting->"남은 경로가 10km가 되면\n기록을 자동으로 시작할게요.";else->"순위와 보상은 주차한 뒤 알려드릴게요."},color=Color(0xFFAFBDCC),fontSize=17.sp,lineHeight=28.sp,modifier=Modifier.padding(top=19.dp))
                Spacer(Modifier.height(35.dp))
                Row(verticalAlignment=Alignment.Bottom) {
                    Text("%.1f".format(vm.remainingM/1000),color=Color.White,fontSize=66.sp,fontWeight=FontWeight.Light,letterSpacing=(-3).sp)
                    Text(" km 남음",color=Color(0xFFAFBDCC),fontSize=17.sp,modifier=Modifier.padding(bottom=14.dp))
                }
            }
            Box(Modifier.weight(1f).fillMaxHeight(),contentAlignment=Alignment.Center) { RouteArt(if(waiting) 0f else (1-vm.remainingM/10000).toFloat().coerceIn(0f,1f)) }
        }
        if(vm.consoleTest && vm.parked) {
            Text("급가속 ${vm.progressReport.accelerationEvents}건 · 급감속 ${vm.progressReport.decelerationEvents}건 · 누적 가점 +${scorePoints(vm.progressReport.earnedPoints)}점", color=Mint, fontSize=14.sp)
            Text("시험 제한속도 ${vm.consoleSessionLimitKph}km/h · %.1fHz · 내 기록·주간 점수·배지·보상 제외".format(vm.progressReport.longitudinalSampleRateHz), color=Mint, fontSize=12.sp)
        }
        LinearProgressIndicator(progress={if(waiting) 0f else (1-vm.remainingM/10000).toFloat().coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth().height(5.dp).clip(CircleShape),color=Mint,trackColor=Color(0xFF304765))
        Row(Modifier.fillMaxWidth().padding(top=17.dp),verticalAlignment=Alignment.CenterVertically) {
            Text(if(waiting) "기록 시작까지 ${"%.1f".format(((vm.remainingM-10000)/1000).coerceAtLeast(0.0))} km" else if(arrived) "결과 공개 대기" else "안전한 곳까지 평소처럼 운전해 주세요",color=Color(0xFFAFBDCC),fontSize=13.sp)
            Spacer(Modifier.weight(1f))
            if(vm.parked && vm.activeJourney) {
                TextButton(onClick=vm::cancel) { Text("참가 취소",color=Color(0xFFAFBDCC)) }
                if(vm.consoleTest) Button(onClick=vm::finishConsoleTest) { Text("콘솔 시험 종료") }
                else if(vm.demo) Button(onClick=vm::runDemo,shape=RoundedCornerShape(12.dp),colors=ButtonDefaults.buttonColors(containerColor=Mint,contentColor=Ink)) { Text("시연 주행 재생  →",fontWeight=FontWeight.Bold) }
                else Button(onClick=vm::openBuiltInNavigation,colors=ButtonDefaults.buttonColors(containerColor=Mint,contentColor=Ink)) { Text("내장 내비로 돌아가기") }
            }
        }
    }
}

@Composable private fun ResultScreen(vm:ChallengeViewModel,onBadge:()->Unit) {
    val saved=vm.selectedHistoryRecord
    val report=saved?.report ?: vm.report
    if(report==null || !vm.parked || (saved==null && !vm.resultUnlocked)) { EmptyState("아직 기록이 없어요","챌린지에 참가하고 주차한 뒤 결과를 확인해 주세요.") { vm.navigate(Screen.HOME) }; return }
    if(saved!=null) {
        SavedDriveDetailScreen(saved) { vm.navigate(Screen.HISTORY) }
        return
    }
    val mood=DriveMood.forScore(report.score)
    val accent=if(mood==DriveMood.TEARY) Color(0xFFA8654C) else Green
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(verticalAlignment=Alignment.CenterVertically) {
            Column { Text(saved?.name ?: vm.destinationName,color=Muted,fontSize=13.sp); Text(when(mood) {
                DriveMood.CELEBRATING->"오늘의 드라이브, 멋졌어요!"
                DriveMood.ENCOURAGING->"좋은 습관을 하나씩 쌓아가요."
                DriveMood.TEARY->"다음엔 조금 더 여유롭게 가요."
                DriveMood.NEUTRAL->"무사히 도착했어요."
            },fontSize=29.sp,fontWeight=FontWeight.Bold,modifier=Modifier.padding(top=8.dp)) }
            Spacer(Modifier.weight(1f))
            Tag(if(saved==null && vm.consoleTest) "콘솔 시험 · 집계 제외" else if(saved?.missedDeadline ?: vm.missedDeadline) "마감 이후 도착 · 순위 제외" else if(report.valid) "기록 확인 완료" else "개인 기록",PaleMint,Green)
        }
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth().height(400.dp),horizontalArrangement=Arrangement.spacedBy(20.dp)) {
            Column(Modifier.weight(.9f).fillMaxHeight().clip(RoundedCornerShape(24.dp)).background(Color.White).padding(20.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Text("오늘의 주행습관 점수",color=Muted,fontSize=14.sp)
                    Spacer(Modifier.weight(1f))
                    Icon(Icons.Outlined.VerifiedUser,null,tint=Green,modifier=Modifier.size(20.dp))
                }
                Row(Modifier.fillMaxWidth().height(156.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
                    ScoreDial(report.score,accent,Modifier.size(140.dp))
                    ExpressionMascot(mood,Modifier.size(148.dp))
                }
                if(report.score != null) ScoreMetricBars(report,10.dp)
                else if(report.usesDistanceBonus) Text("거리·수신 품질 부족 · 점수 미확정",fontSize=13.sp,color=Muted)
                else Spacer(Modifier.height(10.dp))
                Spacer(Modifier.weight(1f))
                HorizontalDivider(Modifier.padding(vertical=10.dp),color=Line)
                Row {
                    SmallStat("유효 주행","%.1f km".format(report.validDistanceM/1000))
                    Spacer(Modifier.weight(1f));SmallStat("데이터 수신","${(report.coverage*100).roundToInt()}%")
                    Spacer(Modifier.weight(1f));SmallStat(if(report.usesDistanceBonus) "급감속" else "급제동",
                        if(report.usesDistanceBonus) "구간 가점 제외" else "감점 제외")
                }
            }
            Surface(Modifier.weight(1f).fillMaxHeight(),color=Color.White,shape=RoundedCornerShape(24.dp)) {
                ResultFeedback(report)
            }
        }
        ResultRanking(vm,saved)
        if(saved==null && vm.pendingBadges.isNotEmpty()) {
            Surface(onClick=onBadge,color=Color(0xFFFFF3D9),shape=RoundedCornerShape(16.dp),modifier=Modifier.fillMaxWidth().padding(top=12.dp)) {
                Row(Modifier.padding(horizontal=18.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically) {
                    Icon(Icons.Outlined.WorkspacePremium,null,tint=Gold,modifier=Modifier.size(25.dp))
                    Text("새 배지 ${vm.pendingBadges.size}개가 도착했어요",fontSize=15.sp,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f).padding(start=12.dp))
                    Text("열어보기",fontSize=14.sp,color=Ink,fontWeight=FontWeight.Bold)
                    Icon(Icons.Outlined.ChevronRight,null,tint=Ink)
                }
            }
        }
        Text(if(vm.consoleTest) "에뮬레이터 시험 기록 · 제한속도·벨트는 시험 조건 · 내 기록·주간 점수·배지·보상 제외" else "시제품 평가 기준 · 순위 보상은 85점 이상 유효 기록에 적용 · 완료 보상 0M",fontSize=11.sp,color=Muted,modifier=Modifier.padding(top=12.dp,bottom=2.dp))
    }
}

@Composable private fun ResultFeedback(report:ScoreReport) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("이번 주행 피드백",fontSize=19.sp,fontWeight=FontWeight.Bold,color=Ink)
        Text(if(report.usesDistanceBonus) "주행 가점과 회전 감점, 기록된 구간을 돌아봐요." else "감점 이유와 기록된 구간을 돌아봐요.",fontSize=13.sp,color=Muted)
        if(report.score!=null) {
            Surface(color=PaleMint,shape=RoundedCornerShape(14.dp)) {
                Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(7.dp)) {
                    if(report.usesDistanceBonus) {
                        Text("기본 60점 + 속도 ${scorePoints(report.speedPoints)}점 + 가감속 ${scorePoints(report.accelerationPoints)}점 − 회전 ${scorePoints(report.turnPenalty)}점",fontSize=14.sp,fontWeight=FontWeight.Bold,color=Ink)
                        Text("제한속도 초과 ${"%.0f".format(report.overLimitDistanceM)} m · 급가속 ${report.accelerationEvents}회 · 급감속 ${report.decelerationEvents}회",fontSize=14.sp,color=Ink)
                        Text("급가감속 구간은 가감속 가점에서 제외해요. 처음 10km까지 거리별 가점을 쌓고, 100점은 가점을 모두 얻었을 때 표시해요.",fontSize=12.sp,color=Muted)
                        Text("회전 방향지시등 미사용 −1점 · 반대 방향 −2점 · 최대 −10점. 판단할 자료가 없으면 감점하지 않아요.",fontSize=12.sp,color=Muted)
                    } else {
                        Text("제한속도 초과 ${"%.0f".format(report.overLimitDistanceM)} m · 약 ${"%.1f".format((100-report.compliance)*.7)}점 감점",fontSize=14.sp,fontWeight=FontWeight.Bold,color=Ink)
                        Text("급가속 ${report.accelerationEvents}회 · 약 ${"%.1f".format((100-report.smoothness)*.3)}점 감점",fontSize=14.sp,fontWeight=FontWeight.Bold,color=Ink)
                        Text("급감속은 감점하지 않아요. 최종 점수는 반올림돼요.",fontSize=12.sp,color=Muted)
                    }
                }
            }
        } else Text(scorePendingMessage(report),fontSize=14.sp,lineHeight=22.sp,color=Muted)
        if(report.events.isEmpty()) {
            Text(if(report.accelerationEvents>0 || report.overLimitDistanceM>0)
                "이전 기록에는 구간별 정보가 없어요. 다음 주행부터 발생 시점과 구간을 함께 보여드릴게요."
                else if(report.score!=null && report.usesDistanceBonus) "기록된 과속·급가감속 구간은 없어요. 회전 평가는 확인된 자료에만 적용해요."
                else if(report.score!=null) "감점에 해당하는 제한속도 초과와 급가속이 기록되지 않았어요."
                else "표시할 주행 구간이 아직 없어요.",fontSize=14.sp,lineHeight=22.sp,color=Ink)
        } else {
            Text("주요 주행 구간",fontSize=15.sp,fontWeight=FontWeight.Bold,color=Ink)
            Text("기록 시작 기준 시간과 누적 유효 거리예요. GPS 위치가 아닌 대략적인 구간입니다.",fontSize=12.sp,lineHeight=18.sp,color=Muted)
            report.events.take(8).forEach {event ->
                val label=when(event.type) {
                    DrivingEventType.OVERSPEED->"제한속도 초과"
                    DrivingEventType.RAPID_ACCELERATION->"급가속"
                    DrivingEventType.RAPID_DECELERATION->decelerationEventLabel(report)
                }
                Column(Modifier.fillMaxWidth().background(Paper,RoundedCornerShape(12.dp)).padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Text(label,fontSize=14.sp,fontWeight=FontWeight.Bold,color=Ink)
                    Text("${event.elapsedMs/60000}분 ${(event.elapsedMs/1000)%60}초 · 약 ${"%.1f".format(event.distanceM/1000)} km 부근",fontSize=12.sp,color=Muted)
                    Text(when(event.type) {
                        DrivingEventType.OVERSPEED->"제한 ${event.limitKph} km/h 구간에서 ${event.toKph.roundToInt()} km/h로 주행했어요."
                        DrivingEventType.RAPID_ACCELERATION->"${event.fromKph.roundToInt()} → ${event.toKph.roundToInt()} km/h로 빠르게 가속했어요."
                        DrivingEventType.RAPID_DECELERATION->"${event.fromKph.roundToInt()} → ${event.toKph.roundToInt()} km/h로 빠르게 감속했어요."
                    },fontSize=13.sp,lineHeight=20.sp,color=Ink)
                }
            }
        }
    }
}

@Composable private fun ResultRanking(vm:ChallengeViewModel,saved:CompletedDrive?=null) {
    val demo=saved?.demo ?: vm.demo
    val ranking=saved?.ranking ?: vm.ranking
    val missedDeadline=saved?.missedDeadline ?: vm.missedDeadline
    val earned=saved?.earned ?: vm.earned
    Surface(Modifier.fillMaxWidth().padding(top=12.dp),color=Color.White,shape=RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Text("함께 도착한 무사즈",fontSize=19.sp,fontWeight=FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                if(demo) Tag("시연 비교조 ${ranking.size}명",Paper,Muted)
            }
            Text(if(saved==null && vm.consoleTest) "실제 SDK 차속으로 점수 로직 시험" else if(demo) "같은 회차 · 챌린지 반경 1km" else "실제 서버 매칭은 준비 중이에요",fontSize=11.sp,color=Muted,modifier=Modifier.padding(top=7.dp,bottom=12.dp))
            if(ranking.isEmpty()) {
                Text(if(saved==null && vm.consoleTest) "시험 제한속도 ${vm.consoleSessionLimitKph}km/h · 벨트 착용 가정 · 내 기록·주간 점수·배지·보상에 반영하지 않아요." else if(!demo) "이번 기록은 개인 결과로 확인해요. 실제 경쟁·보상은 서버 연결 후 제공해요."
                    else if(missedDeadline) "마감 이후에 도착해 개인 기록만 표시해요."
                    else "보상 순위에 필요한 거리·데이터 조건을 채우지 못했어요.",fontSize=14.sp,lineHeight=22.sp,color=Muted)
            } else ranking.forEach {row ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if(row.id=="나") PaleMint else Color.Transparent).padding(horizontal=12.dp,vertical=6.dp),verticalAlignment=Alignment.CenterVertically) {
                    Text("%02d".format(row.rank),fontSize=15.sp,color=if(row.id=="나") Green else Muted,fontWeight=FontWeight.Bold,modifier=Modifier.width(40.dp))
                    Text(row.id,Modifier.weight(1f),fontSize=14.sp,fontWeight=if(row.id=="나") FontWeight.Bold else FontWeight.Normal)
                    Text("${row.score}점",fontSize=14.sp,fontWeight=FontWeight.Bold)
                    Text("${row.miles} M",Modifier.width(64.dp),textAlign=TextAlign.End,fontSize=13.sp,color=Green)
                }
            }
            HorizontalDivider(Modifier.padding(top=8.dp),color=Line)
            Row(Modifier.fillMaxWidth().padding(top=8.dp),verticalAlignment=Alignment.CenterVertically) {
                Column { Text("이번에 쌓인 마일리지",fontSize=12.sp,color=Muted);Text("+${earned} M",fontSize=25.sp,fontWeight=FontWeight.Bold,color=Green,modifier=Modifier.padding(top=2.dp)) }
                Spacer(Modifier.weight(1f))
                FilledTonalButton(onClick={vm.navigate(Screen.WALLET)},shape=RoundedCornerShape(12.dp),colors=ButtonDefaults.filledTonalButtonColors(containerColor=PaleMint,contentColor=Green)) { Text("내 마일리지");Icon(Icons.Outlined.ChevronRight,null,Modifier.size(18.dp)) }
            }
        }
    }
}

@Composable private fun History(
    vm:ChallengeViewModel,dateOrder:HistoryDateOrder,onDateOrder:(HistoryDateOrder)->Unit,
    selectedDate:LocalDate?,onDateSelected:(LocalDate?)->Unit,
    month:YearMonth,onMonthChange:(YearMonth)->Unit,
) {
    if(vm.completedDrives.isEmpty()) { EmptyState("첫 드라이브를 기다리고 있어요","참가한 이동의 주행습관과 결과를 이곳에 모아드릴게요.") { vm.navigate(Screen.HOME) };return }
    val records=remember(vm.completedDrives,dateOrder,selectedDate) { historyByDate(historyForDay(vm.completedDrives,selectedDate),dateOrder) }
    val recordDates=remember(vm.completedDrives) { vm.completedDrives.mapNotNull { historyDate(it.completedAtEpochMs) }.toSet() }
    val calendarWidth=368.dp
    val columnSpacing=20.dp
    val chipSpacing=8.dp
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(bottom=12.dp),horizontalArrangement=Arrangement.spacedBy(columnSpacing),verticalAlignment=Alignment.CenterVertically) {
            Text("저장된 주행 기록",Modifier.width(calendarWidth),color=Muted,fontSize=14.sp)
            Row(Modifier.weight(1f),verticalAlignment=Alignment.CenterVertically) {
                Text(selectedDate?.let { "${it.year}.${"%02d".format(it.monthValue)}.${"%02d".format(it.dayOfMonth)} · ${records.size}건" }
                    ?: "전체 ${records.size}건",color=Muted,fontSize=14.sp)
                Spacer(Modifier.weight(1f))
                Row(horizontalArrangement=Arrangement.spacedBy(chipSpacing)) {
                    FilterChip(selected=selectedDate==null,onClick={onDateSelected(null)},label={Text("전체 기록")})
                    Row(Modifier.selectableGroup(),horizontalArrangement=Arrangement.spacedBy(chipSpacing)) {
                        listOf(HistoryDateOrder.NEWEST_FIRST to "최신순",HistoryDateOrder.OLDEST_FIRST to "오래된순").forEach { (order,label) ->
                            FilterChip(selected=dateOrder==order,onClick={onDateOrder(order)},label={Text(label)},
                                leadingIcon=if(dateOrder==order) { { Icon(Icons.Outlined.Check,null,Modifier.size(18.dp)) } } else null)
                        }
                    }
                }
            }
        }
        Row(Modifier.weight(1f).fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(columnSpacing),verticalAlignment=Alignment.Top) {
            HistoryCalendar(month,selectedDate,recordDates,onMonthChange,{onDateSelected(it)},
                Modifier.width(calendarWidth).verticalScroll(rememberScrollState()))
            key(selectedDate,dateOrder) {
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                    if(records.isEmpty()) {
                        Surface(color=Color.White,shape=RoundedCornerShape(22.dp),modifier=Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(28.dp)) {
                                Text("이날은 주행 기록이 없어요",fontSize=20.sp,fontWeight=FontWeight.Bold,color=Ink)
                                Text("다른 날짜를 선택하거나 전체 기록을 확인해 주세요.",color=Muted,fontSize=14.sp,lineHeight=22.sp,modifier=Modifier.padding(top=8.dp))
                            }
                        }
                    }
                    records.forEach { record ->
                        key(record.session.id) {
                            Surface(
                                onClick = { vm.openHistoryRecord(record.session.id) },
                                color = Color.White, shape = RoundedCornerShape(18.dp),
                                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                            ) {
                                Row(
                                    Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                                ) {
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                        Text(record.name, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                                            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                        Text(historyDateLabel(record.completedAtEpochMs), color = Muted, fontSize = 13.sp)
                                        Text("${"%.1f".format(record.report.validDistanceM / 1000)} km  /  ${if (record.demo) "시연" else "차량 기록"}",
                                            color = Muted, fontSize = 13.sp)
                                    }
                                    Surface(color = PaleMint, shape = RoundedCornerShape(12.dp)) {
                                        Box(Modifier.widthIn(min = 72.dp).padding(horizontal = 10.dp, vertical = 9.dp),
                                            contentAlignment = Alignment.Center) {
                                            Text(record.report.score?.let { "${it}점" } ?: "미확정",
                                                fontSize = 20.sp, color = Ink, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                    Icon(Icons.Outlined.ChevronRight, null, tint = Muted)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun ConnectionScreen(vm:ChallengeViewModel) {
    Column(Modifier.fillMaxSize()) {
        Text("아래 값은 시연 데이터와 분리된 실제 SDK 응답입니다",color=Muted,fontSize=14.sp,modifier=Modifier.padding(bottom=20.dp))
        Row(horizontalArrangement=Arrangement.spacedBy(16.dp),modifier=Modifier.fillMaxWidth()) {
            InfoTile("Vehicle SDK","2.3.2",Modifier.weight(1f));InfoTile("NaviHelper SDK","3.0.5",Modifier.weight(1f));InfoTile("주차 확인",if(vm.live.parked) "P · UX 허용" else "미확인 / 이동",Modifier.weight(1f))
        }
        ConsoleScoringControls(vm)
        Column(Modifier.weight(1f).padding(top=20.dp).verticalScroll(rememberScrollState())) {
            val statuses=vm.live.statuses
            listOf("AAOS","NaviHelper","차량 속도","제한속도","주차 상태","Seat","Brake","TurnSignal","Steering","GPS","Odometer").forEach { key ->
                Row(Modifier.fillMaxWidth().padding(vertical=10.dp),verticalAlignment=Alignment.CenterVertically) {
                    Text(key,Modifier.width(160.dp),fontWeight=FontWeight.Bold,fontSize=14.sp)
                    Text(statuses[key] ?: "응답 대기",fontSize=14.sp,color=Muted)
                };HorizontalDivider(color=Line)
            }
        }
        Text("실제 입력 모드는 목적지·속도·제한속도·벨트·주차 상태가 수신되어야 진행됩니다. 실차 검증 및 앱 심사는 별도입니다.",fontSize=12.sp,color=Muted,modifier=Modifier.padding(top=15.dp))
    }
}

@Composable internal fun ExpressionMascot(mood:DriveMood,modifier:Modifier) {
    if(mood==DriveMood.NEUTRAL) {
        Mascot(0,modifier.semantics { contentDescription="편안한 표정으로 기다리는 무사즈" })
        return
    }
    val resource=when(mood) {
        DriveMood.TEARY -> R.drawable.musaz_sad
        DriveMood.CELEBRATING -> R.drawable.musaz_celebrate
        else -> R.drawable.musaz_encourage
    }
    Image(painterResource(resource),contentDescription=when(mood) {
        DriveMood.TEARY->"살짝 울먹이며 다음 운전을 응원하는 무사즈"
        DriveMood.CELEBRATING->"기뻐하는 무사즈"
        else->"다정하게 응원하는 무사즈"
    },modifier=modifier,contentScale=ContentScale.Fit)
}

@Composable internal fun ScoreDial(
    score: Int?, accent: Color, modifier: Modifier, numberSize: TextUnit = 50.sp, pendingLabel: String = "평가 대기",
) {
    val target=(score ?: 0)/100f
    val progress by animateFloatAsState(target,tween(700),label="result-score")
    Box(modifier.semantics {contentDescription="주행습관 점수 원"},contentAlignment=Alignment.Center) {
        Canvas(Modifier.fillMaxSize().padding(8.dp)) {
            val diameter=size.minDimension
            val origin=Offset((size.width-diameter)/2,(size.height-diameter)/2)
            val circle=androidx.compose.ui.geometry.Size(diameter,diameter)
            drawArc(Line,135f,270f,false,topLeft=origin,size=circle,style=Stroke(8.dp.toPx(),cap=StrokeCap.Round))
            if(score!=null) drawArc(accent,135f,270f*progress,false,topLeft=origin,size=circle,style=Stroke(8.dp.toPx(),cap=StrokeCap.Round))
        }
        Column(horizontalAlignment=Alignment.CenterHorizontally) {
            Text(score?.toString() ?: "—",fontSize=numberSize,fontWeight=FontWeight.Bold,letterSpacing=(-2).sp)
            Text(if(score==null) pendingLabel else "100점 만점",fontSize=14.sp,color=Muted)
        }
    }
}

@Composable private fun BadgeCelebration(badge:BadgeAward,remaining:Int,onNext:()->Unit) {
    Dialog(onDismissRequest=onNext,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.width(590.dp).heightIn(max=610.dp),shape=RoundedCornerShape(30.dp),color=Color(0xFFFFFBF4)) {
            Column(Modifier.padding(28.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                Tag("새 배지 획득",Color(0xFFFFE6A4),Color(0xFF795720))
                Text(badge.title,fontSize=31.sp,fontWeight=FontWeight.Bold,modifier=Modifier.padding(top=12.dp))
                // The shield mascot is reserved exclusively for this newly earned badge event.
                Mascot(3,Modifier.size(280.dp))
                Text(badge.description,fontSize=15.sp,color=Muted,textAlign=TextAlign.Center,lineHeight=24.sp,modifier=Modifier.padding(bottom=20.dp))
                Primary(if(remaining>1) "다음 배지 보기 (${remaining-1}개)" else "좋은 순간으로 간직하기",Icons.Outlined.Check,action=onNext)
            }
        }
    }
}

@Composable private fun Mascot(pose:Int,modifier:Modifier,animate:Boolean=false) {
    val bitmap=ImageBitmap.imageResource(if(pose==3) R.drawable.musaz_achievement else R.drawable.musaz_poses)
    val motion=rememberInfiniteTransition(label="musaz")
    val y by motion.animateFloat(0f,if(animate) -5f else 0f,infiniteRepeatable(tween(2200,easing=FastOutSlowInEasing),RepeatMode.Reverse),label="breathe")
    val offset=when(pose) {0->IntOffset(120,150);1->IntOffset(975,0);2->IntOffset(975,475);else->IntOffset.Zero}
    val crop=when(pose) {0->IntSize(800,850);1->IntSize(475,470);2->IntSize(475,510);else->IntSize(bitmap.width,bitmap.height)}
    Canvas(modifier.graphicsLayer { translationY=y }) {
        val ratio=min(size.width/crop.width,size.height/crop.height)
        val target=IntSize((crop.width*ratio).roundToInt(),(crop.height*ratio).roundToInt())
        drawImage(bitmap,srcOffset=offset,srcSize=crop,dstOffset=IntOffset(((size.width-target.width)/2).roundToInt(),((size.height-target.height)/2).roundToInt()),dstSize=target,filterQuality=FilterQuality.High)
    }
}

@Composable private fun RouteArt(progress:Float) {
    Canvas(Modifier.fillMaxWidth().height(300.dp)) {
        val w=size.width;val h=size.height
        val path=Path().apply { moveTo(w*.2f,h*.85f);cubicTo(w*.1f,h*.5f,w*.85f,h*.7f,w*.75f,h*.35f);cubicTo(w*.7f,h*.15f,w*.42f,h*.3f,w*.55f,h*.09f) }
        drawPath(path,Color(0xFF2D4564),style=Stroke(width=44f,cap=StrokeCap.Round))
        drawPath(path,Color(0xFF617991),style=Stroke(width=2f,pathEffect=PathEffect.dashPathEffect(floatArrayOf(8f,12f))))
        drawCircle(Ink,20f,Offset(w*.55f,h*.09f));drawCircle(Mint,11f,Offset(w*.55f,h*.09f))
        val measure=PathMeasure().apply { setPath(path,false) }
        val point=measure.getPosition(measure.length*progress.coerceIn(.02f,.93f))
        drawCircle(Mint.copy(alpha=.15f),43f,point);drawCircle(Mint,24f,point);drawCircle(Ink,8f,point)
    }
}

@Composable private fun Primary(label:String,icon:ImageVector=Icons.Outlined.ArrowForward,enabled:Boolean=true,action:()->Unit) {
    Button(onClick=action,enabled=enabled,modifier=Modifier.fillMaxWidth().height(56.dp),shape=RoundedCornerShape(14.dp),colors=ButtonDefaults.buttonColors(containerColor=Ink,contentColor=Color.White),contentPadding=PaddingValues(horizontal=20.dp)) {
        Text(label,fontSize=16.sp,fontWeight=FontWeight.Bold);Spacer(Modifier.weight(1f));Icon(icon,null,Modifier.size(21.dp))
    }
}
@Composable private fun Eyebrow(text:String) { Text(text,fontSize=11.sp,color=Green,fontWeight=FontWeight.Bold,letterSpacing=1.3.sp) }
@Composable private fun PageTitle(overline:String,title:String,description:String) { Column(Modifier.padding(top=7.dp,bottom=24.dp)) { Text(title,fontSize=32.sp,fontWeight=FontWeight.Bold,letterSpacing=(-.8).sp,modifier=Modifier.padding(top=10.dp));Text(description,fontSize=14.sp,color=Muted,modifier=Modifier.padding(top=9.dp)) } }
@Composable private fun Tag(text:String,bg:Color,fg:Color) { Text(text,Modifier.clip(RoundedCornerShape(8.dp)).background(bg).padding(horizontal=10.dp,vertical=6.dp),fontSize=11.sp,color=fg,fontWeight=FontWeight.Medium) }
@Composable private fun Tick(text:String) { Row(Modifier.padding(vertical=7.dp),verticalAlignment=Alignment.CenterVertically) { Icon(Icons.Outlined.CheckCircle,null,tint=Green,modifier=Modifier.size(19.dp));Text(text,fontSize=15.sp,modifier=Modifier.padding(start=10.dp)) } }
@Composable private fun InfoTile(label:String,value:String,modifier:Modifier) { Column(modifier.clip(RoundedCornerShape(16.dp)).background(Paper).padding(20.dp)) { Text(label,color=Muted,fontSize=12.sp);Text(value,fontSize=20.sp,fontWeight=FontWeight.Bold,modifier=Modifier.padding(top=10.dp)) } }
@Composable private fun SmallStat(label:String,value:String) { Column { Text(label,color=Muted,fontSize=11.sp);Text(value,fontSize=15.sp,fontWeight=FontWeight.SemiBold,modifier=Modifier.padding(top=7.dp)) } }
@Composable internal fun ScoreMetricBars(report:ScoreReport,gap:Dp=8.dp) {
    if(report.usesDistanceBonus) {
        Text("기본 60점 · 회전 −${scorePoints(report.turnPenalty)}점",fontSize=12.sp,lineHeight=16.sp,color=Muted)
        Spacer(Modifier.height(5.dp))
        BonusMetricBar("속도 가점",report.speedPoints,25)
        Spacer(Modifier.height(gap))
        BonusMetricBar("가감속 가점",report.accelerationPoints,15)
    } else {
        MetricBar("제한속도 준수",report.compliance.roundToInt(),"70% 반영")
        Spacer(Modifier.height(gap))
        MetricBar("부드러운 가속",report.smoothness.roundToInt(),"30% 반영")
    }
}
@Composable private fun BonusMetricBar(label:String,points:Double,maximum:Int) {
    Row {
        Text(label,fontSize=13.sp,lineHeight=18.sp,fontWeight=FontWeight.Medium)
        Spacer(Modifier.weight(1f))
        Text("+${scorePoints(points)} / ${maximum}점",fontSize=13.sp,lineHeight=18.sp,fontWeight=FontWeight.Bold,color=Green)
    }
    LinearProgressIndicator(progress={(points/maximum).toFloat().coerceIn(0f,1f)},
        modifier=Modifier.fillMaxWidth().padding(top=9.dp).height(6.dp).clip(CircleShape),color=Green,trackColor=PaleMint)
}
@Composable internal fun MetricBar(label:String,value:Int,note:String) { Row { Text(label,fontSize=13.sp,fontWeight=FontWeight.Medium);Text("  $note",fontSize=11.sp,color=Muted);Spacer(Modifier.weight(1f));Text("$value",fontSize=13.sp,fontWeight=FontWeight.Bold,color=Green) };LinearProgressIndicator(progress={value/100f},modifier=Modifier.fillMaxWidth().padding(top=9.dp).height(6.dp).clip(CircleShape),color=Green,trackColor=PaleMint) }

@Composable private fun EmptyState(title:String,description:String,action:()->Unit) {
    Row(Modifier.fillMaxSize().padding(vertical=42.dp),verticalAlignment=Alignment.CenterVertically,
        horizontalArrangement=Arrangement.spacedBy(36.dp)) {
        Column(Modifier.weight(1f).padding(start=24.dp)) {
            Tag("내 주행 기록",PaleMint,Green)
            Text(title,fontSize=32.sp,fontWeight=FontWeight.Bold,modifier=Modifier.padding(top=14.dp))
            Text(description,fontSize=16.sp,color=Muted,lineHeight=24.sp,modifier=Modifier.padding(top=10.dp,bottom=22.dp))
            Box(Modifier.widthIn(max=330.dp).fillMaxWidth()) { Primary("첫 챌린지 둘러보기",action=action) }
        }
        Box(Modifier.weight(.8f).fillMaxHeight().clip(RoundedCornerShape(28.dp))
            .background(Brush.verticalGradient(listOf(H1Style.PaleMint,Paper))),contentAlignment=Alignment.Center) {
            ExpressionMascot(DriveMood.ENCOURAGING,Modifier.fillMaxSize().padding(28.dp))
        }
    }
}
@Composable internal fun Modal(title:String,subtitle:String,dismiss:()->Unit,body:@Composable ColumnScope.()->Unit) {
    Dialog(onDismissRequest=dismiss,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.width(620.dp).heightIn(max=640.dp),shape=RoundedCornerShape(28.dp),color=Color.White) {
            Column(Modifier.padding(30.dp).verticalScroll(rememberScrollState())) {
                Row(verticalAlignment=Alignment.CenterVertically) { Text(title,Modifier.weight(1f),fontSize=26.sp,fontWeight=FontWeight.Bold);IconButton(onClick=dismiss) { Icon(Icons.Outlined.Close,"닫기") } }
                Text(subtitle,fontSize=14.sp,color=Muted,modifier=Modifier.padding(top=4.dp,bottom=24.dp));body()
            }
        }
    }
}
