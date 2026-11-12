package ai.musaz.h1score

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Source-branch console harness, kept separate from the production home and badge UI. */
@Composable internal fun ConsoleScoringControls(vm: ChallengeViewModel) {
    if (!vm.consoleTestAvailable) return
    Column(Modifier.fillMaxWidth().padding(top=16.dp), verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Text("Pleos Virtual Console 점수 시험", fontWeight=FontWeight.Bold, color=H1Style.Ink)
        Text("실제 SDK 차속을 기록해요. 제한속도·벨트 착용은 시험 조건이며 내 기록·주간 점수·배지·보상에 반영하지 않아요.",
            fontSize=12.sp, color=H1Style.Muted)
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            listOf(50,80,100).forEach { limit ->
                FilterChip(selected=vm.consoleLimitKph==limit,
                    onClick={vm.chooseConsoleLimit(limit)}, label={Text("${limit} km/h")},
                    enabled=vm.live.parked && !vm.activeJourney && !vm.awaitingMapRoute)
            }
            Button(onClick=vm::startConsoleTest,
                enabled=vm.live.parked && !vm.activeJourney && !vm.awaitingMapRoute) {
                Text("콘솔 시험 시작")
            }
        }
    }
}
