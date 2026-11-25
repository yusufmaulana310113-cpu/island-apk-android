package id.island.dynamic

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/** Layar pengaturan: izin, tampilan, tombol uji. UI dibuat lewat kode (tanpa XML). */
class MainActivity : Activity() {

    private lateinit var cfg: Settings
    private lateinit var box: LinearLayout
    private val statusViews = ArrayList<Pair<TextView, () -> Boolean>>()
    private lateinit var mainSwitch: Switch

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        cfg = Settings.load(this)
        val sv = ScrollView(this).apply { setBackgroundColor(Color.rgb(18, 18, 20)); isFillViewport = true }
        box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(40), dp(20), dp(40)) }
        sv.addView(box)
        setContentView(sv)
        build()
    }

    override fun onResume() {
        super.onResume()
        statusViews.forEach { (tv, ok) -> val g = ok(); tv.text = if (g) "✓ Aktif" else "✗ Belum"; tv.setTextColor(if (g) Color.rgb(48, 209, 88) else Color.rgb(255, 105, 97)) }
        mainSwitch.isChecked = cfg.enabled && AndroidSettings.canDrawOverlays(this)
    }

    // ------------------------------------------------------------ helpers
    private fun title(s: String, size: Float = 22f) = TextView(this).apply {
        text = s; textSize = size; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(18), 0, dp(8)); box.addView(this)
    }

    private fun note(s: String) = TextView(this).apply {
        text = s; textSize = 12.5f; setTextColor(Color.rgb(150, 150, 158)); setPadding(0, 0, 0, dp(6)); box.addView(this)
    }

    private fun row(label: String, build: LinearLayout.() -> Unit) {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, dp(6)) }
        r.addView(TextView(this).apply { text = label; textSize = 15f; setTextColor(Color.rgb(235, 235, 240)) },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        r.build(); box.addView(r)
    }

    private fun toggle(label: String, get: () -> Boolean, set: (Boolean) -> Unit) = row(label) {
        addView(Switch(context).apply { isChecked = get(); setOnCheckedChangeListener { _, v -> set(v); cfg.save(context) } })
    }

    private fun slider(label: String, min: Int, max: Int, get: () -> Int, suffix: String, set: (Int) -> Unit) {
        val tv = TextView(this).apply { textSize = 15f; setTextColor(Color.rgb(235, 235, 240)) }
        fun upd(v: Int) { tv.text = "$label: $v$suffix" }
        upd(get())
        box.addView(tv)
        box.addView(SeekBar(this).apply {
            this.max = max - min; progress = get() - min
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, user: Boolean) { upd(p + min); if (user) { set(p + min); cfg.save(context) } }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        })
    }

    private fun perm(label: String, hint: String, granted: () -> Boolean, action: () -> Unit) {
        row(label) {
            val st = TextView(context).apply { textSize = 13f; setPadding(0, 0, dp(10), 0) }
            statusViews.add(st to granted)
            addView(st)
            addView(Button(context).apply { text = "Atur"; isAllCaps = false; setOnClickListener { action() } })
        }
        note(hint)
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; setOnClickListener { onClick() }; box.addView(this)
    }

    // ------------------------------------------------------------ UI
    private fun build() {
        title("Dynamic Island", 28f)
        note("Versi Android dari Dynamic Island v9 (Notch Edition).")
        mainSwitch = Switch(this).apply {
            text = "Aktifkan Dynamic Island"; textSize = 16f; setTextColor(Color.WHITE)
            setOnCheckedChangeListener { _, on -> toggleService(on) }
        }
        box.addView(mainSwitch)

        title("Izin", 18f)
        perm("Tampil di atas aplikasi lain", "Wajib. Untuk menggambar pulau di atas layar.",
            { AndroidSettings.canDrawOverlays(this) }) {
            startActivity(Intent(AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        perm("Akses notifikasi", "Untuk notifikasi, kontrol musik & balas cepat. Bila tombol abu-abu di Android 13+: " +
            "Info aplikasi → ⋮ → \"Izinkan setelan terbatas\", lalu ulangi.",
            { NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName) }) {
            startActivity(Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        if (Build.VERSION.SDK_INT >= 33) perm("Tampilkan notifikasi", "Untuk notifikasi layanan latar belakang.",
            { ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED }) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        if (Build.VERSION.SDK_INT >= 31) perm("Bluetooth", "Opsional. Menampilkan nama perangkat saat terhubung.",
            { ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED }) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 2)
        }

        title("Tampilan", 18f)
        slider("Ukuran", 60, 140, { cfg.scalePct }, "%") { cfg.scalePct = it }
        slider("Jarak dari atas", 0, 60, { cfg.yOffset }, " dp") { cfg.yOffset = it }
        toggle("Mode notch (tepi atas rata + telinga)", { cfg.notchMode }) { cfg.notchMode = it }
        toggle("Cover bulat", { cfg.coverRound }) { cfg.coverRound = it }
        toggle("Tampilkan cuaca", { cfg.showWeather }) { cfg.showWeather = it }
        toggle("Tampilkan notifikasi di pulau", { cfg.showNotifs }) { cfg.showNotifs = it }
        toggle("Sembunyi otomatis", { cfg.autoHide }) { cfg.autoHide = it }
        slider("Jeda sembunyi", 2, 30, { cfg.hideDelay }, " dtk") { cfg.hideDelay = it }

        title("Warna latar", 16f)
        val colors = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4), 0, dp(10)) }
        for (c in Settings.BG_PRESETS) {
            colors.addView(View(this).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c); setStroke(dp(2), Color.rgb(90, 90, 96)) }
                setOnClickListener { cfg.bg = c; cfg.save(this@MainActivity) }
            }, LinearLayout.LayoutParams(dp(38), dp(38)).apply { rightMargin = dp(12) })
        }
        box.addView(colors)

        title("Uji & bantuan", 18f)
        button("Uji notifikasi pulau") {
            val v = IslandHub.view
            if (v == null) toast("Aktifkan dulu Dynamic Island") else
                v.showNotif(NotifItem("demo", packageName, "Pesan", "Dynamic Island", "Ini contoh notifikasi di pulau Android 🎉", null,
                    Color.rgb(37, 211, 102), null, null))
        }
        button("Pengecualian optimasi baterai") {
            startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
        note("Tips: tekan lama pulau untuk membuka layar ini. Geser pulau ke atas untuk menutup panel.")
    }

    private fun toast(s: String) = android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show()

    private fun toggleService(on: Boolean) {
        if (on && !AndroidSettings.canDrawOverlays(this)) {
            mainSwitch.isChecked = false
            toast("Beri izin \"Tampil di atas aplikasi lain\" dulu")
            return
        }
        cfg.enabled = on
        cfg.save(this)
        val i = Intent(this, IslandService::class.java)
        if (on) ContextCompat.startForegroundService(this, i) else stopService(i)
    }
}
