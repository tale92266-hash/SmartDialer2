package com.smartdialerpro.android.ui.screens.settings

import android.content.Context
import com.smartdialerpro.android.repository.ContactsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Local contacts backup/restore: one JSON file, no account, no
 * server. Export groups the phone rows ContactsRepository returns
 * (one row per number) back into per-contact entries. Import skips
 * any number that already exists, so restoring twice is harmless.
 */
object BackupRestore {

    suspend fun exportJson(context: Context): String = withContext(Dispatchers.IO) {
        val repo = ContactsRepository(context)
        val contacts = repo.getContacts(force = true)
        val grouped = LinkedHashMap<String, MutableList<String>>()
        val names = HashMap<String, String>()
        for (contact in contacts) {
            if (contact.number.isBlank()) continue
            grouped.getOrPut(contact.id) { mutableListOf() }.add(contact.number)
            names[contact.id] = contact.name
        }
        val array = JSONArray()
        for ((id, numbers) in grouped) {
            val entry = JSONObject()
            entry.put("name", names[id].orEmpty())
            val nums = JSONArray()
            for (number in numbers.distinct()) nums.put(number)
            entry.put("numbers", nums)
            array.put(entry)
        }
        JSONObject().put("contacts", array).toString()
    }

    /**
     * Returns (imported, skipped): imported counts contacts created,
     * skipped counts entries whose numbers all already existed.
     */
    suspend fun importJson(context: Context, json: String): Pair<Int, Int> =
        withContext(Dispatchers.IO) {
            val repo = ContactsRepository(context)
            val existing = repo.getContacts(force = true)
                .map { it.number.filter(Char::isDigit).takeLast(10) }
                .filter { it.isNotBlank() }
                .toMutableSet()
            var imported = 0
            var skipped = 0
            try {
                val array = JSONObject(json).optJSONArray("contacts") ?: JSONArray()
                for (i in 0 until array.length()) {
                    val entry = array.optJSONObject(i) ?: continue
                    val name = entry.optString("name").trim()
                    val numbers = mutableListOf<String>()
                    val nums = entry.optJSONArray("numbers")
                    if (nums != null) {
                        for (j in 0 until nums.length()) {
                            nums.optString(j)?.takeIf { it.isNotBlank() }?.let { numbers.add(it) }
                        }
                    }
                    if (name.isBlank() || numbers.isEmpty()) {
                        skipped++
                        continue
                    }
                    val fresh = numbers.filter { number ->
                        val key = number.filter(Char::isDigit).takeLast(10)
                        key.isBlank() || key !in existing
                    }.distinct()
                    if (fresh.isEmpty()) {
                        skipped++
                        continue
                    }
                    if (repo.createContact(name, fresh, emptyList())) {
                        imported++
                        fresh.forEach { existing.add(it.filter(Char::isDigit).takeLast(10)) }
                    } else skipped++
                }
            } catch (_: Throwable) {
                // Malformed file: report what was imported before the error.
            }
            imported to skipped
        }
}
