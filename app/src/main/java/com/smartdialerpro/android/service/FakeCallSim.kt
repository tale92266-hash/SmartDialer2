package com.smartdialerpro.android.service

import android.content.Context
import android.telephony.SubscriptionManager

/**
 * Which SIM a Magic (fake) call goes "from". The Settings default-SIM
 * mode wins: SIM 1 / SIM 2 force that slot; "Ask every time" falls
 * back to the dialer's persisted selection (else SIM 2), and the
 * fake-call sheet always shows the resolved SIM before starting.
 * With no explicit selection, SIM 2 (slot 1) is the default when
 * present, else the first active subscription. Every entry point resolves
 * through here so the fake call, its header chip and its call-log row
 * all agree on the same SIM.
 */
object FakeCallSim {

    /** 0 = SIM 1, 1 = SIM 2, 2 = ask every time. */
    fun defaultSimMode(context: Context): Int {
        return context.getSharedPreferences("dialer_preferences", Context.MODE_PRIVATE)
            .getInt("default_sim_mode", 1)
    }

    fun resolveDefaultSimSlot(context: Context): Int {
        val dialerPrefs = context.getSharedPreferences("dialer_preferences", Context.MODE_PRIVATE)
        val mode = dialerPrefs.getInt("default_sim_mode", 1)
        val subs = activeSubs(context)
        if (mode == 0 || mode == 1) {
            if (subs.isEmpty() || subs.any { it.simSlotIndex == mode }) return mode
        }
        val persisted = dialerPrefs.getInt("selected_sim_slot", -1)
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
