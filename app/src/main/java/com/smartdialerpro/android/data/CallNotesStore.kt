package com.smartdialerpro.android.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

/**
 * Call notes: short user-written notes attached to a phone number,
 * shown under the number in call logs. Storage is a plain
 * SQLiteOpenHelper on purpose — this app has zero external
 * dependencies. Keys are the last 10 digits, the same normalization
 * CnapStore uses, so +91 and local forms share one note.
 *
 * Initialized from SmartDialerApplication; every method degrades to
 * a no-op/empty result before init so a missed init can never crash.
 */
object CallNotesStore {
    private const val TAG = "CallNotesStore"
    private const val DB_NAME = "call_notes.db"
    private const val DB_VERSION = 1
    private const val TABLE = "call_notes"

    @Volatile
    private var helper: Helper? = null

    fun init(context: Context) {
        if (helper == null) {
            synchronized(this) {
                if (helper == null) {
                    helper = Helper(context.applicationContext)
                }
            }
        }
    }

    /** Idempotent guard for call sites that hold a Context. */
    fun ensureInit(context: Context) = init(context)

    /** Normalized lookup key: last 10 digits when available. */
    fun keyFor(number: String): String {
        val digits = number.filter(Char::isDigit)
        return if (digits.length > 10) digits.takeLast(10) else digits
    }

    fun putNote(number: String, note: String) {
        val key = keyFor(number)
        if (key.isBlank()) return
        val dbHelper = helper ?: return
        try {
            val db = dbHelper.writableDatabase
            if (note.isBlank()) {
                db.delete(TABLE, "number_key = ?", arrayOf(key))
                return
            }
            val values = ContentValues().apply {
                put("number_key", key)
                put("display_number", number)
                put("note", note.trim())
                put("updated_at", System.currentTimeMillis())
            }
            db.insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE)
        } catch (t: Throwable) {
            Log.w(TAG, "Call note write failed", t)
        }
    }

    fun getNote(number: String): String? {
        val key = keyFor(number)
        if (key.isBlank()) return null
        val dbHelper = helper ?: return null
        return try {
            dbHelper.readableDatabase.query(
                TABLE,
                arrayOf("note"),
                "number_key = ?",
                arrayOf(key),
                null, null, null, "1"
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0)?.takeIf { it.isNotBlank() } else null
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Call note read failed", t)
            null
        }
    }

    /** Latest note per number, keyed by normalized key. */
    fun getNotes(numbers: Collection<String>): Map<String, String> {
        val keys = numbers.map(::keyFor).filter { it.isNotBlank() }.toSet()
        if (keys.isEmpty()) return emptyMap()
        val dbHelper = helper ?: return emptyMap()
        val result = HashMap<String, String>()
        return try {
            val placeholders = keys.joinToString(",") { "?" }
            dbHelper.readableDatabase.query(
                TABLE,
                arrayOf("number_key", "note"),
                "number_key IN ($placeholders)",
                keys.toTypedArray(),
                null, null, null, null
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val note = cursor.getString(1)?.takeIf { it.isNotBlank() } ?: continue
                    result[cursor.getString(0)] = note
                }
            }
            result
        } catch (t: Throwable) {
            Log.w(TAG, "Call note batch read failed", t)
            emptyMap()
        }
    }

    /** Row count, for the Privacy centre. */
    fun count(): Int {
        val dbHelper = helper ?: return 0
        return try {
            dbHelper.readableDatabase.query(TABLE, arrayOf("number_key"), null, null, null, null, null, null)
                .use { it.count }
        } catch (_: Throwable) {
            0
        }
    }

    /** Deletes every saved call note. */
    fun clearAll() {
        val dbHelper = helper ?: return
        try {
            dbHelper.writableDatabase.delete(TABLE, null, null)
        } catch (_: Throwable) {
        }
    }

    private class Helper(context: Context) :
        SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE $TABLE (" +
                    "number_key TEXT PRIMARY KEY, " +
                    "display_number TEXT NOT NULL, " +
                    "note TEXT NOT NULL, " +
                    "updated_at INTEGER NOT NULL)"
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }
}
