package dev.bookah.calendar.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.concurrent.atomic.AtomicLong

/**
 * The phone's copy of the calendars: two small tables in plain SQLite (no code generation needed).
 * Version 2 (Phase 4 tasks): `calendars.kind` = [ListKind] position (0 events, 1 tasks, 2 both).
 */
class SqliteCalendarStore(context: Context, name: String = "calendar.db") : CalendarStore {
    private val helper = object : SQLiteOpenHelper(context.applicationContext, name, null, 2) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE calendars(id INTEGER PRIMARY KEY AUTOINCREMENT, href TEXT NOT NULL UNIQUE, name TEXT NOT NULL, " +
                    "color TEXT NOT NULL, source INTEGER NOT NULL, editable INTEGER NOT NULL, visible INTEGER NOT NULL, sync_token TEXT, " +
                    "kind INTEGER NOT NULL DEFAULT 0)",
            )
            db.execSQL("CREATE TABLE objects(href TEXT PRIMARY KEY, calendar_id INTEGER NOT NULL, etag TEXT NOT NULL, ics TEXT NOT NULL)")
            db.execSQL("CREATE INDEX objects_calendar ON objects(calendar_id)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // version 1 knew only event calendars: they all become kind 0 (events), their show/hide choice and objects stay. A mixed list is
            // corrected by the next sync (the kind follows the server); task-only lists were never stored and arrive with that sync.
            if (oldVersion < 2) db.execSQL("ALTER TABLE calendars ADD COLUMN kind INTEGER NOT NULL DEFAULT 0")
        }

        // An older app version installed over this one (a test build, a rollback): the extra kind column is harmless to it, so the copy
        // is kept as it is instead of the default crash ("Can't downgrade database").
        override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
    }
    private val ver = AtomicLong(0)
    private val db: SQLiteDatabase get() = helper.writableDatabase

    override fun syncFeedCalendars(feeds: List<FeedCalendar>): List<CalendarInfo> {
        db.beginTransaction()
        try {
            val known = calendars().filter { it.source == Source.Google }.associateBy { it.href }
            for (f in feeds) {
                val old = known[f.href]
                if (old == null) {
                    val v = ContentValues().apply {
                        put("href", f.href); put("name", f.name); put("color", f.colorHex)
                        put("source", Source.Google.ordinal); put("editable", 0); put("visible", 1)
                    }
                    db.insert("calendars", null, v)
                } else {
                    db.update("calendars", ContentValues().apply { put("name", f.name); put("color", f.colorHex) }, "id=?", arrayOf(old.id.toString()))
                }
            }
            val keep = feeds.map { it.href }.toSet()
            for (old in known.values) if (old.href !in keep) {
                db.delete("objects", "calendar_id=?", arrayOf(old.id.toString()))
                db.delete("calendars", "id=?", arrayOf(old.id.toString()))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        ver.incrementAndGet()
        return calendars()
    }

    override fun replaceNextcloudCalendars(remote: List<RemoteCalendar>): List<CalendarInfo> {
        db.beginTransaction()
        try {
            val known = calendars().filter { it.source == Source.Nextcloud }.associateBy { it.href }
            val keep = HashSet<String>()
            for (r in remote) {
                keep.add(r.href)
                val editable = r.canWrite && !isContactBirthdays(r.href)
                val old = known[r.href]
                if (old == null) {
                    val v = ContentValues().apply {
                        put("href", r.href); put("name", r.name); put("color", r.colorHex ?: DEFAULT_COLOR)
                        put("source", Source.Nextcloud.ordinal); put("editable", if (editable) 1 else 0)
                        put("visible", if (isContactBirthdays(r.href)) 0 else 1); put("kind", r.kind.ordinal)
                    }
                    db.insert("calendars", null, v)
                } else {
                    // never "visible": the owner's show/hide choice survives every sync
                    val v = ContentValues().apply {
                        put("name", r.name); put("color", r.colorHex ?: old.colorHex); put("editable", if (editable) 1 else 0); put("kind", r.kind.ordinal)
                    }
                    db.update("calendars", v, "id=?", arrayOf(old.id.toString()))
                }
            }
            for (old in known.values) if (old.href !in keep) {
                db.delete("objects", "calendar_id=?", arrayOf(old.id.toString()))
                db.delete("calendars", "id=?", arrayOf(old.id.toString()))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        ver.incrementAndGet()
        return calendars()
    }

    override fun calendars(): List<CalendarInfo> {
        val out = ArrayList<CalendarInfo>()
        db.rawQuery("SELECT id, href, name, color, source, editable, visible, sync_token, kind FROM calendars ORDER BY id", null).use { c ->
            while (c.moveToNext()) {
                out.add(
                    CalendarInfo(
                        id = c.getLong(0), href = c.getString(1), name = c.getString(2), colorHex = c.getString(3),
                        source = Source.entries[c.getInt(4)], editable = c.getInt(5) == 1, visible = c.getInt(6) == 1,
                        syncToken = if (c.isNull(7)) null else c.getString(7),
                        kind = ListKind.entries.getOrElse(c.getInt(8)) { ListKind.Events },
                    ),
                )
            }
        }
        return out
    }

    override fun setVisible(calendarId: Long, visible: Boolean) {
        db.update("calendars", ContentValues().apply { put("visible", if (visible) 1 else 0) }, "id=?", arrayOf(calendarId.toString()))
        ver.incrementAndGet()
    }

    override fun setSyncToken(calendarId: Long, token: String?) {
        db.update("calendars", ContentValues().apply { if (token == null) putNull("sync_token") else put("sync_token", token) }, "id=?", arrayOf(calendarId.toString()))
    }

    override fun objects(calendarId: Long) = query("SELECT href, calendar_id, etag, ics FROM objects WHERE calendar_id=?", arrayOf(calendarId.toString()))
    override fun allObjects() = query("SELECT href, calendar_id, etag, ics FROM objects", null)
    override fun objectByHref(href: String) = query("SELECT href, calendar_id, etag, ics FROM objects WHERE href=?", arrayOf(href)).firstOrNull()

    private fun query(sql: String, args: Array<String>?): List<StoredObject> {
        val out = ArrayList<StoredObject>()
        db.rawQuery(sql, args).use { c -> while (c.moveToNext()) out.add(StoredObject(c.getString(0), c.getLong(1), c.getString(2), c.getString(3))) }
        return out
    }

    override fun upsertObjects(calendarId: Long, objects: List<RemoteObject>) {
        db.beginTransaction()
        try {
            for (o in objects) {
                val v = ContentValues().apply { put("href", o.href); put("calendar_id", calendarId); put("etag", o.etag); put("ics", o.ics) }
                db.insertWithOnConflict("objects", null, v, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        ver.incrementAndGet()
    }

    override fun deleteObjects(calendarId: Long, hrefs: List<String>) {
        db.beginTransaction()
        try {
            for (h in hrefs) db.delete("objects", "href=? AND calendar_id=?", arrayOf(h, calendarId.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        ver.incrementAndGet()
    }

    override fun clearObjects(calendarId: Long) {
        db.delete("objects", "calendar_id=?", arrayOf(calendarId.toString()))
        ver.incrementAndGet()
    }

    override fun objectCount(calendarId: Long): Int =
        db.rawQuery("SELECT COUNT(*) FROM objects WHERE calendar_id=?", arrayOf(calendarId.toString())).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    override fun version() = ver.get()

    override fun clearAll() {
        db.delete("objects", null, null)
        db.delete("calendars", null, null)
        ver.incrementAndGet()
    }
}
