package co.sat.hmdminstaller

import android.content.Context
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentLinkedQueue
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
        private const val TAG = "AdbController"
        private const val KEEPALIVE_SECONDS = 15L
        private const val PROBE_TIMEOUT_SECONDS = 6L
        private const val CONNECT_TIMEOUT_SECONDS = 15L
        private const val MAX_BACKLOG = 200
    }

    private val appCtx = context.applicationContext
    private val manager = AdbManager(appCtx)

    /** Mencegah keepalive/reconnect bentrok dengan operasi user (pair, install, dll). */
    private val opLock = ReentrantLock()
    private val probeExec = Executors.newCachedThreadPool { r -> Thread(r).apply { isDaemon = true } }
    private var keepAlive: ScheduledExecutorService? = null

    /** Alamat connect terakhir yang berhasil; dipakai untuk reconnect otomatis. */
    @Volatile var lastAddr: String? = null
        private set
    @Volatile private var wantConnected = false
    @Volatile private var reconnectFailed = false

    // ---- log: controller hidup lebih lama dari Activity, jadi log ditampung saat UI tidak ada ----
    @Volatile private var sink: ((String) -> Unit)? = null
    @Volatile private var stateListener: (() -> Unit)? = null
    private val backlog = ConcurrentLinkedQueue<String>()

    fun attach(logSink: (String) -> Unit, onState: () -> Unit) {
        sink = logSink
        stateListener = onState
        while (true) logSink(backlog.poll() ?: break)
    }

    fun detach() {
        sink = null
        stateListener = null
    }

    private fun log(line: String) {
        Log.d(TAG, line)
        val s = sink
        if (s != null) s(line) else {
            backlog.add(line)
            while (backlog.size > MAX_BACKLOG) backlog.poll()
        }
    }

    private fun notifyState() { stateListener?.invoke() }

    val isConnected: Boolean
        get() = try { manager.isConnected } catch (_: Throwable) { false }

    fun pair(addr: String, code: String): Boolean = withLock {
        if (addr.isEmpty() || code.isEmpty()) {
            log("Isi IP dan Port pairing dan kode pairing terlebih dahulu.")
            return@withLock false
        }
        val (host, port) = split(addr) ?: run { log("Alamat pairing tidak valid: $addr"); return@withLock false }
        log("> adb pair $addr ******")
        val f = probeExec.submit(Callable { manager.pair(host, port, code) })
        try {
            f.get(30, TimeUnit.SECONDS)
            log("Successfully paired to $addr")
            true
        } catch (_: TimeoutException) {
            f.cancel(true)
            log("Pairing gagal: timeout. Cek IP/port pairing dan kodenya (kode berubah tiap dialog dibuka ulang).")
            false
        } catch (e: java.util.concurrent.ExecutionException) {
            val c = e.cause ?: e
            log("Pairing gagal: ${c.message ?: c.javaClass.simpleName}")
            false
        } catch (e: Throwable) {
            log("Pairing gagal: ${e.message ?: e.javaClass.simpleName}")
            false
        }
    }

    fun connect(addr: String): Boolean {
        // Hentikan keepalive dulu agar tidak memegang kunci / ikut menyambung saat user menekan Connect.
        wantConnected = false
        stopKeepAlive()
        return connectLocked(addr)
    }

    private fun connectLocked(addr: String): Boolean = withLock {
        if (addr.isEmpty()) {
            log("Isi IP dan Port connect terlebih dahulu.")
            return@withLock false
        }
        if (split(addr) == null) { log("Alamat connect tidak valid: $addr"); return@withLock false }
        var ok = false
        for (attempt in 1..2) {
            if (ok) break
            if (attempt > 1) {
                log("Connect gagal, mencoba lagi (1x)...")
                Thread.sleep(1500)
            }
            log("> adb connect $addr")
            ok = connectOnce(addr)
        }
        if (ok) {
            lastAddr = addr
            wantConnected = true
            reconnectFailed = false
            startKeepAlive()
            KeepAliveService.start(appCtx)
        }
        ok
    }

    fun disconnect(): Boolean {
        wantConnected = false
        stopKeepAlive()
        return disconnectLocked()
    }

    private fun disconnectLocked(): Boolean = withLock {
        log("> adb disconnect")
        KeepAliveService.stop(appCtx)
        try {
            manager.disconnect()
            log("disconnected")
            true
        } catch (e: Throwable) {
            log("Disconnect error: ${e.message ?: e.javaClass.simpleName}")
            false
        }
    }

    /** Pasang semua APK berurutan; yang gagal tidak menghentikan yang lain. */
    fun install(apks: List<ApkItem>): Boolean = withLock {
        if (!ensureConnected()) return@withLock false
        if (apks.isEmpty()) {
            log("Daftar APK kosong. Tambahkan APK terlebih dahulu.")
            return@withLock false
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
            return@withLock false
        }
        true
    }

    fun setDeviceOwner(): Boolean = withLock {
        if (!ensureConnected()) return@withLock false
        log("> adb shell dpm set-device-owner $OWNER_COMPONENT")
        val out = try { shell("dpm set-device-owner $OWNER_COMPONENT") } catch (e: Throwable) {
            "Error: ${e.message ?: e.javaClass.simpleName}"
        }
        out.lines().filter { it.isNotBlank() }.forEach { log(it.trim()) }
        if (!out.contains("Success", ignoreCase = true)) {
            log("Set device owner gagal. Pastikan device baru di-reset dan belum ada akun (Google/lainnya).")
            return@withLock false
        }
        true
    }

    fun installAndOwner(apks: List<ApkItem>): Boolean = withLock {
        // Walau sebagian APK gagal, tetap coba set device owner (HMDM mungkin sudah terpasang).
        val installOk = install(apks)
        val ownerOk = setDeviceOwner()
        installOk && ownerOk
    }

    // ---------- internal ----------

    /** Satu kali percobaan connect (tanpa log "> adb connect"). Membersihkan koneksi basi dulu. */
    private fun connectOnce(addr: String): Boolean {
        val (host, port) = split(addr) ?: return false
        try { if (manager.isConnected) manager.disconnect() } catch (_: Throwable) { }
        // libadb-android bisa menunggu tanpa batas bila HP target tidak membalas (port berubah,
        // sesi lama menggantung). Jalankan di thread lain dengan batas waktu supaya tidak "loading" terus.
        val f = probeExec.submit(Callable {
            manager.connect(host, port)
            manager.isConnected
        })
        return try {
            val ok = f.get(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (ok) log("connected to $addr") else log("Connect gagal: tidak terhubung")
            ok
        } catch (_: TimeoutException) {
            f.cancel(true)
            try { manager.disconnect() } catch (_: Throwable) { }
            log("Connect gagal: timeout ${CONNECT_TIMEOUT_SECONDS}s. Cek IP/port connect (port berubah tiap Wireless debugging dinyalakan ulang).")
            false
        } catch (e: java.util.concurrent.ExecutionException) {
            val c = e.cause ?: e
            log("Connect gagal: ${c.message ?: c.javaClass.simpleName}")
            false
        } catch (e: Throwable) {
            log("Connect gagal: ${e.message ?: e.javaClass.simpleName}")
            false
        }
    }

    /**
     * Cek koneksi benar-benar hidup: `isConnected` hanya melihat soket lokal, tidak tahu kalau
     * HP target sudah menutup sesi (mis. HP tidur / Wi-Fi putus). Jadi kirim `echo` sungguhan.
     */
    private fun probe(): Boolean {
        if (!isConnected) return false
        val f = probeExec.submit(Callable {
            val s = manager.openStream("shell:echo ok")
            try { readAll(s.openInputStream()).contains("ok") } finally { try { s.close() } catch (_: Throwable) { } }
        })
        return try {
            f.get(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (_: TimeoutException) {
            f.cancel(true)
            try { manager.disconnect() } catch (_: Throwable) { }  // bebaskan thread yang menggantung
            false
        } catch (_: Throwable) {
            false
        }
    }

    private fun startKeepAlive() {
        if (keepAlive != null) return
        keepAlive = Executors.newSingleThreadScheduledExecutor { r -> Thread(r).apply { isDaemon = true } }.also {
            it.scheduleWithFixedDelay({ keepAliveTick() }, KEEPALIVE_SECONDS, KEEPALIVE_SECONDS, TimeUnit.SECONDS)
        }
    }

    private fun stopKeepAlive() {
        keepAlive?.shutdownNow()
        keepAlive = null
    }

    private fun keepAliveTick() {
        try {
            val addr = lastAddr
            if (!wantConnected || addr == null) return
            if (!opLock.tryLock()) return          // user sedang menjalankan operasi: jangan ganggu
            try {
                if (probe()) {
                    if (reconnectFailed) { reconnectFailed = false; notifyState() }
                    return
                }
                if (!reconnectFailed) {
                    log("Koneksi ADB terputus, menyambung ulang ke $addr ...")
                    notifyState()
                }
                if (connectOnce(addr)) {
                    reconnectFailed = false
                    log("Tersambung kembali ke $addr")
                } else if (!reconnectFailed) {
                    reconnectFailed = true
                    log("Reconnect gagal. Pastikan Wireless debugging di HP target masih ON dan Wi-Fi sama. " +
                        "Jika port berubah, isi port baru lalu klik Connect.")
                }
                notifyState()
            } finally {
                opLock.unlock()
            }
        } catch (e: Throwable) {
            Log.w(TAG, "keepalive error", e)
        }
    }

    private fun ensureConnected(): Boolean {
        if (probe()) return true
        val addr = lastAddr
        if (wantConnected && addr != null) {
            log("Koneksi terputus, menyambung ulang ke $addr ...")
            if (connectOnce(addr)) { notifyState(); return true }
            notifyState()
        }
        log("Belum terhubung ke device. Klik Connect terlebih dahulu.")
        return false
    }

    private inline fun <T> withLock(block: () -> T): T {
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
