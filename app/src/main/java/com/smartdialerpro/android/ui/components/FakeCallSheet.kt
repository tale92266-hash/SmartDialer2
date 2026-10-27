package com.smartdialerpro.android.ui.components

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.telecom.TelecomManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Casino
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.smartdialerpro.android.service.FakeCallDurations

/**
 * The Magic Call picker, matching the original Dialar bottom sheet:
 * a highlighted 2-minute fake call, a genuine-call escape hatch, three
 * random-duration modes and Smart Random. Durations are resolved here
 * (per-day no-repeat pools live in [FakeCallDurations]); the host only
 * starts the fake call or places the real one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FakeCallSheet(
    number: String,
    simSlot: Int,
    onDismiss: () -> Unit,
    onGenuineCall: (String) -> Unit,
    onDirectCall: (number: String, durationSecs: Int, simSlot: Int) -> Unit
) {
    val context = LocalContext.current

    fun directCall(durationSecs: Int) {
        if (!canSaveFakeLogs(context)) {
            Toast.makeText(context, "Please set as Default Dialer first to save call logs!", Toast.LENGTH_LONG).show()
            promptDefaultDialer(context)
            onDismiss()
            return
        }
        onDirectCall(number, durationSecs, simSlot)
        onDismiss()
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(26.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text("Fake Call", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            Text(
                "Choose call mode / duration",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 18.dp)
            )

            // Highlighted quick option: instant start, fixed 120 seconds.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFFFEF3C7))
                    .border(1.dp, Color(0xFFF59E0B), RoundedCornerShape(16.dp))
                    .clickable { directCall(FakeCallDurations.QUICK_TWO_MINUTES) }
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.Bolt, contentDescription = null, tint = Color(0xFFF59E0B), modifier = Modifier.size(26.dp))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("2 Minute Fake Call", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A))
                    Text("Instant start • Fixed 120 seconds", fontSize = 12.sp, color = Color(0xFF64748B))
                }
                Icon(Icons.Rounded.ChevronRight, null, tint = Color(0xFF94A3B8))
            }
            Spacer(Modifier.height(10.dp))

            FakeCallOptionRow(Icons.Rounded.Call, "Genuine Call", "Real call from the default SIM") {
                onGenuineCall(number)
                onDismiss()
            }
            FakeCallOptionRow(Icons.Rounded.Timer, "15 Sec Call", "Random, between 1s - 15s") {
                directCall(FakeCallDurations.smartRandom(context, FakeCallDurations.MODE_SHORT))
            }
            FakeCallOptionRow(Icons.Rounded.Timer, "30 Sec Call", "Random, between 16s - 30s") {
                directCall(FakeCallDurations.smartRandom(context, FakeCallDurations.MODE_MID))
            }
            FakeCallOptionRow(Icons.Rounded.Timer, "45 Sec Call", "Random, between 31s - 46s") {
                directCall(FakeCallDurations.smartRandom(context, FakeCallDurations.MODE_LONG))
            }
            FakeCallOptionRow(Icons.Rounded.Timer, "1 Min 30 Sec", "Random, between 1m 01s - 1m 29s") {
                directCall(FakeCallDurations.smartRandom(context, FakeCallDurations.MODE_XLONG))
            }
            FakeCallOptionRow(Icons.Rounded.Casino, "Smart Random", "Mix & match duration") {
                directCall(FakeCallDurations.ultimateRandom(context))
            }

            TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
            ) {
                Text("Cancel", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun FakeCallOptionRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
    }
    Spacer(Modifier.height(10.dp))
}

/** Fake-call logs need the default-dialer role or WRITE_CALL_LOG. */
private fun canSaveFakeLogs(context: Context): Boolean {
    return try {
        val telecom = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
        val isDefault = telecom?.defaultDialerPackage == context.packageName
        val hasWrite = ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALL_LOG) == PackageManager.PERMISSION_GRANTED
        isDefault || hasWrite
    } catch (_: Throwable) {
        false
    }
}

private fun promptDefaultDialer(context: Context) {
    try {
        val roleManager = context.getSystemService(Context.ROLE_SERVICE) as? RoleManager
        if (roleManager?.isRoleAvailable(RoleManager.ROLE_DIALER) == true) {
            context.startActivity(
                roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    } catch (_: Throwable) {
    }
}
