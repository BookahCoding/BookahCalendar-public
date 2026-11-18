package dev.bookah.calendar

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.bookah.calendar.data.CalDavApi
import dev.bookah.calendar.data.CalDavClient
import dev.bookah.calendar.data.CalendarInfo
import dev.bookah.calendar.data.Editability
import dev.bookah.calendar.data.Occurrence
import dev.bookah.calendar.data.Scope
import dev.bookah.calendar.data.SeriesEdit
import dev.bookah.calendar.data.EventDraft
import dev.bookah.calendar.data.EventEdit
import dev.bookah.calendar.data.EventWriter
import dev.bookah.calendar.data.DayWeather
import dev.bookah.calendar.data.FeedConfig
import dev.bookah.calendar.data.Forecast
import dev.bookah.calendar.data.Place
import dev.bookah.calendar.data.forecastIsFresh
import dev.bookah.calendar.data.FeedStatus
import dev.bookah.calendar.data.IcsEdit
import dev.bookah.calendar.data.Instance
import dev.bookah.calendar.data.ObjectSource
import dev.bookah.calendar.data.ReadOnlyReason
import dev.bookah.calendar.data.Session
import dev.bookah.calendar.data.Source
import dev.bookah.calendar.data.SyncReport
import dev.bookah.calendar.data.TaskItem
import dev.bookah.calendar.data.WriteResult
import dev.bookah.calendar.data.normalizeServerAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

enum class Phase { Loading, SignedOut, SignedIn }

/** A message as a string resource (so it shows in English or Greek) with an optional number. */
data class UiText(@androidx.annotation.StringRes val id: Int, val arg: Int? = null) {
    @androidx.compose.runtime.Composable
    fun resolve(): String = if (arg == null) androidx.compose.ui.res.stringResource(id) else androidx.compose.ui.res.stringResource(id, arg)
}

/** Calendar colour given to a new read-only feed (a plain violet; the user's Nextcloud colours stay their own). */
private const val FEED_COLOR = "#7A5CA8"

data class UiState(
    val phase: Phase = Phase.Loading,
    val signingIn: Boolean = false,
    /** A plain-words message for the sign-in screen; never contains the password. */
    val signInError: UiText? = null,
    val syncing: Boolean = false,
    val lastReport: SyncReport? = null,
    val calendars: List<CalendarInfo> = emptyList(),
    /** Every day of the loaded window (about 6 months back, 13 ahead), empty days included. */
    val days: List<DayItems> = emptyList(),
    val view: ViewMode = ViewMode.Schedule,
    /** Set when a Month tile was tapped: the day list page for that day is open. */
    val openDay: LocalDate? = null,
    /** Set when an event was tapped: its read-only sheet is open. */
    val openEvent: Instance? = null,
    /** Set when a task was tapped: its page is open (a plain task can be ticked there; every other change is made in BookahTasks). */
    val openTask: TaskItem? = null,
    /** The Tasks page (open tasks without a date, by list) is open. */
    val tasksOpen: Boolean = false,
    /** Open tasks without any date from the shown task lists, by list then title. */
    val undatedTasks: List<TaskItem> = emptyList(),
    /** Every task (open or finished) of the shown task lists: the task page looks up a subtask's parent here. */
    val allTasks: List<TaskItem> = emptyList(),
    val settingsOpen: Boolean = false,
    /** The search page is open. */
    val searchOpen: Boolean = false,
    /** "Check for updates" is running / its last answer (null = not asked yet). */
    val updateBusy: Boolean = false,
    val updateResult: dev.bookah.calendar.data.UpdateResult? = null,
    /** The configured read-only feeds. Only id and name: the address stays encrypted in the vault and never reaches the screens. */
    val feeds: List<FeedInfo> = emptyList(),
    val feedBusy: Boolean = false,
    /** A plain-words result of the last add or remove of a feed; never contains the address. */
    val feedMessage: UiText? = null,
    /** Google events left out because Nextcloud already has the same event. */
    val hiddenDuplicates: Int = 0,
    val prefs: Prefs = Prefs(),
    val speed: Speed = Speed(),
    /** The place the owner typed for the weather, or null (then there is no weather and the app never contacts the weather service). */
    val weatherPlace: Place? = null,
    /** The forecast by date ("2026-11-15"), from the cache or the last fetch. */
    val weather: Map<String, DayWeather> = emptyMap(),
    /** Places found by the last search in Settings; null when no search is showing. */
    val weatherResults: List<Place>? = null,
    val weatherBusy: Boolean = false,
    val weatherMessage: UiText? = null,
    /** The open event form, or null. */
    val editor: Editor? = null,
    /** A save or delete is in progress. */
    val saving: Boolean = false,
    /** Why the last save or delete failed (the form stays open with what was typed). */
    val saveError: UiText? = null,
    /** The event changed elsewhere while it was being saved or deleted: the user must choose. */
    val conflict: PendingWrite? = null,
    /** A short message the user has to acknowledge (for example: the event was deleted elsewhere). */
    val notice: UiText? = null,
    /** Whether each open task of the shown lists can be ticked here, by task key (see [taskEditabilities]). */
    val taskEditability: Map<String, dev.bookah.calendar.data.TaskEditability> = emptyMap(),
    /** Whether each open task WITHOUT a date can be given a day here, by task key (see [taskMovabilities]); dated tasks are not in it. */
    val taskMovability: Map<String, dev.bookah.calendar.data.TaskEditability> = emptyMap(),
    /** The key of the task whose tick is being saved, or [UNDO_SAVING] while an undo is; null when no task write runs. */
    val taskSaving: String? = null,
    /** The bar at the bottom: "Task done · Undo", or what went wrong with a tick or an undo. */
    val taskBar: TaskBar? = null,
) {
    /** The events of one day, or none if it is outside the loaded window. */
    fun dayAt(date: LocalDate): DayItems? {
        val first = days.firstOrNull()?.date ?: return null
        val i = (date.toEpochDay() - first.toEpochDay()).toInt()
        return days.getOrNull(i)?.takeIf { it.date == date }
    }
}

enum class ViewMode { Schedule, Month, Day, ThreeDays, Week }

/** [UiState.taskSaving] while an undo of a tick is being saved. */
const val UNDO_SAVING = "undo"

/** Numbers measured on the device itself, shown in Settings (design target: events ready within 1 s of the process start). */
data class Speed(val coldStartMs: Long? = null, val expandMs: Long? = null, val syncMs: Long? = null)

/** The event form: a new event on a day, or an existing plain event with the reminders the form manages. */
sealed interface Editor {
    /** [startMinutes] = minutes after midnight when the user tapped an hour in a time grid; null = the next full hour (today) or 09:00. */
    data class New(val day: LocalDate, val startMinutes: Int? = null) : Editor
    data class Existing(val event: Instance, val reminders: List<Int>) : Editor
}

/** A save or delete that the server refused because the event changed meanwhile; kept so the user's choice can be carried out. */
data class PendingWrite(
    val kind: Kind, val href: String, val edit: EventEdit?,
    /** For a repeating event: which occurrences, and the occurrence itself. Null for a plain event. */
    val scope: dev.bookah.calendar.data.Scope? = null, val occ: dev.bookah.calendar.data.Occurrence? = null,
    /** Delete the whole stored event (an event with other people), after the strong warning. */
    val whole: Boolean = false,
) {
    enum class Kind { Save, Delete }
}

data class FeedInfo(val id: String, val name: String)

class AppViewModel(private val graph: AppGraph) : ViewModel() {
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    /** Sync touches SQLite and the network: never on the main thread. */
    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    private val feedLock get() = graph.feedLock
    private var loadedVersion = -1L
    private var session: Session? = null
    private val zone: ZoneId = ZoneId.systemDefault()

    fun setTheme(id: String) { viewModelScope.launch { graph.prefs.setTheme(id) } }
    fun setAppearance(a: dev.bookah.calendar.design.Appearance) { viewModelScope.launch { graph.prefs.setAppearance(a) } }
    fun setPureBlack(on: Boolean) { viewModelScope.launch { graph.prefs.setPureBlack(on) } }
    fun setWeekStart(d: java.time.DayOfWeek) { viewModelScope.launch { graph.prefs.setWeekStart(d) } }

    // ------------------------------------------------------------------------------------------ weather (only when a place is set)

    private fun forecastToMap(f: Forecast?) = f?.days?.associateBy { it.date }.orEmpty()

    /** Fetches the forecast when there is a place and the cached one is not fresh (or [force]). A failure keeps the old forecast. */
    fun refreshWeather(force: Boolean = false) {
        val place = _state.value.weatherPlace ?: return
        viewModelScope.launch {
            val cached = io { graph.weather.cached() }
            if (!force && forecastIsFresh(cached, System.currentTimeMillis(), LocalDate.now(zone))) return@launch
            try {
                val fresh = io { graph.weatherApi.forecast(place, System.currentTimeMillis()) }
                io { graph.weather.saveCache(fresh) }
                _state.update { it.copy(weather = forecastToMap(fresh), weatherMessage = null) }
            } catch (e: java.io.IOException) {
                // offline or the service is down: the cached forecast stays on screen
            }
        }
    }

    fun searchWeatherPlace(query: String) {
        if (query.isBlank() || _state.value.weatherBusy) return
        _state.update { it.copy(weatherBusy = true, weatherMessage = null, weatherResults = null) }
        viewModelScope.launch {
            try {
                val found = io { graph.weatherApi.searchPlaces(query, java.util.Locale.getDefault().language.ifBlank { "en" }) }
                _state.update { it.copy(weatherBusy = false, weatherResults = found, weatherMessage = if (found.isEmpty()) UiText(R.string.weather_none_found) else null) }
            } catch (e: java.io.IOException) {
                _state.update { it.copy(weatherBusy = false, weatherMessage = UiText(R.string.weather_unreachable)) }
            }
        }
    }

    fun chooseWeatherPlace(place: Place) {
        viewModelScope.launch {
            io { graph.weather.savePlace(place) }
            _state.update { it.copy(weatherPlace = place, weatherResults = null, weatherMessage = null, weather = emptyMap()) }
            refreshWeather(force = true)
        }
    }

    fun removeWeatherPlace() {
        viewModelScope.launch {
            io { graph.weather.clear() }
            _state.update { it.copy(weatherPlace = null, weather = emptyMap(), weatherResults = null, weatherMessage = null) }
        }
    }

    init {
        viewModelScope.launch {
            val place = io { graph.weather.place() }
            if (place != null) {
                _state.update { it.copy(weatherPlace = place, weather = forecastToMap(io { graph.weather.cached() })) }
                refreshWeather()
            }
        }
        viewModelScope.launch { graph.prefs.flow.collect { p -> _state.update { it.copy(prefs = p) } } }
        viewModelScope.launch {
            session = graph.sessions.load()
            if (session == null) {
                _state.update { it.copy(phase = Phase.SignedOut) }
            } else {
                _state.update { it.copy(phase = Phase.SignedIn) }
                reload(recordCold = true)          // the saved copy first: events show before any network call; this is the timed cold start
                refresh()
            }
        }
    }

    fun signIn(serverInput: String, user: String, password: String) {
        val server = normalizeServerAddress(serverInput)
        if (server == null) {
            _state.update { it.copy(signInError = UiText(R.string.err_address_https)) }
            return
        }
        if (user.isBlank() || password.isEmpty()) {
            _state.update { it.copy(signInError = UiText(R.string.err_need_both)) }
            return
        }
        val candidate = Session(server, user.trim(), password)
        _state.update { it.copy(signingIn = true, signInError = null) }
        viewModelScope.launch {
            val report = syncNextcloud(candidate)
            if (report.error != null && graph.store.calendars().isEmpty()) {
                _state.update {
                    it.copy(signingIn = false, signInError = UiText(if (report.offline) R.string.err_unreachable else R.string.err_not_accepted))
                }
                return@launch
            }
            graph.sessions.save(candidate)
            session = candidate
            RefreshWorker.schedule(graph.appContext)
            _state.update { it.copy(signingIn = false, phase = Phase.SignedIn, lastReport = report) }
            reload()
        }
    }

    fun refresh() {
        val s = session ?: return
        if (_state.value.syncing) return
        _state.update { it.copy(syncing = true) }
        refreshWeather()          // a pull to refresh also brings a stale forecast up to date
        viewModelScope.launch {
            val report = syncNextcloud(s)
            _state.update { it.copy(syncing = false, lastReport = report) }
            reload()
            // the feeds after Nextcloud, so the duplicate check sees the fresh copy; a failed feed keeps its old events
            val synced = feedLock.withLock {
                val feeds = graph.feeds.load()
                if (feeds.isNotEmpty()) { io { graph.feedSync.sync(feeds) }; true } else false
            }
            if (synced) reload()
        }
    }

    fun setCalendarVisible(calendarId: Long, visible: Boolean) {
        graph.store.setVisible(calendarId, visible)
        viewModelScope.launch { reload() }
    }

    /** Asks GitHub (the releases repository this build was made with) whether a newer version exists. Only on the button; does nothing in builds without one. */
    fun checkUpdates() {
        val repo = BuildConfig.RELEASES_REPO
        if (repo.isBlank() || _state.value.updateBusy) return
        _state.update { it.copy(updateBusy = true, updateResult = null) }
        viewModelScope.launch {
            val r = dev.bookah.calendar.data.UpdateChecker(graph.http).check(repo, BuildConfig.VERSION_NAME)
            _state.update { it.copy(updateBusy = false, updateResult = r) }
        }
    }

    fun openSearch(open: Boolean) = _state.update { it.copy(searchOpen = open) }
    fun openSettings(open: Boolean) = _state.update { it.copy(settingsOpen = open, feedMessage = null) }

    /**
     * Adds a read-only feed. The address is fetched once to check it; if that fails nothing is kept and the message says why
     * (the message never contains the address). "webcal://" is accepted and read as "https://".
     */
    fun addFeed(name: String, address: String) {
        val url = normalizeServerAddress(address.trim().replaceFirst(Regex("^webcal://", RegexOption.IGNORE_CASE), "https://"))
        if (url == null) {
            _state.update { it.copy(feedMessage = UiText(R.string.feed_err_address)) }
            return
        }
        if (_state.value.feedBusy) return
        _state.update { it.copy(feedBusy = true, feedMessage = null) }
        viewModelScope.launch {
            // one feed change or refresh at a time: a refresh that loaded the list earlier must not undo an add or a remove
            val message = feedLock.withLock {
                val before = graph.feeds.load()
                val feed = FeedConfig(java.util.UUID.randomUUID().toString().take(8), name.trim().ifBlank { graph.appContext.getString(R.string.feed_default_name) }, url, FEED_COLOR)
                graph.feeds.save(before + feed)
                val result = io { graph.feedSync.sync(before + feed) }.last()
                when (result.status) {
                    FeedStatus.Updated, FeedStatus.NotModified -> UiText(R.string.feed_added, result.events)
                    else -> {
                        graph.feeds.save(before)               // nothing is kept when the check fails
                        io { graph.feedSync.sync(before) }
                        when (result.status) {
                            FeedStatus.Offline -> UiText(R.string.feed_err_offline)
                            FeedStatus.Rejected -> UiText(R.string.feed_err_rejected)
                            else -> UiText(R.string.feed_err_invalid)
                        }
                    }
                }
            }
            _state.update { it.copy(feedBusy = false, feedMessage = message, feeds = graph.feeds.load().map { f -> FeedInfo(f.id, f.name) }) }
            reload()
        }
    }

    fun removeFeed(id: String) {
        viewModelScope.launch {
            val rest = feedLock.withLock {
                val left = graph.feeds.load().filter { it.id != id }
                graph.feeds.save(left)
                io { graph.feedSync.sync(left) }
                left
            }
            _state.update { it.copy(feedMessage = null, feeds = rest.map { f -> FeedInfo(f.id, f.name) }) }
            reload()
        }
    }

    fun showView(mode: ViewMode) = _state.update { it.copy(view = mode, openDay = null, openEvent = null, openTask = null, tasksOpen = false) }
    fun openDay(date: LocalDate?) = _state.update { it.copy(openDay = date) }
    fun openEvent(event: Instance?) = _state.update { it.copy(openEvent = event) }
    fun openTask(task: TaskItem?) = _state.update { it.copy(openTask = task) }
    fun openTasks(open: Boolean) = _state.update { it.copy(tasksOpen = open, openDay = null) }

    /** Back closes the topmost thing: task or event page, search, settings, the Tasks page, then the day page. Returns false when there is nothing to close. */
    fun back(): Boolean {
        val s = _state.value
        return when {
            s.openTask != null -> { openTask(null); true }
            s.openEvent != null -> { openEvent(null); true }
            s.searchOpen -> { openSearch(false); true }
            s.settingsOpen -> { openSettings(false); true }
            s.tasksOpen -> { openTasks(false); true }
            s.openDay != null -> { openDay(null); true }
            else -> false
        }
    }

    // ------------------------------------------------------------------------------------------ creating, changing, deleting

    /** Can this event be changed or deleted? Judged on the whole stored object and its calendar (docs/phase-3-design.md 2b). */
    fun editabilityOf(e: Instance): Editability {
        val cal = _state.value.calendars.firstOrNull { it.id == e.calendarId }
        if (cal == null || cal.source != Source.Nextcloud || !cal.editable) return Editability.ReadOnly(ReadOnlyReason.NotAnEvent)
        val stored = graph.store.objectByHref(e.objectHref) ?: return Editability.ReadOnly(ReadOnlyReason.NotAnEvent)
        val plain = IcsEdit.editability(stored.ics)
        // a repeating event may still be simple enough to change (EditableSeries), or involve other people, or be too unusual
        return if (plain is Editability.ReadOnly && plain.reason == ReadOnlyReason.Repeating) SeriesEdit.editability(stored.ics) else plain
    }

    fun openNewEvent(day: LocalDate, startMinutes: Int? = null) = _state.update { it.copy(editor = Editor.New(day, startMinutes), saveError = null) }

    fun openEdit(e: Instance) {
        val can = editabilityOf(e)
        if (can != Editability.Editable && can != Editability.EditableSeries) return      // a simple repeating event opens the form too; it asks which occurrences on Save
        val reminders = graph.store.objectByHref(e.objectHref)?.let { IcsEdit.managedReminderMinutes(it.ics) }.orEmpty()
        _state.update { it.copy(editor = Editor.Existing(e, reminders), saveError = null) }
    }

    fun closeEditor() = _state.update { it.copy(editor = null, saveError = null) }
    fun dismissNotice() = _state.update { it.copy(notice = null) }
    fun dismissConflict() = _state.update { it.copy(conflict = null) }

    fun createEvent(calendarId: Long, draft: EventDraft) = runWrite(null) { it.create(calendarId, draft) }

    /** [scope] is set for a repeating event (which occurrences); null for a plain event. */
    fun saveEdit(e: Instance, edit: EventEdit, scope: Scope? = null) {
        if (scope == null) {
            runWrite(PendingWrite(PendingWrite.Kind.Save, e.objectHref, edit)) { it.update(e.objectHref, edit) }
        } else {
            val occ = Occurrence(e.originalStart, e.start, e.end)
            runWrite(PendingWrite(PendingWrite.Kind.Save, e.objectHref, edit, scope, occ)) { it.updateOccurrence(e.objectHref, occ, scope, edit) }
        }
    }

    /** [scope] for a repeating event; [people] = a whole event with other people (the user has read the warning); otherwise a plain event. */
    fun deleteEvent(e: Instance, scope: Scope? = null, people: Boolean = false) {
        val occ = Occurrence(e.originalStart, e.start, e.end)
        when {
            scope != null -> runWrite(PendingWrite(PendingWrite.Kind.Delete, e.objectHref, null, scope, occ)) { it.deleteOccurrence(e.objectHref, e.originalStart, scope) }
            people -> runWrite(PendingWrite(PendingWrite.Kind.Delete, e.objectHref, null, whole = true)) { it.deleteWholeObject(e.objectHref) }
            else -> runWrite(PendingWrite(PendingWrite.Kind.Delete, e.objectHref, null)) { it.delete(e.objectHref) }
        }
    }

    /** The user's choice after a conflict: [keepMine] applies his change on top of the server's version (or deletes it anyway); otherwise the server's version is kept. */
    fun resolveConflict(keepMine: Boolean) {
        val c = _state.value.conflict ?: return
        _state.update { it.copy(conflict = null) }
        when {
            !keepMine -> runWrite(null) { it.keepTheirs(c.href) }
            c.kind == PendingWrite.Kind.Save && c.scope != null -> runWrite(c) { it.updateOccurrence(c.href, c.occ!!, c.scope, c.edit!!, onTheirs = true) }
            c.kind == PendingWrite.Kind.Save -> runWrite(c) { it.updateOnTheirs(c.href, c.edit!!) }
            c.scope != null -> runWrite(c) { it.deleteOccurrence(c.href, c.occ!!.originalStartMs, c.scope, onTheirs = true) }
            c.whole -> runWrite(c) { it.deleteWholeObject(c.href, onTheirs = true) }
            else -> runWrite(c) { it.deleteOnTheirs(c.href) }
        }
    }

    // ------------------------------------------------------------------------------------------ ticking tasks (online only, no outbox)

    private val taskGate = WriteGate()
    private var barCounter = 0L

    /**
     * The checkbox of a task (or "Mark done" on its page). A task that cannot be ticked here (repeating, open subtasks, read-only list ...) opens
     * its page instead, which says why. One write at a time.
     */
    fun tickTask(t: TaskItem) {
        if (_state.value.taskEditability[t.key] != dev.bookah.calendar.data.TaskEditability.Editable) { openTask(t); return }
        // a new tick replaces the bar of an earlier one: only the latest tick can be undone (decision recorded in HANDOFF)
        runTaskWrite(t.objectHref, savingKey = t.key, after = { r, page, id -> afterTaskWrite(r, false, t.objectHref, page, id) }) { it.complete(t.objectHref) }
    }

    /**
     * "Pick a day" (or a drop on the day strip) for a task WITHOUT a date: a date-only start on [date], as BookahTasks writes it. A task that
     * cannot be given a day here (parked, repeating, read-only list ...) opens its page instead, which says why. One write at a time. A day that
     * is no longer allowed (the picker stayed open over midnight) or a write that is still running is said on the bar, never dropped silently.
     */
    fun moveTask(t: TaskItem, date: LocalDate) {
        val s = _state.value
        when (moveDecision(s.taskMovability[t.key], date, LocalDate.now(zone), busy = s.taskSaving != null || s.saving)) {
            MoveDecision.OpenPage -> { openTask(t); return }
            MoveDecision.PickAgain -> { _state.update { it.copy(taskBar = TaskBar.Message(TaskNote.PickAgain, ++barCounter)) }; return }
            MoveDecision.Busy -> { _state.update { it.copy(taskBar = TaskBar.Message(TaskNote.Busy, ++barCounter)) }; return }
            MoveDecision.Go -> {}
        }
        runTaskWrite(t.objectHref, savingKey = t.key, after = { r, page, id -> afterTaskMove(r, false, t.objectHref, page, date, id) }) { it.setDay(t.objectHref, date) }
    }

    /** "Undo" on the bar: takes back the tick or the move it shows, only if the task is still the version that write made (otherwise it says so). */
    fun undoTaskBar() {
        when (val bar = _state.value.taskBar) {
            is TaskBar.Done -> runTaskWrite(bar.href, savingKey = UNDO_SAVING, after = { r, page, id -> afterTaskWrite(r, true, bar.href, page, id) }) {
                it.reopen(bar.href, bar.etag, bar.before)
            }
            is TaskBar.Moved -> runTaskWrite(bar.href, savingKey = UNDO_SAVING, after = { r, page, id -> afterTaskMove(r, true, bar.href, page, bar.date, id) }) {
                it.clearDay(bar.href, bar.etag, bar.date)
            }
            else -> {}
        }
    }

    /**
     * Runs one task write: off the main thread, under the sync lock, one at a time. The days are loaded again BEFORE the bar appears and the gate
     * opens, all in one step: so the row is gone when "Undo" shows, an Undo tapped on the bar is never swallowed by a gate still closed, and a
     * second tap on the vanishing row finds the gate closed. "May have been saved" starts a refresh so the phone learns the server's version.
     */
    private fun runTaskWrite(
        href: String, savingKey: String,
        /** What the screen does next, from the result, the open task page's object and the bar's number ([afterTaskWrite] / [afterTaskMove]). */
        after: (dev.bookah.calendar.data.TaskWriteResult, String?, Long) -> AfterTaskWrite,
        action: suspend (dev.bookah.calendar.data.TaskWriter) -> dev.bookah.calendar.data.TaskWriteResult,
    ) {
        val s = session ?: return
        if (_state.value.saving || !taskGate.tryEnter()) return
        _state.update { it.copy(taskSaving = savingKey, taskBar = null) }
        viewModelScope.launch {
            var next: AfterTaskWrite? = null
            try {
                val result = io { graph.syncLock.withLock { action(dev.bookah.calendar.data.TaskWriter(graph.store, graph.apiFactory(s))) } }
                reload()
                val a = after(result, _state.value.openTask?.objectHref, ++barCounter)
                next = a
                _state.update { st -> st.copy(taskSaving = null, taskBar = a.bar, openTask = if (a.closePage) null else st.openTask) }
            } finally {
                if (next == null) _state.update { it.copy(taskSaving = null) }
                taskGate.leave()
            }
            if (next?.refresh == true) refresh()
        }
    }

    /** The bar's time is up (or OK was tapped). A newer bar is left alone. */
    fun dismissTaskBar(id: Long) = _state.update { if (it.taskBar?.id == id) it.copy(taskBar = null) else it }

    /** One write at a time, never in the middle of a sync, off the main thread. */
    private fun runWrite(pending: PendingWrite?, action: suspend (EventWriter) -> WriteResult) {
        val s = session ?: return
        if (_state.value.saving || _state.value.taskSaving != null) return
        _state.update { it.copy(saving = true, saveError = null) }
        viewModelScope.launch {
            val result = io { graph.syncLock.withLock { action(EventWriter(graph.store, graph.apiFactory(s))) } }
            when (result) {
                is WriteResult.Saved -> {
                    _state.update { it.copy(saving = false, editor = null, openEvent = null, conflict = null) }
                    reload()
                }
                is WriteResult.Conflict -> _state.update { it.copy(saving = false, conflict = pending) }
                WriteResult.Gone -> {
                    _state.update { it.copy(saving = false, editor = null, openEvent = null, conflict = null, notice = UiText(R.string.write_gone)) }
                    reload()
                }
                is WriteResult.Refused -> _state.update { it.copy(saving = false, saveError = UiText(R.string.write_refused)) }
                is WriteResult.Failed -> _state.update { it.copy(saving = false, saveError = UiText(if (result.offline) R.string.write_offline else R.string.write_failed)) }
            }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            RefreshWorker.cancel(graph.appContext)      // a running background refresh must not refill the cache after sign-out
            graph.sessions.clear()
            // the encrypted feed addresses go too: the next sign-in must not bring a secret address back silently
            feedLock.withLock { graph.feeds.save(emptyList()) }
            // under the sync lock: a write or sync still running finishes first and cannot refill the cache after it was cleared
            graph.syncLock.withLock { graph.store.clearAll() }
            session = null
            _state.value = UiState(phase = Phase.SignedOut, prefs = _state.value.prefs)   // the look is not account data
        }
    }

    /** Expands the cached events for the loaded window (about six months back, thirteen ahead) off the main thread. */
    private suspend fun reload(recordCold: Boolean = false) {
        val today = LocalDate.now(zone)
        val window = loadedWindow(today)
        val from = window.first
        val to = window.end
        loadedVersion = graph.store.version()
        val startedAt = SystemClock.elapsedRealtime()
        class Loaded(
            val cals: List<CalendarInfo>, val days: List<DayItems>, val hidden: Int, val tasks: List<TaskItem>, val undated: List<TaskItem>,
            val tickable: Map<String, dev.bookah.calendar.data.TaskEditability>, val movable: Map<String, dev.bookah.calendar.data.TaskEditability>,
        )
        val loaded = withContext(Dispatchers.Default) {
            val cals = graph.store.calendars()
            val shown = cals.filter { it.visible }.associateBy { it.id }
            val all = graph.store.allObjects()
            // a Google event that Nextcloud already has is left out (checked over all Nextcloud calendars, shown or not)
            val (usable, hidden) = dropGoogleDuplicates(all, cals)
            // events come only from shown lists that hold events: a task-only list never reaches the expander (nor the reminders)
            val sources = eventSources(usable, cals)
            graph.expander.retainOnly(sources.map { it.cacheKey }.toSet())
            val fromMs = from.atStartOfDay(zone).toInstant().toEpochMilli()
            val toMs = to.atStartOfDay(zone).toInstant().toEpochMilli()
            // all-day events are stored as UTC dates, so widen the range by a day on both sides, then place them on days
            val instances = graph.expander.expand(sources, fromMs - 86_400_000L, toMs + 86_400_000L)
            // the tasks of shown Nextcloud lists that hold tasks (read-only; never handed to the reminders)
            val taskLists = shown.values.filter { it.source == Source.Nextcloud && it.kind.holdsTasks }.map { it.id }.toSet()
            val taskSources = all.filter { it.calendarId in taskLists && it.ics.contains("BEGIN:VTODO", ignoreCase = true) }
                .map { ObjectSource(it.calendarId, it.href, it.ics, it.href + "|" + it.etag, readOnly = true) }
            graph.taskReader.retainOnly(taskSources.map { it.cacheKey }.toSet())
            val tasks = graph.taskReader.read(taskSources, zone)
            // which open tasks get a working checkbox (judged here, off the main thread, not per row while drawing)
            val taskObjects = all.filter { it.calendarId in taskLists }.associateBy { it.href }
            val calsById = cals.associateBy { it.id }
            val tickable = taskEditabilities(tasks, taskObjects, calsById)
            val undated = undatedTasks(tasks)
            // and which undated ones may be given a day here (the Tasks page's "Pick a day" and drag)
            val movable = taskMovabilities(undated, taskObjects, calsById)
            Loaded(cals, attachTasks(groupByDay(instances, from, to, zone), groupTasksByDay(tasks, from, to, zone, java.time.LocalDate.now(zone))), hidden, tasks, undated, tickable, movable)
        }
        val feeds = graph.feeds.load().map { FeedInfo(it.id, it.name) }
        val now = SystemClock.elapsedRealtime()
        _state.update {
            // "screen created to events ready" is recorded once, for the first load that has events (a cold start)
            val cold = it.speed.coldStartMs ?: if (recordCold && loaded.days.any { d -> d.events.isNotEmpty() }) now - BookahApp.screenCreatedAt else null
            it.copy(
                calendars = loaded.cals, days = loaded.days, hiddenDuplicates = loaded.hidden, feeds = feeds,
                allTasks = loaded.tasks, undatedTasks = loaded.undated, taskEditability = loaded.tickable, taskMovability = loaded.movable,
                // an open task page shows the newest reading of that task (it stays as it was if the task is gone)
                openTask = it.openTask?.let { o -> loaded.tasks.firstOrNull { t -> t.key == o.key } ?: o },
                speed = it.speed.copy(coldStartMs = cold, expandMs = now - startedAt),
            )
        }
        // the events changed (or were just loaded): the phone's reminder alarms follow them
        viewModelScope.launch { graph.rescheduleReminders() }
    }

    /** Called when the app comes back to the front: a background refresh may have changed the cache while no screen was looking. */
    fun onResume() {
        if (_state.value.phase == Phase.SignedIn && graph.store.version() != loadedVersion) viewModelScope.launch { reload() }
        refreshWeather()          // only does something when a place is set and the cached forecast is older than 3 hours
    }

    /** One Nextcloud sync at a time (the screen and the background worker share the lock): at most one request stream to the server. */
    private suspend fun syncNextcloud(s: Session): SyncReport {
        val startedAt = SystemClock.elapsedRealtime()
        val report = io { graph.syncLock.withLock { graph.sync.sync(graph.apiFactory(s)) } }
        _state.update { it.copy(speed = it.speed.copy(syncMs = SystemClock.elapsedRealtime() - startedAt)) }
        return report
    }

    class Factory(private val graph: AppGraph) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = AppViewModel(graph) as T
    }
}
