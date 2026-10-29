package com.smartdialerpro.android.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

/**
 * CNAP (Calling Name Presentation) cache: the network-delivered
 * caller name for numbers that are not in the user's contacts.
 * The name arrives with the incoming call via Telecom
 * (Call.Details.callerDisplayName) — no internet, no Truecaller-style
 * server. It is cached here so call logs and search can show it
 * after the call, exactly like a contact name.
 *
 * Keys are normalized to the last 10 digits of the dialled number
 * (Indian numbers, with or without the 91 country code, map to the
 * same entry). The store is a plain SQLiteOpenHelper on purpose:
 * this app has zero external dependencies and Room would add one.
 *
 * Initialized once from SmartDialerApplication with the application
 * context, so it is ready in every process start — including a
 * telecom cold start where MainActivity never runs. Call sites that
 * hold a Context should still call [ensureInit] first as a
 * belt-and-braces guard; every method degrades to a no-op/empty
 * result before init so a missed init can never crash a call.
 */
object CnapStore {
    private const val TAG = "CnapStore"
    private const val DB_NAME = "cnap_names.db"
    private const val DB_VERSION = 1
    private const val TABLE = "cnap_names"

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

    fun putName(number: String, name: String) {
        val key = keyFor(number)
        if (key.isBlank() || name.isBlank()) return
        val dbHelper = helper ?: return
        try {
            val db = dbHelper.writableDatabase
            val values = ContentValues().apply {
                put("number_key", key)
                put("display_number", number)
                put("cnap_name", name.trim())
                put("updated_at", System.currentTimeMillis())
            }
            db.insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE)
        } catch (t: Throwable) {
            Log.w(TAG, "CNAP cache write failed", t)
        }
    }

    fun getName(number: String): String? {
        val key = keyFor(number)
        if (key.isBlank()) return null
        val dbHelper = helper ?: return null
        return try {
            dbHelper.readableDatabase.query(
                TABLE,
                arrayOf("cnap_name"),
                "number_key = ?",
                arrayOf(key),
                null, null, null, "1"
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0)?.takeIf { it.isNotBlank() } else null
            }
        } catch (t: Throwable) {
            Log.w(TAG, "CNAP cache read failed", t)
            null
        }
    }

    /** Names for a batch of numbers, keyed by normalized key. */
    fun getNames(numbers: Collection<String>): Map<String, String> {
        val keys = numbers.map(::keyFor).filter { it.isNotBlank() }.toSet()
        if (keys.isEmpty()) return emptyMap()
        val dbHelper = helper ?: return emptyMap()
        val result = HashMap<String, String>()
        return try {
            val placeholders = keys.joinToString(",") { "?" }
            dbHelper.readableDatabase.query(
                TABLE,
                arrayOf("number_key", "cnap_name"),
                "number_key IN ($placeholders)",
                keys.toTypedArray(),
                null, null, null, null
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val name = cursor.getString(1)?.takeIf { it.isNotBlank() } ?: continue
                    result[cursor.getString(0)] = name
                }
            }
            result
        } catch (t: Throwable) {
            Log.w(TAG, "CNAP cache batch read failed", t)
            emptyMap()
        }
    }

    data class CnapEntry(val number: String, val name: String)

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

    /** Deletes every cached CNAP name. */
    fun clearAll() {
        val dbHelper = helper ?: return
        try {
            dbHelper.writableDatabase.delete(TABLE, null, null)
        } catch (_: Throwable) {
        }
    }

    /** All cached entries, newest first — used to build the search index. */
    fun allEntries(): List<CnapEntry> {
        val dbHelper = helper ?: return emptyList()
        return try {
            val entries = ArrayList<CnapEntry>()
            dbHelper.readableDatabase.query(
                TABLE,
                arrayOf("display_number", "cnap_name"),
                null, null, null, null,
                "updated_at DESC",
                "500"
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val number = cursor.getString(0).orEmpty()
                    val name = cursor.getString(1)?.takeIf { it.isNotBlank() } ?: continue
                    if (number.isNotBlank()) entries.add(CnapEntry(number, name))
                }
            }
            entries
        } catch (t: Throwable) {
            Log.w(TAG, "CNAP cache list failed", t)
            emptyList()
        }
    }

    private class Helper(context: Context) :
        SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE $TABLE (" +
                    "number_key TEXT PRIMARY KEY, " +
                    "display_number TEXT NOT NULL, " +
                    "cnap_name TEXT NOT NULL, " +
                    "updated_at INTEGER NOT NULL)"
            )
            db.execSQL("CREATE INDEX idx_cnap_name ON $TABLE(cnap_name COLLATE NOCASE)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }
}
