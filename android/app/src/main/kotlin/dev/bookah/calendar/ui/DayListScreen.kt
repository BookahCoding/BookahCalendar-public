package dev.bookah.calendar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.bookah.calendar.R
import dev.bookah.calendar.UiState
import dev.bookah.calendar.data.Instance
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * The page a Month tap opens: one day's events as cards. A stand-in for the real time-grid Day view of Phase 2.
 * Back (the arrow or the system gesture) returns to Month.
 */
@Composable
fun DayListScreen(date: LocalDate, state: UiState, onBack: () -> Unit, onOpenEvent: (Instance) -> Unit) {
    val zone = remember { ZoneId.systemDefault() }
    val colors = remember(state.calendars) { state.calendars.associateBy { it.id } }
    val events = state.dayAt(date)?.events.orEmpty()
    Column(Modifier.fillMaxSize().safeDrawingPadding().background(MaterialTheme.colorScheme.background)) {
        AppTopBar(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(Locale.getDefault()).format(date), back = true, onNav = onBack)
        if (events.isEmpty()) {
            Text(stringResource(R.string.no_events_day), Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(events, key = { it.key }) { e -> EventCard(e, colors[e.calendarId], date, zone) { onOpenEvent(e) } }
        }
    }
}
