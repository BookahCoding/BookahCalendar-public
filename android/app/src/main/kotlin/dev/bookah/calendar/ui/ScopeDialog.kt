package dev.bookah.calendar.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.bookah.calendar.R
import dev.bookah.calendar.data.Scope

/**
 * "This event / This and following events / All events" for a repeating event (docs/phase-4-design.md 1). "This event" is preselected, as in the
 * screenshots of the original calendar. [destructive] colours the confirm button as a danger action (delete).
 */
@Composable
fun ScopeDialog(title: Int, confirm: Int, destructive: Boolean, onDismiss: () -> Unit, onChosen: (Scope) -> Unit) {
    var selected by remember { mutableStateOf(Scope.This) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = {
            Column {
                for ((scope, label) in listOf(Scope.This to R.string.scope_this, Scope.Following to R.string.scope_following, Scope.All to R.string.scope_all)) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { selected = scope }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected == scope, { selected = scope })
                        Text(stringResource(label), Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton({ onChosen(selected) }) {
                Text(stringResource(confirm), color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.event_cancel)) } },
    )
}
