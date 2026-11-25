package id.island.dynamic

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Semua pemanggilan di sini BLOKING: jalankan dari thread latar. */
object Net {
    private fun http(url: String, timeout: Int = 6000): String? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = timeout
        c.readTimeout = timeout
        c.setRequestProperty("User-Agent", "DynamicIslandAndroid/9.0")
        if (c.responseCode != 200) null else c.inputStream.bufferedReader().use { it.readText() }
    } catch (e: Exception) { null }

    /** (suhu °C, jenis: sun|cloud|rain) via lokasi IP (tanpa izin lokasi) + Open-Meteo. */
    fun weather(): Pair<Int, String>? {
        val ll: Pair<Double, Double>? = http("https://ipwho.is/?fields=success,latitude,longitude")?.let {
            try {
                val j = JSONObject(it)
                if (j.optBoolean("success", false)) j.getDouble("latitude") to j.getDouble("longitude") else null
            } catch (_: Exception) { null }
        }
        if (ll == null) return null
        val (lat, lon) = ll
        val raw = http("https://api.open-meteo.com/v1/forecast?latitude=%.2f&longitude=%.2f&current=temperature_2m,weather_code"
            .format(java.util.Locale.US, lat, lon)) ?: return null
        return try {
            val cur = JSONObject(raw).getJSONObject("current")
            val t = Math.round(cur.getDouble("temperature_2m")).toInt()
            val code = cur.getInt("weather_code")
            val kind = when (code) {
                0, 1 -> "sun"
                in 51..67, in 80..82, in 95..99 -> "rain"
                else -> "cloud"
            }
            t to kind
        } catch (e: Exception) { null }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun parseLrc(text: String): List<Pair<Long, String>> {
        val out = ArrayList<Pair<Long, String>>()
        val re = Regex("""\[(\d+):(\d+(?:\.\d+)?)]""")
        for (line in text.lines()) {
            val ms = re.findAll(line).toList()
            if (ms.isEmpty()) continue
            val lyric = line.substring(ms.last().range.last + 1).trim()
            for (m in ms) {
                val t = (m.groupValues[1].toLong() * 60_000 + (m.groupValues[2].toDouble() * 1000).toLong())
                out.add(t to lyric)
            }
        }
        out.sortBy { it.first }
        return out
    }

    /** Lirik tersinkron dari lrclib.net; null bila tidak ada. */
    fun lyrics(title: String, artist: String, durSec: Long): List<Pair<Long, String>>? {
        val base = "https://lrclib.net/api"
        if (durSec > 0) {
            http("$base/get?artist_name=${enc(artist)}&track_name=${enc(title)}&duration=$durSec")?.let {
                try {
                    val s = JSONObject(it).optString("syncedLyrics", "")
                    if (s.isNotBlank()) return parseLrc(s)
                } catch (_: Exception) {}
            }
        }
        http("$base/search?track_name=${enc(title)}&artist_name=${enc(artist)}")?.let {
            try {
                val arr = JSONArray(it)
                for (i in 0 until arr.length()) {
                    val s = arr.getJSONObject(i).optString("syncedLyrics", "")
                    if (s.isNotBlank()) return parseLrc(s)
                }
            } catch (_: Exception) {}
        }
        return null
    }
}
