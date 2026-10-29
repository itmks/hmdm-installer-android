package co.sat.hmdminstaller

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.KeyFactory
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec

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
 *
 * Dua jalur koneksi: Wireless debugging (TCP, libadb-android) dan USB debugging (USB host, lihat UsbAdb.kt).
 * Hanya satu yang aktif: menyambung lewat satu jalur memutus jalur lainnya.
 */
class AdbController(context: Context, private val log: (String) -> Unit) {

    companion object {
        const val OWNER_COMPONENT = "com.hmdm.launcher/.AdminReceiver"
    }

    private val appContext = context.applicationContext
    private val manager = AdbManager(appContext)
    private val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
    @Volatile private var usb: UsbAdbConnection? = null
    @Volatile private var usbName: String? = null

    /** Jalur Wireless debugging (TCP). */
    private val wirelessLink = object : AdbLink {
        override val isConnected: Boolean
            get() = try { manager.isConnected } catch (_: Throwable) { false }

        override fun open(service: String): AdbIo {
            val s = manager.openStream(service)
            return object : AdbIo {
                override fun input(): InputStream = s.openInputStream()
                override fun output(): java.io.OutputStream = s.openOutputStream()
                override fun close() { s.close() }
            }
        }
    }

    /** Jalur yang sedang terhubung (USB lebih dulu), atau null. */
    private val link: AdbLink?
        get() = usb?.takeIf { it.isConnected } ?: wirelessLink.takeIf { it.isConnected }

    val isConnected: Boolean
        get() = link != null

    val isUsbConnected: Boolean
        get() = usb?.isConnected == true

    /** Nama perangkat USB yang sedang terhubung (untuk header), atau null. */
    val usbLabel: String?
        get() = if (isUsbConnected) usbName else null

    /**
     * Cek ringan apakah koneksi benar-benar hidup (bukan sekadar flag): jalankan `echo` di device.
     * Dipanggil dari thread pemantau; stream ditutup paksa bila tidak membalas dalam 4 detik.
     */
    fun probe(): Boolean {
        val l = link ?: return false
        var stream: AdbIo? = null
        val watchdog = Thread {
            try { Thread.sleep(4_000); stream?.close() } catch (_: InterruptedException) { } catch (_: Throwable) { }
        }.apply { isDaemon = true; start() }
        return try {
            val s = l.open("shell:echo ok")
            stream = s
            readAll(s.input()).contains("ok")
        } catch (_: Throwable) {
            false
        } finally {
            watchdog.interrupt()
            try { stream?.close() } catch (_: Throwable) { }
        }
    }

    fun pair(addr: String, code: String): Boolean {
        if (addr.isEmpty() || code.isEmpty()) {
            log("Isi IP dan Port pairing dan kode pairing terlebih dahulu.")
            return false
        }
        val (host, port) = split(addr) ?: run { log("Alamat pairing tidak valid: $addr"); return false }
        log("> adb pair $addr ******")
        return try {
            manager.pair(host, port, code)
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
        closeUsb()
        var ok = false
        for (attempt in 1..2) {
            if (ok) break
            if (attempt > 1) {
                log("Connect gagal, mencoba lagi (1x)...")
                Thread.sleep(1500)
            }
            log("> adb connect $addr")
            try {
                manager.connect(host, port)
                ok = manager.isConnected
                if (ok) log("connected to $addr") else log("Connect gagal: tidak terhubung")
            } catch (e: Throwable) {
                log("Connect gagal: ${e.message ?: e.javaClass.simpleName}")
            }
        }
        return ok
    }

    fun disconnect(): Boolean {
        log("> adb disconnect")
        closeUsb()
        return try {
            manager.disconnect()
            log("disconnected")
            true
        } catch (e: Throwable) {
            log("Disconnect error: ${e.message ?: e.javaClass.simpleName}")
            false
        }
    }

    // ---------- USB debugging ----------

    /** Perangkat USB yang punya antarmuka ADB (HP target dengan USB debugging aktif). */
    fun usbDevices(): List<UsbDevice> = try { UsbHost.findAdbDevices(usbManager) } catch (_: Throwable) { emptyList() }

    fun usbLabelOf(d: UsbDevice): String = UsbHost.label(d)

    /** Setara `adb devices` + koneksi USB: minta izin USB, handshake, tunggu persetujuan debugging di HP target. */
    fun connectUsb(): Boolean {
        val devices = usbDevices()
        val d = devices.firstOrNull()
        if (d == null) {
            log("Tidak ada perangkat USB (ADB) terdeteksi. Sambungkan kabel OTG/USB ke HP target, aktifkan USB debugging, lalu coba lagi.")
            return false
        }
        if (devices.size > 1) log("Peringatan: ${devices.size} perangkat USB terdeteksi, memakai yang pertama: ${UsbHost.label(d)}")
        val name = UsbHost.label(d)
        log("> adb connect usb: $name")

        // Putus jalur lain dulu: hanya satu koneksi aktif.
        closeUsb()
        try { if (wirelessLink.isConnected) manager.disconnect() } catch (_: Throwable) { }

        if (!UsbHost.ensurePermission(appContext, usbManager, d)) {
            log("Connect USB gagal: izin akses USB ditolak / tidak dijawab. Klik Connect USB lagi lalu pilih OK.")
            return false
        }
        return try {
            val pub = KeyFactory.getInstance("RSA")
                .generatePublic(X509EncodedKeySpec(manager.usbCertificate.publicKey.encoded)) as RSAPublicKey
            usb = UsbAdbConnection.connect(usbManager, d, manager.usbPrivateKey, pub, log)
            usbName = name
            log("connected to usb: $name")
            true
        } catch (e: Throwable) {
            log("Connect USB gagal: ${e.message ?: e.javaClass.simpleName}")
            false
        }
    }

    private fun closeUsb() {
        val u = usb
        usb = null
        usbName = null
        try { u?.close() } catch (_: Throwable) { }
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

    private fun ensureConnected(): Boolean {
        if (isConnected) return true
        log("Belum terhubung ke device. Klik Connect (Wireless) atau Connect USB terlebih dahulu.")
        return false
    }

    private fun shell(cmd: String): String {
        val stream = (link ?: throw java.io.IOException("Belum terhubung ke device.")).open("shell:$cmd")
        try {
            return readAll(stream.input())
        } finally {
            try { stream.close() } catch (_: Throwable) { }
        }
    }

    /** Setara `adb install -r`: stream APK ke `cmd package install -S <ukuran>` lewat service exec. */
    private fun installOne(file: File): String {
        val stream = (link ?: throw java.io.IOException("Belum terhubung ke device."))
            .open("exec:cmd package install -r -S ${file.length()}")
        // Pengaman: tutup stream bila tidak selesai dalam 5 menit.
        val watchdog = Thread {
            try { Thread.sleep(300_000); stream.close() } catch (_: InterruptedException) { } catch (_: Throwable) { }
        }.apply { isDaemon = true; start() }
        try {
            val os = stream.output()
            file.inputStream().use { input ->
                val buf = ByteArray(32 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    os.write(buf, 0, n)
                }
            }
            os.flush()
            return readAll(stream.input())
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
