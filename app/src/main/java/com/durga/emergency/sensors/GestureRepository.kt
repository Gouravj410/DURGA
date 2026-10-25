package com.durga.emergency.sensors

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class GestureRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    companion object {
        private const val PREFS_NAME = "durga_gesture_prefs"
        private const val KEY_GESTURES_JSON = "key_gestures_json"
        private const val KEY_GESTURES_ENABLED = "key_gestures_enabled"

        @Volatile
        private var instance: GestureRepository? = null

        fun getInstance(context: Context): GestureRepository {
            return instance ?: synchronized(this) {
                instance ?: GestureRepository(context.applicationContext).also { instance = it }
            }
        }
    }

    var isGestureActivationEnabled: Boolean
        get() = prefs.getBoolean(KEY_GESTURES_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_GESTURES_ENABLED, value).apply()

    fun getAllGestures(): List<GestureConfig> {
        val json = prefs.getString(KEY_GESTURES_JSON, null)
        if (json.isNullOrEmpty()) {
            val defaults = listOf(
                GestureConfig.createDefaultShake(),
                GestureConfig.createRapidShake(),
                GestureConfig.createDefaultTouchPattern()
            )
            saveAllGestures(defaults)
            return defaults
        }

        return try {
            val type = object : TypeToken<List<GestureConfig>>() {}.type
            gson.fromJson<List<GestureConfig>>(json, type) ?: listOf(GestureConfig.createDefaultShake())
        } catch (e: Exception) {
            listOf(GestureConfig.createDefaultShake())
        }
    }

    fun getPrimaryGesture(): GestureConfig {
        val gestures = getAllGestures()
        return gestures.firstOrNull { it.isPrimary } ?: gestures.firstOrNull() ?: GestureConfig.createDefaultShake()
    }

    fun saveGesture(gesture: GestureConfig) {
        val gestures = getAllGestures().toMutableList()
        val index = gestures.indexOfFirst { it.id == gesture.id }
        if (index >= 0) {
            gestures[index] = gesture
        } else {
            gestures.add(gesture)
        }
        if (gesture.isPrimary) {
            for (i in gestures.indices) {
                if (gestures[i].id != gesture.id) {
                    gestures[i] = gestures[i].copy(isPrimary = false)
                }
            }
        }
        saveAllGestures(gestures)
    }

    fun deleteGesture(gestureId: String): Boolean {
        val gestures = getAllGestures().toMutableList()
        if (gestures.size <= 1) {
            // Cannot delete the only remaining gesture
            return false
        }
        val removed = gestures.removeAll { it.id == gestureId }
        if (removed && gestures.none { it.isPrimary }) {
            // Ensure at least one primary gesture exists
            gestures[0] = gestures[0].copy(isPrimary = true)
        }
        saveAllGestures(gestures)
        return removed
    }

    fun setPrimaryGesture(gestureId: String) {
        val gestures = getAllGestures().map {
            it.copy(isPrimary = (it.id == gestureId))
        }
        saveAllGestures(gestures)
    }

    private fun saveAllGestures(gestures: List<GestureConfig>) {
        val json = gson.toJson(gestures)
        prefs.edit().putString(KEY_GESTURES_JSON, json).apply()
    }
}
