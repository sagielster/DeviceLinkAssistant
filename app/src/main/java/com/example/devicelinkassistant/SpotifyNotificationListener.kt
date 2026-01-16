package com.example.devicelinkassistant

import android.service.notification.NotificationListenerService

/**
 * Empty NotificationListenerService used only so MediaSessionManager.getActiveSessions(...)
 * is allowed to return active media sessions.
 *
 * User must enable Notification Access for this app in Settings.
 */
class SpotifyNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        // No-op. Presence of this service + user-enabled Notification Access is what matters.
    }
}
