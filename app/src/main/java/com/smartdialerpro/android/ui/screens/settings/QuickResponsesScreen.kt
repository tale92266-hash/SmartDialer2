package com.smartdialerpro.android.ui.screens.settings

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.smartdialerpro.android.ui.components.CompactTopBar

private const val PREFS = "app_prefs"
private const val KEY_QUICK_RESPONSES = "quick_responses"

val DEFAULT_QUICK_RESPONSES = listOf(
    "Can't talk right now",
    "I'll call you back",
    "I'm in a meeting",
    "I'm driving"
)

fun loadQuickResponses(context: Context): List<String> {
    return try {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getStringSet(KEY_QUICK_RESPONSES, null)
            ?.toList()?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }
            ?: DEFAULT_QUICK_RESPONSES
    } catch (_: Throwable) {
        DEFAULT_QUICK_RESPONSES
    }
}

/**
 * Edits the four SMS templates offered on the incoming-call screen
 * when declining with a message.
 */
@Composable
fun QuickResponsesScreen(navController: NavController? = null) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    val initial = remember { loadQuickResponses(context) }
    var first by remember { mutableStateOf(initial.getOrElse(0) { DEFAULT_QUICK_RESPONSES[0] }) }
    var second by remember { mutableStateOf(initial.getOrElse(1) { DEFAULT_QUICK_RESPONSES[1] }) }
    var third by remember { mutableStateOf(initial.getOrElse(2) { DEFAULT_QUICK_RESPONSES[2] }) }
    var fourth by remember { mutableStateOf(initial.getOrElse(3) { DEFAULT_QUICK_RESPONSES[3] }) }
    var saved by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth()) {
        CompactTopBar(title = "Quick responses", onBack = { navController?.popBackStack() })
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Text(
                "These messages appear when you decline an incoming call. Tapping one ends the call and sends the SMS.",
                fontSize = 13.sp
            )
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = first, onValueChange = { first = it; saved = false },
                label = { Text("Reply 1") }, singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = second, onValueChange = { second = it; saved = false },
                label = { Text("Reply 2") }, singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = third, onValueChange = { third = it; saved = false },
                label = { Text("Reply 3") }, singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = fourth, onValueChange = { fourth = it; saved = false },
                label = { Text("Reply 4") }, singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = {
                    val values = listOf(first, second, third, fourth)
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                    prefs.edit().putStringSet(
                        KEY_QUICK_RESPONSES,
                        if (values.isEmpty()) DEFAULT_QUICK_RESPONSES.toSet() else values.toSet()
                    ).apply()
                    saved = true
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (saved) "Saved" else "Save replies")
            }
        }
    }
}
