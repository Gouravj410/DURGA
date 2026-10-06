package com.durga.emergency

import android.app.Application
import android.util.Log

class DurgaApplication : Application() {

    companion object {
        const val TAG = "DURGA"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "[DURGA] Application initialized.")
    }
}
