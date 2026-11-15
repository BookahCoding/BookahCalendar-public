package dev.bookah.calendar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import dev.bookah.calendar.locationUrl
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.bookah.calendar.data.Editability
import dev.bookah.calendar.data.ReadOnlyReason
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.bookah.calendar.R
import dev.bookah.calendar.Texts
import dev.bookah.calendar.data.CalendarInfo
import dev.bookah.calendar.data.Instance
import dev.bookah.calendar.design.ChipColor
import dev.bookah.calendar.reminderText
import dev.bookah.calendar.repeatSummary
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The event page (docs/design-spec.md 2.6): a full-height page with a close button at the left; for a plain event in a writable calendar
 * also Edit and Delete at the right. For an event that cannot be changed (repeating, with other people) a line says why.
 * It is a page, not a pull-down sheet: a sheet and a scrolling list inside it fought over the finger and made long events jump up and down.
 */
@Composable
fun EventSheet(
    event: Instance, calendar: CalendarInfo?, editability: Editability, busy: Boolean,
    onEdit: () -> Unit, onDelete: (scope: dev.bookah.calendar.data.Scope?, people: Boolean) -> Unit, onDismiss: () -> Unit,
) {
    val zone = ZoneId.systemDefault()
    val texts = rememberTexts()
    var confirmDelete by remember { mutableStateOf(false) }
    var askScope by remember { mutableStateOf(false) }
    var askPeople by remember { mutableStateOf(false) }
    val isSeries = editability == Editability.EditableSeries
    val hasPeople = editability is Editability.ReadOnly && editability.reason == ReadOnlyReason.Invitation && calendar?.editable == true
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHigh).safeDrawingPadding()
            // the page covers the screen: it must not let taps fall through to the list behind it
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {},
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onDismiss) { Icon(Icons.Default.Close, stringResource(R.string.close)) }
            Spacer(Modifier.weight(1f))
            // editable events (plain or a simple repeating one) get both buttons; an event with other people can only be deleted, after a warning
            if (editability == Editability.Editable || isSeries) IconButton(onEdit, enabled = !busy) { Icon(Icons.Default.Edit, stringResource(R.string.event_edit)) }
            if (editability == Editability.Editable || isSeries || hasPeople) {
                IconButton({ if (isSeries) askScope = true else if (hasPeople) askPeople = true else confirmDelete = true }, enabled = !busy) {
                    Icon(Icons.Default.Delete, stringResource(R.string.event_delete))
                }
            }
        }
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.padding(top = 10.dp).size(16.dp).clip(RoundedCornerShape(4.dp)).background(ChipColor.parse(calendar?.colorHex ?: "#00679E"))) {}
                Text(event.title.ifBlank { stringResource(R.string.no_title) }, fontSize = 28.sp, lineHeight = 34.sp, color = MaterialTheme.colorScheme.onSurface)
            }
            Text(whenText(event, zone, texts), fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
            event.repeat?.let { Text(repeatSummary(it, texts, zone), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (event.alarmMinutes.isNotEmpty()) Detail(event.alarmMinutes.joinToString("\n") { reminderText(it, texts) })
            if (calendar != null) Detail(calendar.name + if (event.readOnly) "  ·  " + stringResource(R.string.read_only) else "")
            if (!event.location.isNullOrBlank()) LocationLink(event.location!!)
            if (!event.description.isNullOrBlank()) Detail(event.description!!)
            // why a change is not offered (only for events in writable calendars; feeds already show the lock)
            if (editability is Editability.ReadOnly && calendar != null && calendar.editable) {
                when (editability.reason) {
                    ReadOnlyReason.Repeating -> R.string.readonly_repeating
                    ReadOnlyReason.Invitation -> R.string.people_note
                    ReadOnlyReason.NotAnEvent -> null
                }?.let { Text(stringResource(it), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete_title)) },
            text = { Text(stringResource(R.string.delete_body)) },
            confirmButton = { TextButton({ confirmDelete = false; onDelete(null, false) }) { Text(stringResource(R.string.event_delete), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton({ confirmDelete = false }) { Text(stringResource(R.string.event_cancel)) } },
        )
    }
    if (askScope) ScopeDialog(R.string.scope_delete_title, R.string.event_delete, destructive = true, onDismiss = { askScope = false }) { scope -> askScope = false; onDelete(scope, false) }
    if (askPeople) {
        AlertDialog(
            onDismissRequest = { askPeople = false },
            title = { Text(stringResource(R.string.people_delete_title)) },
            text = { Text(stringResource(R.string.people_delete_body)) },
            confirmButton = { TextButton({ askPeople = false; onDelete(null, true) }) { Text(stringResource(R.string.event_delete), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton({ askPeople = false }) { Text(stringResource(R.string.event_cancel)) } },
        )
    }
}

@Composable
private fun Detail(text: String) = Text(text, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)

/** The place of the event as a link: a tap opens it in Google Maps (or the browser). The tap target is at least 48 dp tall. */
@Composable
private fun LocationLink(location: String) {
    val context = LocalContext.current
    Text(
        location, fontSize = 16.sp, color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable {
            // no map app or browser installed: nothing happens, no crash
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(locationUrl(location)))) }
        }.padding(vertical = 12.dp),
    )
}

private val TIME_FMT = DateTimeFormatter.ofPattern("HH:mm")

/** "Sun 15 Nov, 10:00 – 11:00"; "Sun 15 Nov, all day"; "Mon 2 Nov – Wed 4 Nov" for a multi-day event. */
internal fun whenText(e: Instance, zone: ZoneId, t: Texts): String {
    val dateFmt = DateTimeFormatter.ofPattern("EEE d MMM", t.locale)
    if (e.allDay) {
        val first = LocalDate.ofEpochDay(Math.floorDiv(e.start, 86_400_000L))
        val last = LocalDate.ofEpochDay(Math.floorDiv(maxOf(e.end - 1, e.start), 86_400_000L))
        return if (first == last) t.get(R.string.when_all_day, dateFmt.format(first)) else dateFmt.format(first) + " – " + dateFmt.format(last)
    }
    val s = Instant.ofEpochMilli(e.start).atZone(zone)
    val en = Instant.ofEpochMilli(e.end).atZone(zone)
    return when {
        e.end <= e.start -> dateFmt.format(s) + ", " + TIME_FMT.format(s)
        s.toLocalDate() == en.toLocalDate() -> dateFmt.format(s) + ", " + TIME_FMT.format(s) + " – " + TIME_FMT.format(en)
        else -> dateFmt.format(s) + " " + TIME_FMT.format(s) + " – " + dateFmt.format(en) + " " + TIME_FMT.format(en)
    }
}
