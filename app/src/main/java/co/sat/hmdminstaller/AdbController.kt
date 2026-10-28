package co.sat.hmdminstaller

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.locks.ReentrantLock

/** Satu APK di daftar: file hasil pilihan user (disalin ke cache). */
data class ApkItem(val name: String, val file: File, val builtIn: Boolean = false) {
    val sizeText: String
        get() {
            val n = file.length()
            return when {
                n >= 1048576 -> "%.1f MB".format(n / 1048576.0)
                n >= 1024 -> "${n / 1024} KB"
                else -> "$n B"
            }
        }
}

/**
 * Padanan fungsi ADB di versi Windows: pair, connect, disconnect,
 * install -r, dan dpm set-device-owner. Dipanggil dari thread latar.
 */
class AdbController(context: Context) {

    companion object {
        const val OWNER_COMPONENT = "com.hmdm.launcher/.AdminReceiver"
        private const val KEEPALIVE_SEC = 15L
        private const val PROBE_TIMEOUT_SEC = 6L
    }

    private val appCtx = context.applicationContext
    private val manager = AdbManager(appCtx)
    private val prefs = appCtx.getSharedPreferences("adb", Context.MODE_PRIVATE)

    /** Diisi Activity (bisa diganti/dikosongkan saat Activity dibuat/dihancurkan). */
    @Volatile var log: (String) -> Unit = {}
    /** Dipanggil saat status koneksi berubah (mis. putus sendiri / tersambung ulang). */
    @Volatile var onStateChanged: () -> Unit = {}

    /** Alamat connect terakhir yang berhasil; disimpan agar bisa disambung ulang otomatis. */
    @Volatile var lastAddr: String? = prefs.getString("last_addr", null)
        private set
    @Volatile var currentAddr: String? = null
        private set

    /** Satu operasi ADB pada satu waktu; keepalive mengalah bila operasi sedang jalan. */
    private val opLock = ReentrantLock()
    private val probeExec = Executors.newCachedThreadPool { r -> Thread(r).apply { isDaemon = true } }
    private var keepAlive: ScheduledExecutorService? = null

    val isConnected: Boolean
        get() = try { manager.isConnected } catch (_: Throwable) { false }

    fun pair(addr: String, code: String): Boolean {
        if (addr.isEmpty() || code.isEmpty()) {
            log("Isi IP dan Port pairing dan kode pairing terlebih dahulu.")
            return false
        }
        val (host, port) = split(addr) ?: run { log("Alamat pairing tidak valid: $addr"); return false }
        log("> adb pair $addr ******")
        return locked {
            try {
                manager.pair(host, port, code)
                log("Successfully paired to $addr")
                true
            } catch (e: Throwable) {
                log("Pairing gagal: ${e.message ?: e.javaClass.simpleName}")
                false
            }
        }
    }

    fun connect(addr: String): Boolean {
        if (addr.isEmpty()) {
            log("Isi IP dan Port connect terlebih dahulu.")
            return false
        }
        val (host, port) = split(addr) ?: run { log("Alamat connect tidak valid: $addr"); return false }
        return locked {
            var ok = false
            for (attempt in 1..2) {
                if (ok) break
                if (attempt > 1) {
                    log("Connect gagal, mencoba lagi (1x)...")
                    Thread.sleep(1500)
                }
                log("> adb connect $addr")
                ok = rawConnect(host, port)
                if (ok) log("connected to $addr")
            }
            if (ok) onConnected(addr)
            ok
        }
    }

    fun disconnect(): Boolean {
        log("> adb disconnect")
        stopKeepAlive()
        currentAddr = null
        return locked {
            try {
                manager.disconnect()
                log("disconnected")
                true
            } catch (e: Throwable) {
                log("Disconnect error: ${e.message ?: e.javaClass.simpleName}")
                false
            }
        }
    }

    /** Pasang semua APK berurutan; yang gagal tidak menghentikan yang lain. */
    fun install(apks: List<ApkItem>): Boolean = locked { installLocked(apks) }

    private fun installLocked(apks: List<ApkItem>): Boolean {
        if (!ensureConnected()) return false
        if (apks.isEmpty()) {
            log("Daftar APK kosong. Tambahkan APK terlebih dahulu.")
            return false
        }
        var ok = 0
        val failed = StringBuilder()
        apks.forEachIndexed { i, apk ->
            log("")
            log("[${i + 1}/${apks.size}] ${apk.name}")
            if (!apk.file.exists()) {
                log("File tidak ditemukan: ${apk.file}")
                failed.appendLine("  - ${apk.name} (file tidak ditemukan)")
                return@forEachIndexed
            }
            log("> adb install -r ${apk.name}")
            val out = try { installOne(apk.file) } catch (e: Throwable) {
                "Error: ${e.message ?: e.javaClass.simpleName}"
            }
            out.lines().filter { it.isNotBlank() }.forEach { log(it.trim()) }
            if (out.contains("Success", ignoreCase = true)) ok++ else failed.appendLine("  - ${apk.name}")
        }
        log("")
        log("Ringkasan install: $ok berhasil, ${apks.size - ok} gagal dari ${apks.size} APK.")
        if (failed.isNotEmpty()) {
            log("Yang gagal:")
            log(failed.toString().trimEnd())
            return false
        }
        return true
    }

    fun setDeviceOwner(): Boolean = locked { setDeviceOwnerLocked() }

    private fun setDeviceOwnerLocked(): Boolean {
        if (!ensureConnected()) return false
        log("> adb shell dpm set-device-owner $OWNER_COMPONENT")
        val out = try { shell("dpm set-device-owner $OWNER_COMPONENT") } catch (e: Throwable) {
            "Error: ${e.message ?: e.javaClass.simpleName}"
        }
        out.lines().filter { it.isNotBlank() }.forEach { log(it.trim()) }
        if (!out.contains("Success", ignoreCase = true)) {
            log("Set device owner gagal. Pastikan device baru di-reset dan belum ada akun (Google/lainnya).")
            return false
        }
        return true
    }

    fun installAndOwner(apks: List<ApkItem>): Boolean {
        // Walau sebagian APK gagal, tetap coba set device owner (HMDM mungkin sudah terpasang).
        val installOk = install(apks)
        val ownerOk = setDeviceOwner()
        return installOk && ownerOk
    }

    // ---------- internal ----------

    /**
     * Pastikan koneksi benar-benar hidup (bukan sekadar socket yang tampak terbuka),
     * dan sambung ulang otomatis ke alamat terakhir bila sudah putus.
     */
    private fun ensureConnected(): Boolean {
        if (isConnected && probe()) return true
        val addr = currentAddr ?: lastAddr
        if (addr == null) {
            log("Belum terhubung ke device. Klik Connect terlebih dahulu.")
            return false
        }
        log("Koneksi terputus. Menyambung ulang ke $addr ...")
        if (reconnect(addr)) return true
        log("Gagal menyambung ulang. Pastikan Wireless debugging di HP target masih aktif lalu klik Connect " +
            "(port bisa berubah setiap Wireless debugging dimatikan/dihidupkan).")
        return false
    }

    private fun rawConnect(host: String, port: Int): Boolean = try {
        try { manager.disconnect() } catch (_: Throwable) { }
        manager.connect(host, port)
        manager.isConnected.also { if (!it) log("Connect gagal: tidak terhubung") }
    } catch (e: Throwable) {
        log("Connect gagal: ${e.message ?: e.javaClass.simpleName}")
        false
    }

    private fun reconnect(addr: String): Boolean {
        val (host, port) = split(addr) ?: return false
        for (attempt in 1..3) {
            if (rawConnect(host, port)) {
                log("connected to $addr")
                onConnected(addr)
                return true
            }
            if (attempt < 3) Thread.sleep(2000L * attempt)
        }
        currentAddr = null
        stopKeepAlive()
        onStateChanged()
        return false
    }

    /** Uji koneksi dengan perintah ringan; socket setengah-mati tidak akan menjawab. */
    private fun probe(): Boolean {
        val f = probeExec.submit<Boolean> {
            val out = shell("echo ok")
            out.contains("ok")
        }
        return try {
            f.get(PROBE_TIMEOUT_SEC, TimeUnit.SECONDS)
        } catch (_: TimeoutException) {
            f.cancel(true)
            try { manager.disconnect() } catch (_: Throwable) { }
            false
        } catch (_: Throwable) {
            false
        }
    }

    private fun onConnected(addr: String) {
        currentAddr = addr
        lastAddr = addr
        prefs.edit().putString("last_addr", addr).apply()
        startKeepAlive()
        onStateChanged()
    }

    // ---------- keepalive ----------

    private fun startKeepAlive() {
        if (keepAlive != null) return
        ContextCompat.startForegroundService(appCtx, Intent(appCtx, AdbService::class.java))
        keepAlive = Executors.newSingleThreadScheduledExecutor { r -> Thread(r).apply { isDaemon = true } }.also { ex ->
            ex.scheduleWithFixedDelay({
                if (!opLock.tryLock()) return@scheduleWithFixedDelay   // sedang ada operasi lain
                try {
                    val addr = currentAddr ?: return@scheduleWithFixedDelay
                    if (!(isConnected && probe())) {
                        log("Koneksi ADB terputus, menyambung ulang otomatis...")
                        onStateChanged()
                        if (reconnect(addr)) log("Tersambung kembali ke $addr")
                        else log("Gagal menyambung ulang. Klik Connect (cek port Wireless debugging).")
                    }
                } catch (_: Throwable) {
                } finally {
                    opLock.unlock()
                }
            }, KEEPALIVE_SEC, KEEPALIVE_SEC, TimeUnit.SECONDS)
        }
    }

    private fun stopKeepAlive() {
        keepAlive?.shutdownNow()
        keepAlive = null
        appCtx.stopService(Intent(appCtx, AdbService::class.java))
    }

    /** Dipanggil saat app kembali ke depan: periksa koneksi, sambung ulang bila putus. */
    fun checkNow() {
        val addr = currentAddr ?: return
        if (!opLock.tryLock()) return
        try {
            if (!(isConnected && probe())) {
                log("Koneksi ADB terputus selama app tidak dipakai. Menyambung ulang...")
                if (reconnect(addr)) log("Tersambung kembali ke $addr")
                else log("Gagal menyambung ulang. Klik Connect (cek port Wireless debugging).")
            }
        } finally {
            opLock.unlock()
            onStateChanged()
        }
    }

    private inline fun <T> locked(block: () -> T): T {
        opLock.lock()
        try { return block() } finally { opLock.unlock() }
    }

    private fun shell(cmd: String): String {
        val stream = manager.openStream("shell:$cmd")
        try {
            return readAll(stream.openInputStream())
        } finally {
            try { stream.close() } catch (_: Throwable) { }
        }
    }

    /** Setara `adb install -r`: stream APK ke `cmd package install -S <ukuran>` lewat service exec. */
    private fun installOne(file: File): String {
        val stream = manager.openStream("exec:cmd package install -r -S ${file.length()}")
        // Pengaman: tutup stream bila tidak selesai dalam 5 menit.
        val watchdog = Thread {
            try { Thread.sleep(300_000); stream.close() } catch (_: InterruptedException) { } catch (_: Throwable) { }
        }.apply { isDaemon = true; start() }
        try {
            val os = stream.openOutputStream()
            file.inputStream().use { input ->
                val buf = ByteArray(32 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    os.write(buf, 0, n)
                }
            }
            os.flush()
            return readAll(stream.openInputStream())
        } finally {
            watchdog.interrupt()
            try { stream.close() } catch (_: Throwable) { }
        }
    }

    private fun readAll(input: InputStream): String {
        val bos = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            bos.write(buf, 0, n)
        }
        return bos.toString("UTF-8")
    }

    private fun split(addr: String): Pair<String, Int>? {
        val i = addr.lastIndexOf(':')
        if (i <= 0) return null
        val port = addr.substring(i + 1).toIntOrNull() ?: return null
        return addr.substring(0, i) to port
    }
}
