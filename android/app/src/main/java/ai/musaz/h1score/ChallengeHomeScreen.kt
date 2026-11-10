package ai.musaz.h1score

import ai.musaz.h1score.core.ChallengeRound
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Work
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.util.Locale

private val popularSpots = demoDestinations.filter { it.demoPeers > 0 }
    .sortedWith(compareByDescending<Destination> { it.demoPeers }.thenBy { it.name })
    .take(3)
private val HomeStatDiameter = 216.dp

@Composable
internal fun ChallengeHomeScreen(vm: ChallengeViewModel) {
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var showMap by rememberSaveable { mutableStateOf(false) }
    var editingShortcuts by rememberSaveable { mutableStateOf(false) }
    var selectedSpot by remember { mutableStateOf<Destination?>(null) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val canChoose = vm.canChooseDestination

    fun hideKeyboard() {
        keyboard?.hide()
        focus.clearFocus(force = true)
    }

    fun closeSearch() {
        hideKeyboard()
        query = ""
        searching = false
    }

    fun editShortcuts() {
        if (!canChoose) return
        hideKeyboard()
        editingShortcuts = true
    }

    fun openShortcut(destination: String) {
        if (!canChoose) return
        if (destination.isEmpty()) {
            editShortcuts()
        } else {
            closeSearch()
            vm.searchDestination(destination)
        }
    }

    fun chooseSpot(spot: Destination) {
        if (!canChoose) return
        if (spot == hangangChallenge || vm.demo) {
            hideKeyboard()
            vm.rememberDestination(spot.name)
            selectedSpot = spot
        } else {
            closeSearch()
            vm.searchDestination(spot.name)
        }
    }

    fun searchDestination(name: String) {
        if (!canChoose || name.isBlank()) return
        val key = name.filter(Char::isLetterOrDigit)
        val spot = popularSpots.singleOrNull {
            val spotKey = it.name.filter(Char::isLetterOrDigit)
            spotKey.equals(key, ignoreCase = true) ||
                (vm.demo && key.length >= 2 && spotKey.startsWith(key, ignoreCase = true))
        }
        if (spot != null) {
            chooseSpot(spot)
        } else {
            closeSearch()
            vm.searchDestination(name)
        }
    }

    BackHandler(enabled = showMap || searching) {
        if (showMap) showMap = false else closeSearch()
    }
    LaunchedEffect(canChoose) {
        if (!canChoose) editingShortcuts = false
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (showMap) {
            TextButton(onClick = { showMap = false }) { Text("목적지 검색으로 돌아가기") }
            ChallengeMap(
                vm,
                Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(24.dp))
                    .background(H1Style.heroBackground)
            )
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it; searching = true },
                    modifier = Modifier.weight(1f).onFocusChanged { if (it.isFocused) searching = true },
                    enabled = canChoose,
                    singleLine = true,
                    shape = RoundedCornerShape(30.dp),
                    placeholder = { Text("목적지 또는 장소를 검색해 주세요", fontSize = 19.sp) },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    trailingIcon = if (query.isNotEmpty()) {
                        { IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Close, "검색어 지우기") } }
                    } else null,
                    textStyle = LocalTextStyle.current.copy(fontSize = 19.sp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { searchDestination(query) }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color.White,
                        unfocusedContainerColor = Color.White,
                        unfocusedBorderColor = H1Style.Line
                    )
                )
                if (searching) TextButton(onClick = ::closeSearch) { Text("닫기", fontSize = 16.sp) }
            }
            if (searching) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    DestinationShortcutRow(vm.destinationShortcuts, canChoose, ::openShortcut, ::editShortcuts)
                    DestinationSuggestions(
                        recent = vm.recentDestinations,
                        query = query,
                        enabled = canChoose,
                        onSearch = ::searchDestination,
                        onSpot = ::chooseSpot,
                        onMap = { hideKeyboard(); showMap = true }
                    )
                }
            } else {
                val score = vm.collectionProgress.weeklyScoreAverage(LocalDate.now(ChallengeRound.seoul))
                Column(
                    Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(28.dp))
                        .background(H1Style.heroBackground).padding(top = 18.dp, bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ChallengeHomeHero(vm.recordedDistanceMeters, score, Modifier.weight(1f))
                    Text(
                        if (vm.awaitingMapRoute) "내비 경로를 확인하고 있어요" else vm.homeGreeting,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        color = H1Style.Ink,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
    if (vm.parked) selectedSpot?.let { spot ->
        ChallengeEntryDialog(vm, spot) { selectedSpot = null }
    }
    if (searching && editingShortcuts && canChoose) {
        DestinationShortcutEditor(vm.destinationShortcuts, vm::saveDestinationShortcuts) { editingShortcuts = false }
    }
}

@Composable
private fun DestinationShortcutRow(
    shortcuts: DestinationShortcuts,
    enabled: Boolean,
    onOpen: (String) -> Unit,
    onEdit: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        DestinationShortcutButton("집", shortcuts.home, Icons.Filled.Home, enabled) { onOpen(shortcuts.home) }
        DestinationShortcutButton("회사", shortcuts.work, Icons.Filled.Work, enabled) { onOpen(shortcuts.work) }
        Spacer(Modifier.weight(1f))
        OutlinedButton(onClick = onEdit, enabled = enabled) { Text("편집") }
    }
}

@Composable
private fun DestinationShortcutButton(
    label: String,
    destination: String,
    icon: ImageVector,
    enabled: Boolean,
    onClick: () -> Unit
) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.widthIn(max = 156.dp),
        shape = CircleShape,
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = Color(0xFFEAF0F5),
            contentColor = H1Style.Ink
        ),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(8.dp))
        Text(
            destination.ifEmpty { "미설정" },
            Modifier.weight(1f, fill = false),
            color = H1Style.Muted,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun DestinationShortcutEditor(
    shortcuts: DestinationShortcuts,
    onSave: (String, String) -> Boolean,
    onDismiss: () -> Unit
) {
    var home by rememberSaveable { mutableStateOf(shortcuts.home) }
    var work by rememberSaveable { mutableStateOf(shortcuts.work) }
    var invalid by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("목적지 바로가기") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("비워 두고 저장하면 해당 바로가기가 지워져요.")
                OutlinedTextField(
                    home, { home = it; invalid = false }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("집 주소 또는 장소") }
                )
                OutlinedTextField(
                    work, { work = it; invalid = false }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("회사 주소 또는 장소") }
                )
                if (invalid) Text("주소나 장소 이름을 한 줄로 입력해 주세요.", color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(onClick = { if (onSave(home, work)) onDismiss() else invalid = true }) { Text("저장") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } }
    )
}

@Composable
private fun ChallengeHomeHero(distanceMeters: Long, score: Int?, modifier: Modifier) {
    val distance = String.format(Locale.KOREA, "%,.1f km", distanceMeters / 1_000.0)
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val imageHeight = (maxHeight - 60.dp).coerceIn(0.dp, 380.dp)
        val statOffset = imageHeight * .635f
        val statTop = -6.dp
        ChallengeHomeStat(
            "무사즈와", distance, "달렸어요",
            Modifier.align(Alignment.TopCenter).offset(x = -statOffset, y = statTop)
        )
        ChallengeHomeStat(
            "이번 주 안전점수", score?.let { "${it}점" } ?: "—",
            if (score == null) "아직 기록이 없어요" else "일별 최고점 평균",
            Modifier.align(Alignment.TopCenter).offset(x = statOffset, y = statTop)
        )
        Image(
            painterResource(R.drawable.musaz_challenge_home),
            contentDescription = null,
            modifier = Modifier.align(Alignment.Center).offset(y = 30.dp).size(imageHeight * 1.5f, imageHeight),
            contentScale = ContentScale.Fit
        )
    }
}

@Composable
private fun ChallengeHomeStat(title: String, value: String, caption: String, modifier: Modifier) {
    Surface(modifier.size(HomeStatDiameter), shape = CircleShape, color = Color.White.copy(alpha = .65f)) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 30.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically)
        ) {
            Text(title, color = H1Style.Ink, fontSize = 21.sp, fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center)
            BasicText(
                value,
                style = LocalTextStyle.current.copy(color = H1Style.Ink, fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center),
                maxLines = 1,
                autoSize = TextAutoSize.StepBased(minFontSize = 16.sp, maxFontSize = 44.sp)
            )
            Text(caption, color = H1Style.Muted, fontSize = 17.sp, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun DestinationSuggestions(
    recent: List<String>,
    query: String,
    enabled: Boolean,
    onSearch: (String) -> Unit,
    onSpot: (Destination) -> Unit,
    onMap: () -> Unit
) {
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val keyword = query.trim()
        if (keyword.isNotEmpty()) {
            item {
                DestinationRow("“$keyword” 검색", "목적지 검색", Icons.Outlined.Search, enabled) { onSearch(keyword) }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.CenterStart) {
                        Text("최근 검색한 목적지", fontSize = 21.sp, fontWeight = FontWeight.Bold)
                    }
                    if (recent.isEmpty()) {
                        Text("아직 검색 기록이 없어요.", Modifier.padding(vertical = 8.dp), color = H1Style.Muted)
                    }
                    recent.forEach { name ->
                        DestinationRow(name, null, Icons.Outlined.History, enabled) { onSearch(name) }
                    }
                }
                Column(Modifier.weight(1.25f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("지금 인기 챌린지 스팟", fontSize = 21.sp, fontWeight = FontWeight.Bold)
                        Text("시연 데이터", color = H1Style.Muted, fontSize = 13.sp)
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = onMap) { Text("챌린지 지도 보기") }
                    }
                    popularSpots.forEach { spot ->
                        val name = if (spot == hangangChallenge) "여의도 한강공원" else spot.name
                        DestinationRow(name, "참가 예시 ${spot.demoPeers}명", Icons.Outlined.LocationOn, enabled) {
                            onSpot(spot)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DestinationRow(
    title: String,
    detail: String?,
    icon: ImageVector,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Surface(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(14.dp), color = Color.White) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 54.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(icon, contentDescription = null, tint = H1Style.Ink, modifier = Modifier.size(21.dp))
            Text(title, Modifier.weight(1f), fontSize = 18.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            detail?.let { Text(it, color = H1Style.Muted, fontSize = 14.sp) }
            Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = H1Style.Muted)
        }
    }
}
