package com.smartdialerpro.android.ui.screens.settings

import android.app.role.RoleManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.BlockedNumberContract
import android.provider.ContactsContract
import android.telephony.PhoneNumberUtils
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.smartdialerpro.android.data.RecentCall
import com.smartdialerpro.android.repository.CallLogRepository
import com.smartdialerpro.android.ui.components.CompactTopBar
import com.smartdialerpro.android.util.Utils
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class BlockedEntry(
    val id: Long,
    val number: String,
    val name: String? = null,
    val e164Number: String = ""
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlockedNumbersScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val roleManager = remember { context.getSystemService(Context.ROLE_SERVICE) as? RoleManager }
    var isDefaultDialer by remember {
        mutableStateOf(roleManager?.isRoleHeld(RoleManager.ROLE_DIALER) == true)
    }

    var blockedList by remember { mutableStateOf<List<BlockedEntry>>(emptyList()) }
    var blockedCalls by remember { mutableStateOf<List<RecentCall>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var showAddDialog by remember { mutableStateOf(false) }
    var inputNumber by remember { mutableStateOf("") }
    var unblockingEntry by remember { mutableStateOf<BlockedEntry?>(null) }

    val defaultRoleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        isDefaultDialer = roleManager?.isRoleHeld(RoleManager.ROLE_DIALER) == true
        if (isDefaultDialer) {
            coroutineScope.launch {
                isLoading = true
                blockedList = withContext(Dispatchers.IO) { fetchAllSystemBlockedNumbers(context) }
                blockedCalls = withContext(Dispatchers.IO) { CallLogRepository(context).getBlockedCalls(100) }
                isLoading = false
            }
        }
    }

    fun loadData() {
        coroutineScope.launch {
            isLoading = true
            blockedList = withContext(Dispatchers.IO) { fetchAllSystemBlockedNumbers(context) }
            blockedCalls = withContext(Dispatchers.IO) { CallLogRepository(context).getBlockedCalls(100) }
            isLoading = false
        }
    }

    LaunchedEffect(Unit) {
        loadData()
    }

    Scaffold(
        topBar = {
            CompactTopBar(
                title = "Blocked numbers",
                subtitle = "System-wide blocked callers",
                onBack = onBack
            ) {
                IconButton(onClick = { loadData() }) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "Refresh")
                }
            }
        },
        floatingActionButton = {
            if (isDefaultDialer) {
                ExtendedFloatingActionButton(
                    onClick = { showAddDialog = true },
                    icon = { Icon(Icons.Rounded.Add, null) },
                    text = { Text("Block a number") },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = CircleShape
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (!isDefaultDialer) {
                // When app is not default, explain and offer 1-click promotion
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(76.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Rounded.Security,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(38.dp)
                        )
                    }
                    Spacer(Modifier.height(20.dp))
                    Text(
                        "Default Phone App Required",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Android restricts reading and modifying system-wide blocked numbers (from Google Dialer, Samsung Phone, etc.) to the active default phone app only.",
                        fontSize = 13.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        lineHeight = 20.sp
                    )
                    Spacer(Modifier.height(24.dp))
                    Button(
                        onClick = {
                            try {
                                if (roleManager?.isRoleAvailable(RoleManager.ROLE_DIALER) == true) {
                                    defaultRoleLauncher.launch(roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER))
                                } else {
                                    Toast.makeText(context, "Role not available on this device", Toast.LENGTH_SHORT).show()
                                }
                            } catch (_: Throwable) {
                                Toast.makeText(context, "Unable to request default phone role", Toast.LENGTH_SHORT).show()
                            }
                        },
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text("Set as Default Phone App", fontWeight = FontWeight.SemiBold)
                    }
                }
            } else when {
                isLoading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(strokeWidth = 3.dp)
                    }
                }
                blockedList.isEmpty() && blockedCalls.isEmpty() -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Rounded.Block,
                                contentDescription = null,
                                modifier = Modifier.size(36.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "No blocked numbers found",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Numbers you blocked in Google Phone, system settings, or SmartDialer will show here.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 90.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (blockedList.isNotEmpty()) {
                            item {
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            Icons.Rounded.Info,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(Modifier.width(10.dp))
                                        Text(
                                            text = "${blockedList.size} numbers blocked across Android system. Incoming calls from these will be dropped automatically.",
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            lineHeight = 16.sp
                                        )
                                    }
                                }
                            }
                        }

                        items(blockedList, key = { it.id }) { entry ->
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(42.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            Icons.Rounded.Block,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                    Spacer(Modifier.width(14.dp))
                                    Column(Modifier.weight(1f)) {
                                        if (!entry.name.isNullOrBlank()) {
                                            Text(
                                                text = entry.name,
                                                fontWeight = FontWeight.SemiBold,
                                                fontSize = 16.sp
                                            )
                                            Text(
                                                text = entry.number,
                                                fontSize = 13.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        } else {
                                            Text(
                                                text = entry.number,
                                                fontWeight = FontWeight.SemiBold,
                                                fontSize = 16.sp
                                            )
                                            if (entry.e164Number.isNotBlank() && entry.e164Number != entry.number) {
                                                Text(
                                                    text = entry.e164Number,
                                                    fontSize = 12.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                    IconButton(
                                        onClick = { unblockingEntry = entry },
                                        modifier = Modifier.size(38.dp)
                                    ) {
                                        Icon(
                                            Icons.Rounded.DeleteOutline,
                                            contentDescription = "Unblock",
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        }

                        if (blockedCalls.isNotEmpty()) {
                            item(key = "blocked_calls_header") {
                                Text(
                                    text = "Blocked calls (${blockedCalls.size})",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                    modifier = Modifier.padding(top = 12.dp, bottom = 2.dp)
                                )
                            }
                            items(blockedCalls, key = { "blocked_call_${it.id}" }) { call ->
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(16.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(42.dp)
                                                .clip(CircleShape)
                                                .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                Icons.Rounded.CallMissed,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.error,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                        Spacer(Modifier.width(14.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                text = call.name?.takeIf { it.isNotBlank() } ?: call.number,
                                                fontWeight = FontWeight.SemiBold,
                                                fontSize = 16.sp,
                                                maxLines = 1
                                            )
                                            Text(
                                                text = Utils.formatCallDate(call.date),
                                                fontSize = 13.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = {
                showAddDialog = false
                inputNumber = ""
            },
            title = { Text("Block a number") },
            text = {
                Column {
                    Text(
                        "Calls and texts from this number will be blocked across the whole Android phone.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(14.dp))
                    OutlinedTextField(
                        value = inputNumber,
                        onValueChange = { inputNumber = it },
                        label = { Text("Phone number") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val cleaned = inputNumber.trim()
                        if (cleaned.isNotBlank()) {
                            coroutineScope.launch {
                                val success = withContext(Dispatchers.IO) {
                                    blockSystemNumber(context, cleaned)
                                }
                                if (success) {
                                    Toast.makeText(context, "$cleaned blocked", Toast.LENGTH_SHORT).show()
                                    showAddDialog = false
                                    inputNumber = ""
                                    loadData()
                                } else {
                                    Toast.makeText(context, "Could not block number. Verify phone app permissions.", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    }
                ) {
                    Text("Block")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showAddDialog = false
                        inputNumber = ""
                    }
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    unblockingEntry?.let { entry ->
        AlertDialog(
            onDismissRequest = { unblockingEntry = null },
            title = { Text("Unblock number?") },
            text = {
                Text("Calls and messages from ${entry.name ?: entry.number} will no longer be blocked by your phone.")
            },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    onClick = {
                        coroutineScope.launch {
                            val success = withContext(Dispatchers.IO) {
                                unblockSystemNumber(context, entry.id)
                            }
                            if (success) {
                                Toast.makeText(context, "${entry.name ?: entry.number} unblocked", Toast.LENGTH_SHORT).show()
                                unblockingEntry = null
                                loadData()
                            } else {
                                Toast.makeText(context, "Failed to unblock number", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                ) {
                    Text("Unblock")
                }
            },
            dismissButton = {
                TextButton(onClick = { unblockingEntry = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

fun isNumberBlocked(context: Context, number: String): Boolean {
    return try {
        val uri = BlockedNumberContract.BlockedNumbers.CONTENT_URI
        val projection = arrayOf(
            BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER,
            BlockedNumberContract.BlockedNumbers.COLUMN_E164_NUMBER
        )
        var blocked = false
        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            val numIdx = cursor.getColumnIndex(BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER)
            val e164Idx = cursor.getColumnIndex(BlockedNumberContract.BlockedNumbers.COLUMN_E164_NUMBER)
            while (cursor.moveToNext() && !blocked) {
                val original = if (numIdx >= 0) cursor.getString(numIdx).orEmpty() else ""
                val e164 = if (e164Idx >= 0) cursor.getString(e164Idx).orEmpty() else ""
                blocked = original.isNotBlank() && PhoneNumberUtils.compare(original, number) ||
                    e164.isNotBlank() && PhoneNumberUtils.compare(e164, number)
            }
        }
        blocked
    } catch (t: Throwable) {
        android.util.Log.w("BlockedNumbers", "Unable to check blocked state", t)
        false
    }
}

fun unblockNumberByNumber(context: Context, number: String): Boolean {
    return try {
        val uri = BlockedNumberContract.BlockedNumbers.CONTENT_URI
        val projection = arrayOf(
            BlockedNumberContract.BlockedNumbers.COLUMN_ID,
            BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER,
            BlockedNumberContract.BlockedNumbers.COLUMN_E164_NUMBER
        )
        var targetId: Long? = null
        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            val idIdx = cursor.getColumnIndex(BlockedNumberContract.BlockedNumbers.COLUMN_ID)
            val numIdx = cursor.getColumnIndex(BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER)
            val e164Idx = cursor.getColumnIndex(BlockedNumberContract.BlockedNumbers.COLUMN_E164_NUMBER)
            while (cursor.moveToNext() && targetId == null) {
                val original = if (numIdx >= 0) cursor.getString(numIdx).orEmpty() else ""
                val e164 = if (e164Idx >= 0) cursor.getString(e164Idx).orEmpty() else ""
                if ((original.isNotBlank() && PhoneNumberUtils.compare(original, number)) ||
                    (e164.isNotBlank() && PhoneNumberUtils.compare(e164, number))
                ) {
                    targetId = if (idIdx >= 0) cursor.getLong(idIdx) else null
                }
            }
        }
        if (targetId != null) unblockSystemNumber(context, targetId!!) else false
    } catch (t: Throwable) {
        android.util.Log.e("BlockedNumbers", "Error unblocking number", t)
        false
    }
}

private fun fetchAllSystemBlockedNumbers(context: Context): List<BlockedEntry> {
    val list = mutableListOf<BlockedEntry>()
    try {
        val uri = BlockedNumberContract.BlockedNumbers.CONTENT_URI
        val projection = arrayOf(
            BlockedNumberContract.BlockedNumbers.COLUMN_ID,
            BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER,
            BlockedNumberContract.BlockedNumbers.COLUMN_E164_NUMBER
        )
        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            val idIdx = cursor.getColumnIndex(BlockedNumberContract.BlockedNumbers.COLUMN_ID)
            val numIdx = cursor.getColumnIndex(BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER)
            val e164Idx = cursor.getColumnIndex(BlockedNumberContract.BlockedNumbers.COLUMN_E164_NUMBER)
            while (cursor.moveToNext()) {
                val id = if (idIdx >= 0) cursor.getLong(idIdx) else 0L
                val originalNumber = if (numIdx >= 0) cursor.getString(numIdx).orEmpty() else ""
                val e164 = if (e164Idx >= 0) cursor.getString(e164Idx).orEmpty() else ""
                val effectiveNumber = originalNumber.ifBlank { e164 }
                if (effectiveNumber.isNotBlank()) {
                    val resolvedName = resolveContactName(context, effectiveNumber)
                    list.add(BlockedEntry(id, effectiveNumber, resolvedName, e164))
                }
            }
        }
    } catch (t: Throwable) {
        android.util.Log.w("BlockedNumbers", "Unable to query system blocked numbers", t)
    }
    return list
}

private fun resolveContactName(context: Context, number: String): String? {
    return try {
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (_: Throwable) {
        null
    }
}

fun blockSystemNumber(context: Context, number: String): Boolean {
    return try {
        val values = ContentValues().apply {
            put(BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER, number)
        }
        val uri = context.contentResolver.insert(BlockedNumberContract.BlockedNumbers.CONTENT_URI, values)
        uri != null
    } catch (t: Throwable) {
        android.util.Log.e("BlockedNumbers", "Error blocking system number", t)
        false
    }
}

fun unblockSystemNumber(context: Context, id: Long): Boolean {
    return try {
        val uri = Uri.withAppendedPath(BlockedNumberContract.BlockedNumbers.CONTENT_URI, id.toString())
        val deleted = context.contentResolver.delete(uri, null, null)
        deleted > 0
    } catch (t: Throwable) {
        android.util.Log.e("BlockedNumbers", "Error unblocking system number", t)
        false
    }
}
