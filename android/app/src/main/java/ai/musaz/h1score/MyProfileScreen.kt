package ai.musaz.h1score

import android.os.Build
import android.os.SystemClock
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlinx.coroutines.delay

private val ProfileInk=Color(0xFF152D4C)
private val ProfileMuted=Color(0xFF73818F)
private val ProfilePaper=Color(0xFFF5F6F8)
private val ProfileGreen=H1Style.Ink

internal data class ProfileEnergy(
    val battery: Int?=null, val fuel: Int?=null, val oil: String?=null, val steering: Float?=null,
    val washerFluidLow: Boolean?=null, val evEfficiency: Float?=null, val fuelEconomy: Float?=null,
)

@Composable internal fun MyProfileScreen(vm: ChallengeViewModel, enabled: Boolean) {
    var editing by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var energy by remember { mutableStateOf(ProfileEnergy()) }
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(enabled,vm.demo) {
        if(!enabled) editing=false
        energy=ProfileEnergy()
        while(enabled) {
            now=SystemClock.elapsedRealtime()
            energy=if(vm.demo) ProfileEnergy() else vm.connection.readProfileEnergy()
            delay(2000)
        }
    }
    val observedAt=maxOf(now,SystemClock.elapsedRealtime())
    val live=vm.live
    val showVehicle=!vm.demo
    fun <T> vehicle(value:T?): T? = value.takeIf { showVehicle }
    val rows=listOf(
        "운전석 안전벨트" to vehicle(live.belt.takeIf { observedAt-live.beltAt in 0..2500 })?.let { if(it) "착용" else "미착용" },
        "핸들 각도" to vehicle(energy.steering)?.let { angle ->
            val magnitude = "%.1f°".format(abs(angle))
            // Pleos Vehicle reports clockwise steering-wheel rotation as negative.
            when {
                abs(angle) < 0.05f -> "중앙 · 0.0°"
                angle < 0f -> "우측 · $magnitude"
                else -> "좌측 · $magnitude"
            }
        },
        "누적 주행거리" to vehicle(live.odometer.takeIf { observedAt-live.odometerAt in 0..5000 })?.let { "%,.1f km".format(it) },
        "전기차 배터리" to vehicle(energy.battery)?.let { "$it%" },
        "연료 잔량" to vehicle(energy.fuel)?.let { "$it%" },
        "엔진오일 수위" to when(vehicle(energy.oil)) { "NORMAL"->"정상";"LOW"->"낮음";"CRITICALLY_LOW"->"매우 낮음";"HIGH"->"높음";else->null },
        "워셔액 부족 경고" to vehicle(energy.washerFluidLow)?.let {if(it) "보충 필요" else "없음"},
        "이번 주행 전비" to vehicle(energy.evEfficiency)?.let {"%.1f km/kWh".format(it)},
        "이번 주행 연비" to vehicle(energy.fuelEconomy)?.let {"%.1f L/100 km".format(it)}
    )
    Column(Modifier.fillMaxSize()) {
        Text("내 프로필과 차량 상태를 확인해요",fontSize=14.sp,color=ProfileMuted,modifier=Modifier.padding(top=4.dp,bottom=16.dp))
        Row(Modifier.weight(1f),horizontalArrangement=Arrangement.spacedBy(20.dp)) {
            Column(Modifier.width(270.dp).fillMaxHeight().clip(RoundedCornerShape(24.dp)).background(Color.White).padding(20.dp)) {
                Text("기본 프로필",fontSize=18.sp,fontWeight=FontWeight.Bold,color=ProfileInk)
                Icon(Icons.Outlined.AccountCircle,null,Modifier.padding(top=20.dp).size(70.dp),tint=ProfileGreen)
                Text(vm.nickname,fontSize=24.sp,fontWeight=FontWeight.Bold,color=ProfileInk,modifier=Modifier.padding(top=12.dp))
                Text("이 기기에 저장된 프로필",fontSize=13.sp,color=ProfileMuted)
                OutlinedButton(onClick={draft=vm.nickname;editing=true},enabled=enabled,modifier=Modifier.padding(top=16.dp)) {
                    Icon(Icons.Outlined.Edit,null,Modifier.size(16.dp)); Text("닉네임 변경",Modifier.padding(start=8.dp))
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick={vm.restartTutorial()},enabled=enabled && vm.canChooseDestination && vm.trackedSpot==null) {
                    Text("튜토리얼 다시보기",fontSize=14.sp)
                }
                Text("각 메뉴의 사용 방법을 다시 살펴봐요",fontSize=11.sp,color=ProfileMuted)
            }
            Column(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(24.dp)).background(Color.White).verticalScroll(rememberScrollState()).padding(20.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Icon(Icons.Outlined.DirectionsCar,null,tint=ProfileGreen)
                    Text("나의 차 상태",fontSize=20.sp,fontWeight=FontWeight.Bold,modifier=Modifier.padding(start=8.dp))
                }
                Text(when {
                    !showVehicle -> "시연·시험 모드에서는 차량 상태를 표시하지 않아요"
                    Build.HARDWARE in listOf("ranchu","goldfish") -> "에뮬레이터 차량 데이터"
                    else -> "연결된 차량에서 읽은 데이터"
                },fontSize=12.sp,color=ProfileMuted,modifier=Modifier.padding(top=5.dp,bottom=14.dp))
                rows.chunked(2).forEach { pair ->
                    Row(Modifier.fillMaxWidth().padding(bottom=8.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        pair.forEach { (label,value) ->
                            Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(ProfilePaper).padding(horizontal=12.dp,vertical=8.dp)) {
                                Text(label,fontSize=12.sp,color=ProfileMuted)
                                Text(value ?: "미확인",fontSize=15.sp,fontWeight=FontWeight.SemiBold,color=when {
                                    value==null -> ProfileMuted
                                    label=="워셔액 부족 경고" && vehicle(energy.washerFluidLow)==true -> Color(0xFFB3261E)
                                    else -> ProfileInk
                                })
                            }
                        }
                        if(pair.size==1) Spacer(Modifier.weight(1f))
                    }
                }
                Text("차종·권한에 따라 일부 항목은 표시되지 않을 수 있어요.",fontSize=11.sp,color=ProfileMuted)
            }
        }
    }
    if(editing && enabled) {
        val trimmed=draft.trim()
        val valid=trimmed.isNotBlank() && trimmed.codePointCount(0,trimmed.length)<=16 && trimmed.none { it.isISOControl() }
        AlertDialog(onDismissRequest={editing=false},title={Text("닉네임 변경")},text={
            OutlinedTextField(draft,{draft=it},singleLine=true,label={Text("닉네임")},supportingText={Text("1~16자")},isError=draft.isNotEmpty() && !valid)
        },confirmButton={TextButton(enabled=valid,onClick={if(vm.updateNickname(draft)) editing=false}){Text("저장")}},
            dismissButton={TextButton(onClick={editing=false}){Text("취소")}})
    }
}
