package com.smartdialerpro.android.ui.screens.settings

import android.content.Context
import android.provider.MediaStore
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.smartdialerpro.android.data.CallNotesStore
import com.smartdialerpro.android.data.CnapStore
import com.smartdialerpro.android.ui.components.CompactTopBar
import com.smartdialerpro.android.ui.navigation.Screen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Privacy centre: what the app stores on this phone, with a way to
 * clear each store. The app has no INTERNET permission, so none of
 * this data ever leaves the device.
 */
@Composable
fun PrivacyCentreScreen(navController: NavController? = null) {
    val context = LocalContext.current
    var cnapCount by remember { mutableStateOf(0) }
    var notesCount by remember { mutableStateOf(0) }
    var recordingsCount by remember { mutableStateOf(0) }
    var confirmClear by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val counts = withContext(Dispatchers.IO) {
            CnapStore.ensureInit(context)
            CallNotesStore.ensureInit(context)
            Triple(CnapStore.count(), CallNotesStore.count(), countRecordings(context))
        }
        cnapCount = counts.first
        notesCount = counts.second
        recordingsCount = counts.third
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        CompactTopBar(title = "Privacy centre", onBack = { navController?.popBackStack() })
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.Security,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Text(
                    "Your data stays on this phone",
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "SmartDialer has no internet permission and no account. Call logs live in the system call log, and the caches below live only in this app's private storage.",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 21.sp
            )
            Spacer(Modifier.height(24.dp))

            PrivacyStoreRow(
                title = "CNAP name cache",
                subtitle = "$cnapCount network-delivered caller names saved for unknown numbers.",
                buttonLabel = "Clear cache",
                onClear = { confirmClear = "cnap" }
            )
            Spacer(Modifier.height(12.dp))
            PrivacyStoreRow(
                title = "Call notes",
                subtitle = "$notesCount notes attached to numbers in your call log.",
                buttonLabel = "Clear notes",
                onClear = { confirmClear = "notes" }
            )
            Spacer(Modifier.height(12.dp))
            PrivacyStoreRow(
                title = "Call recordings",
                subtitle = "$recordingsCount recordings in Music/SmartDialer/Calls. Deleting happens in the Recordings screen.",
                buttonLabel = "View recordings",
                onClear = { navController?.navigate(Screen.Recordings.route) }
            )
            Spacer(Modifier.height(24.dp))
            Text(
                "Clearing the CNAP cache only removes saved names — future calls will save them again. Clearing notes removes the note text from call rows.",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 19.sp
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmClear != null) {
        val what = if (confirmClear == "cnap") "CNAP name cache" else "all call notes"
        AlertDialog(
            onDismissRequest = { confirmClear = null },
            title = { Text("Clear $what?") },
            text = { Text("This cannot be undone.") },
            confirmButton = {
                Button(onClick = {
                    if (confirmClear == "cnap") {
                        CnapStore.clearAll()
                        cnapCount = 0
                    } else {
                        CallNotesStore.clearAll()
                        notesCount = 0
                    }
                    confirmClear = null
                }) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun PrivacyStoreRow(
    title: String,
    subtitle: String,
    buttonLabel: String,
    onClear: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .55f), RoundedCornerShape(18.dp))
            .padding(16.dp)
    ) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Icon(
                Icons.Rounded.DeleteSweep,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(end = 8.dp)
            )
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        }
        Spacer(Modifier.height(6.dp))
        Text(subtitle, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 19.sp)
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onClear, shape = RoundedCornerShape(12.dp)) {
            Text(buttonLabel)
        }
    }
}

private fun countRecordings(context: Context): Int {
    return try {
        var count = 0
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Audio.Media._ID),
            "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?",
            arrayOf("%SmartDialer/Calls%"),
            null
        )?.use { cursor ->
            count = cursor.count
        }
        count
    } catch (_: Throwable) {
        0
    }
}
