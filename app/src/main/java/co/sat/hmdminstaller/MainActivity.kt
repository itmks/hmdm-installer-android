package co.sat.hmdminstaller

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.Manifest
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import java.io.File
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var edtPairIp: TextInputEditText
    private lateinit var edtPairPort: TextInputEditText
    private lateinit var edtPairCode: TextInputEditText
    private lateinit var edtConnIp: TextInputEditText
    private lateinit var edtConnPort: TextInputEditText
    private lateinit var txtLog: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var txtConn: TextView
    private lateinit var txtStatus: TextView
    private lateinit var txtApkTitle: TextView
    private lateinit var txtApkEmpty: TextView
    private lateinit var statusDot: View
    private lateinit var progress: ProgressBar
    private lateinit var apkList: LinearLayout
    private lateinit var buttons: List<MaterialButton>

    private val worker = Executors.newSingleThreadExecutor()
    private val apks = mutableListOf<ApkItem>()
    private val checked = mutableSetOf<ApkItem>()
    private val adb: AdbController get() = (application as App).adb
    @Volatile private var busy = false
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val pickApks = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        var added = 0
        var skipped = 0
        uris.forEach { uri ->
            val item = copyToCache(uri)
            if (item == null) skipped++ else { apks.add(item); added++ }
        }
        if (added > 0) log("Ditambahkan $added APK. Total: ${apks.size}")
        if (skipped > 0) log("$skipped file dilewati (bukan .apk / gagal dibaca).")
        refreshApkList()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        edtPairIp = findViewById(R.id.edtPairIp)
        edtPairPort = findViewById(R.id.edtPairPort)
        edtPairCode = findViewById(R.id.edtPairCode)
        edtConnIp = findViewById(R.id.edtConnIp)
        edtConnPort = findViewById(R.id.edtConnPort)
        txtLog = findViewById(R.id.txtLog)
        logScroll = findViewById(R.id.logScroll)
        txtConn = findViewById(R.id.txtConn)
        txtStatus = findViewById(R.id.txtStatus)
        txtApkTitle = findViewById(R.id.txtApkTitle)
        txtApkEmpty = findViewById(R.id.txtApkEmpty)
        statusDot = findViewById(R.id.statusDot)
        progress = findViewById(R.id.progress)
        apkList = findViewById(R.id.apkList)

        val btnPair: MaterialButton = findViewById(R.id.btnPair)
        val btnConnect: MaterialButton = findViewById(R.id.btnConnect)
        val btnDisconnect: MaterialButton = findViewById(R.id.btnDisconnect)
        val btnAddApk: MaterialButton = findViewById(R.id.btnAddApk)
        val btnRemoveApk: MaterialButton = findViewById(R.id.btnRemoveApk)
        val btnClearApk: MaterialButton = findViewById(R.id.btnClearApk)
        val btnInstallOnly: MaterialButton = findViewById(R.id.btnInstallOnly)
        val btnOwner: MaterialButton = findViewById(R.id.btnOwner)
        val btnAll: MaterialButton = findViewById(R.id.btnAll)
        val btnCopyLog: MaterialButton = findViewById(R.id.btnCopyLog)
        val btnClearLog: MaterialButton = findViewById(R.id.btnClearLog)
        buttons = listOf(btnPair, btnConnect, btnDisconnect, btnAddApk, btnRemoveApk,
            btnClearApk, btnInstallOnly, btnOwner, btnAll)

        // Izin notifikasi (Android 13+) agar notifikasi "ADB terhubung" dari foreground service terlihat.
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)

        btnPair.setOnClickListener {
            val addr = addr(edtPairIp.text.toString(), edtPairPort.text.toString())
            val code = clean(edtPairCode.text.toString())
            run("Pairing") { doPair(addr, code) }
        }
        btnConnect.setOnClickListener {
            val addr = addr(edtConnIp.text.toString(), edtConnPort.text.toString())
            run("Connect") { doConnect(addr) }
        }
        btnDisconnect.setOnClickListener {
            run("Disconnect") { doDisconnect() }
        }
        btnAddApk.setOnClickListener {
            pickApks.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream", "*/*"))
        }
        btnRemoveApk.setOnClickListener {
            val sel = apks.filter { it in checked }
            if (sel.isEmpty()) { log("Tidak ada APK yang dipilih untuk dihapus."); return@setOnClickListener }
            sel.forEach { log("Dihapus dari daftar: ${it.name}"); if (!it.builtIn) it.file.delete() }
            apks.removeAll(sel.toSet()); checked.removeAll(sel.toSet())
            log("Dihapus ${sel.size} APK. Sisa: ${apks.size}")
            refreshApkList()
        }
        btnClearApk.setOnClickListener {
            val n = apks.size
            apks.filter { !it.builtIn }.forEach { it.file.delete() }
            apks.clear(); checked.clear()
            log("Daftar APK dikosongkan ($n APK dihapus dari daftar).")
            refreshApkList()
        }
        btnInstallOnly.setOnClickListener {
            val list = apks.toList()
            run("Install APK") { adb.install(list) }
        }
        btnOwner.setOnClickListener {
            run("Set Device Owner") { doSetOwner() }
        }
        btnAll.setOnClickListener {
            val pAddr = addr(edtPairIp.text.toString(), edtPairPort.text.toString())
            val pCode = clean(edtPairCode.text.toString())
            val cAddr = addr(edtConnIp.text.toString(), edtConnPort.text.toString())
            val list = apks.toList()
            run("Jalankan semua") {
                if (pAddr.isNotEmpty() && pCode.isNotEmpty()) {
                    if (!doPair(pAddr, pCode)) return@run false
                } else {
                    log("[Pair] dilewati (IP / Port / kode pairing kosong).")
                }
                if (!doConnect(cAddr)) return@run false
                adb.installAndOwner(list)
            }
        }
        btnCopyLog.setOnClickListener {
            if (txtLog.length() > 0) {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("log", txtLog.text))
                Toast.makeText(this, "Log disalin", Toast.LENGTH_SHORT).show()
            }
        }
        btnClearLog.setOnClickListener {
            txtLog.text = ""
            log("Log dibersihkan.")
        }

        refreshApkList()
        refreshHeader()
        log("Isi IP dan Port lalu klik tombol.")
    }

    override fun onStart() {
        super.onStart()
        // Controller hidup di Application; Activity hanya menempel selama terlihat.
        adb.attach({ line -> runOnUiThread { log(line) } }, { runOnUiThread { refreshHeader() } })
        refreshHeader()
    }

    override fun onStop() {
        adb.detach()
        super.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        worker.shutdown()
    }

    // ---------- Langkah (jalan di thread kerja) ----------

    private fun doPair(addr: String, code: String): Boolean {
        val ok = adb.pair(addr, code)
        if (ok) runOnUiThread { refreshHeader() }
        return ok
    }

    private fun doConnect(addr: String): Boolean {
        val ok = adb.connect(addr)
        runOnUiThread { refreshHeader() }
        return ok
    }

    private fun doDisconnect(): Boolean {
        val ok = adb.disconnect()
        runOnUiThread { refreshHeader() }
        return ok
    }

    private fun doSetOwner(): Boolean {
        val ok = adb.setDeviceOwner()
        runOnUiThread { refreshHeader() }
        return ok
    }

    private fun run(title: String, work: () -> Boolean) {
        if (busy) return
        busy = true
        setBusy(true, "Sedang berjalan: $title...")
        worker.execute {
            var ok = false
            try { ok = work() } catch (e: Throwable) {
                runOnUiThread { log("ERROR: ${e.message ?: e.javaClass.simpleName}") }
            }
            runOnUiThread {
                log(if (ok) "=== SELESAI: BERHASIL ===" else "=== GAGAL / BERHENTI ===")
                log("")
                busy = false
                setBusy(false, if (ok) "Selesai: $title" else "Gagal / berhenti: $title (lihat log)")
                refreshHeader()
            }
        }
    }

    private fun setBusy(on: Boolean, status: String) {
        buttons.forEach { it.isEnabled = !on }
        progress.visibility = if (on) View.VISIBLE else View.GONE
        txtStatus.text = status
    }

    // ---------- UI helper ----------

    private fun refreshHeader() {
        val addr = adb.lastAddr
        val on = addr != null && adb.isConnected
        txtConn.text = if (on) "Terhubung · $addr" else "Belum terhubung · Pair lalu Connect untuk mulai"
        statusDot.backgroundTintList = ColorStateList.valueOf(if (on) Color.rgb(74, 222, 128) else Color.rgb(148, 163, 184))
    }

    private fun refreshApkList() {
        apkList.removeAllViews()
        apks.forEachIndexed { i, item ->
            val cb = CheckBox(this)
            cb.text = "%02d  %s  ·  %s".format(i + 1, item.name, item.sizeText)
            cb.textSize = 14f
            cb.setPadding(cb.paddingLeft, (8 * resources.displayMetrics.density).toInt(), cb.paddingRight, (8 * resources.displayMetrics.density).toInt())
            cb.isChecked = item in checked
            cb.setOnCheckedChangeListener { _, isChecked -> if (isChecked) checked.add(item) else checked.remove(item) }
            apkList.addView(cb)
        }
        txtApkEmpty.visibility = if (apks.isEmpty()) View.VISIBLE else View.GONE
        txtApkTitle.text = if (apks.isEmpty()) "Daftar APK" else "Daftar APK (${apks.size})"
        refreshHeader()
    }

    private fun log(s: String) {
        val sp = SpannableString(s + "\n")
        sp.setSpan(ForegroundColorSpan(lineColor(s)), 0, sp.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        txtLog.append(sp)
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun lineColor(s: String): Int {
        val l = s.lowercase()
        return when {
            s.startsWith("===") ->
                if (l.contains("berhasil")) Color.rgb(74, 222, 128) else Color.rgb(248, 113, 113)
            s.startsWith("> ") -> Color.rgb(56, 189, 248)
            l.startsWith("ringkasan") -> Color.WHITE
            l.startsWith("peringatan") -> Color.rgb(251, 191, 36)
            l.contains("sudah ada") || l.contains("dilewati") -> Color.rgb(251, 191, 36)
            l.contains("fail") || l.contains("gagal") || l.contains("error") || l.contains("cannot") ||
                l.contains("tidak ditemukan") || l.contains("timeout") -> Color.rgb(248, 113, 113)
            l.contains("success") || l.contains("berhasil") || l.contains("connected to") ||
                l.contains("paired") -> Color.rgb(74, 222, 128)
            s.startsWith("[") || s.startsWith("Isi ") || s.startsWith("Daftar APK kosong") -> Color.rgb(252, 211, 77)
            else -> Color.rgb(203, 213, 225)
        }
    }

    private fun clean(s: String) = s.trim().replace("\"", "").replace(" ", "")

    /** Gabung IP + port jadi "ip:port"; toleran bila user menempel "ip:port" di kolom IP. */
    private fun addr(ipText: String, portText: String): String {
        var ip = clean(ipText)
        var port = clean(portText)
        val i = ip.lastIndexOf(':')
        if (i > 0 && port.isEmpty()) {
            port = ip.substring(i + 1)
            ip = ip.substring(0, i)
        }
        return if (ip.isEmpty() || port.isEmpty()) "" else "$ip:$port"
    }

    private fun copyToCache(uri: Uri): ApkItem? {
        var name = "file-${System.currentTimeMillis()}.apk"
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.let { name = it }
        }
        if (!name.endsWith(".apk", ignoreCase = true)) return null
        if (apks.any { it.name.equals(name, ignoreCase = true) }) {
            log("APK sudah ada di daftar, tidak ditambahkan lagi: $name")
            return null
        }
        return try {
            val dir = File(cacheDir, "picked").apply { mkdirs() }
            val f = File(dir, name)
            contentResolver.openInputStream(uri)!!.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
            ApkItem(name, f)
        } catch (e: Exception) {
            log("Gagal membaca $name: ${e.message}")
            null
        }
    }
}
