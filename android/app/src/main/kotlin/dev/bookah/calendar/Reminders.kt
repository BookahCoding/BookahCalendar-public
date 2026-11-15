package dev.bookah.calendar

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val ACTION_FIRE = "dev.bookah.calendar.REMINDER_FIRE"
private const val ACTION_SNOOZE = "dev.bookah.calendar.REMINDER_SNOOZE"
private const val CHANNEL = "reminders"
private const val SNOOZE_MS = 10 * 60_000L

/** Turns the planned reminders into phone alarms and takes back the ones that are no longer planned. Everything stays on the phone. */
class ReminderScheduler(private val context: Context) {
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val prefs = context.getSharedPreferences("reminders", Context.MODE_PRIVATE)

    /** Makes the phone's alarms equal to [plan]: new ones are set, changed ones updated, vanished ones cancelled. */
    fun reschedule(plan: List<Reminder>) {
        val old = prefs.getStringSet(KEY, emptySet()).orEmpty()
        val keep = plan.map { it.key }.toSet()
        for (k in old - keep) cancel(k)
        for (r in plan) set(r.triggerAtMs, pending(r.key, r, create = true)!!)
        prefs.edit().putStringSet(KEY, keep).apply()
    }

    fun snooze(r: Reminder) = set(System.currentTimeMillis() + SNOOZE_MS, pending(r.key + "|snooze", r, create = true)!!)

    private fun cancel(key: String) {
        pending(key, null, create = false)?.let { alarms.cancel(it); it.cancel() }
    }

    private fun set(at: Long, pi: PendingIntent) {
        // exact when the phone allows it (calendar apps may use the exact-alarm permission); otherwise a slightly inexact alarm, never nothing
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()
        runCatching {
            if (exact) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    private fun pending(key: String, r: Reminder?, create: Boolean): PendingIntent? {
        val intent = Intent(context, ReminderReceiver::class.java).setAction(ACTION_FIRE).setData(Uri.parse("bookah://reminder/" + Uri.encode(key)))
        if (r != null) putReminder(intent, r)
        val flags = PendingIntent.FLAG_IMMUTABLE or if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE
        return PendingIntent.getBroadcast(context, 0, intent, flags)
    }

    private companion object { const val KEY = "scheduled" }
}

private fun putReminder(i: Intent, r: Reminder) {
    i.putExtra("key", r.key).putExtra("title", r.title).putExtra("start", r.startMs).putExtra("end", r.endMs)
        .putExtra("allDay", r.allDay).putExtra("location", r.location)
}

private fun readReminder(i: Intent) = Reminder(
    i.getStringExtra("key").orEmpty(), 0L, i.getStringExtra("title").orEmpty(), i.getLongExtra("start", 0L), i.getLongExtra("end", 0L),
    i.getBooleanExtra("allDay", false), i.getStringExtra("location"),
)

/** The notification itself: the event's title, when it is, where; Open and Snooze (10 minutes). */
object ReminderNotifier {
    fun notificationsAllowed(context: Context) = NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun show(context: Context, r: Reminder, id: Int = r.key.hashCode()) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.reminders_channel), NotificationManager.IMPORTANCE_HIGH))
        val zone = ZoneId.systemDefault()
        val time = DateTimeFormatter.ofPattern("HH:mm")
        val when_ = if (r.allDay) context.getString(R.string.all_day)
        else time.format(Instant.ofEpochMilli(r.startMs).atZone(zone)) + " – " + time.format(Instant.ofEpochMilli(r.endMs).atZone(zone))
        val text = listOfNotNull(when_, r.location?.takeIf { it.isNotBlank() }).joinToString(" · ")
        val open = PendingIntent.getActivity(
            context, id, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val snoozeIntent = Intent(context, ReminderReceiver::class.java).setAction(ACTION_SNOOZE).setData(Uri.parse("bookah://snooze/" + Uri.encode(r.key)))
        putReminder(snoozeIntent, r)
        snoozeIntent.putExtra("notificationId", id)
        val snooze = PendingIntent.getBroadcast(context, id, snoozeIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(r.title.ifBlank { context.getString(R.string.no_title) }).setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_REMINDER).setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true)
            .setContentIntent(open)
            .addAction(0, context.getString(R.string.reminders_snooze), snooze)
            .build()
        try { NotificationManagerCompat.from(context).notify(id, n) } catch (e: SecurityException) { /* notifications not allowed: nothing to show */ }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val r = readReminder(intent)
        when (intent.action) {
            ACTION_FIRE -> ReminderNotifier.show(context, r)
            ACTION_SNOOZE -> {
                NotificationManagerCompat.from(context).cancel(intent.getIntExtra("notificationId", r.key.hashCode()))
                ReminderScheduler(context).snooze(r)
            }
        }
    }
}

/** After a restart of the phone (alarms are lost) or an update of the app: plan the reminders again from what is on the phone. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try { (context.applicationContext as BookahApp).graph.rescheduleReminders() } finally { pending.finish() }
        }
    }
}
