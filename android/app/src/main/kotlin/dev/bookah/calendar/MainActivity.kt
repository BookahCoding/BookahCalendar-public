package dev.bookah.calendar

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.bookah.calendar.ui.AppDrawer
import dev.bookah.calendar.ui.BookahTheme
import dev.bookah.calendar.ui.DayListScreen
import dev.bookah.calendar.ui.EventSheet
import dev.bookah.calendar.ui.MonthScreen
import dev.bookah.calendar.ui.ScheduleScreen
import dev.bookah.calendar.ui.SearchScreen
import dev.bookah.calendar.ui.EditEventScreen
import dev.bookah.calendar.ui.NewEventButton
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.unit.dp
import dev.bookah.calendar.ui.SettingsScreen
import dev.bookah.calendar.ui.TimeGridScreen
import dev.bookah.calendar.ui.SignInScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        BookahApp.screenCreatedAt = android.os.SystemClock.elapsedRealtime()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val graph = (application as BookahApp).graph
        setContent {
            val model: AppViewModel = viewModel(factory = AppViewModel.Factory(graph))
            val state by model.state.collectAsStateWithLifecycle()
            BookahTheme(state.prefs.themeId, state.prefs.appearance) {
                // coming back to the app: pick up what a background refresh changed meanwhile
                androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) { model.onResume() }
                // Android 13+ needs a one-time yes for notifications before a reminder can ring; asked once after sign-in, never nagged
                val askNotifications = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) {}
                androidx.compose.runtime.LaunchedEffect(state.phase) {
                    if (state.phase == Phase.SignedIn && android.os.Build.VERSION.SDK_INT >= 33 && !ReminderNotifier.notificationsAllowed(this@MainActivity)) {
                        val sp = getSharedPreferences("reminders", MODE_PRIVATE)
                        if (!sp.getBoolean("asked", false)) { sp.edit().putBoolean("asked", true).apply(); askNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS) }
                    }
                }
                val drawer = rememberDrawerState(DrawerValue.Closed)
                val scope = rememberCoroutineScope()
                // Back closes the topmost thing: the drawer, then the event sheet, then the day page; otherwise the system leaves the app.
                BackHandler(enabled = drawer.isOpen || state.openEvent != null || state.settingsOpen || state.searchOpen || state.openDay != null) {
                    if (drawer.isOpen) scope.launch { drawer.close() } else model.back()
                }
                // a Surface sets the default text and icon colour for everything inside (otherwise black text on a dark page)
                androidx.compose.material3.Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
                  androidx.compose.runtime.CompositionLocalProvider(dev.bookah.calendar.ui.LocalOpenSearch provides { model.openSearch(true) }) {
                  Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    when (state.phase) {
                        Phase.Loading -> {}
                        Phase.SignedOut -> SignInScreen(state.signingIn, state.signInError, model::signIn)
                        Phase.SignedIn -> ModalNavigationDrawer(
                            drawerContent = {
                                AppDrawer(
                                    state,
                                    onView = { model.showView(it); scope.launch { drawer.close() } },
                                    onRefresh = { model.refresh(); scope.launch { drawer.close() } },
                                    onToggle = model::setCalendarVisible,
                                    onSettings = { model.openSettings(true); scope.launch { drawer.close() } },
                                )
                            },
                            drawerState = drawer,
                            gesturesEnabled = state.openDay == null,
                        ) {
                            val openDay = state.openDay
                            when {
                                state.searchOpen -> SearchScreen(state, onBack = { model.openSearch(false) }, onOpenEvent = model::openEvent)
                                state.settingsOpen -> SettingsScreen(state, onBack = { model.openSettings(false) }, onAddFeed = model::addFeed, onRemoveFeed = model::removeFeed, onSignOut = model::signOut, onTheme = model::setTheme, onAppearance = model::setAppearance, onWeekStart = model::setWeekStart, onSearchWeatherPlace = model::searchWeatherPlace, onChooseWeatherPlace = model::chooseWeatherPlace, onRemoveWeatherPlace = model::removeWeatherPlace, onCheckUpdates = model::checkUpdates)
                                openDay != null -> DayListScreen(openDay, state, onBack = { model.openDay(null) }, onOpenEvent = model::openEvent)
                                state.view == ViewMode.Month -> MonthScreen(state, onMenu = { scope.launch { drawer.open() } }, onOpenDay = model::openDay)
                                state.view == ViewMode.Day || state.view == ViewMode.ThreeDays || state.view == ViewMode.Week -> androidx.compose.runtime.key(state.view) {
                                    TimeGridScreen(
                                        state, daysPerPage = when (state.view) { ViewMode.Day -> 1; ViewMode.ThreeDays -> 3; else -> 7 },
                                        onMenu = { scope.launch { drawer.open() } }, onOpenEvent = model::openEvent,
                                        onNewAt = { day, minutes -> model.openNewEvent(day, minutes) },
                                    )
                                }
                                else -> ScheduleScreen(state, onMenu = { scope.launch { drawer.open() } }, onRefresh = model::refresh, onOpenEvent = model::openEvent)
                            }
                        }
                    }
                    // the "+" button: new event on today, or on the day whose page is open
                    val showFab = state.phase == Phase.SignedIn && state.openEvent == null && state.editor == null && !state.settingsOpen && !state.searchOpen
                    if (showFab) NewEventButton(
                        { model.openNewEvent(state.openDay ?: java.time.LocalDate.now()) },
                        Modifier.align(androidx.compose.ui.Alignment.BottomEnd).safeDrawingPadding().padding(16.dp),
                    )
                    state.openEvent?.let { e ->
                        EventSheet(
                            e, state.calendars.firstOrNull { it.id == e.calendarId }, model.editabilityOf(e), state.saving,
                            onEdit = { model.openEdit(e) }, onDelete = { scope, people -> model.deleteEvent(e, scope, people) }, onDismiss = { model.openEvent(null) },
                        )
                    }
                    state.editor?.let { editor ->
                        EditEventScreen(
                            editor, state, onClose = model::closeEditor, onCreate = model::createEvent, onSave = model::saveEdit,
                            isSeries = editor is Editor.Existing && model.editabilityOf(editor.event) == dev.bookah.calendar.data.Editability.EditableSeries,
                        )
                    }
                    state.conflict?.let { c ->
                        val deleting = c.kind == PendingWrite.Kind.Delete
                        androidx.compose.material3.AlertDialog(
                            onDismissRequest = model::dismissConflict,
                            title = { androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(R.string.conflict_title)) },
                            text = { androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(if (deleting) R.string.conflict_delete_body else R.string.conflict_save_body)) },
                            confirmButton = {
                                androidx.compose.material3.TextButton({ model.resolveConflict(true) }) {
                                    androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(if (deleting) R.string.conflict_delete_anyway else R.string.conflict_keep_mine))
                                }
                            },
                            dismissButton = {
                                androidx.compose.material3.TextButton({ model.resolveConflict(false) }) {
                                    androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(if (deleting) R.string.conflict_keep_it else R.string.conflict_use_theirs))
                                }
                            },
                        )
                    }
                    state.notice?.let { n ->
                        androidx.compose.material3.AlertDialog(
                            onDismissRequest = model::dismissNotice,
                            text = { androidx.compose.material3.Text(n.resolve()) },
                            confirmButton = { androidx.compose.material3.TextButton(model::dismissNotice) { androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(R.string.event_ok)) } },
                        )
                    }
                  }
                  }
                }
            }
        }
    }
}
