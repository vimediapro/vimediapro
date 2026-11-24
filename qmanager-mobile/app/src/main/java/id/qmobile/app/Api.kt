package id.qmobile.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class Api(address: String, private val pinned: CertInfo? = null) {
    val base: HttpUrl = parseModemUrl(address)
    val isHttps: Boolean get() = base.isHttps
    /** True jika handshake menemukan fingerprint yang berbeda dari yang disetujui; koneksi dihentikan. */
    @Volatile var certificateChanged = false
        private set
    private val jar = object : CookieJar {
        private val cookies = mutableListOf<Cookie>()
        @Synchronized override fun saveFromResponse(url: HttpUrl, newCookies: List<Cookie>) {
            newCookies.forEach { c -> cookies.removeAll { it.name == c.name && it.domain == c.domain && it.path == c.path }; if (c.expiresAt > System.currentTimeMillis()) cookies.add(c) }
        }
        @Synchronized override fun loadForRequest(url: HttpUrl): List<Cookie> = cookies.filter { it.expiresAt > System.currentTimeMillis() && it.matches(url) }
        @Synchronized fun clear() { cookies.clear() }
    }
    private val lock = Mutex()
    private val client = OkHttpClient.Builder().cookieJar(jar).followRedirects(false).followSslRedirects(false)
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(65, TimeUnit.SECONDS).callTimeout(75, TimeUnit.SECONDS).retryOnConnectionFailure(false)
        .applyTlsProfile(base, pinned) { certificateChanged = true }.build()
    private fun <T> guarded(block: () -> T): T {
        if (certificateChanged) throw CertificateChanged(base.host, base.port)
        return try { block() } catch (e: IOException) {
            val friendly = friendlyNetworkError(e, base)
            if (friendly is CertificateChanged || certificateChanged) { certificateChanged = true; throw CertificateChanged(base.host, base.port) }
            throw friendly
        }
    }
    /** Uji koneksi tanpa login / tanpa mengirim password. */
    suspend fun testConnection(): String = lock.withLock { withContext(Dispatchers.IO) { guarded {
        val url = base.newBuilder().addPathSegments("cgi-bin/quecmanager/auth/check.sh").build()
        client.newCall(Request.Builder().url(url).header("Accept", "application/json").build()).execute().use { r ->
            val tls = if (base.isHttps) (if (pinned != null) "HTTPS (sertifikat terverifikasi fingerprint)" else "HTTPS (Strict SSL)") else "HTTP (tidak terenkripsi)"
            val note = when { r.code == 401 || r.code == 403 -> " — login diperlukan"; r.code in 300..399 -> " — server mengalihkan (redirect tidak diikuti)"; r.isSuccessful -> ""; else -> " — periksa versi QManager" }
            "Koneksi OK via $tls. Server merespons HTTP ${r.code}$note."
        }
    } } }
    suspend fun request(path: String, body: JSONObject? = null): JSONObject = lock.withLock { withContext(Dispatchers.IO) { guarded {
        val url = base.newBuilder().addPathSegments("cgi-bin/quecmanager/$path").build()
        val builder = Request.Builder().url(url).header("Accept", "application/json")
        if (body != null) builder.post(body.toString().toRequestBody("application/json".toMediaType()))
        client.newCall(builder.build()).execute().use { response ->
            if (response.code == 401 || response.code == 403) throw AuthRequired()
            if (response.code in 300..399) error("Server mengalihkan permintaan. Gunakan URL HTTPS yang dipercaya atau periksa konfigurasi modem.")
            if (!response.isSuccessful) error("HTTP ${response.code} — periksa versi QManager")
            val raw = response.body?.string() ?: error("Respons kosong")
            val json = try { JSONObject(raw) } catch (_: Exception) { error("Respons bukan JSON QManager. Periksa alamat dan versi backend.") }
            if (json.has("success") && !json.optBoolean("success")) error(json.value("error", "Permintaan gagal") + ": " + json.value("detail", "Periksa konfigurasi modem"))
            json
        }
    } } }
    suspend fun login(password: String) { request("auth/login.sh", JSONObject().put("password", password)) }
    suspend fun logout() { try { request("auth/logout.sh", JSONObject()) } finally { jar.clear() } }
    suspend fun status(): JSONObject = request("at_cmd/fetch_data.sh").also { require(it.has("network") || it.has("device") || it.has("lte") || it.has("nr")) { "Format data modem tidak dikenal." } }
    suspend fun command(command: String): String = request("at_cmd/send_command.sh", JSONObject().put("command", command)).value("response", "Tidak ada output AT pada respons")
}
open class AuthRequired(message: String = "Login QManager diperlukan atau sesi berakhir") : Exception(message)
class CertificateChanged(val host: String, val port: Int) : AuthRequired("Fingerprint sertifikat HTTPS $host:$port berubah. Koneksi dihentikan sebelum data dikirim; verifikasi ulang sertifikat.")
const val CERT_CHANGED_MESSAGE = "Fingerprint sertifikat HTTPS modem berubah. Koneksi dihentikan; tekan Connect untuk verifikasi ulang sertifikat."
fun JSONObject?.value(key: String, fallback: String = "—"): String = if (this == null || !has(key) || isNull(key)) fallback else optString(key).ifBlank { fallback }
fun JSONObject?.number(key: String): Double? = this?.optDouble(key)?.takeIf { it.isFinite() }
