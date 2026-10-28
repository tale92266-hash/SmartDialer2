package com.smartdialerpro.android.ui.screens.settings

import android.Manifest
import android.app.Activity
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.net.Uri
import android.provider.CallLog
import android.provider.Settings
import android.telecom.TelecomManager
import android.telephony.SubscriptionManager
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import com.smartdialerpro.android.service.AmbientSoundManager
import com.smartdialerpro.android.service.FakeCallSim
import com.smartdialerpro.android.service.SmartDialerScreeningService
import com.smartdialerpro.android.ui.components.CompactTopBar
import com.smartdialerpro.android.ui.navigation.Screen

private const val PREFS = "app_prefs"
private const val DIALER_PREFS = "dialer_preferences"
private const val KEY_ACCOUNT_ID = "selected_phone_account_id"

/** Settings home: professional group cards, each opening its own sub-screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(navController: NavController? = null) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text("Settings", fontSize = 32.sp, fontWeight = FontWeight.Bold)
            Text(
                "Customize your calling & dialer experience",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
            )
        }
        item {
            SettingsGroupCard(
                icon = Icons.Rounded.Settings,
                title = "General",
                summary = "Dialpad feedback, tones & suggestions"
            ) { navController?.navigate(Screen.SettingsGeneral.route) }
        }
        item {
            SettingsGroupCard(
                icon = Icons.Rounded.SimCard,
                title = "Calls & SIM",
                summary = "SIM selection & call confirmation"
            ) { navController?.navigate(Screen.SettingsCalls.route) }
        }
        item {
            SettingsGroupCard(
                icon = Icons.Rounded.FiberManualRecord,
                title = "Recording",
                summary = "Auto-record & saved recordings"
            ) { navController?.navigate(Screen.SettingsRecording.route) }
        }
        item {
            SettingsGroupCard(
                icon = Icons.Rounded.Security,
                title = "Privacy & Blocked",
                summary = "Blocked numbers & app permissions"
            ) { navController?.navigate(Screen.SettingsPrivacy.route) }
        }
        item {
            SettingsGroupCard(
                icon = Icons.Rounded.AutoAwesome,
                title = "Magic Call",
                summary = "Fake calls, ambient sound & caller voice"
            ) { navController?.navigate(Screen.SettingsMagicCall.route) }
        }
        item {
            SettingsGroupCard(
                icon = Icons.Rounded.Info,
                title = "System & About",
                summary = "Default dialer, notifications & version"
            ) { navController?.navigate(Screen.SettingsSystem.route) }
        }
        item { Spacer(Modifier.height(40.dp)) }
    }
}

@Composable
private fun SettingsGroupCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    summary: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier
                        .padding(10.dp)
                        .size(24.dp)
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 16.dp)
            ) {
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Text(
                    summary,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            Icon(
                Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSubScreenScaffold(
    title: String,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Scaffold(
        topBar = { CompactTopBar(title = title, onBack = onBack) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content
        )
    }
}

@Composable
fun SettingsGeneralScreen(navController: NavController? = null) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    var vibrate by remember { mutableStateOf(prefs.getBoolean("dialer_haptic", true)) }
    var dtmfTone by remember { mutableStateOf(prefs.getBoolean("dialer_tones", true)) }
    var showOnlyContacts by remember { mutableStateOf(prefs.getBoolean("show_only_contacts", false)) }
    var openDialpadOnLaunch by remember { mutableStateOf(prefs.getBoolean("open_dialpad_on_launch", false)) }
    var dtmfLength by remember { mutableStateOf(prefs.getString("dtmf_tone_length", "normal") ?: "normal") }
    var showDtmfDialog by remember { mutableStateOf(false) }
    SettingsSubScreenScaffold(title = "General", onBack = { navController?.popBackStack() }) {
        SettingSwitchRow(
            icon = Icons.Rounded.Vibration,
            title = "Haptic feedback",
            subtitle = "Vibrate lightly on dialpad presses and deletions",
            checked = vibrate
        ) {
            vibrate = it
            prefs.edit().putBoolean("dialer_haptic", it).apply()
        }
        SettingSwitchRow(
            icon = Icons.Rounded.VolumeUp,
            title = "Dialpad audio tones",
            subtitle = "Play DTMF sound tones when dialing digits",
            checked = dtmfTone
        ) {
            dtmfTone = it
            prefs.edit().putBoolean("dialer_tones", it).apply()
        }
        SettingRow(
            icon = Icons.Rounded.Dialpad,
            title = "Dialpad tone length",
            subtitle = when (dtmfLength) {
                "short" -> "Short tone"
                "long" -> "Long tone (helps with IVR menus)"
                else -> "Normal tone"
            }
        ) { showDtmfDialog = true }
        if (showDtmfDialog) {
            val lengths = listOf("short", "normal", "long")
            val labels = listOf("Short", "Normal", "Long")
            SingleChoiceDialog(
                title = "Dialpad tone length",
                options = labels,
                selected = lengths.indexOf(dtmfLength).takeIf { it >= 0 } ?: 1,
                onDismiss = { showDtmfDialog = false },
                onSelect = {
                    dtmfLength = lengths[it]
                    prefs.edit().putString("dtmf_tone_length", lengths[it]).apply()
                    showDtmfDialog = false
                }
            )
        }
        SettingSwitchRow(
            icon = Icons.Rounded.Contacts,
            title = "Contacts only in suggestions",
            subtitle = "Hide unknown numbers from dialpad smart matches",
            checked = showOnlyContacts
        ) {
            showOnlyContacts = it
            prefs.edit().putBoolean("show_only_contacts", it).apply()
        }
        SettingSwitchRow(
            icon = Icons.Rounded.Dialpad,
            title = "Open dialpad on launch",
            subtitle = "Show the dialpad when the app starts",
            checked = openDialpadOnLaunch
        ) {
            openDialpadOnLaunch = it
            prefs.edit().putBoolean("open_dialpad_on_launch", it).apply()
        }
    }
}

@Composable
fun SettingsCallsScreen(navController: NavController? = null) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    var confirmCall by remember { mutableStateOf(prefs.getBoolean("confirm_call", false)) }
    var vibrateOnAnswer by remember { mutableStateOf(prefs.getBoolean("vibrate_on_answer", false)) }
    var vibrateOnEnd by remember { mutableStateOf(prefs.getBoolean("vibrate_on_end", false)) }
    var proximityOff by remember { mutableStateOf(prefs.getBoolean("proximity_screen_off", true)) }
    var flipToSilence by remember { mutableStateOf(prefs.getBoolean("flip_to_silence", false)) }
    var flashOnCall by remember { mutableStateOf(prefs.getBoolean("flash_on_call", false)) }
    var pendingFlashAfterPermission by remember { mutableStateOf(false) }
    var autoAnswer by remember { mutableStateOf(prefs.getBoolean("auto_answer_headset", false)) }
    var autoAnswerDelay by remember { mutableStateOf(prefs.getInt("auto_answer_delay_secs", 5)) }
    var autoRedial by remember { mutableStateOf(prefs.getBoolean("auto_redial", false)) }
    var redialAttempts by remember { mutableStateOf(prefs.getInt("auto_redial_attempts", 3)) }
    var redialGap by remember { mutableStateOf(prefs.getInt("auto_redial_gap", 30)) }
    val dialerPrefs = remember { context.getSharedPreferences(DIALER_PREFS, Context.MODE_PRIVATE) }
    val selectedAccountId by remember { mutableStateOf(dialerPrefs.getString(KEY_ACCOUNT_ID, null)) }
    var simMode by remember { mutableStateOf(dialerPrefs.getInt("default_sim_mode", 1)) }
    var showSimModeDialog by remember { mutableStateOf(false) }
    var showDelayDialog by remember { mutableStateOf(false) }
    var showAttemptsDialog by remember { mutableStateOf(false) }
    var showGapDialog by remember { mutableStateOf(false) }
    var ringtoneTitle by remember { mutableStateOf(currentRingtoneTitle(context)) }
    var pendingRingtoneAfterWriteSettings by remember { mutableStateOf(false) }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && pendingFlashAfterPermission) {
            flashOnCall = true
            prefs.edit().putBoolean("flash_on_call", true).apply()
        } else if (!granted) {
            ToastHelper.show(context, "Camera permission is needed for flash alerts.")
        }
        pendingFlashAfterPermission = false
    }

    val ringtonePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            try {
                val picked = result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI) as? Uri
                if (picked != null) {
                    RingtoneManager.setActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE, picked)
                    ringtoneTitle = currentRingtoneTitle(context)
                }
            } catch (t: Throwable) {
                ToastHelper.show(context, "Could not change the ringtone.")
            }
        }
    }

    val writeSettingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (Settings.System.canWrite(context)) {
            if (pendingRingtoneAfterWriteSettings) openRingtonePicker(context, ringtonePickerLauncher)
            pendingRingtoneAfterWriteSettings = false
        } else {
            ToastHelper.show(context, "Please allow modifying system settings to change the ringtone.")
        }
    }

    fun openPicker() {
        if (Settings.System.canWrite(context)) {
            openRingtonePicker(context, ringtonePickerLauncher)
        } else {
            pendingRingtoneAfterWriteSettings = true
            try {
                writeSettingsLauncher.launch(
                    Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}"))
                )
            } catch (_: Throwable) {
                ToastHelper.show(context, "Please allow modifying system settings first.")
            }
        }
    }

    SettingsSubScreenScaffold(title = "Calls & SIM", onBack = { navController?.popBackStack() }) {
        SectionTitle("SIM & carrier")
        SettingRow(
            icon = Icons.Rounded.SimCard,
            title = "SIM selection",
            subtitle = if (selectedAccountId.isNullOrBlank()) "System default • choose per call" else "Configured in dialer"
        ) {
            ToastHelper.show(context, "You can select your preferred SIM directly in the Dialer or Contact Profile.")
        }
        SettingRow(
            icon = Icons.Rounded.SimCard,
            title = "Default SIM for calls",
            subtitle = when (simMode) {
                0 -> "SIM 1 always"
                2 -> "Ask every time"
                else -> "SIM 2 always"
            }
        ) { showSimModeDialog = true }
        SettingRow(
            icon = Icons.Rounded.Forward,
            title = "Call forwarding",
            subtitle = "Forward calls to another number (carrier setting)"
        ) { openSystemPage(context, TelecomManager.ACTION_SHOW_CALL_SETTINGS) }
        SettingRow(
            icon = Icons.Rounded.Schedule,
            title = "Call waiting",
            subtitle = "Be notified of a second incoming call (carrier setting)"
        ) { openSystemPage(context, TelecomManager.ACTION_SHOW_CALL_SETTINGS) }
        SettingRow(
            icon = Icons.Rounded.Badge,
            title = "Caller ID",
            subtitle = "Show or hide your number on outgoing calls (carrier setting)"
        ) { openSystemPage(context, TelecomManager.ACTION_SHOW_CALL_SETTINGS) }
        SettingRow(
            icon = Icons.Rounded.Wifi,
            title = "Wi-Fi calling",
            subtitle = "Call over Wi-Fi when the mobile signal is weak"
        ) { openSystemPage(context, "android.settings.WIFI_CALLING_SETTINGS", TelecomManager.ACTION_SHOW_CALL_SETTINGS) }
        SettingRow(
            icon = Icons.Rounded.Voicemail,
            title = "Voicemail",
            subtitle = "Voicemail number and notifications"
        ) { openSystemPage(context, "android.settings.VOICEMAIL_SETTINGS", TelecomManager.ACTION_SHOW_CALL_SETTINGS) }

        SectionTitle("Answering & ending")
        SettingSwitchRow(
            icon = Icons.Rounded.Call,
            title = "Confirm before calling",
            subtitle = "Ask for confirmation before placing an outgoing call",
            checked = confirmCall
        ) {
            confirmCall = it
            prefs.edit().putBoolean("confirm_call", it).apply()
        }
        SettingSwitchRow(
            icon = Icons.Rounded.Vibration,
            title = "Vibrate when call connects",
            subtitle = "Short vibration when the other side answers",
            checked = vibrateOnAnswer
        ) {
            vibrateOnAnswer = it
            prefs.edit().putBoolean("vibrate_on_answer", it).apply()
        }
        SettingSwitchRow(
            icon = Icons.Rounded.Vibration,
            title = "Vibrate when call ends",
            subtitle = "Short vibration when the call disconnects",
            checked = vibrateOnEnd
        ) {
            vibrateOnEnd = it
            prefs.edit().putBoolean("vibrate_on_end", it).apply()
        }
        SettingSwitchRow(
            icon = Icons.Rounded.ScreenRotation,
            title = "Flip to silence",
            subtitle = "Turn the phone face-down to silence an incoming call",
            checked = flipToSilence
        ) {
            flipToSilence = it
            prefs.edit().putBoolean("flip_to_silence", it).apply()
        }
        SettingRow(
            icon = Icons.Rounded.PowerSettingsNew,
            title = "Power button ends call",
            subtitle = "Opens the system Accessibility setting"
        ) { openSystemPage(context, Settings.ACTION_ACCESSIBILITY_SETTINGS) }
        SettingSwitchRow(
            icon = Icons.Rounded.Headphones,
            title = "Auto-answer on headset",
            subtitle = "Answer automatically when a headset or car Bluetooth is connected",
            checked = autoAnswer
        ) {
            autoAnswer = it
            prefs.edit().putBoolean("auto_answer_headset", it).apply()
        }
        SettingRow(
            icon = Icons.Rounded.Schedule,
            title = "Auto-answer delay",
            subtitle = "$autoAnswerDelay seconds after the call starts ringing"
        ) { showDelayDialog = true }
        SettingSwitchRow(
            icon = Icons.Rounded.Refresh,
            title = "Auto redial",
            subtitle = "Redial automatically when the number is busy or unanswered",
            checked = autoRedial
        ) {
            autoRedial = it
            prefs.edit().putBoolean("auto_redial", it).apply()
        }
        SettingRow(
            icon = Icons.Rounded.Refresh,
            title = "Redial attempts",
            subtitle = "$redialAttempts attempts"
        ) { showAttemptsDialog = true }
        SettingRow(
            icon = Icons.Rounded.Schedule,
            title = "Redial gap",
            subtitle = "Wait $redialGap seconds between attempts"
        ) { showGapDialog = true }
        SettingRow(
            icon = Icons.Rounded.Message,
            title = "Quick responses",
            subtitle = "Edit the SMS replies offered when declining a call"
        ) {
            if (navController != null) navController.navigate(Screen.QuickResponses.route)
            else ToastHelper.show(context, "Navigation controller unavailable")
        }

        SectionTitle("During the call")
        SettingSwitchRow(
            icon = Icons.Rounded.Sensors,
            title = "Turn off screen near ear",
            subtitle = "Proximity sensor blanks the screen during calls",
            checked = proximityOff
        ) {
            proximityOff = it
            prefs.edit().putBoolean("proximity_screen_off", it).apply()
        }
        SettingSwitchRow(
            icon = Icons.Rounded.FlashOn,
            title = "Flash on incoming call",
            subtitle = "Blink the camera flash while the phone rings",
            checked = flashOnCall
        ) {
            if (it) {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    flashOnCall = true
                    prefs.edit().putBoolean("flash_on_call", true).apply()
                } else {
                    pendingFlashAfterPermission = true
                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                }
            } else {
                flashOnCall = false
                prefs.edit().putBoolean("flash_on_call", false).apply()
            }
        }
        SettingRow(
            icon = Icons.Rounded.MusicNote,
            title = "Phone ringtone",
            subtitle = ringtoneTitle
        ) { openPicker() }

        SectionTitle("Accessibility")
        SettingRow(
            icon = Icons.Rounded.AccessibilityNew,
            title = "TTY mode",
            subtitle = "Text telephone mode for incoming and outgoing calls"
        ) { openSystemPage(context, "android.settings.TTY_SETTINGS", Settings.ACTION_ACCESSIBILITY_SETTINGS) }
        SettingRow(
            icon = Icons.Rounded.VolumeUp,
            title = "Hearing aids",
            subtitle = "Bluetooth hearing aid and compatibility settings"
        ) { openSystemPage(context, "android.settings.HEARING_AID_SETTINGS", Settings.ACTION_ACCESSIBILITY_SETTINGS) }

        if (showSimModeDialog) {
            SingleChoiceDialog(
                title = "Default SIM for calls",
                options = listOf("SIM 1 always", "SIM 2 always", "Ask every time"),
                selected = simMode,
                onDismiss = { showSimModeDialog = false },
                onSelect = {
                    simMode = it
                    dialerPrefs.edit().putInt("default_sim_mode", it).apply()
                    showSimModeDialog = false
                }
            )
        }
        if (showDelayDialog) {
            val delays = listOf(3, 5, 10)
            SingleChoiceDialog(
                title = "Auto-answer delay",
                options = delays.map { "$it seconds" },
                selected = delays.indexOf(autoAnswerDelay).takeIf { it >= 0 } ?: 1,
                onDismiss = { showDelayDialog = false },
                onSelect = {
                    autoAnswerDelay = delays[it]
                    prefs.edit().putInt("auto_answer_delay_secs", delays[it]).apply()
                    showDelayDialog = false
                }
            )
        }
        if (showAttemptsDialog) {
            val attempts = listOf(2, 3, 5)
            SingleChoiceDialog(
                title = "Redial attempts",
                options = attempts.map { "$it attempts" },
                selected = attempts.indexOf(redialAttempts).takeIf { it >= 0 } ?: 1,
                onDismiss = { showAttemptsDialog = false },
                onSelect = {
                    redialAttempts = attempts[it]
                    prefs.edit().putInt("auto_redial_attempts", attempts[it]).apply()
                    showAttemptsDialog = false
                }
            )
        }
        if (showGapDialog) {
            val gaps = listOf(30, 60, 90)
            SingleChoiceDialog(
                title = "Redial gap",
                options = gaps.map { "$it seconds" },
                selected = gaps.indexOf(redialGap).takeIf { it >= 0 } ?: 0,
                onDismiss = { showGapDialog = false },
                onSelect = {
                    redialGap = gaps[it]
                    prefs.edit().putInt("auto_redial_gap", gaps[it]).apply()
                    showGapDialog = false
                }
            )
        }
    }
}

private fun openSystemPage(context: Context, action: String, fallbackAction: String = Settings.ACTION_ACCESSIBILITY_SETTINGS) {
    try {
        val intent = Intent(action)
        if (intent.resolveActivity(context.packageManager) != null) {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        context.startActivity(Intent(fallbackAction).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Throwable) {
        ToastHelper.show(context, "Could not open system settings.")
    }
}

private fun currentRingtoneTitle(context: Context): String {
    return try {
        val uri = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE)
            ?: return "Default ringtone"
        RingtoneManager.getRingtone(context, uri)?.getTitle(context) ?: "Default ringtone"
    } catch (_: Throwable) {
        "Default ringtone"
    }
}

private fun openRingtonePicker(
    context: Context,
    launcher: ActivityResultLauncher<Intent>
) {
    try {
        val existing = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE)
        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_RINGTONE)
            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Phone ringtone")
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            if (existing != null) putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, existing)
        }
        launcher.launch(intent)
    } catch (_: Throwable) {
        ToastHelper.show(context, "Could not open the ringtone picker.")
    }
}

@Composable
private fun SingleChoiceDialog(
    title: String,
    options: List<String>,
    selected: Int,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEachIndexed { index, label ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(index) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = index == selected, onClick = { onSelect(index) })
                        Spacer(Modifier.width(8.dp))
                        Text(label)
                    }
                }
            }
        },
        confirmButton = {}
    )
}

@Composable
fun SettingsRecordingScreen(navController: NavController? = null) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    var autoRecord by remember { mutableStateOf(prefs.getBoolean("auto_record_calls", false)) }
    var unknownOnly by remember { mutableStateOf(prefs.getBoolean("auto_record_unknown_only", false)) }
    SettingsSubScreenScaffold(title = "Recording", onBack = { navController?.popBackStack() }) {
        SettingSwitchRow(
            icon = Icons.Rounded.FiberManualRecord,
            title = "Auto-record active calls",
            subtitle = "Automatically trigger recording on answered phone calls",
            checked = autoRecord
        ) {
            autoRecord = it
            prefs.edit().putBoolean("auto_record_calls", it).apply()
            if (it) ToastHelper.show(context, "Recording will start when active calls connect.")
        }
        SettingSwitchRow(
            icon = Icons.Rounded.Person,
            title = "Unknown numbers only",
            subtitle = "Auto-record only calls from numbers not in your contacts",
            checked = unknownOnly
        ) {
            unknownOnly = it
            prefs.edit().putBoolean("auto_record_unknown_only", it).apply()
        }
        SettingRow(
            icon = Icons.Rounded.FiberManualRecord,
            title = "Call recordings",
            subtitle = "Play, share, or delete your recorded calls"
        ) {
            if (navController != null) {
                navController.navigate(Screen.Recordings.route)
            } else {
                ToastHelper.show(context, "Navigation controller unavailable")
            }
        }
    }
}

@Composable
fun SettingsPrivacyScreen(navController: NavController? = null) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    val roleManager = remember { context.getSystemService(RoleManager::class.java) }
    var silenceUnknown by remember { mutableStateOf(prefs.getBoolean(SmartDialerScreeningService.KEY_SILENCE_UNKNOWN, false)) }
    var roleHeld by remember {
        mutableStateOf(
            try {
                roleManager?.isRoleHeld(RoleManager.ROLE_CALL_SCREENING) == true
            } catch (_: Throwable) {
                false
            }
        )
    }
    var pendingSilenceAfterRole by remember { mutableStateOf(false) }
    val roleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val held = try {
            roleManager?.isRoleHeld(RoleManager.ROLE_CALL_SCREENING) == true
        } catch (_: Throwable) {
            false
        }
        roleHeld = held
        if (held && pendingSilenceAfterRole) {
            silenceUnknown = true
            prefs.edit().putBoolean(SmartDialerScreeningService.KEY_SILENCE_UNKNOWN, true).apply()
        } else if (!held && pendingSilenceAfterRole) {
            ToastHelper.show(context, "Caller ID & spam role is needed to silence unknown callers.")
        }
        pendingSilenceAfterRole = false
    }

    SettingsSubScreenScaffold(title = "Privacy & Blocked", onBack = { navController?.popBackStack() }) {
        SettingRow(
            icon = Icons.Rounded.Block,
            title = "Blocked numbers",
            subtitle = "Manage system-wide blocked callers and spam rejection"
        ) {
            if (navController != null) {
                navController.navigate(Screen.BlockedNumbers.route)
            } else {
                ToastHelper.show(context, "Navigation controller unavailable")
            }
        }
        SettingSwitchRow(
            icon = Icons.Rounded.Shield,
            title = "Silence unknown callers",
            subtitle = "Calls from numbers not in your contacts never ring",
            checked = silenceUnknown
        ) {
            if (it) {
                if (roleHeld) {
                    silenceUnknown = true
                    prefs.edit().putBoolean(SmartDialerScreeningService.KEY_SILENCE_UNKNOWN, true).apply()
                } else {
                    pendingSilenceAfterRole = true
                    try {
                        val intent = roleManager?.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING)
                        if (intent != null) roleLauncher.launch(intent)
                        else ToastHelper.show(context, "Caller ID & spam role is not available on this phone.")
                    } catch (_: Throwable) {
                        ToastHelper.show(context, "Could not request the Caller ID & spam role.")
                    }
                }
            } else {
                silenceUnknown = false
                prefs.edit().putBoolean(SmartDialerScreeningService.KEY_SILENCE_UNKNOWN, false).apply()
            }
        }
        SettingRow(
            icon = Icons.Rounded.Security,
            title = "Caller ID & spam role",
            subtitle = if (roleHeld) "Granted — SmartDialer can screen incoming calls" else "Not granted — tap to allow call screening"
        ) {
            try {
                val intent = roleManager?.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING)
                if (intent != null) roleLauncher.launch(intent)
                else ToastHelper.show(context, "Caller ID & spam role is not available on this phone.")
            } catch (_: Throwable) {
                ToastHelper.show(context, "Could not request the Caller ID & spam role.")
            }
        }
        SettingRow(
            icon = Icons.Rounded.Security,
            title = "App permissions",
            subtitle = "Manage Phone, Microphone, and Contacts permissions"
        ) {
            try {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Throwable) {
                ToastHelper.show(context, "Unable to open app settings.")
            }
        }
    }
}

@Composable
fun SettingsSystemScreen(navController: NavController? = null) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    var keepScreenOn by remember { mutableStateOf(prefs.getBoolean("keep_screen_on", false)) }
    SettingsSubScreenScaffold(title = "System & About", onBack = { navController?.popBackStack() }) {
        val roleManager = context.getSystemService(Context.ROLE_SERVICE) as? RoleManager
        val isDefault = roleManager?.isRoleHeld(RoleManager.ROLE_DIALER) == true
        SettingSwitchRow(
            icon = Icons.Rounded.Visibility,
            title = "Keep screen on during calls",
            subtitle = "Prevent the display from sleeping on the call screen",
            checked = keepScreenOn
        ) {
            keepScreenOn = it
            prefs.edit().putBoolean("keep_screen_on", it).apply()
        }
        SettingRow(
            icon = Icons.Rounded.Phone,
            title = "Default phone app",
            subtitle = if (isDefault) "SmartDialer is active default dialer" else "Set SmartDialer as system default"
        ) {
            try {
                if (roleManager?.isRoleAvailable(RoleManager.ROLE_DIALER) == true) {
                    context.startActivity(roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } else ToastHelper.show(context, "Default dialer role is unavailable on this device.")
            } catch (_: Throwable) {
                ToastHelper.show(context, "Unable to launch default phone app settings.")
            }
        }
        SettingRow(
            icon = Icons.Rounded.Notifications,
            title = "Call notifications",
            subtitle = "Configure ongoing, incoming, and heads-up banner alerts"
        ) {
            try {
                context.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Throwable) {
                ToastHelper.show(context, "Unable to open notification settings.")
            }
        }
        SettingRow(
            icon = Icons.Rounded.Info,
            title = "About SmartDialer",
            subtitle = "Version 1.0 • ${com.smartdialerpro.android.util.BuildInfo.LABEL}"
        ) {}
    }
}

@Composable
fun SettingsMagicCallScreen(navController: NavController? = null) {
    val context = LocalContext.current
    val ambient = remember { AmbientSoundManager.getInstance(context) }
    var preset by remember { mutableStateOf(ambient.getCurrentPreset()) }
    var isRecording by remember { mutableStateOf(ambient.isRecordingVoice()) }
    var hasVoice by remember { mutableStateOf(ambient.hasCustomVoice()) }

    val recordPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            if (ambient.startRecordingVoice()) {
                isRecording = true
                ToastHelper.show(context, "Recording caller voice… tap Stop when done.")
            } else {
                ToastHelper.show(context, "Could not start recording.")
            }
        } else {
            ToastHelper.show(context, "Microphone permission is required to record a voice.")
        }
    }
    val pickAudioLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Throwable) {
            }
            ambient.setCustomVoiceUri(uri)
            hasVoice = true
            ToastHelper.show(context, "Caller voice file selected.")
        }
    }

    SettingsSubScreenScaffold(title = "Magic Call", onBack = { navController?.popBackStack() }) {
        SectionTitle("Ambient sound")
        Text(
            "Background sound played during a fake call, so it feels like a real place.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
        )
        listOf(
            AmbientSoundManager.PRESET_TRAFFIC to "Traffic",
            AmbientSoundManager.PRESET_AIRPORT to "Airport",
            AmbientSoundManager.PRESET_OFFICE to "Office",
            AmbientSoundManager.PRESET_OFF to "Off"
        ).forEach { (value, label) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        preset = value
                        ambient.setPreset(value)
                    }
                    .padding(horizontal = 4.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (preset == value) {
                    Icon(Icons.Rounded.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary)
                }
            }
        }

        SectionTitle("Caller voice")
        Text(
            if (hasVoice) "A caller voice is set and will play during fake calls."
            else "No caller voice yet. Record one or pick an audio file.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
        )
        SettingRow(
            icon = if (isRecording) Icons.Rounded.Stop else Icons.Rounded.Mic,
            title = if (isRecording) "Stop recording" else "Record caller voice",
            subtitle = if (isRecording) "Recording… tap to stop and save" else "Record with the microphone"
        ) {
            if (isRecording) {
                if (ambient.stopRecordingVoice()) {
                    isRecording = false
                    hasVoice = ambient.hasCustomVoice()
                    ToastHelper.show(context, "Caller voice saved.")
                } else {
                    isRecording = false
                    ToastHelper.show(context, "Recording was too short to save.")
                }
            } else {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    if (ambient.startRecordingVoice()) {
                        isRecording = true
                        ToastHelper.show(context, "Recording caller voice… tap Stop when done.")
                    } else {
                        ToastHelper.show(context, "Could not start recording.")
                    }
                } else {
                    recordPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
        }
        SettingRow(
            icon = Icons.Rounded.AudioFile,
            title = "Pick an audio file",
            subtitle = "Use any audio file as the caller voice"
        ) {
            pickAudioLauncher.launch("audio/*")
        }
        if (hasVoice) {
            SettingRow(
                icon = Icons.Rounded.Delete,
                title = "Remove caller voice",
                subtitle = "Fake calls will use ambient sound only"
            ) {
                ambient.deleteRecordedVoice()
                hasVoice = false
                ToastHelper.show(context, "Caller voice removed.")
            }
        }

        SectionTitle("Call log")
        val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? android.telecom.TelecomManager
        val isDefaultDialer = telecomManager?.defaultDialerPackage == context.packageName
        val hasWriteLog = ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALL_LOG) == PackageManager.PERMISSION_GRANTED
        Text(
            if (isDefaultDialer || hasWriteLog) "Fake call logs are written to the real system call log, so they appear in every dialer app."
            else "Set SmartDialer as the default phone app so fake call logs appear in every dialer app.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
        )
        SettingRow(
            icon = Icons.Rounded.Phone,
            title = if (isDefaultDialer) "Default dialer is set" else "Set as default dialer",
            subtitle = if (isDefaultDialer) "Call logs will be saved" else "Required to save call logs"
        ) {
            if (!isDefaultDialer) {
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
            } else {
                ToastHelper.show(context, "SmartDialer is already the default phone app.")
            }
        }

        SectionTitle("Diagnostics")
        var diagRefresh by remember { mutableStateOf(0) }
        val diag = remember(diagRefresh) { loadFakeCallDiagnostics(context) }
        SettingRow(
            icon = Icons.Rounded.Phone,
            title = "Default dialer",
            subtitle = diag.defaultDialer
        ) {}
        SettingRow(
            icon = Icons.Rounded.Security,
            title = "Permissions",
            subtitle = diag.permissions
        ) {}
        SettingRow(
            icon = Icons.Rounded.SimCard,
            title = "Phone accounts",
            subtitle = diag.accounts
        ) {}
        SettingRow(
            icon = Icons.Rounded.SignalCellularAlt,
            title = "SIM subscriptions",
            subtitle = diag.subscriptions
        ) {}
        SettingRow(
            icon = Icons.Rounded.AutoAwesome,
            title = "Default fake-call SIM",
            subtitle = diag.defaultSim
        ) {}
        SettingRow(
            icon = Icons.Rounded.History,
            title = "Last fake-call log write",
            subtitle = diag.lastWrite
        ) {}
        SettingRow(
            icon = Icons.Rounded.Visibility,
            title = "Last log row on this phone",
            subtitle = diag.lastRow
        ) {}
        SettingRow(
            icon = Icons.Rounded.Refresh,
            title = "Refresh diagnostics",
            subtitle = "Tap to re-check after a fake call"
        ) { diagRefresh++ }

        SectionTitle("About Magic Call")
        Text(
            "Magic Call starts a simulated outgoing call with the duration you pick. " +
                "It never uses the SIM or network. When it ends, a realistic entry is added to the call log. " +
                "Call-log entries need SmartDialer set as the default phone app.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp)
        )
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title.uppercase(),
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 18.dp, bottom = 4.dp, start = 4.dp)
    )
}

private data class FakeCallDiag(
    val defaultDialer: String,
    val permissions: String,
    val accounts: String,
    val subscriptions: String,
    val defaultSim: String,
    val lastWrite: String,
    val lastRow: String
)

/** On-device facts for debugging fake-call SIM / log issues. All reads guarded: a missing permission or column shows as text, never a crash. */
private fun loadFakeCallDiagnostics(context: Context): FakeCallDiag {
    fun granted(perm: String) =
        ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED

    val telecom = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
    val defaultDialer = try {
        val isDefault = telecom?.defaultDialerPackage == context.packageName
        if (isDefault) "Yes — SmartDialer" else "No — ${telecom?.defaultDialerPackage ?: "unknown"}"
    } catch (_: Throwable) {
        "Unknown"
    }
    val permissions = "Phone state: ${if (granted(Manifest.permission.READ_PHONE_STATE)) "granted" else "DENIED"}, " +
        "Read log: ${if (granted(Manifest.permission.READ_CALL_LOG)) "granted" else "DENIED"}, " +
        "Write log: ${if (granted(Manifest.permission.WRITE_CALL_LOG)) "granted" else "DENIED"}"

    val accounts = try {
        val handles = telecom?.callCapablePhoneAccounts.orEmpty()
        if (handles.isEmpty()) "None found"
        else handles.mapIndexed { i, h -> "$i: ${h.id.take(16)}" }.joinToString(" | ")
    } catch (t: Throwable) {
        "Error: ${t.javaClass.simpleName}"
    }
    val subscriptions = try {
        val subs = SubscriptionManager.from(context)?.activeSubscriptionInfoList.orEmpty()
        if (subs.isEmpty()) "None found (needs phone-state permission)"
        else subs.joinToString(" | ") {
            "slot ${it.simSlotIndex}: subId ${it.subscriptionId} • ${it.carrierName ?: ""}"
        }
    } catch (t: Throwable) {
        "Error: ${t.javaClass.simpleName}"
    }
    val defaultSim = try {
        val slot = FakeCallSim.resolveDefaultSimSlot(context)
        FakeCallSim.simLabelForSlot(context, slot) + " (slot $slot)"
    } catch (t: Throwable) {
        "Error: ${t.javaClass.simpleName}"
    }
    val prefs = context.getSharedPreferences("dialer_preferences", Context.MODE_PRIVATE)
    val lastWrite = prefs.getString("fake_log_last_write", null) ?: "No fake call logged yet"
    val lastRow = readLastFakeLogRow(context, prefs)
    return FakeCallDiag(defaultDialer, permissions, accounts, subscriptions, defaultSim, lastWrite, lastRow)
}

private fun readLastFakeLogRow(context: Context, prefs: android.content.SharedPreferences): String {
    val number = prefs.getString("fake_log_last_number", null)
    if (number.isNullOrBlank()) return "No fake call logged yet"
    return try {
        val base = arrayOf(CallLog.Calls.DATE, CallLog.Calls.DURATION, CallLog.Calls.PHONE_ACCOUNT_ID)
        val withSub = base + "sub_id"
        var usedSub = true
        var cursor = try {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI, withSub,
                "${CallLog.Calls.NUMBER} = ?", arrayOf(number),
                "${CallLog.Calls.DATE} DESC LIMIT 1"
            )
        } catch (_: Throwable) {
            usedSub = false
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI, base,
                "${CallLog.Calls.NUMBER} = ?", arrayOf(number),
                "${CallLog.Calls.DATE} DESC LIMIT 1"
            )
        }
        cursor?.use {
            if (!it.moveToFirst()) return "No row found for $number"
            val date = it.getLong(0)
            val duration = it.getInt(1)
            val account = if (!it.isNull(2)) it.getString(2)?.take(20) else null
            val sub = if (usedSub) {
                val idx = it.getColumnIndex("sub_id")
                if (idx >= 0 && !it.isNull(idx)) it.getLong(idx).toString() else "missing"
            } else "column absent"
            "date=$date duration=${duration}s account=${account ?: "MISSING"} sub_id=$sub"
        } ?: "Query returned nothing (needs call-log permission)"
    } catch (t: Throwable) {
        "Error: ${t.javaClass.simpleName} — needs call-log permission"
    }
}

@Composable
private fun SettingRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(23.dp)
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 16.dp)
            ) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(
                    subtitle,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            Icon(
                Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
private fun SettingSwitchRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(23.dp)
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 16.dp)
            ) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(
                    subtitle,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

private object ToastHelper {
    fun show(context: Context, message: String) =
        android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
}
