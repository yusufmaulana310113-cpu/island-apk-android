package id.island.dynamic

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock

/** Memantau sesi media aktif (Spotify, YouTube Music, dll). Butuh akses notifikasi (listener) untuk getActiveSessions. */
class MediaTracker(private val ctx: Context, private val view: IslandView) {
    private val msm = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    private val cn = ComponentName(ctx, IslandListener::class.java)
    private var ctl: MediaController? = null
    private var started = false
    private val icons = LruMap<String, android.graphics.Bitmap?>(8)

    private val cb = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = refresh()
        override fun onMetadataChanged(metadata: MediaMetadata?) = refresh()
        override fun onSessionDestroyed() { pick(safeSessions()) }
    }
    private val sl = MediaSessionManager.OnActiveSessionsChangedListener { pick(it ?: emptyList()) }

    private fun safeSessions(): List<MediaController> = try { msm.getActiveSessions(cn) } catch (e: SecurityException) { emptyList() }

    fun start(): Boolean {
        if (started) return true
        return try {
            msm.addOnActiveSessionsChangedListener(sl, cn)
            started = true
            pick(msm.getActiveSessions(cn))
            true
        } catch (e: SecurityException) { false }
    }

    fun stop() {
        try { msm.removeOnActiveSessionsChangedListener(sl) } catch (_: Exception) {}
        ctl?.unregisterCallback(cb)
        ctl = null
        started = false
    }

    private fun isPlaying(c: MediaController) = c.playbackState?.state.let {
        it == PlaybackState.STATE_PLAYING || it == PlaybackState.STATE_BUFFERING
    }

    private fun pick(list: List<MediaController>) {
        val best = list.firstOrNull { isPlaying(it) && it.packageName.contains("spotify") }
            ?: list.firstOrNull { isPlaying(it) }
            ?: list.firstOrNull { it.sessionToken == ctl?.sessionToken }
            ?: list.firstOrNull { it.metadata != null }
        if (best?.sessionToken != ctl?.sessionToken) {
            ctl?.unregisterCallback(cb)
            ctl = best
            best?.registerCallback(cb)
        }
        refresh()
    }

    private fun srcIcon(pkg: String): android.graphics.Bitmap? = icons.getOrPut(pkg) {
        try { drawableToBitmap(ctx.packageManager.getApplicationIcon(pkg), 48) } catch (_: Exception) { null }
    }

    private fun refresh() {
        val c = ctl
        val md = c?.metadata
        val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: ""
        if (c == null || title.isBlank()) { view.setMedia(null); return }
        val artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) ?: ""
        val cover = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val ps = c.playbackState
        val pos = ps?.position?.takeIf { it >= 0 } ?: 0L
        view.setMedia(
            MediaInfo(
                pkg = c.packageName, title = title, artist = artist, cover = cover, srcIcon = srcIcon(c.packageName),
                playing = isPlaying(c), durMs = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L,
                posMs = pos, stampMs = ps?.lastPositionUpdateTime?.takeIf { it > 0 } ?: SystemClock.elapsedRealtime(),
                speed = ps?.playbackSpeed ?: 1f,
            )
        )
    }

    fun prev() { ctl?.transportControls?.skipToPrevious() }
    fun next() { ctl?.transportControls?.skipToNext() }
    fun toggle() { ctl?.let { if (isPlaying(it)) it.transportControls.pause() else it.transportControls.play() } }
    fun seekTo(ms: Long) { ctl?.transportControls?.seekTo(ms) }
    fun openApp() {
        try { ctl?.sessionActivity?.send() } catch (_: Exception) {
            ctl?.packageName?.let { p -> ctx.packageManager.getLaunchIntentForPackage(p)?.let {
                it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK); ctx.startActivity(it) } }
        }
    }
}
