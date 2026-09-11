package com.sharethis.app

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

class ShareThisApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
    }
}
