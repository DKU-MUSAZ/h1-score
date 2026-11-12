package ai.musaz.h1score

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.YearMonth

@Composable internal fun HistoryCalendar(
    month: YearMonth,
    selectedDate: LocalDate?,
    recordDates: Set<LocalDate>,
    onMonthChange: (YearMonth) -> Unit,
    onDateSelected: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val weeks = remember(month) { historyCalendarDays(month).chunked(7) }
    Surface(modifier, color = Color.White, shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onMonthChange(month.minusMonths(1)) }) {
                    Icon(Icons.Outlined.ChevronLeft, "이전 달", tint = H1Style.Ink)
                }
                Text("${month.year}년 ${month.monthValue}월", Modifier.weight(1f).semantics { heading() },
                    color = H1Style.Ink, fontSize = 19.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                IconButton(onClick = { onMonthChange(month.plusMonths(1)) }) {
                    Icon(Icons.Outlined.ChevronRight, "다음 달", tint = H1Style.Ink)
                }
            }
            Row(Modifier.fillMaxWidth().height(28.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf("일", "월", "화", "수", "목", "금", "토").forEach { weekday ->
                    Text(weekday, Modifier.weight(1f), color = H1Style.Muted, fontSize = 13.sp, textAlign = TextAlign.Center)
                }
            }
            Column(Modifier.selectableGroup()) {
                weeks.forEach { week ->
                    Row(Modifier.fillMaxWidth()) {
                        week.forEach { date ->
                            if (date == null) {
                                Spacer(Modifier.weight(1f).height(48.dp))
                            } else {
                                val selected = date == selectedDate
                                val hasRecords = date in recordDates
                                Box(
                                    Modifier.weight(1f).height(48.dp)
                                        .selectable(selected = selected, role = Role.RadioButton, onClick = { onDateSelected(date) })
                                        .semantics(mergeDescendants = true) {
                                            contentDescription = "${date.year}년 ${date.monthValue}월 ${date.dayOfMonth}일"
                                            stateDescription = if (hasRecords) "주행 기록 있음" else "주행 기록 없음"
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Column(
                                        Modifier.size(40.dp).background(if (selected) H1Style.Ink else Color.Transparent, CircleShape),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center,
                                    ) {
                                        Text(date.dayOfMonth.toString(), color = if (selected) Color.White else H1Style.Ink,
                                            fontSize = 16.sp, fontWeight = if (hasRecords) FontWeight.Bold else FontWeight.Normal)
                                        Spacer(Modifier.height(2.dp))
                                        Box(Modifier.size(4.dp).background(
                                            if (hasRecords) { if (selected) Color.White else H1Style.Ink } else Color.Transparent,
                                            CircleShape,
                                        ))
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp).height(20.dp),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(4.dp).background(H1Style.Ink, CircleShape))
                Spacer(Modifier.width(6.dp))
                Text("주행 기록이 있는 날", color = H1Style.Muted, fontSize = 12.sp)
            }
        }
    }
}
