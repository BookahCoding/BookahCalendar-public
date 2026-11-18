package dev.bookah.calendar.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.bookah.calendar.R
import dev.bookah.calendar.UiState
import dev.bookah.calendar.data.CalendarInfo
import dev.bookah.calendar.design.ChipColor
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

private val CHIP_HEIGHT = 15.dp
private val CHIP_GAP = 2.dp
private val NUMBER_ROW = 24.dp

/** Month: 6 rows of 7 rounded tiles, Monday first, swipe sideways for the other months (docs/design-spec.md 2.2). */
@Composable
fun MonthScreen(state: UiState, onMenu: () -> Unit, onOpenDay: (LocalDate) -> Unit) {
    val first = state.days.firstOrNull()?.date
    val last = state.days.lastOrNull()?.date
    val firstMonth = first?.let { YearMonth.from(it) } ?: YearMonth.now()
    val monthCount = if (last == null) 1 else (YearMonth.from(last).year * 12 + YearMonth.from(last).monthValue) - (firstMonth.year * 12 + firstMonth.monthValue) + 1
    val thisMonth = YearMonth.now()
    val todayPage = ((thisMonth.year * 12 + thisMonth.monthValue) - (firstMonth.year * 12 + firstMonth.monthValue)).coerceIn(0, monthCount - 1)
    val pager = rememberPagerState(initialPage = todayPage) { monthCount }
    val scope = rememberCoroutineScope()
    val shown = firstMonth.plusMonths(pager.currentPage.toLong())
    val colors = remember(state.calendars) { state.calendars.associateBy { it.id } }

    Column(Modifier.fillMaxSize().safeDrawingPadding().background(MaterialTheme.colorScheme.background)) {
        AppTopBar(
            title = shown.month.getDisplayName(TextStyle.FULL_STANDALONE, Locale.getDefault()) + " " + shown.year, back = false, onNav = onMenu,
            actions = { TextButton({ scope.launch { pager.animateScrollToPage(todayPage) } }) { Text(stringResource(R.string.today)) } },
        )
        SyncStatusLine(state)
        HorizontalPager(pager, Modifier.fillMaxSize(), key = { it }) { page ->
            val pageMonth = firstMonth.plusMonths(page.toLong())
            Column(Modifier.fillMaxSize()) {
                // the month's picture above the grid, shorter than the Schedule's so the six rows keep their room
                MonthBanner(pageMonth.month, "", height = 56.dp, modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp), showTitle = false)
                WeekdayHeader(state)
                CappedFontScale(1.15f) { MonthGrid(pageMonth, state, colors, onOpenDay) }
            }
        }
    }
}

@Composable
private fun WeekdayHeader(state: UiState) {
        CappedFontScale(1.15f) { Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp)) {
            for (i in 0 until 7) {
                Text(
                    state.prefs.weekStart.plus(i.toLong()).getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                    Modifier.weight(1f), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        } }
}

@Composable
private fun MonthGrid(month: YearMonth, state: UiState, colors: Map<Long, CalendarInfo>, onOpenDay: (LocalDate) -> Unit) {
    val today = remember { LocalDate.now() }
    val gridStart = month.atDay(1).with(java.time.temporal.TemporalAdjusters.previousOrSame(state.prefs.weekStart))
    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 2.dp)) {
        val tileHeight: Dp = maxHeight / 6
        // how many chip lines fit under the date number
        val lines = (((tileHeight - 4.dp - NUMBER_ROW) / (CHIP_HEIGHT + CHIP_GAP))).toInt().coerceAtLeast(0)
        Column(Modifier.fillMaxSize()) {
            for (row in 0 until 6) {
                Row(Modifier.fillMaxWidth().weight(1f)) {
                    for (col in 0 until 7) {
                        val date = gridStart.plusDays((row * 7 + col).toLong())
                        DayTile(date, date.month == month.month, date == today, state, colors, lines, Modifier.weight(1f), onOpenDay)
                    }
                }
            }
        }
    }
}

@Composable
private fun DayTile(
    date: LocalDate, inMonth: Boolean, isToday: Boolean, state: UiState, colors: Map<Long, CalendarInfo>, lines: Int, modifier: Modifier,
    onOpenDay: (LocalDate) -> Unit,
) {
    val t = LocalThemeColors.current
    val events = state.dayAt(date)?.events.orEmpty()
    val tasks = state.dayAt(date)?.tasks.orEmpty()
    // what a screen reader says for the tile: the date and how many events and tasks, not the chips one by one (they are on the day page)
    val spoken = java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.FULL).format(date).let { d ->
        val ev = if (events.isEmpty() && tasks.isNotEmpty()) d
            else if (events.isEmpty()) stringResource(R.string.month_tile_empty, d) else androidx.compose.ui.res.pluralStringResource(R.plurals.month_tile_events, events.size, d, events.size)
        if (tasks.isEmpty()) ev else ev + ", " + androidx.compose.ui.res.pluralStringResource(R.plurals.month_tile_tasks, tasks.size, tasks.size)
    }
    Column(
        modifier.fillMaxSize().padding(1.5.dp).clip(RoundedCornerShape(12.dp))
            .background(t.raised)
            .border(BorderStroke(1.dp, t.border), RoundedCornerShape(12.dp))
            .clickable { onOpenDay(date) }.clearAndSetSemantics { contentDescription = spoken }.padding(horizontal = 2.dp, vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(CHIP_GAP),
    ) {
        androidx.compose.foundation.layout.Box(
            Modifier.height(NUMBER_ROW - 2.dp).let { if (isToday) it.size(22.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary) else it },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                date.dayOfMonth.toString(), fontSize = 12.sp,
                color = when { isToday -> MaterialTheme.colorScheme.onPrimary; inMonth -> MaterialTheme.colorScheme.onBackground; else -> t.textMuted.copy(alpha = 0.6f) },
            )
        }
        // with too many events and tasks the last line says "+N" instead of showing one more chip; events come first, then tasks
        val total = events.size + tasks.size
        val overflow = total > lines
        val shownChips = if (overflow) (lines - 1).coerceAtLeast(0) else total
        for (e in events.take(shownChips)) {
            val chip = eventChipColor(e, colors[e.calendarId])
            // read-only events (feeds) get a hairline outline and a small lock (docs/design-spec.md 3.3)
            val outline = if (e.readOnly) Modifier.border(1.dp, chip.text.copy(alpha = 0.55f), RoundedCornerShape(4.dp)) else Modifier
            Row(
                Modifier.fillMaxWidth().height(CHIP_HEIGHT).clip(RoundedCornerShape(4.dp)).background(chip.fill).then(outline).padding(horizontal = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(e.title.ifBlank { stringResource(R.string.no_title) }, Modifier.weight(1f), color = chip.text, fontSize = 11.sp, lineHeight = 15.sp, maxLines = 1, overflow = TextOverflow.Clip)
                if (e.readOnly) Icon(Icons.Default.Lock, stringResource(R.string.lock_description), Modifier.size(9.dp), tint = chip.text)
            }
        }
        // a task chip: the list's pill colours with the task's tick symbol in front of the title (the same as on the Schedule)
        for (t in tasks.take((shownChips - events.size).coerceAtLeast(0))) {
            val chip = taskChipColor(colors[t.calendarId])
            Row(
                Modifier.fillMaxWidth().height(CHIP_HEIGHT).clip(RoundedCornerShape(4.dp)).background(chip.fill).padding(horizontal = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TaskGlyph(chip.text, 8.dp, described = false)
                androidx.compose.foundation.layout.Spacer(Modifier.size(2.dp))
                Text(t.title.ifBlank { stringResource(R.string.no_title) }, Modifier.weight(1f), color = chip.text, fontSize = 11.sp, lineHeight = 15.sp, maxLines = 1, overflow = TextOverflow.Clip)
            }
        }
        if (overflow && lines > 0) {
            Text(stringResource(R.string.more_events, total - shownChips), fontSize = 11.sp, lineHeight = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
