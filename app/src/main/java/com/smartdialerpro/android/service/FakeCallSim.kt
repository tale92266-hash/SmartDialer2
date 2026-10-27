package com.smartdialerpro.android.service

import android.content.Context
import android.telephony.SubscriptionManager

/**
 * Which SIM a Magic (fake) call goes "from": the SIM the user has
 * selected in the dialer (persisted slot in dialer_preferences).
 * With no explicit selection, SIM 2 (slot 1) is the default when
 * present, else the first active subscription. Every entry point resolves
 * through here so the fake call, its header chip and its call-log row
 * all agree on the same SIM.
 */
object FakeCallSim {

    fun resolveDefaultSimSlot(context: Context): Int {
        val persisted = context.getSharedPreferences("dialer_preferences", Context.MODE_PRIVATE)
            .getInt("selected_sim_slot", -1)
        val subs = activeSubs(context)
        if (persisted >= 0) {
            if (subs.isEmpty() || subs.any { it.simSlotIndex == persisted }) return persisted
        }
        return subs.firstOrNull { it.simSlotIndex == 1 }?.simSlotIndex
            ?: subs.firstOrNull()?.simSlotIndex
            ?: 0
    }

    fun simLabelForSlot(context: Context, slot: Int): String {
        val carrier = try {
            activeSubs(context).firstOrNull { it.simSlotIndex == slot }
                ?.carrierName?.toString()?.takeIf { it.isNotBlank() }
        } catch (_: Throwable) {
            null
        }
        return "SIM ${slot + 1}" + (if (carrier != null) " • $carrier" else "")
    }

    private fun activeSubs(context: Context) = try {
        SubscriptionManager.from(context)?.activeSubscriptionInfoList.orEmpty()
    } catch (_: Throwable) {
        emptyList()
    }
}
