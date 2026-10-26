package com.hermes.client.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.hermes.client.data.repository.NotificationSettings
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Brings the connection service back after a reboot or an app update, when the user has
 * notifications enabled. Without it nothing is listening until the app is next opened by hand.
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {
    @Inject lateinit var settings: NotificationSettings

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (settings.prefs.first().enabled) GatewayConnectionService.start(context)
            } finally {
                pending.finish()
            }
        }
    }
}
