package id.qmobile.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private fun obj(vararg pairs: Pair<String, Any>): JSONObject = JSONObject().apply { pairs.forEach { (k,v) -> put(k,v) } }
private fun JSONObject?.objects(key: String): List<JSONObject> = this?.optJSONArray(key)?.let { arr -> (0 until arr.length()).mapNotNull { arr.optJSONObject(it) } } ?: emptyList()
private fun bands(raw: String): Set<Int> = raw.split(':', ',', ' ').mapNotNull { it.trim().removePrefix("B").removePrefix("N").removePrefix("b").removePrefix("n").toIntOrNull()?.takeIf { n -> n in 1..1024 } }.toSet()
private val reads = listOf("bands/current.sh", "tower/status.sh")
private sealed interface Op { val label: String }
private data class Change(val path: String, val body: JSONObject, override val label: String) : Op
private data class ApnOp(val write: ApnWrite, override val label: String) : Op
private data class ModeOp(val choice: ModeChoice, override val label: String) : Op

@Composable
internal fun NetworkControls(api: Api, gateway: ModemGateway, dashboard: JSONObject?, busy: Boolean, onBusy: (Boolean) -> Unit, onAuth: () -> Unit) {
    val scope = rememberCoroutineScope()
    var section by remember { mutableIntStateOf(0) }
    var data by remember { mutableStateOf<JSONObject?>(null) }
    var apnState by remember { mutableStateOf<ApnState?>(null) }
    var modeState by remember { mutableStateOf<ModeState?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<Op?>(null) }
    var device by remember { mutableStateOf(dashboard?.optJSONObject("device")) }
    fun run(op: Op? = null) {
        if (busy) return
        val sec = section
        val apnBefore = apnState; val modeBefore = modeState
        onBusy(true); data = null; apnState = null; modeState = null; message = ""; problem = false
        scope.launch {
            var accepted = false
            var warning = ""
            var written = ""
            try {
                when (op) {
                    is Change -> {
                        val result = api.request(op.path, op.body)
                        check(result.optBoolean("success", false)) { "Backend tidak mengonfirmasi perubahan" }
                        accepted = true
                        warning = if (result.optBoolean("service_enable_failed") || result.optBoolean("service_disable_failed")) " Peringatan: konfigurasi saat boot tidak berhasil diperbarui." else ""
                        delay(5000)
                    }
                    is ApnOp -> { written = gateway.writeApn(checkNotNull(apnBefore) { "Baca konfigurasi APN terlebih dahulu" }, op.write); accepted = true; delay(5000) }
                    is ModeOp -> { written = gateway.writeMode(checkNotNull(modeBefore) { "Baca konfigurasi mode terlebih dahulu" }, op.choice); accepted = true; delay(5000) }
                    null -> Unit
                }
                when (sec) {
                    0, 1 -> {
                        val read = reads[sec]
                        val fresh = api.request(read)
                        check(fresh.optBoolean("success", false)) { "Format API tidak didukung" }
                        if (sec == 0) check(fresh.optJSONObject("current") != null) { "Format band tidak didukung" }
                        else check(fresh.optJSONObject("modem_state") != null) { "Format cell lock tidak didukung" }
                        data = fresh
                        if (sec == 0) {
                            try { device = api.status().optJSONObject("device") } catch (e: CancellationException) { throw e } catch (e: AuthRequired) { throw e } catch (_: Exception) { /* keep previous device capabilities */ }
                        }
                        if (accepted) message = "Backend menerima perubahan. Periksa konfigurasi aktual di bawah.$warning"
                    }
                    2 -> {
                        val fresh = gateway.readApn(); apnState = fresh
                        if (accepted && op is ApnOp) {
                            val p = fresh.profiles.firstOrNull { it.cid == op.write.cid }
                            val ok = when (val w = op.write) {
                                is ApnSave -> p != null && p.apn.equals(w.apn, ignoreCase = true) && (p.pdpType == null || p.pdpType == w.pdp)
                                is ApnToggle -> p?.active == null || p.active == w.enable
                            }
                            problem = !ok
                            message = if (ok) "Perubahan APN terverifikasi dari pembacaan ulang ($written)." else "Perintah diterima ($written), tetapi pembacaan ulang berbeda. Periksa sebelum mencoba lagi."
                        }
                    }
                    3 -> {
                        val fresh = gateway.readMode(); modeState = fresh
                        if (accepted && op is ModeOp) {
                            val ok = fresh.rats.toSet() == normalizeModePref(written).toSet() && (!fresh.nr5gSupported || fresh.nr5gDisable == op.choice.disable)
                            problem = !ok
                            message = if (ok) "Network mode terverifikasi (mode_pref=${fresh.raw}). Registrasi jaringan bergantung pada cakupan operator." else "Modem menerima perubahan, tetapi mode aktual berbeda (mode_pref=${fresh.raw}, nr5g=${fresh.nr5gDisable ?: "?"}). Periksa hasil sebelum mencoba lagi."
                        }
                    }
                }
                revision++
            } catch (e: CancellationException) { throw e }
            catch (e: AuthRequired) { onAuth() }
            catch (e: Exception) {
                problem = true
                message = if (accepted) "Perubahan diterima, tetapi pembacaan ulang gagal.$warning Muat ulang sebelum mencoba lagi." else if (op != null) "Perubahan tidak terkonfirmasi: ${e.message}. Muat ulang untuk memeriksa hasil sebelum mengulang." else "Gagal membaca: ${e.message}"
            } finally { onBusy(false) }
        }
    }
    Panel("Network management") {
        Text("Gunakan LAN atau Wi-Fi lokal. Perubahan jaringan dapat memutus koneksi internet modem. Membaca/refresh tidak mengubah konfigurasi.", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Band", "Cell", "APN", "Mode").forEachIndexed { i, title ->
                FilterChip(selected = section == i, onClick = { section = i; data = null; apnState = null; modeState = null; message = ""; pending = null }, label = { Text(title) }, enabled = !busy)
            }
        }
        OutlinedButton(onClick = { run() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Memproses…" else "Baca / Refresh konfigurasi") }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (message.isNotBlank()) Text(message, color = if (problem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
    }
    key(section, revision) {
        when (section) {
            0 -> data?.let { BandEditor(it, device, busy) { c -> pending = c } }
            1 -> data?.let { CellEditor(it, dashboard, busy) { c -> pending = c } }
            2 -> apnState?.let { ApnEditor(it, busy) { c -> pending = c } }
            3 -> modeState?.let { ModeEditor(it, busy) { c -> pending = c } }
        }
    }
    pending?.let { change ->
        AlertDialog(onDismissRequest = { pending = null }, title = { Text("Terapkan perubahan?") }, text = { Text("${change.label}\n\nKoneksi internet modem dapat terputus sementara atau hingga pengaturan dikembalikan. Pastikan akses LAN tersedia. Tidak ada pengulangan otomatis bila gagal.") }, confirmButton = { TextButton(enabled = !busy, onClick = { pending = null; run(change) }) { Text("Terapkan") } }, dismissButton = { TextButton(onClick = { pending = null }) { Text("Batal") } })
    }
}

@Composable private fun BandEditor(data: JSONObject, device: JSONObject?, busy: Boolean, change: (Change) -> Unit) {
    val current = data.optJSONObject("current")
    val categories = listOf(Triple("LTE / 4G", "lte", "lte_bands"), Triple("5G NSA", "nsa_nr5g", "nsa_nr5g_bands"), Triple("5G SA", "sa_nr5g", "sa_nr5g_bands"))
    categories.forEach { (label, type, field) -> key(type) {
        val actual = bands(current.value(field, ""))
        val supported = bands(device.value("supported_$field", ""))
        val prefix = if (type == "lte") "B" else "N"
        var selected by remember { mutableStateOf(actual) }
        val valid = supported.isNotEmpty() && actual.isNotEmpty() && supported.containsAll(actual)
        Panel("Band Lock • $label") {
            Text("Aktual: ${actual.sorted().joinToString(" / ") { "$prefix$it" }.ifBlank { "Tidak diketahui" }}")
            if (!valid) Text("Daftar band modem belum lengkap atau tidak cocok. Pengubahan dinonaktifkan.", color = MaterialTheme.colorScheme.error)
            (supported + actual).sorted().chunked(4).forEach { group ->
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) { group.forEach { n -> FilterChip(selected = n in selected, onClick = { selected = if (n in selected) selected - n else selected + n }, enabled = valid && !busy, label = { Text("$prefix$n") }) } }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { selected = supported }, enabled = valid && !busy) { Text("Pilih semua") }
                TextButton(onClick = { selected = actual }, enabled = valid && !busy) { Text("Batal edit") }
            }
            fun request(set: Set<Int>, label: String) = Change("bands/lock.sh", obj("band_type" to type, "bands" to set.sorted().joinToString(":")), "$label • ${set.sorted().joinToString(", ") { "$prefix$it" }}")
            Button(onClick = { change(request(selected, "Lock $label")) }, enabled = valid && !busy && selected.isNotEmpty() && selected != actual, modifier = Modifier.fillMaxWidth()) { Text("Apply band lock") }
            OutlinedButton(onClick = { change(request(supported, "Aktifkan semua band $label")) }, enabled = valid && !busy, modifier = Modifier.fillMaxWidth()) { Text("Restore all supported bands") }
        }
    } }
    data.optJSONObject("failover")?.let { f -> Panel("Band failover") { Detail("Enabled", f.value("enabled")); Detail("Activated", f.value("activated")) } }
}

private data class CellInput(val freq: String = "", val pci: String = "")
private fun String.inRange(range: IntRange) = toIntOrNull()?.let { it in range } == true
@Composable private fun NumberField(label: String, value: String, enabled: Boolean, edit: (String) -> Unit) {
    OutlinedTextField(value, { if (it.length <= 8 && it.all(Char::isDigit)) edit(it) }, label = { Text(label) }, singleLine = true, enabled = enabled, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
}
@Composable private fun CellEditor(data: JSONObject, dashboard: JSONObject?, busy: Boolean, change: (Change) -> Unit) {
    val state = data.optJSONObject("modem_state") ?: return
    val lteOk = state.has("lte_locked") && state.optBoolean("lte_read_ok", true)
    val nrOk = state.has("nr_locked") && state.optBoolean("nr_read_ok", true)
    val old = state.objects("lte_cells")
    var cells by remember { mutableStateOf(List(3) { i -> old.getOrNull(i)?.let { CellInput(it.value("earfcn", ""), it.value("pci", "")) } ?: CellInput() }) }
    val nrCell = state.optJSONObject("nr_cell")
    var arfcn by remember { mutableStateOf(nrCell.value("arfcn", "")) }; var pci by remember { mutableStateOf(nrCell.value("pci", "")) }
    var band by remember { mutableStateOf(nrCell.value("band", "")) }; var scs by remember { mutableStateOf(nrCell.value("scs", "")) }
    fun lockState(ok: Boolean, key: String) = if (!ok) "Tidak diketahui" else if (state.optBoolean(key)) "Locked" else "Unlocked"
    Panel("Cell Lock • LTE") {
        Detail("Status", lockState(lteOk, "lte_locked"))
        Text("Maksimal 3 target EARFCN + PCI. Lock baru menggantikan target LTE sebelumnya.", style = MaterialTheme.typography.bodySmall)
        val lte = dashboard?.optJSONObject("lte")
        TextButton(onClick = { cells = cells.toMutableList().apply { this[0] = CellInput(lte.value("earfcn", ""), lte.value("pci", "")) } }, enabled = lteOk && !busy && lte.value("earfcn").inRange(0..262143) && lte.value("pci").inRange(0..503)) { Text("Isi target 1 dari dashboard terakhir") }
        cells.forEachIndexed { i, c ->
            Text("Target ${i+1}")
            NumberField("EARFCN", c.freq, lteOk && !busy) { v -> cells = cells.toMutableList().apply { this[i] = c.copy(freq = v) } }
            NumberField("PCI", c.pci, lteOk && !busy) { v -> cells = cells.toMutableList().apply { this[i] = c.copy(pci = v) } }
        }
        val targets = cells.filter { it.freq.isNotEmpty() || it.pci.isNotEmpty() }
        val valid = targets.isNotEmpty() && targets.all { it.freq.inRange(0..262143) && it.pci.inRange(0..503) } && targets.map { it.freq.toInt() to it.pci.toInt() }.distinct().size == targets.size
        Button(onClick = { val arr = JSONArray(); targets.forEach { arr.put(obj("earfcn" to it.freq.toInt(), "pci" to it.pci.toInt())) }; change(Change("tower/lock.sh", obj("type" to "lte", "action" to "lock", "cells" to arr), "Lock LTE: ${targets.joinToString { "EARFCN ${it.freq}, PCI ${it.pci}" }}")) }, enabled = lteOk && !busy && valid, modifier = Modifier.fillMaxWidth()) { Text("Lock LTE cells") }
        OutlinedButton(onClick = { change(Change("tower/lock.sh", obj("type" to "lte", "action" to "unlock"), "Unlock LTE")) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Unlock LTE") }
    }
    Panel("Cell Lock • 5G SA") {
        Detail("Status", lockState(nrOk, "nr_locked"))
        Text("Khusus SA. Pada NSA, gunakan LTE anchor atau Band Lock. Isi target dari hasil scan yang sesuai.", style = MaterialTheme.typography.bodySmall)
        NumberField("NR-ARFCN", arfcn, nrOk && !busy) { arfcn = it }; NumberField("PCI", pci, nrOk && !busy) { pci = it }; NumberField("NR band (contoh 40)", band, nrOk && !busy) { band = it }
        Text("Subcarrier spacing (kHz)")
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { listOf("15", "30", "60", "120").forEach { n -> FilterChip(selected = scs == n, onClick = { scs = n }, enabled = nrOk && !busy, label = { Text(n) }) } }
        FilterChip(selected = scs == "240", onClick = { scs = "240" }, enabled = nrOk && !busy, label = { Text("240 kHz") })
        val valid = arfcn.inRange(0..3279165) && pci.inRange(0..1007) && band.inRange(1..1024) && scs in listOf("15", "30", "60", "120", "240")
        Button(onClick = { change(Change("tower/lock.sh", obj("type" to "nr_sa", "action" to "lock", "pci" to pci.toInt(), "arfcn" to arfcn.toInt(), "scs" to scs.toInt(), "band" to band.toInt()), "Lock 5G SA: N$band, ARFCN $arfcn, PCI $pci, $scs kHz")) }, enabled = nrOk && !busy && valid, modifier = Modifier.fillMaxWidth()) { Text("Lock 5G SA cell") }
        OutlinedButton(onClick = { change(Change("tower/lock.sh", obj("type" to "nr_sa", "action" to "unlock"), "Unlock 5G SA")) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Unlock 5G SA") }
    }
}

@Composable private fun SourceLine(source: DataSource, reason: String) {
    Detail("Data source", source.label)
    if (source == DataSource.AT) Text("API tidak kompatibel ($reason). Data dibaca langsung dari modem via at_cmd/send_command.sh.", style = MaterialTheme.typography.bodySmall)
}
private fun unknown(v: String?) = v?.takeIf { it.isNotBlank() } ?: "Unknown"
private fun yesNo(v: Boolean?, yes: String, no: String) = when (v) { true -> yes; false -> no; null -> "Unknown" }

@Composable private fun ApnEditor(state: ApnState, busy: Boolean, change: (Op) -> Unit) {
    val profiles = state.profiles
    var chosen by remember { mutableIntStateOf(profiles.firstOrNull { !it.protected && it.defined }?.cid ?: profiles.firstOrNull()?.cid ?: 1) }
    Panel("APN profiles") {
        SourceLine(state.source, state.reason)
        if (state.source == DataSource.AT) Detail("Dukungan autentikasi", "AT+QICSGP: ${if (state.auth.qicsgp) "ya" else "tidak"} • AT+CGAUTH: ${if (state.auth.cgauth) "ya" else "tidak"}")
        Text("Pilih PDP context (CID). Profil IMS dan Emergency tidak dapat diubah.", style = MaterialTheme.typography.bodySmall)
        if (profiles.isEmpty()) Text("Tidak ada PDP context yang terdefinisi.")
        profiles.forEach { p ->
            FilterChip(selected = chosen == p.cid, onClick = { chosen = p.cid }, enabled = !busy, label = { Text("CID ${p.cid} • ${p.apn?.ifBlank { "(kosong)" } ?: "Unknown"} • ${yesNo(p.active, "Active", "Inactive")}${if (p.protected) " • 🔒" else ""}") })
        }
    }
    val profile = profiles.firstOrNull { it.cid == chosen } ?: return
    key(chosen) {
        val viaApi = state.source == DataSource.API
        val authEditable = viaApi || state.auth.any
        var name by remember { mutableStateOf(profile.name.orEmpty()) }; var apn by remember { mutableStateOf(profile.apn.orEmpty()) }
        var pdp by remember { mutableStateOf(profile.pdpType?.takeIf { it in listOf("ipv4", "ipv6", "ipv4v6") } ?: "ipv4v6") }
        var auth by remember { mutableStateOf(profile.authType?.takeIf { it in listOf("none", "pap", "chap") && authEditable } ?: "none") }
        var user by remember { mutableStateOf(profile.username.orEmpty()) }; var pass by remember { mutableStateOf("") }
        var reattach by remember { mutableStateOf(true) }
        val edit = !busy && !profile.protected
        val passwordRequired = !viaApi && !state.auth.qicsgp && auth != "none"
        fun safe(s: String) = s.length <= 64 && s.none { it == '"' || it == '\\' || it.isISOControl() }
        val valid = apn.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,99}")) && safe(name) && safe(user) && safe(pass) && pdp in listOf("ipv4", "ipv6", "ipv4v6") && auth in listOf("none", "pap", "chap") && (!passwordRequired || pass.isNotEmpty())
        Panel("Edit APN • CID $chosen") {
            Detail("Status", unknown(profile.status)); Detail("PDP type", unknown(profile.pdpType?.uppercase())); Detail("Auth", unknown(profile.authType?.uppercase()))
            Detail("Username", if (profile.authType == "none") "—" else unknown(profile.username)); Detail("Password tersimpan", yesNo(profile.hasPassword, "Ya", "Tidak"))
            Detail("IPv4", unknown(profile.ipv4)); Detail("IPv6", unknown(profile.ipv6)); Detail("DNS", unknown(profile.dns))
            if (profile.protected) Text("Read-only: ${profile.protectionReason}.", color = MaterialTheme.colorScheme.error)
            if (viaApi) OutlinedTextField(name, { name = it }, label = { Text("Profile name") }, singleLine = true, enabled = edit, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(apn, { apn = it }, label = { Text("APN") }, singleLine = true, enabled = edit, modifier = Modifier.fillMaxWidth())
            Text("PDP type")
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) { listOf("ipv4", "ipv6", "ipv4v6").forEach { n -> FilterChip(selected = pdp == n, onClick = { pdp = n }, enabled = edit, label = { Text(n.uppercase()) }) } }
            Text("Authentication")
            if (!authEditable) Text("Firmware tidak mendukung AT+QICSGP/AT+CGAUTH: autentikasi tidak dapat diubah; hanya APN dan PDP type yang ditulis.", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) { listOf("none", "pap", "chap").forEach { n -> FilterChip(selected = auth == n, onClick = { auth = n }, enabled = edit && authEditable, label = { Text(n.uppercase()) }) } }
            if (auth != "none") {
                OutlinedTextField(user, { user = it }, label = { Text("Username") }, singleLine = true, enabled = edit, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(pass, { pass = it }, label = { Text(if (passwordRequired) "Password (wajib)" else "Password (kosong = pertahankan)") }, singleLine = true, enabled = edit, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            } else if (authEditable) Text("NONE akan menghapus kredensial APN tersimpan saat save.", style = MaterialTheme.typography.bodySmall)
            if (!viaApi) Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Checkbox(checked = reattach, onCheckedChange = { reattach = it }, enabled = edit)
                Text("Registrasi ulang (AT+COPS=2 → AT+COPS=0) agar APN baru dinegosiasikan. Internet modem terputus sebentar.", style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = { change(ApnOp(ApnSave(chosen, name, apn, pdp, auth, if (auth == "none") "" else user, if (auth == "none") "" else pass, !viaApi && reattach), "Simpan APN $apn di CID $chosen (${pdp.uppercase()} / ${auth.uppercase()}) via ${state.source.label}${if (!viaApi && reattach) " + registrasi ulang" else ""}")) }, enabled = edit && valid, modifier = Modifier.fillMaxWidth()) { Text("Save APN") }
            val active = profile.active
            OutlinedButton(onClick = { if (active != null) change(ApnOp(ApnToggle(chosen, !active), "${if (active) "Nonaktifkan" else "Aktifkan"} CID $chosen via ${state.source.label}")) }, enabled = edit && active != null && !profile.apn.isNullOrBlank(), modifier = Modifier.fillMaxWidth()) { Text(if (active == true) "Deactivate context" else "Activate context") }
        }
    }
}

@Composable private fun ModeEditor(state: ModeState, busy: Boolean, change: (Op) -> Unit) {
    val actual = state.actual
    var selected by remember { mutableStateOf(actual) }
    Panel("Network Mode") {
        SourceLine(state.source, state.reason)
        Detail("Konfigurasi aktual", actual?.title ?: "Kustom (${state.raw} / nr5g ${state.nr5gDisable ?: "?"})")
        Detail("mode_pref", state.raw)
        Detail("Prioritas RAT", state.rats.joinToString(" → ").ifBlank { "Unknown" })
        Detail("nr5g_disable_mode", nr5gDisableLabel(state.nr5gDisable))
        state.ratOrder?.let { Detail("RAT acquisition order", it) }
        Detail("Dukungan 5G", when (state.fiveG) { true -> "Ya"; false -> "Tidak"; null -> "Unknown" })
        if (!state.nr5gSupported) Text("nr5g_disable_mode tidak didukung/terbaca: opsi NSA dan SA Only dinonaktifkan.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        modeChoices.forEach { mode ->
            val blocked = state.blockedReason(mode)
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                RadioButton(selected = selected == mode, onClick = { selected = mode }, enabled = !busy && blocked == null)
                Column(Modifier.weight(1f)) { Text(mode.title); Text(blocked ?: mode.description, style = MaterialTheme.typography.bodySmall, color = if (blocked != null) MaterialTheme.colorScheme.error else androidx.compose.ui.graphics.Color.Unspecified) }
            }
        }
        Text("Mode adalah preferensi modem, bukan jaminan registrasi 5G. Urutan prioritas RAT yang ada dipertahankan bila set RAT sama. Band/cell lock tetap berlaku.", style = MaterialTheme.typography.bodySmall)
        val choice = selected
        Button(onClick = { if (choice != null) { val pref = modePrefToWrite(choice, state.rats); change(ModeOp(choice, "Network mode: ${choice.title} (mode_pref=$pref${if (state.nr5gSupported) ", nr5g_disable_mode=${choice.disable}" else ""}) via ${state.source.label}")) } },
            enabled = !busy && choice != null && state.blockedReason(choice) == null && choice != actual, modifier = Modifier.fillMaxWidth()) { Text("Apply Network Mode") }
    }
}
