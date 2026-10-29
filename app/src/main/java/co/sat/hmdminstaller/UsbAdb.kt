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
import android.util.Base64
import androidx.core.content.ContextCompat
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.PrivateKey
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher

/** Satu stream ADB (shell:/exec:) yang sedang terbuka. */
interface AdbIo : Closeable {
    fun input(): InputStream
    fun output(): OutputStream
}

/** Satu jalur koneksi ADB (Wireless debugging atau USB). */
interface AdbLink {
    val isConnected: Boolean
    fun open(service: String): AdbIo
}

/** Pencarian perangkat USB + permintaan izin USB dari sistem. */
object UsbHost {
    private const val ACTION_PERMISSION = "co.sat.hmdminstaller.USB_PERMISSION"

    /** Antarmuka ADB: class 0xFF, subclass 0x42, protocol 0x01. */
    fun adbInterface(d: UsbDevice): UsbInterface? {
        for (i in 0 until d.interfaceCount) {
            val f = d.getInterface(i)
            if (f.interfaceClass == 0xFF && f.interfaceSubclass == 0x42 && f.interfaceProtocol == 1) return f
        }
        return null
    }

    fun findAdbDevices(um: UsbManager): List<UsbDevice> =
        um.deviceList.values.filter { adbInterface(it) != null }.sortedBy { it.deviceName }

    fun label(d: UsbDevice): String {
        val name = try {
            listOfNotNull(d.manufacturerName, d.productName).filter { it.isNotBlank() }.joinToString(" ")
        } catch (_: Throwable) { "" }
        val ids = "%04x:%04x".format(d.vendorId, d.productId)
        return if (name.isBlank()) ids else "$name ($ids)"
    }

    /** Minta izin USB ke user; blok sampai dijawab (maks [timeoutMs]). Panggil dari thread kerja. */
    fun ensurePermission(ctx: Context, um: UsbManager, d: UsbDevice, timeoutMs: Long = 60_000): Boolean {
        if (um.hasPermission(d)) return true
        val latch = CountDownLatch(1)
        val granted = AtomicBoolean(false)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.action == ACTION_PERMISSION) {
                    granted.set(i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
                    latch.countDown()
                }
            }
        }
        ContextCompat.registerReceiver(ctx, receiver, IntentFilter(ACTION_PERMISSION), ContextCompat.RECEIVER_NOT_EXPORTED)
        try {
            val pi = PendingIntent.getBroadcast(
                ctx, 0, Intent(ACTION_PERMISSION).setPackage(ctx.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            um.requestPermission(d, pi)
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } finally {
            try { ctx.unregisterReceiver(receiver) } catch (_: Throwable) { }
        }
        return granted.get() || um.hasPermission(d)
    }
}

/** Autentikasi ADB: tanda tangan token + format kunci publik ADB. */
internal object AdbAuth {
    // DigestInfo SHA-1 (PKCS#1 v1.5). ADB menandatangani token 20-byte seolah-olah itu hash SHA-1.
    private val SHA1_PREFIX = byteArrayOf(
        0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
    )

    fun sign(key: PrivateKey, token: ByteArray): ByteArray {
        val padded = ByteArray(256)
        padded[0] = 0x00
        padded[1] = 0x01
        val psEnd = 256 - 1 - SHA1_PREFIX.size - token.size
        for (i in 2 until psEnd) padded[i] = 0xFF.toByte()
        padded[psEnd] = 0x00
        System.arraycopy(SHA1_PREFIX, 0, padded, psEnd + 1, SHA1_PREFIX.size)
        System.arraycopy(token, 0, padded, psEnd + 1 + SHA1_PREFIX.size, token.size)
        val c = Cipher.getInstance("RSA/ECB/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key)
        return c.doFinal(padded)
    }

    /** Kunci publik dalam format `android_pubkey` (base64) + nama, diakhiri NUL. */
    fun publicKeyBlob(pub: RSAPublicKey, name: String): ByteArray {
        val n = pub.modulus
        val words = 64 // RSA 2048 bit
        val r32 = BigInteger.ONE.shiftLeft(32)
        val n0inv = n.mod(r32).modInverse(r32).negate().mod(r32).toInt()
        val rr = BigInteger.ONE.shiftLeft(2048 * 2).mod(n)
        val bb = ByteBuffer.allocate(4 + 4 + 256 + 256 + 4).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(words)
        bb.putInt(n0inv)
        bb.put(toLittleEndian(n, 256))
        bb.put(toLittleEndian(rr, 256))
        bb.putInt(pub.publicExponent.toInt())
        val b64 = Base64.encodeToString(bb.array(), Base64.NO_WRAP)
        return ("$b64 $name\u0000").toByteArray(Charsets.US_ASCII)
    }

    private fun toLittleEndian(v: BigInteger, size: Int): ByteArray {
        val be = v.toByteArray()
        val out = ByteArray(size)
        // be big-endian (mungkin ada byte 0 di depan); balik ke little-endian.
        for (i in be.indices) {
            val dst = be.size - 1 - i
            if (dst < size) out[dst] = be[i]
        }
        return out
    }
}

/**
 * Koneksi ADB lewat USB host (HP teknisi = host, HP target = device, kabel OTG).
 * Mengimplementasikan protokol ADB (CNXN/AUTH/OPEN/OKAY/WRTE/CLSE) langsung di atas bulk endpoint.
 */
class UsbAdbConnection private constructor(
    private val manager: UsbManager,
    private val device: UsbDevice,
    private val conn: UsbDeviceConnection,
    private val usbIf: UsbInterface,
    private val epIn: UsbEndpoint,
    private val epOut: UsbEndpoint,
) : AdbLink {

    companion object {
        private const val A_CNXN = 0x4e584e43
        private const val A_AUTH = 0x48545541
        private const val A_OPEN = 0x4e45504f
        private const val A_OKAY = 0x59414b4f
        private const val A_CLSE = 0x45534c43
        private const val A_WRTE = 0x45545257

        private const val VERSION = 0x01000001
        private const val MAX_DATA = 256 * 1024
        private const val USB_CHUNK = 16 * 1024
        private const val AUTH_TOKEN = 1
        private const val AUTH_SIGNATURE = 2
        private const val AUTH_RSAPUBLICKEY = 3
        private const val AUTH_WAIT_MS = 75_000L

        /**
         * Buka perangkat, claim antarmuka ADB, lalu handshake (+ autentikasi).
         * Izin USB harus sudah diberikan. Blok sampai selesai / gagal: panggil dari thread kerja.
         */
        fun connect(
            manager: UsbManager,
            device: UsbDevice,
            privateKey: PrivateKey,
            publicKey: RSAPublicKey,
            log: (String) -> Unit,
        ): UsbAdbConnection {
            val usbIf = UsbHost.adbInterface(device) ?: throw IOException("Perangkat tidak punya antarmuka ADB.")
            var epIn: UsbEndpoint? = null
            var epOut: UsbEndpoint? = null
            for (i in 0 until usbIf.endpointCount) {
                val ep = usbIf.getEndpoint(i)
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (ep.direction == UsbConstants.USB_DIR_IN) epIn = ep else epOut = ep
            }
            if (epIn == null || epOut == null) throw IOException("Endpoint bulk ADB tidak ditemukan.")
            val c = manager.openDevice(device) ?: throw IOException("Gagal membuka perangkat USB (izin?).")
            if (!c.claimInterface(usbIf, true)) {
                c.close()
                throw IOException("Gagal mengklaim antarmuka USB (dipakai aplikasi lain, mis. adb server?).")
            }
            val u = UsbAdbConnection(manager, device, c, usbIf, epIn, epOut)
            try {
                u.handshake(privateKey, publicKey, log)
            } catch (e: Throwable) {
                u.releaseNow()
                throw e
            }
            u.startReader()
            return u
        }
    }

    private class Pkt(val cmd: Int, val arg0: Int, val arg1: Int, val data: ByteArray)

    private val writeLock = Any()
    private val dead = AtomicBoolean(false)
    private val nextId = AtomicInteger(1)
    private val streams = ConcurrentHashMap<Int, Stream>()
    @Volatile private var maxData = 4096
    private var reader: Thread? = null

    override val isConnected: Boolean
        get() = !dead.get()

    // ---------- handshake ----------

    private fun handshake(key: PrivateKey, pub: RSAPublicKey, log: (String) -> Unit) {
        sendPacket(A_CNXN, VERSION, MAX_DATA, "host::\u0000".toByteArray())
        val deadline = System.currentTimeMillis() + AUTH_WAIT_MS
        var sentSig = false
        var sentKey = false
        while (System.currentTimeMillis() < deadline) {
            val p = readPacket(2000) ?: continue
            when (p.cmd) {
                A_CNXN -> {
                    val peer = p.arg1
                    maxData = if (peer in 1024..MAX_DATA) peer else if (peer > MAX_DATA) MAX_DATA else 4096
                    return
                }
                A_AUTH -> if (p.arg0 == AUTH_TOKEN) {
                    if (!sentSig) {
                        sentSig = true
                        sendPacket(A_AUTH, AUTH_SIGNATURE, 0, AdbAuth.sign(key, p.data))
                    } else if (!sentKey) {
                        sentKey = true
                        log("Menunggu persetujuan \"Izinkan debugging USB\" di HP target (centang \"Selalu izinkan\")...")
                        sendPacket(A_AUTH, AUTH_RSAPUBLICKEY, 0, AdbAuth.publicKeyBlob(pub, "HMDM-Installer@usb"))
                    } else {
                        // Token baru setelah kunci dikirim: coba tanda tangan lagi (kunci mungkin baru disetujui).
                        sendPacket(A_AUTH, AUTH_SIGNATURE, 0, AdbAuth.sign(key, p.data))
                    }
                }
                else -> { /* abaikan */ }
            }
        }
        throw IOException(
            if (sentKey) "Timeout: dialog debugging USB di HP target tidak disetujui."
            else "Timeout: HP target tidak membalas (USB debugging aktif? mode USB bukan 'hanya mengisi daya'?)."
        )
    }

    // ---------- paket ----------

    /** Baca satu paket; null bila tidak ada data dalam [timeoutMs]. */
    private fun readPacket(timeoutMs: Int): Pkt? {
        val h = ByteArray(24)
        val n = conn.bulkTransfer(epIn, h, 24, timeoutMs)
        if (n <= 0) return null
        var got = n
        while (got < 24) {
            val r = conn.bulkTransfer(epIn, h, got, 24 - got, 5000)
            if (r <= 0) throw IOException("Header USB terpotong.")
            got += r
        }
        val bb = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = bb.int
        val a0 = bb.int
        val a1 = bb.int
        val len = bb.int
        bb.int // checksum (tidak diverifikasi)
        val magic = bb.int
        if (magic != cmd.inv()) throw IOException("Paket ADB rusak (magic tidak cocok).")
        if (len < 0 || len > 1024 * 1024) throw IOException("Ukuran paket ADB tidak wajar: $len")
        val data = ByteArray(len)
        var off = 0
        var zeros = 0
        while (off < len) {
            val r = conn.bulkTransfer(epIn, data, off, minOf(len - off, USB_CHUNK), 10_000)
            if (r < 0) throw IOException("Baca data USB gagal / timeout.")
            if (r == 0) { if (++zeros > 100) throw IOException("Baca data USB macet."); continue }
            off += r
        }
        return Pkt(cmd, a0, a1, data)
    }

    private fun sendPacket(cmd: Int, a0: Int, a1: Int, data: ByteArray) {
        synchronized(writeLock) {
            if (dead.get()) throw IOException("Koneksi USB sudah tertutup.")
            var sum = 0
            for (b in data) sum += b.toInt() and 0xFF
            val bb = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            bb.putInt(cmd).putInt(a0).putInt(a1).putInt(data.size).putInt(sum).putInt(cmd.inv())
            writeAll(bb.array())
            if (data.isNotEmpty()) writeAll(data)
        }
    }

    private fun writeAll(buf: ByteArray) {
        var off = 0
        var zeros = 0
        while (off < buf.size) {
            val r = conn.bulkTransfer(epOut, buf, off, minOf(buf.size - off, USB_CHUNK), 5000)
            if (r < 0) throw IOException("Tulis data USB gagal (kabel terlepas?).")
            if (r == 0) { if (++zeros > 100) throw IOException("Tulis data USB macet."); continue }
            off += r
        }
    }

    // ---------- pembaca latar ----------

    private fun startReader() {
        reader = Thread({ readerLoop() }, "usb-adb-reader").apply { isDaemon = true; start() }
    }

    private fun readerLoop() {
        try {
            while (!dead.get()) {
                val p = readPacket(1000)
                if (p == null) {
                    if (!stillAttached()) break
                    continue
                }
                dispatch(p)
            }
        } catch (_: Throwable) {
        } finally {
            dead.set(true)
            streams.values.forEach { it.markEof() }
            releaseNow()
        }
    }

    private fun stillAttached(): Boolean = try {
        manager.deviceList.values.any { it.deviceName == device.deviceName }
    } catch (_: Throwable) { true }

    private fun dispatch(p: Pkt) {
        when (p.cmd) {
            A_OKAY -> streams[p.arg1]?.onOkay(p.arg0)
            A_WRTE -> {
                val s = streams[p.arg1]
                if (s != null) {
                    s.onData(p.data)
                    sendPacket(A_OKAY, s.localId, p.arg0, ByteArray(0))
                }
            }
            A_CLSE -> {
                val s = streams.remove(p.arg1)
                s?.markEof()
            }
            else -> { /* abaikan */ }
        }
    }

    private fun releaseNow() {
        try { conn.releaseInterface(usbIf) } catch (_: Throwable) { }
        try { conn.close() } catch (_: Throwable) { }
    }

    /** Tutup koneksi; pembaca latar ikut berhenti (maks ±1 detik) lalu melepas perangkat USB. */
    fun close() {
        if (dead.getAndSet(true)) return
        streams.values.forEach { it.markEof() }
        val r = reader
        if (r != null && r !== Thread.currentThread()) {
            try { r.join(2500) } catch (_: InterruptedException) { }
        } else if (r == null) {
            releaseNow()
        }
    }

    // ---------- stream ----------

    override fun open(service: String): AdbIo {
        if (dead.get()) throw IOException("Koneksi USB sudah tertutup.")
        val id = nextId.getAndIncrement()
        val s = Stream(id)
        streams[id] = s
        try {
            sendPacket(A_OPEN, id, 0, "$service\u0000".toByteArray())
            s.opened.await(10, TimeUnit.SECONDS)
            if (!s.isOpen) throw IOException("Service ditolak / tidak ada respons: $service")
        } catch (e: Throwable) {
            streams.remove(id)
            s.markEof()
            throw e
        }
        return s
    }

    private inner class Stream(val localId: Int) : AdbIo {
        @Volatile var remoteId = 0
        @Volatile var isOpen = false
        @Volatile private var closedFlag = false
        val opened = CountDownLatch(1)
        private val acks = Semaphore(0)
        private val queue = LinkedBlockingQueue<ByteArray>()
        private val eof = ByteArray(0)

        fun onOkay(remote: Int) {
            if (!isOpen) {
                remoteId = remote
                isOpen = true
                opened.countDown()
            } else {
                acks.release()
            }
        }

        fun onData(d: ByteArray) {
            if (d.isNotEmpty()) queue.offer(d)
        }

        /** Tandai selesai (CLSE dari device, close lokal, atau koneksi putus): bangunkan pembaca/penulis. */
        fun markEof() {
            closedFlag = true
            queue.offer(eof)
            acks.release(1000)
            opened.countDown()
        }

        private val ins = object : InputStream() {
            private var cur: ByteArray? = null
            private var pos = 0
            private var atEof = false

            override fun read(): Int {
                val one = ByteArray(1)
                val n = read(one, 0, 1)
                return if (n < 0) -1 else one[0].toInt() and 0xFF
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (len == 0) return 0
                while (true) {
                    val c = cur
                    if (c != null && pos < c.size) {
                        val n = minOf(len, c.size - pos)
                        System.arraycopy(c, pos, b, off, n)
                        pos += n
                        return n
                    }
                    if (atEof) return -1
                    val next = queue.poll(500, TimeUnit.MILLISECONDS)
                    if (next == null) {
                        if (closedFlag && queue.isEmpty()) atEof = true
                        continue
                    }
                    if (next === eof) { atEof = true; continue }
                    cur = next
                    pos = 0
                }
            }
        }

        private val outs = object : OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

            override fun write(b: ByteArray, off: Int, len: Int) {
                var o = off
                var rem = len
                val chunk = minOf(maxData, 64 * 1024)
                while (rem > 0) {
                    if (closedFlag || dead.get()) throw IOException("Stream USB sudah tertutup.")
                    val n = minOf(rem, chunk)
                    sendPacket(A_WRTE, localId, remoteId, b.copyOfRange(o, o + n))
                    // Tunggu OKAY dari device sebelum paket berikutnya (flow control ADB).
                    while (!acks.tryAcquire(500, TimeUnit.MILLISECONDS)) {
                        if (closedFlag || dead.get()) throw IOException("Stream USB ditutup saat menulis.")
                    }
                    o += n
                    rem -= n
                }
            }
        }

        override fun input(): InputStream = ins
        override fun output(): OutputStream = outs

        override fun close() {
            if (closedFlag) { streams.remove(localId); return }
            streams.remove(localId)
            try {
                if (isOpen && !dead.get()) sendPacket(A_CLSE, localId, remoteId, ByteArray(0))
            } catch (_: Throwable) { }
            markEof()
        }
    }
}
