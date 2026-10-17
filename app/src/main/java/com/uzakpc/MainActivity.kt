package com.uzakpc

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.WindowManager
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
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private val port = 8765
    private var web: WebView? = null
    private lateinit var prefs: SharedPreferences

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        prefs = getSharedPreferences("cfg", Context.MODE_PRIVATE)
        showMenu()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun btn(text: String, color: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        textSize = 17f
        isAllCaps = false
        setBackgroundColor(Color.parseColor(color))
        layoutParams = LinearLayout.LayoutParams(-1, dp(60)).apply { topMargin = dp(12) }
        setOnClickListener { onClick() }
    }

    private fun edit(hint: String, value: String, type: Int = InputType.TYPE_CLASS_TEXT) = EditText(this).apply {
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
            setPadding(dp(24), dp(40), dp(24), dp(24))
        }
        val ip = edit("Bilgisayar IP adresi (örn. 192.168.1.20)", prefs.getString("ip", "") ?: "", InputType.TYPE_CLASS_PHONE)
        val mac = edit("MAC adresi (otomatik alınır)", prefs.getString("mac", "") ?: "")
        val status = label("", 15f, "#8AB4F8")

        fun say(t: String) = runOnUiThread { status.text = t }
        fun save() = prefs.edit().putString("ip", ip.text.toString().trim()).putString("mac", mac.text.toString().trim()).apply()

        root.addView(label("Uzak PC", 28f, "#FFFFFF"))
        root.addView(label("Bilgisayar IP adresi"))
        root.addView(ip)
        root.addView(label("MAC adresi (Bilgisayarı Aç için)"))
        root.addView(mac)

        root.addView(btn("⏻  BİLGİSAYARI AÇ", "#2E7D32") {
            save()
            wake(mac.text.toString().trim(), ip.text.toString().trim(), ::say)
        })
        root.addView(btn("🖥  BAĞLAN VE YÖNET", "#3B6CF5") {
            save()
            val addr = ip.text.toString().trim()
            if (addr.isEmpty()) { say("Önce IP adresini girin"); return@btn }
            say("Bağlanılıyor…")
            thread {
                if (isUp(addr)) runOnUiThread { showWeb(addr) }
                else say("Bilgisayara ulaşılamıyor. Açık mı ve aynı Wi-Fi'de miyiz?")
            }
        })
        root.addView(btn("MAC adresini bilgisayardan al", "#3A3F4B") {
            save()
            val addr = ip.text.toString().trim()
            if (addr.isEmpty()) { say("Önce IP adresini girin"); return@btn }
            say("MAC alınıyor…")
            thread {
                try {
                    val c = URL("http://$addr:$port/api/kimlik").openConnection() as HttpURLConnection
                    c.connectTimeout = 2500
                    c.readTimeout = 2500
                    val j = JSONObject(c.inputStream.bufferedReader().readText())
                    runOnUiThread { mac.setText(j.getString("mac")) }
                    prefs.edit().putString("mac", j.getString("mac")).apply()
                    say("✓ MAC alındı: ${j.getString("mac")}")
                } catch (e: Exception) {
                    say("Alınamadı. Bilgisayar açık ve kur.bat çalışmış olmalı.")
                }
            }
        })
        root.addView(status)
        root.addView(label("İlk kullanım: bilgisayar açıkken IP'yi yazıp \"MAC adresini al\"a basın. Sonra kapalı bilgisayarı da bu uygulamayla açabilirsiniz. Bilgisayar kablo ile bağlı ve BIOS'ta Wake-on-LAN açık olmalı."))

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0F1115"))
            addView(root)
        })
    }

    private fun showWeb(ip: String) {
        val w = WebView(this)
        web = w
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.settings.mediaPlaybackRequiresUserGesture = false
        w.setBackgroundColor(Color.BLACK)
        w.webViewClient = WebViewClient()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(w)
        w.loadUrl("http://$ip:$port/")
    }

    private fun isUp(ip: String): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(ip, port), 700) }
        true
    } catch (e: Exception) {
        false
    }

    /** Wake-on-LAN: 6x0xFF + 16x MAC adresi, UDP broadcast. */
    private fun wake(macStr: String, ip: String, say: (String) -> Unit) {
        val mac = try {
            macStr.split(":", "-").map { it.toInt(16).toByte() }.toByteArray()
        } catch (e: Exception) { null }
        if (mac == null || mac.size != 6) { say("MAC adresi geçersiz veya boş"); return }
        thread {
            val pkt = ByteArray(6) { 0xFF.toByte() } + ByteArray(96) { mac[it % 6] }
            val targets = mutableListOf("255.255.255.255")
            val p = ip.split(".")
            if (p.size == 4) targets.add("${p[0]}.${p[1]}.${p[2]}.255")
            try {
                DatagramSocket().use { s ->
                    s.broadcast = true
                    repeat(3) {
                        for (t in targets) for (port in intArrayOf(9, 7))
                            s.send(DatagramPacket(pkt, pkt.size, InetAddress.getByName(t), port))
                        Thread.sleep(200)
                    }
                }
            } catch (e: Exception) {
                say("Gönderilemedi: ${e.message}")
                return@thread
            }
            say("Açma sinyali gönderildi, bekleniyor…")
            if (ip.isEmpty()) return@thread
            val end = System.currentTimeMillis() + 120_000
            while (System.currentTimeMillis() < end) {
                if (isUp(ip)) { say("✓ Bilgisayar açıldı. Şimdi BAĞLAN'a basabilirsiniz."); return@thread }
                Thread.sleep(2000)
            }
            say("Yanıt gelmedi. BIOS'ta Wake-on-LAN açık mı, bilgisayar kabloyla mı bağlı?")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web != null) showMenu() else super.onBackPressed()
    }
}
