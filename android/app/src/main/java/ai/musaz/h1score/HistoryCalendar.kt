package ai.musaz.h1score

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs

private const val CenterWeekPage = 10_000
private const val WeekPageCount = CenterWeekPage * 2 + 1
private val CalendarOrigin = LocalDate.of(2020, 1, 6)
private val WeekDate = DateTimeFormatter.ofPattern("M월 d일", Locale.KOREAN)
private val Weekday = DateTimeFormatter.ofPattern("E", Locale.KOREAN)

@Composable internal fun HistoryCalendar(
    week: LocalDate,
    selectedDate: LocalDate?,
    recordDates: Set<LocalDate>,
    onWeekChange: (LocalDate) -> Unit,
    onDateSelected: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val initialPage = (CenterWeekPage + ChronoUnit.WEEKS.between(CalendarOrigin, week))
        .coerceIn(0L, (WeekPageCount - 1).toLong()).toInt()
    val pager = rememberPagerState(initialPage = initialPage) { WeekPageCount }
    val scope = rememberCoroutineScope()
    val visibleWeek = CalendarOrigin.plusWeeks((pager.currentPage - CenterWeekPage).toLong())
    val end = visibleWeek.plusDays(6)
    val endLabel = if (end.year == visibleWeek.year) end.format(WeekDate) else "${end.year}년 ${end.format(WeekDate)}"

    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.collect { page ->
            onWeekChange(CalendarOrigin.plusWeeks((page - CenterWeekPage).toLong()))
        }
    }

    Surface(modifier, color = lerp(H1Style.PaleMint, Color.White, .25f), shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.padding(horizontal = 22.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${visibleWeek.year}년 ${visibleWeek.format(WeekDate)} – $endLabel",
                    Modifier.weight(1f).semantics { heading() },
                    color = H1Style.Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                )
                IconButton(
                    enabled = pager.canScrollBackward,
                    onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } },
                ) { Icon(Icons.Outlined.ChevronLeft, "이전 주", tint = H1Style.Ink) }
                IconButton(
                    enabled = pager.canScrollForward,
                    onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
                ) { Icon(Icons.Outlined.ChevronRight, "다음 주", tint = H1Style.Ink) }
            }
            BoxWithConstraints(Modifier.fillMaxWidth().height(84.dp)) {
                // Each page is seven slots wide, with two slots from each neighbor in view.
                val dayWidth = maxWidth / 11
                HorizontalPager(
                    state = pager,
                    modifier = Modifier.fillMaxSize().testTag("history-week-strip"),
                    contentPadding = PaddingValues(horizontal = dayWidth * 2),
                    beyondViewportPageCount = 1,
                ) { page ->
                    val start = CalendarOrigin.plusWeeks((page - CenterWeekPage).toLong())
                    Row(Modifier.fillMaxSize().selectableGroup(), verticalAlignment = Alignment.CenterVertically) {
                        repeat(7) { index ->
                            val date = start.plusDays(index.toLong())
                            val selected = date == selectedDate
                            val hasRecords = date in recordDates
                            Box(
                                Modifier.weight(1f).fillMaxHeight()
                                    .selectable(selected = selected, role = Role.RadioButton, onClick = {
                                        onDateSelected(date)
                                        if (page != pager.currentPage) {
                                            scope.launch { pager.animateScrollToPage(page) }
                                        }
                                    })
                                    .semantics(mergeDescendants = true) {
                                        contentDescription = "${date.year}년 ${date.monthValue}월 ${date.dayOfMonth}일"
                                        stateDescription = if (hasRecords) "주행 기록 있음" else "주행 기록 없음"
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                HistoryDialDay(date, selected, hasRecords, Modifier.graphicsLayer {
                                    val slot = index - 3 + pager.getOffsetDistanceInPages(page) * 7f
                                    val depth = ((abs(slot) - 2.5f) / 2.5f).coerceIn(0f, 1f)
                                    val edgeFade = ((abs(slot) - 3f) / 2f).coerceIn(0f, 1f)
                                    rotationY = (slot / 5f).coerceIn(-1f, 1f) * depth * 36f
                                    scaleX = 1f - depth * .13f
                                    scaleY = scaleX
                                    alpha = 1f - edgeFade * .65f
                                    cameraDistance = 14.dp.toPx()
                                })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun HistoryDialDay(
    date: LocalDate,
    selected: Boolean,
    hasRecords: Boolean,
    modifier: Modifier,
) {
    val ink = if (selected) Color.White else H1Style.Ink
    Column(
        modifier.size(width = 65.dp, height = 68.dp)
            .background(if (selected) H1Style.Ink else Color.White, RoundedCornerShape(15.dp)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(date.format(Weekday), color = if (selected) ink else H1Style.Muted, fontSize = 14.sp)
        Text(date.dayOfMonth.toString(), color = ink, fontSize = 27.sp,
            fontWeight = if (selected || hasRecords) FontWeight.Bold else FontWeight.Medium)
        Spacer(Modifier.height(2.dp))
        Box(Modifier.size(5.dp).background(if (hasRecords) ink else Color.Transparent, CircleShape))
    }
}
