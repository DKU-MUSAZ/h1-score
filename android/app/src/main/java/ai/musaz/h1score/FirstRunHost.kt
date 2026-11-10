package ai.musaz.h1score

import android.content.SharedPreferences
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import kotlin.math.min

private enum class SetupStep { WELCOME, CONSENT, NICKNAME, TOUR, DONE }
private class FirstRunStore(private val prefs: SharedPreferences) {
    var step by mutableStateOf(when {
        prefs.getBoolean("first-run-complete-v1",false) -> SetupStep.DONE
        prefs.getBoolean("first-run-nickname-v1",false) -> SetupStep.TOUR
        prefs.getBoolean("first-run-consent-v1",false) -> SetupStep.NICKNAME
        else -> SetupStep.WELCOME
    }); private set
    fun showConsent() {step=SetupStep.CONSENT}
    fun consent() {
        prefs.edit().putBoolean("first-run-consent-v1",true).putLong("first-run-consent-at",System.currentTimeMillis()).commit()
        step=SetupStep.NICKNAME
    }
    fun nickname() {prefs.edit().putBoolean("first-run-nickname-v1",true).commit();step=SetupStep.TOUR}
    fun finish() {prefs.edit().putBoolean("first-run-complete-v1",true).commit();step=SetupStep.DONE}
}

private val LocalTourTargets=staticCompositionLocalOf<MutableMap<String,Rect>?> {null}
@Composable internal fun Modifier.tutorialTarget(key:String): Modifier {
    val targets=LocalTourTargets.current ?: return this
    return onGloballyPositioned {targets[key]=it.boundsInRoot()}
}

private data class TourStep(val target:String,val title:String,val body:String,val screen:Screen)
private val tourSteps=listOf(
    TourStep("destination-search","목적지를 정해볼까요?","장소를 검색하면 내장 내비로 이어져요. 목적지에서 1km 안에 개최지가 있으면 가장 가까운 대회에 참가할 수 있어요.",Screen.HOME),
    TourStep("nav-내 기록","한 번의 운전을 돌아봐요","주차한 뒤 이번 주행의 점수와 습관을 확인해요. 완료 알림을 눌러 결과로 돌아올 수도 있어요.",Screen.HISTORY),
    TourStep("nav-마일리지","내 차를 위한 작은 선물","모은 마일리지와 와이퍼·필터·엔진오일 혜택을 여기서 확인해요. 현재 교환은 시연 기능이에요.",Screen.WALLET),
    TourStep("nav-배지","좋은 습관을 배지로 모아요","챌린지와 방문 미션으로 배지를 모아요. 어떤 조건을 달성했는지 한눈에 볼 수 있어요.",Screen.BADGES),
    TourStep("nav-마이페이지","나에게 맞게 준비해요","닉네임과 차량 상태를 확인하는 곳이에요. 준비가 끝났다면 무사즈와 함께 출발해요!",Screen.PROFILE),
)

@Composable internal fun FirstRunHost(vm: ChallengeViewModel, prefs: SharedPreferences, onExit: () -> Unit, onComplete: () -> Unit = {}) {
    val store=remember(prefs,vm.onboardingRevision) {FirstRunStore(prefs)}
    val targets=remember {mutableStateMapOf<String,Rect>()}
    var tourIndex by rememberSaveable(vm.onboardingRevision) {mutableIntStateOf(0)}
    if(store.step==SetupStep.DONE) {LaunchedEffect(Unit) {onComplete()};H1App(vm);return}
    BackHandler {onExit()}
    MaterialTheme(colorScheme=lightColorScheme(primary=H1Style.Ink,secondary=H1Style.Green,background=H1Style.Paper,surface=Color.White)) {
        if(store.step==SetupStep.TOUR) {
            val current=tourSteps[tourIndex]
            LaunchedEffect(current.screen,vm.parked) {if(vm.parked) vm.navigate(current.screen)}
            CompositionLocalProvider(LocalTourTargets provides targets) {
                Box(Modifier.fillMaxSize()) {
                    H1App(vm)
                    if(vm.parked) Spotlight(current,targets[current.target],tourIndex,
                        onBack={if(tourIndex>0) tourIndex--},
                        onNext={if(tourIndex==tourSteps.lastIndex) {store.finish();vm.navigate(Screen.HOME)} else tourIndex++},
                        onSkip={store.finish();vm.navigate(Screen.HOME)})
                    else ParkSetupNotice()
                }
            }
        } else {
            BoxWithConstraints(Modifier.fillMaxSize().background(H1Style.Paper),contentAlignment=Alignment.Center) {
                val density=LocalDensity.current
                val scale=min(maxWidth.value/1180f,maxHeight.value/680f)
                CompositionLocalProvider(LocalDensity provides Density(density.density*scale,density.fontScale)) {
                    Box(Modifier.requiredSize(1180.dp,680.dp),contentAlignment=Alignment.Center) {
                        SetupCard(vm,store,onExit)
                        if(!vm.parked) ParkSetupNotice()
                    }
                }
            }
        }
    }
}

@Composable private fun ParkSetupNotice() {
    Box(Modifier.fillMaxSize().background(H1Style.Ink.copy(alpha=.94f)).clickable(enabled=true,onClick={}),contentAlignment=Alignment.Center) {
        Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Text("주차 후 천천히 시작해요",fontSize=28.sp,fontWeight=FontWeight.Bold,color=Color.White)
            Text("P단과 정지 상태가 확인되면 설정을 이어갈게요.",fontSize=17.sp,color=Color.White)
        }
    }
}

@Composable private fun SetupCard(vm:ChallengeViewModel,store:FirstRunStore,onExit:()->Unit) {
    val keyboard=LocalSoftwareKeyboardController.current
    val focus=LocalFocusManager.current
    var agreed by rememberSaveable {mutableStateOf(false)}
    var nickname by rememberSaveable {mutableStateOf(vm.nickname.takeUnless {it=="무사즈 드라이버"}.orEmpty())}
    var details by remember {mutableStateOf(false)}
    LaunchedEffect(vm.parked) {if(!vm.parked) details=false}
    val valid=nickname.trim().let {it.isNotBlank() && it.codePointCount(0,it.length)<=16 && it.none(Char::isISOControl)}
    Surface(Modifier.width(990.dp).height(570.dp),shape=RoundedCornerShape(32.dp),color=Color.White,shadowElevation=2.dp) {
        Row {
            Column(Modifier.width(350.dp).fillMaxHeight().background(H1Style.Ink).padding(32.dp),verticalArrangement=Arrangement.SpaceBetween) {
                Column {
                    Text("안운잘부",color=Color.White,fontSize=20.sp,fontWeight=FontWeight.Bold)
                    Text("좋은 운전 습관,\n무사즈와 함께.",color=Color.White,fontSize=32.sp,lineHeight=42.sp,fontWeight=FontWeight.Bold,modifier=Modifier.padding(top=26.dp))
                }
                Image(painterResource(R.drawable.musaz_challenge_home),"반갑게 인사하는 무사즈",Modifier.fillMaxWidth().height(250.dp))
                Text("오늘도 무사히, 기분 좋게",color=Color(0xFFBCEADB),fontSize=15.sp)
            }
            Column(Modifier.weight(1f).fillMaxHeight().padding(32.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text(when(store.step) {SetupStep.WELCOME->"01  반가워요";SetupStep.CONSENT->"02  내 정보 안내";else->"03  나의 닉네임"},color=H1Style.Green,fontSize=14.sp,fontWeight=FontWeight.SemiBold)
                when(store.step) {
                    SetupStep.WELCOME -> {
                        Text("운전이 끝난 뒤,\n좋은 습관이 남도록",fontSize=30.sp,lineHeight=40.sp,fontWeight=FontWeight.Bold,color=H1Style.Ink)
                        Text("무사즈가 안전한 드라이브를 함께해요.\n시작하기 전에 딱 세 가지만 준비할게요.",fontSize=17.sp,lineHeight=27.sp,color=H1Style.Muted)
                        SetupFeature("01","목적지 주변 챌린지","가장 가까운 개최지에서 함께 달려요")
                        SetupFeature("02","주차 후 나의 기록","운전 습관을 차분히 돌아봐요")
                        SetupFeature("03","차곡차곡 모으는 즐거움","마일리지와 배지로 좋은 습관을 남겨요")
                        Spacer(Modifier.weight(1f))
                        Button(onClick=store::showConsent,enabled=vm.parked,modifier=Modifier.fillMaxWidth().height(52.dp),shape=RoundedCornerShape(15.dp)) {Text("무사즈와 시작하기",fontSize=17.sp)}
                    }
                    SetupStep.CONSENT -> {
                        Text("내 정보, 이렇게 사용해요",fontSize=30.sp,lineHeight=39.sp,fontWeight=FontWeight.Bold,color=H1Style.Ink)
                        Text("시제품 데이터 이용 안내",fontSize=14.sp,color=H1Style.Green)
                        Text("닉네임·바로가기·누적 기록은 이 기기에 저장해요. 차량 상태는 안전한 화면 표시와 주행 평가에 사용해요.",fontSize=17.sp,lineHeight=27.sp,color=H1Style.Ink)
                        TextButton(onClick={details=true},enabled=vm.parked,contentPadding=PaddingValues(0.dp)) {Text("이용 항목과 보관 방법 자세히 보기")}
                        Row(Modifier.fillMaxWidth().testTag("data-consent").toggleable(value=agreed,enabled=vm.parked,role=Role.Checkbox,onValueChange={agreed=it}).padding(vertical=4.dp),verticalAlignment=Alignment.CenterVertically) {
                            Checkbox(checked=agreed,onCheckedChange=null)
                            Text("[필수] 데이터 이용 안내를 확인하고 동의해요",fontSize=15.sp,modifier=Modifier.padding(start=10.dp))
                        }
                        Text("동의하지 않으면 설정을 마치지 않고 종료할 수 있어요.",fontSize=12.sp,color=H1Style.Muted)
                        Spacer(Modifier.weight(1f))
                        Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(onClick=onExit,modifier=Modifier.height(52.dp)) {Text("나중에")}
                            Button(onClick=store::consent,enabled=agreed && vm.parked,modifier=Modifier.weight(1f).height(52.dp),shape=RoundedCornerShape(15.dp)) {Text("동의하고 계속",fontSize=17.sp)}
                        }
                    }
                    SetupStep.NICKNAME -> {
                        Text("어떻게 불러드릴까요?",fontSize=30.sp,fontWeight=FontWeight.Bold,color=H1Style.Ink)
                        Text("별도 회원가입 없이 닉네임으로 시작해요.\n마이페이지에서 언제든 바꿀 수 있어요.",fontSize=17.sp,lineHeight=27.sp,color=H1Style.Muted)
                        OutlinedTextField(nickname,{nickname=it},enabled=vm.parked,modifier=Modifier.fillMaxWidth().testTag("first-nickname"),label={Text("닉네임")},placeholder={Text("예: 느긋한 무사즈")},singleLine=true,shape=RoundedCornerShape(16.dp),supportingText={Text("1~16자 · 다른 사람의 개인정보는 입력하지 마세요")})
                        Surface(color=H1Style.Paper,shape=RoundedCornerShape(16.dp)) {
                            Row(Modifier.fillMaxWidth().padding(16.dp),verticalAlignment=Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {Text("무사즈 음성 안내",fontWeight=FontWeight.SemiBold);Text("주행 시작과 결과를 음성으로 알려줘요",fontSize=12.sp,color=H1Style.Muted)}
                                Switch(checked=vm.voiceEnabled,enabled=vm.parked,onCheckedChange={vm.toggleVoice()})
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        Button(onClick={if(vm.updateNickname(nickname)) {keyboard?.hide();focus.clearFocus();store.nickname()}},enabled=valid && vm.parked,modifier=Modifier.fillMaxWidth().height(52.dp),shape=RoundedCornerShape(15.dp)) {Text("닉네임으로 시작",fontSize=17.sp)}
                    }
                    else -> Unit
                }
            }
        }
    }
    if(details && vm.parked) AlertDialog(onDismissRequest={details=false},containerColor=Color.White,title={Text("데이터 이용 안내")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("• 닉네임, 목적지 바로가기, 누적 주행 거리, 배지, 시연 잔액: 이 기기의 앱 저장소에 보관해요.")
        Text("• 속도·제한속도·목적지 정보: 사용자가 참가한 주행의 평가에 사용해요. 차량 P단·정지·안전벨트 등은 안전한 이용 상태 확인에 사용해요.")
        Text("• 앱의 별도 계정 서버 전송 기능은 없어요. 지도·내비 요청은 연결된 서비스에서 처리하며 해당 서비스의 정책이 적용돼요.")
        Text("• 앱 데이터 삭제 또는 제거 시 앱에 저장한 정보가 지워져요. 시스템 설정에서 권한과 알림을 변경할 수 있어요.")
        Text("현재 시제품 동작에 대한 안내예요. 출시용 개인정보 처리방침은 서비스 운영 정보 확정 후 별도로 제공됩니다.",fontSize=12.sp,color=H1Style.Muted)
    }},confirmButton={TextButton(onClick={details=false}) {Text("확인")}})
}

@Composable private fun SetupFeature(number:String,title:String,body:String) {
    Row(horizontalArrangement=Arrangement.spacedBy(14.dp),verticalAlignment=Alignment.CenterVertically) {
        Surface(color=H1Style.PaleMint,shape=RoundedCornerShape(12.dp)) {Text(number,Modifier.padding(12.dp),color=H1Style.Green,fontWeight=FontWeight.Bold)}
        Column {Text(title,fontSize=17.sp,fontWeight=FontWeight.SemiBold,color=H1Style.Ink);Text(body,fontSize=13.sp,color=H1Style.Muted,modifier=Modifier.padding(top=4.dp))}
    }
}

@Composable private fun Spotlight(step:TourStep,target:Rect?,index:Int,onBack:()->Unit,onNext:()->Unit,onSkip:()->Unit) {
    Box(Modifier.fillMaxSize().clickable(onClick={})) {
        Canvas(Modifier.fillMaxSize()) {
            val hole=target?.inflate(6.dp.toPx())
            val shade=Path().apply {fillType=PathFillType.EvenOdd;addRect(Rect(0f,0f,size.width,size.height));if(hole!=null) addRoundRect(RoundRect(hole,18.dp.toPx(),18.dp.toPx()))}
            drawPath(shade,Color(0xFF0A1628).copy(alpha=.82f))
            if(hole!=null) drawRoundRect(Color.White,topLeft=hole.topLeft,size=hole.size,cornerRadius=androidx.compose.ui.geometry.CornerRadius(18.dp.toPx()),style=Stroke(2.dp.toPx()))
        }
        Surface(Modifier.align(Alignment.CenterEnd).padding(32.dp).widthIn(max=410.dp),shape=RoundedCornerShape(24.dp),color=Color.White,shadowElevation=12.dp) {
            Column(Modifier.padding(28.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                Text("무사즈의 첫 안내  ${index+1} / ${tourSteps.size}",fontSize=14.sp,color=H1Style.Green,fontWeight=FontWeight.SemiBold)
                Text(step.title,fontSize=25.sp,lineHeight=33.sp,fontWeight=FontWeight.Bold,color=H1Style.Ink)
                Text(step.body,fontSize=17.sp,lineHeight=27.sp,color=H1Style.Muted)
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    TextButton(onClick=onSkip) {Text("건너뛰기",color=H1Style.Muted)}
                    Spacer(Modifier.weight(1f))
                    if(index>0) TextButton(onClick=onBack) {Text("이전")}
                    Button(onClick=onNext,shape=RoundedCornerShape(12.dp)) {Text(if(index==tourSteps.lastIndex) "무사즈와 출발하기" else "다음")}
                }
            }
        }
    }
}
