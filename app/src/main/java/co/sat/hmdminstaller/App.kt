package co.sat.hmdminstaller

import android.app.Application
import org.conscrypt.Conscrypt
import java.security.Security

class App : Application() {

    /** Satu controller untuk seluruh proses, agar koneksi ADB tidak ikut hilang bila Activity dibuat ulang. */
    val adb: AdbController by lazy { AdbController(this) }

    override fun onCreate() {
        super.onCreate()
        // Wireless debugging memakai TLS 1.3; Conscrypt dibutuhkan oleh libadb-android.
        Security.insertProviderAt(Conscrypt.newProvider(), 1)
    }
}
