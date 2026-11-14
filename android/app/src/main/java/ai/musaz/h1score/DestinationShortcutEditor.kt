package ai.musaz.h1score

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
    var query by rememberSaveable(item?.id) { mutableStateOf(item?.searchQuery.orEmpty()) }
    AlertDialog(
        onDismissRequest=onDismiss,
        containerColor=Color.White,
        titleContentColor=H1Style.Ink,
        textContentColor=H1Style.Muted,
        shape=RoundedCornerShape(24.dp),
        title={Text(if(item==null) "목적지 바로가기" else "${item.label} 목적지", fontWeight=FontWeight.Bold)},
        text={Column(Modifier.width(480.dp).heightIn(max=340.dp).verticalScroll(rememberScrollState()), verticalArrangement=Arrangement.spacedBy(12.dp)) {
            if(item==null) {
                Text("자주 가는 장소를 저장하면 검색창에 바로 불러올 수 있어요.")
                vm.destinationShortcuts.forEach { shortcut ->
                    OutlinedButton(onClick={selectedId=shortcut.id;error=null}, modifier=Modifier.fillMaxWidth()) {
                        Text(shortcut.label, Modifier.weight(1f))
                        Text(shortcut.searchQuery.ifBlank { "목적지 설정" })
                    }
                }
                OutlinedTextField(newLabel,{newLabel=it;error=null},modifier=Modifier.fillMaxWidth(),singleLine=true,label={Text("새 바로가기 이름")},placeholder={Text("예: 학교, 운동장")})
                Button(onClick={
                    val id=vm.addShortcut(newLabel)
                    if(id!=null) {selectedId=id;newLabel=""} else error="중복되지 않는 이름을 1~12자로 입력해 주세요. 최대 10개까지 등록할 수 있어요."
                },enabled=vm.destinationShortcuts.size<10) {Text("바로가기 추가")}
            } else {
                OutlinedTextField(label,{label=it;error=null},modifier=Modifier.fillMaxWidth(),singleLine=true,label={Text("버튼 이름")})
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it; error = null },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("장소 이름 또는 주소") },
                    placeholder = { Text("예: 여의도 한강공원") },
                )
                TextButton(onClick={vm.removeShortcut(item.id);selectedId=null;error=null}) {Text("이 바로가기 삭제")}
            }
            error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
        }},
        confirmButton={if(item!=null) Button(onClick={
            if (vm.saveShortcut(item.id, label, query)) {
                selectedId = null
                error = null
            } else {
                error = "중복되지 않는 버튼 이름을 1~12자로 입력해 주세요."
            }
        }, enabled = query.isNotBlank() && (query.trim() != item.searchQuery || label.trim() != item.label)) {Text("저장")}},
        dismissButton={TextButton(onClick={if(item!=null) {selectedId=null;error=null} else onDismiss()}) {Text(if(item!=null) "목록으로" else "닫기")}}
    )
}
