package com.smartdialerpro.android.data

import androidx.compose.runtime.Immutable

@Immutable
data class Contact(
    val id: String,
    val name: String,
    val number: String,
    val normalizedNumber: String,
    val photoUri: String?,
    val isFavorite: Boolean = false
)

@Immutable
data class RecentCall(
    val id: String,
    val name: String?,
    val number: String,
    val type: Int,
    val date: Long,
    val duration: Long,
    val simSlot: Int? = null,
    val photoUri: String? = null,
    val callCount: Int = 1,
    val missedCount: Int = 0
)
