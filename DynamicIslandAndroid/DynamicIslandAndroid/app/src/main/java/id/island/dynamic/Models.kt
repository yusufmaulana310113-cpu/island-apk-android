package id.island.dynamic

import android.app.Notification
import android.app.PendingIntent
import android.graphics.Bitmap

class NotifItem(
    val key: String,
    val pkg: String,
    val app: String,
    val title: String,
    val body: String,
    val icon: Bitmap?,
    val color: Int,
    val open: PendingIntent?,
    val reply: Notification.Action?,
)

class MediaInfo(
    val pkg: String,
    val title: String,
    val artist: String,
    val cover: Bitmap?,
    val srcIcon: Bitmap?,
    val playing: Boolean,
    val durMs: Long,
    val posMs: Long,
    val stampMs: Long,     // SystemClock.elapsedRealtime() saat posMs valid
    val speed: Float,
)

/** Jembatan antar komponen (service overlay <-> listener notifikasi), satu proses. */
object IslandHub {
    @Volatile var view: IslandView? = null
    @Volatile var onListenerConnected: (() -> Unit)? = null

    fun notify(item: NotifItem) {
        val v = view ?: return
        v.post { v.showNotif(item) }
    }
}
