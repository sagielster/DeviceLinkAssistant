package com.example.devicelinkassistant

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log

object HubIntegrations {

    enum class Hub { ALEXA }

    fun handoffToHub(context: Context, hub: Hub) {
        when (hub) {
            Hub.ALEXA -> openAlexa(context)
        }
    }

    /**
     * Option B: Alexa is a voice remote/controller. The PHONE remains the Bluetooth hub.
     * This just opens Alexa so the user can trigger a routine or say "play X on this phone".
     */
    fun openAlexaApp(context: Context) {
        openAlexa(context)
    }

    fun openBluetoothSettings(context: Context) {
        try {
            val bt = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(bt)
        } catch (t: Throwable) {
            Log.w("HubIntegrations", "Failed to open Bluetooth settings", t)
        }
    }

    private fun openAlexa(context: Context) {
        val alexaPkg = "com.amazon.dee.app"

        val launch = context.packageManager.getLaunchIntentForPackage(alexaPkg)
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(launch)
                return
            } catch (t: Throwable) {
                Log.w("HubIntegrations", "Failed to launch Alexa app", t)
            }
        }

        // fallback
        openBluetoothSettings(context)
    }
}
