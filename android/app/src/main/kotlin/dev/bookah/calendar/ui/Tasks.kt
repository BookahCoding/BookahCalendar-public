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

/** BookahTasks' package: the only app this one opens for tasks (declared in the manifest's queries so Android lets us see it). */
private const val TASKS_PACKAGE = "dev.bookah.tasks"

/** The colour of a task's list (tasks have no colour of their own here). */
fun taskListColor(calendar: CalendarInfo?): Color = ChipColor.parse(calendar?.colorHex ?: "#00679E")

/** The round mark of a task where it is only a mark (Month chips, the all-day strip): a tap there opens the task page, it never ticks. */
@Composable
fun TaskGlyph(color: Color, size: Dp, modifier: Modifier = Modifier, described: Boolean = true) {
    val spoken = stringResource(R.string.task_glyph)
    Box(
        modifier.size(size).clip(CircleShape).border(maxOf(1.dp, size / 9), color, CircleShape)
            .then(if (described) Modifier.semantics { contentDescription = spoken } else Modifier),
    )
}

/**
 * The round mark as a checkbox with a 48 dp touch target. [tickable]: a tap marks the task done (spoken "Mark done: <title>"). Otherwise a tap
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
        else Box(Modifier.size(size).clip(CircleShape).border(maxOf(1.dp, size / 9), color, CircleShape))
    }
}

/**
 * A quiet task line under a day's events: the mark in the list's colour, the title, the time if it has one. A late task (deadline passed, with
 * or without a time) also gets the word "Late" in a small outlined label, so lateness is never told by colour alone. With [onTick] the mark is a
 * [TaskCheckbox]; the rest of the row opens the task page.
 */
@Composable
fun TaskRow(
    task: TaskItem, calendar: CalendarInfo?, zone: ZoneId, nowMs: Long, today: LocalDate, onClick: () -> Unit, showTime: Boolean = true,
    onTick: (() -> Unit)? = null, tickable: Boolean = false, saving: Boolean = false,
) {
    val late = isOverdue(task, nowMs, today)
    val lateSpoken = stringResource(R.string.task_late_description)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick)
            .padding(start = if (onTick != null) 0.dp else 12.dp, end = 12.dp, top = if (onTick != null) 0.dp else 6.dp, bottom = if (onTick != null) 0.dp else 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onTick != null) {
            TaskCheckbox(task, taskListColor(calendar), tickable, saving, onTick)
            Spacer(Modifier.size(4.dp))
        } else {
            TaskGlyph(taskListColor(calendar), 18.dp)
            Spacer(Modifier.size(12.dp))
        }
        Text(
            task.title.ifBlank { stringResource(R.string.no_title) }, Modifier.weight(1f), fontSize = 15.sp, color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        if (late) {
            Text(
                stringResource(R.string.task_late),
                Modifier.padding(start = 8.dp).border(1.dp, MaterialTheme.colorScheme.error, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 1.dp)
                    .semantics { contentDescription = lateSpoken },
                fontSize = 12.sp, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Medium, maxLines = 1,
            )
        }
        val time = if (showTime) taskTimeText(task, zone) else null
        if (time != null) Text(time, Modifier.padding(start = 8.dp), fontSize = 14.sp, color = if (late) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A [TaskRow] whose mark is a checkbox, wired to the state (Schedule, day page, Tasks page). */
@Composable
fun TickableTaskRow(task: TaskItem, calendar: CalendarInfo?, zone: ZoneId, nowMs: Long, today: LocalDate, state: UiState, onOpen: () -> Unit, onTick: () -> Unit) =
    TaskRow(
        task, calendar, zone, nowMs, today, onOpen, onTick = onTick,
        tickable = state.taskEditability[task.key] == TaskEditability.Editable, saving = state.taskSaving == task.key,
    )

/** The Tasks page from the drawer: open tasks with no date, grouped by their list. Back returns to the view before. */
@Composable
fun TasksScreen(state: UiState, onBack: () -> Unit, onOpenTask: (TaskItem) -> Unit, onTick: (TaskItem) -> Unit = {}) {
    val zone = remember { ZoneId.systemDefault() }
    val today = rememberToday(zone)
    val now = remember(state.undatedTasks, today) { System.currentTimeMillis() }
    val cals = remember(state.calendars) { state.calendars.associateBy { it.id } }
    val groups = remember(state.undatedTasks) { state.undatedTasks.groupBy { it.calendarId } }
    Column(Modifier.fillMaxSize().safeDrawingPadding().background(MaterialTheme.colorScheme.background)) {
        AppTopBar(stringResource(R.string.tasks_title), back = true, onNav = onBack)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
            item {
                Text(stringResource(R.string.tasks_hint), Modifier.padding(horizontal = 12.dp, vertical = 4.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (groups.isEmpty()) item {
                Text(stringResource(R.string.tasks_empty), Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            for ((calId, list) in groups) {
                val cal = cals[calId]
                item(key = "list|$calId") {
                    Row(Modifier.padding(start = 12.dp, end = 12.dp, top = 16.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(taskListColor(cal)))
                        Spacer(Modifier.size(8.dp))
                        Text(cal?.name.orEmpty(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                items(list, key = { it.key }) { t -> TickableTaskRow(t, cal, zone, now, today, state, { onOpenTask(t) }, { onTick(t) }) }
            }
        }
    }
}

/**
 * The task page: title, notes, dates, list, priority, link, parent task. A plain task gets a "Mark done" button ([editability] Editable); a task
 * that cannot be ticked here says why. Every other change is made in BookahTasks: "Open in BookahTasks" starts that app (if it is installed).
 */
@Composable
fun TaskPage(
    task: TaskItem, calendar: CalendarInfo?, parentTitle: String?, onDismiss: () -> Unit,
    editability: TaskEditability? = null, saving: Boolean = false, onMarkDone: () -> Unit = {},
) {
    val zone = ZoneId.systemDefault()
    val locale = rememberTexts().locale
    val context = LocalContext.current
    var missing by remember(task.key) { mutableStateOf(false) }
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
            when (editability) {
                TaskEditability.Editable -> {
                    Button(onMarkDone, Modifier.heightIn(min = 48.dp), enabled = !saving) {
                        if (saving) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary); Spacer(Modifier.size(8.dp)) }
                        Text(stringResource(if (saving) R.string.task_saving else R.string.task_mark_done))
                    }
                    Text(stringResource(R.string.task_read_only_note), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                is TaskEditability.ReadOnly -> Text(stringResource(taskHint(editability.reason)), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                // no button here (a finished task, or one not judged yet): do not promise a tick
                null -> Text(stringResource(R.string.task_page_note_other), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedButton({ missing = !openBookahTasks(context) }, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.task_open_in_tasks)) }
            if (missing) Text(stringResource(R.string.task_tasks_missing), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Why a task cannot be ticked here, and what to do instead. */
private fun taskHint(reason: TaskReadOnlyReason): Int = when (reason) {
    TaskReadOnlyReason.Repeating -> R.string.task_hint_repeating
    TaskReadOnlyReason.OpenSubtasks -> R.string.task_hint_subtasks
    TaskReadOnlyReason.ReadOnlyList -> R.string.task_hint_read_only_list
    TaskReadOnlyReason.WithPeople -> R.string.task_hint_people
    TaskReadOnlyReason.Unusual, TaskReadOnlyReason.NotATask, TaskReadOnlyReason.Finished -> R.string.task_hint_other
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

/** Starts BookahTasks. False when it is not installed (or cannot be started): the page then says so instead of crashing. */
fun openBookahTasks(context: Context): Boolean = runCatching {
    val launch = context.packageManager.getLaunchIntentForPackage(TASKS_PACKAGE) ?: return false
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
