package id.island.dynamic

import android.app.Notification
import android.graphics.Bitmap
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** Menangkap notifikasi sistem untuk ditampilkan di pulau (+ memberi akses ke sesi media). */
class IslandListener : NotificationListenerService() {
    private val seen = LruMap<String, Int>(64)
    private val icons = LruMap<String, Bitmap?>(24)

    override fun onListenerConnected() { IslandHub.onListenerConnected?.invoke() }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val n = sbn.notification ?: return
        if (n.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_GROUP_SUMMARY) != 0) return
        when (n.category) {
            Notification.CATEGORY_TRANSPORT, Notification.CATEGORY_PROGRESS,
            Notification.CATEGORY_SERVICE, Notification.CATEGORY_SYSTEM, Notification.CATEGORY_STATUS -> return
        }
        val ex = n.extras
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val body = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString().orEmpty()
        if (title.isBlank() && body.isBlank()) return
        val hash = (title + "\u0000" + body).hashCode()
        if (seen[sbn.key] == hash) return          // update progres/duplikat
        seen[sbn.key] = hash

        val app = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        } catch (e: Exception) { sbn.packageName.substringAfterLast('.').replaceFirstChar { it.uppercase() } }

        val big = try { n.getLargeIcon()?.loadDrawable(this)?.let { drawableToBitmap(it, 96) } } catch (_: Exception) { null }
        val icon = big ?: icons.getOrPut(sbn.packageName) {
            try { drawableToBitmap(packageManager.getApplicationIcon(sbn.packageName), 96) } catch (_: Exception) { null }
        }
        val reply = n.actions?.firstOrNull { a -> a.actionIntent != null && a.remoteInputs?.isNotEmpty() == true }
        val color = if (n.color != 0) (n.color or (0xFF shl 24)) else appColor(app)
        IslandHub.notify(NotifItem(sbn.key, sbn.packageName, app, title, body, icon, color, n.contentIntent, reply))
    }
}
