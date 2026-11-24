package id.qmobile.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.internal.tls.OkHostnameVerifier
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.UnknownServiceException
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertPathValidatorException
import java.security.cert.Certificate
import java.security.cert.CertificateException
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateNotYetValidException
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/** SSL Certificate Mode per profil modem. */
enum class SslMode(val key: String, val label: String) {
    STRICT("strict", "Strict SSL (default)"),
    SELF_SIGNED("self_signed", "Trust Self-Signed Certificate");
    companion object { fun of(key: String?): SslMode = entries.firstOrNull { it.key == key } ?: STRICT }
}

/**
 * Parse alamat modem. Tanpa skema dianggap http:// (tidak pernah diubah otomatis ke https,
 * dan https tidak pernah diturunkan ke http).
 */
fun parseModemUrl(address: String): HttpUrl {
    val text = address.trim()
    val url = (if (text.contains("://")) text else "http://$text").toHttpUrlOrNull()
        ?: throw IllegalArgumentException("Alamat modem tidak valid. Contoh: http://192.168.225.1 atau https://192.168.225.1")
    require(url.encodedPath == "/" && url.query == null && url.fragment == null && url.username.isEmpty() && url.password.isEmpty()) { "Masukkan alamat server tanpa path atau kredensial." }
    return url
}

fun sha256Fingerprint(cert: Certificate): String =
    MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString(":") { "%02X".format(it) }

fun formatCertDate(ms: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ms))

/** Ringkasan sertifikat leaf yang ditampilkan ke pengguna dan disimpan setelah disetujui. */
data class CertInfo(
    val host: String, val port: Int, val sha256: String,
    val subject: String, val issuer: String, val notBefore: Long, val notAfter: Long,
) {
    val expired: Boolean get() = System.currentTimeMillis() > notAfter
    val notYetValid: Boolean get() = System.currentTimeMillis() < notBefore
    val selfSigned: Boolean get() = subject == issuer
    fun toJson(): String = JSONObject().put("host", host).put("port", port).put("sha256", sha256).put("subject", subject)
        .put("issuer", issuer).put("not_before", notBefore).put("not_after", notAfter).put("approved_at", System.currentTimeMillis()).toString()
    companion object {
        fun from(host: String, port: Int, cert: X509Certificate) = CertInfo(
            host, port, sha256Fingerprint(cert), cert.subjectX500Principal.name, cert.issuerX500Principal.name, cert.notBefore.time, cert.notAfter.time)
        fun fromJson(raw: String): CertInfo? = try {
            val o = JSONObject(raw)
            CertInfo(o.getString("host"), o.getInt("port"), o.getString("sha256"), o.optString("subject"), o.optString("issuer"), o.optLong("not_before"), o.optLong("not_after"))
        } catch (_: Exception) { null }
    }
}

/** Sertifikat yang disetujui pengguna, per profil (hostname + port) beserta fingerprint SHA-256. */
class TrustStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("trusted_certs", Context.MODE_PRIVATE)
    private fun key(host: String, port: Int) = "${host.lowercase(Locale.ROOT)}:$port"
    fun get(host: String, port: Int): CertInfo? = prefs.getString(key(host, port), null)?.let { CertInfo.fromJson(it) }
        ?.takeIf { it.host.equals(host, ignoreCase = true) && it.port == port }
    fun save(info: CertInfo) { prefs.edit().putString(key(info.host, info.port), info.toJson()).commit() }
    fun remove(host: String, port: Int) { prefs.edit().remove(key(host, port)).commit() }
}

class FingerprintMismatch(val actual: String) : CertificateException("Fingerprint sertifikat server berubah")

/**
 * TrustManager khusus satu profil: hanya menerima sertifikat leaf dengan fingerprint yang sudah disetujui.
 * Bukan trust-all — sertifikat lain selalu ditolak.
 */
internal class PinnedTrustManager(private val fingerprint: String, private val onMismatch: () -> Unit) : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) { throw CertificateException("Client certificate tidak didukung") }
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("Server tidak mengirim sertifikat")
        val actual = sha256Fingerprint(leaf)
        if (!actual.equals(fingerprint, ignoreCase = true)) { onMismatch(); throw FingerprintMismatch(actual) }
    }
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/**
 * Verifikasi hostname standar lebih dulu. Hanya jika gagal (mis. sertifikat modem tidak memuat IP lokal di SAN),
 * diterima secara terbatas: host persis sama dengan host profil DAN fingerprint leaf sama dengan yang disetujui.
 */
internal class PinnedHostnameVerifier(private val host: String, private val fingerprint: String) : HostnameVerifier {
    override fun verify(hostname: String, session: SSLSession): Boolean {
        if (OkHostnameVerifier.verify(hostname, session)) return true
        if (!hostname.equals(host, ignoreCase = true)) return false
        val leaf = try { session.peerCertificates.firstOrNull() } catch (_: SSLPeerUnverifiedException) { null } ?: return false
        return sha256Fingerprint(leaf).equals(fingerprint, ignoreCase = true)
    }
}

/** Konfigurasi HTTP client sesuai profil: Strict = validasi Android standar; Self-signed = pin fingerprint. */
internal fun OkHttpClient.Builder.applyTlsProfile(base: HttpUrl, pinned: CertInfo?, onMismatch: () -> Unit): OkHttpClient.Builder {
    if (!base.isHttps || pinned == null) return this
    require(pinned.host.equals(base.host, ignoreCase = true) && pinned.port == base.port) { "Sertifikat tersimpan bukan untuk ${base.host}:${base.port}" }
    val trustManager = PinnedTrustManager(pinned.sha256, onMismatch)
    val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), SecureRandom()) }
    return sslSocketFactory(context.socketFactory, trustManager).hostnameVerifier(PinnedHostnameVerifier(base.host, pinned.sha256))
}

/**
 * Ambil sertifikat server untuk ditampilkan ke pengguna. Handshake SELALU dibatalkan setelah sertifikat terbaca,
 * sehingga tidak ada data (password/cookie) yang terkirim.
 */
suspend fun fetchServerCertificate(base: HttpUrl): CertInfo = withContext(Dispatchers.IO) {
    require(base.isHttps) { "Inspeksi sertifikat hanya untuk https://" }
    var captured: X509Certificate? = null
    val inspector = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) { throw CertificateException("Tidak didukung") }
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            captured = chain?.firstOrNull()
            throw CertificateException("Inspeksi sertifikat selesai; koneksi tidak dilanjutkan")
        }
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
    val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(inspector), SecureRandom()) }
    val raw = Socket()
    try {
        raw.connect(InetSocketAddress(base.host, base.port), 5000)
        raw.soTimeout = 10000
        val ssl = context.socketFactory.createSocket(raw, base.host, base.port, true) as SSLSocket
        try { ssl.startHandshake() } catch (e: IOException) { if (captured == null) throw e } finally { try { ssl.close() } catch (_: IOException) {} }
    } catch (e: IOException) {
        if (captured == null) throw friendlyNetworkError(e, base)
    } finally { try { raw.close() } catch (_: IOException) {} }
    CertInfo.from(base.host, base.port, captured ?: throw IOException("Server tidak mengirim sertifikat HTTPS"))
}

/** Ubah exception jaringan/SSL menjadi pesan yang mudah dipahami. */
fun friendlyNetworkError(e: Throwable, base: HttpUrl): Exception {
    val target = "${base.host}:${base.port}"
    val chain = generateSequence(e) { it.cause }.take(12).toList()
    fun has(predicate: (Throwable) -> Boolean) = chain.any(predicate)
    return when {
        has { it is FingerprintMismatch } -> CertificateChanged(base.host, base.port)
        has { it is CertificateExpiredException } -> IOException("Sertifikat HTTPS $target sudah kedaluwarsa. Periksa tanggal/jam ponsel dan modem, atau perbarui sertifikat QManager.")
        has { it is CertificateNotYetValidException } -> IOException("Sertifikat HTTPS $target belum berlaku. Periksa tanggal/jam ponsel dan modem.")
        has { it is CertPathValidatorException } || has { it.message?.contains("Trust anchor", ignoreCase = true) == true } ->
            IOException("Sertifikat HTTPS modem ($target) tidak dipercaya Android — kemungkinan self-signed. Pilih SSL Certificate Mode \"Trust Self-Signed Certificate\", lalu verifikasi fingerprint sebelum login.")
        has { it is SSLPeerUnverifiedException } || has { it.message?.contains("Hostname", ignoreCase = true) == true && it is SSLException } ->
            IOException("Nama host ${base.host} tidak cocok dengan sertifikat modem. Untuk IP lokal, gunakan mode \"Trust Self-Signed Certificate\" dan verifikasi fingerprint.")
        has { it is SSLHandshakeException } || has { it is SSLException } ->
            IOException("Negosiasi HTTPS dengan $target gagal (${e.message ?: "SSL error"}). Pastikan port HTTPS benar; jika modem hanya melayani HTTP, gunakan http://")
        has { it is UnknownServiceException && it.message?.contains("CLEARTEXT", ignoreCase = true) == true } ->
            IOException("Koneksi HTTP ke $target diblokir oleh konfigurasi keamanan jaringan Android.")
        has { it is SocketTimeoutException } -> IOException("Waktu koneksi ke $target habis. Pastikan ponsel terhubung ke jaringan modem.")
        has { it is ConnectException || it is NoRouteToHostException } -> IOException("Tidak dapat terhubung ke $target. Periksa alamat, port (HTTP/HTTPS), dan koneksi Wi-Fi/LAN ke modem.")
        has { it is UnknownHostException } -> IOException("Host ${base.host} tidak ditemukan.")
        e is Exception -> e
        else -> IOException(e.message ?: "Koneksi gagal", e)
    }
}
