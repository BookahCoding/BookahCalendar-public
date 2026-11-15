package dev.bookah.calendar.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.bookah.calendar.MINUTES_PER_DAY
import dev.bookah.calendar.R
import dev.bookah.calendar.UiState
import dev.bookah.calendar.allDayFor
import dev.bookah.calendar.data.CalendarInfo
import dev.bookah.calendar.data.Instance
import dev.bookah.calendar.design.ChipColor
import dev.bookah.calendar.layoutDay
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale

private const val MIN_HOUR_DP = 32f
private const val MAX_HOUR_DP = 140f
private val LABEL_WIDTH = 52.dp

/**
 * Day, 3 days and Week in one component (docs/design-spec.md 2.3): the date header, an all-day strip, and 24 hour tiles with the timed
 * events on them. Swipe sideways for the next period; pinch with two fingers to change the hour height; tap an empty hour to start a new
 * event at that hour; tap an event to open it. Week starts on the day chosen in Settings; Day and 3 days always show today as the first column
 * of its page.
 */
@Composable
fun TimeGridScreen(
    state: UiState,
    daysPerPage: Int,
    onMenu: () -> Unit,
    onOpenEvent: (Instance) -> Unit,
    onNewAt: (LocalDate, Int) -> Unit,
) {
    val zone = remember { ZoneId.systemDefault() }
    val today = remember { LocalDate.now(zone) }
    val first = state.days.firstOrNull()?.date ?: today
    val last = state.days.lastOrNull()?.date ?: today
    val align: LocalDate =
        if (daysPerPage == 7) first.with(TemporalAdjusters.nextOrSame(state.prefs.weekStart))
        else first.plusDays(Math.floorMod(today.toEpochDay() - first.toEpochDay(), daysPerPage.toLong()))
    val pageCount = maxOf(1, ((last.toEpochDay() - align.toEpochDay() + 1 + daysPerPage - 1) / daysPerPage).toInt())
    val todayPage = ((today.toEpochDay() - align.toEpochDay()) / daysPerPage).toInt().coerceIn(0, pageCount - 1)
    val pager = rememberPagerState(initialPage = todayPage) { pageCount }
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()
    var hourDp by rememberSaveable { mutableFloatStateOf(56f) }
    val colors = remember(state.calendars) { state.calendars.associateBy { it.id } }
    val density = LocalDensity.current

    // open at about two hours before now (or 06:00 for another day), so the working day is in view
    LaunchedEffect(daysPerPage) {
        val hour = (LocalTime.now(zone).hour - 2).coerceAtLeast(0)
        scroll.scrollTo(with(density) { (hour * hourDp).dp.roundToPx() })
    }

    val shownFirst = align.plusDays(pager.currentPage.toLong() * daysPerPage)
    Column(Modifier.fillMaxSize().safeDrawingPadding().background(MaterialTheme.colorScheme.background)) {
        AppTopBar(
            title = shownFirst.month.getDisplayName(TextStyle.FULL_STANDALONE, Locale.getDefault()) + " " + shownFirst.year, back = false, onNav = onMenu,
            actions = { TextButton({ scope.launch { pager.animateScrollToPage(todayPage) } }) { Text(stringResource(R.string.today)) } },
        )
        SyncStatusLine(state)
        Box(
            Modifier.fillMaxSize().pointerInput(Unit) {
                // two fingers change the hour height; one finger is left alone so scrolling and swiping keep working
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.size >= 2) {
                            val zoom = event.calculateZoom()
                            if (zoom != 1f) {
                                hourDp = (hourDp * zoom).coerceIn(MIN_HOUR_DP, MAX_HOUR_DP)
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
        ) {
            HorizontalPager(pager, Modifier.fillMaxSize(), key = { it }) { page ->
                val dates = List(daysPerPage) { align.plusDays(page.toLong() * daysPerPage + it) }
                CappedFontScale(1.15f) { GridPage(dates, state, colors, today, zone, hourDp, scroll, onOpenEvent, onNewAt) }
            }
        }
    }
}

@Composable
private fun GridPage(
    dates: List<LocalDate>, state: UiState, colors: Map<Long, CalendarInfo>, today: LocalDate, zone: ZoneId, hourDp: Float,
    scroll: androidx.compose.foundation.ScrollState, onOpenEvent: (Instance) -> Unit, onNewAt: (LocalDate, Int) -> Unit,
) {
    val t = LocalThemeColors.current
    // a long press on an empty spot starts a one-hour box under the finger; moving the finger moves it (day and time); letting go opens the new event form there
    var ghost by remember { mutableStateOf<Pair<Int, Int>?>(null) }          // column index, start minute
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    Column(Modifier.fillMaxSize()) {
        // header: weekday over date number, today in a filled pill
        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
            Spacer(Modifier.width(LABEL_WIDTH))
            for (d in dates) {
                val isToday = d == today
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()), fontSize = 12.sp, maxLines = 1,
                        color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Box(
                        Modifier.defaultMinSize(32.dp, 32.dp).clip(CircleShape).background(if (isToday) MaterialTheme.colorScheme.primary else Color.Transparent),
                        contentAlignment = Alignment.Center,
                    ) { Text(d.dayOfMonth.toString(), fontSize = 16.sp, color = if (isToday) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onBackground) }
                }
            }
        }
        // all-day strip
        val strips = dates.map { allDayFor(state.dayAt(it)?.events.orEmpty(), it) }
        if (strips.any { it.isNotEmpty() }) {
            Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                Spacer(Modifier.width(LABEL_WIDTH))
                for (list in strips) {
                    Column(Modifier.weight(1f).padding(horizontal = 1.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(2.dp)) {
                        for (e in list.take(3)) {
                            val chip = eventChipColor(e, colors[e.calendarId])
                            Text(
                                e.title.ifBlank { stringResource(R.string.no_title) },
                                Modifier.fillMaxWidth().height(20.dp).clip(RoundedCornerShape(4.dp)).background(chip.fill).clickable { onOpenEvent(e) }.padding(horizontal = 3.dp),
                                color = chip.text, fontSize = 11.sp, lineHeight = 20.sp, maxLines = 1, overflow = TextOverflow.Clip,
                            )
                        }
                        if (list.size > 3) Text(stringResource(R.string.more_events, list.size - 3), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        // the 24 hours
        Box(Modifier.weight(1f).verticalScroll(scroll)) {
            Row(
                Modifier.fillMaxWidth().height((hourDp * 24).dp).pointerInput(dates, hourDp) {
                    val labelPx = LABEL_WIDTH.toPx()
                    val hourPx = hourDp.dp.toPx()
                    fun spot(o: androidx.compose.ui.geometry.Offset): Pair<Int, Int> {
                        val colWidth = ((size.width - labelPx) / dates.size).coerceAtLeast(1f)
                        val col = ((o.x - labelPx) / colWidth).toInt().coerceIn(0, dates.size - 1)
                        // the box is centred on the finger and starts on a quarter of an hour
                        val raw = o.y / hourPx * 60f - DEFAULT_MINUTES / 2f
                        return col to (Math.round(raw / 15f) * 15).coerceIn(0, MINUTES_PER_DAY - DEFAULT_MINUTES)
                    }
                    detectDragGesturesAfterLongPress(
                        onDragStart = { o -> ghost = spot(o); haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress) },
                        onDrag = { change, _ -> change.consume(); ghost = spot(change.position) },
                        onDragEnd = { ghost?.let { (c, m) -> onNewAt(dates[c], m) }; ghost = null },
                        onDragCancel = { ghost = null },
                    )
                },
            ) {
                Column(Modifier.width(LABEL_WIDTH)) {
                    for (h in 0 until 24) {
                        Box(Modifier.height(hourDp.dp), contentAlignment = Alignment.TopEnd) {
                            Text(
                                "%02d:00".format(h), Modifier.padding(end = 6.dp), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End,
                            )
                        }
                    }
                }
                for ((i, d) in dates.withIndex()) DayColumn(d, state, colors, d == today, zone, hourDp, t.raised, t.border, Modifier.weight(1f), onOpenEvent, onNewAt, ghost?.takeIf { it.first == i }?.second)
            }
        }
    }
}

@Composable
private fun DayColumn(
    date: LocalDate, state: UiState, colors: Map<Long, CalendarInfo>, isToday: Boolean, zone: ZoneId, hourDp: Float,
    tile: Color, hairline: Color, modifier: Modifier, onOpenEvent: (Instance) -> Unit, onNewAt: (LocalDate, Int) -> Unit, ghostStart: Int? = null,
) {
    val placed = remember(state.days, date) { layoutDay(state.dayAt(date)?.events.orEmpty(), date, zone) }
    val newLabel = stringResource(R.string.fab_add)
    val noTitle = stringResource(R.string.no_title)
    BoxWithConstraints(modifier.fillMaxSize().padding(horizontal = 1.dp)) {
        val colWidth = maxWidth
        // one rounded tile per hour; a tap on an empty hour starts a new event there
        Column(Modifier.fillMaxSize()) {
            for (h in 0 until 24) {
                Box(
                    Modifier.fillMaxWidth().height(hourDp.dp).padding(1.dp).clip(RoundedCornerShape(8.dp)).background(tile)
                        .border(BorderStroke(1.dp, hairline), RoundedCornerShape(8.dp)).clickable(onClickLabel = newLabel) { onNewAt(date, h * 60) },
                )
            }
        }
        for (p in placed) {
            val color = eventChipColor(p.event, colors[p.event.calendarId])
            val height = (p.endMin - p.startMin) * hourDp / 60f
            val spoken = listOfNotNull(
                p.event.title.ifBlank { noTitle }, if (p.event.allDay) null else dev.bookah.calendar.timeRangeText(p.event, date, zone), p.event.location?.takeIf { it.isNotBlank() },
            ).joinToString(", ")
            Column(
                Modifier.offset(x = colWidth * p.column / p.columns, y = (p.startMin * hourDp / 60f).dp)
                    .width(colWidth / p.columns - 2.dp).height((height - 1f).coerceAtLeast(14f).dp)
                    .clip(RoundedCornerShape(6.dp)).background(color.fill)
                    .then(if (p.event.readOnly) Modifier.border(1.dp, color.text.copy(alpha = 0.55f), RoundedCornerShape(6.dp)) else Modifier)
                    .clickable { onOpenEvent(p.event) }.clearAndSetSemantics { contentDescription = spoken }.padding(horizontal = 3.dp, vertical = 1.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        p.event.title.ifBlank { stringResource(R.string.no_title) }, Modifier.weight(1f), color = color.text, fontSize = 11.sp,
                        lineHeight = 13.sp, fontWeight = FontWeight.Medium, maxLines = if (height >= 46f) 2 else 1, overflow = TextOverflow.Ellipsis,
                    )
                    if (p.event.readOnly) Icon(Icons.Default.Lock, stringResource(R.string.lock_description), Modifier.size(9.dp), tint = color.text)
                }
                // the time line only when the block is tall enough for a second line
                if (height >= 40f) Text(dev.bookah.calendar.timeRangeText(p.event, date, zone), color = color.text, fontSize = 10.sp, lineHeight = 12.sp, maxLines = 1, overflow = TextOverflow.Clip)
            }
        }
        if (isToday) NowLine(hourDp)
        if (ghostStart != null) {
            val primary = MaterialTheme.colorScheme.primary
            Box(
                Modifier.offset(y = (ghostStart * hourDp / 60f).dp).fillMaxWidth().height((DEFAULT_MINUTES * hourDp / 60f).dp)
                    .clip(RoundedCornerShape(8.dp)).background(primary.copy(alpha = 0.35f)).border(2.dp, primary, RoundedCornerShape(8.dp)).padding(4.dp),
            ) {
                Text(
                    "%02d:%02d – %02d:%02d".format(ghostStart / 60, ghostStart % 60, (ghostStart + DEFAULT_MINUTES) / 60 % 24, (ghostStart + DEFAULT_MINUTES) % 60),
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface, maxLines = 1,
                )
            }
        }
    }
}

/** The length of an event made by long press and drag (the same one hour as the form's default). */
private const val DEFAULT_MINUTES = 60

/** A dot and a thin line at the current time, in today's column (updates every half minute). */
@Composable
private fun NowLine(hourDp: Float) {
    val minutes by produceState(LocalTime.now().let { it.hour * 60 + it.minute }) {
        while (true) { delay(30_000); value = LocalTime.now().let { it.hour * 60 + it.minute } }
    }
    val y = (minutes.coerceIn(0, MINUTES_PER_DAY) * hourDp / 60f).dp
    Row(Modifier.fillMaxWidth().offset(y = y - 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
        Box(Modifier.fillMaxWidth().height(2.dp).background(MaterialTheme.colorScheme.primary))
    }
}
