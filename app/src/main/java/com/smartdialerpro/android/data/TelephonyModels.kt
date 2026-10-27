package com.smartdialerpro.android.data

import android.telecom.PhoneAccountHandle

data class SimInfo(
    val slotIndex: Int,
    val carrierName: String,
    val accountHandle: PhoneAccountHandle?,
    val subscriptionId: Int? = null,
    val displayName: String? = null,
    val phoneNumber: String? = null,
    val isActive: Boolean = true
)