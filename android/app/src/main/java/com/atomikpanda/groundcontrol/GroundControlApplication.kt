package com.atomikpanda.groundcontrol

import android.app.Application
import com.atomikpanda.groundcontrol.notify.NotificationChannels

class GroundControlApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        NotificationChannels.createAll(this)
    }
}
