package dev.bookah.calendar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.bookah.calendar.DayItems
import dev.bookah.calendar.R
import dev.bookah.calendar.UiState
import dev.bookah.calendar.data.CalendarInfo
import dev.bookah.calendar.data.Instance
import dev.bookah.calendar.data.TaskItem
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/** Schedule: a vertical list of days; each day has a date column and full-width event cards (docs/design-spec.md 2.4). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleScreen(state: UiState, onMenu: () -> Unit, onRefresh: () -> Unit, onOpenEvent: (Instance) -> Unit, onOpenTask: (TaskItem) -> Unit, onTick: (TaskItem) -> Unit = {}) {
    val zone = remember { ZoneId.systemDefault() }
    // follows the clock: back in the app the next morning, today's pill and "late" move on
    val today = rememberToday(zone)
    // "late" for a task is judged against this moment (recomputed whenever the days are loaded again or the date changes)
    val now = remember(state.days, today) { System.currentTimeMillis() }
    val colors = remember(state.calendars) { state.calendars.associateBy { it.id } }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val todayIndex = state.days.indexOfFirst { it.date == today }.coerceAtLeast(0)
    // open on today once the first days arrive
    LaunchedEffect(state.days.isNotEmpty()) { if (state.days.isNotEmpty()) list.scrollToItem(todayIndex) }

    Column(Modifier.fillMaxSize().safeDrawingPadding().background(MaterialTheme.colorScheme.background)) {
        AppTopBar(
            title = today.month.getDisplayName(TextStyle.FULL_STANDALONE, Locale.getDefault()) + " " + today.year, back = false, onNav = onMenu,
            actions = { TextButton({ scope.launch { list.animateScrollToItem(todayIndex) } }) { Text(stringResource(R.string.today)) } },
        )
        SyncStatusLine(state)
        PullToRefreshBox(isRefreshing = state.syncing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
            if (state.calendars.isEmpty() && state.days.isNotEmpty()) {
                Text(stringResource(R.string.no_calendars), Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LazyColumn(Modifier.fillMaxSize(), list, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(state.days, key = { it.date.toEpochDay() }) { day -> DayRow(day, today, now, colors, zone, onOpenEvent, onOpenTask, state.weather[day.date.toString()], state, onTick) }
            }
        }
    }
}

/** The quiet line under the top bar: syncing, offline, or the server's last complaint. */
@Composable
fun SyncStatusLine(state: UiState) {
    val status = when {
        state.syncing -> stringResource(R.string.status_syncing)
        state.lastReport?.offline == true -> stringResource(R.string.status_offline)
        state.lastReport?.error != null -> stringResource(when (state.lastReport.errorCode) { 401 -> R.string.sync_err_auth; 403, 404 -> R.string.sync_err_gone; else -> R.string.sync_err_other })
        else -> null
    }
    if (status != null) Text(status, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun DayRow(
    day: DayItems, today: LocalDate, now: Long, calendars: Map<Long, CalendarInfo>, zone: ZoneId, onOpenEvent: (Instance) -> Unit, onOpenTask: (TaskItem) -> Unit,
    weather: dev.bookah.calendar.data.DayWeather?, state: UiState, onTick: (TaskItem) -> Unit,
) {
    val isToday = day.date == today
    Column {
        if (day.date.dayOfMonth == 1) {
            MonthBanner(day.date.month, monthTitle(day.date.month, day.date.year), modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 6.dp))
        }
        if (weather != null) WeatherLine(weather, Modifier.padding(start = 72.dp, end = 16.dp, bottom = 4.dp))
        Row(Modifier.fillMaxWidth().padding(end = 16.dp)) {
            Column(Modifier.width(64.dp).padding(start = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    day.date.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                    fontSize = 12.sp, color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Box(
                    Modifier.defaultMinSize(36.dp, 36.dp).clip(CircleShape).background(if (isToday) MaterialTheme.colorScheme.primary else Color.Transparent),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(day.date.dayOfMonth.toString(), fontSize = 18.sp, color = if (isToday) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onBackground)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (day.events.isEmpty() && day.tasks.isEmpty()) {
                    Text(stringResource(R.string.quiet_day), Modifier.padding(top = 10.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                }
                for (e in day.events) EventCard(e, calendars[e.calendarId], day.date, zone) { onOpenEvent(e) }
                // the day's open tasks as quiet lines under its events: the round mark ticks a plain task, the rest of the row opens the task page
                if (day.tasks.isNotEmpty()) Column {
                    for (t in day.tasks) androidx.compose.runtime.key(t.key) { TickableTaskRow(t, calendars[t.calendarId], zone, now, today, state, { onOpenTask(t) }, { onTick(t) }) }
                }
            }
        }
    }
}

/** The small line above a coming day: a weather symbol, the high and low in °C, and the chance of rain when it is notable. */
@Composable
fun WeatherLine(w: dev.bookah.calendar.data.DayWeather, modifier: Modifier = Modifier) {
    val symbol = when (dev.bookah.calendar.data.weatherKind(w.code)) {
        dev.bookah.calendar.data.WeatherKind.Clear -> "☀️"
        dev.bookah.calendar.data.WeatherKind.PartlyCloudy -> "⛅"
        dev.bookah.calendar.data.WeatherKind.Cloudy -> "☁️"
        dev.bookah.calendar.data.WeatherKind.Fog -> "🌫️"
        dev.bookah.calendar.data.WeatherKind.Drizzle -> "🌦️"
        dev.bookah.calendar.data.WeatherKind.Rain -> "🌧️"
        dev.bookah.calendar.data.WeatherKind.Snow -> "❄️"
        dev.bookah.calendar.data.WeatherKind.Thunder -> "⛈️"
        dev.bookah.calendar.data.WeatherKind.Unknown -> "·"
    }
    val rain = w.rainPercent?.takeIf { it >= 30 }?.let { "  💧$it%" }.orEmpty()
    Text(
        "$symbol  ${w.maxC}° / ${w.minC}°$rain", modifier, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
    )
}
