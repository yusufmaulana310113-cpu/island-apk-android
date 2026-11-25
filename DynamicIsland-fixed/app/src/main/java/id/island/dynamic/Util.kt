package id.island.dynamic

import android.graphics.Color
import android.graphics.Path
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

const val K_CIRC = 0.5523f

fun clamp(v: Float, lo: Float = 0f, hi: Float = 1f): Float = if (v < lo) lo else if (v > hi) hi else v

fun fmtTime(ms: Long): String {
    val s = max(0L, ms / 1000)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

/** Squircle (sudut halus ala Apple), port dari _smooth_rect versi Windows. */
fun smoothRect(path: Path, x: Float, y: Float, w: Float, h: Float, r0: Float) {
    val r = max(0f, min(r0, min(w / 2f, h / 2f)))
    if (r < 0.5f) { path.addRect(x, y, x + w, y + h, Path.Direction.CW); return }
    val t = min(r * 1.2f, min(w / 2f, h / 2f))
    val k = 0.4477f - 0.0477f * clamp((t / r - 1f) / 0.2f)
    val a = t * k
    path.moveTo(x + t, y)
    path.lineTo(x + w - t, y)
    path.cubicTo(x + w - a, y, x + w, y + a, x + w, y + t)
    path.lineTo(x + w, y + h - t)
    path.cubicTo(x + w, y + h - a, x + w - a, y + h, x + w - t, y + h)
    path.lineTo(x + t, y + h)
    path.cubicTo(x + a, y + h, x, y + h - a, x, y + h - t)
    path.lineTo(x, y + t)
    path.cubicTo(x, y + a, x + a, y, x + t, y)
    path.close()
}

/** Bentuk Notch: sisi atas rata, "telinga" reverse-fillet kiri/kanan, sudut bawah squircle. W = badan + 2E. */
fun notchPath(path: Path, W: Float, H: Float, E: Float, r0: Float) {
    if (E <= 0.5f) { smoothRect(path, 0f, 0f, W, H, r0); return }
    val e = max(0f, min(E, H * 0.5f))
    val L = E
    val R = W - E
    val bw = max(1f, R - L)
    val r = max(0f, min(r0, min(bw / 2f, H - e)))
    val t: Float
    val a: Float
    if (r < 0.5f) { t = 0f; a = 0f } else {
        t = min(r * 1.2f, min(bw / 2f, H - e))
        a = t * (0.4477f - 0.0477f * clamp((t / r - 1f) / 0.2f))
    }
    path.moveTo(L - e, 0f)
    path.lineTo(R + e, 0f)
    path.cubicTo(R + e - e * K_CIRC, 0f, R, e - e * K_CIRC, R, e)
    path.lineTo(R, H - t)
    path.cubicTo(R, H - a, R - a, H, R - t, H)
    path.lineTo(L + t, H)
    path.cubicTo(L + a, H, L, H - a, L, H - t)
    path.lineTo(L, e)
    path.cubicTo(L, e - e * K_CIRC, L - e + e * K_CIRC, 0f, L - e, 0f)
    path.close()
}

/** Pegas (spring) kritis/underdamped, diintegrasi dengan sub-step agar stabil. */
class Spring(var x: Float, var target: Float) {
    var v = 0f
    fun step(dt: Float, response: Float, zeta: Float) {
        val w = (2.0 * Math.PI / response).toFloat()
        var t = dt
        while (t > 0f) {
            val h = min(t, 1f / 240f)
            val a = -w * w * (x - target) - 2f * zeta * w * v
            v += a * h
            x += v * h
            t -= h
        }
    }
    val settled: Boolean get() = abs(x - target) < 0.04f && abs(v) < 0.4f
    fun snap() { x = target; v = 0f }
}

fun drawableToBitmap(d: Drawable, size: Int): Bitmap {
    if (d is BitmapDrawable && d.bitmap != null && d.bitmap.width <= size * 2) return d.bitmap
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(bmp)
    d.setBounds(0, 0, size, size)
    d.draw(c)
    return bmp
}

/** Warna aksen: rata-rata piksel berbobot saturasi dari versi mini cover. */
fun accentOf(b: Bitmap?): Int {
    if (b == null) return Color.rgb(120, 130, 255)
    val s = Bitmap.createScaledBitmap(b, 12, 12, true)
    var rs = 0.0; var gs = 0.0; var bs = 0.0; var ws = 0.0
    val hsv = FloatArray(3)
    for (y in 0 until 12) for (x in 0 until 12) {
        val p = s.getPixel(x, y)
        Color.colorToHSV(p, hsv)
        val w = 0.05 + hsv[1] * hsv[2]
        rs += Color.red(p) * w; gs += Color.green(p) * w; bs += Color.blue(p) * w; ws += w
    }
    if (s !== b) s.recycle()
    var col = Color.rgb((rs / ws).toInt(), (gs / ws).toInt(), (bs / ws).toInt())
    Color.colorToHSV(col, hsv)
    hsv[1] = max(hsv[1], 0.45f)
    hsv[2] = clamp(max(hsv[2], 0.75f))
    col = Color.HSVToColor(hsv)
    return col
}

private val APP_COLORS = listOf(
    listOf("whatsapp") to Color.rgb(37, 211, 102),
    listOf("gmail", "mail", "outlook") to Color.rgb(234, 67, 53),
    listOf("telegram") to Color.rgb(42, 171, 238),
    listOf("chrome", "firefox", "brave", "opera", "edge") to Color.rgb(66, 133, 244),
    listOf("instagram") to Color.rgb(225, 48, 108),
    listOf("discord") to Color.rgb(88, 101, 242),
    listOf("slack") to Color.rgb(116, 40, 118),
    listOf("teams") to Color.rgb(98, 100, 167),
    listOf("messenger", "facebook") to Color.rgb(0, 132, 255),
    listOf("tiktok") to Color.rgb(254, 44, 85),
    listOf("spotify") to Color.rgb(30, 215, 96),
)

fun appColor(app: String): Int {
    val a = app.lowercase()
    for ((keys, rgb) in APP_COLORS) if (keys.any { a.contains(it) }) return rgb
    return Color.rgb(110, 118, 140)
}

class LruMap<K, V>(private val max: Int) : LinkedHashMap<K, V>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > max
}
