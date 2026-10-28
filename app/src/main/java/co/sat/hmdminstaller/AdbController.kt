package co.sat.hmdminstaller

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

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
class AdbController(context: Context, private val log: (String) -> Unit) {

    companion object {
        const val OWNER_COMPONENT = "com.hmdm.launcher/.AdminReceiver"
    }

    private val appContext = context.applicationContext
    @Volatile private var manager = AdbManager(appContext)
    @Volatile private var lastAddr: String? = null

    val isConnected: Boolean
        get() = try { manager.isConnected } catch (_: Throwable) { false }

    fun pair(addr: String, code: String): Boolean {
        if (addr.isEmpty() || code.isEmpty()) {
            log("Isi IP dan Port pairing dan kode pairing terlebih dahulu.")
            return false
        }
        val (host, port) = split(addr) ?: run { log("Alamat pairing tidak valid: $addr"); return false }
        log("> adb pair $addr ******")
        return try {
            val m = manager
            callWithTimeout(25, "pairing") { m.pair(host, port, code) }
            log("Successfully paired to $addr")
            true
        } catch (e: Throwable) {
            log("Pairing gagal: ${e.message ?: e.javaClass.simpleName}")
            false
        }
    }

    fun connect(addr: String): Boolean {
        if (addr.isEmpty()) {
            log("Isi IP dan Port connect terlebih dahulu.")
            return false
        }
        val (host, port) = split(addr) ?: run { log("Alamat connect tidak valid: $addr"); return false }
        var ok = false
        for (attempt in 1..2) {
            if (ok) break
            if (attempt > 1) {
                log("Connect gagal, mencoba lagi (1x)...")
                Thread.sleep(1500)
            }
            log("> adb connect $addr")
            // Putuskan koneksi lama dulu: library langsung return true bila masih terhubung
            // ke device lain, sehingga IP yang tidak ada pun tampak "berhasil".
            safeDisconnect()
            try {
                // Cek cepat: IP/port tidak terjangkau -> gagal dalam 3 detik (bukan menunggu lama).
                try {
                    Socket().use { it.connect(InetSocketAddress(host, port), 3000) }
                } catch (e: Throwable) {
                    log("Connect gagal: $addr tidak terjangkau (${e.javaClass.simpleName})")
                    return false
                }
                val connected = connectWithTimeout(host, port, 10)
                if (!connected || !manager.isConnected) {
                    log("Connect gagal: tidak terhubung")
                } else {
                    // Verifikasi nyata: jalankan perintah di device tujuan.
                    val out = try { shell("echo hmdm-ok") } catch (e: Throwable) {
                        log("Verifikasi gagal: ${e.javaClass.simpleName}: ${e.message}"); ""
                    }
                    ok = out.contains("hmdm-ok")
                    if (ok) { lastAddr = addr; log("connected to $addr") } else log("Connect gagal: device tidak merespons")
                }
                if (!ok) safeDisconnect()
            } catch (e: Throwable) {
                log("Connect gagal: ${e.javaClass.simpleName}: ${e.message}")
                safeDisconnect()
            }
        }
        return ok
    }

    fun disconnect(): Boolean {
        log("> adb disconnect")
        lastAddr = null
        safeDisconnect()
        log("disconnected")
        return true
    }

    /** Pasang semua APK berurutan; yang gagal tidak menghentikan yang lain. */
    fun install(apks: List<ApkItem>): Boolean {
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

    fun setDeviceOwner(): Boolean {
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

    /** Cek koneksi benar-benar hidup; kalau putus, sambung ulang otomatis ke alamat terakhir. */
    private fun ensureConnected(): Boolean {
        if (isConnected && probe()) return true
        val addr = lastAddr
        if (addr == null) {
            log("Belum terhubung ke device. Klik Connect terlebih dahulu.")
            return false
        }
        log("Koneksi terputus, menyambung ulang ke $addr...")
        if (connect(addr)) return true
        log("Sambung ulang gagal. Cek Wireless debugging di HP lalu klik Connect.")
        return false
    }

    private fun probe(): Boolean =
        try { shell("echo hmdm-ok", 8).contains("hmdm-ok") } catch (_: Throwable) { false }

    /**
     * Jalankan [block] di thread daemon dengan batas waktu keras. I/O socket yang macet tidak
     * bisa di-interrupt, jadi bila timeout kita tinggalkan thread-nya dan panggil pemanggil dengan error.
     */
    private fun <T> callWithTimeout(seconds: Long, what: String, block: () -> T): T {
        val ex = Executors.newSingleThreadExecutor { r -> Thread(r).apply { isDaemon = true } }
        val f = ex.submit(Callable { block() })
        try {
            return f.get(seconds, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            f.cancel(true)
            abandonManager()
            throw RuntimeException("$what timeout ${seconds}s (cek Wireless debugging & dialog di HP)")
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        } finally {
            ex.shutdownNow()
        }
    }

    /** Buang koneksi yang macet (ditutup di thread terpisah) dan pakai manager baru. */
    private fun abandonManager() {
        val old = manager
        manager = AdbManager(appContext)
        Thread { try { old.close() } catch (_: Throwable) { } }.apply { isDaemon = true }.start()
    }

    /** Disconnect yang tidak pernah menggantung: maksimal 3 detik, lalu manager diganti. */
    private fun safeDisconnect() {
        val m = manager
        val t = Thread { try { m.disconnect() } catch (_: Throwable) { } }.apply { isDaemon = true; start() }
        t.join(3000)
        if (t.isAlive) abandonManager()
    }

    private fun connectWithTimeout(host: String, port: Int, seconds: Long): Boolean {
        val m = manager
        return callWithTimeout(seconds, "connect") { m.connect(host, port) }
    }

    private fun shell(cmd: String, seconds: Long = 20): String {
        val m = manager
        return callWithTimeout(seconds, "shell") {
            val stream = m.openStream("shell:$cmd")
            try {
                readAll(stream.openInputStream())
            } finally {
                try { stream.close() } catch (_: Throwable) { }
            }
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
