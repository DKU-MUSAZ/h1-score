package ai.musaz.h1score

import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val BadgeInk=H1Style.Ink
private val BadgeMuted=H1Style.Muted
private val BadgeLine=H1Style.Line
private fun points(value: Int)=String.format(Locale.KOREA,"%,d",value)
private enum class BadgeFamily(val title: String,val subtitle: String,val accent: Color,val tint: Color,val icon: ImageVector) {
    PARTICIPATION("참여","한 번씩, 차곡차곡",Color(0xFF237B66),Color(0xFFE9F5EE),Icons.Outlined.Route),
    SCORE("점수","좋은 운전의 기록",Color(0xFF4472AB),Color(0xFFEDF3FB),Icons.Outlined.VerifiedUser),
    RANK("순위","함께 달성한 성취",Color(0xFFAA792A),Color(0xFFFCF4E4),Icons.Outlined.EmojiEvents),
    ROUTINE("꾸준함","일상에 스며든 습관",Color(0xFF8270AC),Color(0xFFF2EEF8),Icons.Outlined.CalendarMonth)
}
private enum class TrophyTier(val title: String,val accent: Color,val image: Int) {
    BRONZE("브론즈",Color(0xFFAC693A),R.drawable.musaz_trophy_bronze_raised_large),
    SILVER("실버",Color(0xFF697D91),R.drawable.musaz_trophy_silver_raised_large),
    GOLD("골드",Color(0xFFAB7926),R.drawable.musaz_trophy_gold_raised_large),
    PLATINUM("플래티넘",Color(0xFF337F89),R.drawable.musaz_trophy_platinum_raised_large)
}
private data class CollectionBadge(val id: String,val family: BadgeFamily,val title: String,val condition: String,
    val target: Int,val reward: Int,val previewProgress: Int,val unit: String="일",val explanation: String="각 배지는 한 달에 한 번 받을 수 있어요.",val tier: TrophyTier?=null)
private val collectionBadges=listOf(
    CollectionBadge("join-5",BadgeFamily.PARTICIPATION,"첫 발걸음","유효한 대회 5일 참여",5,200,12),
    CollectionBadge("join-10",BadgeFamily.PARTICIPATION,"꾸준한 드라이버","유효한 대회 10일 참여",10,300,12),
    CollectionBadge("join-15",BadgeFamily.PARTICIPATION,"출근길 동반자","유효한 대회 15일 참여",15,500,12),
    CollectionBadge("join-20",BadgeFamily.PARTICIPATION,"이달의 무사즈","유효한 대회 20일 참여",20,1000,12),
    CollectionBadge("score-80-5",BadgeFamily.SCORE,"차분한 출발","80점 이상 5일",5,100,10),
    CollectionBadge("score-80-10",BadgeFamily.SCORE,"안정적인 흐름","80점 이상 10일",10,200,10),
    CollectionBadge("score-90-5",BadgeFamily.SCORE,"좋은 습관","90점 이상 5일",5,200,6),
    CollectionBadge("score-90-10",BadgeFamily.SCORE,"믿음직한 드라이버","90점 이상 10일",10,400,6),
    CollectionBadge("score-95-5",BadgeFamily.SCORE,"정교한 운전","95점 이상 5일",5,300,2),
    CollectionBadge("rank-90",BadgeFamily.RANK,"순위 첫걸음","상위 90% 이내 5일",5,50,11,tier=TrophyTier.BRONZE),
    CollectionBadge("rank-80",BadgeFamily.RANK,"순위 적응 완료","상위 80% 이내 10일",10,100,10,tier=TrophyTier.BRONZE),
    CollectionBadge("rank-50",BadgeFamily.RANK,"중위권 돌파","상위 50% 이내 5일",5,150,6,tier=TrophyTier.SILVER),
    CollectionBadge("rank-30",BadgeFamily.RANK,"꾸준한 상위권","상위 30% 이내 5일",5,250,4,tier=TrophyTier.SILVER),
    CollectionBadge("rank-10-3",BadgeFamily.RANK,"상위권 단골","상위 10% 이내 3일",3,300,3,tier=TrophyTier.GOLD),
    CollectionBadge("rank-10-10",BadgeFamily.RANK,"이달의 에이스","상위 10% 이내 10일",10,700,3,tier=TrophyTier.PLATINUM),
    CollectionBadge("routine-week",BadgeFamily.ROUTINE,"주간 루틴","한 주에 서로 다른 3일 참여",3,50,3,"일", "월요일~일요일 중 3일 참여하면 주당 50P. 월 최대 4회이며, 보상은 일요일이 속한 달에 집계해요."),
    CollectionBadge("routine-month",BadgeFamily.ROUTINE,"한 달 동행","서로 다른 4개 주에 참여",4,200,3,"주"),
    CollectionBadge("routine-score",BadgeFamily.ROUTINE,"꾸준한 좋은 흐름","서로 다른 3개 주에서 90점 이상",3,200,2,"주")
)

/** Policy preview only. This screen never credits a ledger or converts legacy badges into monthly awards. */
@Composable internal fun BadgeCollectionScreen(legacyOwned: Set<String>,onChallenge: ()->Unit) {
    var family by remember { mutableStateOf(BadgeFamily.PARTICIPATION) }
    var preview by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<CollectionBadge?>(null) }
    var rules by remember { mutableStateOf(false) }
    var reward by remember { mutableStateOf(false) }
    var legacy by remember { mutableStateOf(false) }
    val month=LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).format(DateTimeFormatter.ofPattern("yyyy년 M월"))
    fun progress(b: CollectionBadge)=if(preview)b.previewProgress.coerceAtMost(b.target) else 0
    val earned=collectionBadges.filter { progress(it)>=it.target }
    // Example includes three weekly routine rewards; no production award is persisted here.
    val monthlyPoints=if(preview)earned.sumOf { it.reward }+100 else 0
    val balance=if(preview)monthlyPoints+1500 else 0
    val visible=collectionBadges.filter { it.family==family }
    val next=visible.filter { progress(it)<it.target }.maxByOrNull { progress(it).toFloat()/it.target }
    val scroll=rememberLazyGridState()
    LaunchedEffect(family) { scroll.scrollToItem(0) }
    CompositionLocalProvider(LocalTextStyle provides androidx.compose.ui.text.TextStyle(color=BadgeInk,fontSize=14.sp)) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(bottom=16.dp),verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("나의 배지 여정",fontSize=32.sp,fontWeight=FontWeight.Bold,color=BadgeInk,letterSpacing=(-.7).sp)
                Text("네 가지 성취, 하나로 이어지는 좋은 습관",fontSize=14.sp,color=BadgeMuted,modifier=Modifier.padding(top=4.dp))
            }
            Text(month,color=BadgeMuted,fontSize=12.sp,modifier=Modifier.padding(end=14.dp))
            FilterChip(selected=preview,onClick={preview=!preview},label={Text(if(preview)"예시 기록 보는 중" else "달성 예시 보기",fontSize=12.sp)},
                leadingIcon={Icon(Icons.Outlined.AutoAwesome,null,Modifier.size(15.dp))},
                colors=FilterChipDefaults.filterChipColors(selectedContainerColor=H1Style.PaleMint,selectedLabelColor=H1Style.Green,selectedLeadingIconColor=H1Style.Green))
        }
        Row(Modifier.weight(1f),horizontalArrangement=Arrangement.spacedBy(20.dp)) {
            Column(Modifier.weight(1f).clip(RoundedCornerShape(24.dp)).background(Color.White).padding(12.dp)) {
                Row(horizontalArrangement=Arrangement.spacedBy(9.dp)) {
                    BadgeFamily.entries.forEach { item ->
                        val count=collectionBadges.count { it.family==item && it in earned }
                        Surface(onClick={family=item},shape=RoundedCornerShape(16.dp),color=if(family==item)H1Style.PaleMint else H1Style.Paper,
                            border=androidx.compose.foundation.BorderStroke(1.dp,if(family==item)H1Style.Green.copy(alpha=.25f) else Color.Transparent),modifier=Modifier.weight(1f)) {
                            Column(Modifier.padding(10.dp)) {
                                Row(verticalAlignment=Alignment.CenterVertically) {
                                    Icon(item.icon,null,Modifier.size(19.dp),tint=item.accent)
                                    Text(item.title,Modifier.padding(start=7.dp),fontSize=14.sp,fontWeight=FontWeight.Bold,color=BadgeInk)
                                }
                                Text("${count} / ${collectionBadges.count { it.family==item }}개",fontSize=11.sp,color=BadgeMuted,modifier=Modifier.padding(top=8.dp))
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top=12.dp,bottom=10.dp),verticalAlignment=Alignment.CenterVertically) {
                    Column {
                        Text(family.subtitle,fontSize=16.sp,fontWeight=FontWeight.Bold,color=BadgeInk)
                        Text(when(family) {
                            BadgeFamily.PARTICIPATION -> "무사즈와 한 걸음씩, 나의 길을 만들어요"
                            BadgeFamily.SCORE -> "차분한 운전마다, 믿음의 체크를 채워요"
                            BadgeFamily.RANK -> "브론즈 → 실버 → 골드 → 플래티넘"
                            BadgeFamily.ROUTINE -> "일상 속 작은 약속을, 달력에 남겨요"
                        },fontSize=10.sp,color=BadgeMuted,modifier=Modifier.padding(top=3.dp))
                    }
                    Spacer(Modifier.weight(1f))
                    Text("${earned.count { it.family==family }}개 달성",fontSize=11.sp,color=family.accent)
                }
                LazyVerticalGrid(columns=GridCells.Fixed(3),state=scroll,modifier=Modifier.weight(1f),
                    horizontalArrangement=Arrangement.spacedBy(10.dp),verticalArrangement=Arrangement.spacedBy(10.dp),contentPadding=PaddingValues(bottom=8.dp)) {
                    items(visible,key={it.id}) { badge ->
                        CollectionCard(badge,progress(badge),onClick={selected=badge})
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top=5.dp),verticalAlignment=Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Info,null,Modifier.size(13.dp),tint=BadgeMuted)
                    Text("월간 배지 미리보기 · 실제 포인트는 지급되지 않아요",fontSize=10.sp,color=BadgeMuted,modifier=Modifier.padding(start=5.dp))
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick={legacy=true},contentPadding=PaddingValues(0.dp),modifier=Modifier.height(25.dp)) { Text("이전 배지 ${legacyOwned.size}개",fontSize=10.sp) }
                }
            }
            Column(Modifier.width(257.dp).fillMaxHeight().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(BadgeInk).padding(20.dp)) {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Text("이달의 성취",fontSize=12.sp,color=Color(0xFFBDCCDA));Spacer(Modifier.weight(1f))
                        Text("${earned.size} / 18",fontSize=12.sp,fontWeight=FontWeight.Bold,color=H1Style.Mint)
                    }
                    Row(verticalAlignment=Alignment.CenterVertically,modifier=Modifier.fillMaxWidth().padding(top=4.dp,bottom=7.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(points(monthlyPoints),fontSize=31.sp,fontWeight=FontWeight.Bold,color=Color.White,letterSpacing=(-1).sp)
                            Text("이달의 배지 포인트",fontSize=10.sp,color=Color(0xFFBDCCDA),modifier=Modifier.padding(top=3.dp))
                        }
                        BadgeEmblem(family,true,Modifier.size(79.dp),tier=earned.lastOrNull { it.family==family }?.tier)
                    }
                    LinearProgressIndicator(progress={monthlyPoints/4000f},color=H1Style.Mint,trackColor=Color(0xFF344B66),modifier=Modifier.fillMaxWidth().height(5.dp).clip(CircleShape))
                    Text("월간 배지 보너스 / 최대 4,000P",fontSize=10.sp,color=Color(0xFFBDCCDA),modifier=Modifier.padding(top=8.dp))
                    HorizontalDivider(Modifier.padding(vertical=13.dp),color=Color(0xFF344B66))
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Icon(Icons.Outlined.EmojiEvents,null,Modifier.size(17.dp),tint=H1Style.Gold)
                        Text("대회 상위 10%는 별도 +500P",fontSize=10.sp,color=Color.White,modifier=Modifier.padding(start=6.dp))
                    }
                }
                Surface(onClick={selected=next},enabled=next!=null,shape=RoundedCornerShape(16.dp),color=Color.White) {
                    Column(Modifier.fillMaxWidth().padding(15.dp)) {
                        Text("다음 작은 목표",fontSize=11.sp,color=BadgeMuted)
                        if(next!=null) {
                            Row(verticalAlignment=Alignment.CenterVertically,modifier=Modifier.padding(top=8.dp)) {
                                BadgeEmblem(next.family,false,Modifier.size(45.dp),tier=next.tier)
                                Column(Modifier.weight(1f).padding(start=8.dp)) {
                                    Text(next.title,fontSize=13.sp,fontWeight=FontWeight.Bold,color=BadgeInk)
                                    Text("${next.target-progress(next)}${next.unit} 더 채우면 +${points(next.reward)}P",fontSize=10.sp,color=family.accent,modifier=Modifier.padding(top=3.dp))
                                }
                            }
                        } else Text("이번 유형을 모두 모았어요",fontSize=13.sp,color=family.accent,modifier=Modifier.padding(top=12.dp))
                    }
                }
                Surface(onClick={reward=true},shape=RoundedCornerShape(16.dp),color=Color.White) {
                    Column(Modifier.fillMaxWidth().padding(15.dp)) {
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            Icon(Icons.Outlined.LocalCarWash,null,Modifier.size(20.dp),tint=Color(0xFF237B66))
                            Text("운전의 보람을, 차량 관리로",fontSize=11.sp,fontWeight=FontWeight.Bold,color=BadgeInk,modifier=Modifier.padding(start=6.dp))
                        }
                        Text("엔진오일 교환권",fontSize=15.sp,fontWeight=FontWeight.Bold,color=BadgeInk,modifier=Modifier.padding(top=9.dp))
                        Text("목표 50,000P · 제공 준비 중",fontSize=10.sp,color=BadgeMuted,modifier=Modifier.padding(top=4.dp))
                        Row(Modifier.fillMaxWidth().padding(top=10.dp),verticalAlignment=Alignment.CenterVertically) {
                            Text(if(preview)"예시 잔액 ${points(balance)}P" else "배지와 대회 포인트를 함께 모아요",fontSize=10.sp,color=Color(0xFF237B66))
                            Spacer(Modifier.weight(1f));Icon(Icons.Outlined.ArrowForward,null,Modifier.size(15.dp),tint=Color(0xFF237B66))
                        }
                    }
                }
                TextButton(onClick={rules=true},modifier=Modifier.fillMaxWidth().height(29.dp),contentPadding=PaddingValues(0.dp)) {
                    Text("배지·포인트 지급 기준",fontSize=11.sp);Icon(Icons.Outlined.ChevronRight,null,Modifier.size(15.dp))
                }
            }
        }
    }
    selected?.let { badge ->
        val value=progress(badge);val complete=value>=badge.target
        AlertDialog(onDismissRequest={selected=null},title={
            Column(horizontalAlignment=Alignment.CenterHorizontally,modifier=Modifier.fillMaxWidth()) {
                BadgeEmblem(badge.family,complete,Modifier.size(128.dp),tier=badge.tier)
                Text(badge.title,fontSize=23.sp,fontWeight=FontWeight.Bold,modifier=Modifier.padding(top=12.dp))
            }
        },text={Column {
            Text("${badge.family.title} 컬렉션${badge.tier?.let { " · ${it.title} 트로피" } ?: ""} · $month",color=badge.family.accent,fontSize=12.sp)
            Text(badge.condition,fontSize=17.sp,fontWeight=FontWeight.SemiBold,modifier=Modifier.padding(top=12.dp))
            Text("$value / ${badge.target}${badge.unit} · ${if(complete)"달성" else "진행 중"}",fontSize=12.sp,color=BadgeMuted,modifier=Modifier.padding(vertical=10.dp))
            LinearProgressIndicator(progress={value.toFloat()/badge.target},color=badge.family.accent,trackColor=badge.family.tint,modifier=Modifier.fillMaxWidth())
            Text("달성 보너스 +${points(badge.reward)}P",fontSize=19.sp,fontWeight=FontWeight.Bold,color=badge.family.accent,modifier=Modifier.padding(top=18.dp,bottom=10.dp))
            Text(badge.explanation,fontSize=12.sp,color=BadgeMuted)
            Text("같은 기록으로 다른 유형도 함께 채울 수 있어요. 배지 보너스는 월 합계 4,000P까지 지급해요.",fontSize=12.sp,color=BadgeMuted,modifier=Modifier.padding(top=8.dp))
            Text("현재는 화면 미리보기이며 포인트를 지급하지 않습니다.",fontSize=11.sp,color=BadgeMuted,modifier=Modifier.padding(top=12.dp))
        }},confirmButton={TextButton(onClick={selected=null}){Text("확인")}},dismissButton={if(!complete)TextButton(onClick={selected=null;onChallenge()}){Text("챌린지 둘러보기")}})
    }
    if(rules) AlertDialog(onDismissRequest={rules=false},title={Text("함께 채우는 네 가지 성취")},text={Column(Modifier.verticalScroll(rememberScrollState())) {
        Text("하루 한 번의 유효한 대회 완주를 인정해요. 실격·취소·무효 기록은 제외해요.\n\n참여·점수·순위·꾸준함 조건은 동시에 채울 수 있어요. 95점은 90점·80점 조건에도, 상위 10%는 더 넓은 순위 조건에도 반영돼요.\n\n월간 배지 보너스는 최대 4,000P. 상한을 넘어도 배지는 계속 모을 수 있고, 포인트는 남은 한도만큼 지급해요. 대회 상위 10%의 500P는 별도예요.\n\n순위 배지는 유효 참가자 10명 이상부터 적용해요. 동점자는 공동 순위로 인정해요.\n\n매월 한국시간 1일에 집계를 새로 시작하고, 획득한 배지는 달성 월과 함께 남아요. 주간 루틴은 월 최대 4회, 나머지 월간 배지는 각각 월 1회예요.\n\n이 화면은 정책 미리보기이며 실제 지급 기능은 아직 연결하지 않았어요.",fontSize=13.sp,lineHeight=22.sp)
    }},confirmButton={TextButton(onClick={rules=false}){Text("확인")}})
    if(reward) AlertDialog(onDismissRequest={reward=false},title={Text("작은 성취가 차량 관리 혜택으로")},text={Column {
        Text("엔진오일 교환권 · 목표 50,000P",fontSize=18.sp,fontWeight=FontWeight.Bold,color=BadgeInk)
        Text("대회 포인트 + 월간 배지 보너스",fontSize=13.sp,color=BadgeMuted,modifier=Modifier.padding(top=12.dp))
        if(preview)Text("예시: 대회 1,500P + 배지 ${points(monthlyPoints)}P = ${points(balance)}P",fontSize=13.sp,color=BadgeInk,modifier=Modifier.padding(top=8.dp))
        Text("교환권은 제공 준비 중입니다. 실제 제휴·사용처·지원 차종·추가금 조건은 확정 후 안내합니다.",fontSize=13.sp,color=BadgeMuted,modifier=Modifier.padding(top=16.dp))
    }},confirmButton={TextButton(onClick={reward=false}){Text("확인")}})
    if(legacy) AlertDialog(onDismissRequest={legacy=false},title={Text("이전 여정의 배지")},text={Column(Modifier.verticalScroll(rememberScrollState())) {
        if(legacyOwned.isEmpty())Text("아직 이전 배지가 없어요. 새로운 여정에서 하나씩 모아봐요.")
        legacyOwned.sorted().forEach { id -> Text(when { id=="first-drive"->"첫 무사 도착"; id=="top-three"->"무사 TOP 3"; id.startsWith("visit:")->"첫 발자국 · ${id.removePrefix("visit:")}";else->id },modifier=Modifier.padding(vertical=7.dp)) }
        Text("이전 배지는 그대로 보관되며 월간 배지 포인트로 전환되지 않아요.",fontSize=12.sp,color=BadgeMuted,modifier=Modifier.padding(top=10.dp))
    }},confirmButton={TextButton(onClick={legacy=false}){Text("확인")}})
    }
}

@Composable private fun CollectionCard(badge: CollectionBadge,progress: Int,onClick: ()->Unit) {
    val complete=progress>=badge.target
    Surface(onClick=onClick,shape=RoundedCornerShape(16.dp),color=H1Style.Paper,
        border=androidx.compose.foundation.BorderStroke(1.dp,if(complete)badge.family.accent.copy(alpha=.25f) else Color.Transparent),modifier=Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal=10.dp,vertical=8.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                BadgeEmblem(badge.family,complete,Modifier.size(70.dp),tier=badge.tier)
                Spacer(Modifier.weight(1f))
                Column(horizontalAlignment=Alignment.End) {
                    badge.tier?.let { tier -> Text(tier.title,fontSize=10.sp,fontWeight=FontWeight.Bold,color=tier.accent,modifier=Modifier.padding(bottom=7.dp)) }
                    Surface(color=if(complete)badge.family.tint else Color(0xFFF4F6F8),shape=CircleShape) {
                        Text(if(complete)"달성" else "도전 중",fontSize=9.sp,color=if(complete)badge.family.accent else BadgeMuted,modifier=Modifier.padding(horizontal=7.dp,vertical=4.dp))
                    }
                }
            }
            Text(badge.title,fontSize=13.sp,fontWeight=FontWeight.Bold,color=BadgeInk,modifier=Modifier.padding(top=4.dp),maxLines=1)
            Text(badge.condition,fontSize=10.sp,color=BadgeMuted,modifier=Modifier.padding(top=4.dp),maxLines=2,minLines=1)
            Row(Modifier.fillMaxWidth().padding(top=6.dp,bottom=5.dp),verticalAlignment=Alignment.CenterVertically) {
                Text("$progress / ${badge.target}${badge.unit}",fontSize=10.sp,color=BadgeMuted)
                Spacer(Modifier.weight(1f));Text("+${points(badge.reward)}P",fontSize=12.sp,fontWeight=FontWeight.Bold,color=badge.family.accent)
            }
            LinearProgressIndicator(progress={progress.toFloat()/badge.target},color=badge.family.accent,trackColor=badge.family.tint,modifier=Modifier.fillMaxWidth().height(4.dp).clip(CircleShape))
        }
    }
}

/** Common shield behind every category. Rank uses approved one-paw oversized-trophy variants. */
@Composable private fun BadgeEmblem(family: BadgeFamily,earned: Boolean,modifier: Modifier=Modifier,tier: TrophyTier?=null) {
    val rankTier=if(family==BadgeFamily.RANK)tier ?: TrophyTier.BRONZE else null
    Box(modifier.semantics { contentDescription="${family.title} 무사즈 배지${rankTier?.let { " · ${it.title} 트로피" } ?: ""} · ${if(earned)"달성" else "도전 중"}" },contentAlignment=Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w=size.width;val h=size.height
            val shield=Path().apply {
                moveTo(w*.5f,h*.07f);lineTo(w*.86f,h*.2f);lineTo(w*.82f,h*.59f)
                quadraticTo(w*.78f,h*.83f,w*.5f,h*.96f);quadraticTo(w*.22f,h*.83f,w*.18f,h*.59f)
                lineTo(w*.14f,h*.2f);close()
            }
            drawPath(shield,family.tint)
            drawPath(shield,family.accent.copy(alpha=.35f),style=Stroke(w*.022f))
        }
        val resource=rankTier?.image ?: R.drawable.musaz_encourage
        Image(painterResource(resource),null,modifier=Modifier.fillMaxSize(if(rankTier!=null).94f else .82f).offset(y=3.dp),contentScale=ContentScale.Fit)
        Box(Modifier.align(Alignment.BottomEnd).size(20.dp).background(Color.White,CircleShape).border(1.dp,family.accent.copy(alpha=.18f),CircleShape),contentAlignment=Alignment.Center) {
            Icon(family.icon,null,tint=family.accent,modifier=Modifier.size(12.dp))
        }
    }
}
