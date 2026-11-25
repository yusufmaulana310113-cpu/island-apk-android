package id.island.dynamic

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings as AndroidSettings
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (Settings.load(ctx).enabled && AndroidSettings.canDrawOverlays(ctx)) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, IslandService::class.java))
        }
    }
}
