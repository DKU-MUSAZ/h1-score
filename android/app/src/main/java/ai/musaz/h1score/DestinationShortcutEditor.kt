package ai.musaz.h1score

import ai.musaz.h1score.core.SavedPlace
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight

@Composable
internal fun DestinationShortcutEditor(vm: ChallengeViewModel, initialId: String?, onDismiss: () -> Unit) {
    var selectedId by rememberSaveable { mutableStateOf(initialId) }
    var newLabel by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val item=vm.destinationShortcuts.find { it.id==selectedId }
    var label by remember(item?.id) { mutableStateOf(item?.label.orEmpty()) }
    var query by remember(item?.id) { mutableStateOf(item?.place?.name ?: item?.legacyQuery.orEmpty()) }
    var candidate by remember(item?.id) { mutableStateOf<SavedPlace?>(null) }
    var currentPlace by remember { mutableStateOf(vm.currentNavigationPlace) }
    LaunchedEffect(Unit) {
        while (true) { currentPlace=vm.currentNavigationPlace; kotlinx.coroutines.delay(500) }
    }
    AlertDialog(
        onDismissRequest=onDismiss,
        containerColor=Color.White,
        titleContentColor=H1Style.Ink,
        textContentColor=H1Style.Muted,
        shape=RoundedCornerShape(24.dp),
        title={Text(if(item==null) "목적지 바로가기" else "${item.label} 목적지", fontWeight=FontWeight.Bold)},
        text={Column(Modifier.width(480.dp).heightIn(max=340.dp).verticalScroll(rememberScrollState()), verticalArrangement=Arrangement.spacedBy(12.dp)) {
            if(item==null) {
                Text("자주 가는 곳을 등록해 한 번에 길 안내를 시작하세요.")
                vm.destinationShortcuts.forEach { shortcut ->
                    OutlinedButton(onClick={selectedId=shortcut.id;error=null}, modifier=Modifier.fillMaxWidth()) {
                        Text(shortcut.label, Modifier.weight(1f))
                        Text(shortcut.place?.name ?: "목적지 설정")
                    }
                }
                OutlinedTextField(newLabel,{newLabel=it;error=null},modifier=Modifier.fillMaxWidth(),singleLine=true,label={Text("새 바로가기 이름")},placeholder={Text("예: 학교, 운동장")})
                Button(onClick={
                    val id=vm.addShortcut(newLabel)
                    if(id!=null) {selectedId=id;newLabel=""} else error="중복되지 않는 이름을 1~12자로 입력해 주세요. 최대 10개까지 등록할 수 있어요."
                },enabled=vm.destinationShortcuts.size<10) {Text("바로가기 추가")}
            } else {
                OutlinedTextField(label,{label=it;error=null},modifier=Modifier.fillMaxWidth(),singleLine=true,label={Text("버튼 이름")})
                item.place?.let { Text("저장된 목적지: ${it.name}") }
                OutlinedTextField(query,{query=it},modifier=Modifier.fillMaxWidth(),singleLine=true,label={Text("주소 또는 장소 검색")})
                OutlinedButton(onClick={vm.searchDestination(query)},enabled=query.isNotBlank() && vm.live.parked,modifier=Modifier.fillMaxWidth()) {Text("내비에서 검색")}
                Text("내비에서 장소를 선택해 길 안내를 시작한 뒤 돌아오세요. 아래 목적지를 확인하고 저장해 주세요.")
                currentPlace?.let { place ->
                    OutlinedButton(onClick={candidate=place},modifier=Modifier.fillMaxWidth()) {Text("현재 내비 목적지 선택: ${place.name}")}
                } ?: Text("내비에서 선택한 목적지를 기다리고 있어요.")
                candidate?.let { Text("저장할 장소: ${it.name}", color=H1Style.Ink,fontWeight=FontWeight.Bold) }
                TextButton(onClick={vm.removeShortcut(item.id);selectedId=null;error=null}) {Text("이 바로가기 삭제")}
            }
            error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
        }},
        confirmButton={if(item!=null) Button(onClick={
            if(vm.renameShortcut(item.id,label)) {
                val place=candidate
                if(place==null || vm.saveShortcutPlace(item.id,place)) {selectedId=null;error=null}
            } else error="중복되지 않는 이름을 1~12자로 입력해 주세요."
        }, enabled=candidate!=null || label!=item.label) {Text("저장")}},
        dismissButton={TextButton(onClick={if(item!=null) {selectedId=null;error=null} else onDismiss()}) {Text(if(item!=null) "목록으로" else "닫기")}}
    )
}
