package co.sat.hmdminstaller

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Transport USB untuk ADB. HP teknisi bertindak sebagai USB host (kabel OTG) ke HP target
 * yang USB debugging-nya aktif.
 *
 * libadb-android hanya bicara lewat Socket, sedangkan protokol ADB di USB memakai bingkai yang
 * sama persis dengan TCP (header 24 byte + payload). Jadi USB dijembatani secara transparan:
 *
 *   libadb ⇄ Socket 127.0.0.1:<port> ⇄ [UsbBridge] ⇄ bulk endpoint USB ⇄ adbd di HP target
 *
 * Bridge tidak mengubah isi paket; autentikasi (dialog "Izinkan USB debugging") dan semua
 * perintah tetap ditangani libadb.
 */
class UsbBridge(context: Context) {

    companion object {
        private const val ACTION_PERMISSION = "co.sat.hmdminstaller.USB_PERMISSION"
        private const val ADB_HEADER = 24
        private const val CHUNK = 16 * 1024
        private const val WRITE_TIMEOUT_MS = 10_000
        private const val MAX_PAYLOAD = 4 * 1024 * 1024

        /** Interface ADB: class 0xFF (vendor), subclass 0x42, protocol 0x01. */
        private fun findAdbInterface(d: UsbDevice): UsbInterface? {
            for (i in 0 until d.interfaceCount) {
                val itf = d.getInterface(i)
                if (itf.interfaceClass == 0xFF && itf.interfaceSubclass == 0x42 && itf.interfaceProtocol == 0x01) {
                    return itf
                }
            }
            return null
        }
    }

    private val app = context.applicationContext
    private val usb = app.getSystemService(Context.USB_SERVICE) as UsbManager

    /** Device USB yang mengiklankan interface ADB (HP dengan USB debugging aktif). */
    fun listAdbDevices(): List<UsbDevice> =
        usb.deviceList.values.filter { findAdbInterface(it) != null }

    fun label(d: UsbDevice): String {
        val name = d.productName ?: "USB device"
        return "$name (%04x:%04x)".format(d.vendorId, d.productId)
    }

    fun hasPermission(d: UsbDevice) = usb.hasPermission(d)

    /** Minta izin USB ke user. [onResult] dipanggil di main thread. */
    fun requestPermission(d: UsbDevice, onResult: (Boolean) -> Unit) {
        if (usb.hasPermission(d)) { onResult(true); return }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                try { app.unregisterReceiver(this) } catch (_: Throwable) { }
                onResult(i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
            }
        }
        val filter = IntentFilter(ACTION_PERMISSION)
        if (Build.VERSION.SDK_INT >= 33) {
            app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            app.registerReceiver(receiver, filter)
        }
        // Harus mutable: sistem mengisi EXTRA_DEVICE / EXTRA_PERMISSION_GRANTED ke intent ini.
        val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val pi = PendingIntent.getBroadcast(app, 0, Intent(ACTION_PERMISSION).setPackage(app.packageName), flags)
        usb.requestPermission(d, pi)
    }

    // ---------- sesi ----------

    private var conn: UsbDeviceConnection? = null
    private var itf: UsbInterface? = null
    private var epIn: UsbEndpoint? = null
    private var epOut: UsbEndpoint? = null
    private var server: ServerSocket? = null
    private var client: Socket? = null
    @Volatile private var proxyGen = 0

    val isOpen: Boolean get() = conn != null

    /** Buka device & claim interface ADB. Lempar IOException bila gagal. */
    @Synchronized
    fun open(d: UsbDevice) {
        close()
        val adbItf = findAdbInterface(d) ?: throw IOException("Device tidak punya interface ADB (USB debugging belum aktif?)")
        var i: UsbEndpoint? = null
        var o: UsbEndpoint? = null
        for (e in 0 until adbItf.endpointCount) {
            val ep = adbItf.getEndpoint(e)
            if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
            if (ep.direction == UsbConstants.USB_DIR_IN) i = ep else o = ep
        }
        if (i == null || o == null) throw IOException("Endpoint bulk ADB tidak ditemukan")
        val c = usb.openDevice(d) ?: throw IOException("Gagal membuka device USB (izin belum diberikan?)")
        if (!c.claimInterface(adbItf, true)) {
            c.close()
            throw IOException("Gagal claim interface ADB (dipakai aplikasi lain?)")
        }
        conn = c; itf = adbItf; epIn = i; epOut = o
    }

    /**
     * Mulai jembatan: buka server socket lokal lalu jalankan pompa data dua arah begitu libadb
     * tersambung. Kembalikan port lokal yang harus di-connect oleh libadb.
     */
    @Synchronized
    fun startProxy(): Int {
        stopProxy()
        val c = conn ?: throw IOException("USB belum dibuka")
        val ss = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        server = ss
        val gen = ++proxyGen
        Thread({
            try {
                val s = ss.accept()
                s.tcpNoDelay = true
                synchronized(this) {
                    if (gen != proxyGen) { s.close(); return@Thread }
                    client = s
                }
                try { ss.close() } catch (_: Throwable) { }
                pump(c, s, gen)
            } catch (_: Throwable) {
                // server ditutup / koneksi putus
            }
        }, "usb-bridge-accept").apply { isDaemon = true; start() }
        return ss.localPort
    }

    /** Tutup jembatan socket saja; koneksi USB tetap terbuka agar bisa dicoba lagi. */
    @Synchronized
    fun stopProxy() {
        proxyGen++
        try { client?.close() } catch (_: Throwable) { }
        try { server?.close() } catch (_: Throwable) { }
        client = null; server = null
    }

    /** Tutup semuanya dan lepaskan device USB. */
    @Synchronized
    fun close() {
        stopProxy()
        val c = conn
        if (c != null) {
            try { itf?.let { c.releaseInterface(it) } } catch (_: Throwable) { }
            try { c.close() } catch (_: Throwable) { }
        }
        conn = null; itf = null; epIn = null; epOut = null
    }

    // ---------- pompa data ----------

    private fun pump(c: UsbDeviceConnection, s: Socket, gen: Int) {
        val inEp = epIn ?: return
        val outEp = epOut ?: return

        // socket (libadb) → USB (adbd)
        val up = Thread({
            try {
                val sin = s.getInputStream()
                val header = ByteArray(ADB_HEADER)
                while (gen == proxyGen) {
                    readFully(sin, header, ADB_HEADER)
                    val len = le32(header, 12)
                    if (len < 0 || len > MAX_PAYLOAD) throw IOException("Panjang payload tidak valid: $len")
                    usbWrite(c, outEp, header, ADB_HEADER)
                    if (len > 0) {
                        val payload = ByteArray(len)
                        readFully(sin, payload, len)
                        usbWrite(c, outEp, payload, len)
                    }
                }
            } catch (_: Throwable) {
            } finally {
                try { s.close() } catch (_: Throwable) { }
            }
        }, "usb-bridge-up").apply { isDaemon = true; start() }

        // USB (adbd) → socket (libadb)
        try {
            val sout = s.getOutputStream()
            val header = ByteArray(ADB_HEADER)
            val buf = ByteArray(CHUNK)
            while (gen == proxyGen && !s.isClosed) {
                usbReadFully(c, inEp, header, ADB_HEADER, buf, gen)
                val len = le32(header, 12)
                if (len < 0 || len > MAX_PAYLOAD) throw IOException("Payload dari device tidak valid: $len")
                sout.write(header, 0, ADB_HEADER)
                var remain = len
                while (remain > 0) {
                    val want = minOf(remain, CHUNK)
                    val n = usbRead(c, inEp, buf, want, gen)
                    sout.write(buf, 0, n)
                    remain -= n
                }
                sout.flush()
            }
        } catch (_: Throwable) {
        } finally {
            try { s.close() } catch (_: Throwable) { }
            up.interrupt()
        }
    }

    private fun usbReadFully(c: UsbDeviceConnection, ep: UsbEndpoint, dst: ByteArray, len: Int, scratch: ByteArray, gen: Int) {
        var got = 0
        while (got < len) {
            val n = usbRead(c, ep, scratch, minOf(len - got, scratch.size), gen)
            System.arraycopy(scratch, 0, dst, got, n)
            got += n
        }
    }

    /**
     * Baca bulk dengan timeout pendek supaya thread lama ikut berhenti begitu sesi diganti/ditutup
     * (kalau tidak, ia akan "mencuri" paket milik sesi berikutnya). Timeout (-1 setelah ±1 detik)
     * diulang; -1 yang datang cepat berarti error nyata (mis. kabel dicabut).
     */
    private fun usbRead(c: UsbDeviceConnection, ep: UsbEndpoint, dst: ByteArray, len: Int, gen: Int): Int {
        while (true) {
            if (gen != proxyGen || conn !== c) throw IOException("sesi USB ditutup")
            val t0 = System.currentTimeMillis()
            val n = c.bulkTransfer(ep, dst, len, 1000)
            if (n > 0) return n
            if (n < 0 && System.currentTimeMillis() - t0 < 300) throw IOException("USB read gagal")
        }
    }

    private fun usbWrite(c: UsbDeviceConnection, ep: UsbEndpoint, data: ByteArray, len: Int) {
        var off = 0
        val tmp = ByteArray(minOf(len, CHUNK).coerceAtLeast(1))
        while (off < len) {
            val n = minOf(len - off, CHUNK)
            System.arraycopy(data, off, tmp, 0, n)
            val w = c.bulkTransfer(ep, tmp, n, WRITE_TIMEOUT_MS)
            if (w < 0) throw IOException("USB write gagal")
            off += n
        }
    }

    private fun readFully(i: InputStream, dst: ByteArray, len: Int) {
        var off = 0
        while (off < len) {
            val n = i.read(dst, off, len - off)
            if (n < 0) throw IOException("socket ditutup")
            off += n
        }
    }

    private fun le32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)
}
