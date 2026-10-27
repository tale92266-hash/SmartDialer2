package com.smartdialerpro.android.ui.screens.profile

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Settings
import android.telecom.PhoneAccount
import android.telecom.TelecomManager
import android.telephony.SubscriptionManager
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Message
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.smartdialerpro.android.R
import com.smartdialerpro.android.data.RecentCall
import com.smartdialerpro.android.data.SimInfo
import com.smartdialerpro.android.repository.CallLogRepository
import com.smartdialerpro.android.ui.screens.contacts.ContactAvatar
import com.smartdialerpro.android.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class AccountSource(
    val accountName: String,
    val accountType: String
)

private data class ProfileData(
    val contactId: String?,
    val name: String?,
    val photoUri: String?,
    val favorite: Boolean,
    val accounts: List<AccountSource>
)

@Composable
fun ContactProfileScreen(number: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var profile by remember(number) { mutableStateOf(ProfileData(null, null, null, false, emptyList())) }
    var history by remember(number) { mutableStateOf<List<RecentCall>>(emptyList()) }
    var loading by remember(number) { mutableStateOf(false) }
    var contactsPermission by remember { mutableStateOf(hasPermission(context, Manifest.permission.READ_CONTACTS)) }
    var callLogPermission by remember { mutableStateOf(hasPermission(context, Manifest.permission.READ_CALL_LOG)) }
    val lifecycleOwner = LocalLifecycleOwner.current

    var availableSims by remember { mutableStateOf<List<SimInfo>>(emptyList()) }
    var selectedSim by remember { mutableStateOf<SimInfo?>(null) }
    var userManuallySelectedSim by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }

    val callPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            placeRealCall(context, number, selectedSim)
        }
    }

    val writeContactsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && profile.contactId != null) {
            coroutineScope.launch {
                val newFav = !profile.favorite
                val ok = toggleFavoriteContact(context, profile.contactId!!, profile.favorite)
                if (ok) {
                    profile = profile.copy(favorite = newFav)
                    Toast.makeText(context, if (newFav) "Added to favorites" else "Removed from favorites", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun reload() {
        contactsPermission = hasPermission(context, Manifest.permission.READ_CONTACTS)
        callLogPermission = hasPermission(context, Manifest.permission.READ_CALL_LOG)
    }

    DisposableEffect(lifecycleOwner, number) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) reload()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        availableSims = withContext(Dispatchers.IO) { queryPhoneAccounts(context) }
    }

    LaunchedEffect(number, contactsPermission) {
        if (contactsPermission) {
            profile = withContext(Dispatchers.IO) { findContactAccounts(context, number) }
        } else {
            profile = ProfileData(null, null, null, false, emptyList())
        }
    }

    LaunchedEffect(number, callLogPermission) {
        if (!callLogPermission) {
            history = emptyList()
            return@LaunchedEffect
        }
        loading = true
        history = withContext(Dispatchers.IO) {
            CallLogRepository(context).getRecentCalls(100)
                .filter { Utils.isSameNumber(it.number, number) }
                .take(30)
        }
        loading = false
    }

    LaunchedEffect(history, availableSims) {
        if (!userManuallySelectedSim && availableSims.isNotEmpty()) {
            val lastCallWithSim = history.firstOrNull { it.simSlot != null }
            val matchedSim = if (lastCallWithSim != null && lastCallWithSim.simSlot != null) {
                val targetSlotIndex = lastCallWithSim.simSlot - 1
                availableSims.firstOrNull { it.slotIndex == targetSlotIndex } ?: availableSims.firstOrNull()
            } else {
                availableSims.firstOrNull()
            }
            selectedSim = matchedSim
        }
    }

    val isSavedContact = profile.contactId != null || !profile.name.isNullOrBlank() || profile.accounts.isNotEmpty()
    val displayName = profile.name?.takeIf { it.isNotBlank() } ?: number
    val listState = rememberLazyListState()

    fun handleFavoriteToggle() {
        if (profile.contactId == null) return
        if (hasPermission(context, Manifest.permission.WRITE_CONTACTS)) {
            coroutineScope.launch {
                val newFav = !profile.favorite
                val ok = toggleFavoriteContact(context, profile.contactId!!, profile.favorite)
                if (ok) {
                    profile = profile.copy(favorite = newFav)
                    Toast.makeText(context, if (newFav) "Added to favorites" else "Removed from favorites", Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            writeContactsLauncher.launch(Manifest.permission.WRITE_CONTACTS)
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, top = 10.dp, end = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, "Back") }
            Text(
                text = "Contact Info",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )

            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = "Options")
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                    modifier = Modifier.background(MaterialTheme.colorScheme.surface)
                ) {
                    if (isSavedContact) {
                        DropdownMenuItem(
                            text = { Text("Edit contact") },
                            leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                            onClick = {
                                menuExpanded = false
                                openEditContact(context, profile.contactId)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(if (profile.favorite) "Remove from favorites" else "Mark as favorite") },
                            leadingIcon = { Icon(if (profile.favorite) Icons.Rounded.StarBorder else Icons.Rounded.Star, null) },
                            onClick = {
                                menuExpanded = false
                                handleFavoriteToggle()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Share contact") },
                            leadingIcon = { Icon(Icons.Rounded.Share, null) },
                            onClick = {
                                menuExpanded = false
                                shareContact(context, profile.name, number)
                            }
                        )
                    } else {
                        DropdownMenuItem(
                            text = { Text("Create new contact") },
                            leadingIcon = { Icon(Icons.Rounded.PersonAdd, null) },
                            onClick = {
                                menuExpanded = false
                                createNewContact(context, number)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Add to existing contact") },
                            leadingIcon = { Icon(Icons.Rounded.GroupAdd, null) },
                            onClick = {
                                menuExpanded = false
                                addToExistingContact(context, number)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Share number") },
                            leadingIcon = { Icon(Icons.Rounded.Share, null) },
                            onClick = {
                                menuExpanded = false
                                shareContact(context, null, number)
                            }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Copy number") },
                        leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) },
                        onClick = {
                            menuExpanded = false
                            copyToClipboard(context, number)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Block number") },
                        leadingIcon = { Icon(Icons.Rounded.Block, null, tint = MaterialTheme.colorScheme.error) },
                        onClick = {
                            menuExpanded = false
                            openBlockedNumbers(context)
                        }
                    )
                }
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(8.dp))
                    ContactAvatar(displayName, profile.photoUri, 116.dp)
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = displayName,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = number.ifBlank { "Private number" },
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )

                    if (profile.favorite) {
                        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Star, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(17.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Favorite", color = MaterialTheme.colorScheme.primary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }

                    if (availableSims.size > 1) {
                        Spacer(Modifier.height(12.dp))
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
                        ) {
                            Row(
                                modifier = Modifier.padding(3.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                availableSims.forEach { sim ->
                                    val isSelected = selectedSim?.accountHandle?.id == sim.accountHandle?.id
                                    val slotTitle = if (sim.slotIndex >= 0) "SIM ${sim.slotIndex + 1}" else "SIM"
                                    val rawCarrier = (sim.displayName ?: sim.carrierName).trim()
                                    val carrier = if (
                                        rawCarrier.isNotBlank() &&
                                        !rawCarrier.equals("Phone account", ignoreCase = true) &&
                                        !rawCarrier.equals(slotTitle, ignoreCase = true)
                                    ) rawCarrier else null

                                    val simLabel = if (carrier != null) "$slotTitle • $carrier" else slotTitle

                                    val bgAnimated by animateColorAsState(
                                        if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                                        label = "profileSimBg"
                                    )
                                    val contentColorAnimated by animateColorAsState(
                                        if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        label = "profileSimContent"
                                    )

                                    Surface(
                                        shape = RoundedCornerShape(50),
                                        color = bgAnimated,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(50))
                                            .clickable {
                                                userManuallySelectedSim = true
                                                selectedSim = sim
                                            }
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.SimCard,
                                                contentDescription = null,
                                                tint = contentColorAnimated,
                                                modifier = Modifier.size(14.dp)
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                text = simLabel,
                                                fontSize = 11.5.sp,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                                color = contentColorAnimated
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val callLabel = selectedSim?.let { sim ->
                        if (availableSims.size > 1 && sim.slotIndex >= 0) "Call (SIM ${sim.slotIndex + 1})" else "Call"
                    } ?: "Call"

                    ProfileAction(
                        icon = Icons.Rounded.Call,
                        label = callLabel,
                        modifier = Modifier.weight(1f)
                    ) {
                        if (hasPermission(context, Manifest.permission.CALL_PHONE)) {
                            placeRealCall(context, number, selectedSim)
                        } else {
                            callPermissionLauncher.launch(Manifest.permission.CALL_PHONE)
                        }
                    }
                    ProfileAction(Icons.AutoMirrored.Rounded.Message, "Message", Modifier.weight(1f)) { openSms(context, number) }
                    ProfileAction(ImageVector.vectorResource(R.drawable.ic_whatsapp), "WhatsApp", Modifier.weight(1f), Color(0xFF25D366)) { openWhatsApp(context, number) }
                }
            }

            item {
                AccountsInfoCard(profile.accounts)
            }

            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.History, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Call history", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    if (history.isNotEmpty()) Text("${history.size}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            if (loading) {
                item { Box(Modifier.fillMaxWidth().padding(vertical = 28.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp) } }
            } else if (!callLogPermission) {
                item {
                    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .5f))) {
                        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Rounded.Lock, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(34.dp))
                            Spacer(Modifier.height(8.dp))
                            Text("Call history access required", fontWeight = FontWeight.SemiBold)
                            Text("Grant call-log permission to view history for this number.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(8.dp))
                            TextButton(onClick = { Utils.openAppSettings(context) }) { Text("Open settings") }
                        }
                    }
                }
            } else if (history.isEmpty()) {
                item {
                    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .5f))) {
                        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Rounded.History, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(34.dp))
                            Spacer(Modifier.height(8.dp))
                            Text("No call history", fontWeight = FontWeight.SemiBold)
                            Text("Calls with this number will appear here.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            } else {
                items(history, key = { it.id }) { call -> ProfileCallHistoryRow(call) }
            }
        }
    }
}

private fun hasPermission(context: Context, permission: String) =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

@Composable
private fun ProfileAction(icon: ImageVector, label: String, modifier: Modifier, tint: Color = MaterialTheme.colorScheme.primary, onClick: () -> Unit) {
    Surface(
        modifier = modifier.clip(RoundedCornerShape(18.dp)).clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .58f),
        tonalElevation = 1.dp
    ) {
        Column(Modifier.padding(vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, label, tint = tint, modifier = Modifier.size(28.dp))
            Spacer(Modifier.height(7.dp))
            Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun AccountsInfoCard(accounts: List<AccountSource>) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .48f))) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.AccountCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Saved Accounts", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Text(
                        if (accounts.isEmpty()) "Not saved in contacts" else "Saved across ${accounts.size} account(s)",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (accounts.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Divider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                Spacer(Modifier.height(10.dp))

                accounts.forEach { acc ->
                    val providerName = formatAccountType(acc.accountType)
                    val emailOrName = acc.accountName.ifBlank { "Device Storage" }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = when {
                                acc.accountType.contains("google", true) -> Icons.Rounded.Cloud
                                acc.accountType.contains("whatsapp", true) -> Icons.Rounded.Chat
                                else -> Icons.Rounded.Phone
                            },
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(emailOrName, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(providerName, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

private fun formatAccountType(type: String): String {
    return when {
        type.contains("google", true) -> "Google Account"
        type.contains("whatsapp", true) -> "WhatsApp Contact"
        type.contains("sim", true) -> "SIM Card Contact"
        type.contains("device", true) || type.isBlank() -> "Phone Device Storage"
        else -> type.substringAfterLast('.').replaceFirstChar { it.uppercase() } + " Account"
    }
}

@Composable
private fun ProfileCallHistoryRow(call: RecentCall) {
    val isMissed = call.type == CallLog.Calls.MISSED_TYPE
    val isIncoming = call.type == CallLog.Calls.INCOMING_TYPE
    val icon = if (isMissed || isIncoming) Icons.Rounded.CallReceived else Icons.Rounded.CallMade
    val title = when (call.type) {
        CallLog.Calls.MISSED_TYPE -> "Missed call"
        CallLog.Calls.INCOMING_TYPE -> "Incoming call"
        CallLog.Calls.OUTGOING_TYPE -> "Outgoing call"
        else -> "Call"
    }
    val date = remember(call.date) { SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()).format(Date(call.date)) }
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .38f))) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).clip(CircleShape).background(if (isMissed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = if (isMissed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, modifier = Modifier.size(21.dp))
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) { Text(title, fontWeight = FontWeight.SemiBold); Text(date, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Column(horizontalAlignment = Alignment.End) {
                Text(if (call.duration > 0) formatDuration(call.duration) else "—", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                call.simSlot?.let { Text("SIM $it", fontSize = 10.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

private suspend fun findContactAccounts(context: Context, number: String): ProfileData = withContext(Dispatchers.IO) {
    if (number.isBlank()) return@withContext ProfileData(null, null, null, false, emptyList())
    try {
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        val projection = arrayOf(
            ContactsContract.PhoneLookup.DISPLAY_NAME,
            ContactsContract.PhoneLookup.PHOTO_URI,
            ContactsContract.PhoneLookup.STARRED,
            ContactsContract.PhoneLookup.CONTACT_ID
        )

        var foundName: String? = null
        var foundPhoto: String? = null
        var isFavorite = false
        val contactIds = HashSet<String>()

        context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                if (foundName == null) foundName = c.getString(0)
                if (foundPhoto == null) foundPhoto = c.getString(1)
                if (c.getInt(2) == 1) isFavorite = true
                val cid = c.getString(3)
                if (!cid.isNullOrBlank()) contactIds.add(cid)
            }
        }

        val accountsList = ArrayList<AccountSource>()
        for (cid in contactIds) {
            val rawProjection = arrayOf(ContactsContract.RawContacts.ACCOUNT_NAME, ContactsContract.RawContacts.ACCOUNT_TYPE)
            context.contentResolver.query(
                ContactsContract.RawContacts.CONTENT_URI,
                rawProjection,
                "${ContactsContract.RawContacts.CONTACT_ID}=?",
                arrayOf(cid),
                null
            )?.use { rc ->
                while (rc.moveToNext()) {
                    val name = rc.getString(0).orEmpty()
                    val type = rc.getString(1).orEmpty()
                    accountsList.add(AccountSource(name, type))
                }
            }
        }

        ProfileData(
            contactId = contactIds.firstOrNull(),
            name = foundName,
            photoUri = foundPhoto,
            favorite = isFavorite,
            accounts = accountsList.distinctBy { it.accountName + it.accountType }
        )
    } catch (t: Throwable) {
        Log.w("ContactProfile", "Unable to resolve contact accounts for $number", t)
        ProfileData(null, null, null, false, emptyList())
    }
}

private suspend fun toggleFavoriteContact(context: Context, contactId: String, currentFavorite: Boolean): Boolean = withContext(Dispatchers.IO) {
    try {
        val values = ContentValues().apply {
            put(ContactsContract.Contacts.STARRED, if (currentFavorite) 0 else 1)
        }
        context.contentResolver.update(
            ContactsContract.Contacts.CONTENT_URI,
            values,
            "${ContactsContract.Contacts._ID}=?",
            arrayOf(contactId)
        ) > 0
    } catch (t: Throwable) {
        Log.w("ContactProfile", "Failed to update favorite status", t)
        false
    }
}

private fun queryPhoneAccounts(context: Context): List<SimInfo> {
    val telecom = context.getSystemService(TelecomManager::class.java) ?: return emptyList()
    val subscriptionManager = context.getSystemService(SubscriptionManager::class.java)
    val subscriptions = try {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
            subscriptionManager?.activeSubscriptionInfoList.orEmpty()
        } else emptyList()
    } catch (_: Throwable) {
        emptyList()
    }
    val accounts = try { telecom.callCapablePhoneAccounts } catch (_: Throwable) { emptyList() }

    return accounts.mapNotNull { handle ->
        val account = try { telecom.getPhoneAccount(handle) } catch (_: Throwable) { null } ?: return@mapNotNull null
        val subscription = subscriptions.firstOrNull { sub ->
            handle.id == sub.subscriptionId.toString() ||
                handle.id.contains(sub.subscriptionId.toString(), ignoreCase = true) ||
                account.address?.schemeSpecificPart == sub.number
        }
        val slot = subscription?.simSlotIndex ?: inferSlotFromAccount(account)
        val carrier = account.label?.toString()?.takeIf { it.isNotBlank() }
            ?: subscription?.carrierName?.toString()?.takeIf { it.isNotBlank() }
            ?: "Phone account"
        val displayName = account.label?.toString()?.takeIf { it.isNotBlank() } ?: carrier
        val number = account.address?.schemeSpecificPart ?: subscription?.number
        SimInfo(slot, carrier, handle, subscription?.subscriptionId, displayName, number, subscription != null)
    }.distinctBy { it.accountHandle?.id ?: "" }
        .sortedWith(compareBy<SimInfo> { if (it.slotIndex >= 0) it.slotIndex else Int.MAX_VALUE }.thenBy { it.displayName ?: it.carrierName })
}

private fun inferSlotFromAccount(account: PhoneAccount): Int {
    val explicit = account.extras?.getInt("slot", -1) ?: -1
    if (explicit >= 0) return explicit
    val extrasSlot = account.extras?.getInt("slot_id", -1) ?: -1
    if (extrasSlot >= 0) return extrasSlot
    return -1
}

private fun placeRealCall(context: Context, number: String, selectedSim: SimInfo?) {
    val cleanNumber = number.filter { it.isDigit() || it == '+' || it == '*' || it == '#' || it == ',' || it == ';' }
    if (cleanNumber.isBlank()) return
    try {
        val isUssd = cleanNumber.contains("#") || (cleanNumber.startsWith("*") && cleanNumber.endsWith("#"))
        val action = if (isUssd) Intent.ACTION_DIAL else Intent.ACTION_CALL
        val intent = Intent(action, Uri.parse("tel:${Uri.encode(cleanNumber)}")).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            if (!isUssd) {
                selectedSim?.accountHandle?.let { putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, it) }
            }
        }
        context.startActivity(intent)
    } catch (_: Throwable) {
        try {
            context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(cleanNumber)}")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
        } catch (_: Throwable) { }
    }
}

private fun formatDuration(seconds: Long): String = String.format(Locale.getDefault(), "%02d:%02d", seconds / 60, seconds % 60)

private fun openSms(context: Context, number: String) {
    val clean = number.filter { it.isDigit() || it == '+' }
    if (clean.isBlank()) return
    try { context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$clean"))) }
    catch (_: Throwable) { }
}

private fun openWhatsApp(context: Context, number: String) {
    val clean = number.filter { it.isDigit() || it == '+' }
    if (clean.isBlank()) return
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW).apply { data = Uri.parse("https://api.whatsapp.com/send?phone=$clean"); setPackage("com.whatsapp") })
    } catch (_: Throwable) {
        try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://api.whatsapp.com/send?phone=$clean"))) }
        catch (_: Throwable) { }
    }
}

private fun createNewContact(context: Context, number: String) {
    try {
        val intent = Intent(Intent.ACTION_INSERT).apply {
            type = ContactsContract.RawContacts.CONTENT_TYPE
            putExtra(ContactsContract.Intents.Insert.PHONE, number)
        }
        context.startActivity(intent)
    } catch (_: Throwable) {
        try {
            val fallback = Intent(Intent.ACTION_INSERT_OR_EDIT).apply {
                type = ContactsContract.Contacts.CONTENT_ITEM_TYPE
                putExtra(ContactsContract.Intents.Insert.PHONE, number)
            }
            context.startActivity(fallback)
        } catch (_: Throwable) {
            Toast.makeText(context, "Unable to launch contact editor", Toast.LENGTH_SHORT).show()
        }
    }
}

private fun addToExistingContact(context: Context, number: String) {
    try {
        val intent = Intent(Intent.ACTION_INSERT_OR_EDIT).apply {
            type = ContactsContract.Contacts.CONTENT_ITEM_TYPE
            putExtra(ContactsContract.Intents.Insert.PHONE, number)
        }
        context.startActivity(intent)
    } catch (_: Throwable) {
        Toast.makeText(context, "Unable to open contacts to link", Toast.LENGTH_SHORT).show()
    }
}

private fun openEditContact(context: Context, contactId: String?) {
    if (contactId.isNullOrBlank()) {
        Toast.makeText(context, "Contact record not found", Toast.LENGTH_SHORT).show()
        return
    }
    try {
        val uri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_URI, contactId)
        val intent = Intent(Intent.ACTION_EDIT).apply {
            setDataAndType(uri, ContactsContract.Contacts.CONTENT_ITEM_TYPE)
        }
        context.startActivity(intent)
    } catch (_: Throwable) {
        Toast.makeText(context, "Unable to edit contact", Toast.LENGTH_SHORT).show()
    }
}

private fun copyToClipboard(context: Context, number: String) {
    try {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = ClipData.newPlainText("Phone Number", number)
        clipboard?.setPrimaryClip(clip)
        Toast.makeText(context, "Number copied to clipboard", Toast.LENGTH_SHORT).show()
    } catch (_: Throwable) {
        Toast.makeText(context, "Unable to copy", Toast.LENGTH_SHORT).show()
    }
}

private fun shareContact(context: Context, name: String?, number: String) {
    try {
        val shareBody = if (!name.isNullOrBlank()) "$name: $number" else number
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, shareBody)
        }
        context.startActivity(Intent.createChooser(intent, "Share contact"))
    } catch (_: Throwable) {
        Toast.makeText(context, "Unable to share contact", Toast.LENGTH_SHORT).show()
    }
}

private fun openBlockedNumbers(context: Context) {
    try {
        context.startActivity(Intent("android.intent.action.BLOCKED_NUMBERS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Throwable) {
        try {
            context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Throwable) {
            Toast.makeText(context, "Unable to open blocked numbers settings", Toast.LENGTH_SHORT).show()
        }
    }
}
