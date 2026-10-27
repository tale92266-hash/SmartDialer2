package com.smartdialerpro.android.ui.components

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.smartdialerpro.android.R

data class WhatsAppTarget(
    val packageName: String,
    val title: String,
    val subtitle: String,
    val tint: Color
)

private const val WHATSAPP_PKG = "com.whatsapp"
private const val WHATSAPP_BUSINESS_PKG = "com.whatsapp.w4b"
private const val PREFS = "app_prefs"
private const val KEY_CHOICE = "whatsapp_choice_pkg"

fun installedWhatsAppTargets(context: Context): List<WhatsAppTarget> {
    val targets = ArrayList<WhatsAppTarget>()
    if (isPackageInstalled(context, WHATSAPP_PKG)) {
        targets.add(WhatsAppTarget(WHATSAPP_PKG, "WhatsApp", "Personal chats", Color(0xFF25D366)))
    }
    if (isPackageInstalled(context, WHATSAPP_BUSINESS_PKG)) {
        targets.add(WhatsAppTarget(WHATSAPP_BUSINESS_PKG, "WhatsApp Business", "Business chats", Color(0xFF075E54)))
    }
    return targets
}

private fun isPackageInstalled(context: Context, packageName: String): Boolean {
    return try {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }
}

fun openWhatsAppChat(context: Context, number: String, packageName: String?): Boolean {
    val clean = number.filter { it.isDigit() || it == '+' }
    if (clean.isBlank()) return false
    return try {
        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("https://api.whatsapp.com/send?phone=$clean")
            if (packageName != null) setPackage(packageName)
        })
        true
    } catch (_: Throwable) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://api.whatsapp.com/send?phone=$clean")))
            true
        } catch (_: Throwable) {
            Toast.makeText(context, "WhatsApp is not installed", Toast.LENGTH_SHORT).show()
            false
        }
    }
}

/**
 * Opens the chat directly when there is a remembered choice or only one
 * WhatsApp app installed; otherwise asks the caller to show the chooser
 * dialog for [number] via [onNeedChoice].
 */
fun launchWhatsApp(context: Context, number: String, onNeedChoice: (String) -> Unit) {
    val targets = installedWhatsAppTargets(context)
    if (targets.isEmpty()) {
        openWhatsAppChat(context, number, null)
        return
    }
    if (targets.size == 1) {
        openWhatsAppChat(context, number, targets.first().packageName)
        return
    }
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val remembered = prefs.getString(KEY_CHOICE, null)
    val match = targets.firstOrNull { it.packageName == remembered }
    if (match != null) {
        openWhatsAppChat(context, number, match.packageName)
    } else {
        onNeedChoice(number)
    }
}

/** Chooser dialog shown when both WhatsApp and WhatsApp Business are installed. */
@Composable
fun WhatsAppChooserDialog(
    number: String,
    onDismiss: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val targets = remember { installedWhatsAppTargets(context) }
    var rememberChoice by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Open chat in", fontWeight = FontWeight.SemiBold) },
        text = {
            Column {
                targets.forEach { target ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.large)
                            .clickable {
                                if (rememberChoice) {
                                    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                                        .edit().putString(KEY_CHOICE, target.packageName).apply()
                                }
                                openWhatsAppChat(context, number, target.packageName)
                                onDismiss()
                            }
                            .padding(horizontal = 8.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        androidx.compose.foundation.layout.Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(target.tint.copy(alpha = 0.16f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                ImageVector.vectorResource(R.drawable.ic_whatsapp),
                                contentDescription = null,
                                tint = target.tint,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(target.title, fontWeight = FontWeight.SemiBold)
                            Text(
                                target.subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { rememberChoice = !rememberChoice }
                        .padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = rememberChoice, onCheckedChange = { rememberChoice = it })
                    Text("Always use this app", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
