package com.smartdialerpro.android.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartdialerpro.android.data.SimInfo

/**
 * "Ask every time" SIM chooser: shown by the dialpads when the
 * Settings default-SIM mode is "Ask every time". Picking a SIM
 * places that one call only — the choice is not persisted.
 */
@Composable
fun SimChooserDialog(
    number: String,
    sims: List<SimInfo>,
    onDismiss: () -> Unit,
    onPick: (SimInfo) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose SIM") },
        text = {
            Column {
                Text(
                    "Call $number with:",
                    fontSize = 13.sp,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                sims.forEach { sim ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(sim) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SimSlotIcon(slot = sim.slotIndex + 1, size = 13.dp)
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text(
                                "SIM ${sim.slotIndex + 1}",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp
                            )
                            val carrier = sim.carrierName.takeIf { it.isNotBlank() }
                            val phone = sim.phoneNumber?.takeIf { it.isNotBlank() }
                            val detail = listOfNotNull(carrier, phone).joinToString(" • ")
                            if (detail.isNotBlank()) {
                                Text(detail, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {}
    )
}
