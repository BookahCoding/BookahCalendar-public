package dev.bookah.calendar

import android.app.Application
import android.content.Context
import dev.bookah.calendar.data.Expander
import dev.bookah.calendar.data.FeedStore
import dev.bookah.calendar.data.FeedSync
import dev.bookah.calendar.data.Http
import dev.bookah.calendar.data.KeystoreVault
import dev.bookah.calendar.data.SessionStore
import dev.bookah.calendar.data.SqliteCalendarStore
import dev.bookah.calendar.data.SyncEngine
import dev.bookah.calendar.data.VaultSessionStore

/** The few long-lived objects of the app, created once. */
class AppGraph(context: Context) {
    val appContext: Context = context.applicationContext
    val store = SqliteCalendarStore(context)
    val vault = KeystoreVault(context)
    val sessions: SessionStore = VaultSessionStore(vault)
    val http = Http.newClient()

    /** How the app talks to the server. Debug builds can swap in an in-memory fake for emulator tests; release builds never do. */
    @Volatile var apiFactory: (dev.bookah.calendar.data.Session) -> dev.bookah.calendar.data.CalDavApi = { dev.bookah.calendar.data.CalDavClient(http, it) }
    val expander = Expander()
    val sync = SyncEngine(store)
    /** One feed change or sync at a time, whether it comes from the screen or from the background worker. */
    val feedLock = kotlinx.coroutines.sync.Mutex()

    /** One Nextcloud sync at a time, from the screen or from the background worker. */
    val syncLock = kotlinx.coroutines.sync.Mutex()
    val prefs = PrefsStore(context)
    val feeds = FeedStore(vault)
    val weatherApi = dev.bookah.calendar.data.WeatherApi(http)
    val weather = WeatherRepo(vault, java.io.File(context.filesDir, "weather.json"))
    val feedSync = FeedSync(store, http)

    /**
     * Plans the phone's reminder alarms from the events on the phone (visible calendars only; a Google copy that Nextcloud already has is not
     * counted twice): the next 14 days, the nearest ones. Called after every change of the events and after a restart of the phone.
     */
    suspend fun rescheduleReminders() = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
        val now = System.currentTimeMillis()
        val cals = store.calendars()
        val shown = cals.filter { it.visible }.associateBy { it.id }
        val (usable, _) = dropGoogleDuplicates(store.allObjects(), cals)
        val sources = usable.filter { it.calendarId in shown }.map {
            dev.bookah.calendar.data.ObjectSource(it.calendarId, it.href, it.ics, it.href + "|" + it.etag, readOnly = shown.getValue(it.calendarId).editable.not())
        }
        val instances = expander.expand(sources, now - 86_400_000L, now + (AlarmPlanner.HORIZON_DAYS + 1) * 86_400_000L)
        ReminderScheduler(appContext).reschedule(AlarmPlanner.plan(instances, now, java.time.ZoneId.systemDefault()))
    }
}

class BookahApp : Application() {
    companion object {
        /** When MainActivity started to be created (SystemClock.elapsedRealtime); a background run that started the process earlier does not count. */
        @Volatile var screenCreatedAt: Long = android.os.SystemClock.elapsedRealtime()
    }

    val graph: AppGraph by lazy { AppGraph(this) }

    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        RefreshWorker.schedule(this)      // KEEP: an existing schedule is left alone; with nobody signed in a run does nothing
    }
}
