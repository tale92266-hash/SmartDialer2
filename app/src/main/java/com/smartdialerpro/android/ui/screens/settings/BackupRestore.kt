package com.smartdialerpro.android.ui.screens.settings

import android.content.Context
import android.util.Base64
import com.smartdialerpro.android.repository.ContactsRepository
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
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

    // ---- Encrypted backups (AES/GCM, password-derived key, no dependency) ----

    /** True when the file text is an encrypted backup envelope. */
    fun isEncryptedBackup(json: String): Boolean {
        return try {
            JSONObject(json).optBoolean("encrypted", false)
        } catch (_: Throwable) {
            false
        }
    }

    /** Encrypts the plain backup JSON with the password. Output is a JSON envelope. */
    suspend fun exportEncryptedJson(context: Context, password: String): String =
        withContext(Dispatchers.IO) {
            val plain = exportJson(context)
            val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val key = deriveKey(password, salt)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
            val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            JSONObject()
                .put("encrypted", true)
                .put("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
                .put("iv", Base64.encodeToString(iv, Base64.NO_WRAP))
                .put("data", Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .toString()
        }

    /** Decrypts an encrypted backup envelope; null when the password is wrong or the file is bad. */
    fun decryptBackup(envelope: String, password: String): String? {
        return try {
            val obj = JSONObject(envelope)
            val salt = Base64.decode(obj.getString("salt"), Base64.NO_WRAP)
            val iv = Base64.decode(obj.getString("iv"), Base64.NO_WRAP)
            val data = Base64.decode(obj.getString("data"), Base64.NO_WRAP)
            val key = deriveKey(password, salt)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            String(cipher.doFinal(data), Charsets.UTF_8)
        } catch (_: Throwable) {
            null
        }
    }

    private fun deriveKey(password: String, salt: ByteArray): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(password.toCharArray(), salt, 65_536, 256)
        return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
    }
}
