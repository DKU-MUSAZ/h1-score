package ai.musaz.h1score

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ai.musaz.h1score.core.*
import java.time.Instant
import kotlinx.coroutines.delay
import kotlin.math.min
import kotlin.math.roundToInt

private val Ink=Color(0xFF152D4C)
private val Muted=Color(0xFF73818F)
private val Paper=Color(0xFFF5F6F8)
private val Line=Color(0xFFE2E7EB)
private val Mint=Color(0xFF74DFB2)
private val Green=Color(0xFF176B54)
private val PaleMint=Color(0xFFEAF7F0)
private val Gold=Color(0xFFBC8624)

@Composable fun H1App(vm: ChallengeViewModel) {
    MaterialTheme(colorScheme=lightColorScheme(primary=Ink,secondary=Green,background=Paper,surface=Color.White,onSurface=Ink)) {
        BoxWithConstraints(Modifier.fillMaxSize().background(Paper),contentAlignment=Alignment.Center) {
            val compact = maxWidth < maxHeight * 1.25f
            if (compact && (!vm.parked || vm.screen in listOf(Screen.HOME, Screen.JOURNEY))) {
                CompactNavigationPanel(vm)
                return@BoxWithConstraints
            }
            val current=LocalDensity.current
            val scale=min(maxWidth.value/1180f,maxHeight.value/680f)
            CompositionLocalProvider(LocalDensity provides Density(current.density*scale,current.fontScale)) {
                AppFrame(vm, Modifier.requiredSize(1180.dp,680.dp))
            }
        }
    }
}

@Composable private fun CompactNavigationPanel(vm: ChallengeViewModel) {
    val waiting = vm.phase == Phase.RESERVED
    Column(Modifier.fillMaxSize().background(Ink).verticalScroll(rememberScrollState()).padding(24.dp)) {
        Text("H1-score",color=Color.White,fontSize=23.sp,fontWeight=FontWeight.Bold)
        Spacer(Modifier.height(18.dp))
        Tag(when(vm.phase) {
            Phase.RESERVED -> "참가 예약 완료"
            Phase.RECORDING -> "주행 기록 중"
            Phase.AWAITING_PARK -> "주차 후 결과 공개"
            Phase.CANCELLED -> "기록 종료"
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
                !vm.activeJourney && !vm.demo && vm.hasLiveRoute -> {
                    var invite by remember { mutableStateOf(false) }
                    Button(onClick={invite=true}) { Text("참가 예약") }
                    if (invite) Modal("이번 드라이브 참가", vm.destinationName, { invite=false }) {
                        RoundConsent(vm, "참가 예약", vm.destinationInHangangGroup && vm.live.beltConditionMet) {
                            invite=false
                            vm.accept(it)
                        }
                    }
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
    LaunchedEffect(vm.parked) { if(!vm.parked) { destinations=false; simulator=false; badgeReveal=false } }
    LaunchedEffect(vm.notice) { if(vm.notice!=null) { kotlinx.coroutines.delay(3500); vm.notice=null } }
    val display=if(!vm.parked) Screen.JOURNEY else vm.screen
    Row(modifier.background(Paper)) {
        Column(Modifier.width(92.dp).fillMaxHeight().background(Color.White).padding(vertical=30.dp),horizontalAlignment=Alignment.CenterHorizontally) {
            Box(Modifier.size(52.dp).clip(RoundedCornerShape(17.dp)).background(Ink),contentAlignment=Alignment.Center) {
                Text("H1",fontSize=26.sp,fontWeight=FontWeight.Black,color=Color.White)
                Box(Modifier.align(Alignment.BottomCenter).padding(bottom=10.dp).width(22.dp).height(3.dp).background(Mint))
            }
            Spacer(Modifier.height(46.dp))
            NavItem("챌린지",Icons.Outlined.Route,display in listOf(Screen.HOME,Screen.JOURNEY,Screen.RESULT),vm.parked) { vm.navigate(if(vm.activeJourney) Screen.JOURNEY else Screen.HOME) }
            NavItem("내 기록",Icons.Outlined.BarChart,display==Screen.HISTORY,vm.parked) { vm.navigate(Screen.HISTORY) }
            NavItem("마일리지",Icons.Outlined.CardGiftcard,display==Screen.WALLET,vm.parked) { vm.navigate(Screen.WALLET) }
            NavItem("배지",Icons.Outlined.WorkspacePremium,display==Screen.BADGES,vm.parked) { vm.navigate(Screen.BADGES) }
            NavItem("마이페이지",Icons.Outlined.AccountCircle,display==Screen.PROFILE,vm.parked) { vm.navigate(Screen.PROFILE) }
            Spacer(Modifier.weight(1f))
            Text("MUSAZ",color=Muted,fontSize=10.sp,fontWeight=FontWeight.Bold,letterSpacing=1.5.sp)
        }
        Column(Modifier.weight(1f).padding(horizontal=32.dp)) {
            Row(Modifier.fillMaxWidth().height(86.dp),verticalAlignment=Alignment.CenterVertically) {
                Text("H1-score",fontWeight=FontWeight.ExtraBold,fontSize=27.sp,letterSpacing=(-1).sp)
                Text("  /  무사히, 함께",color=Muted,fontSize=14.sp)
                Spacer(Modifier.weight(1f))
                Icon(if(vm.parked) Icons.Outlined.LocalParking else Icons.Outlined.DirectionsCar,null,Modifier.size(17.dp),tint=Green)
                Text(if(vm.parked) " 주차 중" else " 주행 모드",color=Green,fontSize=13.sp,fontWeight=FontWeight.Medium)
                Spacer(Modifier.width(18.dp))
                Surface(onClick={ if(vm.parked) simulator=true },enabled=vm.parked,color=if(vm.demo) Color(0xFFF1EBE0) else PaleMint,shape=RoundedCornerShape(20.dp)) {
                    Text(if(vm.demo) "●  시연 모드" else "●  Pleos 연결",Modifier.padding(horizontal=14.dp,vertical=9.dp),fontSize=12.sp,color=if(vm.demo) Color(0xFF846B40) else Green,fontWeight=FontWeight.SemiBold)
                }
            }
            AnimatedContent(targetState=display,modifier=Modifier.weight(1f),transitionSpec={ (fadeIn(tween(220))+slideInHorizontally { it/25 }) togetherWith fadeOut(tween(120)) },label="screen") { screen ->
                when(screen) {
                    Screen.HOME -> Home(vm)
                    Screen.JOURNEY -> Journey(vm)
                    Screen.RESULT -> ResultScreen(vm) { badgeReveal=true }
                    Screen.WALLET -> Wallet(vm)
                    Screen.BADGES -> Badges(vm)
                    Screen.HISTORY -> History(vm)
                    Screen.CONNECTION -> ConnectionScreen(vm)
                    Screen.PROFILE -> MyProfileScreen(vm,display==Screen.PROFILE && vm.parked)
                }
            }
            Row(Modifier.fillMaxWidth().height(39.dp),verticalAlignment=Alignment.CenterVertically) {
                Text("무사즈가 함께하는 좋은 운전 습관",fontSize=11.sp,color=Muted)
                Spacer(Modifier.weight(1f))
                Text(if(vm.demo) "PROTOTYPE  ·  주행·참가자·보상은 시연 데이터" else "PLEOS LIVE  ·  차량 데이터 수신 확인용",fontSize=10.sp,color=Muted,letterSpacing=.3.sp)
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

@Composable private fun NavItem(label:String,icon:ImageVector,selected:Boolean,enabled:Boolean,onClick:()->Unit) {
    Column(Modifier.padding(bottom=13.dp).width(72.dp).clip(RoundedCornerShape(16.dp)).background(if(selected) PaleMint else Color.Transparent).clickable(enabled=enabled,onClick=onClick).padding(vertical=13.dp),horizontalAlignment=Alignment.CenterHorizontally) {
        Icon(icon,label,Modifier.size(25.dp),tint=if(selected) Green else Muted.copy(alpha=if(enabled) 1f else .4f))
        Text(label,Modifier.padding(top=7.dp),fontSize=11.sp,color=if(selected) Green else Muted,fontWeight=if(selected) FontWeight.Bold else FontWeight.Normal)
    }
}

@Composable private fun Home(vm:ChallengeViewModel) {
    Column(Modifier.fillMaxSize()) {
        Text("오늘도, 무사히 함께.",fontSize=38.sp,fontWeight=FontWeight.Bold,letterSpacing=(-1.8).sp)
        Text("같은 목적지로 향하는 우리. 마지막 10km를 좋은 습관으로 채워요.",
            fontSize=16.sp,color=Muted,modifier=Modifier.padding(top=5.dp,bottom=16.dp))
        Row(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(28.dp))
            .background(Brush.horizontalGradient(listOf(Color(0xFFE9EFE5),Color(0xFFE7EFF2))))) {
            ChallengeMap(vm,Modifier.weight(3f).fillMaxHeight())
            Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal=12.dp,vertical=20.dp),
                horizontalAlignment=Alignment.CenterHorizontally) {
                ExpressionMascot(DriveMood.ENCOURAGING,Modifier.weight(1f).fillMaxWidth())
                Text(if(vm.awaitingMapRoute) "내비 경로 확인 중…" else "무사즈와 함께 출발해요",
                    color=Ink,fontSize=15.sp,fontWeight=FontWeight.Bold,textAlign=TextAlign.Center)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top=22.dp,bottom=6.dp),verticalAlignment=Alignment.CenterVertically) {
            Icon(Icons.Outlined.CheckCircle,null,tint=Green,modifier=Modifier.size(18.dp));Text("  출발 전에 참가",fontSize=13.sp,fontWeight=FontWeight.Medium)
            StepLine();Text("마지막 10km 자동 기록",fontSize=13.sp,color=Muted)
            StepLine();Text("주차 후 결과와 보상",fontSize=13.sp,color=Muted)
            Spacer(Modifier.weight(1f));Icon(Icons.Outlined.VerifiedUser,null,Modifier.size(18.dp),tint=Green)
        }
    }
}

@Composable private fun ChallengeMap(vm: ChallengeViewModel, modifier: Modifier) {
    var query by remember { mutableStateOf("") }
    var zoom by remember { mutableFloatStateOf(1f) }
    var confirm by remember { mutableStateOf(false) }
    val application = androidx.compose.ui.platform.LocalContext.current.applicationContext as H1Application
    var showKakao by remember { mutableStateOf(application.kakaoMapEnabled) }
    var kakaoMap by remember { mutableStateOf<com.kakao.vectormap.KakaoMap?>(null) }
    val focus = LocalFocusManager.current
    val canJoin = vm.parked && !vm.activeJourney && !vm.awaitingMapRoute
    val key = query.filter(Char::isLetterOrDigit)
    val searchMatch = key.takeIf {
        it.length >= 2 && listOf("여의도한강공원", "서울영등포구여의동로330").any { name -> it in name }
    }
    val matches = query.isBlank() || searchMatch != null
    val now = rememberChallengeTime()
    val deadlineLabel = ChallengeRound.currentOrNext(now).closingLabel + " 마감"
    val participantCount = demoDestinations.first { it.name == "여의도한강공원" }.demoPeers
    Box(modifier.clipToBounds()) {
        if (showKakao) KakaoChallengeMap(participantCount, deadlineLabel, matches, searchMatch, { kakaoMap=it }, {
            kakaoMap=null
            showKakao=false
        }) {
            if (canJoin) { focus.clearFocus(); confirm=true }
        }
        if (!showKakao) Canvas(Modifier.fillMaxSize().pointerInput(Unit) {
            detectTransformGestures { _, _, factor, _ -> zoom=(zoom*factor).coerceIn(1f,2.25f) }
        }.graphicsLayer { scaleX=zoom; scaleY=zoom }) {
            val w = size.width
            val h = size.height
            for (n in 0..8) {
                val x = w * n / 8
                drawLine(Color.White.copy(alpha=.8f),Offset(x-w*.18f,0f),Offset(x+w*.22f,h),4.dp.toPx())
            }
            for (n in 1..7) {
                val y = h * n / 8
                drawLine(Color.White,Offset(0f,y),Offset(w,y-h*.10f),3.dp.toPx())
            }
            val river=Path().apply {
                moveTo(-w*.1f,h*.71f)
                cubicTo(w*.18f,h*.85f,w*.25f,h*.31f,w*.56f,h*.44f)
                cubicTo(w*.78f,h*.57f,w*.9f,h*.55f,w*1.1f,h*.29f)
            }
            drawPath(river,Color(0xFFA6D4E8),style=Stroke(width=66.dp.toPx()))
        }
        Column(Modifier.align(Alignment.TopStart).fillMaxWidth().padding(18.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Text("챌린지 지도",Modifier.weight(1f),fontSize=21.sp,fontWeight=FontWeight.Bold)
                Tag(if(showKakao) "카카오맵" else "시연 지도",Color.White,Muted)
            }
            OutlinedTextField(query,{query=it},enabled=vm.parked && !vm.activeJourney,singleLine=true,
                leadingIcon={Icon(Icons.Outlined.Search,null)},
                trailingIcon={if(query.isNotEmpty()) IconButton(onClick={query=""}) { Icon(Icons.Outlined.Close,"검색어 지우기") }},
                placeholder={Text("챌린지 장소 검색")},shape=RoundedCornerShape(28.dp),
                colors=OutlinedTextFieldDefaults.colors(focusedContainerColor=Color.White,unfocusedContainerColor=Color.White,
                    focusedBorderColor=Green,unfocusedBorderColor=Color.Transparent),
                modifier=Modifier.fillMaxWidth().padding(top=10.dp))
        }
        Surface(Modifier.align(Alignment.BottomEnd).padding(14.dp),shape=RoundedCornerShape(20.dp),color=Color.White) {
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
        if (matches && !showKakao) Column(Modifier.align(Alignment.Center).offset(y=52.dp),horizontalAlignment=Alignment.CenterHorizontally) {
            Text("여의도 한강공원",color=Ink,fontSize=17.sp,fontWeight=FontWeight.Bold,
                modifier=Modifier.background(Color.White,RoundedCornerShape(12.dp)).padding(horizontal=12.dp,vertical=6.dp))
            Spacer(Modifier.height(8.dp))
            Surface(onClick={focus.clearFocus();confirm=true},enabled=vm.parked && !vm.activeJourney && !vm.awaitingMapRoute,
                modifier=Modifier.size(118.dp).border(5.dp,Color.White,CircleShape),shape=CircleShape,color=Ink) {
                Column(verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally) {
                    Text("${participantCount}명",color=Color.White,fontSize=32.sp,fontWeight=FontWeight.Bold)
                    Text("참여 중 · 시연",color=Mint,fontSize=13.sp)
                }
            }
            Spacer(Modifier.height(8.dp))
            Tag(deadlineLabel,Color.White,Ink)
        }
        if (!matches) Surface(Modifier.align(Alignment.Center).padding(top=55.dp),shape=RoundedCornerShape(20.dp),color=Color.White) {
            Column(Modifier.widthIn(max=360.dp).padding(18.dp)) {
                Text("검색과 일치하는 챌린지가 없어요",color=Ink,fontWeight=FontWeight.Bold)
                Text("내장 내비에서 장소를 선택하고 길 안내를 시작할 수 있어요.",
                    color=Muted,fontSize=13.sp,modifier=Modifier.padding(top=6.dp,bottom=14.dp))
                Primary("내비에서 검색",enabled=vm.parked && !vm.activeJourney && !vm.awaitingMapRoute) {
                    focus.clearFocus()
                    vm.searchInNavigation(query)
                }
            }
        }
        if (!showKakao) Text("한강",color=Color(0xFF245774),fontSize=14.sp,fontWeight=FontWeight.Bold,
            modifier=Modifier.align(Alignment.CenterEnd).padding(end=18.dp))
        Text("마커를 눌러 챌린지 참가",fontSize=12.sp,color=Ink,
            modifier=Modifier.align(Alignment.BottomStart).padding(20.dp))
    }
    LaunchedEffect(vm.parked) { if(!vm.parked) confirm=false }
    if (confirm && vm.parked) Modal("이 목적지로 챌린지에 참가할까요?", "여의도 한강공원 · 참가 예시 4명", {confirm=false}) {
        Text("길 안내를 시작하고 남은 경로가 10km에 들어오면 자동으로 기록해요. 도착 후 주차하면 결과를 볼 수 있어요.",
            color=Muted,modifier=Modifier.padding(bottom=18.dp))
        val beltRequired = !vm.demo && !vm.live.beltConditionMet
        if (beltRequired) {
            Text(if(vm.live.belt == false) "운전석 벨트를 착용하면 참가할 수 있어요."
                else "운전석 벨트 상태를 확인하고 있어요.",color=Ink,
                modifier=Modifier.fillMaxWidth().background(PaleMint,RoundedCornerShape(12.dp)).padding(14.dp))
            Spacer(Modifier.height(12.dp))
        }
        if (beltRequired) {
            OutlinedButton(onClick={confirm=false;vm.navigateToChallengeWithoutJoining()},modifier=Modifier.fillMaxWidth()) {
                Text("길 안내만 열기")
            }
            Spacer(Modifier.height(8.dp))
        }
        if (!vm.demo && vm.hasLiveRoute && vm.destinationInHangangGroup && !beltRequired) {
            Text("현재 내비 목적지도 한강 챌린지 반경에 포함돼요.", color=Green)
        }
        RoundConsent(vm, if(vm.demo) "참가 예약 · 내비 시작" else "동의하고 내비 시작", !beltRequired,
            if (!vm.demo && vm.hasLiveRoute && vm.destinationInHangangGroup && !beltRequired) {
                { round -> confirm=false; vm.accept(round) }
            } else null) {
            confirm=false
            vm.joinMapChallenge(it)
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

@Composable private fun RoundConsent(
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
    val cancelled=vm.phase==Phase.CANCELLED
    val idle=vm.phase==Phase.IDLE
    Column(Modifier.fillMaxSize().clip(RoundedCornerShape(28.dp)).background(Ink).padding(34.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(if(waiting) Color(0xFFD8C394) else Mint,CircleShape))
            Text(if(idle) "차량 상태 확인 중" else if(cancelled) "기록 종료" else if(arrived) "도착 확인" else if(waiting) "참가 예약 완료" else "주행 기록 중",color=Color.White,fontSize=16.sp,fontWeight=FontWeight.Medium,modifier=Modifier.padding(start=10.dp))
            Spacer(Modifier.weight(1f))
            Text(vm.destinationName,color=Color(0xFFB7C7D8),fontSize=15.sp)
        }
        Row(Modifier.weight(1f),verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1.2f)) {
                Text(when {idle->"안전한 주차 후\n시작해 주세요.";cancelled->"기록을 종료했어요";arrived->"무사히 도착했어요.";waiting->"운전에만 집중하세요.";else->"좋은 흐름으로\n가고 있어요."},color=Color.White,fontSize=39.sp,lineHeight=52.sp,fontWeight=FontWeight.Bold,letterSpacing=(-1).sp)
                Text(when {idle->"P단·속도·차량 화면 제한을 확인하고 있어요.";cancelled->"주차 후 새 목적지로 참가해 주세요.";arrived->"P단으로 주차하면 결과가 열려요.";waiting->"남은 경로가 10km가 되면\n기록을 자동으로 시작할게요.";else->"순위와 보상은 주차한 뒤 알려드릴게요."},color=Color(0xFFAFBDCC),fontSize=17.sp,lineHeight=28.sp,modifier=Modifier.padding(top=19.dp))
                Spacer(Modifier.height(35.dp))
                Row(verticalAlignment=Alignment.Bottom) {
                    Text("%.1f".format(vm.remainingM/1000),color=Color.White,fontSize=66.sp,fontWeight=FontWeight.Light,letterSpacing=(-3).sp)
                    Text(" km 남음",color=Color(0xFFAFBDCC),fontSize=17.sp,modifier=Modifier.padding(bottom=14.dp))
                }
            }
            Box(Modifier.weight(1f).fillMaxHeight(),contentAlignment=Alignment.Center) { RouteArt(if(waiting) 0f else (1-vm.remainingM/10000).toFloat().coerceIn(0f,1f)) }
        }
        LinearProgressIndicator(progress={if(waiting) 0f else (1-vm.remainingM/10000).toFloat().coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth().height(5.dp).clip(CircleShape),color=Mint,trackColor=Color(0xFF304765))
        Row(Modifier.fillMaxWidth().padding(top=17.dp),verticalAlignment=Alignment.CenterVertically) {
            Text(if(waiting) "기록 시작까지 ${"%.1f".format(((vm.remainingM-10000)/1000).coerceAtLeast(0.0))} km" else if(arrived) "결과 공개 대기" else "안전한 곳까지 평소처럼 운전해 주세요",color=Color(0xFFAFBDCC),fontSize=13.sp)
            Spacer(Modifier.weight(1f))
            if(vm.parked && vm.activeJourney) {
                TextButton(onClick=vm::cancel) { Text("참가 취소",color=Color(0xFFAFBDCC)) }
                if(vm.demo) Button(onClick=vm::runDemo,shape=RoundedCornerShape(12.dp),colors=ButtonDefaults.buttonColors(containerColor=Mint,contentColor=Ink)) { Text("시연 주행 재생  →",fontWeight=FontWeight.Bold) }
                else Button(onClick=vm::openBuiltInNavigation,colors=ButtonDefaults.buttonColors(containerColor=Mint,contentColor=Ink)) { Text("내장 내비로 돌아가기") }
            }
        }
    }
}

@Composable private fun ResultScreen(vm:ChallengeViewModel,onBadge:()->Unit) {
    val report=vm.report
    if(report==null || !vm.resultUnlocked) { EmptyState("아직 기록이 없어요","챌린지에 참가하고 주차한 뒤 결과를 확인해 주세요.") { vm.navigate(Screen.HOME) }; return }
    val mood=DriveMood.forScore(report.score)
    val accent=if(mood==DriveMood.TEARY) Color(0xFFA8654C) else Green
    val tone=if(mood==DriveMood.TEARY) Color(0xFFFFF1EB) else if(mood==DriveMood.CELEBRATING) Color(0xFFEAF7F0) else Color(0xFFEDF3F8)
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment=Alignment.CenterVertically) {
            Column { Text(vm.destinationName,color=Muted,fontSize=13.sp); Text(when(mood) {
                DriveMood.CELEBRATING->"오늘의 드라이브, 멋졌어요!"
                DriveMood.ENCOURAGING->"좋은 습관을 하나씩 쌓아가요."
                DriveMood.TEARY->"다음엔 조금 더 여유롭게 가요."
                DriveMood.NEUTRAL->"무사히 도착했어요."
            },fontSize=29.sp,fontWeight=FontWeight.Bold,modifier=Modifier.padding(top=8.dp)) }
            Spacer(Modifier.weight(1f))
            Tag(if(vm.missedDeadline) "마감 이후 도착 · 순위 제외" else if(report.valid) "기록 확인 완료" else "개인 기록",PaleMint,Green)
        }
        Spacer(Modifier.height(18.dp))
        Row(Modifier.weight(1f),horizontalArrangement=Arrangement.spacedBy(20.dp)) {
            Column(Modifier.weight(.9f).fillMaxHeight().clip(RoundedCornerShape(24.dp)).background(Color.White).padding(20.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically) { Text("오늘의 주행습관 점수",color=Muted,fontSize=14.sp);Spacer(Modifier.weight(1f));Icon(Icons.Outlined.VerifiedUser,null,tint=Green,modifier=Modifier.size(20.dp)) }
                Row(Modifier.weight(1f).fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
                    ScoreDial(report.score,accent,Modifier.size(140.dp))
                    ExpressionMascot(mood,Modifier.size(148.dp))
                }
                Surface(color=tone,shape=RoundedCornerShape(14.dp),modifier=Modifier.fillMaxWidth().padding(bottom=12.dp)) {
                    Text(when {
                        report.score==null->"자료가 모이면 함께 돌아볼게요."
                        report.compliance<85->"다음엔 제한속도를 조금 더 살펴봐요."
                        report.smoothness<85->"출발할 때 가속 페달을 조금 더 부드럽게."
                        else->"차분하게 이어간 흐름, 다음에도 함께해요."
                    },Modifier.padding(10.dp),fontSize=12.sp,color=accent,lineHeight=19.sp)
                }
                if(report.score != null) MetricBar("제한속도 준수",report.compliance.roundToInt(),"70% 반영")
                Spacer(Modifier.height(10.dp));if(report.score != null) MetricBar("부드러운 가속",report.smoothness.roundToInt(),"30% 반영")
                HorizontalDivider(Modifier.padding(vertical=10.dp),color=Line)
                Row { SmallStat("유효 주행","%.1f km".format(report.validDistanceM/1000));Spacer(Modifier.weight(1f));SmallStat("데이터 수신","${(report.coverage*100).roundToInt()}%");Spacer(Modifier.weight(1f));SmallStat("급제동","감점 제외") }
            }
            Column(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(24.dp)).background(Color.White).padding(20.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically) { Text("함께 도착한 무사즈",fontSize=19.sp,fontWeight=FontWeight.Bold);Spacer(Modifier.weight(1f));if(vm.demo)Tag("시연 ${vm.ranking.size}인",Paper,Muted) }
                Text(if(vm.demo) "같은 회차 · 챌린지 반경 1km" else "실제 서버 매칭은 준비 중이에요",fontSize=11.sp,color=Muted,modifier=Modifier.padding(top=7.dp,bottom=16.dp))
                if(vm.ranking.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth(),contentAlignment=Alignment.Center) { Text(if(!vm.demo) "이번 기록은 개인 결과로 확인해요.\n실제 경쟁·보상은 서버 연결 후 제공해요." else if(vm.missedDeadline) "마감 이후에 도착해 개인 기록만 표시해요." else "보상 순위에 필요한 거리·데이터\n조건을 채우지 못했어요.",textAlign=TextAlign.Center,lineHeight=26.sp,color=Muted) }
                } else Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) { vm.ranking.forEach { row ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if(row.id=="나") PaleMint else Color.Transparent).padding(horizontal=12.dp,vertical=6.dp),verticalAlignment=Alignment.CenterVertically) {
                        Text("%02d".format(row.rank),fontSize=15.sp,color=if(row.id=="나") Green else Muted,fontWeight=FontWeight.Bold,modifier=Modifier.width(40.dp))
                        Text(row.id,Modifier.weight(1f),fontSize=14.sp,fontWeight=if(row.id=="나") FontWeight.Bold else FontWeight.Normal)
                        Text("${row.score}점",fontSize=14.sp,fontWeight=FontWeight.Bold)
                        Text("${row.miles} M",Modifier.width(64.dp),textAlign=TextAlign.End,fontSize=13.sp,color=Green)
                    }
                } }
                HorizontalDivider(Modifier.padding(top=8.dp),color=Line)
                Row(Modifier.fillMaxWidth().padding(top=8.dp),verticalAlignment=Alignment.CenterVertically) {
                    Column { Text("이번에 쌓인 마일리지",fontSize=12.sp,color=Muted);Text("+${vm.earned} M",fontSize=25.sp,fontWeight=FontWeight.Bold,color=Green,modifier=Modifier.padding(top=2.dp)) }
                    Spacer(Modifier.weight(1f));FilledTonalButton(onClick={vm.navigate(Screen.WALLET)},shape=RoundedCornerShape(12.dp),colors=ButtonDefaults.filledTonalButtonColors(containerColor=PaleMint,contentColor=Green)) { Text("내 마일리지");Icon(Icons.Outlined.ChevronRight,null,Modifier.size(18.dp)) }
                }
            }
        }
        if(vm.pendingBadges.isNotEmpty()) {
            Surface(onClick=onBadge,color=Color(0xFFFFF3D9),shape=RoundedCornerShape(16.dp),modifier=Modifier.fillMaxWidth().padding(top=12.dp)) {
                Row(Modifier.padding(horizontal=18.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically) {
                    Icon(Icons.Outlined.WorkspacePremium,null,tint=Gold,modifier=Modifier.size(25.dp))
                    Text("새 배지 ${vm.pendingBadges.size}개가 도착했어요",fontSize=15.sp,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f).padding(start=12.dp))
                    Text("열어보기",fontSize=14.sp,color=Ink,fontWeight=FontWeight.Bold)
                    Icon(Icons.Outlined.ChevronRight,null,tint=Ink)
                }
            }
        }
        Text("시제품 평가 기준 · 순위 보상은 85점 이상 유효 기록에 적용 · 완료 보상 0M",fontSize=11.sp,color=Muted,modifier=Modifier.padding(top=12.dp,bottom=2.dp))
    }
}

internal data class MileageReward(val id:String,val title:String,val cost:Int)
private val mileageRewards = listOf(
    MileageReward("wiper","와이퍼 교환",80),
    MileageReward("filter","에어컨 필터 교환",300),
    MileageReward("oil","엔진오일 교환",600),
)

@Composable private fun Wallet(vm:ChallengeViewModel) {
    var selected by remember { mutableStateOf<MileageReward?>(null) }
    val next = mileageRewards.firstOrNull { it.id !in vm.redeemedRewards }
    val available = mileageRewards.count { it.cost <= vm.balance && it.id !in vm.redeemedRewards }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text("마일리지 사용",fontSize=32.sp,fontWeight=FontWeight.Bold,modifier=Modifier.padding(bottom=16.dp))
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Ink).padding(24.dp),
            verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(30.dp)) {
            Column(Modifier.weight(1f)) {
                Text("내 시연 잔액",fontSize=14.sp,color=Mint)
                Text("${vm.balance} M",fontSize=46.sp,color=Color.White,fontWeight=FontWeight.Bold)
            }
            Column(Modifier.weight(1.25f)) {
                Text(if(available>0) "교환 가능한 혜택 ${available}개" else next?.let { "${it.title}까지 ${it.cost-vm.balance} M" } ?: "모든 시연 혜택을 받았어요",
                    color=Color.White,fontSize=18.sp,fontWeight=FontWeight.Bold)
                Text("시연에서만 사용하는 마일리지",color=Color(0xFFBDCCDA),fontSize=13.sp,modifier=Modifier.padding(top=8.dp))
            }
        }
        Text("차량 관리 혜택",fontSize=20.sp,fontWeight=FontWeight.Bold,modifier=Modifier.padding(top=19.dp,bottom=10.dp))
        mileageRewards.forEachIndexed { index, reward ->
            val received = reward.id in vm.redeemedRewards
            val short = (reward.cost-vm.balance).coerceAtLeast(0)
            Surface(onClick={selected=reward},color=Color.White,shape=RoundedCornerShape(16.dp),modifier=Modifier.fillMaxWidth().padding(bottom=8.dp)) {
                Row(Modifier.padding(horizontal=16.dp,vertical=11.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                    Box(Modifier.size(44.dp).background(PaleMint,RoundedCornerShape(12.dp)),contentAlignment=Alignment.Center) {
                        Icon(listOf(Icons.Outlined.WaterDrop,Icons.Outlined.Air,Icons.Outlined.Build)[index],null,tint=Green)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(reward.title,fontSize=17.sp,fontWeight=FontWeight.Bold)
                        LinearProgressIndicator(progress={if(received) 1f else (vm.balance.toFloat()/reward.cost).coerceIn(0f,1f)},
                            modifier=Modifier.widthIn(max=290.dp).fillMaxWidth().padding(top=7.dp),color=Green,trackColor=PaleMint)
                    }
                    Text("${reward.cost} M",fontSize=17.sp,fontWeight=FontWeight.Bold)
                    Tag(when { received -> "교환 완료"; short==0 -> "교환 가능"; else -> "${short} M 더 필요" },PaleMint,Green)
                    Icon(Icons.Outlined.ChevronRight,"혜택 상세",tint=Muted)
                }
            }
        }
    }
    selected?.takeIf { vm.parked }?.let { reward ->
        val received=reward.id in vm.redeemedRewards
        val short=(reward.cost-vm.balance).coerceAtLeast(0)
        Modal(reward.title,"시연 교환 · 실제 정비 쿠폰은 발급되지 않아요",{selected=null}) {
            Text("필요 마일리지 ${reward.cost} M   /   내 잔액 ${vm.balance} M",fontSize=18.sp,modifier=Modifier.padding(bottom=20.dp))
            Primary(when { received -> "시연 교환 완료"; short>0 -> "${short} M 더 필요"; else -> "${reward.cost} M으로 시연 교환" },
                enabled=!received && short==0) { vm.redeemDemoReward(reward) }
        }
    }
}

@Composable private fun Badges(vm:ChallengeViewModel) {
    Row(Modifier.fillMaxSize(),horizontalArrangement=Arrangement.spacedBy(26.dp)) {
        Column(Modifier.weight(.85f).fillMaxHeight().clip(RoundedCornerShape(26.dp)).background(Color(0xFFF3EEEA)).padding(26.dp)) {
            Tag("내 배지 컬렉션",Color.White,Green)
            Text("나의 무사한\n순간들.",fontSize=36.sp,lineHeight=46.sp,fontWeight=FontWeight.Bold,modifier=Modifier.padding(top=14.dp))
            ExpressionMascot(DriveMood.ENCOURAGING,Modifier.weight(1f).fillMaxWidth())
            Text("조금씩 모이는 좋은 운전의 흔적",fontSize=13.sp,color=Muted)
        }
        Column(Modifier.weight(1.1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
            PageTitle("BADGE COLLECTION","작은 성취를 모아봐요","배지를 위한 추가 이동은 필요 없어요")
            val earned=vm.ownedBadges.contains("first-drive")
            BadgeRow("첫 무사 도착","첫 유효 주행 기록을 완성했어요",earned,Icons.Outlined.VerifiedUser,Gold)
            BadgeRow("첫 발자국","${vm.ownedBadges.count { it.startsWith("visit:") }}곳에 남긴 첫 방문 기록",vm.ownedBadges.any { it.startsWith("visit:") },Icons.Outlined.LocationOn,Green)
            BadgeRow("무사 TOP 3","85점 이상으로 그룹 상위 3위",vm.ownedBadges.contains("top-three"),Icons.Outlined.EmojiEvents,Color(0xFF8263A4))
            BadgeRow("무사한 습관","준비 중 · 85점 이상 유효 기록 5회",false,Icons.Outlined.Eco,Green)
            BadgeRow("꾸준한 무사즈","준비 중 · 서로 다른 7일의 유효 기록",false,Icons.Outlined.CalendarToday,Ink)
        }
    }
}

@Composable private fun History(vm:ChallengeViewModel) {
    if(vm.report==null) { EmptyState("첫 드라이브를 기다리고 있어요","참가한 이동의 주행습관과 결과를 이곳에 모아드릴게요.") { vm.navigate(Screen.HOME) };return }
    Column {
        PageTitle("DRIVE JOURNAL","나의 드라이브","이번 실행에서 완성한 최근 기록")
        Surface(onClick={vm.navigate(Screen.RESULT)},color=Color.White,shape=RoundedCornerShape(22.dp),modifier=Modifier.fillMaxWidth()) {
            Row(Modifier.padding(28.dp),verticalAlignment=Alignment.CenterVertically) {
                Box(Modifier.size(60.dp).background(PaleMint,RoundedCornerShape(18.dp)),contentAlignment=Alignment.Center) { Icon(Icons.Outlined.Route,null,tint=Green) }
                Column(Modifier.weight(1f).padding(start=20.dp)) { Text(vm.destinationName,fontSize=24.sp,fontWeight=FontWeight.Bold);Text("유효 거리 ${"%.1f".format((vm.report?.validDistanceM ?:0.0)/1000)} km  ·  ${if(vm.demo) "시연 기록" else "SDK 기록"}",color=Muted,fontSize=14.sp,modifier=Modifier.padding(top=8.dp)) }
                Text("${vm.report?.score ?: "—"}",fontSize=40.sp,color=Green);Text(" 점",color=Muted);Icon(Icons.Outlined.ChevronRight,null,Modifier.padding(start=28.dp))
            }
        }
    }
}

@Composable private fun ConnectionScreen(vm:ChallengeViewModel) {
    Column(Modifier.fillMaxSize()) {
        PageTitle("DEVELOPER VIEW","Pleos 연결 확인","아래 값은 시연 데이터와 분리된 실제 SDK 응답입니다")
        Row(horizontalArrangement=Arrangement.spacedBy(16.dp),modifier=Modifier.fillMaxWidth()) {
            InfoTile("Vehicle SDK","2.3.2",Modifier.weight(1f));InfoTile("NaviHelper SDK","3.0.5",Modifier.weight(1f));InfoTile("주차 확인",if(vm.live.parked) "P · UX 허용" else "미확인 / 이동",Modifier.weight(1f))
        }
        Column(Modifier.weight(1f).padding(top=20.dp).verticalScroll(rememberScrollState())) {
            val statuses=vm.live.statuses
            listOf("AAOS","NaviHelper","차량 속도","제한속도","주차 상태","Seat","Brake","TurnSignal","Odometer").forEach { key ->
                Row(Modifier.fillMaxWidth().padding(vertical=10.dp),verticalAlignment=Alignment.CenterVertically) {
                    Text(key,Modifier.width(160.dp),fontWeight=FontWeight.Bold,fontSize=14.sp)
                    Text(statuses[key] ?: "응답 대기",fontSize=14.sp,color=Muted)
                };HorizontalDivider(color=Line)
            }
        }
        Text("실제 입력 모드는 목적지·속도·제한속도·벨트·주차 상태가 수신되어야 진행됩니다. 실차 검증 및 앱 심사는 별도입니다.",fontSize=12.sp,color=Muted,modifier=Modifier.padding(top=15.dp))
    }
}

@Composable private fun ExpressionMascot(mood:DriveMood,modifier:Modifier) {
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

@Composable private fun ScoreDial(score:Int?,accent:Color,modifier:Modifier) {
    val target=(score ?: 0)/100f
    val progress by animateFloatAsState(target,tween(700),label="result-score")
    Box(modifier,contentAlignment=Alignment.Center) {
        Canvas(Modifier.fillMaxSize().padding(8.dp)) {
            drawArc(Line,135f,270f,false,style=Stroke(8.dp.toPx(),cap=StrokeCap.Round))
            if(score!=null) drawArc(accent,135f,270f*progress,false,style=Stroke(8.dp.toPx(),cap=StrokeCap.Round))
        }
        Column(horizontalAlignment=Alignment.CenterHorizontally) {
            Text(score?.toString() ?: "—",fontSize=50.sp,fontWeight=FontWeight.Bold,letterSpacing=(-2).sp)
            Text(if(score==null) "평가 대기" else "100점 만점",fontSize=12.sp,color=Muted)
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
@Composable private fun StepLine() { Box(Modifier.padding(horizontal=19.dp).width(35.dp).height(1.dp).background(Line)) }
@Composable private fun Tick(text:String) { Row(Modifier.padding(vertical=7.dp),verticalAlignment=Alignment.CenterVertically) { Icon(Icons.Outlined.CheckCircle,null,tint=Green,modifier=Modifier.size(19.dp));Text(text,fontSize=15.sp,modifier=Modifier.padding(start=10.dp)) } }
@Composable private fun InfoTile(label:String,value:String,modifier:Modifier) { Column(modifier.clip(RoundedCornerShape(16.dp)).background(Paper).padding(20.dp)) { Text(label,color=Muted,fontSize=12.sp);Text(value,fontSize=20.sp,fontWeight=FontWeight.Bold,modifier=Modifier.padding(top=10.dp)) } }
@Composable private fun SmallStat(label:String,value:String) { Column { Text(label,color=Muted,fontSize=11.sp);Text(value,fontSize=15.sp,fontWeight=FontWeight.SemiBold,modifier=Modifier.padding(top=7.dp)) } }
@Composable private fun MetricBar(label:String,value:Int,note:String) { Row { Text(label,fontSize=13.sp,fontWeight=FontWeight.Medium);Text("  $note",fontSize=11.sp,color=Muted);Spacer(Modifier.weight(1f));Text("$value",fontSize=13.sp,fontWeight=FontWeight.Bold,color=Green) };LinearProgressIndicator(progress={value/100f},modifier=Modifier.fillMaxWidth().padding(top=9.dp).height(6.dp).clip(CircleShape),color=Green,trackColor=PaleMint) }

@Composable private fun BadgeRow(name:String,description:String,earned:Boolean,icon:ImageVector,color:Color) {
    Row(Modifier.fillMaxWidth().padding(bottom=12.dp).clip(RoundedCornerShape(16.dp)).background(Color.White).padding(15.dp),verticalAlignment=Alignment.CenterVertically) {
        Box(Modifier.size(49.dp).background(if(earned) color.copy(alpha=.12f) else Paper,RoundedCornerShape(14.dp)),contentAlignment=Alignment.Center) { Icon(icon,null,tint=if(earned) color else Muted.copy(alpha=.4f),modifier=Modifier.size(27.dp)) }
        Column(Modifier.weight(1f).padding(start=15.dp)) { Text(name,fontSize=16.sp,fontWeight=FontWeight.Bold,color=if(earned) Ink else Muted);Text(description,fontSize=11.sp,color=Muted,modifier=Modifier.padding(top=4.dp)) }
        Icon(if(earned) Icons.Outlined.CheckCircle else Icons.Outlined.Lock,null,tint=if(earned) Green else Line,modifier=Modifier.size(20.dp))
    }
}
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
            .background(Brush.verticalGradient(listOf(Color(0xFFE7EFF2),Paper))),contentAlignment=Alignment.Center) {
            ExpressionMascot(DriveMood.ENCOURAGING,Modifier.fillMaxSize().padding(28.dp))
        }
    }
}
@Composable private fun Modal(title:String,subtitle:String,dismiss:()->Unit,body:@Composable ColumnScope.()->Unit) {
    Dialog(onDismissRequest=dismiss,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.width(620.dp).heightIn(max=640.dp),shape=RoundedCornerShape(28.dp),color=Color.White) {
            Column(Modifier.padding(30.dp).verticalScroll(rememberScrollState())) {
                Row(verticalAlignment=Alignment.CenterVertically) { Text(title,Modifier.weight(1f),fontSize=26.sp,fontWeight=FontWeight.Bold);IconButton(onClick=dismiss) { Icon(Icons.Outlined.Close,"닫기") } }
                Text(subtitle,fontSize=14.sp,color=Muted,modifier=Modifier.padding(top=4.dp,bottom=24.dp));body()
            }
        }
    }
}
