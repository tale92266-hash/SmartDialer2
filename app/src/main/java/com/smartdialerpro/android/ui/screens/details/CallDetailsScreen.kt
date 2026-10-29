package com.smartdialerpro.android.ui.screens.details

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CallLog
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Message
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.smartdialerpro.android.R
import com.smartdialerpro.android.data.RecentCall
import com.smartdialerpro.android.ui.components.CompactTopBar
import com.smartdialerpro.android.ui.components.SimSlotIcon
import com.smartdialerpro.android.util.Utils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The call whose details are shown; set by the row before navigating. */
object CallDetailsHolder {
    @Volatile
    var call: RecentCall? = null
}

private data class ExtraRow(
    val dataUsage: Long? = null,
    val location: String? = null,
    val accountId: String? = null,
    val countryIso: String? = null
)

@Composable
fun CallDetailsScreen(navController: NavController) {
    val context = LocalContext.current
    val call = CallDetailsHolder.call
    if (call == null) {
        LaunchedEffect(Unit) { navController.popBackStack() }
        return
    }
    var extra by remember { mutableStateOf(ExtraRow()) }
    LaunchedEffect(call.id) {
        extra = withContext(Dispatchers.IO) { loadExtraRow(context, call) }
    }

    val displayName = call.name?.takeIf { it.isNotBlank() } ?: call.number
    val typeLabel = when (call.type) {
        CallLog.Calls.INCOMING_TYPE -> "Incoming call"
        CallLog.Calls.OUTGOING_TYPE -> "Outgoing call"
        CallLog.Calls.MISSED_TYPE -> "Missed call"
        CallLog.Calls.BLOCKED_TYPE -> "Blocked call"
        CallLog.Calls.VOICEMAIL_TYPE -> "Voicemail"
        CallLog.Calls.REJECTED_TYPE -> "Rejected call"
        else -> "Call"
    }
    val simLabel = remember(call.simSlot) {
        try {
            call.simSlot?.let {
                com.smartdialerpro.android.service.FakeCallSim.simLabelForSlot(context, it - 1)
            }
        } catch (_: Throwable) {
            null
        }
    }
    val note = remember(call.number) {
        try {
            com.smartdialerpro.android.data.CallNotesStore.ensureInit(context)
            com.smartdialerpro.android.data.CallNotesStore.getNote(call.number)
        } catch (_: Throwable) {
            null
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        CompactTopBar(title = "Call details", onBack = { navController.popBackStack() })
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            Spacer(Modifier.height(12.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = Utils.getAvatarInitial(displayName),
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(displayName, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(call.number, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = typeLabel,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (call.type == CallLog.Calls.MISSED_TYPE) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .background(
                                (if (call.type == CallLog.Calls.MISSED_TYPE) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer),
                                androidx.compose.foundation.shape.RoundedCornerShape(50.dp)
                            )
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                    call.simSlot?.let { slot ->
                        SimSlotIcon(slot = slot, size = 13.dp)
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            DetailRow("Date", formatDateTime(call.date))
            DetailRow("Duration", if (call.duration > 0) Utils.formatDuration(call.duration) else "Not answered")
            simLabel?.let { DetailRow("SIM", it) }
            extra.location?.let { DetailRow("Location", it) }
            extra.countryIso?.let { DetailRow("Country", it) }
            extra.dataUsage?.let { DetailRow("Data used", formatBytes(it)) }
            DetailRow("Times called", "${call.callCount}")
            if (call.missedCount > 0) DetailRow("Missed from this number", "${call.missedCount}")
            note?.let { DetailRow("Note", it) }
            extra.accountId?.let {
                DetailRow("Phone account", it.take(16) + if (it.length > 16) "…" else "")
            }

            Spacer(Modifier.height(24.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                DetailAction(Icons.Rounded.Call, "Call") { Utils.makeCall(context, call.number) }
                DetailAction(Icons.AutoMirrored.Rounded.Message, "Message") { openSms(context, call.number) }
                DetailAction(
                    ImageVector.vectorResource(R.drawable.ic_whatsapp),
                    "WhatsApp",
                    tint = Color(0xFF25D366)
                ) { openWhatsApp(context, call.number) }
                DetailAction(Icons.Rounded.ContentCopy, "Copy") { copyNumber(context, call.number) }
                DetailAction(Icons.Rounded.Person, "Profile") {
                    navController.navigate(
                        com.smartdialerpro.android.ui.navigation.Screen.ContactProfile.createRoute(Uri.encode(call.number))
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun DetailAction(
    icon: ImageVector,
    label: String,
    tint: Color = MaterialTheme.colorScheme.primary,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(horizontal = 4.dp)
    ) {
        Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, label, tint = tint, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.4f)
        )
        Text(
            text = value,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(0.6f)
        )
    }
}

private fun formatDateTime(millis: Long): String {
    if (millis <= 0L) return "Unknown"
    val date = Utils.formatCallDate(millis)
    val time = try {
        SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(millis))
    } catch (_: Throwable) {
        ""
    }
    return if (time.isBlank()) date else "$date • $time"
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 KB"
    val kb = bytes / 1024.0
    return if (kb < 1024) "%.1f KB".format(Locale.US, kb) else "%.2f MB".format(Locale.US, kb / 1024.0)
}

private fun loadExtraRow(context: Context, call: RecentCall): ExtraRow {
    return try {
        val rowId = call.id.toLongOrNull()
        val projection = arrayOf(
            CallLog.Calls.DATA_USAGE,
            CallLog.Calls.GEOCODED_LOCATION,
            CallLog.Calls.PHONE_ACCOUNT_ID,
            CallLog.Calls.COUNTRY_ISO
        )
        val (selection, args) = if (rowId != null) {
            "${CallLog.Calls._ID} = ?" to arrayOf(rowId.toString())
        } else {
            "${CallLog.Calls.NUMBER} = ? AND ${CallLog.Calls.DATE} = ?" to arrayOf(call.number, call.date.toString())
        }
        var result = ExtraRow()
        try {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI, projection, selection, args,
                "${CallLog.Calls.DATE} DESC LIMIT 1"
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val usageIdx = cursor.getColumnIndex(CallLog.Calls.DATA_USAGE)
                    val locIdx = cursor.getColumnIndex(CallLog.Calls.GEOCODED_LOCATION)
                    val accIdx = cursor.getColumnIndex(CallLog.Calls.PHONE_ACCOUNT_ID)
                    val isoIdx = cursor.getColumnIndex(CallLog.Calls.COUNTRY_ISO)
                    result = ExtraRow(
                        dataUsage = if (usageIdx >= 0 && !cursor.isNull(usageIdx)) cursor.getLong(usageIdx) else null,
                        location = if (locIdx >= 0) cursor.getString(locIdx)?.takeIf { it.isNotBlank() } else null,
                        accountId = if (accIdx >= 0) cursor.getString(accIdx)?.takeIf { it.isNotBlank() } else null,
                        countryIso = if (isoIdx >= 0) cursor.getString(isoIdx)?.takeIf { it.isNotBlank() } else null
                    )
                }
            }
        } catch (_: Throwable) {
            // Some providers reject the extended projection; the base
            // details above are enough without the extras.
        }
        result
    } catch (_: Throwable) {
        ExtraRow()
    }
}

private fun openSms(context: Context, number: String) {
    try {
        context.startActivity(
            Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(number)}")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        )
    } catch (_: Throwable) {
    }
}

private fun openWhatsApp(context: Context, number: String) {
    try {
        val clean = number.filter { it.isDigit() || it == '+' }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://api.whatsapp.com/send?phone=$clean"))
        val resolved = context.packageManager.queryIntentActivities(intent, 0)
        val wa = resolved.firstOrNull { it.activityInfo.packageName == "com.whatsapp" || it.activityInfo.packageName == "com.whatsapp.w4b" }
        if (wa != null) intent.setPackage(wa.activityInfo.packageName)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        context.startActivity(intent)
    } catch (t: Throwable) {
        Toast.makeText(context, "WhatsApp is not available", Toast.LENGTH_SHORT).show()
    }
}

private fun copyNumber(context: Context, number: String) {
    try {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("Phone Number", number))
        Toast.makeText(context, "Number copied to clipboard", Toast.LENGTH_SHORT).show()
    } catch (_: Throwable) {
    }
}
