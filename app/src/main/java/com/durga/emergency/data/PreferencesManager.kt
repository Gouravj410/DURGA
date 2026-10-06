package com.durga.emergency.data

import android.content.Context
import android.content.SharedPreferences
import com.durga.emergency.emergency.UserRole

class PreferencesManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "durga_preferences"
        private const val KEY_SELECTED_ROLE = "key_selected_role"
        private const val KEY_FIRST_LAUNCH = "key_first_launch"
        private const val KEY_CONTACT_NAME = "key_contact_name"
        private const val KEY_CONTACT_PHONE = "key_contact_phone"
        private const val KEY_USER_NAME = "key_user_name"
    }

    var selectedRole: UserRole
        get() {
            val roleName = prefs.getString(KEY_SELECTED_ROLE, null)
            return if (roleName != null) {
                try {
                    UserRole.valueOf(roleName)
                } catch (e: Exception) {
                    UserRole.USER
                }
            } else {
                UserRole.USER
            }
        }
        set(value) {
            prefs.edit().putString(KEY_SELECTED_ROLE, value.name).apply()
        }

    var isFirstLaunch: Boolean
        get() = prefs.getBoolean(KEY_FIRST_LAUNCH, true)
        set(value) = prefs.edit().putBoolean(KEY_FIRST_LAUNCH, value).apply()

    var userName: String
        get() = prefs.getString(KEY_USER_NAME, "Citizen A") ?: "Citizen A"
        set(value) = prefs.edit().putString(KEY_USER_NAME, value).apply()

    var contactName: String
        get() = prefs.getString(KEY_CONTACT_NAME, "Trusted Contact") ?: "Trusted Contact"
        set(value) = prefs.edit().putString(KEY_CONTACT_NAME, value).apply()

    var contactPhone: String
        get() = prefs.getString(KEY_CONTACT_PHONE, "+91-9876543210") ?: "+91-9876543210"
        set(value) = prefs.edit().putString(KEY_CONTACT_PHONE, value).apply()
}
