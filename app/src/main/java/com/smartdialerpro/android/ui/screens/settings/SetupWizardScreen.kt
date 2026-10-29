package com.smartdialerpro.android.ui.screens.settings

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import com.smartdialerpro.android.ui.components.CompactTopBar

/**
 * Guided first-run setup: default-dialer role, then the permissions
 * the dialer needs, then done. Each step shows its live status; the
 * user can also reach it later from Settings > System & About.
 */
@Composable
fun SetupWizardScreen(navController: NavController? = null) {
    val context = LocalContext.current
    val roleManager = remember { context.getSystemService(Context.ROLE_SERVICE) as? RoleManager }

    var roleHeld by remember { mutableStateOf(roleManager?.isRoleHeld(RoleManager.ROLE_DIALER) == true) }
    var granted by remember { mutableStateOf(emptyMap<String, Boolean>()) }

    fun refresh() {
        roleHeld = roleManager?.isRoleHeld(RoleManager.ROLE_DIALER) == true
        val needed = neededPermissions()
        granted = needed.associateWith { perm ->
            ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
        }
    }

    LaunchedEffect(Unit) { refresh() }

    val roleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        refresh()
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refresh()
    }

    val allPermissionsGranted = granted.isNotEmpty() && granted.values.all { it }
    val allDone = roleHeld && allPermissionsGranted

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        CompactTopBar(title = "Set up SmartDialer", onBack = { navController?.popBackStack() })
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Three quick steps and SmartDialer can replace your default phone app.",
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 22.sp
            )
            Spacer(Modifier.height(24.dp))

            WizardStep(
                number = "1",
                title = "Default dialer",
                subtitle = "SmartDialer handles your calls, incoming call screen, and call log.",
                done = roleHeld,
                actionLabel = if (roleHeld) "Done" else "Set as default",
                onAction = {
                    try {
                        if (roleManager?.isRoleAvailable(RoleManager.ROLE_DIALER) == true) {
                            roleLauncher.launch(roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER))
                        } else {
                            Toast.makeText(context, "Default dialer role is unavailable on this device.", Toast.LENGTH_LONG).show()
                        }
                    } catch (t: Throwable) {
                        Toast.makeText(context, "Unable to open default phone app settings.", Toast.LENGTH_LONG).show()
                    }
                }
            )
            Spacer(Modifier.height(16.dp))
            WizardStep(
                number = "2",
                title = "Permissions",
                subtitle = "Phone, contacts, call history, phone state, and notifications. Recording permission is only asked when you record.",
                done = allPermissionsGranted,
                actionLabel = if (allPermissionsGranted) "Done" else "Allow permissions",
                onAction = {
                    val missing = granted.filter { !it.value }.keys.toTypedArray()
                    if (missing.isNotEmpty()) permissionLauncher.launch(missing)
                }
            )
            if (granted.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                granted.forEach { (perm, isGranted) ->
                    WizardPermissionRow(perm, isGranted)
                }
            }
            Spacer(Modifier.height(16.dp))
            WizardStep(
                number = "3",
                title = "Try a Magic Call",
                subtitle = "Dial any number, tap the Magic button, and pick a duration — the call looks real everywhere, including the system call log.",
                done = allDone,
                actionLabel = "Done",
                onAction = { navController?.popBackStack() }
            )
            Spacer(Modifier.height(24.dp))
            if (allDone) {
                Text(
                    "You're all set — SmartDialer is your default phone app.",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(12.dp))
            }
            Button(
                onClick = { navController?.popBackStack() },
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text(if (allDone) "Done" else "Skip for now", fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun neededPermissions(): List<String> = buildList {
    add(Manifest.permission.CALL_PHONE)
    add(Manifest.permission.READ_CONTACTS)
    add(Manifest.permission.READ_CALL_LOG)
    add(Manifest.permission.READ_PHONE_STATE)
    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
}

private fun permissionLabel(perm: String): String = when (perm) {
    Manifest.permission.CALL_PHONE -> "Phone calls"
    Manifest.permission.READ_CONTACTS -> "Contacts"
    Manifest.permission.READ_CALL_LOG -> "Call history"
    Manifest.permission.READ_PHONE_STATE -> "Phone state (SIM detection)"
    Manifest.permission.POST_NOTIFICATIONS -> "Notifications"
    else -> perm
}

@Composable
private fun WizardStep(
    number: String,
    title: String,
    subtitle: String,
    done: Boolean,
    actionLabel: String,
    onAction: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .55f), RoundedCornerShape(18.dp))
            .padding(16.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            if (done) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(26.dp)
        )
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text("Step $number — $title", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Spacer(Modifier.height(4.dp))
            Text(subtitle, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 19.sp)
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onAction, shape = RoundedCornerShape(12.dp)) {
                Text(actionLabel)
            }
        }
        Icon(Icons.Rounded.Phone, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun WizardPermissionRow(perm: String, granted: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            permissionLabel(perm),
            fontSize = 14.sp,
            modifier = Modifier.weight(1f)
        )
        Text(
            if (granted) "Allowed" else "Needed",
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
        )
    }
}
