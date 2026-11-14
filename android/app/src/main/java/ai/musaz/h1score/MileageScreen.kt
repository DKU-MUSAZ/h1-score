package ai.musaz.h1score

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.util.Locale

internal data class MileageReward(val id:String,val title:String,val cost:Int)
internal val mileageRewards = listOf(
    MileageReward("wiper","와이퍼 교환",80),
    MileageReward("filter","에어컨 필터 교환",300),
    MileageReward("oil","엔진오일 교환",600),
)
private data class RewardVisual(val name:String,val description:String,val tint:Color,@DrawableRes val image:Int)
private fun visual(reward: MileageReward) = when(reward.id) {
    "wiper" -> RewardVisual("와이퍼","비 오는 날에도\n시야를 또렷하게",H1Style.PaleMint,R.drawable.reward_wiper)
    "filter" -> RewardVisual("에어컨 필터","차 안으로 들어오는\n공기를 쾌적하게",Color(0xFFEBF0FC),R.drawable.reward_filter)
    else -> RewardVisual("엔진오일","엔진의 마찰을 줄이는\n든든한 기본 관리",Color(0xFFFFF0DD),R.drawable.reward_oil)
}
private fun mileage(value:Int)=String.format(Locale.KOREA,"%,d",value)
// Fixed five-year date requested for the prototype coupon display on 2026-11-11.
private const val DemoCouponExpiry = "2031.11.11"
private const val SampleCouponNumber = "AJ-7C29-4B16-8305"

@Composable internal fun Wallet(vm:ChallengeViewModel) {
    var selected by remember { mutableStateOf<MileageReward?>(null) }
    var showOilVoucher by remember { mutableStateOf(false) }
    var usage by remember { mutableStateOf(false) }
    var showCoupons by remember { mutableStateOf(false) }
    val next=mileageRewards.firstOrNull {it.id !in vm.redeemedRewards}
    val available=if (vm.canPreviewCouponExchange) mileageRewards.count {it.cost<=vm.balance && it.id !in vm.redeemedRewards} else 0
    LaunchedEffect(vm.parked) {if(!vm.parked) {selected=null;showOilVoucher=false;usage=false;showCoupons=false}}
    Row(Modifier.fillMaxSize(),horizontalArrangement=Arrangement.spacedBy(22.dp)) {
        Surface(Modifier.width(292.dp).heightIn(max=660.dp),color=H1Style.Ink,shape=RoundedCornerShape(26.dp)) {
            Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState())) {
                Text("차곡차곡, 내 마일리지",color=H1Style.Mint,fontSize=16.sp,fontWeight=FontWeight.Medium)
                Row(Modifier.padding(top=12.dp),verticalAlignment=Alignment.Bottom) {
                    Text(mileage(vm.balance),color=Color.White,fontSize=48.sp,fontWeight=FontWeight.Bold,letterSpacing=(-1.5).sp)
                    Text(" M",color=H1Style.Mint,fontSize=24.sp,modifier=Modifier.padding(bottom=7.dp))
                }
                Text(if (vm.canPreviewCouponExchange) "시연 잔액" else "보유 마일리지",color=Color(0xFFCAD8E5),fontSize=12.sp)
                Box(Modifier.fillMaxWidth().height(210.dp).padding(vertical=10.dp),contentAlignment=Alignment.Center) {
                    // Size by height to account for the artwork's transparent side margins.
                    Image(painterResource(R.drawable.musaz_mileage),null,
                        Modifier.requiredSize(285.dp,190.dp),contentScale=ContentScale.Fit)
                }
                Text("안전하게 달린 만큼,\n내 차도 기분 좋게",color=Color.White,fontSize=20.sp,fontWeight=FontWeight.Bold,lineHeight=27.sp)
                Spacer(Modifier.height(18.dp))
                Surface(color=Color(0xFF294A65),shape=RoundedCornerShape(16.dp)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp)) {
                        val status = when {
                            !vm.canPreviewCouponExchange -> "쿠폰 교환 준비 중"
                            available > 0 -> "지금 교환 가능한 혜택 ${available}개"
                            next != null -> "${visual(next).name}까지 한 걸음씩"
                            else -> "모든 시연 혜택을 받았어요"
                        }
                        Text(status,color=Color.White,fontSize=14.sp,fontWeight=FontWeight.SemiBold)
                        if(vm.canPreviewCouponExchange && next!=null && available==0) {
                            Text("${mileage((next.cost-vm.balance).coerceAtLeast(0))} M 더 모으면 돼요",color=H1Style.Mint,fontSize=13.sp,modifier=Modifier.padding(top=6.dp))
                            LinearProgressIndicator(progress={(vm.balance.toFloat()/next.cost).coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth().padding(top=10.dp),color=H1Style.Mint,trackColor=Color(0xFF46637B))
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                Button(onClick={showCoupons=true},enabled=vm.parked,
                    modifier=Modifier.fillMaxWidth().height(52.dp),shape=RoundedCornerShape(15.dp),
                    colors=ButtonDefaults.buttonColors(containerColor=Color.White,contentColor=H1Style.Ink)) {
                    Text("나의 쿠폰함",fontSize=17.sp,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f))
                    Icon(Icons.Outlined.ChevronRight,null)
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            Column {
                Text("내 차를 위한 작은 선물",fontSize=23.sp,fontWeight=FontWeight.Bold,color=H1Style.Ink)
                Text("모은 마일리지로 필요한 차량 관리 혜택을 골라보세요.",fontSize=14.sp,color=H1Style.Muted,modifier=Modifier.padding(top=5.dp))
            }
            Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                mileageRewards.forEach { reward ->
                    RewardCard(reward,vm.balance,reward.id in vm.redeemedRewards,vm.parked,vm.canPreviewCouponExchange,Modifier.fillMaxWidth()) {selected=reward}
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
    if(showCoupons && vm.parked) {
        val received=mileageRewards.filter {it.id in vm.redeemedRewards}
        MileageDialog(onDismissRequest={showCoupons=false}) {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    Text("나의 쿠폰함",fontSize=32.sp,fontWeight=FontWeight.Bold,color=H1Style.Ink,modifier=Modifier.weight(1f))
                    Text("보유 혜택 ${received.size}개",fontSize=18.sp,color=H1Style.Muted)
                }
                Text(if (vm.canPreviewCouponExchange) "교환한 쿠폰과 시연 예시를 확인할 수 있어요." else "실제 쿠폰 교환은 제휴가 확정되면 이용할 수 있어요.",
                    fontSize=18.sp,lineHeight=28.sp,color=H1Style.Muted)
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                    if (!vm.canPreviewCouponExchange && received.isEmpty()) {
                        Text("아직 교환한 혜택이 없어요.",fontSize=24.sp,lineHeight=34.sp,color=H1Style.Ink,modifier=Modifier.padding(vertical=32.dp))
                    }
                    received.forEach { reward ->
                        val number = vm.couponNumbers[reward.id] ?: return@forEach
                        CouponCard(reward, number, "시연 교환 완료")
                    }
                    if (vm.demo) {
                        CouponCard(mileageRewards.last(), SampleCouponNumber, "시연 예시 쿠폰")
                    }
                }
                Button(onClick={showCoupons=false},modifier=Modifier.fillMaxWidth().height(56.dp),shape=RoundedCornerShape(15.dp)) {
                    Text("확인",fontSize=20.sp,fontWeight=FontWeight.Bold)
                }
        }
    }
    if(showOilVoucher && vm.parked) MonthlyOilVoucherInfo {showOilVoucher=false}
    if(usage && vm.parked) MileageDialog(onDismissRequest={usage=false}) {
        Text("마일리지 사용처 안내",fontSize=32.sp,fontWeight=FontWeight.Bold,color=H1Style.Ink)
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(20.dp)) {
            Text("와이퍼 · 에어컨 필터 · 엔진오일 교환 혜택을 준비하고 있어요.",fontSize=24.sp,lineHeight=34.sp,fontWeight=FontWeight.SemiBold,color=H1Style.Ink)
            Text("현재는 앱 안에서 잔액 차감과 교환 완료를 확인하는 시연입니다. 실제 정비소 결제나 쿠폰 발급은 지원하지 않아요.",fontSize=22.sp,lineHeight=34.sp,color=H1Style.Ink)
            Text("사용 가능한 매장, 차종별 조건, 교환권 이용 방법은 제휴가 확정되면 안내할게요.",fontSize=22.sp,lineHeight=34.sp,color=H1Style.Ink)
            Text("월간 엔진오일 교환권 목표 안내는 이 화면의 시연 교환과 별도예요.",fontSize=18.sp,lineHeight=28.sp,color=H1Style.Muted)
        }
        Button(onClick={usage=false},modifier=Modifier.fillMaxWidth().height(56.dp),shape=RoundedCornerShape(15.dp)) {
            Text("확인",fontSize=20.sp,fontWeight=FontWeight.Bold)
        }
    }
    selected?.takeIf {vm.parked}?.let {reward ->
        val received=reward.id in vm.redeemedRewards
        val short=(reward.cost-vm.balance).coerceAtLeast(0)
        val style=visual(reward)
        MileageDialog(onDismissRequest={selected=null}) {
            val title = when {
                !vm.canPreviewCouponExchange -> "${style.name} 쿠폰"
                received -> "쿠폰 교환이 완료됐어요"
                else -> "${style.name} 쿠폰을 교환하시겠습니까?"
            }
            Text(title,
                fontSize=32.sp,lineHeight=42.sp,fontWeight=FontWeight.Bold,color=H1Style.Ink)
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(20.dp)) {
                Box(Modifier.fillMaxWidth().height(160.dp).background(style.tint,RoundedCornerShape(20.dp)),contentAlignment=Alignment.Center) {
                    Image(painterResource(style.image),null,Modifier.size(130.dp))
                }
                Text("필요 ${mileage(reward.cost)} M · 내 잔액 ${mileage(vm.balance)} M",fontSize=26.sp,lineHeight=36.sp,fontWeight=FontWeight.Bold,color=H1Style.Ink)
                when {
                    !vm.canPreviewCouponExchange -> Text("쿠폰 교환은 시연 모드에서 체험할 수 있어요.",
                        fontSize=22.sp,lineHeight=32.sp,color=H1Style.Ink)
                    received -> Text("이미 교환한 쿠폰이에요. 나의 쿠폰함에서 확인해 주세요.",
                        fontSize=22.sp,lineHeight=32.sp,fontWeight=FontWeight.SemiBold,color=H1Style.Ink)
                    short>0 -> Surface(color=MaterialTheme.colorScheme.errorContainer,shape=RoundedCornerShape(16.dp)) {
                        Column(Modifier.fillMaxWidth().padding(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            Text("마일리지가 부족해요",fontSize=24.sp,fontWeight=FontWeight.Bold,color=MaterialTheme.colorScheme.onErrorContainer)
                            Text("${mileage(short)} M 더 모으면 교환할 수 있어요.",fontSize=20.sp,lineHeight=30.sp,color=MaterialTheme.colorScheme.onErrorContainer)
                        }
                    }
                    else -> Text("교환하면 ${mileage(reward.cost)} M가 차감돼요.",
                        fontSize=22.sp,lineHeight=32.sp,color=H1Style.Ink)
                }
                if (vm.canPreviewCouponExchange) Text("시연 교환 · 실제 정비 쿠폰은 발급되지 않아요.",fontSize=18.sp,lineHeight=28.sp,color=H1Style.Muted)
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                OutlinedButton(onClick={selected=null},modifier=Modifier.weight(1f).height(56.dp),shape=RoundedCornerShape(15.dp)) {
                    Text(if(received || !vm.canPreviewCouponExchange) "닫기" else "취소",fontSize=20.sp,fontWeight=FontWeight.Bold)
                }
                if (vm.canPreviewCouponExchange) Button(onClick={vm.redeemDemoReward(reward)},enabled=!received && short==0 && vm.parked,
                    modifier=Modifier.weight(2f).height(56.dp),shape=RoundedCornerShape(15.dp)) {
                    Text(if(received) "교환 완료" else "교환하기",fontSize=20.sp,fontWeight=FontWeight.Bold)
                }
            }
        }
    }
}

@Composable private fun CouponCard(reward: MileageReward, number: String, status: String) {
    Surface(color = H1Style.PaleMint, shape = RoundedCornerShape(20.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            Image(painterResource(visual(reward).image), null, Modifier.size(80.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(reward.title, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = H1Style.Ink)
                Text(status, fontSize = 16.sp, color = H1Style.Ink)
                Text("사용기한 · ${DemoCouponExpiry}까지", fontSize = 16.sp, color = H1Style.Muted)
            }
            Column(Modifier.widthIn(max = 244.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("교환권 번호", fontSize = 15.sp, color = H1Style.Muted)
                Text(number, fontSize = 21.sp, fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold, color = H1Style.Ink)
            }
        }
    }
}

@Composable private fun MileageDialog(onDismissRequest:()->Unit,content:@Composable ColumnScope.()->Unit) {
    Dialog(onDismissRequest=onDismissRequest,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.width(820.dp).heightIn(min=420.dp,max=620.dp),color=Color.White,shape=RoundedCornerShape(28.dp)) {
            Column(Modifier.padding(32.dp),verticalArrangement=Arrangement.spacedBy(20.dp),content=content)
        }
    }
}

@Composable private fun RewardCard(reward:MileageReward,balance:Int,received:Boolean,enabled:Boolean,demo:Boolean,modifier:Modifier,onClick:()->Unit) {
    val style=visual(reward)
    val short=(reward.cost-balance).coerceAtLeast(0)
    Surface(onClick=onClick,enabled=enabled,modifier=modifier.semantics {contentDescription="${style.name} 상세"},
        shape=RoundedCornerShape(22.dp),color=Color.White) {
        Row(Modifier.fillMaxWidth().padding(horizontal=18.dp,vertical=16.dp),
            verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(18.dp)) {
            Box(Modifier.size(72.dp).background(style.tint,RoundedCornerShape(16.dp)),contentAlignment=Alignment.Center) {
                Image(painterResource(style.image),null,Modifier.size(64.dp))
            }
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(7.dp)) {
                Text(style.name,color=H1Style.Ink,fontSize=21.sp,fontWeight=FontWeight.Bold)
                Text(style.description.replace("\n"," "),color=H1Style.Muted,fontSize=14.sp,lineHeight=21.sp)
            }
            Column(Modifier.width(160.dp),horizontalAlignment=Alignment.End,verticalArrangement=Arrangement.spacedBy(5.dp)) {
                Text("${mileage(reward.cost)} M",color=H1Style.Ink,fontSize=27.sp,fontWeight=FontWeight.Bold)
                Text(when {!demo->"교환 준비 중";received->"교환 완료";short==0->"지금 교환 가능";else->"${mileage(short)} M 더 모으면 돼요"},
                    color=if(received || short==0) H1Style.Ink else H1Style.Muted,fontSize=12.sp,lineHeight=18.sp,
                    textAlign=androidx.compose.ui.text.style.TextAlign.End)
            }
        }
    }
}
