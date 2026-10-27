package com.smartdialerpro.android.ui.screens.recents

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CallLog
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.automirrored.rounded.Message
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.navigation.NavController
import com.smartdialerpro.android.R
import com.smartdialerpro.android.data.Contact
import com.smartdialerpro.android.data.RecentCall
import com.smartdialerpro.android.ui.components.EmptyStateView
import com.smartdialerpro.android.ui.navigation.Screen
import com.smartdialerpro.android.ui.screens.contacts.ContactAvatar
import com.smartdialerpro.android.util.Utils
import com.smartdialerpro.android.viewmodel.RecentFilter
import com.smartdialerpro.android.viewmodel.RecentsViewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
fun RecentsScreen(
    viewModel: RecentsViewModel,
    navController: NavController,
    embedded: Boolean = false,
    dialerQuery: String = "",
    dialerMatches: List<Contact> = emptyList(),
    onUserScroll: () -> Unit = {}
) {
    val context = LocalContext.current
    var hasPermission by remember { mutableStateOf(hasReadCallLog(context)) }
    var expandedCallId by remember { mutableStateOf<String?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasPermission = granted
        if (granted) viewModel.loadRecents(force = true)
    }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val granted = hasReadCallLog(context)
                hasPermission = granted
                if (granted) viewModel.loadRecents(force = true)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val calls by viewModel.recentCalls.collectAsState()
    val filter by viewModel.filter.collectAsState()
    val displayCalls = remember(calls, filter) {
        calls.filter {
            when (filter) {
                RecentFilter.ALL -> true
                RecentFilter.MISSED -> it.type == CallLog.Calls.MISSED_TYPE
                RecentFilter.INCOMING -> it.type == CallLog.Calls.INCOMING_TYPE
                RecentFilter.OUTGOING -> it.type == CallLog.Calls.OUTGOING_TYPE
            }
        }
    }
    val listState = rememberLazyListState()
    val cleanQuery = remember(dialerQuery) { dialerQuery.filter(Char::isDigit).takeLast(15) }
    val recentSuggestions = remember(cleanQuery, displayCalls, dialerMatches, dialerQuery) {
        if (cleanQuery.isBlank() && dialerQuery.isBlank()) emptyList()
        else {
            val recent = displayCalls.filter { call ->
                val digits = call.number.filter(Char::isDigit)
                digits.contains(cleanQuery) || call.name?.contains(dialerQuery.trim(), ignoreCase = true) == true
            }
            val contacts = dialerMatches.filter { contact ->
                contact.number.filter(Char::isDigit).contains(cleanQuery) || contact.name.contains(dialerQuery.trim(), ignoreCase = true)
            }
            (recent + contacts.map { contact ->
                RecentCall(
                    id = "suggestion_${contact.id}_${contact.number}",
                    name = contact.name,
                    number = contact.number,
                    type = CallLog.Calls.OUTGOING_TYPE,
                    date = 0L,
                    duration = 0L,
                    photoUri = contact.photoUri,
                    callCount = 1,
                    missedCount = 0
                )
            }).distinctBy { it.number }.take(5)
        }
    }

    LaunchedEffect(listState) {
        snapshotFlow { Triple(listState.isScrollInProgress, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset) }
            .map { (scrolling, index, offset) -> scrolling && (index > 0 || offset > 8) }
            .distinctUntilChanged()
            .filter { it }
            .collect { onUserScroll() }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (!hasPermission) {
            EmptyStateView(
                icon = Icons.Rounded.History,
                title = "Access Required",
                description = "Allow access to call history to display your recent calls.",
                actionText = "Grant Permission",
                onActionClick = { launcher.launch(Manifest.permission.READ_CALL_LOG) }
            )
        } else {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.Center) {
                FilterChip(selected = filter == RecentFilter.ALL, onClick = { viewModel.setFilter(RecentFilter.ALL) }, label = { Text("All") }, shape = RoundedCornerShape(50), modifier = Modifier.padding(end = 8.dp))
                FilterChip(selected = filter == RecentFilter.MISSED, onClick = { viewModel.setFilter(RecentFilter.MISSED) }, label = { Text("Missed") }, shape = RoundedCornerShape(50))
            }

            if (displayCalls.isEmpty() && recentSuggestions.isEmpty()) {
                EmptyStateView(
                    icon = if (filter == RecentFilter.MISSED) Icons.Rounded.CallMissed else Icons.Rounded.History,
                    title = if (calls.isEmpty()) "No recent calls" else "No matching calls",
                    description = if (calls.isEmpty()) "Your incoming and outgoing calls will appear here." else "No calls match the selected filter or search.",
                    actionText = if (filter != RecentFilter.ALL) "Show all" else null,
                    onActionClick = if (filter != RecentFilter.ALL) ({ viewModel.setFilter(RecentFilter.ALL) }) else null
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 110.dp)
                ) {
                    if (recentSuggestions.isNotEmpty()) {
                        item(key = "dialer_suggestions") {
                            Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 3.dp)) {
                                Text("Suggestions", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp))
                                recentSuggestions.forEach { suggestion -> RecentSuggestionRow(suggestion, context) }
                            }
                        }
                    }
                    items(displayCalls, key = { it.id }) { call ->
                        if (isFirstForDay(displayCalls, call)) {
                            Text(dayLabel(call.date), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 4.dp))
                        }
                        SmartDialerRecentCallRow(
                            call = call,
                            expanded = expandedCallId == call.id,
                            onToggle = { expandedCallId = if (expandedCallId == call.id) null else call.id },
                            onInfo = { navController.navigate(Screen.ContactProfile.createRoute(Uri.encode(call.number))) },
                            onCall = { Utils.makeCall(context, call.number) },
                            onMessage = { openSms(context, call.number) },
                            onWhatsApp = { openWhatsApp(context, call.number) }
                        )
                    }
                }
            }
        }
    }
}

private fun hasReadCallLog(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED

@Composable
private fun RecentSuggestionRow(suggestion: RecentCall, context: Context) {
    Row(Modifier.fillMaxWidth().clickable { Utils.makeCall(context, suggestion.number) }.padding(horizontal = 8.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        ContactAvatar(suggestion.name?.takeIf { it.isNotBlank() } ?: suggestion.number, suggestion.photoUri, 40.dp)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(suggestion.name?.takeIf { it.isNotBlank() } ?: suggestion.number, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(suggestion.number, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        Icon(Icons.Rounded.Call, "Call", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
    }
}

private fun isFirstForDay(calls: List<RecentCall>, current: RecentCall): Boolean {
    val index = calls.indexOfFirst { it.id == current.id }
    return index <= 0 || dayKey(calls[index - 1].date) != dayKey(current.date)
}

private fun dayKey(time: Long): String {
    val c = Calendar.getInstance().apply { timeInMillis = time }
    return "${c.get(Calendar.YEAR)}-${c.get(Calendar.DAY_OF_YEAR)}"
}

private fun dayLabel(time: Long): String {
    val now = Calendar.getInstance()
    val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
    return when {
        dayKey(time) == dayKey(now.timeInMillis) -> "Today"
        dayKey(time) == dayKey(yesterday.timeInMillis) -> "Yesterday"
        Calendar.getInstance().apply { timeInMillis = time }.get(Calendar.YEAR) == now.get(Calendar.YEAR) -> SimpleDateFormat("dd MMM", Locale.getDefault()).format(Date(time))
        else -> SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(time))
    }
}

@Composable
private fun SmartDialerRecentCallRow(call: RecentCall, expanded: Boolean, onToggle: () -> Unit, onInfo: () -> Unit, onCall: () -> Unit, onMessage: () -> Unit, onWhatsApp: () -> Unit) {
    val icon = when (call.type) {
        CallLog.Calls.INCOMING_TYPE -> Icons.Rounded.CallReceived
        CallLog.Calls.OUTGOING_TYPE -> Icons.Rounded.CallMade
        CallLog.Calls.MISSED_TYPE -> Icons.Rounded.CallMissed
        else -> Icons.Rounded.Phone
    }
    val iconColor = if (call.type == CallLog.Calls.MISSED_TYPE) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    val displayName = call.name?.takeIf { it.isNotBlank() } ?: call.number
    val isUnknown = call.name.isNullOrBlank()
    val intelligenceLabel = when {
        isUnknown && call.missedCount >= 2 -> "Repeated missed caller"
        isUnknown && call.callCount >= 3 -> "Frequent unknown caller"
        call.missedCount >= 2 -> "${call.missedCount} missed calls"
        call.callCount >= 3 -> "${call.callCount} calls"
        else -> null
    }
    val durationText = formatCallDuration(call.duration)
    val backgroundColor by animateColorAsState(if (expanded) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .65f) else Color.Transparent, label = "recent_bg")
    val rowShape = if (expanded) RoundedCornerShape(20.dp) else RoundedCornerShape(0.dp)

    Column(
        Modifier.fillMaxWidth().padding(horizontal = if (expanded) 4.dp else 0.dp, vertical = 2.dp)
            .background(backgroundColor, rowShape)
            .clickable(onClick = onToggle)
            .animateContentSize(animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium))
            .padding(horizontal = if (expanded) 12.dp else 8.dp, vertical = 10.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            ContactAvatar(displayName, call.photoUri, 46.dp)
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(displayName, fontSize = 17.sp, fontWeight = FontWeight.Medium, color = if (call.type == CallLog.Calls.MISSED_TYPE) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, false))
                    if (isUnknown) {
                        Spacer(Modifier.width(7.dp))
                        AssistChip(onClick = onInfo, label = { Text("Unknown", fontSize = 10.sp) }, leadingIcon = { Icon(Icons.Rounded.PersonOff, null, Modifier.size(14.dp)) }, modifier = Modifier.height(28.dp))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, null, tint = iconColor, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(call.number, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (call.duration > 0) Text(" • $durationText", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    call.simSlot?.let { Text(" • SIM $it", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                intelligenceLabel?.let { Text(it, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = if (call.missedCount >= 2) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 2.dp)) }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(Utils.formatCallDate(call.date), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                IconButton(onClick = onCall, modifier = Modifier.size(40.dp)) { Icon(Icons.Rounded.Call, "Call", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp)) }
            }
        }
        if (expanded) Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            RecentActionButton(Icons.AutoMirrored.Rounded.Message, "Message", onMessage)
            RecentActionButton(ImageVector.vectorResource(R.drawable.ic_whatsapp), "WhatsApp", onWhatsApp, Color(0xFF25D366))
            RecentActionButton(Icons.Rounded.Info, "Info", onInfo)
        }
    }
}

@Composable
private fun RecentActionButton(icon: ImageVector, label: String, onClick: () -> Unit, iconTint: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp)) {
        Icon(icon, label, tint = iconTint, modifier = Modifier.size(22.dp)); Spacer(Modifier.height(5.dp)); Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun formatCallDuration(seconds: Long): String {
    if (seconds <= 0) return "0s"
    val m = seconds / 60
    val s = seconds % 60
    return if (m > 0) "${m}m ${s}s" else "${s}s"
}

private fun openSms(context: Context, number: String) {
    val clean = number.filter { it.isDigit() || it == '+' }
    if (clean.isBlank()) return
    try { context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$clean"))) }
    catch (t: Throwable) { android.util.Log.w("RecentsScreen", "Unable to open SMS composer", t) }
}

private fun openWhatsApp(context: Context, number: String) {
    val clean = number.filter { it.isDigit() || it == '+' }
    if (clean.isBlank()) return
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW).apply { data = Uri.parse("https://api.whatsapp.com/send?phone=$clean"); setPackage("com.whatsapp") })
    } catch (t: Throwable) {
        try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://api.whatsapp.com/send?phone=$clean"))) }
        catch (inner: Throwable) { android.util.Log.w("RecentsScreen", "Unable to open WhatsApp", inner) }
    }
}