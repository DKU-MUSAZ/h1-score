package ai.musaz.h1score

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Leaderboard
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ai.musaz.h1score.core.CollectionProgress
import ai.musaz.h1score.core.RankBadgeGoal
import ai.musaz.h1score.core.RankBadgeProgress
import ai.musaz.h1score.core.RankTier
import ai.musaz.h1score.core.SpotTour
import java.util.Locale

private val BadgeInk=H1Style.Ink
private val BadgeMuted=H1Style.Muted
private val BadgeSecondaryText = BadgeInk.copy(alpha = .78f)
private const val WelcomeBadgeTitle = "당신도 이제 무사즈!"
private fun points(value: Int)=String.format(Locale.KOREA,"%,d",value)
private enum class BadgeFamily(val title: String, val accent: Color, val tint: Color, val filterIcon: ImageVector) {
    PARTICIPATION("챌린지", Color(0xFF237B66), Color(0xFFE9F5EE), Icons.Outlined.Flag),
    SCORE("안전운전", Color(0xFF4472AB), Color(0xFFEDF3FB), Icons.Outlined.VerifiedUser),
    RANK("랭킹", Color(0xFFAA792A), Color(0xFFFCF4E4), Icons.Outlined.Leaderboard),
    ROUTINE("주행 습관", Color(0xFF8270AC), Color(0xFFF2EEF8), Icons.Outlined.CalendarMonth),
    SPOT("스팟투어", Color(0xFF2E6E75), Color(0xFFE8F5F6), Icons.Outlined.Map)
}
private data class CollectionBadge(
    val id: String,
    val family: BadgeFamily,
    val title: String,
    val condition: String,
    val target: Int,
    val reward: Int,
    val previewProgress: Int,
    @DrawableRes val artwork: Int,
    val unit: String = "일",
    val explanation: String = "조건을 채우면 컬렉션에 보관돼요.",
    val rankGoal: RankBadgeGoal? = null,
    val minimumScore: Int = 0,
    val spot: SpotTour? = null
) {
    val tier: RankTier? get() = rankGoal?.tier
    val artworkDescription: String
        get() = "${family.title} 무사즈 배지${tier?.let { " · ${it.title} 트로피" } ?: ""}"
}

private fun rankBadge(goal: RankBadgeGoal, title: String, reward: Int, @DrawableRes artwork: Int) =
    CollectionBadge(goal.id, BadgeFamily.RANK, title, "상위 ${goal.topPercent}% 이내 ${goal.targetDays}일",
        goal.targetDays, reward, 0, artwork, rankGoal = goal)

private fun spotBadge(spot: SpotTour, title: String, @DrawableRes artwork: Int) =
    CollectionBadge(spot.id, BadgeFamily.SPOT, title, "${spot.destinationName} · 3회 방문",
        3, 0, 0, artwork, unit = "회", spot = spot,
        explanation = "방문 기록을 시작한 뒤 내비 도착과 주차가 확인되면 기록돼요. 장소별로 하루 한 번 인정돼요.")

private val collectionBadges = listOf(
    CollectionBadge("join-5", BadgeFamily.PARTICIPATION, "첫 발걸음", "유효한 대회 5일 참여",
        5, 200, 12, R.drawable.musaz_badge_join_5),
    CollectionBadge("join-10", BadgeFamily.PARTICIPATION, "꾸준한 드라이버", "유효한 대회 10일 참여",
        10, 300, 12, R.drawable.musaz_badge_join_10),
    CollectionBadge("join-15", BadgeFamily.PARTICIPATION, "출근길 동반자", "유효한 대회 15일 참여",
        15, 500, 12, R.drawable.musaz_badge_join_15),
    CollectionBadge("join-20", BadgeFamily.PARTICIPATION, "함께 달린 무사즈", "유효한 대회 20일 참여",
        20, 1000, 12, R.drawable.musaz_badge_join_20),
    CollectionBadge("score-80-5", BadgeFamily.SCORE, "차분한 출발", "80점 이상 5일",
        5, 100, 10, R.drawable.musaz_badge_score_80_5, minimumScore = 80),
    CollectionBadge("score-80-10", BadgeFamily.SCORE, "안정적인 흐름", "80점 이상 10일",
        10, 200, 10, R.drawable.musaz_badge_score_80_10, minimumScore = 80),
    CollectionBadge("score-90-5", BadgeFamily.SCORE, "좋은 습관", "90점 이상 5일",
        5, 200, 6, R.drawable.musaz_badge_score_90_5, minimumScore = 90),
    CollectionBadge("score-90-10", BadgeFamily.SCORE, "믿음직한 드라이버", "90점 이상 10일",
        10, 400, 6, R.drawable.musaz_badge_score_90_10, minimumScore = 90),
    CollectionBadge("score-95-5", BadgeFamily.SCORE, "정교한 운전", "95점 이상 5일",
        5, 300, 2, R.drawable.musaz_badge_score_95_5, minimumScore = 95),
    rankBadge(RankBadgeGoal.FIRST_RANK, "순위 첫걸음", 50, R.drawable.musaz_trophy_bronze_raised_large),
    rankBadge(RankBadgeGoal.RANK_ADAPTED, "순위 적응 완료", 100, R.drawable.musaz_badge_rank_80),
    rankBadge(RankBadgeGoal.MIDFIELD, "중위권 돌파", 150, R.drawable.musaz_trophy_silver_raised_large),
    rankBadge(RankBadgeGoal.UPPER_RANK, "꾸준한 상위권", 250, R.drawable.musaz_badge_rank_30),
    rankBadge(RankBadgeGoal.TOP_REGULAR, "상위권 단골", 300, R.drawable.musaz_trophy_gold_raised_large),
    rankBadge(RankBadgeGoal.ACE, "무사즈 에이스", 700, R.drawable.musaz_trophy_platinum_raised_large),
    CollectionBadge("routine-week", BadgeFamily.ROUTINE, "주간 루틴", "한 주에 서로 다른 3일 참여",
        3, 50, 3, R.drawable.musaz_badge_routine_week,
        explanation = "월요일~일요일 중 3일에 참여하면 달성해요."),
    CollectionBadge("routine-month", BadgeFamily.ROUTINE, "네 주의 동행", "서로 다른 4개 주에 참여",
        4, 200, 3, R.drawable.musaz_badge_routine_month, unit = "주"),
    CollectionBadge("routine-score", BadgeFamily.ROUTINE, "꾸준한 좋은 흐름", "서로 다른 3개 주에서 90점 이상",
        3, 200, 2, R.drawable.musaz_badge_routine_score, unit = "주"),
    spotBadge(SpotTour.SEOUL, "서울 오토 라이브러리", R.drawable.musaz_badge_spot_seoul),
    spotBadge(SpotTour.GOYANG, "고양 모빌리티 탐험", R.drawable.musaz_badge_spot_goyang),
    spotBadge(SpotTour.BUSAN, "부산 디자인 산책", R.drawable.musaz_badge_spot_busan)
)

private val rankExample = RankBadgeProgress(mapOf(
    RankBadgeGoal.FIRST_RANK to 5, RankBadgeGoal.RANK_ADAPTED to 10,
    RankBadgeGoal.MIDFIELD to 5, RankBadgeGoal.UPPER_RANK to 4
))

private val exampleBadgePoints = collectionBadges
    .filter { (it.rankGoal?.let(rankExample::days) ?: it.previewProgress) >= it.target }
    .sumOf { it.reward } + 100 // The original reward example includes two additional weekly routine bonuses.

@Composable internal fun BadgeCollectionScreen(
    legacyOwned: Set<String>,
    rankProgress: RankBadgeProgress = RankBadgeProgress(),
    collectionProgress: CollectionProgress = CollectionProgress(),
    demo: Boolean = false,
    trackedSpot: SpotTour? = null,
    onStartSpot: (SpotTour) -> Unit = {},
    onCancelSpot: () -> Unit = {},
    onChallenge: () -> Unit
) {
    var family by remember { mutableStateOf<BadgeFamily?>(null) }
    var preview by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<CollectionBadge?>(null) }
    var showWelcomeBadge by remember { mutableStateOf(false) }
    var guide by remember { mutableStateOf(false) }
    var legacy by remember { mutableStateOf(false) }
    val ranks = if (preview) rankExample else rankProgress
    val rankAvailable = preview || demo
    fun rankLock(badge: CollectionBadge): String? = badge.tier
        ?.takeUnless(ranks::isUnlocked)?.let(::previousTierMessage)
    fun progress(badge: CollectionBadge): Int? {
        badge.rankGoal?.let {
            return if (rankAvailable && ranks.isUnlocked(it.tier)) ranks.days(it) else null
        }
        badge.spot?.let { return if (demo) null else collectionProgress.visits(it).coerceAtMost(badge.target) }
        val value = if (preview) badge.previewProgress else when (badge.family) {
            BadgeFamily.PARTICIPATION -> collectionProgress.challengeDays
            BadgeFamily.SCORE -> collectionProgress.scoreDays(badge.minimumScore)
            BadgeFamily.ROUTINE -> when (badge.id) {
                "routine-week" -> collectionProgress.bestWeekDays
                "routine-month" -> collectionProgress.challengeWeeks
                else -> collectionProgress.scoreWeeks(90)
            }
            BadgeFamily.RANK, BadgeFamily.SPOT -> return null
        }
        return value.coerceAtMost(badge.target)
    }
    val earned = collectionBadges.filter { progress(it) == it.target }
    val categories = family?.let { listOf(it) } ?: BadgeFamily.entries
    val scroll = rememberLazyGridState()
    CompositionLocalProvider(LocalTextStyle provides androidx.compose.ui.text.TextStyle(color=BadgeInk,fontSize=14.sp)) {
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxWidth().padding(bottom = 16.dp)
                .background(Color(0xFFE9EDF2), RoundedCornerShape(20.dp))
                .padding(horizontal = 18.dp, vertical = 10.dp)
        ) {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Text("차곡차곡 모은 배지", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    if (preview) {
                        Text("예시 기록", Modifier.padding(start = 14.dp), fontSize = 14.sp, color = BadgeSecondaryText)
                    }
                }
                TextButton(onClick = { preview = !preview }) {
                    Icon(
                        if (preview) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (preview) "예시 종료" else "시연용 - 달성 예시보기", fontSize = 14.sp)
                }
                TextButton(onClick = { guide = true }) {
                    Icon(Icons.Outlined.Info, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("배지 안내", fontSize = 14.sp)
                }
            }
            Row(
                Modifier.fillMaxWidth().selectableGroup(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    painterResource(R.drawable.ic_badge_filter_sliders), contentDescription = null,
                    modifier = Modifier.size(20.dp), tint = BadgeSecondaryText
                )
                (listOf(null) + BadgeFamily.entries).forEach { category ->
                    val active = family == category
                    val contentColor = if (active) Color.White else BadgeInk
                    Row(
                        Modifier.heightIn(min = 48.dp)
                            .clip(CircleShape)
                            .background(if (active) BadgeInk else Color.White.copy(alpha = .7f))
                            .selectable(selected = active, role = Role.RadioButton) {
                                if (!active) {
                                    family = category
                                    scroll.requestScrollToItem(0)
                                }
                            }
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        Icon(
                            category?.filterIcon ?: Icons.Outlined.GridView,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = contentColor
                        )
                        Text(
                            category?.title ?: "전체",
                            fontSize = 16.sp,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                            color = contentColor
                        )
                    }
                }
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            state = scroll,
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 8.dp)
        ) {
            if (family == null) {
                item(key = "welcome-heading", span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        "시작 배지",
                        Modifier.semantics { heading() },
                        fontSize = 19.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                item(key = "welcome-badge", span = { GridItemSpan(maxLineSpan) }) {
                    WelcomeBadgeCard(onClick = { showWelcomeBadge = true })
                }
            }
            categories.forEachIndexed { index, category ->
                val cards = collectionBadges.filter { it.family == category }
                item(key = category.name, span = { GridItemSpan(maxLineSpan) }) {
                    Row(
                        Modifier.fillMaxWidth().padding(top = if (family == null || index > 0) 12.dp else 0.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            if (family == null) category.title else "${category.title} 배지",
                            Modifier.semantics { heading() },
                            fontSize = 19.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (family != null) {
                            val count = if ((category != BadgeFamily.RANK || rankAvailable) &&
                                (category != BadgeFamily.SPOT || !demo)) {
                                "${cards.size}종 중 ${earned.count { it.family == category }}개 획득"
                            } else {
                                "${cards.size}종"
                            }
                            Text(count, fontSize = 14.sp, color = BadgeSecondaryText)
                        }
                    }
                }
                val groups = if (category == BadgeFamily.RANK) {
                    RankTier.entries.map { tier -> tier to cards.filter { it.tier == tier } }
                } else {
                    listOf(null to cards)
                }
                groups.forEach { (tier, group) ->
                    if (tier != null) {
                        item(key = "rank-tier-${tier.name}", span = { GridItemSpan(maxLineSpan) }) {
                            RankTierHeading(tier, ranks, rankAvailable)
                        }
                    }
                    items(group, key = { it.id }) { badge ->
                        val unavailable = when {
                            rankLock(badge) != null -> "티어 잠금"
                            badge.family == BadgeFamily.SPOT -> "실차에서 집계"
                            else -> "집계 준비 중"
                        }
                        CollectionCard(badge, progress(badge), unavailable, onClick = { selected = badge })
                    }
                }
            }
        }
    }
    if (showWelcomeBadge) {
        AlertDialog(
            onDismissRequest = { showWelcomeBadge = false },
            title = {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    WelcomeBadgeArtwork(Modifier.size(128.dp))
                    Text(WelcomeBadgeTitle, Modifier.padding(top = 12.dp), fontSize = 23.sp, fontWeight = FontWeight.Bold)
                }
            },
            text = { Text("앱을 처음 실행한 모든 무사즈에게 주는 환영 배지예요.") },
            confirmButton = { TextButton(onClick = { showWelcomeBadge = false }) { Text("확인") } }
        )
    }
    selected?.let { badge ->
        val value = progress(badge)
        val complete = value == badge.target
        val lockedReason = rankLock(badge)
        AlertDialog(onDismissRequest={selected=null},title={
            Column(horizontalAlignment=Alignment.CenterHorizontally,modifier=Modifier.fillMaxWidth()) {
                BadgeEmblem(badge.artwork, badge.artworkDescription, badge.family.tint, locked = !complete, modifier = Modifier.size(128.dp))
                Text(badge.title,fontSize=23.sp,fontWeight=FontWeight.Bold,modifier=Modifier.padding(top=12.dp))
            }
        },text={Column {
            Text("${badge.family.title} 컬렉션${badge.tier?.let { " · ${it.title} 트로피" } ?: ""}",color=badge.family.accent,fontSize=12.sp)
            Text(badge.condition,fontSize=17.sp,fontWeight=FontWeight.SemiBold,modifier=Modifier.padding(top=12.dp))
            if (badge.family == BadgeFamily.RANK && !rankAvailable) {
                Text("집계 준비 중", Modifier.padding(top = 12.dp), color = BadgeSecondaryText, fontSize = 14.sp)
            }
            if (lockedReason != null) {
                Text(lockedReason, Modifier.padding(top = 12.dp), color = BadgeSecondaryText, fontSize = 14.sp)
            } else if (value != null) {
                Text("$value / ${badge.target}${badge.unit} · ${if (complete) "획득" else "진행 중"}",
                    fontSize = 12.sp, color = BadgeMuted, modifier = Modifier.padding(vertical = 10.dp))
                LinearProgressIndicator(progress = { value.toFloat() / badge.target },
                    color = badge.family.accent, trackColor = badge.family.tint, modifier = Modifier.fillMaxWidth())
            }
            if (badge.reward > 0 && preview) {
                Text("달성 보너스 +${points(badge.reward)}P · 예시",fontSize=17.sp,fontWeight=FontWeight.Bold,
                    color=badge.family.accent,modifier=Modifier.padding(top=14.dp))
            }
            Text(badge.explanation, Modifier.padding(top = 10.dp), fontSize=13.sp,color=BadgeMuted)
        }},confirmButton={TextButton(onClick={selected=null}){Text("확인")}},
            dismissButton={
                val spot = badge.spot
                when {
                    spot != null && trackedSpot == spot ->
                        TextButton(onClick = { selected = null; onCancelSpot() }) { Text("방문 기록 종료") }
                    spot != null && !demo && !preview && !complete && trackedSpot == null ->
                        TextButton(onClick = { selected = null; onStartSpot(spot) }) { Text("방문 기록 시작") }
                    spot == null && !complete && lockedReason == null && (rankAvailable || badge.rankGoal == null) ->
                        TextButton(onClick = { selected = null; onChallenge() }) { Text("챌린지 둘러보기") }
                }
            })
    }
    if (guide) BadgeGuide(initialFamily = family, demo = demo,
        onDismiss = { guide = false }, onLegacy = { guide = false; legacy = true })
    if(legacy) AlertDialog(onDismissRequest={legacy=false},title={Text("이전 여정의 배지")},text={Column(Modifier.verticalScroll(rememberScrollState())) {
        if(legacyOwned.isEmpty())Text("아직 이전 배지가 없어요. 새로운 여정에서 하나씩 모아봐요.")
        legacyOwned.sorted().forEach { id -> Text(when { id=="first-drive"->"첫 무사 도착"; id=="top-three"->"무사 TOP 3"; id.startsWith("visit:")->"첫 발자국 · ${id.removePrefix("visit:")}";else->id },modifier=Modifier.padding(vertical=7.dp)) }
        Text("이전 배지는 이곳에 따로 보관돼요.",fontSize=12.sp,color=BadgeMuted,modifier=Modifier.padding(top=10.dp))
    }},confirmButton={TextButton(onClick={legacy=false}){Text("확인")}})
    }
}

@Composable private fun BadgeGuide(
    initialFamily: BadgeFamily?,
    demo: Boolean,
    onDismiss: () -> Unit,
    onLegacy: () -> Unit
) {
    val first = initialFamily ?: BadgeFamily.PARTICIPATION
    val sections = listOf(first) + BadgeFamily.entries.filter { it != first }
    var expanded by remember { mutableStateOf<BadgeFamily?>(first) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.widthIn(max = 1120.dp).fillMaxWidth(.94f).fillMaxHeight(.92f),
            shape = RoundedCornerShape(24.dp),
            color = Color.White,
            contentColor = BadgeInk
        ) {
            Column(Modifier.padding(horizontal = 30.dp, vertical = 20.dp)) {
                Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("배지 획득 안내", Modifier.weight(1f).semantics { heading() },
                        fontSize = 38.sp, lineHeight = 46.sp, fontWeight = FontWeight.Bold)
                    TextButton(onClick = onDismiss) { Text("닫기", fontSize = 22.sp) }
                }
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        Text("배지마다 인정되는 기록과 반영 시점이 달라요. 집계 날짜는 한국시간 기준이에요.",
                            Modifier.padding(bottom = 8.dp), fontSize = 22.sp, lineHeight = 30.sp,
                            color = BadgeSecondaryText)
                    }
                    items(sections, key = { it.name }) { category ->
                        val open = expanded == category
                        val status = when {
                            category == BadgeFamily.RANK && !demo -> "집계 준비 중"
                            category == BadgeFamily.SPOT && demo -> "실차에서 집계"
                            demo -> "시연 집계"
                            else -> "실제 기록 집계"
                        }
                        Column(Modifier.fillMaxWidth().background(if (open) category.tint else Color(0xFFF4F6F8),
                            RoundedCornerShape(14.dp))) {
                            Surface(
                                onClick = { expanded = if (open) null else category },
                                modifier = Modifier.semantics { stateDescription = if (open) "펼쳐짐" else "접힘" },
                                shape = RoundedCornerShape(14.dp),
                                color = Color.Transparent
                            ) {
                                Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                    Text(category.title, Modifier.weight(1f), fontSize = 28.sp, fontWeight = FontWeight.Bold)
                                    Text(status, fontSize = 20.sp, color = BadgeSecondaryText)
                                    Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                                        null, Modifier.size(28.dp))
                                }
                            }
                            if (open) BadgeGuideDetails(category)
                        }
                    }
                    item {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("화면의 배지 보너스는 시연용 예시예요.",
                                Modifier.weight(1f), fontSize = 20.sp, lineHeight = 28.sp, color = BadgeSecondaryText)
                            TextButton(onClick = onLegacy) { Text("이전 배지", fontSize = 22.sp) }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun BadgeGuideDetails(category: BadgeFamily) {
    val timing = when (category) {
        BadgeFamily.PARTICIPATION -> "회차 안에 유효한 챌린지를 완료하고 주차하면 반영돼요. 하루에 한 번 인정돼요."
        BadgeFamily.SCORE -> "유효한 챌린지가 끝나고 주차하면 반영돼요. 같은 날에는 회차 안에 얻은 최고 점수를 써요."
        BadgeFamily.ROUTINE -> "회차 안에 유효한 챌린지를 완료하고 주차하면 반영돼요. 한 주는 월요일부터 일요일까지예요."
        BadgeFamily.RANK -> "브론즈 배지 2개 획득 → 실버 해금. 다음 티어는 해금 이후의 순위 기록부터 하루 한 번 집계해요."
        BadgeFamily.SPOT -> "방문 기록 시작 → Pleos 내비 목적지 도착 → 주차. 장소마다 하루 한 번 기록돼요."
    }
    Column(Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("집계 시점", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(timing, fontSize = 22.sp, lineHeight = 30.sp, color = BadgeSecondaryText)
        if (category == BadgeFamily.PARTICIPATION || category == BadgeFamily.SCORE || category == BadgeFamily.ROUTINE) {
            Text("유효한 챌린지는 출발 시 10km 이상이며, 기록된 유효 거리 9km 이상과 데이터 수신율 90% 이상이 기준이에요.",
                fontSize = 20.sp, lineHeight = 28.sp, color = BadgeSecondaryText)
        }
        Text("획득 조건", Modifier.padding(top = 6.dp), fontSize = 24.sp, fontWeight = FontWeight.Bold)
        collectionBadges.filter { it.family == category }.forEach { badge ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Text(badge.title, Modifier.weight(1f), fontSize = 22.sp, lineHeight = 30.sp,
                    fontWeight = FontWeight.Medium)
                Text(badge.condition, Modifier.weight(1.2f), fontSize = 22.sp, lineHeight = 30.sp,
                    color = BadgeSecondaryText, textAlign = TextAlign.End)
            }
        }
    }
}

@Composable private fun WelcomeBadgeCard(onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(20.dp), color = Color.White) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            WelcomeBadgeArtwork(Modifier.size(100.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(WelcomeBadgeTitle, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                Text("앱을 시작한 모든 무사즈에게", fontSize = 16.sp, color = BadgeSecondaryText)
                Text("기본 획득", fontSize = 14.sp, color = H1Style.Green)
            }
        }
    }
}

@Composable private fun WelcomeBadgeArtwork(modifier: Modifier) {
    BadgeEmblem(
        R.drawable.musaz_badge_welcome, "무사즈 환영 배지", H1Style.PaleMint,
        locked = false, modifier = modifier
    )
}

@Composable internal fun MonthlyOilVoucherInfo(onDismiss: () -> Unit) {
    val exampleTotal = 1500 + exampleBadgePoints
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("엔진오일 교환권 안내") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("목표 50,000P", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = BadgeInk)
                Text("대회 포인트 + 배지 보너스")
                Text("예시 · 대회 1,500P + 배지 ${points(exampleBadgePoints)}P = ${points(exampleTotal)}P")
                Text("교환권은 제공 준비 중입니다. 실제 제휴·사용처·지원 차종·추가금 조건은 확정 후 안내합니다.")
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("확인") } }
    )
}

private fun previousTierMessage(tier: RankTier): String {
    val previous = RankTier.entries[tier.ordinal - 1]
    val count = RankBadgeGoal.entries.count { it.tier == previous }
    return "${previous.title} 배지 ${count}개를 모두 획득하면 열려요"
}

@Composable private fun RankTierHeading(tier: RankTier, ranks: RankBadgeProgress, available: Boolean) {
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        HorizontalDivider(color = H1Style.Line)
        Row(
            Modifier.padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(tier.title, Modifier.semantics { heading() }, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            val status = when {
                ranks.completed(tier) -> "완료"
                ranks.isUnlocked(tier) -> if (available) "획득 가능" else "집계 준비 중"
                else -> previousTierMessage(tier)
            }
            Text(status, fontSize = 14.sp, color = BadgeSecondaryText)
        }
    }
}

@Composable private fun CollectionCard(badge: CollectionBadge, progress: Int?, unavailable: String, onClick: () -> Unit) {
    val complete = progress != null && progress >= badge.target
    val progressLabel = progress?.let {
        "$it / ${badge.target}${badge.unit}" + if (complete) " · 획득" else ""
    } ?: unavailable
    Surface(onClick = onClick, shape = RoundedCornerShape(20.dp), color = Color.White) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BadgeEmblem(badge.artwork, badge.artworkDescription, badge.family.tint, locked = !complete, modifier = Modifier.size(116.dp))
            Column(Modifier.weight(1f)) {
                Text(badge.title, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                Text(badge.condition, Modifier.padding(top = 3.dp), fontSize = 16.sp, color = BadgeSecondaryText)
                Text(
                    progressLabel,
                    Modifier.padding(top = 8.dp, bottom = 5.dp),
                    fontSize = 14.sp,
                    color = if (complete) H1Style.Green else BadgeSecondaryText
                )
                val track = Modifier.fillMaxWidth().height(4.dp)
                if (progress == null) {
                    Spacer(track.background(H1Style.Line, CircleShape))
                } else {
                    LinearProgressIndicator(
                        progress = { progress.toFloat() / badge.target },
                        modifier = track,
                        color = H1Style.Green,
                        trackColor = H1Style.Line,
                        gapSize = 0.dp,
                        drawStopIndicator = {}
                    )
                }
            }
        }
    }
}

@Composable private fun BadgeEmblem(
    @DrawableRes artwork: Int,
    artworkDescription: String,
    tint: Color,
    locked: Boolean,
    modifier: Modifier = Modifier
) {
    Box(modifier.semantics {
        contentDescription = artworkDescription
        stateDescription = if (locked) "잠금" else "획득"
    }) {
        Box(Modifier.matchParentSize().alpha(if (locked) .36f else 1f), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val shield = Path().apply {
                    moveTo(w * .5f, h * .07f)
                    lineTo(w * .86f, h * .2f)
                    lineTo(w * .82f, h * .59f)
                    quadraticTo(w * .78f, h * .83f, w * .5f, h * .96f)
                    quadraticTo(w * .22f, h * .83f, w * .18f, h * .59f)
                    lineTo(w * .14f, h * .2f)
                    close()
                }
                drawPath(shield, tint)
            }
            Image(
                painter = painterResource(artwork),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(.94f).offset(y = 3.dp),
                contentScale = ContentScale.Fit
            )
        }
        if (locked) {
            Box(
                Modifier.align(Alignment.BottomEnd).padding(3.dp).size(26.dp)
                    .background(H1Style.Line, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Outlined.Lock, null, Modifier.size(15.dp), tint = BadgeInk)
            }
        }
    }
}
