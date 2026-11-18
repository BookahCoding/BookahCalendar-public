package dev.bookah.calendar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.bookah.calendar.R
import dev.bookah.calendar.data.CalendarInfo
import dev.bookah.calendar.data.Instance
import dev.bookah.calendar.design.ChipColor
import dev.bookah.calendar.timeRangeText
import java.time.LocalDate
import java.time.ZoneId

/** The top bar every screen shares: a menu button (or a back arrow on the day page), a title and actions (docs/design-spec.md 2.1). */
@Composable
fun AppTopBar(title: String, back: Boolean, onNav: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onNav) {
            if (back) Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
            else Icon(Icons.Default.Menu, stringResource(R.string.menu))
        }
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        // the search button sits before the other actions on the main screens (not on the day page, which has a back arrow)
        LocalOpenSearch.current?.takeIf { !back }?.let { open -> IconButton(open) { Icon(Icons.Default.Search, stringResource(R.string.search)) } }
        actions()
    }
}

/**
 * Keeps text in fixed-height grids (month tiles, time grid) from growing past what the cells can hold. The page for a single day and every
 * list still follow the phone's full text size.
 */
@Composable
fun CappedFontScale(max: Float, content: @Composable () -> Unit) {
    val d = androidx.compose.ui.platform.LocalDensity.current
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(d.density, minOf(d.fontScale, max)), content = content,
    )
}

/**
 * Today's date that follows the clock: read again whenever the app comes back to the front and at the next midnight, so a screen left open
 * overnight (or reopened the next morning) marks the right day and judges "late" against it.
 */
@Composable
fun rememberToday(zone: java.time.ZoneId): LocalDate {
    var today by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(LocalDate.now(zone)) }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) { today = LocalDate.now(zone) }
    androidx.compose.runtime.LaunchedEffect(today) {
        val nextMidnight = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        kotlinx.coroutines.delay((nextMidnight - System.currentTimeMillis()).coerceAtLeast(1_000L))
        today = LocalDate.now(zone)
    }
    return today
}

/** Opens the search page; provided once by the main activity so every main screen's top bar can show the search button. */
val LocalOpenSearch = androidx.compose.runtime.staticCompositionLocalOf<(() -> Unit)?> { null }

/** The chip colours of an event: its own colour if it has one, else its calendar's. */
fun eventChipColor(e: Instance, calendar: CalendarInfo?): dev.bookah.calendar.design.ChipColors = ChipColor.forCalendar(ChipColor.parse(e.colorHex ?: calendar?.colorHex ?: "#00679E"))

/** A full-width event card in the calendar's own colour, with black or white text chosen for contrast. */
@Composable
fun EventCard(e: Instance, calendar: CalendarInfo?, day: LocalDate, zone: ZoneId, onClick: () -> Unit) {
    val chip = eventChipColor(e, calendar)
    // read-only events (feeds) get a hairline outline and a lock, so they are told apart from editable ones (docs/design-spec.md 3.3)
    val outline = if (e.readOnly) Modifier.border(1.dp, chip.text.copy(alpha = 0.55f), RoundedCornerShape(16.dp)) else Modifier
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(chip.fill).then(outline).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(e.title.ifBlank { stringResource(R.string.no_title) }, Modifier.weight(1f), color = chip.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (e.readOnly) Icon(Icons.Default.Lock, stringResource(R.string.lock_description), Modifier.padding(start = 8.dp).size(16.dp), tint = chip.text)
        }
        Text(
            if (e.allDay) stringResource(R.string.all_day) else timeRangeText(e, day, zone),
            color = chip.text, fontSize = 14.sp,
        )
        if (!e.location.isNullOrBlank()) Text(e.location!!, color = chip.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The "+" button: a rounded square in the accent colour, bottom right (docs/design-spec.md 2.1). Opens the form for a new event. */
@Composable
fun NewEventButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    androidx.compose.material3.FloatingActionButton(
        onClick, modifier, shape = RoundedCornerShape(16.dp),
        containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary,
    ) { Icon(androidx.compose.material.icons.Icons.Default.Add, stringResource(R.string.fab_add)) }
}

/** The words of the app in the current language, for code that is not a composable. */
@Composable
fun rememberTexts(): dev.bookah.calendar.Texts {
    val context = androidx.compose.ui.platform.LocalContext.current
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    return androidx.compose.runtime.remember(configuration) { dev.bookah.calendar.ResTexts(context.resources) }
}
