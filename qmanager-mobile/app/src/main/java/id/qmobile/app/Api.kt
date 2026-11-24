package id.qmobile.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class Api(address: String) {
    private val base = (if (address.contains("://")) address.trim() else "http://${address.trim()}").toHttpUrlOrNull()
        ?.also { require(it.encodedPath == "/" && it.query == null && it.fragment == null && it.username.isEmpty() && it.password.isEmpty()) { "Masukkan alamat server tanpa path atau kredensial." } }
        ?: throw IllegalArgumentException("Alamat modem tidak valid")
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
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(65, TimeUnit.SECONDS).callTimeout(75, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
    suspend fun request(path: String, body: JSONObject? = null): JSONObject = lock.withLock { withContext(Dispatchers.IO) {
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
    }
    }
    suspend fun login(password: String) { request("auth/login.sh", JSONObject().put("password", password)) }
    suspend fun logout() { try { request("auth/logout.sh", JSONObject()) } finally { jar.clear() } }
    suspend fun status(): JSONObject = request("at_cmd/fetch_data.sh").also { require(it.has("network") || it.has("device") || it.has("lte") || it.has("nr")) { "Format data modem tidak dikenal." } }
    suspend fun command(command: String): String = request("at_cmd/send_command.sh", JSONObject().put("command", command)).value("response", "Tidak ada output AT pada respons")
}
class AuthRequired : Exception("Login QManager diperlukan atau sesi berakhir")
fun JSONObject?.value(key: String, fallback: String = "—"): String = if (this == null || !has(key) || isNull(key)) fallback else optString(key).ifBlank { fallback }
fun JSONObject?.number(key: String): Double? = this?.optDouble(key)?.takeIf { it.isFinite() }
