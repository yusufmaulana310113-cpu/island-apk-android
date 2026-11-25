package id.island.dynamic

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.graphics.PixelFormat
import android.graphics.RectF
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings as AndroidSettings
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import androidx.core.content.ContextCompat

/** Foreground service: memegang jendela overlay + semua sumber event (baterai, volume, kecerahan, Bluetooth, media, cuaca). */
class IslandService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private lateinit var lp: WindowManager.LayoutParams
    private var root: Root? = null
    private var view: IslandView? = null
    private var edit: EditText? = null
    private var media: MediaTracker? = null
    private var cfg = Settings()
    private var added = false
    private var lastBrightness = -1
    private var lastBatt = -1

    /** Root: meneruskan ACTION_OUTSIDE (ketuk di luar pulau) ke view. */
    private inner class Root(c: Context) : FrameLayout(c) {
        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) { view?.outsideTouch(); return true }
            return super.dispatchTouchEvent(ev)
        }
    }

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> applyCfg() }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startFg()
        if (!AndroidSettings.canDrawOverlays(this)) { stopSelf(); return }
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        cfg = Settings.load(this)
        buildOverlay()
        registerSources()
        getSharedPreferences(Settings.PREFS, MODE_PRIVATE).registerOnSharedPreferenceChangeListener(prefListener)
        IslandHub.onListenerConnected = { main.post { initMedia() } }
        initMedia()
        pollWeather()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        getSharedPreferences(Settings.PREFS, MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(prefListener)
        IslandHub.onListenerConnected = null
        IslandHub.view = null
        media?.stop()
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
        try { contentResolver.unregisterContentObserver(brightObs) } catch (_: Exception) {}
        if (added) try { wm.removeView(root) } catch (_: Exception) {}
        super.onDestroy()
    }

    // ------------------------------------------------------------ foreground
    private fun startFg() {
        val ch = "island"
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(ch, "Dynamic Island", NotificationManager.IMPORTANCE_MIN).apply { setShowBadge(false) })
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, ch).setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle("Dynamic Island aktif").setContentText("Ketuk untuk membuka pengaturan")
            .setContentIntent(pi).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, n)
    }

    // ------------------------------------------------------------ overlay
    private fun yPx(): Int {
        val d = resources.displayMetrics.density
        return ((if (cfg.notchMode) 0f else 8f) * d + cfg.yOffset * d).toInt()
    }

    private fun buildOverlay() {
        val v = IslandView(this)
        val r = Root(this)
        val et = EditText(this).apply {
            setBackgroundColor(0); setTextColor(0xFFFFFFFF.toInt()); setHintTextColor(0x88FFFFFF.toInt())
            hint = "Balas…"; textSize = 14.5f; isSingleLine = true; setPadding(0, 0, 0, 0)
            imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            visibility = android.view.View.GONE
            setOnEditorActionListener { _, id, _ ->
                if (id == EditorInfo.IME_ACTION_SEND) { sendReply(); true } else false
            }
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) { v.touchedReply() }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        r.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        r.addView(et, FrameLayout.LayoutParams(10, 10))
        root = r; view = v; edit = et

        lp = WindowManager.LayoutParams(
            100, 100, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = yPx()
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            if (Build.VERSION.SDK_INT >= 30) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            else if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        v.onWindow = { w, h ->
            if (lp.width != w || lp.height != h) {
                lp.width = w; lp.height = h
                if (added) try { wm.updateViewLayout(r, lp) } catch (_: Exception) {}
            }
        }
        v.onAction = { name, arg -> handleAction(name, arg) }
        v.onFocus = { on -> setFocusable(on) }
        v.onReplyField = { rect -> placeEdit(rect) }
        v.cfg = cfg
        wm.addView(r, lp)
        added = true
        IslandHub.view = v
    }

    private fun setFocusable(on: Boolean) {
        lp.flags = if (on) lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        else lp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        if (added) try { wm.updateViewLayout(root, lp) } catch (_: Exception) {}
        if (!on) {
            edit?.let {
                it.setText(""); it.clearFocus()
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(it.windowToken, 0)
            }
        }
    }

    private fun placeEdit(rect: RectF?) {
        val et = edit ?: return
        if (rect == null) { et.visibility = android.view.View.GONE; return }
        val p = FrameLayout.LayoutParams(rect.width().toInt(), rect.height().toInt())
        p.leftMargin = rect.left.toInt(); p.topMargin = rect.top.toInt()
        et.layoutParams = p
        if (et.visibility != android.view.View.VISIBLE) {
            et.visibility = android.view.View.VISIBLE
            et.postDelayed({
                et.requestFocus()
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(et, InputMethodManager.SHOW_IMPLICIT)
            }, 120)
        }
    }

    private fun applyCfg() {
        cfg = Settings.load(this)
        if (!cfg.enabled) { stopSelf(); return }
        lp.y = yPx()
        view?.cfg = cfg
        if (added) try { wm.updateViewLayout(root, lp) } catch (_: Exception) {}
        pollWeatherSoon()
    }

    // ------------------------------------------------------------ aksi dari view
    private fun handleAction(name: String, arg: Any?) {
        when (name) {
            "prev" -> media?.prev()
            "next" -> media?.next()
            "play" -> media?.toggle()
            "seek" -> media?.seekTo(arg as Long)
            "open_media" -> media?.openApp()
            "open_notif" -> (arg as? NotifItem)?.open?.let { try { it.send() } catch (_: Exception) {} }
            "reply_send" -> sendReply()
            "settings" -> startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "lyrics_need" -> {
                @Suppress("UNCHECKED_CAST")
                val (key, ta, dur) = arg as Triple<String, Pair<String, String>, Long>
                Thread {
                    val l = Net.lyrics(ta.first, ta.second, dur)
                    main.post { view?.setLyrics(key, l) }
                }.start()
            }
        }
    }

    private fun sendReply() {
        val v = view ?: return
        val item = v.currentReplyItem() ?: return
        val action = item.reply ?: return
        val text = edit?.text?.toString()?.trim().orEmpty()
        if (text.isEmpty() || v.replyBusy()) return
        v.replySending()
        placeEdit(null)
        try {
            val ri = action.remoteInputs ?: throw IllegalStateException("no remote input")
            val intent = Intent()
            val b = Bundle()
            for (r in ri) b.putCharSequence(r.resultKey, text)
            RemoteInput.addResultsToIntent(ri, intent, b)
            action.actionIntent.send(this, 0, intent)
            edit?.setText("")
            v.replyDone(true)
        } catch (e: Exception) {
            v.replyDone(false)
        }
    }

    // ------------------------------------------------------------ media
    private fun initMedia() {
        val v = view ?: return
        if (media == null) media = MediaTracker(this, v)
        media?.start()
    }

    // ------------------------------------------------------------ sumber event
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val v = view ?: return
            when (i.action) {
                Intent.ACTION_BATTERY_CHANGED -> {
                    val lvl = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val sc = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                    if (lvl >= 0) { lastBatt = lvl * 100 / sc; v.setBattery(lastBatt) }
                }
                Intent.ACTION_POWER_CONNECTED -> v.showCharge(lastBatt.coerceAtLeast(0))
                "android.media.VOLUME_CHANGED_ACTION" -> {
                    val type = i.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1)
                    val am = getSystemService(AUDIO_SERVICE) as AudioManager
                    if (type < 0) return
                    val mx = am.getStreamMaxVolume(type).coerceAtLeast(1)
                    val pct = am.getStreamVolume(type) * 100 / mx
                    val title = when (type) {
                        AudioManager.STREAM_MUSIC -> "Volume"
                        AudioManager.STREAM_RING -> "Dering"
                        AudioManager.STREAM_ALARM -> "Alarm"
                        AudioManager.STREAM_VOICE_CALL -> "Panggilan"
                        AudioManager.STREAM_NOTIFICATION -> "Notifikasi"
                        else -> return
                    }
                    v.showHud("vol", pct, title)
                }
                BluetoothDevice.ACTION_ACL_CONNECTED, BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    val on = i.action == BluetoothDevice.ACTION_ACL_CONNECTED
                    val name = try {
                        @Suppress("DEPRECATION")
                        (i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE))?.name
                    } catch (e: SecurityException) { null } ?: "Perangkat Bluetooth"
                    v.showHud("bt", -1, (if (on) "Terhubung · " else "Terputus · ") + name)
                }
            }
        }
    }

    private val brightObs = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) {
            try {
                val raw = AndroidSettings.System.getInt(contentResolver, AndroidSettings.System.SCREEN_BRIGHTNESS)
                val pct = raw * 100 / 255
                if (lastBrightness >= 0 && pct != lastBrightness) view?.showHud("bri", pct, "Kecerahan")
                lastBrightness = pct
            } catch (_: Exception) {}
        }
    }

    private fun registerSources() {
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED); addAction(Intent.ACTION_POWER_CONNECTED)
            addAction("android.media.VOLUME_CHANGED_ACTION")
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED); addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        ContextCompat.registerReceiver(this, receiver, f, ContextCompat.RECEIVER_EXPORTED)
        try { lastBrightness = AndroidSettings.System.getInt(contentResolver, AndroidSettings.System.SCREEN_BRIGHTNESS) * 100 / 255 } catch (_: Exception) {}
        contentResolver.registerContentObserver(AndroidSettings.System.getUriFor(AndroidSettings.System.SCREEN_BRIGHTNESS), false, brightObs)
    }

    // ------------------------------------------------------------ cuaca
    private var weatherBusy = false
    private val weatherRun = Runnable { pollWeather() }

    private fun pollWeatherSoon() { if (cfg.showWeather) { main.removeCallbacks(weatherRun); main.postDelayed(weatherRun, 300) } }

    private fun pollWeather() {
        main.removeCallbacks(weatherRun)
        if (!cfg.showWeather) { main.postDelayed(weatherRun, 60_000); return }
        if (weatherBusy) return
        weatherBusy = true
        Thread {
            val r = Net.weather()
            main.post {
                weatherBusy = false
                if (r != null) view?.setWeather(r.first, r.second)
                main.postDelayed(weatherRun, if (r != null) 45L * 60_000 else 60_000)
            }
        }.start()
    }
}
