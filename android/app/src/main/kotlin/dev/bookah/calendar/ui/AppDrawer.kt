package dev.bookah.calendar.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.bookah.calendar.R
import dev.bookah.calendar.UiState
import dev.bookah.calendar.ViewMode
import dev.bookah.calendar.data.ListKind
import dev.bookah.calendar.data.Source
import dev.bookah.calendar.design.ChipColor

/**
 * The drawer (docs/design-spec.md 2.5): view switcher, Refresh, calendars with a checkbox in their own colour, grouped by source.
 * Day, 3 days and Week are shown but not available until Phase 2. The footer opens Settings (account, feeds).
 */
@Composable
fun AppDrawer(
    state: UiState, onView: (ViewMode) -> Unit, onRefresh: () -> Unit, onToggle: (Long, Boolean) -> Unit, onSettings: () -> Unit, onTasks: () -> Unit = {},
) {
    ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 12.dp)) {
            // our name on a strip of the month's artwork (docs/design-spec.md 2.5)
            MonthBanner(java.time.LocalDate.now().month, "BookahCalendar", height = 84.dp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
            // the order of docs/design-spec.md 2.5: Schedule, Day, 3 days, Week, Month
            for ((mode, label) in listOf(
                ViewMode.Schedule to R.string.view_schedule, ViewMode.Day to R.string.view_day, ViewMode.ThreeDays to R.string.view_3days,
                ViewMode.Week to R.string.view_week, ViewMode.Month to R.string.view_month,
            )) {
                NavigationDrawerItem({ Text(stringResource(label)) }, state.view == mode && !state.tasksOpen, { onView(mode) }, Modifier.padding(horizontal = 12.dp))
            }
            // the Tasks page (open tasks without a date); the number is how many it lists. Only when the server has lists that hold tasks.
            if (state.calendars.any { it.source == Source.Nextcloud && it.kind.holdsTasks }) {
                NavigationDrawerItem(
                    { Text(stringResource(R.string.drawer_tasks)) }, state.tasksOpen, onTasks, Modifier.padding(horizontal = 12.dp),
                    badge = { if (state.undatedTasks.isNotEmpty()) Text(state.undatedTasks.size.toString()) },
                )
            }
            NavigationDrawerItem({ Text(stringResource(R.string.action_refresh)) }, false, onRefresh, Modifier.padding(horizontal = 12.dp))
            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            // event calendars (a list that holds both events and tasks stays here: its one switch shows or hides both), then task-only lists
            val groups = listOf(
                R.string.source_nextcloud to state.calendars.filter { it.source == Source.Nextcloud && it.kind != ListKind.Tasks },
                R.string.source_task_lists to state.calendars.filter { it.source == Source.Nextcloud && it.kind == ListKind.Tasks },
                R.string.source_google to state.calendars.filter { it.source == Source.Google },
            )
            for ((label, group) in groups) {
                if (group.isEmpty()) continue
                Text(stringResource(label), Modifier.padding(horizontal = 28.dp, vertical = 8.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                for (cal in group) {
                    val color = ChipColor.parse(cal.colorHex)
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                        // the checkbox carries the calendar's own colour
                        Checkbox(cal.visible, { onToggle(cal.id, it) }, colors = CheckboxDefaults.colors(checkedColor = color, uncheckedColor = color, checkmarkColor = ChipColor.forCalendar(color).text))
                        Text(cal.name, Modifier.weight(1f), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            NavigationDrawerItem({ Text(stringResource(R.string.settings)) }, false, onSettings, Modifier.padding(horizontal = 12.dp))
        }
    }
}
