package ai.musaz.h1score

import ai.musaz.h1score.core.ChallengeRound
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.util.Locale

internal val popularSpots get() = popularChallengeSpots(challengeSpots)
internal fun popularChallengeSpots(spots: List<Destination>) = spots.filter { it.demoPeers >= 10 }
    .sortedWith(compareByDescending<Destination> { it.demoPeers }.thenBy { it.name })
    .take(3)
private val HomeStatDiameter = 216.dp

@Composable
internal fun ChallengeHomeScreen(vm: ChallengeViewModel) {
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var mapSpot by remember { mutableStateOf<Destination?>(null) }
    var showMap by rememberSaveable { mutableStateOf(false) }
    var shortcutId by rememberSaveable { mutableStateOf<String?>(null) }
    var editingShortcuts by rememberSaveable { mutableStateOf(false) }
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
        shortcutId = null
        editingShortcuts = true
    }

    fun openShortcut(id: String) {
        if (!canChoose) return
        val item=vm.destinationShortcuts.find { it.id==id } ?: return
        if (item.place==null) { editShortcuts(); shortcutId=id }
        else vm.navigateShortcut(id)
    }

    fun chooseSpot(spot: Destination) {
        if (!canChoose) return
        hideKeyboard()
        vm.rememberDestination(spot.name)
        mapSpot = spot
        showMap = true
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
        DestinationCompetitionCard(vm)
        if (showMap) {
            TextButton(onClick = { showMap = false }) { Text("목적지 검색으로 돌아가기") }
            ChallengeMap(
                vm,
                Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(24.dp))
                    .background(H1Style.heroBackground),
                initialSpot = mapSpot
            )
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it; searching = true },
                    modifier = Modifier.weight(1f).heightIn(min = 68.dp).tutorialTarget("destination-search").onFocusChanged { if (it.isFocused) searching = true },
                    enabled = canChoose,
                    singleLine = true,
                    shape = RoundedCornerShape(34.dp),
                    placeholder = { Text("목적지 또는 장소를 검색해 주세요", fontSize = 21.sp) },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(26.dp)) },
                    trailingIcon = if (query.isNotEmpty()) {
                        { IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Close, "검색어 지우기") } }
                    } else null,
                    textStyle = LocalTextStyle.current.copy(fontSize = 21.sp),
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
                        onMap = { hideKeyboard(); mapSpot=null; showMap = true }
                    )
                }
            } else {
                val score = vm.collectionProgress.weeklyScoreAverage(LocalDate.now(ChallengeRound.seoul))
                Column(
                    Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(28.dp))
                        .background(H1Style.heroBackground).padding(top = 18.dp, bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp)
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
    if (searching && editingShortcuts && canChoose) {
        DestinationShortcutEditor(vm, shortcutId) { editingShortcuts = false }
    }
}

@Composable
private fun DestinationShortcutRow(
    shortcuts: List<DestinationShortcut>,
    enabled: Boolean,
    onOpen: (String) -> Unit,
    onEdit: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            shortcuts.forEach { item ->
                DestinationShortcutButton(item.label, item.place?.name.orEmpty(),
                    when(item.id) { "home" -> Icons.Filled.Home; "work" -> Icons.Filled.Work; else -> Icons.Outlined.LocationOn }, enabled) { onOpen(item.id) }
            }
        }
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
private fun ChallengeHomeHero(distanceMeters: Long, score: Int?, modifier: Modifier) {
    val distance = String.format(Locale.KOREA, "%,.1f km", distanceMeters / 1_000.0)
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val imageHeight = (maxHeight - 20.dp).coerceIn(0.dp, 380.dp)
        val statDiameter = (imageHeight * .62f).coerceIn(144.dp, HomeStatDiameter)
        val maxStatOffset = ((maxWidth - statDiameter) / 2 - 12.dp).coerceAtLeast(0.dp)
        // The mascot PNG has transparent side margins: match the visible figure,
        // with the two circles above its hands, as in the reference layout.
        val statOffset = (statDiameter / 2 + imageHeight * .30f).coerceAtMost(maxStatOffset)
        val statTop = (maxHeight - imageHeight - 90.dp).coerceAtLeast(18.dp)
        ChallengeHomeStat(
            "무사즈와", distance, "달렸어요",
            Modifier.align(Alignment.TopCenter).offset(x = -statOffset, y = statTop), statDiameter
        )
        ChallengeHomeStat(
            "이번 주 안전점수", score?.let { "${it}점" } ?: "—",
            if (score == null) "아직 기록이 없어요" else "일별 최고점 평균",
            Modifier.align(Alignment.TopCenter).offset(x = statOffset, y = statTop), statDiameter
        )
        Image(
            painterResource(R.drawable.musaz_challenge_home),
            contentDescription = null,
            modifier = Modifier.align(Alignment.BottomCenter).offset(y = 8.dp).size(imageHeight * 1.5f, imageHeight),
            contentScale = ContentScale.Fit
        )
    }
}

@Composable
private fun ChallengeHomeStat(title: String, value: String, caption: String, modifier: Modifier, diameter: Dp) {
    val scale = diameter / HomeStatDiameter
    Box(modifier.size(diameter)) {
        Box(Modifier.matchParentSize().offset(x = 6.dp * scale, y = 8.dp * scale)
            .background(H1Style.Ink.copy(alpha = .12f), CircleShape))
        Surface(Modifier.fillMaxSize(), shape = CircleShape, color = H1Style.Ink,
            shadowElevation = 8.dp * scale, border = BorderStroke(1.dp, Color.White.copy(alpha = .18f))) {
            Column(
                Modifier.fillMaxSize().padding(horizontal = 30.dp * scale, vertical = 12.dp * scale),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp * scale, Alignment.CenterVertically)
            ) {
                Text(title, color = Color.White, fontSize = 21.sp * scale, fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center)
                BasicText(
                    value,
                    style = LocalTextStyle.current.copy(color = Color.White, fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center),
                    maxLines = 1,
                    autoSize = TextAutoSize.StepBased(minFontSize = 16.sp * scale, maxFontSize = 44.sp * scale)
                )
                Text(caption, color = Color(0xFFDCE3EC), fontSize = 17.sp * scale, textAlign = TextAlign.Center)
            }
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
                        Text("10명 이상 · 시연", color = H1Style.Muted, fontSize = 13.sp)
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = onMap) { Text("챌린지 지도 보기") }
                    }
                    if (popularSpots.isEmpty()) Text("아직 10명 이상 모인 스팟이 없어요.", color=H1Style.Muted)
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
