package com.uzakpc

import android.app.Activity
import android.content.SharedPreferences
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private val port = 8765

    // Ilk acilista kullanilan varsayilanlar (Ayarlar'dan degistirilebilir, baglaninca otomatik guncellenir)
    private val defaultIp = "192.168.1.17"
    private val defaultMacs = "94:B6:09:89:A4:EF, B0:25:AA:81:84:32"
    private var web: WebView? = null
    private lateinit var prefs: SharedPreferences

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        prefs = getSharedPreferences("cfg", MODE_PRIVATE)
        showMenu()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun btn(text: String, color: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        textSize = 18f
        isAllCaps = false
        setBackgroundColor(Color.parseColor(color))
        layoutParams = LinearLayout.LayoutParams(-1, dp(62)).apply { topMargin = dp(14) }
        setOnClickListener { onClick() }
    }

    private fun edit(hint: String, value: String, type: Int) = EditText(this).apply {
        this.hint = hint
        setText(value)
        inputType = type
        setTextColor(Color.WHITE)
        setHintTextColor(Color.GRAY)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
    }

    private fun label(t: String, size: Float = 14f, color: String = "#9AA0A6") = TextView(this).apply {
        text = t
        textSize = size
        setTextColor(Color.parseColor(color))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) }
    }

    private fun showMenu() {
        web?.destroy()
        web = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0F1115"))
            setPadding(dp(24), dp(48), dp(24), dp(24))
        }
        val pass = edit("Şifre", "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD).apply {
            textSize = 26f
            gravity = Gravity.CENTER
            imeOptions = EditorInfo.IME_ACTION_GO
        }
        val ip = edit("Bilgisayar IP adresi", prefs.getString("ip", defaultIp) ?: defaultIp, InputType.TYPE_CLASS_PHONE)
        val mac = edit(
            "MAC adresleri (virgülle ayrılır)", prefs.getString("mac", defaultMacs) ?: defaultMacs,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        )
        val status = label("", 15f, "#8AB4F8")
        fun say(t: String) = runOnUiThread { status.text = t }
        fun save() = prefs.edit().putString("ip", ip.text.toString().trim()).putString("mac", mac.text.toString().trim()).apply()

        fun connect() {
            val pw = pass.text.toString().trim()
            if (pw.isEmpty()) { say("Önce şifreyi girin"); return }
            save()
            say("Bilgisayar aranıyor…")
            val saved = ip.text.toString().trim()
            thread {
                val found = findPc(saved)
                if (found == null) {
                    say("Bilgisayar bulunamadı. Açık ve aynı Wi-Fi'de mi? Kapalıysa BİLGİSAYARI AÇ'a basın.")
                    return@thread
                }
                prefs.edit().putString("ip", found).apply()
                runOnUiThread { ip.setText(found) }
                fetchMacs(found)?.let { m ->
                    prefs.edit().putString("mac", m).apply()
                    runOnUiThread { mac.setText(m) }
                }
                runOnUiThread { showWeb(found, pw) }
            }
        }

        pass.setOnEditorActionListener { _, _, _ -> connect(); true }

        val settings = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(label("Bilgisayar IP adresi (otomatik bulunur)"))
            addView(ip)
            addView(label("MAC adresleri (bağlanınca otomatik kaydedilir)"))
            addView(mac)
        }

        root.addView(label("Uzak PC", 30f, "#FFFFFF"))
        root.addView(label("Şifreyi girin"))
        root.addView(pass)
        root.addView(btn("🖥  BAĞLAN", "#3B6CF5") { connect() })
        root.addView(btn("⏻  BİLGİSAYARI AÇ", "#2E7D32") {
            save()
            wake(mac.text.toString().trim(), ip.text.toString().trim(), ::say)
        })
        root.addView(btn("⚙  Ayarlar", "#3A3F4B") {
            settings.visibility = if (settings.visibility == View.GONE) View.VISIBLE else View.GONE
        })
        root.addView(settings)
        root.addView(status)
        root.addView(label("Bilgisayar açıkken şifreyi yazıp BAĞLAN'a basın. IP ve MAC adresi otomatik bulunup kaydedilir. Kapalı bilgisayarı BİLGİSAYARI AÇ ile uyandırmayı deneyebilirsiniz (uyku modunda daha çok çalışır)."))

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0F1115"))
            addView(root)
        })
    }

    private fun showWeb(ip: String, pass: String) {
        val w = WebView(this)
        web = w
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.settings.mediaPlaybackRequiresUserGesture = false
        w.setBackgroundColor(Color.BLACK)
        w.webViewClient = WebViewClient()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(w)
        w.loadUrl("http://$ip:$port/?pin=" + Uri.encode(pass))
    }

    // ---------------------------------------------------------------- ag yardimcilari
    private fun isUp(ip: String, ms: Int = 700): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(ip, port), ms) }
        true
    } catch (e: Exception) {
        false
    }

    private fun readKimlik(host: String): String? = try {
        val c = URL("http://$host:$port/api/kimlik").openConnection() as HttpURLConnection
        c.connectTimeout = 3000
        c.readTimeout = 8000
        c.inputStream.bufferedReader().readText()
    } catch (e: Exception) {
        null
    }

    private fun isPc(host: String): Boolean = isUp(host, 500) && (readKimlik(host)?.contains("\"mac\"") == true)

    private fun fetchMacs(host: String): String? = try {
        val j = JSONObject(readKimlik(host) ?: "")
        val list = mutableListOf<String>()
        val arr = j.optJSONArray("kartlar")
        if (arr != null) for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val ad = (o.optString("baglanti") + " " + o.optString("kart")).lowercase()
            if (listOf("bluetooth", "virtual", "vpn", "tap-", "hyper-v", "vmware", "vbox", "loopback").none { ad.contains(it) })
                list.add(o.getString("mac"))
        }
        if (list.isEmpty() && j.has("mac")) list.add(j.getString("mac"))
        if (list.isEmpty()) null else list.distinct().joinToString(", ")
    } catch (e: Exception) {
        null
    }

    /** Telefonun bagli oldugu yerel agin on ekleri, orn. "192.168.1". */
    private fun localPrefixes(): List<String> {
        val out = mutableListOf<String>()
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (a in ni.inetAddresses) {
                    if (a is Inet4Address && a.isSiteLocalAddress) {
                        val p = (a.hostAddress ?: "").substringBeforeLast(".")
                        if (p.isNotEmpty() && p !in out) out.add(p)
                    }
                }
            }
        } catch (e: Exception) { }
        return out
    }

    private fun scan(prefix: String): String? {
        val found = AtomicReference<String?>(null)
        val pool = Executors.newFixedThreadPool(48)
        for (i in 1..254) pool.execute {
            if (found.get() == null) {
                val h = "$prefix.$i"
                if (isUp(h, 350) && isPc(h)) found.compareAndSet(null, h)
            }
        }
        pool.shutdown()
        pool.awaitTermination(20, TimeUnit.SECONDS)
        return found.get()
    }

    /** Kayitli IP calisiyorsa onu, yoksa agi tarayip bilgisayari bulur. */
    private fun findPc(saved: String): String? {
        if (saved.isNotEmpty() && isPc(saved)) return saved
        for (prefix in localPrefixes()) scan(prefix)?.let { return it }
        return null
    }

    /** Wake-on-LAN: 6x0xFF + 16x MAC. Her MAC icin IP'ye dogrudan ve yayin (broadcast) olarak gonderilir. */
    private fun wake(macStr: String, ip: String, say: (String) -> Unit) {
        val macs = macStr.replace(Regex("[^0-9A-Fa-f,;]"), "").split(Regex("[,;]"))
            .flatMap { it.chunked(12) }.filter { it.length == 12 }
            .map { h -> ByteArray(6) { i -> h.substring(i * 2, i * 2 + 2).toInt(16).toByte() } }
        if (macs.isEmpty()) { say("MAC adresi geçersiz: Ayarlar'dan 12 harf/rakamlık MAC girin"); return }
        thread {
            val targets = linkedSetOf<String>()
            if (ip.split(".").size == 4) {
                targets.add(ip)
                targets.add(ip.substringBeforeLast(".") + ".255")
            }
            for (pf in localPrefixes()) targets.add("$pf.255")
            targets.add("255.255.255.255")
            try {
                DatagramSocket().use { s ->
                    s.broadcast = true
                    repeat(6) {
                        for (m in macs) {
                            val pkt = ByteArray(6) { 0xFF.toByte() } + ByteArray(96) { m[it % 6] }
                            for (t in targets) for (pt in intArrayOf(9, 7)) {
                                try { s.send(DatagramPacket(pkt, pkt.size, InetAddress.getByName(t), pt)) } catch (e: Exception) { }
                            }
                        }
                        Thread.sleep(400)
                    }
                }
            } catch (e: Exception) {
                say("Gönderilemedi: ${e.message}")
                return@thread
            }
            say("Açma sinyali ${macs.size} karta gönderildi, bekleniyor…")
            val end = System.currentTimeMillis() + 120_000
            var n = 0
            while (System.currentTimeMillis() < end) {
                if (ip.isNotEmpty() && isUp(ip)) { say("✓ Bilgisayar açıldı. Şifreyi girip BAĞLAN'a basın."); return@thread }
                if (n % 6 == 5) {
                    val f = findPc("")
                    if (f != null) { prefs.edit().putString("ip", f).apply(); say("✓ Bilgisayar açıldı ($f). Şifreyi girip BAĞLAN'a basın."); return@thread }
                }
                n++
                Thread.sleep(2000)
            }
            say("Yanıt gelmedi. Bilgisayar kapalıyken Wi-Fi ile uyanmayabilir. Uyku modunu deneyin.")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web != null) showMenu() else super.onBackPressed()
    }
}
