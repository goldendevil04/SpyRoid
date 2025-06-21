package com.myrat.app.receiver

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import com.myrat.app.utils.Logger

class MyDeviceAdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        Logger.log("Device Admin enabled")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Logger.log("Device Admin disabled")
    }
}