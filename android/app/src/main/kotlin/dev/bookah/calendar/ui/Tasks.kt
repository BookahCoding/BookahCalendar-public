package dev.bookah.calendar.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.IntOffset
import dev.bookah.calendar.ownsDrag
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.stateDescription
import dev.bookah.calendar.TaskBar
import dev.bookah.calendar.TaskNote
import dev.bookah.calendar.data.TaskEditability
import dev.bookah.calendar.data.TaskReadOnlyReason
import kotlinx.coroutines.delay
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.bookah.calendar.R
import dev.bookah.calendar.UiState
import dev.bookah.calendar.data.CalendarInfo
import dev.bookah.calendar.data.TaskItem
import dev.bookah.calendar.design.ChipColor
import dev.bookah.calendar.isOverdue
import dev.bookah.calendar.taskTimeText
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The colour of a task's list (tasks have no colour of their own here). */
fun taskListColor(calendar: CalendarInfo?): Color = ChipColor.parse(calendar?.colorHex ?: "#00679E")

/**
 * A task's pill colours: the list's colour through the same [ChipColor.forCalendar] as events (so the writing on it is black or white with the
 * contrast ThemeContrastTest / the chip colour tests check), used by every view (Schedule, day page, Tasks page, Month chips, all-day strip).
 */
fun taskChipColor(calendar: CalendarInfo?): dev.bookah.calendar.design.ChipColors = ChipColor.forCalendar(taskListColor(calendar))

/**
 * The task's tick symbol, an empty checkbox (a rounded square, unlike anything on an event), drawn in the pill's writing colour. Where it is only
 * a mark (Month chips, the all-day strip, the task page title) a tap there opens the task page; it never ticks.
 */
@Composable
fun TaskGlyph(color: Color, size: Dp, modifier: Modifier = Modifier, described: Boolean = true) {
    val spoken = stringResource(R.string.task_glyph)
    val shape = RoundedCornerShape(size / 4)
    Box(
        modifier.size(size).clip(shape).border(maxOf(1.dp, size / 8), color, shape)
            .then(if (described) Modifier.semantics { contentDescription = spoken } else Modifier),
    )
}

/**
 * The tick symbol as a checkbox with a 48 dp touch target. [tickable]: a tap marks the task done (spoken "Mark done: <title>"). Otherwise a tap
 * calls [onTap] too, and the view model opens the task page, which says why it cannot be ticked here. [saving]: a small spinner, no taps.
 */
@Composable
fun TaskCheckbox(task: TaskItem, color: Color, tickable: Boolean, saving: Boolean, onTap: () -> Unit, size: Dp = 18.dp) {
    val title = task.title.ifBlank { stringResource(R.string.no_title) }
    val spoken = if (tickable) stringResource(R.string.task_mark_done_description, title) else stringResource(R.string.task_open_description, title)
    val savingText = stringResource(R.string.task_saving)
    Box(
        Modifier.size(48.dp).clip(CircleShape)
            .then(
                if (tickable) Modifier.toggleable(value = saving, enabled = !saving, role = Role.Checkbox, onValueChange = { onTap() })
                else Modifier.clickable(role = Role.Button, onClick = onTap),
            )
            .semantics { contentDescription = spoken; if (saving) stateDescription = savingText },
        contentAlignment = Alignment.Center,
    ) {
        if (saving) CircularProgressIndicator(Modifier.size(size), color = color, strokeWidth = 2.dp)
        else TaskGlyph(color, size, described = false)
    }
}

/**
 * A task as a pill like an [EventCard]: the list's colour ([taskChipColor]), the same rounded shape, the title, and under it the time if it has
 * one. The tick symbol before the title tells it from an event at a glance. A late task (deadline passed, with or without a time) gets the word
 * "Late" in a small outlined label inside the pill, so lateness is never told by colour alone. With [onTick] the symbol is a [TaskCheckbox]
 * (48 dp); the rest of the pill opens the task page. [trailing] draws a button at the end of the pill in the pill's writing colour.
 */
@Composable
fun TaskRow(
    task: TaskItem, calendar: CalendarInfo?, zone: ZoneId, nowMs: Long, today: LocalDate, onClick: () -> Unit, showTime: Boolean = true,
    onTick: (() -> Unit)? = null, tickable: Boolean = false, saving: Boolean = false, trailing: (@Composable (Color) -> Unit)? = null,
) {
    val chip = taskChipColor(calendar)
    val late = isOverdue(task, nowMs, today)
    val lateSpoken = stringResource(R.string.task_late_description)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(16.dp)).background(chip.fill).clickable(onClick = onClick)
            .padding(start = if (onTick != null) 2.dp else 14.dp, end = if (trailing != null) 2.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onTick != null) {
            TaskCheckbox(task, chip.text, tickable, saving, onTick)
        } else {
            TaskGlyph(chip.text, 18.dp)
            Spacer(Modifier.size(12.dp))
        }
        Column(Modifier.weight(1f).padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                task.title.ifBlank { stringResource(R.string.no_title) }, color = chip.text, fontSize = 16.sp, fontWeight = FontWeight.Medium,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            val time = if (showTime) taskTimeText(task, zone) else null
            if (late || time != null) Row(verticalAlignment = Alignment.CenterVertically) {
                if (late) {
                    Text(
                        stringResource(R.string.task_late),
                        Modifier.border(1.dp, chip.text, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 1.dp)
                            .semantics { contentDescription = lateSpoken },
                        fontSize = 12.sp, color = chip.text, fontWeight = FontWeight.Medium, maxLines = 1,
                    )
                }
                if (time != null) Text(time, Modifier.padding(start = if (late) 8.dp else 0.dp), fontSize = 14.sp, color = chip.text)
            }
        }
        trailing?.invoke(chip.text)
    }
}

/** A [TaskRow] whose mark is a checkbox, wired to the state (Schedule, day page, Tasks page). */
@Composable
fun TickableTaskRow(
    task: TaskItem, calendar: CalendarInfo?, zone: ZoneId, nowMs: Long, today: LocalDate, state: UiState, onOpen: () -> Unit, onTick: () -> Unit,
    trailing: (@Composable (Color) -> Unit)? = null,
) = TaskRow(
    task, calendar, zone, nowMs, today, onOpen, onTick = onTick,
    tickable = state.taskEditability[task.key] == TaskEditability.Editable, saving = state.taskSaving == task.key, trailing = trailing,
)

/**
 * The Tasks page from the drawer: open tasks with no date, grouped by their list. Back returns to the view before. Each row has a "Pick a day"
 * button (a date picker, then [onMove]); a task that may be moved can also be held and dragged onto the day strip that appears at the bottom
 * (today, tomorrow, the next five days). Letting go anywhere else writes nothing.
 */
@Composable
fun TasksScreen(
    state: UiState, onBack: () -> Unit, onOpenTask: (TaskItem) -> Unit, onTick: (TaskItem) -> Unit = {},
    onMove: (TaskItem, LocalDate) -> Unit = { _, _ -> },
) {
    val zone = remember { ZoneId.systemDefault() }
    val today = rememberToday(zone)
    val now = remember(state.undatedTasks, today) { System.currentTimeMillis() }
    val cals = remember(state.calendars) { state.calendars.associateBy { it.id } }
    val groups = remember(state.undatedTasks) { state.undatedTasks.groupBy { it.calendarId } }
    var drag by remember { mutableStateOf<dev.bookah.calendar.DragSession?>(null) }
    val boxes = remember { androidx.compose.runtime.mutableStateMapOf<LocalDate, dev.bookah.calendar.DropBox>() }
    var picking by remember { mutableStateOf<TaskItem?>(null) }
    var screenOrigin by remember { mutableStateOf(Offset.Zero) }
    var screenWidth by remember { mutableStateOf(0) }
    var screenHeight by remember { mutableStateOf(0) }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val currentOnMove by androidx.compose.runtime.rememberUpdatedState(onMove)
    // no Pick a day and no drag while any task write runs: a day picked then would have nowhere to go
    val writing = state.taskSaving != null
    Box(Modifier.fillMaxSize().onGloballyPositioned { screenOrigin = it.positionInRoot(); screenWidth = it.size.width; screenHeight = it.size.height }) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().background(MaterialTheme.colorScheme.background)) {
            AppTopBar(stringResource(R.string.tasks_title), back = true, onNav = onBack)
            // the strip lies over the list while dragging. No padding at the top for it: that would push the list down under the finger.
            // Room at the bottom only lets the last rows scroll clear of a bottom strip; the list does not move.
            val bottomStrip = drag?.stripOnTop == false
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = if (bottomStrip) 200.dp else 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Text(stringResource(R.string.tasks_hint), Modifier.padding(horizontal = 12.dp, vertical = 4.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (groups.isEmpty()) item {
                    Text(stringResource(R.string.tasks_empty), Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                for ((calId, list) in groups) {
                    val cal = cals[calId]
                    item(key = "list|$calId") {
                        Row(Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(10.dp).clip(CircleShape).background(taskListColor(cal)))
                            Spacer(Modifier.size(8.dp))
                            Text(cal?.name.orEmpty(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    items(list, key = { it.key }) { t ->
                        val movable = state.taskMovability[t.key] == TaskEditability.Editable
                        val task by androidx.compose.runtime.rememberUpdatedState(t)
                        var rowOrigin by remember { mutableStateOf(Offset.Zero) }
                        val canDrag = movable && !writing
                        Box(
                            Modifier.fillMaxWidth()
                                .then(if (ownsDrag(drag, t.key)) Modifier.border(2.dp, MaterialTheme.colorScheme.onBackground, RoundedCornerShape(16.dp)) else Modifier)
                                .onGloballyPositioned { rowOrigin = it.positionInRoot() }
                                .then(
                                    if (!canDrag) Modifier
                                    else Modifier.pointerInput(t.key) {
                                        // only the row that started the drag moves or ends it: a second finger on another row changes nothing
                                        detectDragGesturesAfterLongPress(
                                            onDragStart = { at ->
                                                val p = rowOrigin + at
                                                val started = dev.bookah.calendar.beginDrag(drag, task, p.x, p.y, (screenOrigin.y + screenHeight).toFloat())
                                                if (started != null) {
                                                    boxes.clear()
                                                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                                    drag = started
                                                }
                                            },
                                            onDrag = { change, amount ->
                                                if (ownsDrag(drag, task.key)) { change.consume(); drag = drag?.moveBy(task.key, amount.x, amount.y) }
                                            },
                                            onDragEnd = {
                                                if (ownsDrag(drag, task.key)) {
                                                    val d = drag
                                                    val day = dev.bookah.calendar.dropDay(d, task.key, boxes.values.toList(), viewConfiguration.touchSlop)
                                                    drag = null
                                                    if (d != null && day != null) currentOnMove(d.task, day)
                                                }
                                            },
                                            onDragCancel = { if (ownsDrag(drag, task.key)) drag = null },
                                        )
                                    },
                                ),
                        ) {
                            // a task that cannot be given a day here has no Pick a day button (its page says why)
                            TickableTaskRow(
                                t, cal, zone, now, today, state, { onOpenTask(t) }, { onTick(t) },
                                trailing = if (!movable) null else { ink -> PickDayButton(t, ink, enabled = !writing) { picking = t } },
                            )
                        }
                    }
                }
            }
        }
        drag?.let { d ->
            val hovered = dev.bookah.calendar.dayAt(boxes.values.toList(), d.x, d.y)
            DayStrip(
                today, hovered, onBox = { boxes[it.date] = it },
                // at the top when the drag started in the lower half, so the strip never appears under the finger
                modifier = Modifier.align(if (d.stripOnTop) Alignment.TopCenter else Alignment.BottomCenter).safeDrawingPadding()
                    .padding(start = 12.dp, end = 12.dp, top = if (d.stripOnTop) 64.dp else 12.dp, bottom = 12.dp),
            )
            // the task follows the finger, a little above it so the finger does not hide it, and never leaves the screen at the sides
            Box(
                Modifier.offset {
                    val w = 240.dp.roundToPx()
                    val x = ((d.x - screenOrigin.x).toInt() - 60.dp.roundToPx()).coerceIn(0, maxOf(0, screenWidth - w))
                    IntOffset(x, (d.y - screenOrigin.y).toInt() - 72.dp.roundToPx())
                }
                    .widthIn(max = 240.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.onBackground).padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Text(d.task.title.ifBlank { stringResource(R.string.no_title) }, color = MaterialTheme.colorScheme.background, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 15.sp)
            }
        }
    }
    picking?.let { t ->
        PickDayDialog(today, onPick = { day -> picking = null; onMove(t, day) }, onDismiss = { picking = null })
    }
}

/** "Pick a day" at the end of an undated pill: a 48 dp button in the pill's writing colour ([ink]), spoken "Pick a day: <title>". */
@Composable
private fun PickDayButton(task: TaskItem, ink: Color, enabled: Boolean, onClick: () -> Unit) {
    val spoken = stringResource(R.string.task_pick_day_description, task.title.ifBlank { stringResource(R.string.no_title) })
    IconButton(onClick, enabled = enabled, modifier = Modifier.semantics { contentDescription = spoken }) {
        Icon(Icons.Default.DateRange, contentDescription = null, tint = if (enabled) ink else ink.copy(alpha = 0.4f))
    }
}

/**
 * The drop targets while a task is dragged: Today and Tomorrow wide on top, the next five days below. Each reports where it is on screen
 * ([onBox]); the one under the finger ([hovered]) is filled.
 */
@Composable
private fun DayStrip(today: LocalDate, hovered: LocalDate?, onBox: (dev.bookah.calendar.DropBox) -> Unit, modifier: Modifier = Modifier) {
    val locale = rememberTexts().locale
    val days = dev.bookah.calendar.dropDays(today)
    val weekday = DateTimeFormatter.ofPattern("EEE", locale)
    val dayMonth = DateTimeFormatter.ofPattern("d MMM", locale)
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp)).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(stringResource(R.string.task_drop_hint), Modifier.padding(horizontal = 4.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        @Composable fun Target(date: LocalDate, top: String, bottom: String, mod: Modifier) {
            val on = date == hovered
            Column(
                mod.heightIn(min = 56.dp).clip(RoundedCornerShape(12.dp))
                    .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.background)
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                    .onGloballyPositioned { c -> val b = c.boundsInRoot(); onBox(dev.bookah.calendar.DropBox(date, b.left, b.top, b.right, b.bottom)) }
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) {
                val ink = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onBackground
                Text(top, fontSize = 13.sp, color = ink, fontWeight = FontWeight.Medium, maxLines = 1)
                Text(bottom, fontSize = 12.sp, color = ink, maxLines = 1)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Target(days[0], stringResource(R.string.today), dayMonth.format(days[0]), Modifier.weight(1f))
            Target(days[1], stringResource(R.string.task_tomorrow), dayMonth.format(days[1]), Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (d in days.drop(2)) Target(d, weekday.format(d), d.dayOfMonth.toString(), Modifier.weight(1f))
        }
    }
}

/** The app's date picker for giving a task a day: from today to the last day the app loads (so the task lands on a day it shows). */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun PickDayDialog(today: LocalDate, onPick: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val end = dev.bookah.calendar.loadedWindow(today).end
    val st = androidx.compose.material3.rememberDatePickerState(
        initialSelectedDateMillis = today.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli(),
        yearRange = today.year..end.year,
        selectableDates = object : androidx.compose.material3.SelectableDates {
            // the picker's millis are UTC midnight of the date (as in the event form): read them in UTC, never in the phone's zone
            override fun isSelectableDate(utcTimeMillis: Long) = dev.bookah.calendar.pickableDay(LocalDate.ofEpochDay(Math.floorDiv(utcTimeMillis, 86_400_000L)), today)
            override fun isSelectableYear(year: Int) = year in today.year..end.year
        },
    )
    androidx.compose.material3.DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton({
                st.selectedDateMillis?.let { ms -> onPick(Instant.ofEpochMilli(ms).atZone(java.time.ZoneOffset.UTC).toLocalDate()) } ?: onDismiss()
            }) { Text(stringResource(R.string.event_ok)) }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.event_cancel)) } },
    ) { androidx.compose.material3.DatePicker(st, title = { Text(stringResource(R.string.task_pick_day), Modifier.padding(start = 24.dp, top = 16.dp)) }) }
}

/**
 * The task page: title, notes, dates, list, priority, link, parent task. A plain task gets a "Mark done" button ([editability] Editable); a task
 * that cannot be ticked here says why. Every other change is made in BookahTasks: "Open in BookahTasks" starts that app (if it is installed).
 */
@Composable
fun TaskPage(
    task: TaskItem, calendar: CalendarInfo?, parentTitle: String?, onDismiss: () -> Unit,
    editability: TaskEditability? = null, saving: Boolean = false, onMarkDone: () -> Unit = {},
    /** For a task without a date: may it be given a day here ("Pick a day")? Null for a dated task. */
    movability: TaskEditability? = null, onPickDay: (LocalDate) -> Unit = {},
    /** Some task write (of any task) is running: Pick a day waits. */
    writing: Boolean = saving,
) {
    val zone = ZoneId.systemDefault()
    val locale = rememberTexts().locale
    val context = LocalContext.current
    var missing by remember(task.key) { mutableStateOf(false) }
    var picking by remember(task.key) { mutableStateOf(false) }
    val today = LocalDate.now(zone)
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHigh).safeDrawingPadding()
            // the page covers the screen: taps must not fall through to the list behind it
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {},
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onDismiss) { Icon(Icons.Default.Close, stringResource(R.string.close)) }
        }
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TaskGlyph(taskListColor(calendar), 22.dp, Modifier.padding(top = 8.dp))
                Text(task.title.ifBlank { stringResource(R.string.no_title) }, fontSize = 28.sp, lineHeight = 34.sp, color = MaterialTheme.colorScheme.onSurface)
            }
            val dates = listOfNotNull(
                task.due?.let { stringResource(R.string.task_due, taskDateText(it, task.dueAllDay, zone, locale)) },
                task.start?.let { stringResource(R.string.task_start, taskDateText(it, task.startAllDay, zone, locale)) },
            )
            Text(if (dates.isEmpty()) stringResource(R.string.task_no_date) else dates.joinToString("\n"), fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
            if (isOverdue(task, System.currentTimeMillis(), today)) Text(stringResource(R.string.task_overdue), fontSize = 14.sp, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Medium)
            when (task.priority) {
                in 1..4 -> R.string.task_priority_high
                5 -> R.string.task_priority_medium
                in 6..9 -> R.string.task_priority_low
                else -> null
            }?.let { Text(stringResource(it), fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface) }
            if (parentTitle != null) Text(stringResource(R.string.task_subtask_of, parentTitle.ifBlank { stringResource(R.string.no_title) }), fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
            val readOnlyList = (editability as? TaskEditability.ReadOnly)?.reason == TaskReadOnlyReason.ReadOnlyList
            if (calendar != null) Text(calendar.name + if (readOnlyList) "  ·  " + stringResource(R.string.read_only) else "", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
            task.url?.let { url -> TaskLink(url) }
            task.notes?.let { Text(it, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface) }
            val movable = movability == TaskEditability.Editable
            when (editability) {
                TaskEditability.Editable -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(onMarkDone, Modifier.heightIn(min = 48.dp), enabled = !saving) {
                            if (saving) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary); Spacer(Modifier.size(8.dp)) }
                            Text(stringResource(if (saving) R.string.task_saving else R.string.task_mark_done))
                        }
                        if (movable) PickDayPageButton(enabled = !writing) { picking = true }
                    }
                    Text(stringResource(if (movable) R.string.task_move_note else R.string.task_read_only_note), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                is TaskEditability.ReadOnly -> Text(stringResource(taskHint(editability.reason)), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                // no button here (a finished task, or one not judged yet): do not promise a tick
                null -> Text(stringResource(R.string.task_page_note_other), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // a task without a date that cannot be ticked here may still be given a day (a parent with open subtasks, for example)
            if (movable && editability != TaskEditability.Editable) PickDayPageButton(enabled = !writing) { picking = true }
            moveHint(movability, editability)?.let { Text(stringResource(it), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            OutlinedButton({ missing = !openTaskInBookahTasks(context, task.uid, calendar?.href) }, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.task_open_in_tasks)) }
            if (missing) Text(stringResource(R.string.task_tasks_missing), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (picking) PickDayDialog(today, onPick = { day -> picking = false; onPickDay(day) }, onDismiss = { picking = false })
}

@Composable
private fun PickDayPageButton(enabled: Boolean, onClick: () -> Unit) =
    OutlinedButton(onClick, Modifier.heightIn(min = 48.dp), enabled = enabled) {
        Icon(Icons.Default.DateRange, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(8.dp))
        Text(stringResource(R.string.task_pick_day))
    }

/**
 * Why a task without a date cannot be given a day here, when the tick's hint does not already say it: parked tasks always (they are dated in
 * BookahTasks), others only when the task can be ticked (otherwise the page already sends the owner to BookahTasks).
 */
private fun moveHint(movability: TaskEditability?, editability: TaskEditability?): Int? {
    val reason = (movability as? TaskEditability.ReadOnly)?.reason ?: return null
    return when {
        reason == TaskReadOnlyReason.Parked -> R.string.task_hint_parked
        reason == TaskReadOnlyReason.ReadOnlyList -> null
        editability == TaskEditability.Editable -> R.string.task_move_hint_other
        else -> null
    }
}

/** Why a task cannot be ticked here, and what to do instead. */
private fun taskHint(reason: TaskReadOnlyReason): Int = when (reason) {
    TaskReadOnlyReason.Repeating -> R.string.task_hint_repeating
    TaskReadOnlyReason.OpenSubtasks -> R.string.task_hint_subtasks
    TaskReadOnlyReason.ReadOnlyList -> R.string.task_hint_read_only_list
    TaskReadOnlyReason.WithPeople -> R.string.task_hint_people
    TaskReadOnlyReason.Parked -> R.string.task_hint_parked
    TaskReadOnlyReason.Unusual, TaskReadOnlyReason.NotATask, TaskReadOnlyReason.Finished, TaskReadOnlyReason.HasDates -> R.string.task_hint_other
}

/**
 * The bar at the bottom after a tick: "Task done" with Undo, or what went wrong (with OK). While an undo is being saved it says so.
 * It is a polite live region (TalkBack reads it out), and it stays as long as the phone's accessibility settings ask for a bar with text and
 * a button (6 s for Undo, 8 s for a message when nothing longer is asked).
 */
@Composable
fun TaskBarHost(bar: TaskBar?, undoSaving: Boolean, onUndo: () -> Unit, onDismiss: (Long) -> Unit, modifier: Modifier = Modifier) {
    // the theme's own text colour as the bar and its page colour as the writing (their contrast is checked for every theme by
    // ThemeContrastTest); the theme sets no inverse colours, and the default accent on the default inverse bar was hard to read in dark themes
    val barColor = MaterialTheme.colorScheme.onBackground
    val onBar = MaterialTheme.colorScheme.background
    @Composable fun BarButton(label: Int, onClick: () -> Unit) =
        TextButton(onClick, Modifier.heightIn(min = 48.dp), colors = ButtonDefaults.textButtonColors(contentColor = onBar)) {
            Text(stringResource(label), fontWeight = FontWeight.Bold)
        }
    val spoken = modifier.semantics { liveRegion = LiveRegionMode.Polite }
    if (undoSaving) {
        Snackbar(spoken, containerColor = barColor, contentColor = onBar) { Text(stringResource(R.string.task_saving)) }
        return
    }
    if (bar == null) return
    val a11y = LocalAccessibilityManager.current
    LaunchedEffect(bar.id) {
        val base = if (bar is TaskBar.Done) 6_000L else 8_000L
        delay(a11y?.calculateRecommendedTimeoutMillis(base, containsIcons = false, containsText = true, containsControls = true) ?: base)
        onDismiss(bar.id)
    }
    when (bar) {
        is TaskBar.Done -> Snackbar(spoken, action = { BarButton(R.string.task_undo, onUndo) }, containerColor = barColor, contentColor = onBar) {
            Text(stringResource(R.string.task_done_bar))
        }
        is TaskBar.Moved -> Snackbar(spoken, action = { BarButton(R.string.task_undo, onUndo) }, containerColor = barColor, contentColor = onBar) {
            val locale = rememberTexts().locale
            Text(stringResource(R.string.task_moved_bar, DateTimeFormatter.ofPattern("EEE d MMM", locale).format(bar.date)))
        }
        is TaskBar.Message -> Snackbar(spoken, action = { BarButton(R.string.event_ok) { onDismiss(bar.id) } }, containerColor = barColor, contentColor = onBar) {
            Text(
                stringResource(
                    when (bar.note) {
                        TaskNote.ChangedElsewhere -> R.string.task_changed_elsewhere
                        TaskNote.Gone -> R.string.task_gone
                        TaskNote.Offline -> R.string.task_write_offline
                        TaskNote.Failed -> R.string.task_write_failed
                        TaskNote.Refused -> R.string.task_write_refused
                        TaskNote.MaybeSaved -> R.string.task_maybe_saved
                        TaskNote.AlreadyDone -> R.string.task_already_done
                        TaskNote.AlreadyOpen -> R.string.task_already_open
                        TaskNote.AlreadyDated -> R.string.task_already_dated
                        TaskNote.MoveRefused -> R.string.task_move_refused
                        TaskNote.UndoRefused -> R.string.task_undo_refused
                        TaskNote.PickAgain -> R.string.task_pick_again
                        TaskNote.Busy -> R.string.task_busy
                    },
                ),
            )
        }
    }
}

/** The task's link: only a web address is offered as a link (opens in the browser); anything else is shown as text. */
@Composable
private fun TaskLink(url: String) {
    val context = LocalContext.current
    val web = url.startsWith("https://", true) || url.startsWith("http://", true)
    Text(
        url, fontSize = 16.sp, color = if (web) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        textDecoration = if (web) TextDecoration.Underline else null, maxLines = 3, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .then(if (web) Modifier.clickable { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } } else Modifier)
            .padding(vertical = 12.dp),
    )
}

/**
 * Opens this one task in BookahTasks with its link ([dev.bookah.calendar.bookahTasksLink]: the task's UID and its list's href). The link is
 * given to BookahTasks only (setPackage). The list's href holds the Nextcloud user name, so it is put in the link only when the installed
 * BookahTasks is signed with BookahTasks' own release certificate ([bookahTasksIsGenuine]); otherwise the link carries the UID alone. No link
 * (a UID over 1024 characters, a link over 4096) or a BookahTasks without it (an older one): BookahTasks is simply started; false when it is
 * not installed at all. The link is never logged.
 */
fun openTaskInBookahTasks(context: Context, uid: String, listHref: String?): Boolean {
    val list = listHref?.takeIf { bookahTasksIsGenuine(context) }
    val link = dev.bookah.calendar.bookahTasksLink(uid, list)
    if (link != null) {
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(link)).setPackage(dev.bookah.calendar.TASKS_PACKAGE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // ActivityNotFoundException when BookahTasks is missing or has no such link yet: then the plain start below
        if (runCatching { context.startActivity(view) }.isSuccess) return true
    }
    return openBookahTasks(context)
}

/** Is the installed BookahTasks signed with BookahTasks' release certificate? False when it is missing, signed otherwise (a debug build), or unknown. */
fun bookahTasksIsGenuine(context: Context): Boolean = runCatching {
    context.packageManager.hasSigningCertificate(
        dev.bookah.calendar.TASKS_PACKAGE, dev.bookah.calendar.fingerprintBytes(dev.bookah.calendar.BOOKAH_TASKS_CERT_SHA256),
        android.content.pm.PackageManager.CERT_INPUT_SHA256,
    )
}.getOrDefault(false)

/** Starts BookahTasks. False when it is not installed (or cannot be started): the page then says so instead of crashing. */
fun openBookahTasks(context: Context): Boolean = runCatching {
    val launch = context.packageManager.getLaunchIntentForPackage(dev.bookah.calendar.TASKS_PACKAGE) ?: return false
    context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
}.getOrDefault(false)

private val TASK_TIME = DateTimeFormatter.ofPattern("HH:mm")

/** "Tue 18 Nov" for a date, "Tue 18 Nov, 14:00" for a time. */
internal fun taskDateText(ms: Long, allDay: Boolean, zone: ZoneId, locale: java.util.Locale): String {
    val dateFmt = DateTimeFormatter.ofPattern("EEE d MMM", locale)
    if (allDay) return dateFmt.format(LocalDate.ofEpochDay(Math.floorDiv(ms, 86_400_000L)))
    val z = Instant.ofEpochMilli(ms).atZone(zone)
    return dateFmt.format(z) + ", " + TASK_TIME.format(z)
}
