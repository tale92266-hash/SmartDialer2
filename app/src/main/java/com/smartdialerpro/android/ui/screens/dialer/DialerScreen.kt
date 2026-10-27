package com.smartdialerpro.android.ui.screens.dialer

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.telecom.TelecomManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.smartdialerpro.android.data.RecentCall
import com.smartdialerpro.android.data.SimInfo
import com.smartdialerpro.android.viewmodel.DialerViewModel
import kotlinx.coroutines.launch

private const val PREFS_NAME = "dialer_preferences"
private const val KEY_ACCOUNT_ID = "selected_phone_account_id"

@Composable
fun DialerScreen(
    snackbarHostState: SnackbarHostState,
    viewModel: DialerViewModel,
    recentCalls: List<RecentCall> = emptyList(),
    modifier: Modifier = Modifier,
    onNumberChanged: (String) -> Unit = {},
    onSearchClick: (() -> Unit)? = null,
    onContactsClick: (() -> Unit)? = null
) {
    var numberValue by remember { mutableStateOf(TextFieldValue("")) }
    val number = numberValue.text
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    var selectedAccountId by remember { mutableStateOf(prefs.getString(KEY_ACCOUNT_ID, null)) }
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()
    val sims by viewModel.availableSims.collectAsState()
    val selectedSimIndex = sims.indexOfFirst { it.accountHandle?.id == selectedAccountId }
    val selectedSim = sims.getOrNull(selectedSimIndex)

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        viewModel.loadSimCards(context)
        val canCall = ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        if (canCall && number.isNotEmpty()) placeRealCall(context, number, selectedSim)
        else if (!canCall) coroutineScope.launch { snackbarHostState.showSnackbar("Call permission denied. Open Settings to allow phone calls.") }
    }

    LaunchedEffect(Unit) {
        viewModel.loadDataForMatching()
        viewModel.loadSimCards(context)
    }

    LaunchedEffect(sims) {
        val currentStillValid = selectedAccountId != null && sims.any { it.accountHandle?.id == selectedAccountId }
        when {
            currentStillValid -> Unit
            sims.size == 1 -> {
                selectedAccountId = sims.first().accountHandle?.id
                viewModel.persistSelectedSim(context, sims.first())
            }
            else -> {
                selectedAccountId = null
                viewModel.persistSelectedSim(context, null)
            }
        }
    }

    LaunchedEffect(number) {
        viewModel.findMatch(number)
        onNumberChanged(number)
    }

    Column(modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            NumberDisplayRow(
                value = numberValue,
                onValueChange = { numberValue = formatForDisplay(it) },
                onDelete = {
                    if (numberValue.text.isNotEmpty()) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        numberValue = deletePreviousCharacter(numberValue)
                    }
                },
                onLongDelete = {
                    if (numberValue.text.isNotEmpty()) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        numberValue = TextFieldValue("")
                    }
                }
            )

            if (sims.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                SimSelector(sims, selectedSimIndex) { index ->
                    sims.getOrNull(index)?.let { sim ->
                        selectedAccountId = sim.accountHandle?.id
                        viewModel.persistSelectedSim(context, sim)
                    }
                }
            }

            Spacer(Modifier.height(3.dp))
            DialPad(
                onKeyPress = { key ->
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    numberValue = insertAtCursor(numberValue, key)
                },
                onLongKeyPress = { key ->
                    if (key == "0") {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        numberValue = insertAtCursor(numberValue, "+")
                    }
                }
            )
        }

        DialerActionRow {
            if (number.isEmpty()) {
                coroutineScope.launch { snackbarHostState.showSnackbar("Enter a number first") }
                return@DialerActionRow
            }
            val hasCall = ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
            if (hasCall) placeRealCall(context, number, selectedSim)
            else permissionLauncher.launch(arrayOf(Manifest.permission.CALL_PHONE))
        }
    }
}

private fun formatForDisplay(value: TextFieldValue): TextFieldValue {
    val raw = value.text
    if (raw.isEmpty()) return value
    val clean = raw.filter { it.isDigit() || it == '+' || it == '*' || it == '#' || it == ',' }
    if (clean.isEmpty()) return TextFieldValue("")
    if (clean.any { it == '*' || it == '#' || it == ',' }) return value.copy(text = clean, selection = TextRange(clean.length))
    val formatted = formatPhoneNumber(clean)
    val cursorRawCount = raw.take(value.selection.start.coerceAtMost(raw.length)).count { it.isDigit() || it == '+' }
    var seen = 0
    var cursor = 0
    while (cursor < formatted.length && seen < cursorRawCount) {
        if (formatted[cursor] != ' ') seen++
        cursor++
    }
    return TextFieldValue(formatted, TextRange(cursor.coerceAtMost(formatted.length)))
}

private fun insertAtCursor(value: TextFieldValue, inserted: String): TextFieldValue {
    val start = value.selection.min.coerceIn(0, value.text.length)
    val end = value.selection.max.coerceIn(0, value.text.length)
    val next = value.text.substring(0, start) + inserted + value.text.substring(end)
    return formatForDisplay(TextFieldValue(next, TextRange(start + inserted.length)))
}

private fun deletePreviousCharacter(value: TextFieldValue): TextFieldValue {
    val start = value.selection.min.coerceIn(0, value.text.length)
    val end = value.selection.max.coerceIn(0, value.text.length)
    if (start != end) return formatForDisplay(TextFieldValue(value.text.removeRange(start, end), TextRange(start)))
    if (start <= 0) return value
    var deleteStart = start - 1
    if (value.text[deleteStart] == ' ') deleteStart--
    if (deleteStart < 0) return value.copy(selection = TextRange(0))
    val raw = value.text.removeRange(deleteStart, start)
    return formatForDisplay(TextFieldValue(raw, TextRange(deleteStart)))
}

private fun formatPhoneNumber(raw: String): String {
    if (raw.startsWith("+")) {
        val digits = raw.substring(1)
        val out = StringBuilder("+")
        if (digits.isNotEmpty()) out.append(digits.substring(0, minOf(2, digits.length)))
        if (digits.length > 2) out.append(' ').append(digits.substring(2, minOf(7, digits.length)))
        if (digits.length > 7) out.append(' ').append(digits.substring(7))
        return out.toString().trim()
    }
    val out = StringBuilder()
    if (raw.isNotEmpty()) out.append(raw.substring(0, minOf(5, raw.length)))
    if (raw.length > 5) out.append(' ').append(raw.substring(5, minOf(10, raw.length)))
    if (raw.length > 10) out.append(' ').append(raw.substring(10))
    return out.toString().trim()
}

fun placeRealCall(context: Context, number: String, selectedSim: SimInfo?) {
    val cleanNumber = number.filter { it.isDigit() || it == '+' || it == '*' || it == '#' || it == ',' || it == ';' }
    if (cleanNumber.isBlank()) return
    try {
        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:${Uri.encode(cleanNumber)}")).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            selectedSim?.accountHandle?.let { putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, it) }
        }
        context.startActivity(intent)
    } catch (t: Throwable) {
        try {
            context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(cleanNumber)}")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
        } catch (_: Throwable) {}
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NumberDisplayRow(value: TextFieldValue, onValueChange: (TextFieldValue) -> Unit, onDelete: () -> Unit, onLongDelete: () -> Unit) {
    val number = value.text
    val responsiveFontSize = when {
        number.length <= 6 -> 31.sp
        number.length <= 9 -> 28.sp
        number.length <= 12 -> 24.sp
        number.length <= 15 -> 21.sp
        else -> 18.sp
    }
    Row(Modifier.fillMaxWidth().height(42.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).height(40.dp), contentAlignment = Alignment.Center) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(
                    fontSize = responsiveFontSize,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth().basicMarquee()
            )
        }
        Box(
            Modifier.size(36.dp).combinedClickable(onClick = onDelete, onLongClick = onLongDelete),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.AutoMirrored.Rounded.Backspace, "Delete", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(21.dp))
        }
    }
}

@Composable
fun SimSelector(sims: List<SimInfo>, selectedIndex: Int, onSimSelected: (Int) -> Unit) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Row(
            Modifier.height(40.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(50)).padding(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            sims.forEachIndexed { index, sim ->
                val title = if (sim.slotIndex >= 0) "SIM ${sim.slotIndex + 1}" else "Phone"
                val subtitle = sim.displayName ?: sim.carrierName
                SimPill(title, subtitle, selectedIndex == index) { onSimSelected(index) }
            }
        }
    }
}

@Composable
fun SimPill(title: String, subtitle: String, isSelected: Boolean, onClick: () -> Unit) {
    val bgColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        Modifier.height(36.dp).background(bgColor, RoundedCornerShape(50)).clickable(onClick = onClick).padding(horizontal = 15.dp, vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(title, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = contentColor)
        Text(subtitle, fontSize = 7.sp, color = contentColor.copy(alpha = .8f), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun DialPad(onKeyPress: (String) -> Unit, onLongKeyPress: (String) -> Unit) {
    val keys = listOf(
        listOf("1" to "", "2" to "ABC", "3" to "DEF"),
        listOf("4" to "GHI", "5" to "JKL", "6" to "MNO"),
        listOf("7" to "PQRS", "8" to "TUV", "9" to "WXYZ"),
        listOf("*" to "", "0" to "+", "#" to "")
    )
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        keys.forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                row.forEach { (num, chars) ->
                    DialPadKey(
                        number = num,
                        letters = chars,
                        modifier = Modifier.weight(1f),
                        onClick = { onKeyPress(num) },
                        onLongClick = { onLongKeyPress(num) }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DialPadKey(number: String, letters: String, modifier: Modifier, onClick: () -> Unit, onLongClick: () -> Unit) {
    Column(
        modifier
            .height(41.dp)
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(number, fontSize = 20.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onBackground)
        if (letters.isNotEmpty()) Text(letters, fontSize = 7.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 1.sp)
    }
}

@Composable
fun DialerActionRow(onCall: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(48.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        FloatingActionButton(
            onClick = onCall,
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            shape = CircleShape,
            modifier = Modifier.size(46.dp),
            elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 2.dp)
        ) {
            Icon(Icons.Rounded.Call, "Call", modifier = Modifier.size(21.dp))
        }
    }
}
