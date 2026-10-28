package com.smartdialerpro.android.ui.screens.settings

import android.content.Context
import android.provider.CallLog
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartdialerpro.android.repository.CallLogRepository
import com.smartdialerpro.android.ui.components.CompactTopBar
import com.smartdialerpro.android.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private data class CallStats(
    val total: Int,
    val incoming: Int,
    val outgoing: Int,
    val missed: Int,
    val talkSeconds: Long,
    val topContacts: List<TopContact>,
    val last7Days: List<DayCount>
)

private data class TopContact(val label: String, val count: Int)

private data class DayCount(val label: String, val count: Int)

/**
 * Call statistics, computed on a background thread from the system
 * call log: totals, talk time, most-called numbers and a 7-day bar
 * chart. No network, nothing leaves the device.
 */
@Composable
fun CallStatsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var stats by remember { mutableStateOf<CallStats?>(null) }

    LaunchedEffect(Unit) {
        stats = computeStats(context)
    }

    Scaffold(
        topBar = { CompactTopBar(title = "Call statistics", onBack = onBack) }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            val current = stats
            if (current == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        StatCard("Total calls", current.total.toString(), Modifier.weight(1f))
                        StatCard("Talk time", Utils.formatDuration(current.talkSeconds), Modifier.weight(1f))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        StatCard("Incoming", current.incoming.toString(), Modifier.weight(1f))
                        StatCard("Outgoing", current.outgoing.toString(), Modifier.weight(1f))
                        StatCard("Missed", current.missed.toString(), Modifier.weight(1f))
                    }
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Last 7 days", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                            Spacer(Modifier.height(12.dp))
                            val max = (current.last7Days.maxOfOrNull { it.count } ?: 0).coerceAtLeast(1)
                            Row(
                                Modifier.fillMaxWidth().height(110.dp),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                verticalAlignment = Alignment.Bottom
                            ) {
                                for (day in current.last7Days) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(day.count.toString(), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Spacer(Modifier.height(4.dp))
                                        Box(
                                            modifier = Modifier
                                                .width(22.dp)
                                                .height((8 + (96 * day.count / max)).dp)
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(MaterialTheme.colorScheme.primary)
                                        )
                                        Spacer(Modifier.height(6.dp))
                                        Text(day.label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Most called", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                            Spacer(Modifier.height(8.dp))
                            if (current.topContacts.isEmpty()) {
                                Text(
                                    "No calls yet.",
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            for ((index, top) in current.topContacts.withIndex()) {
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "${index + 1}.",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.width(28.dp)
                                    )
                                    Text(
                                        text = top.label,
                                        fontSize = 14.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        "${top.count} calls",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                    Text(
                        "Based on the calls stored on this phone.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        modifier = modifier
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(value, fontWeight = FontWeight.Bold, fontSize = 22.sp, maxLines = 1)
            Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private suspend fun computeStats(context: Context): CallStats = withContext(Dispatchers.IO) {
    val calls = try {
        CallLogRepository(context).getRecentCalls(1000)
    } catch (_: Throwable) {
        emptyList()
    }
    var incoming = 0
    var outgoing = 0
    var missed = 0
    var talkSeconds = 0L
    val counts = LinkedHashMap<String, Int>()
    val labels = HashMap<String, String>()
    val dayCounts = IntArray(7)
    val dayFormat = SimpleDateFormat("EEE", Locale.US)
    val now = System.currentTimeMillis()
    val dayMs = 24L * 60 * 60 * 1000
    for (call in calls) {
        when (call.type) {
            CallLog.Calls.INCOMING_TYPE -> incoming++
            CallLog.Calls.OUTGOING_TYPE -> outgoing++
            CallLog.Calls.MISSED_TYPE -> missed++
        }
        talkSeconds += call.duration
        val key = call.number.filter(Char::isDigit).takeLast(10).ifBlank { call.number }
        counts[key] = (counts[key] ?: 0) + 1
        labels[key] = call.name?.takeIf { it.isNotBlank() } ?: call.number
        if (call.date > 0) {
            val ageDays = ((now - call.date) / dayMs).toInt()
            if (ageDays in 0..6) dayCounts[6 - ageDays]++
        }
    }
    val calendar = Calendar.getInstance()
    val last7Days = (6 downTo 0).map { back ->
        calendar.time = Date(now - back * dayMs)
        DayCount(dayFormat.format(calendar.time), dayCounts[6 - back])
    }
    val top = counts.entries
        .sortedByDescending { it.value }
        .take(5)
        .map { TopContact(labels[it.key] ?: it.key, it.value) }
    CallStats(
        total = calls.size,
        incoming = incoming,
        outgoing = outgoing,
        missed = missed,
        talkSeconds = talkSeconds,
        topContacts = top,
        last7Days = last7Days
    )
}
