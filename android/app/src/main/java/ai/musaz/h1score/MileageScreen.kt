package ai.musaz.h1score

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

internal data class MileageReward(val id:String,val title:String,val cost:Int)
internal val mileageRewards = listOf(
    MileageReward("wiper","와이퍼 교환",80),
    MileageReward("filter","에어컨 필터 교환",300),
    MileageReward("oil","엔진오일 교환",600),
)
private data class RewardVisual(val name:String,val description:String,val tint:Color,@DrawableRes val image:Int)
private fun visual(reward: MileageReward) = when(reward.id) {
    "wiper" -> RewardVisual("와이퍼","비 오는 날에도\n시야를 또렷하게",Color(0xFFE5F4F4),R.drawable.reward_wiper)
    "filter" -> RewardVisual("에어컨 필터","차 안으로 들어오는\n공기를 쾌적하게",Color(0xFFEBF0FC),R.drawable.reward_filter)
    else -> RewardVisual("엔진오일","엔진의 마찰을 줄이는\n든든한 기본 관리",Color(0xFFFFF0DD),R.drawable.reward_oil)
}
private fun mileage(value:Int)=String.format(Locale.KOREA,"%,d",value)

@Composable internal fun Wallet(vm:ChallengeViewModel) {
    var selected by remember { mutableStateOf<MileageReward?>(null) }
    var showOilVoucher by remember { mutableStateOf(false) }
    var usage by remember { mutableStateOf(false) }
    val next=mileageRewards.firstOrNull {it.id !in vm.redeemedRewards}
    val available=mileageRewards.count {it.cost<=vm.balance && it.id !in vm.redeemedRewards}
    LaunchedEffect(vm.parked) {if(!vm.parked) {selected=null;showOilVoucher=false;usage=false}}
    Row(Modifier.fillMaxSize(),horizontalArrangement=Arrangement.spacedBy(22.dp)) {
        Surface(Modifier.width(292.dp).fillMaxHeight(),color=H1Style.Ink,shape=RoundedCornerShape(26.dp)) {
            Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState())) {
                Text("차곡차곡, 내 마일리지",color=Color(0xFFBCEADB),fontSize=16.sp,fontWeight=FontWeight.Medium)
                Row(Modifier.padding(top=12.dp),verticalAlignment=Alignment.Bottom) {
                    Text(mileage(vm.balance),color=Color.White,fontSize=48.sp,fontWeight=FontWeight.Bold,letterSpacing=(-1.5).sp)
                    Text(" M",color=Color(0xFFBCEADB),fontSize=24.sp,modifier=Modifier.padding(bottom=7.dp))
                }
                Text("시연 잔액",color=Color(0xFFCAD8E5),fontSize=12.sp)
                Image(painterResource(R.drawable.musaz_challenge_home),null,Modifier.fillMaxWidth().height(150.dp).padding(top=10.dp),contentScale=ContentScale.Fit)
                Text("안전하게 달린 만큼,\n내 차도 기분 좋게",color=Color.White,fontSize=20.sp,fontWeight=FontWeight.Bold,lineHeight=27.sp)
                Spacer(Modifier.height(18.dp))
                Surface(color=Color(0xFF294A65),shape=RoundedCornerShape(16.dp)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp)) {
                        Text(if(available>0) "지금 교환 가능한 혜택 ${available}개" else next?.let {"${visual(it).name}까지 한 걸음씩"} ?: "모든 시연 혜택을 받았어요",color=Color.White,fontSize=14.sp,fontWeight=FontWeight.SemiBold)
                        if(next!=null && available==0) {
                            Text("${mileage((next.cost-vm.balance).coerceAtLeast(0))} M 더 모으면 돼요",color=Color(0xFFBCEADB),fontSize=13.sp,modifier=Modifier.padding(top=6.dp))
                            LinearProgressIndicator(progress={(vm.balance.toFloat()/next.cost).coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth().padding(top=10.dp),color=H1Style.Mint,trackColor=Color(0xFF46637B))
                        }
                    }
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            Column {
                Text("내 차를 위한 작은 선물",fontSize=23.sp,fontWeight=FontWeight.Bold,color=H1Style.Ink)
                Text("모은 마일리지로 필요한 차량 관리 혜택을 골라보세요.",fontSize=14.sp,color=H1Style.Muted,modifier=Modifier.padding(top=5.dp))
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                mileageRewards.forEach { reward ->
                    RewardCard(reward,vm.balance,reward.id in vm.redeemedRewards,vm.parked,Modifier.weight(1f)) {selected=reward}
                }
            }
            Surface(onClick={usage=true},enabled=vm.parked,color=Color.White,shape=RoundedCornerShape(18.dp)) {
                Row(Modifier.fillMaxWidth().padding(16.dp),verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("마일리지, 이렇게 써요",color=H1Style.Ink,fontSize=16.sp,fontWeight=FontWeight.SemiBold)
                        Text("혜택 고르기 → 필요한 M 확인 → 시연 교환",color=H1Style.Muted,fontSize=13.sp,modifier=Modifier.padding(top=5.dp))
                    }
                    Icon(Icons.Outlined.ChevronRight,"사용처 안내",tint=H1Style.Ink)
                }
            }
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                Text("지금은 시연 교환이에요. 실제 사용처는 제휴 확정 후 안내합니다.",color=H1Style.Muted,fontSize=12.sp,modifier=Modifier.weight(1f))
                TextButton(onClick={showOilVoucher=true},enabled=vm.parked,contentPadding=PaddingValues(0.dp)) {Text("엔진오일 교환권 안내",fontSize=12.sp)}
            }
        }
    }
    if(showOilVoucher && vm.parked) MonthlyOilVoucherInfo {showOilVoucher=false}
    if(usage && vm.parked) AlertDialog(onDismissRequest={usage=false},containerColor=Color.White,
        title={Text("마일리지 사용처 안내")},text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("와이퍼 · 에어컨 필터 · 엔진오일 교환 혜택을 준비하고 있어요.")
            Text("현재는 앱 안에서 잔액 차감과 교환 완료를 확인하는 시연입니다. 실제 정비소 결제나 쿠폰 발급은 지원하지 않아요.")
            Text("사용 가능한 매장, 차종별 조건, 교환권 이용 방법은 제휴가 확정되면 안내할게요.")
            Text("월간 엔진오일 교환권 목표 안내는 이 화면의 시연 교환과 별도예요.",color=H1Style.Muted)
        }},confirmButton={TextButton(onClick={usage=false}) {Text("확인")}})
    selected?.takeIf {vm.parked}?.let {reward ->
        val received=reward.id in vm.redeemedRewards
        val short=(reward.cost-vm.balance).coerceAtLeast(0)
        val style=visual(reward)
        AlertDialog(onDismissRequest={selected=null},containerColor=Color.White,shape=RoundedCornerShape(24.dp),
            title={Text(reward.title,fontWeight=FontWeight.Bold)},
            text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Image(painterResource(style.image),null,Modifier.fillMaxWidth().height(110.dp))
                Text("필요 ${mileage(reward.cost)} M · 내 잔액 ${mileage(vm.balance)} M",fontWeight=FontWeight.SemiBold)
                Text("시연 교환 · 실제 정비 쿠폰은 발급되지 않아요.",color=H1Style.Muted)
                if(received) Text("교환을 완료했어요!",color=H1Style.Green)
            }},confirmButton={Button(onClick={vm.redeemDemoReward(reward)},enabled=!received && short==0 && vm.parked) {
                Text(when {received->"교환 완료";short>0->"${mileage(short)} M 더 필요";else->"${mileage(reward.cost)} M으로 시연 교환"})
            }},dismissButton={TextButton(onClick={selected=null}) {Text("닫기")}})
    }
}

@Composable private fun RewardCard(reward:MileageReward,balance:Int,received:Boolean,enabled:Boolean,modifier:Modifier,onClick:()->Unit) {
    val style=visual(reward)
    val short=(reward.cost-balance).coerceAtLeast(0)
    Surface(modifier,shape=RoundedCornerShape(22.dp),color=Color.White) {
        Column(Modifier.padding(14.dp),horizontalAlignment=Alignment.CenterHorizontally) {
            Box(Modifier.fillMaxWidth().height(80.dp).background(style.tint,RoundedCornerShape(16.dp)),contentAlignment=Alignment.Center) {
                Image(painterResource(style.image),null,Modifier.size(72.dp))
            }
            Text(style.name,color=H1Style.Ink,fontSize=17.sp,fontWeight=FontWeight.Bold,modifier=Modifier.padding(top=10.dp))
            Text(style.description,color=H1Style.Muted,fontSize=12.sp,lineHeight=16.sp,textAlign=androidx.compose.ui.text.style.TextAlign.Center,modifier=Modifier.padding(top=7.dp))
            Text("${mileage(reward.cost)} M",color=H1Style.Ink,fontSize=25.sp,fontWeight=FontWeight.Bold,modifier=Modifier.padding(top=8.dp))
            Text(when {received->"교환 완료";short==0->"지금 교환 가능";else->"${mileage(short)} M 더 모으면 돼요"},color=if(received || short==0) H1Style.Green else H1Style.Muted,fontSize=11.sp,modifier=Modifier.padding(top=4.dp,bottom=8.dp))
            OutlinedButton(onClick=onClick,enabled=enabled,modifier=Modifier.fillMaxWidth().height(44.dp).semantics {contentDescription="${style.name} 상세"},contentPadding=PaddingValues(horizontal=4.dp),shape=RoundedCornerShape(12.dp)) {
                if(received) Icon(Icons.Outlined.CheckCircle,null,Modifier.size(15.dp))
                Text(if(received) "교환 내역" else "자세히 보기",fontSize=13.sp)
            }
        }
    }
}
