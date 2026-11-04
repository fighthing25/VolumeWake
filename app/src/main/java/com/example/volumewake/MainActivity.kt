package com.example.volumewake

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.graphics.Typeface
import android.hardware.Sensor
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var systemInfo: TextView
    private val triggerInfo = HashMap<String, TextView>()
    private val ticker = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(32))
        }
        setContentView(ScrollView(this).apply { addView(root) })

        root.addView(heading("Volume Wake", 24f))
        root.addView(body("Urutan uji: (1) Mulai layanan. (2) Tes apakah app bisa menyalakan layar. (3) Aktifkan pemicu yang mau dicoba, matikan layar, lalu coba tiap pemicu. (4) Nyalakan layar dengan tombol power dan lihat hasilnya di bagian 3."))

        root.addView(heading("1. Status dan layanan"))
        systemInfo = body("")
        root.addView(systemInfo)
        root.addView(button("Mulai / perbarui layanan") { startSvc() })
        root.addView(button("Hentikan layanan") { stopService(Intent(this, VolumeWakeService::class.java)) })
        root.addView(button("Buka pengaturan Aksesibilitas") { open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })
        root.addView(button("Pengecualian optimasi baterai") { open(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) })
        root.addView(button("Info aplikasi (izin, mulai otomatis)") {
            open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        })

        root.addView(heading("2. Tes: bisakah app menyalakan layar?"))
        root.addView(body("Tekan salah satu tombol, lalu langsung matikan layar dengan tombol power. Dalam 10 detik layar harus menyala sendiri. Kalau tidak, cara itu diblokir di HP ini."))
        root.addView(button("Tes cara WakeLock") { wakeTest("lock") })
        root.addView(button("Tes cara Layar-penuh") { wakeTest("fullscreen") })
        root.addView(body("Cara yang dipakai saat pemicu terdeteksi:"))
        val rLock = RadioButton(this).apply {
            text = "WakeLock"
            id = View.generateViewId()
        }
        val rFull = RadioButton(this).apply {
            text = "Layar-penuh (lewat notifikasi)"
            id = View.generateViewId()
        }
        val group = RadioGroup(this)
        group.addView(rLock)
        group.addView(rFull)
        group.check(if (Core.wakeMode(this) == "fullscreen") rFull.id else rLock.id)
        group.setOnCheckedChangeListener { _, checkedId ->
            val mode = if (checkedId == rFull.id) "fullscreen" else "lock"
            Core.prefs(this).edit().putString("wake_mode", mode).apply()
        }
        root.addView(group)

        root.addView(heading("3. Pemicu (hasil uji)"))
        for (t in Core.TRIGGERS) {
            val sw = Switch(this).apply {
                text = t.label
                isChecked = Core.isEnabled(this@MainActivity, t.id)
                setOnCheckedChangeListener { _, on ->
                    Core.prefs(this@MainActivity).edit().putBoolean("en_${t.id}", on).apply()
                    if (VolumeWakeService.running) startSvc()
                }
            }
            val info = body("")
            triggerInfo[t.id] = info
            root.addView(sw)
            root.addView(info)
        }
        root.addView(button("Reset hitungan") { Core.resetCounters(this) })
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    private fun startSvc(action: String? = null, mode: String? = null) {
        val i = Intent(this, VolumeWakeService::class.java)
        if (action != null) i.action = action
        if (mode != null) i.putExtra(VolumeWakeService.EXTRA_MODE, mode)
        startForegroundService(i)
    }

    private fun wakeTest(mode: String) {
        startSvc(VolumeWakeService.ACTION_TEST, mode)
        Toast.makeText(this, "Matikan layar sekarang. Layar harus menyala sendiri dalam 10 detik.", Toast.LENGTH_LONG).show()
    }

    private fun open(i: Intent) {
        try {
            startActivity(i)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Halaman ini tidak tersedia di HP ini", Toast.LENGTH_SHORT).show()
        }
    }

    private fun accessibilityOn(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        val cn = ComponentName(this, VolumeWakeAccessibility::class.java)
        return enabled.contains(cn.flattenToString()) || enabled.contains(cn.flattenToShortString())
    }

    private fun render() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val sm = getSystemService(SENSOR_SERVICE) as SensorManager
        fun yn(b: Boolean) = if (b) "ya" else "tidak"
        systemInfo.text = "HP: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}\n" +
            "Layanan latar berjalan: ${yn(VolumeWakeService.running)}\n" +
            "Aksesibilitas aktif: ${yn(accessibilityOn())}\n" +
            "Bebas optimasi baterai: ${yn(pm.isIgnoringBatteryOptimizations(packageName))}\n" +
            "Akselerometer: ${yn(sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null)}, " +
            "Sensor jarak: ${yn(sm.getDefaultSensor(Sensor.TYPE_PROXIMITY) != null)}"

        val p = Core.prefs(this)
        val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        for (t in Core.TRIGGERS) {
            val off = p.getInt("${t.id}_off", 0)
            val on = p.getInt("${t.id}_on", 0)
            val last = p.getLong("${t.id}_last", 0L)
            val verdict = when {
                off > 0 -> "✓ Terbaca saat layar mati (${off}x)"
                on > 0 -> "△ Hanya terbaca saat layar menyala (${on}x), saat layar mati belum"
                else -> "… Belum ada kejadian"
            }
            val lastText = if (last > 0L) "\nTerakhir: ${fmt.format(Date(last))}" else ""
            triggerInfo[t.id]?.text = "${t.hint}\n$verdict$lastText"
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun heading(s: String, size: Float = 18f): TextView = TextView(this).apply {
        text = s
        textSize = size
        setTypeface(null, Typeface.BOLD)
        setPadding(0, dp(16), 0, dp(4))
    }

    private fun body(s: String): TextView = TextView(this).apply {
        text = s
        textSize = 14f
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun button(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }
}
