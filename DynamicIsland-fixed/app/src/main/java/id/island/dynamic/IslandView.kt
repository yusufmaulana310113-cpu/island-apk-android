package id.island.dynamic

import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Kapsul Dynamic Island. Semua gambar dalam satuan "logis" (1 unit = 1dp * skala pengguna),
 * sehingga tata letak mengikuti versi desktop. Gerak memakai pegas asimetris (membesar = bouncy,
 * mengecil = rapat tanpa overshoot). Frame hanya berjalan saat ada animasi / konten bergerak.
 */
class IslandView(ctx: Context) : View(ctx) {

    // ---- dihubungkan oleh IslandService ----
    var onWindow: ((Int, Int) -> Unit)? = null              // ukuran jendela (px) yang dibutuhkan
    var onAction: ((String, Any?) -> Unit)? = null
    var onReplyField: ((RectF?) -> Unit)? = null            // kotak EditText (px) atau null = sembunyi
    var onFocus: ((Boolean) -> Unit)? = null

    var cfg = Settings()
        set(v) { field = v; onCfg() }

    // ---- konstanta (mengikuti versi Windows) ----
    private companion object {
        const val AUTO_SHRINK_MS = 5000L
        const val SONG_POPUP_MS = 4500L
        const val NOTIF_MS = 5500L
        const val CHARGE_MS = 4500L
        const val HUD_MS = 1600L
        const val REPLY_IDLE_MS = 30000L
        const val REPLY_RESULT_MS = 1500L
        const val SPRING_GROW_R = 0.40f; const val SPRING_GROW_Z = 0.56f
        const val SPRING_SHRINK_R = 0.26f; const val SPRING_SHRINK_Z = 1.0f
        const val MAX_CORNER = 40f
        const val NOTCH_CORNER = 30f
        const val EAR = 11f
        const val FLOAT_MARGIN = 8f
        const val MAX_W = 344f
        const val TICK_MS = 33L
        val WHITE = Color.WHITE
        val GRAY = Color.rgb(160, 160, 166)
    }

    private val density = resources.displayMetrics.density
    private val h = Handler(Looper.getMainLooper())

    // ---- state ----
    private var state = "hidden"
    private var stateAt = SystemClock.uptimeMillis()
    private var hiddenFlag = false
    private val wSp = Spring(64f, 64f)
    private val hSp = Spring(10f, 10f)
    private var tw = 64f
    private var th = 10f
    private var anim = 0f              // jam animasi (detik)
    private var acc = 0f

    // media
    private var media: MediaInfo? = null
    private var hasTrack = false
    private var playing = false
    private var trackKey = ""
    private var accent = Color.rgb(120, 130, 255)
    private var scrubbing = false
    private var scrubFrac = 0f
    private var lyrics: List<Pair<Long, String>>? = null
    private var lyricsKey = ""
    private var lyricsState = 0        // 0 belum, 1 memuat, 2 ada, 3 tidak ada
    private var basePos = 0L
    private var baseStamp = 0L

    // notif / reply
    private var notif: NotifItem? = null
    private var replyItem: NotifItem? = null
    private var replyStatus = ""       // "", "sending", "ok", "err"
    private var lastFieldPx: RectF? = null

    // charge / hud / lain-lain
    private var battery = -1
    private var chargePct = 0
    private var hudKind = "vol"
    private var hudValue = 0
    private var hudTitle = ""
    private var dnd = false
    private var wxTemp: Int? = null
    private var wxKind = "cloud"

    // interaksi
    private val buttons = ArrayList<Pair<String, RectF>>()
    private var pressed: String? = null
    private var downX = 0f; private var downY = 0f
    private var moved = false
    private var swiped = false
    private var longFired = false
    private var seekRect = RectF()

    // ---- paint ----
    private val P = Paint(Paint.ANTI_ALIAS_FLAG)
    private val T = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val shape = Path()
    private val tmp = Path()
    private val rf = RectF()
    private var ca = 1f                // alpha konten (fade-in)
    private var contentLeft = 0f
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dateFmt = SimpleDateFormat("EEE d MMM", Locale("id", "ID"))
    private val bold = Typeface.create("sans-serif-medium", Typeface.BOLD)
    private val regular = Typeface.create("sans-serif", Typeface.NORMAL)

    // ---- frame loop ----
    private val choreo = Choreographer.getInstance()
    private var running = false
    private var lastNs = 0L
    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(ns: Long) {
            val dt = if (lastNs == 0L) 1f / 60f else min(0.05f, (ns - lastNs) / 1e9f)
            lastNs = ns
            step(dt)
            if (needsFrames()) choreo.postFrameCallback(this) else { running = false; lastNs = 0L }
        }
    }
    private val clockTick = object : Runnable {
        override fun run() { if (!running) invalidate(); h.postDelayed(this, 1000) }
    }

    private val restRun = Runnable { toBase() }
    private val hideRun = Runnable { hiddenFlag = true; if (state == "idle" || state == "music") toBase() }
    private val replyIdleRun = Runnable { if (state == "reply") closeReply() }
    private val longRun = Runnable {
        if (pressed == null && !moved && (state == "idle" || state == "music")) { longFired = true; onAction?.invoke("settings", null) }
    }

    // ============================================================ util
    private fun pt(x: Float) = x * 1.3f

    private fun unit(): Float {
        val sw = resources.displayMetrics.widthPixels.toFloat()
        val fit = min(1f, (sw - 12f * density) / (MAX_W * density * cfg.scale))
        return density * cfg.scale * fit
    }

    private fun sizeFor(s: String): Pair<Float, Float> = when (s) {
        "idle" -> 168f to 32f
        "music" -> 236f to 32f
        "player" -> MAX_W to 176f
        "notif" -> MAX_W to notifHeight()
        "charge" -> 280f to 56f
        "hud" -> 300f to 60f
        "hidden" -> 64f to 10f
        "reply" -> MAX_W to 124f
        "lyrics" -> MAX_W to 156f
        else -> 168f to 32f
    }

    private fun notifHeight(): Float {
        val n = notif ?: return 78f
        return if (bodyLines(n).size >= 2) 94f else 78f
    }

    private fun bodyLines(n: NotifItem): List<String> {
        T.typeface = regular; T.textSize = pt(10f)
        val avail = MAX_W - (18f + 42f + 14f) - 20f - (if (n.reply != null) 40f else 0f)
        return wrapLines(n.body, avail, 2)
    }

    private fun wrapLines(text: String, width: Float, maxLines: Int): List<String> {
        val t = text.trim().replace(Regex("\\s+"), " ")
        if (t.isEmpty() || width < 10f) return emptyList()
        val lines = ArrayList<String>()
        var cur = ""
        for (w0 in t.split(" ")) {
            var word = w0
            val trial = if (cur.isEmpty()) word else "$cur $word"
            if (T.measureText(trial) <= width) { cur = trial; continue }
            if (cur.isNotEmpty()) { lines.add(cur); cur = "" }
            while (word.length > 1 && T.measureText(word) > width) {
                var n = word.length
                while (n > 1 && T.measureText(word, 0, n) > width) n--
                lines.add(word.substring(0, n)); word = word.substring(n)
            }
            cur = word
        }
        if (cur.isNotEmpty()) lines.add(cur)
        if (lines.size > maxLines) {
            var last = lines[maxLines - 1]
            while (last.isNotEmpty() && T.measureText("$last…") > width) last = last.dropLast(1)
            return lines.take(maxLines - 1) + (last + "…")
        }
        return lines
    }

    private fun col(c: Int, mul: Float = 1f): Int {
        val a = (Color.alpha(c) * ca * mul).toInt().coerceIn(0, 255)
        return Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))
    }

    private fun txt(c: Canvas, s: String, x: Float, cy: Float, size: Float, isBold: Boolean, color: Int,
                    align: Paint.Align = Paint.Align.LEFT, maxW: Float = 0f) {
        T.typeface = if (isBold) bold else regular
        T.textSize = size
        T.color = col(color)
        T.textAlign = align
        val str = if (maxW > 0f) TextUtils.ellipsize(s, T, maxW, TextUtils.TruncateAt.END).toString() else s
        val fm = T.fontMetrics
        c.drawText(str, x, cy - (fm.ascent + fm.descent) / 2f, T)
    }

    private fun measure(s: String, size: Float, isBold: Boolean): Float {
        T.typeface = if (isBold) bold else regular; T.textSize = size
        return T.measureText(s)
    }

    private fun btn(name: String, r: RectF) { buttons.add(name to RectF(r).apply { offset(contentLeft, 0f) }) }

    /** efek tekan 0.86x seperti versi desktop; panggil di dalam save()/restore(). */
    private fun pressFx(c: Canvas, name: String, r: RectF) {
        if (pressed == name) {
            val cx = r.centerX(); val cy = r.centerY()
            c.translate(cx, cy); c.scale(0.86f, 0.86f); c.translate(-cx, -cy)
        }
    }

    private fun fill(color: Int, style: Paint.Style = Paint.Style.FILL, sw: Float = 0f) {
        P.shader = null; P.color = col(color); P.style = style; P.strokeWidth = sw
        P.strokeCap = Paint.Cap.ROUND; P.strokeJoin = Paint.Join.ROUND
    }

    // ============================================================ state machine
    private fun base(): String = when {
        cfg.autoHide && hiddenFlag -> "hidden"
        hasTrack && playing -> "music"
        else -> "idle"
    }

    private fun toBase() { h.removeCallbacks(restRun); go(base()); armHide() }

    private fun schedule(ms: Long) { h.removeCallbacks(restRun); h.postDelayed(restRun, ms) }

    private fun armHide() {
        h.removeCallbacks(hideRun)
        if (cfg.autoHide && (state == "idle" || state == "music")) h.postDelayed(hideRun, cfg.hideDelay * 1000L)
    }

    private fun go(s: String) {
        if (s == state) { fit(); kick(); return }
        val old = state
        state = s
        stateAt = SystemClock.uptimeMillis()
        if (old == "reply" && s != "reply") { onReplyField?.invoke(null); onFocus?.invoke(false); lastFieldPx = null }
        val (w, hh) = sizeFor(s)
        tw = w; th = hh
        wSp.target = w; hSp.target = hh
        fit(); kick()
    }

    private fun onCfg() {
        if (!cfg.autoHide) { hiddenFlag = false; h.removeCallbacks(hideRun) }
        if (state != "hidden" || !cfg.autoHide) {
            if (state == "hidden") go(base()) else { val (w, hh) = sizeFor(state); wSp.target = w; hSp.target = hh }
        }
        armHide(); fit(); kick(); invalidate()
    }

    private fun firstShow() { go("idle"); armHide() }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        h.post { if (state == "hidden" && !(cfg.autoHide && hiddenFlag)) firstShow() }
        h.postDelayed(clockTick, 1000)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        h.removeCallbacksAndMessages(null)
        choreo.removeFrameCallback(frame); running = false; lastNs = 0L
    }

    // ============================================================ frame
    private fun kick() {
        if (!running) { running = true; choreo.postFrameCallback(frame) }
    }

    private fun fade() = (SystemClock.uptimeMillis() - stateAt) < 280

    private fun springing() = !(wSp.settled && hSp.settled)

    private fun needsFrames(): Boolean = springing() || fade() || when (state) {
        "music" -> playing
        "player" -> true
        "lyrics" -> playing
        "charge" -> true
        else -> false
    }

    private fun step(dt: Float) {
        anim += dt
        if (springing()) {
            val grow = wSp.target * hSp.target >= wSp.x * hSp.x
            val r = if (grow) SPRING_GROW_R else SPRING_SHRINK_R
            val z = if (grow) SPRING_GROW_Z else SPRING_SHRINK_Z
            wSp.step(dt, r, z); hSp.step(dt, r, z)
            if (wSp.settled) wSp.snap()
            if (hSp.settled) hSp.snap()
            if (!springing()) fit()                       // selesai: ciutkan jendela
            invalidate()
        } else {
            acc += dt
            if (fade() || acc >= TICK_MS / 1000f) { acc = 0f; invalidate() }
        }
    }

    /** Minta ukuran jendela: saat bergerak = maks(sekarang, target) agar tidak terpotong. */
    private fun fit() {
        val U = unit()
        var bw = tw; var bh = th
        if (springing()) { bw = max(bw, wSp.x); bh = max(bh, hSp.x) }
        val ear = if (cfg.notchMode) EAR else 0f
        val minH = if (state == "hidden") 22f else 0f
        onWindow?.invoke(((bw + 2 * ear) * U).toInt() + 2, (max(bh, minH) * U).toInt() + 2)
    }

    // ============================================================ API publik
    fun setWeather(temp: Int?, kind: String) { wxTemp = temp; wxKind = kind; if (!running) invalidate() }

    fun setBattery(pct: Int) { battery = pct; if (!running) invalidate() }

    fun setMedia(m: MediaInfo?) {
        val wasKey = trackKey
        media = m
        hasTrack = m != null
        playing = m?.playing == true
        if (m != null) {
            basePos = m.posMs; baseStamp = m.stampMs
            trackKey = m.title + "\u0000" + m.artist
            accent = accentOf(m.cover)
        } else trackKey = ""
        if (trackKey != wasKey) { lyrics = null; lyricsKey = ""; lyricsState = 0 }
        val busy = state == "notif" || state == "reply" || state == "charge" || state == "hud"
        if (m != null && playing && trackKey != wasKey && !busy) {
            hiddenFlag = false
            go("player"); schedule(SONG_POPUP_MS)       // pop-up lagu baru
        } else if (state == "idle" || state == "music" || state == "hidden") {
            if (playing) hiddenFlag = false
            toBase()
        } else if (state == "lyrics" && !hasTrack) toBase()
        if (state == "lyrics" && lyricsState == 0) requestLyrics()
        kick(); invalidate()
    }

    fun showNotif(n: NotifItem) {
        if (dnd || !cfg.showNotifs || state == "reply") return
        notif = n; hiddenFlag = false
        h.removeCallbacks(hideRun)
        val sameState = state == "notif"
        go("notif")
        if (sameState) { val (w, hh) = sizeFor("notif"); tw = w; th = hh; hSp.target = hh; stateAt = SystemClock.uptimeMillis(); fit(); kick() }
        schedule(NOTIF_MS)
    }

    fun showCharge(pct: Int) {
        if (dnd || state == "reply" || state == "player" || state == "lyrics") return
        chargePct = pct; hiddenFlag = false
        go("charge"); schedule(CHARGE_MS)
    }

    /** value 0..100, atau -1 bila tanpa bar (mis. status Bluetooth). kind: vol | bri | bt */
    fun showHud(kind: String, value: Int, title: String) {
        if (dnd && kind != "vol") return
        if (state == "reply" || state == "player" || state == "lyrics" || state == "notif") return
        hudKind = kind; hudValue = value; hudTitle = title; hiddenFlag = false
        if (state != "hud") stateAt = SystemClock.uptimeMillis()
        go("hud"); schedule(HUD_MS)
        invalidate()
    }

    fun setLyrics(key: String, list: List<Pair<Long, String>>?) {
        if (key != trackKey) return
        lyrics = list; lyricsKey = key; lyricsState = if (list.isNullOrEmpty()) 3 else 2
        invalidate()
    }

    fun replyDone(ok: Boolean) {
        replyStatus = if (ok) "ok" else "err"
        invalidate()
        h.removeCallbacks(replyIdleRun)
        h.postDelayed({ if (state == "reply") closeReply() }, REPLY_RESULT_MS)
    }

    private fun requestLyrics() {
        val m = media ?: return
        if (lyricsState == 1 || lyricsKey == trackKey && lyricsState != 0) return
        lyricsState = 1
        onAction?.invoke("lyrics_need", Triple(trackKey, m.title to m.artist, m.durMs / 1000))
    }

    private fun curPos(): Long {
        val m = media ?: return 0L
        val p = if (playing) basePos + ((SystemClock.elapsedRealtime() - baseStamp) * m.speed).toLong() else basePos
        return p.coerceIn(0L, if (m.durMs > 0) m.durMs else Long.MAX_VALUE)
    }

    private fun openReply() {
        val n = notif ?: return
        if (n.reply == null) return
        replyItem = n; replyStatus = ""
        h.removeCallbacks(restRun)
        go("reply")
        onFocus?.invoke(true)
        h.removeCallbacks(replyIdleRun); h.postDelayed(replyIdleRun, REPLY_IDLE_MS)
    }

    fun closeReply() {
        h.removeCallbacks(replyIdleRun)
        replyStatus = ""
        toBase()
    }

    fun touchedReply() { h.removeCallbacks(replyIdleRun); h.postDelayed(replyIdleRun, REPLY_IDLE_MS) }
    fun replySending() { replyStatus = "sending"; invalidate() }
    fun currentReplyItem() = replyItem
    fun replyBusy() = replyStatus == "sending"

    fun outsideTouch() {
        when (state) {
            "player", "lyrics" -> toBase()
            "reply" -> if (replyStatus != "sending") closeReply()
        }
    }

    // ============================================================ draw
    override fun onDraw(c: Canvas) {
        buttons.clear()
        val U = unit()
        c.save()
        c.scale(U, U)
        val vw = width / U
        val bw = max(1f, wSp.x)
        val bh = max(1f, hSp.x)
        val notch = cfg.notchMode
        val ear = if (notch) EAR else 0f
        val ox = (vw - bw) / 2f
        shape.reset()
        if (notch) {
            notchPath(shape, bw + 2 * ear, bh, ear, min(NOTCH_CORNER, bh * 0.5f))
            shape.offset(ox - ear, 0f)
        } else {
            smoothRect(shape, ox, 0f, bw, bh, min(MAX_CORNER, bh * 0.5f))
        }
        P.shader = null; P.style = Paint.Style.FILL; P.color = cfg.bg
        c.drawPath(shape, P)

        ca = clamp((SystemClock.uptimeMillis() - stateAt - 60) / 200f)
        val sizeProgress = clamp((min(bw / tw, bh / th) - 0.55f) / 0.45f)
        ca *= sizeProgress
        c.save()
        c.clipPath(shape)
        contentLeft = (vw - tw) / 2f
        c.translate(contentLeft, 0f)
        if (ca > 0.01f) paintState(c, state, tw, th)
        c.restore()

        if (!notch) {                                   // bingkai tipis
            P.style = Paint.Style.STROKE; P.strokeWidth = 0.6f; P.color = Color.argb(30, 255, 255, 255)
            c.drawPath(shape, P)
        }
        c.restore()
    }

    private fun paintState(c: Canvas, s: String, w: Float, hh: Float) {
        when (s) {
            "idle" -> paintIdle(c, w, hh)
            "music" -> paintMusic(c, w, hh)
            "player" -> paintPlayer(c, w, hh)
            "notif" -> paintNotif(c, w, hh)
            "reply" -> paintReply(c, w, hh)
            "charge" -> paintCharge(c, w, hh)
            "hud" -> paintHud(c, w, hh)
            "lyrics" -> paintLyrics(c, w, hh)
        }
    }

    // ---------- idle
    private fun paintIdle(c: Canvas, w: Float, hh: Float) {
        val cy = hh / 2f
        val now = Date()
        val time = timeFmt.format(now)
        val f1 = pt(10.5f)
        txt(c, time, 14f, cy, f1, true, WHITE)
        if (dnd) drawMoon(c, RectF(14f + measure(time, f1, true) + 6f, cy - 5f, 14f + measure(time, f1, true) + 16f, cy + 5f), Color.rgb(255, 159, 10))
        var rx = w - 14f
        if (cfg.showWeather && wxTemp != null) {
            val s = "${wxTemp}°"
            txt(c, s, rx, cy, pt(9.5f), true, Color.rgb(235, 235, 240), Paint.Align.RIGHT)
            rx -= measure(s, pt(9.5f), true) + 4f
            drawWeather(c, RectF(rx - 14f, cy - 7f, rx, cy + 7f), wxKind)
        } else if (battery >= 0) {
            txt(c, "$battery%", rx, cy, pt(9f), false, GRAY, Paint.Align.RIGHT)
        }
    }

    private fun drawWeather(c: Canvas, r: RectF, kind: String) {
        c.save()
        val x = r.left; val y = r.top
        if (kind == "sun") {
            val cx = r.centerX(); val cy = r.centerY()
            fill(Color.rgb(255, 204, 10)); c.drawCircle(cx, cy, 3.4f, P)
            fill(Color.rgb(255, 204, 10), Paint.Style.STROKE, 1.2f)
            for (i in 0 until 8) {
                val a = i * Math.PI / 4.0
                c.drawLine(cx + 5.2f * cos(a).toFloat(), cy + 5.2f * sin(a).toFloat(), cx + 6.8f * cos(a).toFloat(), cy + 6.8f * sin(a).toFloat(), P)
            }
        } else {
            val dy = if (kind == "rain") -1.3f else 0.4f
            fill(if (kind == "cloud") Color.rgb(205, 210, 222) else Color.rgb(150, 165, 192))
            val k = r.width() / 16f
            c.drawRoundRect(x + 1f * k, y + 7f * k + dy, x + 15f * k, y + 13f * k + dy, 3f, 3f, P)
            c.drawCircle(x + 5.7f * k, y + 6.8f * k + dy, 3.2f * k, P)
            c.drawCircle(x + 10.2f * k, y + 5.4f * k + dy, 3.2f * k, P)
            if (kind == "rain") {
                fill(Color.rgb(80, 160, 255), Paint.Style.STROKE, 1.3f)
                for (dx in floatArrayOf(4f, 8f, 12f)) c.drawLine(x + dx * k, y + 12.5f * k, x + (dx - 1.2f) * k, y + 15.5f * k, P)
            }
        }
        c.restore()
    }

    private fun drawMoon(c: Canvas, r: RectF, color: Int) {
        tmp.reset()
        val a = Path().apply { addCircle(r.centerX(), r.centerY(), r.width() / 2f, Path.Direction.CW) }
        val b = Path().apply { addCircle(r.centerX() + r.width() * 0.32f, r.centerY() - r.width() * 0.2f, r.width() * 0.42f, Path.Direction.CW) }
        tmp.op(a, b, Path.Op.DIFFERENCE)
        fill(color); c.drawPath(tmp, P)
    }

    // ---------- cover / wave / marquee
    private fun drawArt(c: Canvas, r: RectF, radius: Float) {
        val rad = if (cfg.coverRound) r.width() / 2f else radius
        c.save()
        tmp.reset(); tmp.addRoundRect(r, rad, rad, Path.Direction.CW)
        c.clipPath(tmp)
        val bmp = media?.cover
        if (bmp != null) {
            val s = min(bmp.width, bmp.height)
            val src = Rect((bmp.width - s) / 2, (bmp.height - s) / 2, (bmp.width + s) / 2, (bmp.height + s) / 2)
            P.shader = null; P.style = Paint.Style.FILL; P.alpha = (255 * ca).toInt()
            c.drawBitmap(bmp, src, r, P)
        } else {
            P.style = Paint.Style.FILL
            P.shader = LinearGradient(r.left, r.top, r.right, r.bottom, col(accent), col(Color.rgb(40, 40, 60)), Shader.TileMode.CLAMP)
            c.drawRect(r, P); P.shader = null
            fill(WHITE, Paint.Style.FILL)
            val cx = r.centerX(); val cy = r.centerY(); val k = r.width() / 24f
            c.drawCircle(cx - 3f * k, cy + 4f * k, 3f * k, P)
            fill(WHITE, Paint.Style.STROKE, 1.6f * k)
            c.drawLine(cx, cy + 4f * k, cx, cy - 6f * k, P)
            c.drawLine(cx, cy - 6f * k, cx + 5f * k, cy - 4f * k, P)
        }
        c.restore()
    }

    private fun drawSourceBadge(c: Canvas, cx: Float, cy: Float) {
        val ic = media?.srcIcon ?: return
        val r = RectF(cx - 8f, cy - 8f, cx + 8f, cy + 8f)
        fill(cfg.bg); c.drawCircle(cx, cy, 10f, P)
        c.save()
        tmp.reset(); tmp.addRoundRect(r, 4f, 4f, Path.Direction.CW); c.clipPath(tmp)
        P.shader = null; P.style = Paint.Style.FILL; P.alpha = (255 * ca).toInt()
        c.drawBitmap(ic, null, r, P)
        c.restore()
    }

    private fun drawWave(c: Canvas, r: RectF, bars: Int, bw: Float, gap: Float) {
        fill(accent)
        val total = bars * bw + (bars - 1) * gap
        var x = r.centerX() - total / 2f
        for (i in 0 until bars) {
            val k = if (playing) 0.28f + 0.72f * abs(sin(anim * (3.2f + i * 0.45f) + i * 0.9f)) else 0.22f
            val bh = max(bw, r.height() * k)
            rf.set(x, r.centerY() - bh / 2f, x + bw, r.centerY() + bh / 2f)
            c.drawRoundRect(rf, bw / 2f, bw / 2f, P)
            x += bw + gap
        }
    }

    private fun marquee(c: Canvas, s: String, r: RectF, size: Float, isBold: Boolean, color: Int) {
        val tw0 = measure(s, size, isBold)
        if (tw0 <= r.width()) { txt(c, s, r.left, r.centerY(), size, isBold, color); return }
        c.save(); c.clipRect(r)
        val gap = 34f
        val off = (max(0f, anim - 1.4f) * 30f) % (tw0 + gap)
        txt(c, s, r.left - off, r.centerY(), size, isBold, color)
        txt(c, s, r.left - off + tw0 + gap, r.centerY(), size, isBold, color)
        c.restore()
    }

    private fun title() = media?.title ?: ""
    private fun artist() = media?.artist ?: ""

    // ---------- music pill
    private fun paintMusic(c: Canvas, w: Float, hh: Float) {
        val cy = hh / 2f
        drawArt(c, RectF(8f, cy - 11f, 30f, cy + 11f), 6f)
        drawWave(c, RectF(w - 8f - 26f, cy - 8f, w - 8f, cy + 8f), 5, 3f, 2.2f)
    }

    // ---------- player
    private fun paintPlayer(c: Canvas, w: Float, hh: Float) {
        val acc = accent
        P.style = Paint.Style.FILL
        P.shader = RadialGradient(80f, 54f, 190f, intArrayOf(col(acc, if (playing) 0.33f else 0.19f), col(acc, 0f)), null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, hh, P); P.shader = null

        val art = RectF(20f, 18f, 88f, 86f)
        for ((i, a) in listOf(4 to 12, 3 to 18, 2 to 26, 1 to 34)) {
            fill(Color.argb(a, 0, 0, 0))
            val rad = (if (cfg.coverRound) 34f else 14f) + i
            c.drawRoundRect(art.left - i, art.top - i + 4f, art.right + i, art.bottom + i + 4f, rad, rad, P)
        }
        drawArt(c, art, 14f)
        drawSourceBadge(c, art.right - 3f, art.bottom - 3f)
        drawWave(c, RectF(w - 20f - 36f, 22f, w - 20f, 46f), 7, 3f, 2f)
        val tx = art.right + 14f
        val tww = w - 20f - 36f - 12f - tx
        marquee(c, title(), RectF(tx, 23f, tx + tww, 45f), pt(11.5f), true, WHITE)
        marquee(c, artist(), RectF(tx, 47f, tx + tww, 67f), pt(9.5f), false, GRAY)

        // scrubber
        val dur = media?.durMs ?: 0L
        if (dur > 0) {
            val bx0 = 20f; val bx1 = w - 20f; val by = 106f
            val active = scrubbing || pressed == "seek"
            val barH = if (active) 7f else 4f
            fill(Color.argb(56, 255, 255, 255))
            c.drawRoundRect(bx0, by - barH / 2, bx1, by + barH / 2, barH / 2, barH / 2, P)
            val pos = if (scrubbing) (scrubFrac * dur).toLong() else curPos()
            val frac = clamp(pos.toFloat() / dur)
            if (frac > 0f) {
                fill(Color.argb(245, 255, 255, 255))
                c.drawRoundRect(bx0, by - barH / 2, bx0 + max(barH, (bx1 - bx0) * frac), by + barH / 2, barH / 2, barH / 2, P)
            }
            seekRect = RectF(bx0, by - 12f, bx1, by + 12f)
            btn("seek", seekRect)
            txt(c, fmtTime(pos), bx0, 121f, pt(8f), false, GRAY)
            txt(c, "-" + fmtTime(dur - pos), bx1, 121f, pt(8f), false, GRAY, Paint.Align.RIGHT)
        }

        // kontrol
        val cy = 150f; val mx = w / 2f
        drawSkip(c, RectF(mx - 64f - 16f, cy - 16f, mx - 64f + 16f, cy + 16f), false)
        drawPlayPause(c, RectF(mx - 23f, cy - 23f, mx + 23f, cy + 23f))
        drawSkip(c, RectF(mx + 64f - 16f, cy - 16f, mx + 64f + 16f, cy + 16f), true)
        drawDndBtn(c, RectF(34f - 11f, cy - 11f, 34f + 11f, cy + 11f))
        drawOpenBtn(c, RectF(70f - 11f, cy - 11f, 70f + 11f, cy + 11f))
        drawLyricsBtn(c, RectF(w - 70f - 11f, cy - 11f, w - 70f + 11f, cy + 11f), "lyrics")
        drawChevron(c, RectF(w - 34f - 11f, cy - 11f, w - 34f + 11f, cy + 11f), "collapse", true)
    }

    private fun drawPlayPause(c: Canvas, r: RectF) {
        btn("play", r); c.save(); pressFx(c, "play", r)
        val cx = r.centerX(); val cy = r.centerY(); val s = r.height()
        fill(WHITE)
        if (playing) {
            val bw = s * 0.16f; val bh = s * 0.6f
            c.drawRoundRect(cx - s * 0.15f - bw / 2, cy - bh / 2, cx - s * 0.15f + bw / 2, cy + bh / 2, bw * 0.4f, bw * 0.4f, P)
            c.drawRoundRect(cx + s * 0.15f - bw / 2, cy - bh / 2, cx + s * 0.15f + bw / 2, cy + bh / 2, bw * 0.4f, bw * 0.4f, P)
        } else {
            val tw1 = s * 0.5f; val th1 = s * 0.52f; val x0 = cx - tw1 * 0.42f
            tmp.reset(); tmp.moveTo(x0, cy - th1 / 2); tmp.lineTo(x0, cy + th1 / 2); tmp.lineTo(x0 + tw1, cy); tmp.close()
            P.style = Paint.Style.FILL_AND_STROKE; P.strokeWidth = s * 0.07f
            c.drawPath(tmp, P)
        }
        c.restore()
    }

    private fun drawSkip(c: Canvas, r: RectF, fwd: Boolean) {
        val name = if (fwd) "next" else "prev"
        btn(name, r); c.save(); pressFx(c, name, r)
        val cx = r.centerX(); val cy = r.centerY(); val s = r.height()
        val sg = if (fwd) 1f else -1f
        fill(WHITE); P.style = Paint.Style.FILL_AND_STROKE; P.strokeWidth = s * 0.07f
        val tw1 = s * 0.42f; val th1 = s * 0.5f
        for (i in 0 until 2) {
            val xa = cx + sg * (-tw1 + i * tw1); val xb = xa + sg * tw1
            tmp.reset(); tmp.moveTo(xa, cy - th1 / 2); tmp.lineTo(xa, cy + th1 / 2); tmp.lineTo(xb, cy); tmp.close()
            c.drawPath(tmp, P)
        }
        c.restore()
    }

    private fun drawLyricsBtn(c: Canvas, r: RectF, name: String) {
        btn(name, r); c.save(); pressFx(c, name, r)
        fill(if (state == "lyrics") accent else Color.rgb(225, 225, 230), Paint.Style.STROKE, 1.7f)
        rf.set(r.left + 1.5f, r.top + 3f, r.right - 1.5f, r.bottom - 3f)
        c.drawRoundRect(rf, 5f, 5f, P)
        val x1 = r.left + 6f; val x2 = r.right - 5f
        for ((i, fr) in floatArrayOf(1f, 1f, 0.55f).withIndex()) {
            val y = r.top + 8f + i * 3.2f
            c.drawLine(x1, y, x1 + (x2 - x1) * fr, y, P)
        }
        c.restore()
    }

    private fun drawDndBtn(c: Canvas, r: RectF) {
        btn("dnd", r); c.save(); pressFx(c, "dnd", r)
        drawMoon(c, RectF(r.centerX() - 7f, r.centerY() - 7f, r.centerX() + 7f, r.centerY() + 7f),
            if (dnd) Color.rgb(255, 159, 10) else Color.rgb(225, 225, 230))
        c.restore()
    }

    private fun drawOpenBtn(c: Canvas, r: RectF) {
        btn("open", r); c.save(); pressFx(c, "open", r)
        fill(Color.rgb(225, 225, 230), Paint.Style.STROKE, 1.7f)
        val cx = r.centerX(); val cy = r.centerY()
        c.drawLine(cx - 4f, cy + 4f, cx + 4.5f, cy - 4.5f, P)
        c.drawLine(cx - 0.5f, cy - 4.5f, cx + 4.5f, cy - 4.5f, P)
        c.drawLine(cx + 4.5f, cy - 4.5f, cx + 4.5f, cy + 0.5f, P)
        c.restore()
    }

    private fun drawChevron(c: Canvas, r: RectF, name: String, up: Boolean) {
        btn(name, r); c.save(); pressFx(c, name, r)
        fill(Color.rgb(225, 225, 230), Paint.Style.STROKE, 1.9f)
        val cx = r.centerX(); val cy = r.centerY(); val d = if (up) 1f else -1f
        c.drawLine(cx - 5f, cy + 2.5f * d, cx, cy - 2.5f * d, P)
        c.drawLine(cx, cy - 2.5f * d, cx + 5f, cy + 2.5f * d, P)
        c.restore()
    }

    // ---------- lyrics
    private fun paintLyrics(c: Canvas, w: Float, hh: Float) {
        P.style = Paint.Style.FILL
        P.shader = RadialGradient(w / 2f, 30f, 200f, intArrayOf(col(accent, 0.25f), col(accent, 0f)), null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, hh, P); P.shader = null
        txt(c, "${title()} · ${artist()}", 20f, 20f, pt(8.5f), true, GRAY, maxW = w - 80f)
        drawChevron(c, RectF(w - 34f, 9f, w - 12f, 31f), "lback", false)
        val list = lyrics
        val cx = w / 2f; val mw = w - 44f
        if (lyricsState != 2 || list == null) {
            val msg = if (lyricsState == 3) "Lirik tidak ditemukan" else "Memuat lirik…"
            txt(c, msg, cx, 88f, pt(11f), true, GRAY, Paint.Align.CENTER)
            return
        }
        val pos = curPos()
        var idx = -1
        for (i in list.indices) { if (list[i].first <= pos) idx = i else break }
        fun line(i: Int) = list.getOrNull(i)?.second?.ifBlank { "♪" } ?: ""
        txt(c, line(idx - 1), cx, 66f, pt(9.5f), false, Color.argb(120, 255, 255, 255), Paint.Align.CENTER, mw)
        txt(c, if (idx >= 0) line(idx) else "♪", cx, 94f, pt(12.5f), true, WHITE, Paint.Align.CENTER, mw)
        txt(c, line(idx + 1), cx, 122f, pt(9.5f), false, Color.argb(120, 255, 255, 255), Paint.Align.CENTER, mw)
    }

    // ---------- notif
    private fun drawAppIcon(c: Canvas, r: RectF, n: NotifItem, radius: Float) {
        c.save()
        tmp.reset(); tmp.addRoundRect(r, radius, radius, Path.Direction.CW); c.clipPath(tmp)
        val ic = n.icon
        if (ic != null) {
            P.shader = null; P.style = Paint.Style.FILL; P.alpha = (255 * ca).toInt()
            c.drawBitmap(ic, null, r, P)
        } else {
            P.style = Paint.Style.FILL
            P.shader = LinearGradient(r.left, r.top, r.left, r.bottom, col(n.color), col(Color.rgb(
                (Color.red(n.color) * 0.8f).toInt(), (Color.green(n.color) * 0.8f).toInt(), (Color.blue(n.color) * 0.8f).toInt())), Shader.TileMode.CLAMP)
            c.drawRect(r, P); P.shader = null
            txt(c, (n.app.take(1).ifEmpty { "!" }).uppercase(), r.centerX(), r.centerY(), r.width() * 0.4f, true, WHITE, Paint.Align.CENTER)
        }
        c.restore()
    }

    private fun paintNotif(c: Canvas, w: Float, hh: Float) {
        val n = notif ?: return
        val can = n.reply != null
        val d = 42f
        val icon = RectF(18f, (hh - d) / 2f, 18f + d, (hh + d) / 2f)
        drawAppIcon(c, icon, n, 10f)
        val tx = icon.right + 14f
        val tww = w - tx - 20f
        val tt = tww - if (can) 40f else 0f
        txt(c, n.app.uppercase(), tx, 19f, pt(8f), true, GRAY, maxW = tww - 62f)
        txt(c, "sekarang", tx + tww, 19f, pt(8f), false, GRAY, Paint.Align.RIGHT)
        txt(c, n.title.ifBlank { "(tanpa judul)" }, tx, 34f, pt(10.5f), true, WHITE, maxW = tt)
        T.typeface = regular; T.textSize = pt(10f)
        for ((i, ln) in bodyLines(n).withIndex()) txt(c, ln, tx, 54f + i * 17f, pt(10f), false, Color.rgb(205, 205, 210))
        if (can) drawReplyBtn(c, RectF(w - 20f - 30f, hh / 2f - 15f + 6f, w - 20f, hh / 2f + 15f + 6f))
    }

    private fun drawReplyBtn(c: Canvas, r: RectF) {
        btn("reply", r); c.save(); pressFx(c, "reply", r)
        fill(Color.argb(40, 255, 255, 255)); c.drawOval(r, P)
        val cx = r.centerX(); val cy = r.centerY()
        fill(Color.rgb(235, 235, 240), Paint.Style.STROKE, 1.8f)
        tmp.reset(); tmp.moveTo(cx + 7f, cy + 6f); tmp.cubicTo(cx + 7f, cy + 0.5f, cx + 4f, cy - 1.5f, cx - 1f, cy - 1.5f); tmp.lineTo(cx - 7f, cy - 1.5f)
        c.drawPath(tmp, P)
        tmp.reset(); tmp.moveTo(cx - 2.5f, cy - 6f); tmp.lineTo(cx - 7f, cy - 1.5f); tmp.lineTo(cx - 2.5f, cy + 3f)
        c.drawPath(tmp, P)
        c.restore()
    }

    private fun paintReply(c: Canvas, w: Float, hh: Float) {
        val n = replyItem ?: notif ?: return
        val icon = RectF(18f, 14f, 48f, 44f)
        drawAppIcon(c, icon, n, 8f)
        val tx = icon.right + 12f
        txt(c, "BALAS · ${n.app.uppercase()}", tx, 20f, pt(8f), true, GRAY, maxW = w - tx - 56f)
        txt(c, n.title.ifBlank { "(tanpa judul)" }, tx, 36f, pt(10.5f), true, WHITE, maxW = w - tx - 56f)
        val cr = RectF(w - 18f - 24f, 17f, w - 18f, 41f)
        btn("rclose", cr); c.save(); pressFx(c, "rclose", cr)
        fill(Color.argb(34, 255, 255, 255)); c.drawOval(cr, P)
        fill(Color.rgb(235, 235, 240), Paint.Style.STROKE, 1.8f)
        val k = 4.2f
        c.drawLine(cr.centerX() - k, cr.centerY() - k, cr.centerX() + k, cr.centerY() + k, P)
        c.drawLine(cr.centerX() - k, cr.centerY() + k, cr.centerX() + k, cr.centerY() - k, P)
        c.restore()

        val fr = RectF(18f, 62f, w - 18f - 38f - 8f, 100f)
        fill(Color.argb(26, 255, 255, 255)); c.drawRoundRect(fr, 19f, 19f, P)
        fill(Color.argb(46, 255, 255, 255), Paint.Style.STROKE, 1f); c.drawRoundRect(fr, 19f, 19f, P)
        val msg = when (replyStatus) { "ok" -> "Terkirim ✓"; "err" -> "Gagal mengirim"; "sending" -> "Mengirim…"; else -> "" }
        if (msg.isNotEmpty()) txt(c, msg, fr.left + 15f, fr.centerY(), pt(10f), false, if (replyStatus == "err") Color.rgb(255, 105, 97) else Color.argb(190, 255, 255, 255))
        val sr = RectF(w - 18f - 38f, fr.top, w - 18f, fr.top + 38f)
        btn("send", sr); c.save(); pressFx(c, "send", sr)
        fill(if (replyStatus == "sending") Color.argb(90, 10, 132, 255) else Color.rgb(10, 132, 255)); c.drawOval(sr, P)
        fill(WHITE, Paint.Style.STROKE, 2f)
        val sx = sr.centerX(); val sy = sr.centerY()
        c.drawLine(sx, sy + 6f, sx, sy - 6f, P)
        c.drawLine(sx - 5f, sy - 1f, sx, sy - 6f, P)
        c.drawLine(sx + 5f, sy - 1f, sx, sy - 6f, P)
        c.restore()

        // posisi EditText (px) — dilaporkan ke service bila berubah
        if (replyStatus == "" ) {
            val U = unit()
            val px = RectF((contentLeft + fr.left + 14f) * U, fr.top * U, (contentLeft + fr.right - 10f) * U, fr.bottom * U)
            val last = lastFieldPx
            if (last == null || abs(last.left - px.left) > 1f || abs(last.right - px.right) > 1f || abs(last.top - px.top) > 1f) {
                lastFieldPx = px
                post { if (state == "reply") onReplyField?.invoke(px) }
            }
        } else if (lastFieldPx != null) { lastFieldPx = null; post { onReplyField?.invoke(null) } }
    }

    // ---------- charge
    private fun paintCharge(c: Canvas, w: Float, hh: Float) {
        val t = anim * 60f
        val tt = (SystemClock.uptimeMillis() - stateAt) / 16.7f
        val cy = hh / 2f
        val pct = chargePct.coerceIn(0, 100)
        val green = Color.rgb(48, 209, 88)
        val bx = 24f
        P.style = Paint.Style.FILL
        P.shader = RadialGradient(bx + 27f, cy, 62f, col(green, (75f + 40f * sin(t * 0.12f)) / 255f), col(green, 0f), Shader.TileMode.CLAMP)
        c.drawCircle(bx + 27f, cy, 62f, P); P.shader = null
        val body = RectF(bx, cy - 12f, bx + 52f, cy + 12f)
        fill(Color.argb(210, 255, 255, 255), Paint.Style.STROKE, 1.6f); c.drawRoundRect(body, 7f, 7f, P)
        fill(Color.argb(210, 255, 255, 255)); c.drawRoundRect(body.right + 1.8f, cy - 4f, body.right + 5.4f, cy + 4f, 1.5f, 1.5f, P)
        var k = min(1f, tt / 30f); k = 1 - (1 - k) * (1 - k) * (1 - k)
        val inner = RectF(body.left + 3.2f, body.top + 3.2f, body.right - 3.2f, body.bottom - 3.2f)
        val fw = inner.width() * pct / 100f * k
        if (fw > 1f) {
            val fillR = RectF(inner.left, inner.top, inner.left + fw, inner.bottom)
            P.style = Paint.Style.FILL
            P.shader = LinearGradient(0f, fillR.top, 0f, fillR.bottom, col(Color.rgb(110, 240, 140)), col(Color.rgb(30, 180, 70)), Shader.TileMode.CLAMP)
            c.drawRoundRect(fillR, 4f, 4f, P); P.shader = null
            c.save()
            tmp.reset(); tmp.addRoundRect(fillR, 4f, 4f, Path.Direction.CW); c.clipPath(tmp)
            val sx = fillR.left + (((t * 0.045f) % 1.7f) - 0.35f) * inner.width()
            P.shader = LinearGradient(sx - 12f, 0f, sx + 12f, 0f, intArrayOf(col(WHITE, 0f), col(WHITE, 0.5f), col(WHITE, 0f)), null, Shader.TileMode.CLAMP)
            c.drawRect(sx - 12f, fillR.top, sx + 12f, fillR.bottom, P); P.shader = null
            c.restore()
        }
        val sc = 7.5f * (1f + 0.1f * sin(t * 0.2f))
        val bolt = arrayOf(0.15f to -1f, -0.55f to 0.1f, -0.05f to 0.1f, -0.2f to 1f, 0.55f to -0.15f, 0.05f to -0.15f)
        tmp.reset()
        bolt.forEachIndexed { i, (x, y) -> if (i == 0) tmp.moveTo(body.centerX() + x * sc, body.centerY() + y * sc) else tmp.lineTo(body.centerX() + x * sc, body.centerY() + y * sc) }
        tmp.close(); fill(WHITE); c.drawPath(tmp, P)
        val tx = body.right + 22f
        txt(c, "$pct%", tx, cy - 9f, pt(15f), true, WHITE)
        txt(c, if (pct < 100) "Mengisi daya" else "Terisi penuh", tx, cy + 13f, pt(8.5f), false, green)
        val x0 = w - 24f - 36f
        for (i in 0 until 3) {
            fill(green, Paint.Style.STROKE, 2.4f)
            P.alpha = (255 * ca * (0.2f + 0.8f * max(0f, sin(t * 0.18f - i * 0.9f)))).toInt().coerceIn(0, 255)
            val x = x0 + i * 12f
            tmp.reset(); tmp.moveTo(x, cy - 7f); tmp.lineTo(x + 6f, cy); tmp.lineTo(x, cy + 7f)
            c.drawPath(tmp, P)
        }
    }

    // ---------- HUD (volume / kecerahan / bluetooth)
    private fun paintHud(c: Canvas, w: Float, hh: Float) {
        val cy = hh / 2f
        val ic = RectF(20f, cy - 12f, 44f, cy + 12f)
        when (hudKind) {
            "bri" -> {
                val cx = ic.centerX(); val cyy = ic.centerY()
                fill(Color.rgb(255, 214, 10)); c.drawCircle(cx, cyy, 4.6f, P)
                fill(Color.rgb(255, 214, 10), Paint.Style.STROKE, 1.6f)
                for (i in 0 until 8) { val a = i * Math.PI / 4.0
                    c.drawLine(cx + 7.2f * cos(a).toFloat(), cyy + 7.2f * sin(a).toFloat(), cx + 10f * cos(a).toFloat(), cyy + 10f * sin(a).toFloat(), P) }
            }
            "bt" -> {
                fill(Color.rgb(10, 132, 255)); c.drawCircle(ic.centerX(), ic.centerY(), 12f, P)
                fill(WHITE, Paint.Style.STROKE, 1.6f)
                val x = ic.centerX(); val y = ic.centerY(); val s = 0.9f
                tmp.reset()
                tmp.moveTo(x - 3.5f * s, y - 3f * s); tmp.lineTo(x + 3.5f * s, y + 3.5f * s); tmp.lineTo(x, y + 7f * s)
                tmp.lineTo(x, y - 7f * s); tmp.lineTo(x + 3.5f * s, y - 3.5f * s); tmp.lineTo(x - 3.5f * s, y + 3f * s)
                c.drawPath(tmp, P)
            }
            else -> {
                val cx = ic.centerX(); val cyy = ic.centerY()
                fill(WHITE)
                tmp.reset(); tmp.moveTo(cx - 8f, cyy - 3f); tmp.lineTo(cx - 4f, cyy - 3f); tmp.lineTo(cx + 1f, cyy - 7.5f)
                tmp.lineTo(cx + 1f, cyy + 7.5f); tmp.lineTo(cx - 4f, cyy + 3f); tmp.lineTo(cx - 8f, cyy + 3f); tmp.close()
                c.drawPath(tmp, P)
                if (hudValue > 0) {
                    fill(WHITE, Paint.Style.STROKE, 1.6f)
                    rf.set(cx - 2f, cyy - 4f, cx + 6f, cyy + 4f); c.drawArc(rf, -50f, 100f, false, P)
                    if (hudValue > 45) { rf.set(cx - 4f, cyy - 7.5f, cx + 9f, cyy + 7.5f); c.drawArc(rf, -50f, 100f, false, P) }
                } else {
                    fill(WHITE, Paint.Style.STROKE, 1.6f)
                    c.drawLine(cx + 4f, cyy - 3.5f, cx + 9f, cyy + 3.5f, P); c.drawLine(cx + 9f, cyy - 3.5f, cx + 4f, cyy + 3.5f, P)
                }
            }
        }
        val tx = 58f
        txt(c, hudTitle, tx, if (hudValue >= 0) 20f else cy, pt(9.5f), true, WHITE, maxW = w - tx - 56f)
        if (hudValue >= 0) {
            txt(c, "$hudValue%", w - 20f, 20f, pt(9f), false, GRAY, Paint.Align.RIGHT)
            val x0 = tx; val x1 = w - 20f; val by = 40f
            fill(Color.argb(56, 255, 255, 255)); c.drawRoundRect(x0, by - 3f, x1, by + 3f, 3f, 3f, P)
            val fillW = (x1 - x0) * clamp(hudValue / 100f)
            if (fillW > 0f) { fill(Color.argb(245, 255, 255, 255)); c.drawRoundRect(x0, by - 3f, x0 + max(6f, fillW), by + 3f, 3f, 3f, P) }
        }
    }

    // ============================================================ sentuhan
    private fun hit(x: Float, y: Float): String? {
        for (i in buttons.indices.reversed()) {
            val (n, r) = buttons[i]
            val pad = if (n == "seek") 0f else 5f
            if (x >= r.left - pad && x <= r.right + pad && y >= r.top - pad && y <= r.bottom + pad) return n
        }
        return null
    }

    private fun bumpRest() {
        if (state == "player" || state == "lyrics") schedule(AUTO_SHRINK_MS)
        if (state == "reply") touchedReply()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val U = unit()
        val x = e.x / U; val y = e.y / U
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = x; downY = y; moved = false; swiped = false; longFired = false
                pressed = hit(x, y)
                if (pressed == "seek") { scrubbing = true; scrubFrac = clamp((x - (seekRect.left + contentLeft)) / seekRect.width()) }
                h.removeCallbacks(longRun); h.postDelayed(longRun, 650)
                bumpRest(); kick(); invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(x - downX) > 6f || abs(y - downY) > 6f) { moved = true; h.removeCallbacks(longRun) }
                if (scrubbing) {
                    scrubFrac = clamp((x - (seekRect.left + contentLeft)) / seekRect.width()); bumpRest()
                } else if (!swiped && downY - y > 24f) {
                    swiped = true; pressed = null; swipeUp()
                } else if (pressed != null && hit(x, y) != pressed) pressed = null
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                h.removeCallbacks(longRun)
                val p = pressed; pressed = null
                if (scrubbing) {
                    scrubbing = false
                    val dur = media?.durMs ?: 0L
                    if (dur > 0) {
                        val to = (scrubFrac * dur).toLong()
                        basePos = to; baseStamp = SystemClock.elapsedRealtime()
                        onAction?.invoke("seek", to)
                    }
                } else if (swiped || longFired) { /* sudah ditangani */ }
                else if (p != null) click(p)
                else if (!moved) bodyTap()
                bumpRest(); kick(); invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL -> { h.removeCallbacks(longRun); pressed = null; scrubbing = false; invalidate(); return true }
        }
        return super.onTouchEvent(e)
    }

    private fun click(name: String) {
        when (name) {
            "prev" -> onAction?.invoke("prev", null)
            "next" -> onAction?.invoke("next", null)
            "play" -> { onAction?.invoke("play", null); playing = !playing; basePos = curPos(); baseStamp = SystemClock.elapsedRealtime() }
            "lyrics" -> { go("lyrics"); requestLyrics(); schedule(AUTO_SHRINK_MS * 3) }
            "lback" -> { go("player"); schedule(AUTO_SHRINK_MS) }
            "collapse" -> toBase()
            "dnd" -> { dnd = !dnd }
            "open" -> { onAction?.invoke("open_media", null); toBase() }
            "reply" -> openReply()
            "rclose" -> closeReply()
            "send" -> onAction?.invoke("reply_send", replyItem)
        }
    }

    private fun bodyTap() {
        when (state) {
            "hidden" -> { hiddenFlag = false; toBase() }
            "idle", "music" -> if (hasTrack) { go("player"); schedule(AUTO_SHRINK_MS * 2) }
            "notif" -> { notif?.let { onAction?.invoke("open_notif", it) }; toBase() }
        }
    }

    private fun swipeUp() {
        when (state) {
            "notif", "player", "lyrics", "charge", "hud" -> toBase()
            "reply" -> closeReply()
            "idle", "music" -> if (cfg.autoHide) { hiddenFlag = true; toBase() }
        }
    }
}
