package ai.musaz.h1score

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.YearMonth
import ai.musaz.h1score.core.ChallengeRound

@Composable internal fun HistoryViewControls(
    monthly: Boolean,
    dateOrder: HistoryDateOrder,
    onMonthlyChange: (Boolean) -> Unit,
    onDateOrder: (HistoryDateOrder) -> Unit,
) {
    var sorting by remember { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.clip(CircleShape)
            .background(Color(0xFFEAEDF0)).border(1.dp, Color.White, CircleShape)
            .padding(4.dp).selectableGroup()) {
            listOf(false to "주", true to "월").forEach { (isMonthly, label) ->
                val active = monthly == isMonthly
                Box(Modifier.width(64.dp).height(44.dp).clip(CircleShape)
                    .background(if (active) H1Style.Ink else Color.Transparent)
                    .testTag(if (isMonthly) "history-view-month" else "history-view-week")
                    .semantics { contentDescription = if (isMonthly) "월간 보기" else "주간 보기" }
                    .selectable(selected = active, role = Role.Tab, onClick = {
                        sorting = false
                        if (!active) onMonthlyChange(isMonthly)
                    }), contentAlignment = Alignment.Center) {
                    Text(label, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                        color = if (active) Color.White else H1Style.Ink)
                }
            }
        }
        // Reserve this space so the calendar control never jumps when the list disappears.
        Box(Modifier.width(152.dp)) {
            if (!monthly) {
                OutlinedButton(onClick = { sorting = true },
                    modifier = Modifier.fillMaxWidth().height(44.dp).testTag("history-sort").semantics {
                        selected = true
                        contentDescription = "기록 정렬"
                    },
                    shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, H1Style.Line),
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.White, contentColor = H1Style.Ink),
                    contentPadding = PaddingValues(horizontal = 12.dp)) {
                    Icon(Icons.Outlined.Sort, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (dateOrder == HistoryDateOrder.NEWEST_FIRST) "최신순" else "오래된순", fontSize = 15.sp)
                    Spacer(Modifier.weight(1f))
                    Icon(Icons.Outlined.ExpandMore, null, Modifier.size(18.dp))
                }
                DropdownMenu(expanded = sorting, onDismissRequest = { sorting = false },
                    modifier = Modifier.width(200.dp).testTag("history-sort-menu"),
                    containerColor = Color.White, tonalElevation = 0.dp, shadowElevation = 4.dp,
                    shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, H1Style.Line)) {
                    Text("정렬 기준", Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        fontSize = 13.sp, color = H1Style.Muted)
                    HistoryDateOrder.entries.forEach { order ->
                        DropdownMenuItem(text = { Text(if (order == HistoryDateOrder.NEWEST_FIRST) "최신순" else "오래된순",
                            fontSize = 17.sp, color = H1Style.Ink,
                            fontWeight = if (order == dateOrder) FontWeight.SemiBold else FontWeight.Normal) },
                            modifier = Modifier.heightIn(min = 52.dp),
                            onClick = { onDateOrder(order); sorting = false },
                            trailingIcon = { if (order == dateOrder) Icon(Icons.Outlined.Check, null, tint = H1Style.Ink) })
                    }
                }
            }
        }
    }
}

/** The same surface expands; month dates converge on the selected week's seven center slots. */
@Composable internal fun HistoryCalendarPanel(
    expanded: Boolean,
    month: YearMonth,
    week: LocalDate,
    selectedDate: LocalDate?,
    records: List<CompletedDrive>,
    onMonthChange: (YearMonth) -> Unit,
    onWeekChange: (LocalDate) -> Unit,
    onDateSelected: (LocalDate) -> Unit,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val progress by animateFloatAsState(if (expanded) 1f else 0f,
        tween(460, easing = FastOutSlowInEasing), label = "history-month-expansion")
    val days = remember(month) { historyMonthDays(month) }
    val grouped = remember(records) {
        records.filter { historyRecordDay(it) != null }
            .groupBy { historyRecordDay(it)!! }
            .mapValues { (_, items) -> historyByDate(items, HistoryDateOrder.OLDEST_FIRST) }
    }
    val today = LocalDate.now(ChallengeRound.seoul)
    BoxWithConstraints(modifier) {
        val expandedHeight = maxHeight
        val panelHeight = 148.dp + (expandedHeight - 148.dp) * progress
        val monthlyAlpha = (progress / .18f).coerceIn(0f, 1f)
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().height(panelHeight).clip(RoundedCornerShape(22.dp))
                .background(lerp(lerp(H1Style.PaleMint, Color.White, .25f), Color.White, progress))) {
                // Preserve the pager during ordinary edge-date selections.
                if (progress < .18f) {
                    Box(Modifier.graphicsLayer { alpha = 1f - monthlyAlpha }
                        .then(if (expanded || progress > 0f) Modifier.clearAndSetSemantics { }
                            .pointerInput(Unit) {
                                awaitPointerEventScope { while (true) {
                                    awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                                } }
                            } else Modifier)) {
                        HistoryCalendar(week, selectedDate, grouped.keys, onWeekChange, onDateSelected, Modifier.fillMaxWidth())
                    }
                }
                if (progress > 0f) {
                    Column(Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 8.dp)
                        .graphicsLayer { alpha = monthlyAlpha }.testTag("history-month-grid")) {
                        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { onMonthChange(month.minusMonths(1)) }, enabled = expanded && progress == 1f) {
                                Icon(Icons.Outlined.ChevronLeft, "이전 달", Modifier.size(28.dp), tint = H1Style.Ink)
                            }
                            Text("${month.year}년 ${month.monthValue}월", Modifier.weight(1f).semantics { heading() },
                                textAlign = TextAlign.Center, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = H1Style.Ink)
                            IconButton(onClick = { onMonthChange(month.plusMonths(1)) }, enabled = expanded && progress == 1f) {
                                Icon(Icons.Outlined.ChevronRight, "다음 달", Modifier.size(28.dp), tint = H1Style.Ink)
                            }
                        }
                        Row(Modifier.fillMaxWidth().height(36.dp * progress)
                            .graphicsLayer { alpha = progress }, verticalAlignment = Alignment.CenterVertically) {
                            listOf("일", "월", "화", "수", "목", "금", "토").forEach { label ->
                                Text(label, Modifier.weight(1f), textAlign = TextAlign.Center,
                                    fontSize = 16.sp, color = H1Style.Muted)
                            }
                        }
                        HorizontalDivider(color = H1Style.Ink.copy(alpha = .07f * progress))
                        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                            val monthCellHeight = (expandedHeight - 109.dp) / (days.size / 7)
                            val monthCellWidth = maxWidth / 7
                            val weekCellWidth = maxWidth / 11
                            val compact = monthCellHeight < 90.dp
                            days.forEachIndexed { index, date ->
                                // Blank boundary slots keep the focus on the current month.
                                if (YearMonth.from(date) != month) return@forEachIndexed
                                val row = index / 7
                                val col = index % 7
                                val focusWeek = historyWeekStart(date) == week
                                val opacity = if (focusWeek) 1f else progress * progress
                                val wheelColumn = date.dayOfWeek.value - 1
                                val x = weekCellWidth * (wheelColumn + 2) * (1f - progress) + monthCellWidth * col * progress
                                val y = monthCellHeight * row * progress
                                val width = weekCellWidth + (monthCellWidth - weekCellWidth) * progress
                                val height = 68.dp + (monthCellHeight - 68.dp) * progress
                                val chosen = selectedDate == date
                                val scores = grouped[date].orEmpty()
                                Column(Modifier.offset(x = x, y = y).size(width, height)
                                    .graphicsLayer { alpha = opacity }
                                    .clickable(enabled = expanded && progress == 1f) {
                                        onWeekChange(historyWeekStart(date))
                                        onDateSelected(date)
                                        onCollapse()
                                    }.semantics(mergeDescendants = true) {
                                        contentDescription = "${date.year}년 ${date.monthValue}월 ${date.dayOfMonth}일"
                                        selected = chosen
                                        stateDescription = if (scores.isEmpty()) "주행 기록 없음" else "${scores.size}경기 · ${historyDayResults(scores)}"
                                    }, horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center) {
                                    val diameter = if (compact) 38.dp else 46.dp
                                    Box(Modifier.size(diameter).clip(CircleShape)
                                        .background(if (chosen) H1Style.Ink else Color.Transparent)
                                        .then(if (date == today && !chosen) Modifier.border(1.dp, H1Style.Ink.copy(alpha = .28f), CircleShape) else Modifier),
                                        contentAlignment = Alignment.Center) {
                                        Text(date.dayOfMonth.toString(), fontSize = if (compact) 26.sp else 30.sp,
                                            fontWeight = if (chosen || scores.isNotEmpty()) FontWeight.SemiBold else FontWeight.Normal,
                                            color = if (chosen) Color.White else H1Style.Ink)
                                    }
                                    // Equal score space keeps numerals aligned even on empty dates.
                                    Spacer(Modifier.height(if (compact) 4.dp else 8.dp))
                                    Box(Modifier.height(if (compact) 34.dp else 44.dp).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                                        if (scores.isNotEmpty()) Text(historyDayResults(scores),
                                            Modifier.graphicsLayer { alpha = progress },
                                            fontSize = if (compact) 15.sp else 19.sp,
                                            lineHeight = if (compact) 17.sp else 22.sp,
                                            fontWeight = FontWeight.Medium, textAlign = TextAlign.Center,
                                            color = Color(0xFF526D88))
                                    }
                                }
                            }
                        }
                    }
                }
            }
            // Transparent rows must never receive taps during a calendar transition.
            if (!expanded && progress == 0f) {
                Column(Modifier.weight(1f).fillMaxWidth(), content = content)
            }
        }
    }
}
