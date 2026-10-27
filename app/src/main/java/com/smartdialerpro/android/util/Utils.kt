package com.smartdialerpro.android.util

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.Settings
import android.telecom.TelecomManager
import android.telephony.PhoneNumberUtils
import android.util.Log
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object Utils {
    private const val TAG = "SmartDialerUtils"
    private const val DIALER_PREFS = "dialer_preferences"
    private const val SELECTED_ACCOUNT_ID = "selected_phone_account_id"

    private val avatarMemoryCache = object : LruCache<String, ImageBitmap>(120) {}

    suspend fun loadCachedAvatar(context: Context, uriString: String): ImageBitmap? {
        if (uriString.isBlank()) return null
        synchronized(avatarMemoryCache) {
            avatarMemoryCache.get(uriString)?.let { return it }
        }
        return withContext(Dispatchers.IO) {
            try {
                context.contentResolver.openInputStream(Uri.parse(uriString))?.use { stream ->
                    BitmapFactory.decodeStream(stream)?.asImageBitmap()?.also { bitmap ->
                        synchronized(avatarMemoryCache) {
                            avatarMemoryCache.put(uriString, bitmap)
                        }
                    }
                }
            } catch (_: Throwable) {
                null
            }
        }
    }

    /**
     * Extracts a clean single-character initial for Avatars.
     * Prevents '+' from showing for international numbers (+91, +92, +1, +44, etc.)
     * and instead extracts the first digit of the actual subscriber number.
     */
    fun getAvatarInitial(nameOrNumber: String): String {
        val trimmed = nameOrNumber.trim()
        if (trimmed.isEmpty()) return "#"

        // 1. If it contains alphabet letters, pick the first actual letter (e.g. "Zohra" -> "Z", ". Chachi" -> "C")
        val firstLetter = trimmed.firstOrNull { it.isLetter() }
        if (firstLetter != null) {
            return firstLetter.uppercase()
        }

        // 2. If it is an international phone number starting with '+'
        if (trimmed.startsWith("+")) {
            val digits = trimmed.removePrefix("+").filter { it.isDigit() }
            if (digits.isEmpty()) return "#"

            // Common 3-digit country codes
            val threeDigitCCs = setOf(
                "880", "971", "966", "977", "974", "968", "965", "972",
                "962", "961", "964", "963", "967", "852", "853", "886", "355", "374"
            )
            // Common 2-digit country codes (e.g. 91 for India, 92 for Pakistan, 44 for UK)
            val twoDigitCCs = setOf(
                "91", "92", "93", "94", "95", "98", "44", "61", "81", "49",
                "33", "39", "86", "65", "60", "62", "20", "27", "31", "32",
                "34", "36", "40", "41", "43", "45", "46", "47", "48", "51",
                "52", "54", "55", "56", "57", "58", "63", "64", "66", "82", "84", "90"
            )
            // 1-digit country codes (e.g. 1 for US/Canada, 7 for Russia)
            val oneDigitCCs = setOf("1", "7")

            if (digits.length > 3 && threeDigitCCs.contains(digits.take(3))) {
                return digits.drop(3).firstOrNull()?.toString() ?: digits.first().toString()
            }
            if (digits.length > 2 && twoDigitCCs.contains(digits.take(2))) {
                return digits.drop(2).firstOrNull()?.toString() ?: digits.first().toString()
            }
            if (digits.length > 1 && oneDigitCCs.contains(digits.take(1))) {
                return digits.drop(1).firstOrNull()?.toString() ?: digits.first().toString()
            }

            // Fallback: If length is >= 12, assume 2-digit CC and drop it
            if (digits.length >= 12) {
                return digits.drop(2).firstOrNull()?.toString() ?: digits.first().toString()
            }
            return digits.firstOrNull()?.toString() ?: "#"
        }

        // 3. Number starting with trunk prefix '0' (e.g. 09876543210)
        if (trimmed.startsWith("0") && trimmed.length > 10) {
            val nonZero = trimmed.dropWhile { it == '0' }.firstOrNull { it.isDigit() }
            if (nonZero != null) return nonZero.toString()
        }

        // 4. Regular number without country code
        val firstDigit = trimmed.firstOrNull { it.isDigit() }
        if (firstDigit != null) {
            return firstDigit.toString()
        }

        return trimmed.firstOrNull()?.uppercase() ?: "#"
    }

    fun openAppSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
        }
        context.startActivity(intent)
    }

    fun makeCall(context: Context, number: String): Boolean {
        val cleaned = number.trim()
        if (cleaned.isBlank()) {
            Log.w(TAG, "Call rejected: blank number")
            return false
        }
        val isUssd = cleaned.contains("#") || (cleaned.startsWith("*") && cleaned.endsWith("#"))
        if (isUssd || ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            return openDialer(context, cleaned)
        }

        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:${Uri.encode(cleaned)}")).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        resolveSelectedPhoneAccount(context)?.let { handle ->
            intent.putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle)
        }

        return try {
            context.startActivity(intent)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "Direct call failed; opening dialer instead", t)
            openDialer(context, cleaned)
        }
    }

    private fun openDialer(context: Context, number: String): Boolean = try {
        context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(number)}")).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        })
        true
    } catch (t: Throwable) {
        Log.e(TAG, "Unable to open dialer", t)
        false
    }

    private fun resolveSelectedPhoneAccount(context: Context): android.telecom.PhoneAccountHandle? {
        val savedId = context.getSharedPreferences(DIALER_PREFS, Context.MODE_PRIVATE)
            .getString(SELECTED_ACCOUNT_ID, null)?.takeIf { it.isNotBlank() } ?: return null
        return try {
            val telecom = context.getSystemService(TelecomManager::class.java) ?: return null
            telecom.callCapablePhoneAccounts.firstOrNull { it.id == savedId }
        } catch (t: Throwable) {
            Log.w(TAG, "Selected PhoneAccount is unavailable", t)
            null
        }
    }

    fun normalizePhoneNumber(number: String): String = PhoneNumberUtils.normalizeNumber(number)

    @Suppress("DEPRECATION")
    fun isSameNumber(first: String, second: String): Boolean {
        val a = normalizePhoneNumber(first)
        val b = normalizePhoneNumber(second)
        if (a.isBlank() || b.isBlank()) return false
        if (PhoneNumberUtils.compare(a, b)) return true
        return a.length >= 7 && b.length >= 7 && a.takeLast(7) == b.takeLast(7)
    }

    fun formatDuration(seconds: Long): String {
        val safe = seconds.coerceAtLeast(0L)
        return if (safe >= 3600) {
            String.format(Locale.getDefault(), "%d:%02d:%02d", safe / 3600, (safe % 3600) / 60, safe % 60)
        } else {
            String.format(Locale.getDefault(), "%02d:%02d", safe / 60, safe % 60)
        }
    }

    fun formatCallDate(timeInMillis: Long): String {
        if (timeInMillis <= 0L) return ""
        val callTime = Calendar.getInstance().apply { this.timeInMillis = timeInMillis }
        val now = Calendar.getInstance()
        return when {
            isSameDay(callTime, now) -> SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(timeInMillis))
            isYesterday(callTime, now) -> "Yesterday"
            callTime.get(Calendar.YEAR) == now.get(Calendar.YEAR) -> SimpleDateFormat("dd MMM", Locale.getDefault()).format(Date(timeInMillis))
            else -> SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(timeInMillis))
        }
    }

    private fun isSameDay(cal1: Calendar, cal2: Calendar): Boolean =
        cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
            cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR)

    private fun isYesterday(cal1: Calendar, cal2: Calendar): Boolean {
        val yesterday = cal2.clone() as Calendar
        yesterday.add(Calendar.DAY_OF_YEAR, -1)
        return isSameDay(cal1, yesterday)
    }
}
