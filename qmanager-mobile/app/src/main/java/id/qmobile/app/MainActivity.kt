package id.qmobile.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Locale

private val Mint = Color(0xFF69DFB6)
private val Bg = Color(0xFF101715)
private val Surface = Color(0xFF1C2925)
private val Muted = Color(0xFFAAC0B7)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { MaterialTheme(colorScheme = darkColorScheme(primary = Mint, onPrimary = Color(0xFF00382B), background = Bg, surface = Surface, onSurface = Color(0xFFE6F3ED), onBackground = Color(0xFFE6F3ED))) { Mobile() } }
    }
}

@Composable
private enum class PendingAction { CONNECT, TEST }
private class CertPrompt(val info: CertInfo, val previous: CertInfo?, val action: PendingAction, val target: String)

@Composable
private fun Mobile() {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("modems", 0)
    val trust = remember { TrustStore(context) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    var address by remember { mutableStateOf(prefs.getString("last", "http://192.168.225.1") ?: "http://192.168.225.1") }
    var saved by remember { mutableStateOf(prefs.getStringSet("hosts", emptySet())!!.toList().sorted()) }
    var password by remember { mutableStateOf("") }
    var api by remember { mutableStateOf<Api?>(null) }
    var host by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<JSONObject?>(null) }
    var error by remember { mutableStateOf("") }
    var toolError by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var online by remember { mutableStateOf(false) }
    var tab by remember { mutableIntStateOf(0) }
    var interval by remember { mutableLongStateOf(5L) }
    var lastUpdate by remember { mutableStateOf("") }
    var command by remember { mutableStateOf("AT+QENG=\"servingcell\"") }
    var output by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf<String?>(null) }
    var download by remember { mutableStateOf<List<Float>>(emptyList()) }
    var upload by remember { mutableStateOf<List<Float>>(emptyList()) }
    var sslMode by remember { mutableStateOf(SslMode.of(prefs.getString("ssl|$address", null))) }
    var certPrompt by remember { mutableStateOf<CertPrompt?>(null) }
    var testResult by remember { mutableStateOf("") }
    var trustRevision by remember { mutableIntStateOf(0) }
    fun update(data: JSONObject) {
        status = data; online = true; error = ""
        lastUpdate = java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(java.util.Date())
        val traffic = data.optJSONObject("traffic")
        traffic.number("rx_bytes_per_sec")?.let { download = (download + (it * 8 / 1_000_000).toFloat()).takeLast(48) }
        traffic.number("tx_bytes_per_sec")?.let { upload = (upload + (it * 8 / 1_000_000).toFloat()).takeLast(48) }
    }
    fun sessionEnded(current: Api?) {
        val changed = current?.certificateChanged == true
        api = null; online = false; status = null; tab = 3
        error = if (changed) CERT_CHANGED_MESSAGE else "Sesi berakhir. Login kembali."
    }
    // Ambil sertifikat server (tanpa mengirim data) dan minta konfirmasi pengguna.
    suspend fun promptCertificate(action: PendingAction, target: String, url: okhttp3.HttpUrl, previous: CertInfo?) {
        certPrompt = CertPrompt(fetchServerCertificate(url), previous, action, target)
    }
    fun perform(action: PendingAction, target: String, url: okhttp3.HttpUrl, pinned: CertInfo?) {
        val secret = password
        busy = true; error = ""; testResult = ""
        if (action == PendingAction.CONNECT) { online = false; api = null; status = null; output = ""; toolError = ""; download = emptyList(); upload = emptyList(); lastUpdate = "" }
        scope.launch {
            try {
                val candidate = Api(target, pinned)
                if (action == PendingAction.TEST) {
                    testResult = candidate.testConnection()
                } else {
                    if (secret.isNotEmpty()) candidate.login(secret)
                    val data = candidate.status()
                    host = target; api = candidate; update(data); password = ""; tab = 0
                    saved = (saved + target).distinct().sorted()
                    prefs.edit().putString("last", target).putStringSet("hosts", saved.toSet()).putString("ssl|$target", sslMode.key).apply()
                }
            } catch (e: CancellationException) { throw e }
            catch (e: CertificateChanged) {
                error = e.message.orEmpty()
                try { promptCertificate(action, target, url, trust.get(url.host, url.port)) }
                catch (x: CancellationException) { throw x } catch (x: Exception) { error = x.message ?: "Gagal membaca sertifikat" }
            }
            catch (e: Exception) { error = e.message ?: "Koneksi gagal" }
            finally { busy = false }
        }
    }
    fun start(action: PendingAction) {
        if (busy || sending) return
        val target = address.trim()
        val url = try { parseModemUrl(target) } catch (e: Exception) { error = e.message ?: "Alamat tidak valid"; return }
        if (!url.isHttps || sslMode == SslMode.STRICT) { perform(action, target, url, null); return }
        val stored = trust.get(url.host, url.port)
        if (stored != null) { perform(action, target, url, stored); return }
        busy = true; error = ""; testResult = ""
        scope.launch {
            try { promptCertificate(action, target, url, null) }
            catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message ?: "Gagal membaca sertifikat" }
            finally { busy = false }
        }
    }
    fun connect() = start(PendingAction.CONNECT)
    fun resetCertificate() {
        val url = try { parseModemUrl(address) } catch (e: Exception) { error = e.message ?: "Alamat tidak valid"; return }
        trust.remove(url.host, url.port); trustRevision++
        val current = api
        if (current != null && current.base.host.equals(url.host, true) && current.base.port == url.port) { api = null; online = false; status = null }
        error = ""
        testResult = "Sertifikat tepercaya untuk ${url.host}:${url.port} dihapus. Verifikasi ulang akan diminta saat Connect."
    }
    fun send(text: String) {
        val current = api ?: return
        if (sending || busy) return
        sending = true; toolError = ""; output = ""
        scope.launch {
            try { output = current.command(text) }
            catch (e: CancellationException) { throw e }
            catch (e: AuthRequired) { api = null; online = false; error = e.message.orEmpty(); tab = 3; status = null }
            catch (e: Exception) { toolError = e.message ?: "AT Command gagal" }
            finally { sending = false }
        }
    }
    LaunchedEffect(api, interval, lifecycle) {
        val current = api ?: return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                delay(interval * 1000)
                if (sending || busy) continue
                try { val data = current.status(); if (api === current) update(data) }
                catch (e: CancellationException) { throw e }
                catch (e: AuthRequired) { if (api === current) { api = null; status = null; online = false; error = e.message.orEmpty(); tab = 3 } }
                catch (e: Exception) { if (api === current) { online = false; error = e.message ?: "Modem tidak terjangkau" } }
            }
        }
    }
    Scaffold(containerColor = Bg, bottomBar = {
        NavigationBar(containerColor = Surface) {
            listOf("Home", "Network", "AT Tools", "Settings", "SMS").forEachIndexed { i, title ->
                NavigationBarItem(selected = tab == i, enabled = !sending && !busy, onClick = { tab = i }, icon = { Text(listOf("⌂", "▥", "›_", "⚙", "✉")[i], style = MaterialTheme.typography.titleLarge) }, label = { Text(title) })
            }
        }
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column { Text("QManager", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold); Text("MOBILE / MODEM MANAGER", style = MaterialTheme.typography.labelSmall, color = Muted) }
                Text(if (busy) "CONNECTING" else if (online) "API ONLINE" else "OFFLINE", color = if (online) Mint else Muted, style = MaterialTheme.typography.labelMedium)
            }
            if (error.isNotEmpty()) Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF452A25))) { Text(error, Modifier.padding(12.dp), color = Color(0xFFFFC7B7)) }
            if (status != null && !online) Text("Data terakhir (tidak live).", color = Muted)
            if (tab == 3 || api == null) {
                Panel("Koneksi modem") {
                    if (api != null) Text("Terhubung ke $host", color = Mint)
                    OutlinedTextField(address, { address = it }, label = { Text("IP / URL QManager") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !busy && !sending)
                    OutlinedTextField(password, { password = it }, label = { Text("Password QManager (jika diperlukan)") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), enabled = !busy && !sending)
                    val parsed = remember(address) { try { parseModemUrl(address) } catch (_: Exception) { null } }
                    Text("SSL Certificate Mode", style = MaterialTheme.typography.labelLarge)
                    SslMode.entries.forEach { mode ->
                        Row(Modifier.fillMaxWidth().selectable(selected = sslMode == mode, enabled = !busy && !sending, role = Role.RadioButton, onClick = { sslMode = mode; testResult = "" }), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = sslMode == mode, onClick = null, enabled = !busy && !sending)
                            Text(mode.label, Modifier.padding(start = 8.dp))
                        }
                    }
                    if (parsed != null && !parsed.isHttps) Text("HTTP: password dan data dikirim tanpa enkripsi. Gunakan hanya di LAN tepercaya atau tunnel VPN. Mode SSL tidak berlaku untuk http://", color = Muted, style = MaterialTheme.typography.bodySmall)
                    else if (sslMode == SslMode.STRICT) Text("Strict SSL: sertifikat HTTPS harus dipercaya Android. Sertifikat self-signed modem akan ditolak.", color = Muted, style = MaterialTheme.typography.bodySmall)
                    else {
                        val stored = remember(parsed, trustRevision) { parsed?.let { trust.get(it.host, it.port) } }
                        Text(if (stored != null) "Sertifikat tepercaya untuk ${stored.host}:${stored.port}\nSHA-256 ${stored.sha256.take(23)}…  •  berlaku s/d ${formatCertDate(stored.notAfter)}" else "Belum ada sertifikat tepercaya. Fingerprint akan ditampilkan untuk diverifikasi sebelum password dikirim.", color = if (stored != null) Mint else Muted, style = MaterialTheme.typography.bodySmall)
                    }
                    Button(onClick = { connect() }, enabled = !busy && !sending && address.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Menghubungkan…" else "Connect / Login") }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { start(PendingAction.TEST) }, enabled = !busy && !sending && address.isNotBlank(), modifier = Modifier.weight(1f)) { Text("Test Connection") }
                        OutlinedButton(onClick = { resetCertificate() }, enabled = !busy && !sending && parsed?.isHttps == true, modifier = Modifier.weight(1f)) { Text("Reset Trusted Certificate") }
                    }
                    if (testResult.isNotEmpty()) Text(testResult, color = Mint, style = MaterialTheme.typography.bodySmall)
                    if (api != null) OutlinedButton(enabled = !busy && !sending, onClick = {
                        val current = api ?: return@OutlinedButton
                        api = null; online = false; status = null; download = emptyList(); upload = emptyList(); output = ""; toolError = ""; error = ""; busy = true
                        scope.launch { try { current.logout() } catch (e: CancellationException) { throw e } catch (_: Exception) { error = "Sesi lokal ditutup; logout server tidak terkonfirmasi." } finally { busy = false } }
                    }, modifier = Modifier.fillMaxWidth()) { Text("Disconnect") }
                }
                if (saved.isNotEmpty()) Panel("Profil tersimpan") {
                    saved.forEach { entry -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { address = entry; password = ""; sslMode = SslMode.of(prefs.getString("ssl|$entry", null)); testResult = "" }, modifier = Modifier.weight(1f), enabled = !busy && !sending) { Text(entry) }
                        TextButton(onClick = {
                            saved = saved - entry; prefs.edit().putStringSet("hosts", saved.toSet()).remove("ssl|$entry").apply()
                            try { parseModemUrl(entry).let { u -> if (saved.none { other -> try { parseModemUrl(other).let { it.host.equals(u.host, true) && it.port == u.port } } catch (_: Exception) { false } }) trust.remove(u.host, u.port) } } catch (_: Exception) {}
                            trustRevision++
                        }, enabled = !busy && !sending) { Text("Hapus") }
                    } }
                }
                Panel("Refresh dashboard") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(3L, 5L, 10L).forEach { n -> FilterChip(selected = interval == n, onClick = { interval = n }, label = { Text("${n}s") }) } }
                    Text("Polling berhenti ketika aplikasi tidak aktif. Password tidak disimpan.", style = MaterialTheme.typography.bodySmall, color = Muted)
                }
            } else if (tab == 0 || tab == 1) {
                val data = status
                val network = data?.optJSONObject("network")
                val device = data?.optJSONObject("device")
                val lte = data?.optJSONObject("lte")
                val nr = data?.optJSONObject("nr")
                val traffic = data?.optJSONObject("traffic")
                val connectivity = data?.optJSONObject("connectivity")
                Panel(device.value("model", "Quectel modem")) {
                    Text(network.value("type"), style = MaterialTheme.typography.headlineLarge, color = Mint, fontWeight = FontWeight.Bold)
                    Text(network.value("carrier"), style = MaterialTheme.typography.titleMedium)
                    Text("SIM ${network.value("sim_slot")}  •  ${network.value("bandwidth_details")}", color = Muted)
                    Text("Update: $lastUpdate  •  ${if (data?.optBoolean("modem_reachable", true) == false) "Modem tidak terjangkau" else network.value("service_status")}", color = Muted, style = MaterialTheme.typography.bodySmall)
                }
                Signal("LTE / 4G", lte)
                Signal("NR / 5G", nr)
                if (tab == 0) {
                    Panel("Traffic") {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Metric("Download", rate(traffic, "rx_bytes_per_sec"), "Mbps", Modifier.weight(1f)); Metric("Upload", rate(traffic, "tx_bytes_per_sec"), "Mbps", Modifier.weight(1f)) }
                        TrafficGraph(download, upload)
                        Text("Grafik sampel selama aplikasi aktif", color = Muted, style = MaterialTheme.typography.bodySmall)
                    }
                    Panel("Device health") { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Metric("Temperature", device.value("temperature"), "°C", Modifier.weight(1f)); Metric("Latency", connectivity.value("latency_ms"), "ms", Modifier.weight(1f)) }; Detail("Firmware", device.value("firmware")); Detail("CPU", device.value("cpu_usage") + if (device.number("cpu_usage") != null) "%" else ""); Detail("RAM", "${device.value("memory_used_mb")} / ${device.value("memory_total_mb")} MB") }
                } else {
                    Panel("Connection details") { Detail("APN", network.value("apn")); Detail("WAN IPv4", network.value("wan_ipv4")); Detail("WAN IPv6", network.value("wan_ipv6")); Detail("DNS", network.value("primary_dns")); Detail("CA", network.value("bandwidth_details")); Detail("LTE PCI / EARFCN", "${lte.value("pci")} / ${lte.value("earfcn")}"); Detail("NR PCI / ARFCN", "${nr.value("pci")} / ${nr.value("arfcn")}") }
                    val activeApi = api
                    if (activeApi != null) key(activeApi) { NetworkControls(activeApi, data, sending, { sending = it }, { sessionEnded(activeApi) }) }
                }
            } else if (tab == 4) {
                val activeApi = api
                if (activeApi != null) key(activeApi) { SmsCenter(activeApi, sending, { sending = it }, { sessionEnded(activeApi) }) }
            } else if (tab == 2) {
                Panel("AT Command") {
                    Text("Perintah diagnosis tersedia sebagai preset. Perintah lain dapat mengubah konfigurasi atau memutus koneksi modem.", color = Muted)
                    listOf("ATI", "AT+QTEMP", "AT+QCAINFO", "AT+QENG=\"servingcell\"", "AT+QUIMSLOT?").forEach { preset -> TextButton(onClick = { command = preset }, enabled = !sending) { Text(preset) } }
                    OutlinedTextField(command, { command = it }, label = { Text("Command") }, modifier = Modifier.fillMaxWidth(), enabled = !sending, singleLine = true)
                    Button(onClick = { confirm = command.trim() }, modifier = Modifier.fillMaxWidth(), enabled = !sending && !busy && online && command.trim().startsWith("AT", ignoreCase = true) && command.none { it == '\r' || it == '\n' }) { Text(if (sending) "Mengirim…" else "Execute") }
                    if (toolError.isNotEmpty()) Text(toolError, color = MaterialTheme.colorScheme.error)
                    if (output.isNotEmpty()) SelectionContainer { Text(output, modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyMedium) }
                }
            }
        }
    }
    certPrompt?.let { prompt -> CertificateDialog(prompt, onTrust = {
        certPrompt = null
        trust.save(prompt.info); trustRevision++
        prefs.edit().putString("ssl|${prompt.target}", SslMode.SELF_SIGNED.key).apply()
        error = ""
        val url = try { parseModemUrl(prompt.target) } catch (_: Exception) { null }
        if (url != null) perform(prompt.action, prompt.target, url, prompt.info)
    }, onCancel = { certPrompt = null; if (prompt.previous != null) error = CERT_CHANGED_MESSAGE }) }
    confirm?.let { text -> AlertDialog(onDismissRequest = { confirm = null }, title = { Text("Jalankan AT Command?") }, text = { Text("$text\n\nPerintah dapat memengaruhi koneksi modem.") }, confirmButton = { TextButton(onClick = { confirm = null; send(text) }) { Text("Execute") } }, dismissButton = { TextButton(onClick = { confirm = null }) { Text("Batal") } }) }
}

@Composable private fun CertificateDialog(prompt: CertPrompt, onTrust: () -> Unit, onCancel: () -> Unit) {
    val info = prompt.info
    val warn = Color(0xFFFFC7B7)
    AlertDialog(onDismissRequest = onCancel,
        title = { Text(if (prompt.previous != null) "Sertifikat modem BERUBAH" else "Verifikasi sertifikat HTTPS") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (prompt.previous != null) Text("Fingerprint berbeda dari yang pernah Anda setujui. Ini wajar setelah reset/upgrade firmware, tetapi juga bisa tanda penyadapan. Lanjutkan hanya jika Anda yakin.\nSebelumnya: ${prompt.previous.sha256}", color = warn)
                Text("Password belum dikirim. Cocokkan fingerprint dengan sertifikat di modem sebelum mempercayainya.", color = Muted, style = MaterialTheme.typography.bodySmall)
                SelectionContainer { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    CertLine("Hostname", "${info.host}:${info.port}")
                    CertLine("SHA-256", info.sha256)
                    CertLine("Subject", info.subject.ifBlank { "—" })
                    CertLine("Issuer", info.issuer.ifBlank { "—" } + if (info.selfSigned) " (self-signed)" else "")
                    CertLine("Berlaku", "${formatCertDate(info.notBefore)} s/d ${formatCertDate(info.notAfter)}")
                } }
                if (info.expired) Text("Sertifikat sudah kedaluwarsa.", color = warn)
                if (info.notYetValid) Text("Sertifikat belum berlaku — periksa jam ponsel/modem.", color = warn)
            }
        },
        confirmButton = { TextButton(onClick = onTrust) { Text("Percayai & lanjutkan") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Batal") } })
}
@Composable private fun CertLine(label: String, value: String) {
    Column { Text(label, color = Muted, style = MaterialTheme.typography.labelMedium); Text(value, style = MaterialTheme.typography.bodySmall) }
}

@Composable internal fun Panel(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) { Text(title, color = Muted, style = MaterialTheme.typography.labelLarge); content() }
    }
}
@Composable private fun Metric(label: String, value: String, unit: String, modifier: Modifier = Modifier) {
    Column(modifier.background(Bg, MaterialTheme.shapes.medium).padding(10.dp)) { Text(label, color = Muted, style = MaterialTheme.typography.labelMedium); Text(value, color = Mint, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold); Text(unit, color = Muted, style = MaterialTheme.typography.labelSmall) }
}
@Composable private fun Signal(label: String, data: JSONObject?) {
    Panel("$label • ${data.value("band")}") { Text(data.value("state"), color = Muted); Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { Metric("RSRP", data.value("rsrp"), "dBm", Modifier.weight(1f)); Metric("RSRQ", data.value("rsrq"), "dB", Modifier.weight(1f)); Metric("SINR", data.value("sinr"), "dB", Modifier.weight(1f)) }; Detail("PCI", data.value("pci")) }
}
@Composable internal fun Detail(label: String, value: String) { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) { Text(label, color = Muted, modifier = Modifier.weight(0.8f)); SelectionContainer(Modifier.weight(1.2f)) { Text(value) } } }
private fun rate(obj: JSONObject?, key: String): String = obj.number(key)?.let { String.format(Locale.US, "%.1f", it * 8 / 1_000_000) } ?: "—"
@Composable private fun TrafficGraph(down: List<Float>, up: List<Float>) {
    Canvas(Modifier.fillMaxWidth().height(95.dp)) {
        val top = maxOf(1f, down.maxOrNull() ?: 0f, up.maxOrNull() ?: 0f)
        for (i in 0..3) { val y = size.height * i / 3; drawLine(Color(0xFF34433D), Offset(0f, y), Offset(size.width, y)) }
        listOf(down to Mint, up to Color(0xFF87B3FF)).forEach { (series, color) ->
            if (series.size > 1) for (i in 1 until series.size) { val step = size.width / (series.size - 1); drawLine(color, Offset((i-1)*step, size.height * (1-series[i-1]/top)), Offset(i*step, size.height * (1-series[i]/top)), strokeWidth = 2.dp.toPx()) }
        }
    }
}
