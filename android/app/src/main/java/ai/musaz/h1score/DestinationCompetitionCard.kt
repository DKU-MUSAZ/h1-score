package ai.musaz.h1score

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
internal fun DestinationCompetitionCard(vm: ChallengeViewModel) {
    if(vm.demo || !vm.hasLiveRoute || vm.activeJourney || vm.awaitingMapRoute) return
    val place=vm.currentNavigationPlace ?: return
    val match=vm.destinationVenueMatch
    val venue=vm.matchedVenue
    var consent by remember { mutableStateOf(false) }
    LaunchedEffect(place,vm.parked) { consent=false }
    Surface(color=Color.White, shape=RoundedCornerShape(16.dp)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(place.name, color=H1Style.Ink)
                Text(if(match!=null) "${venue?.name} 대회 · 개최지까지 ${match.distanceMeters.toInt()}m"
                    else "목적지 1km 안에 개최지 없음 · 일반 길 안내", color=H1Style.Muted)
            }
            Button(onClick={if(match==null) vm.continueWithoutChallenge() else consent=true}, enabled=vm.canChooseDestination) {
                Text(if(match==null) "내비로 계속" else "대회 참가")
            }
        }
    }
    if(consent && vm.parked && venue!=null) {
        AlertDialog(onDismissRequest={consent=false},containerColor=Color.White,
            title={Text("${venue.name} 대회 참가")},
            text={Column {
                Text("목적지는 ${place.name} 그대로 유지돼요. 가장 가까운 개최지의 대회로 배정합니다.")
                if(!vm.live.beltConditionMet) Text("운전석 벨트 상태를 확인해 주세요.")
                RoundConsent(vm,"동의하고 참가",vm.live.beltConditionMet) { round -> consent=false;vm.accept(round) }
            }},confirmButton={},dismissButton={TextButton(onClick={consent=false}) {Text("닫기")}})
    }
}
