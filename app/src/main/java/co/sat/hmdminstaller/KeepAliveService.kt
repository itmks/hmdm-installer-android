package co.sat.hmdminstaller

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Foreground service selama ADB terhubung. Tanpa ini Android membekukan / mematikan proses aplikasi
 * yang lama di background (Doze, app standby, cached-app freezer) sehingga soket ADB mati diam-diam.
 * Service ini menahan proses + CPU + Wi-Fi tetap aktif agar sesi ADB dan keepalive terus berjalan.
 */
class KeepAliveService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Koneksi ADB", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notif: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("HMDM Installer")
            .setContentText("ADB terhubung — koneksi dijaga tetap aktif")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, notif, type)
        } catch (e: Throwable) {
            Log.w(TAG, "startForeground gagal", e)
            stopSelf()
            return START_NOT_STICKY
        }
        acquireLocks()
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        if (wakeLock == null) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hmdminstaller:adb").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
        if (wifiLock == null) {
            val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = wm.createWifiLock(mode, "hmdminstaller:adb").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    override fun onDestroy() {
        try { wakeLock?.takeIf { it.isHeld }?.release() } catch (_: Throwable) { }
        try { wifiLock?.takeIf { it.isHeld }?.release() } catch (_: Throwable) { }
        wakeLock = null
        wifiLock = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "KeepAliveService"
        private const val CHANNEL = "adb_connection"
        private const val NOTIF_ID = 1001

        fun start(ctx: Context) {
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, KeepAliveService::class.java))
            } catch (e: Throwable) {
                Log.w(TAG, "tidak bisa memulai service", e)
            }
        }

        fun stop(ctx: Context) {
            try { ctx.stopService(Intent(ctx, KeepAliveService::class.java)) } catch (_: Throwable) { }
        }
    }
}
