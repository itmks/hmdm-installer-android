package co.sat.hmdminstaller

import android.content.Context
import android.os.Build
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayInputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date

/**
 * Koneksi ADB dari dalam aplikasi. Kunci RSA + sertifikat dibuat sekali dan disimpan,
 * karena status "paired" di HP target terikat pada kunci ini.
 */
class AdbManager(context: Context) : AbsAdbConnectionManager() {

    private val keyFile = File(context.filesDir, "adb_key.pk8")
    private val certFile = File(context.filesDir, "adb_cert.der")
    private val key: PrivateKey
    private val cert: Certificate

    init {
        setApi(Build.VERSION.SDK_INT)
        var k: PrivateKey? = null
        var c: Certificate? = null
        try {
            if (keyFile.exists() && certFile.exists()) {
                k = KeyFactory.getInstance("RSA")
                    .generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
                c = CertificateFactory.getInstance("X.509")
                    .generateCertificate(ByteArrayInputStream(certFile.readBytes()))
            }
        } catch (_: Exception) {
            k = null; c = null
        }
        if (k == null || c == null) {
            val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val name = X500Name("CN=HMDM Installer")
            val now = System.currentTimeMillis()
            val builder = JcaX509v3CertificateBuilder(
                name, BigInteger.valueOf(now), Date(now - 24L * 3600 * 1000),
                Date(now + 20L * 365 * 24 * 3600 * 1000), name, kp.public
            )
            val signer = JcaContentSignerBuilder("SHA256withRSA").build(kp.private)
            val x509 = JcaX509CertificateConverter().getCertificate(builder.build(signer))
            keyFile.writeBytes(kp.private.encoded)
            certFile.writeBytes(x509.encoded)
            k = kp.private
            c = x509
        }
        key = k!!
        cert = c!!
    }

    override fun getPrivateKey(): PrivateKey = key

    override fun getCertificate(): Certificate = cert

    override fun getDeviceName(): String = "HMDM-Installer"
}
