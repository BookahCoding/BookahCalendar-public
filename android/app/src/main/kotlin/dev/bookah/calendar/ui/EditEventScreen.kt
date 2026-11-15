package dev.bookah.calendar.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import dev.bookah.calendar.Editor
import dev.bookah.calendar.R
import dev.bookah.calendar.UiState
import dev.bookah.calendar.data.EventDraft
import dev.bookah.calendar.data.EventEdit
import dev.bookah.calendar.data.EventWhen
import dev.bookah.calendar.data.IcsEdit
import dev.bookah.calendar.data.Instance
import dev.bookah.calendar.data.Source
import dev.bookah.calendar.reminderText
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Check
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import dev.bookah.calendar.data.EventColors
import dev.bookah.calendar.data.RepeatEnd
import dev.bookah.calendar.data.RepeatFreq
import dev.bookah.calendar.data.RepeatRule
import dev.bookah.calendar.data.RepeatWriter
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

private val PRESETS = listOf(0, 5, 10, 15, 30, 60, 1440)
private const val DAY_MS = 86_400_000L

/** The values of the form before the user touches it (also the baseline for "what did he change"). */
private data class Initial(
    val title: String, val allDay: Boolean, val start: LocalDateTime, val end: LocalDateTime,
    val location: String, val notes: String, val reminders: List<Int>, val calendarId: Long,
    /** "" = the calendar's colour, a palette name, or "?" for an own colour the form cannot show (left alone unless the user picks another). */
    val colorName: String = "",
)

private fun initialFor(editor: Editor, state: UiState, zone: ZoneId): Initial = when (editor) {
    is Editor.New -> {
        val first = state.calendars.firstOrNull { it.source == Source.Nextcloud && it.editable }?.id ?: -1L
        val now = LocalDateTime.now(zone)
        val start = when {
            editor.startMinutes != null -> editor.day.atTime(editor.startMinutes / 60, editor.startMinutes % 60)       // the hour that was tapped in a grid
            editor.day == LocalDate.now(zone) -> now.plusHours(1).withMinute(0).withSecond(0).withNano(0)
            else -> editor.day.atTime(9, 0)
        }
        Initial("", false, start, start.plusHours(1), "", "", listOf(0), first)          // a reminder at the time of the event is already set up
    }
    is Editor.Existing -> {
        val e: Instance = editor.event
        val colorName = if (e.colorHex == null) "" else EventColors.choiceFor(e.colorHex)?.name ?: "?"
        if (e.allDay) {
            val first = LocalDate.ofEpochDay(Math.floorDiv(e.start, DAY_MS))
            val last = LocalDate.ofEpochDay(Math.floorDiv(maxOf(e.end - 1, e.start), DAY_MS))
            Initial(e.title, true, first.atTime(9, 0), last.atTime(10, 0), e.location.orEmpty(), e.description.orEmpty(), editor.reminders, e.calendarId, colorName)
        } else {
            val s = Instant.ofEpochMilli(e.start).atZone(zone).toLocalDateTime()
            val en = Instant.ofEpochMilli(maxOf(e.end, e.start)).atZone(zone).toLocalDateTime()
            Initial(e.title, false, s, en, e.location.orEmpty(), e.description.orEmpty(), editor.reminders, e.calendarId, colorName)
        }
    }
}

/**
 * The full-screen event form (docs/design-spec.md 2.7, kept simple): create a new event or change a plain existing one. Nothing is sent
 * until Save. A failed save keeps everything typed. Closing with unsaved changes asks first.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditEventScreen(
    editor: Editor,
    state: UiState,
    onClose: () -> Unit,
    onCreate: (calendarId: Long, draft: EventDraft) -> Unit,
    onSave: (event: Instance, edit: EventEdit, scope: dev.bookah.calendar.data.Scope?) -> Unit,
    /** A repeating event: Save first asks which occurrences (this / following / all). */
    isSeries: Boolean = false,
) {
    val zone = remember { ZoneId.systemDefault() }
    val init = remember(editor) { initialFor(editor, state, zone) }
    val texts = rememberTexts()
    val locale = texts.locale

    var title by rememberSaveable(editor) { mutableStateOf(init.title) }
    var allDay by rememberSaveable(editor) { mutableStateOf(init.allDay) }
    var startDay by rememberSaveable(editor) { mutableStateOf(init.start.toLocalDate().toEpochDay()) }
    var startMin by rememberSaveable(editor) { mutableStateOf(init.start.toLocalTime().toSecondOfDay() / 60) }
    var endDay by rememberSaveable(editor) { mutableStateOf(init.end.toLocalDate().toEpochDay()) }
    var endMin by rememberSaveable(editor) { mutableStateOf(init.end.toLocalTime().toSecondOfDay() / 60) }
    var location by rememberSaveable(editor) { mutableStateOf(init.location) }
    var notes by rememberSaveable(editor) { mutableStateOf(init.notes) }
    var reminders by rememberSaveable(editor) { mutableStateOf(ArrayList(init.reminders)) }
    var calendarId by rememberSaveable(editor) { mutableStateOf(init.calendarId) }
    var colorName by rememberSaveable(editor) { mutableStateOf(init.colorName) }
    var picker by remember { mutableStateOf<String?>(null) }          // "start-date", "start-time", "end-date", "end-time"
    var askDiscard by remember { mutableStateOf(false) }
    var pendingEdit by remember { mutableStateOf<EventEdit?>(null) }
    var reminderMenu by remember { mutableStateOf(false) }
    // repeat (new events only): "" = does not repeat, else a RepeatFreq name
    var repeatFreq by rememberSaveable(editor) { mutableStateOf("") }
    var repeatEvery by rememberSaveable(editor) { mutableStateOf(1) }
    var repeatDays by rememberSaveable(editor) { mutableStateOf(ArrayList<Int>()) }      // DayOfWeek numbers, weekly only
    var repeatEnd by rememberSaveable(editor) { mutableStateOf(0) }                      // 0 never, 1 on a date, 2 after a number of times
    var repeatUntil by rememberSaveable(editor) { mutableStateOf(0L) }                   // epoch day, 0 = not chosen yet
    var repeatTimes by rememberSaveable(editor) { mutableStateOf(10) }

    val start = LocalDate.ofEpochDay(startDay).atTime(LocalTime.ofSecondOfDay(startMin * 60L))
    val end = LocalDate.ofEpochDay(endDay).atTime(LocalTime.ofSecondOfDay(endMin * 60L))
    val whenAt: EventWhen =
        if (allDay) EventWhen.AllDay(start.toLocalDate(), end.toLocalDate())
        else EventWhen.Timed(start.atZone(zone).toInstant().toEpochMilli(), end.atZone(zone).toInstant().toEpochMilli())
    val initialWhen: EventWhen =
        if (init.allDay) EventWhen.AllDay(init.start.toLocalDate(), init.end.toLocalDate())
        else EventWhen.Timed(init.start.atZone(zone).toInstant().toEpochMilli(), init.end.atZone(zone).toInstant().toEpochMilli())
    val timeProblem = IcsEdit.validate(whenAt)

    val isNew = editor is Editor.New
    val rule: RepeatRule? = if (!isNew || repeatFreq.isEmpty()) null else RepeatRule(
        RepeatFreq.valueOf(repeatFreq), repeatEvery, repeatDays.map { DayOfWeek.of(it) }.toSet(),
        when (repeatEnd) { 1 -> RepeatEnd.Until(LocalDate.ofEpochDay(repeatUntil)); 2 -> RepeatEnd.Count(repeatTimes); else -> RepeatEnd.Never },
    )
    val repeatProblem = rule?.problem(start.toLocalDate())
    val writable = remember(state.calendars) { state.calendars.filter { it.source == Source.Nextcloud && it.editable } }
    val dirty = if (isNew) title.isNotBlank() || location.isNotBlank() || notes.isNotBlank() || reminders.toSet() != init.reminders.toSet() || colorName != init.colorName
    else title != init.title || location != init.location || notes != init.notes || whenAt != initialWhen || reminders.toSet() != init.reminders.toSet() || colorName != init.colorName
    val canSave = !state.saving && timeProblem == null && repeatProblem == null && (if (isNew) title.isNotBlank() && calendarId > 0 else dirty)

    BackHandler { if (dirty && !state.saving) askDiscard = true else onClose() }

    fun shiftEndWithStart(newStartDay: Long, newStartMin: Int) {
        // moving the start moves the end by the same amount, so the length of the event stays
        val oldStart = LocalDate.ofEpochDay(startDay).atTime(LocalTime.ofSecondOfDay(startMin * 60L))
        val newStart = LocalDate.ofEpochDay(newStartDay).atTime(LocalTime.ofSecondOfDay(newStartMin * 60L))
        val newEnd = end.plusSeconds(java.time.Duration.between(oldStart, newStart).seconds)
        startDay = newStartDay; startMin = newStartMin
        endDay = newEnd.toLocalDate().toEpochDay(); endMin = newEnd.toLocalTime().toSecondOfDay() / 60
    }

    fun doSave() {
        if (isNew) {
            onCreate(calendarId, EventDraft(title.trim(), whenAt, location.trim(), notes.trim(), reminders.toList(), rule, zone.id, colorName.takeIf { it.isNotEmpty() && it != "?" }))
        } else {
            val event = (editor as Editor.Existing).event
            val edit = EventEdit(
                title = title.trim().takeIf { it != init.title },
                location = location.trim().takeIf { it != init.location },
                notes = notes.trim().takeIf { it != init.notes },
                whenAt = whenAt.takeIf { it != initialWhen },
                reminderMinutes = reminders.toList().takeIf { reminders.toSet() != init.reminders.toSet() },
                color = if (colorName == init.colorName || colorName == "?") null else colorName,
            )
            if (isSeries) pendingEdit = edit else onSave(event, edit, null)       // a series asks "this / following / all" first
        }
    }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).safeDrawingPadding()
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {},
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton({ if (dirty && !state.saving) askDiscard = true else onClose() }) { Icon(Icons.Default.Close, stringResource(R.string.close)) }
            Text(
                stringResource(if (isNew) R.string.form_new_title else R.string.form_edit_title), Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
            )
            Button({ doSave() }, enabled = canSave, modifier = Modifier.padding(end = 12.dp)) {
                Text(if (state.saving) stringResource(R.string.status_syncing) else stringResource(R.string.form_save))
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            state.saveError?.let { Text(it.resolve(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            OutlinedTextField(
                title, { title = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.form_title_hint)) },
                textStyle = MaterialTheme.typography.titleLarge, singleLine = false, maxLines = 3,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            )
            if (isNew) {
                if (writable.isEmpty()) Text(stringResource(R.string.form_no_calendar), color = MaterialTheme.colorScheme.error)
                else FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (c in writable) FilterChip(calendarId == c.id, { calendarId = c.id }, { Text(c.name) })
                }
            } else {
                state.calendars.firstOrNull { it.id == calendarId }?.let {
                    Text(stringResource(R.string.form_calendar) + ": " + it.name, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.form_all_day), Modifier.weight(1f), fontSize = 16.sp)
                Switch(allDay, { allDay = it }, enabled = !isSeries)      // a series cannot change between timed and all-day here
            }
            DateTimeRow(stringResource(R.string.form_start), start.toLocalDate(), start.toLocalTime(), allDay, locale, { picker = "start-date" }, { picker = "start-time" })
            DateTimeRow(stringResource(R.string.form_end), end.toLocalDate(), end.toLocalTime(), allDay, locale, { picker = "end-date" }, { picker = "end-time" })
            if (timeProblem != null) Text(stringResource(R.string.form_end_before_start), color = MaterialTheme.colorScheme.error)
            if (isNew) {
                RepeatSection(
                    freq = repeatFreq, every = repeatEvery, days = repeatDays, end = repeatEnd, until = repeatUntil, times = repeatTimes,
                    startDay = start.toLocalDate(), locale = locale,
                    onFreq = {
                        repeatFreq = it
                        if (it == RepeatFreq.Weekly.name && repeatDays.isEmpty()) repeatDays = arrayListOf(start.toLocalDate().dayOfWeek.value)
                    },
                    onEvery = { repeatEvery = it }, onDays = { repeatDays = ArrayList(it) }, onTimes = { repeatTimes = it },
                    onEnd = {
                        repeatEnd = it
                        if (it == 1 && repeatUntil == 0L) repeatUntil = start.toLocalDate().plusMonths(1).toEpochDay()
                    },
                    onPickUntil = { picker = "until-date" },
                    problem = repeatProblem != null,
                )
            }
            ColorPicker(colorName, state.calendars.firstOrNull { it.id == calendarId }?.colorHex, { colorName = it })
            HorizontalDivider()
            OutlinedTextField(location, { location = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.form_place)) }, singleLine = true)
            Text(stringResource(R.string.form_reminders), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (m in reminders.sortedDescending()) {
                    InputChip(false, { reminders = ArrayList(reminders - m) }, { Text(reminderText(m, texts)) }, trailingIcon = { Icon(Icons.Default.Close, stringResource(R.string.form_discard)) })
                }
            }
            Row {
                TextButton({ reminderMenu = true }) { Text(stringResource(R.string.form_add_reminder)) }
                DropdownMenu(reminderMenu, { reminderMenu = false }) {
                    for (m in PRESETS.filter { it !in reminders }) DropdownMenuItem({ Text(reminderText(m, texts)) }, { reminders = ArrayList(reminders + m); reminderMenu = false })
                }
            }
            Text(stringResource(R.string.form_reminder_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                notes, { notes = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.form_notes)) }, minLines = 3,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            )
            Row(Modifier.padding(bottom = 24.dp)) {}
        }
    }

    // pickers
    val which = picker
    if (which != null && which.endsWith("date")) {
        val day = LocalDate.ofEpochDay(if (which.startsWith("until")) repeatUntil else if (which.startsWith("start")) startDay else endDay)
        val st = rememberDatePickerState(initialSelectedDateMillis = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { picker = null },
            confirmButton = {
                TextButton({
                    st.selectedDateMillis?.let { ms ->
                        val picked = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay()
                        when {
                            which.startsWith("until") -> repeatUntil = picked
                            which.startsWith("start") -> shiftEndWithStart(picked, startMin)
                            else -> endDay = picked
                        }
                    }
                    picker = null
                }) { Text(stringResource(R.string.event_ok)) }
            },
            dismissButton = { TextButton({ picker = null }) { Text(stringResource(R.string.event_cancel)) } },
        ) { DatePicker(st) }
    }
    if (which != null && which.endsWith("time")) {
        val minutes = if (which.startsWith("start")) startMin else endMin
        val st = rememberTimePickerState(initialHour = minutes / 60, initialMinute = minutes % 60, is24Hour = true)
        AlertDialog(
            onDismissRequest = { picker = null },
            text = { TimePicker(st) },
            confirmButton = {
                TextButton({
                    val picked = st.hour * 60 + st.minute
                    if (which.startsWith("start")) shiftEndWithStart(startDay, picked) else endMin = picked
                    picker = null
                }) { Text(stringResource(R.string.event_ok)) }
            },
            dismissButton = { TextButton({ picker = null }) { Text(stringResource(R.string.event_cancel)) } },
        )
    }
    pendingEdit?.let { edit ->
        ScopeDialog(R.string.scope_edit_title, R.string.form_save, destructive = false, onDismiss = { pendingEdit = null }) { scope ->
            pendingEdit = null
            onSave((editor as Editor.Existing).event, edit, scope)
        }
    }
    if (askDiscard) {
        AlertDialog(
            onDismissRequest = { askDiscard = false },
            title = { Text(stringResource(R.string.form_discard_title)) },
            confirmButton = { TextButton({ askDiscard = false; onClose() }) { Text(stringResource(R.string.form_discard)) } },
            dismissButton = { TextButton({ askDiscard = false }) { Text(stringResource(R.string.form_keep_editing)) } },
        )
    }
}

@Composable
private fun colorLabel(name: String): String = stringResource(
    when (name) {
        "tomato" -> R.string.color_tomato; "salmon" -> R.string.color_salmon; "orange" -> R.string.color_orange; "gold" -> R.string.color_gold
        "mediumseagreen" -> R.string.color_lightgreen; "seagreen" -> R.string.color_green; "turquoise" -> R.string.color_turquoise
        "royalblue" -> R.string.color_blue; "mediumpurple" -> R.string.color_lilac; "purple" -> R.string.color_purple
        "hotpink" -> R.string.color_pink; else -> R.string.color_gray
    },
)

/** The colour of this event: "same as the calendar" or one of twelve colours. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorPicker(selected: String, calendarHex: String?, onPick: (String) -> Unit) {
    Text(stringResource(R.string.color_title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        // first: the calendar's own colour (no colour written into the event)
        Swatch(calendarHex?.let { dev.bookah.calendar.design.ChipColor.parse(it) } ?: MaterialTheme.colorScheme.primary, stringResource(R.string.color_calendar), selected == "") { onPick("") }
        for (c in EventColors.palette) Swatch(dev.bookah.calendar.design.ChipColor.parse(c.hex), colorLabel(c.name), selected == c.name) { onPick(c.name) }
    }
}

@Composable
private fun Swatch(color: androidx.compose.ui.graphics.Color, label: String, isSelected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).clip(androidx.compose.foundation.shape.CircleShape)
            .semantics { contentDescription = label; selected = isSelected; role = androidx.compose.ui.semantics.Role.RadioButton }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(32.dp).clip(androidx.compose.foundation.shape.CircleShape).background(color)
                .border(if (isSelected) 3.dp else 1.dp, if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline, androidx.compose.foundation.shape.CircleShape),
            contentAlignment = Alignment.Center,
        ) { if (isSelected) Icon(androidx.compose.material.icons.Icons.Default.Check, null, Modifier.size(18.dp), tint = if (color.luminance() > 0.5f) androidx.compose.ui.graphics.Color.Black else androidx.compose.ui.graphics.Color.White) }
    }
}

/** The repeat part of the form for a new event: how often, which weekdays (weekly), and when it ends. */
@Composable
private fun RepeatSection(
    freq: String, every: Int, days: List<Int>, end: Int, until: Long, times: Int, startDay: LocalDate, locale: Locale,
    onFreq: (String) -> Unit, onEvery: (Int) -> Unit, onDays: (List<Int>) -> Unit, onTimes: (Int) -> Unit, onEnd: (Int) -> Unit, onPickUntil: () -> Unit,
    problem: Boolean,
) {
    Text(stringResource(R.string.repeat_title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(freq.isEmpty(), { onFreq("") }, { Text(stringResource(R.string.repeat_none)) })
        FilterChip(freq == RepeatFreq.Daily.name, { onFreq(RepeatFreq.Daily.name) }, { Text(stringResource(R.string.repeat_f_daily)) })
        FilterChip(freq == RepeatFreq.Weekly.name, { onFreq(RepeatFreq.Weekly.name) }, { Text(stringResource(R.string.repeat_f_weekly)) })
        FilterChip(freq == RepeatFreq.Monthly.name, { onFreq(RepeatFreq.Monthly.name) }, { Text(stringResource(R.string.repeat_f_monthly)) })
        FilterChip(freq == RepeatFreq.Yearly.name, { onFreq(RepeatFreq.Yearly.name) }, { Text(stringResource(R.string.repeat_f_yearly)) })
    }
    if (freq.isEmpty()) return
    val lessText = stringResource(R.string.repeat_less)
    val moreText = stringResource(R.string.repeat_more)
    val unit = when (freq) {
        RepeatFreq.Daily.name -> R.plurals.unit_days; RepeatFreq.Weekly.name -> R.plurals.unit_weeks
        RepeatFreq.Monthly.name -> R.plurals.unit_months; else -> R.plurals.unit_years
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.repeat_every), Modifier.padding(end = 8.dp))
        TextButton({ onEvery((every - 1).coerceAtLeast(1)) }, Modifier.semantics { contentDescription = lessText }, enabled = every > 1) { Text("−", fontSize = 20.sp) }
        Text(every.toString(), Modifier.padding(horizontal = 6.dp), fontSize = 18.sp)
        TextButton({ onEvery((every + 1).coerceAtMost(99)) }, Modifier.semantics { contentDescription = moreText }, enabled = every < 99) { Text("+", fontSize = 20.sp) }
        Text(pluralStringResource(unit, every), Modifier.padding(start = 6.dp))
    }
    if (freq == RepeatFreq.Weekly.name) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (d in DayOfWeek.entries) FilterChip(
                d.value in days,
                { onDays(if (d.value in days) days - d.value else days + d.value) },
                { Text(d.getDisplayName(TextStyle.SHORT, locale)) },
            )
        }
        val rule = RepeatRule(RepeatFreq.Weekly, every, days.map { DayOfWeek.of(it) }.toSet())
        val shift = RepeatWriter.startShiftDays(rule, startDay)
        if (shift != 0L) Text(
            stringResource(R.string.repeat_first_moved, DateTimeFormatter.ofPattern("EEE d MMM", locale).format(startDay.plusDays(shift))),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (freq == RepeatFreq.Monthly.name && startDay.dayOfMonth > 28) Text(
        stringResource(R.string.repeat_month_note, startDay.dayOfMonth), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(stringResource(R.string.repeat_ends), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(end == 0, { onEnd(0) }, { Text(stringResource(R.string.repeat_end_never)) })
        FilterChip(end == 1, { onEnd(1) }, { Text(stringResource(R.string.repeat_end_date)) })
        FilterChip(end == 2, { onEnd(2) }, { Text(stringResource(R.string.repeat_end_count)) })
    }
    if (end == 1) Text(
        DateTimeFormatter.ofPattern("EEE d MMM yyyy", locale).format(LocalDate.ofEpochDay(until)),
        Modifier.clickable(onClick = onPickUntil).padding(vertical = 12.dp), fontSize = 16.sp,
    )
    if (end == 2) Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton({ onTimes((times - 1).coerceAtLeast(1)) }, Modifier.semantics { contentDescription = lessText }, enabled = times > 1) { Text("−", fontSize = 20.sp) }
        Text(times.toString(), Modifier.padding(horizontal = 6.dp), fontSize = 18.sp)
        TextButton({ onTimes((times + 1).coerceAtMost(999)) }, Modifier.semantics { contentDescription = moreText }, enabled = times < 999) { Text("+", fontSize = 20.sp) }
        Text(pluralStringResource(R.plurals.unit_times, times), Modifier.padding(start = 6.dp))
    }
    if (problem) Text(stringResource(R.string.repeat_end_before_start), color = MaterialTheme.colorScheme.error)
}

@Composable
private fun DateTimeRow(label: String, date: LocalDate, time: LocalTime, allDay: Boolean, locale: Locale, onDate: () -> Unit, onTime: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(0.6f), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            DateTimeFormatter.ofPattern("EEE d MMM yyyy", locale).format(date),
            Modifier.weight(1.6f).clickable(onClick = onDate).padding(vertical = 12.dp), fontSize = 16.sp,
        )
        if (!allDay) Text(
            DateTimeFormatter.ofPattern("HH:mm").format(time),
            Modifier.weight(0.8f).clickable(onClick = onTime).padding(vertical = 12.dp), fontSize = 16.sp,
        )
    }
}
