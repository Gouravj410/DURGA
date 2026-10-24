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
        private const val KEY_IS_LOGGED_IN = "key_is_logged_in"
        private const val KEY_USER_ID = "key_user_id"
        private const val KEY_USER_NAME = "key_user_name"
        private const val KEY_USER_PHONE = "key_user_phone"
        private const val KEY_AUTH_TOKEN = "key_auth_token"
        private const val KEY_SECURITY_PIN = "key_security_pin"
        private const val KEY_CONTACT_NAME = "key_contact_name"
        private const val KEY_CONTACT_PHONE = "key_contact_phone"
        private const val KEY_SERVER_BASE_URL = "key_server_base_url"

        const val DEFAULT_SERVER_URL = "http://10.203.117.118:3000/api/v1"
    }

    var isLoggedIn: Boolean
        get() = prefs.getBoolean(KEY_IS_LOGGED_IN, false)
        set(value) = prefs.edit().putBoolean(KEY_IS_LOGGED_IN, value).apply()

    var userId: String
        get() = prefs.getString(KEY_USER_ID, "USER-001") ?: "USER-001"
        set(value) = prefs.edit().putString(KEY_USER_ID, value).apply()

    var authToken: String?
        get() = prefs.getString(KEY_AUTH_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_AUTH_TOKEN, value).apply()

    var userPhone: String
        get() = prefs.getString(KEY_USER_PHONE, "+919876543210") ?: "+919876543210"
        set(value) = prefs.edit().putString(KEY_USER_PHONE, value).apply()

    var userName: String
        get() = prefs.getString(KEY_USER_NAME, "Protected Citizen") ?: "Protected Citizen"
        set(value) = prefs.edit().putString(KEY_USER_NAME, value).apply()

    var securityPin: String
        get() = prefs.getString(KEY_SECURITY_PIN, "1234") ?: "1234"
        set(value) = prefs.edit().putString(KEY_SECURITY_PIN, value).apply()

    var serverBaseUrl: String
        get() = prefs.getString(KEY_SERVER_BASE_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
        set(value) = prefs.edit().putString(KEY_SERVER_BASE_URL, value).apply()

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

    var contactName: String
        get() = prefs.getString(KEY_CONTACT_NAME, "Trusted Contact") ?: "Trusted Contact"
        set(value) = prefs.edit().putString(KEY_CONTACT_NAME, value).apply()

    var contactPhone: String
        get() = prefs.getString(KEY_CONTACT_PHONE, "+91-9876543210") ?: "+91-9876543210"
        set(value) = prefs.edit().putString(KEY_CONTACT_PHONE, value).apply()

    fun clearSession() {
        prefs.edit()
            .remove(KEY_AUTH_TOKEN)
            .remove(KEY_IS_LOGGED_IN)
            .apply()
    }
}
