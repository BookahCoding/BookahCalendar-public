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
) {
    /** The events of one day, or none if it is outside the loaded window. */
    fun dayAt(date: LocalDate): DayItems? {
        val first = days.firstOrNull()?.date ?: return null
        val i = (date.toEpochDay() - first.toEpochDay()).toInt()
        return days.getOrNull(i)?.takeIf { it.date == date }
    }
}

enum class ViewMode { Schedule, Month, Day, ThreeDays, Week }

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

    fun showView(mode: ViewMode) = _state.update { it.copy(view = mode, openDay = null, openEvent = null) }
    fun openDay(date: LocalDate?) = _state.update { it.copy(openDay = date) }
    fun openEvent(event: Instance?) = _state.update { it.copy(openEvent = event) }

    /** Back closes the topmost thing: event sheet, then day page. Returns false when there is nothing to close. */
    fun back(): Boolean {
        val s = _state.value
        return when {
            s.openEvent != null -> { openEvent(null); true }
            s.searchOpen -> { openSearch(false); true }
            s.settingsOpen -> { openSettings(false); true }
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

    /** One write at a time, never in the middle of a sync, off the main thread. */
    private fun runWrite(pending: PendingWrite?, action: suspend (EventWriter) -> WriteResult) {
        val s = session ?: return
        if (_state.value.saving) return
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
            graph.store.clearAll()
            session = null
            _state.value = UiState(phase = Phase.SignedOut, prefs = _state.value.prefs)   // the look is not account data
        }
    }

    /** Expands the cached events for the loaded window (about six months back, thirteen ahead) off the main thread. */
    private suspend fun reload(recordCold: Boolean = false) {
        val today = LocalDate.now(zone)
        val from = today.minusDays(180).withDayOfMonth(1)
        val to = today.plusMonths(13).withDayOfMonth(1)
        loadedVersion = graph.store.version()
        val startedAt = SystemClock.elapsedRealtime()
        val loaded = withContext(Dispatchers.Default) {
            val cals = graph.store.calendars()
            val shown = cals.filter { it.visible }.associateBy { it.id }
            // a Google event that Nextcloud already has is left out (checked over all Nextcloud calendars, shown or not)
            val (usable, hidden) = dropGoogleDuplicates(graph.store.allObjects(), cals)
            val sources = usable.filter { it.calendarId in shown }.map {
                ObjectSource(it.calendarId, it.href, it.ics, it.href + "|" + it.etag, readOnly = shown.getValue(it.calendarId).editable.not())
            }
            graph.expander.retainOnly(sources.map { it.cacheKey }.toSet())
            val fromMs = from.atStartOfDay(zone).toInstant().toEpochMilli()
            val toMs = to.atStartOfDay(zone).toInstant().toEpochMilli()
            // all-day events are stored as UTC dates, so widen the range by a day on both sides, then place them on days
            val instances = graph.expander.expand(sources, fromMs - 86_400_000L, toMs + 86_400_000L)
            Triple(cals, groupByDay(instances, from, to, zone), hidden)
        }
        val feeds = graph.feeds.load().map { FeedInfo(it.id, it.name) }
        val now = SystemClock.elapsedRealtime()
        _state.update {
            // "screen created to events ready" is recorded once, for the first load that has events (a cold start)
            val cold = it.speed.coldStartMs ?: if (recordCold && loaded.second.any { d -> d.events.isNotEmpty() }) now - BookahApp.screenCreatedAt else null
            it.copy(
                calendars = loaded.first, days = loaded.second, hiddenDuplicates = loaded.third, feeds = feeds,
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
