package com.durga.emergency.data.model

import com.durga.emergency.emergency.UserRole

data class UserProfile(
    val userId: String = "",
    val name: String = "",
    val phone: String = "",
    val role: UserRole = UserRole.USER,
    val emergencyContacts: List<String> = emptyList(),
    val fcmToken: String? = null
)
