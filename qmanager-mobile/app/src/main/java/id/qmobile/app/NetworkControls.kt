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
private val reads = listOf("bands/current.sh", "tower/status.sh", "cellular/apn.sh", "cellular/settings.sh")
private data class Change(val path: String, val body: JSONObject, val label: String)

@Composable
internal fun NetworkControls(api: Api, dashboard: JSONObject?, busy: Boolean, onBusy: (Boolean) -> Unit, onAuth: () -> Unit) {
    val scope = rememberCoroutineScope()
    var section by remember { mutableIntStateOf(0) }
    var data by remember { mutableStateOf<JSONObject?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<Change?>(null) }
    var device by remember { mutableStateOf(dashboard?.optJSONObject("device")) }
    fun run(change: Change? = null) {
        if (busy) return
        val read = reads[section]
        onBusy(true); data = null; message = ""; problem = false
        scope.launch {
            var accepted = false
            var warning = ""
            try {
                if (change != null) {
                    val result = api.request(change.path, change.body)
                    check(result.optBoolean("success", false)) { "Backend tidak mengonfirmasi perubahan" }
                    accepted = true
                    warning = if (result.optBoolean("service_enable_failed") || result.optBoolean("service_disable_failed")) " Peringatan: konfigurasi saat boot tidak berhasil diperbarui." else ""
                    delay(5000)
                }
                val fresh = api.request(read)
                check(fresh.optBoolean("success", false)) { "Format API tidak didukung" }
                when (read) {
                    reads[0] -> check(fresh.optJSONObject("current") != null) { "Format band tidak didukung" }
                    reads[1] -> check(fresh.optJSONObject("modem_state") != null) { "Format cell lock tidak didukung" }
                    reads[2] -> check(fresh.optJSONArray("profiles") != null) { "Format profil APN tidak didukung" }
                    reads[3] -> check(fresh.has("mode_pref") && fresh.optInt("nr5g_disable_mode", -1) in 0..2) { "Format network mode tidak didukung" }
                }
                data = fresh; revision++
                if (read == reads[0]) {
                    try { device = api.status().optJSONObject("device") } catch (e: CancellationException) { throw e } catch (e: AuthRequired) { throw e } catch (_: Exception) { /* keep previous device capabilities */ }
                }
                if (accepted) {
                    if (read == reads[3] && change != null) {
                        val matches = fresh.optString("mode_pref") == change.body.optString("mode_pref") && fresh.optInt("nr5g_disable_mode", -1) == change.body.optInt("nr5g_disable_mode", -2)
                        problem = !matches
                        message = if (matches) "Network mode terverifikasi. Registrasi jaringan bergantung pada cakupan operator." else "Backend menerima perubahan, tetapi mode aktual berbeda. Periksa hasil sebelum mencoba lagi."
                    } else message = "Backend menerima perubahan. Periksa konfigurasi aktual di bawah.$warning"
                }
            } catch (e: CancellationException) { throw e }
            catch (e: AuthRequired) { onAuth() }
            catch (e: Exception) {
                problem = true
                message = if (accepted) "Perubahan diterima, tetapi pembacaan ulang gagal.$warning Muat ulang sebelum mencoba lagi." else if (change != null) "Perubahan tidak terkonfirmasi: ${e.message}. Muat ulang untuk memeriksa hasil sebelum mengulang." else "Gagal membaca: ${e.message}"
            } finally { onBusy(false) }
        }
    }
    Panel("Network management") {
        Text("Gunakan LAN atau Wi-Fi lokal. Perubahan jaringan dapat memutus koneksi internet modem.", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Band", "Cell", "APN", "Mode").forEachIndexed { i, title ->
                FilterChip(selected = section == i, onClick = { section = i; data = null; message = ""; pending = null }, label = { Text(title) }, enabled = !busy)
            }
        }
        OutlinedButton(onClick = { run() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Memproses…" else "Baca / Refresh konfigurasi") }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (message.isNotBlank()) Text(message, color = if (problem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
    }
    data?.let { fresh -> key(section, revision) {
        when (section) {
            0 -> BandEditor(fresh, device, busy) { pending = it }
            1 -> CellEditor(fresh, dashboard, busy) { pending = it }
            2 -> ApnEditor(fresh, busy) { pending = it }
            3 -> ModeEditor(fresh, busy) { pending = it }
        }
    } }
    pending?.let { change ->
        AlertDialog(onDismissRequest = { pending = null }, title = { Text("Terapkan perubahan?") }, text = { Text("${change.label}\n\nKoneksi internet modem dapat terputus sementara atau hingga pengaturan dikembalikan. Pastikan akses LAN tersedia.") }, confirmButton = { TextButton(enabled = !busy, onClick = { pending = null; run(change) }) { Text("Terapkan") } }, dismissButton = { TextButton(onClick = { pending = null }) { Text("Batal") } })
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

@Composable private fun ApnEditor(data: JSONObject, busy: Boolean, change: (Change) -> Unit) {
    val profiles = data.objects("profiles").filter { it.optInt("index") in 1..6 }.sortedBy { it.optInt("index") }
    var chosen by remember { mutableIntStateOf(profiles.firstOrNull()?.optInt("index") ?: 1) }
    Panel("APN profiles") {
        Text("Pilih PDP context (CID) untuk melihat atau mengedit profil. IMS dan emergency tidak dapat diedit.", style = MaterialTheme.typography.bodySmall)
        if (profiles.isEmpty()) Text("Tidak ada profil yang dapat dibaca.")
        profiles.forEach { p ->
            val index = p.optInt("index")
            FilterChip(selected = chosen == index, onClick = { chosen = index }, enabled = !busy, label = { Text("CID $index • ${p.value("apn", "Kosong")} • ${if (p.optBoolean("enabled")) "Active" else "Inactive"}") })
        }
    }
    val profile = profiles.firstOrNull { it.optInt("index") == chosen } ?: return
    key(chosen) {
        val protected = chosen in 2..3 || profile.value("apn_type", "").lowercase() in listOf("ims", "emergency", "sos") || profile.value("apn", "").lowercase() in listOf("ims", "sos", "emergency")
        var name by remember { mutableStateOf(profile.value("name", "")) }; var apn by remember { mutableStateOf(profile.value("apn", "")) }
        var pdp by remember { mutableStateOf(profile.value("pdp_type", "").lowercase().ifBlank { "ipv4v6" }) }
        var auth by remember { mutableStateOf(profile.value("auth_type", "").lowercase().ifBlank { "none" }) }
        var user by remember { mutableStateOf(profile.value("username", "")) }; var pass by remember { mutableStateOf("") }
        val edit = !busy && !protected
        fun safe(s: String) = s.length <= 128 && s.none { it == '"' || it == '\\' || it.isISOControl() }
        val valid = apn.matches(Regex("[A-Za-z0-9][A-Za-z0-9.-]{0,99}")) && safe(name) && safe(user) && safe(pass) && pdp in listOf("ipv4", "ipv6", "ipv4v6") && auth in listOf("none", "pap", "chap")
        Panel("Edit APN • CID $chosen") {
            Detail("Status", profile.value("connect_progress")); Detail("IPv4", profile.value("ipv4_address")); Detail("IPv6", profile.value("ipv6_address")); Detail("DNS", profile.value("dns1"))
            if (protected) Text("Read-only: slot IMS/emergency atau slot operator yang dilindungi.", color = MaterialTheme.colorScheme.error)
            OutlinedTextField(name, { name = it }, label = { Text("Profile name") }, singleLine = true, enabled = edit, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(apn, { apn = it }, label = { Text("APN") }, singleLine = true, enabled = edit, modifier = Modifier.fillMaxWidth())
            Text("PDP type")
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) { listOf("ipv4", "ipv6", "ipv4v6").forEach { n -> FilterChip(selected = pdp == n, onClick = { pdp = n }, enabled = edit, label = { Text(n.uppercase()) }) } }
            Text("Authentication")
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) { listOf("none", "pap", "chap").forEach { n -> FilterChip(selected = auth == n, onClick = { auth = n }, enabled = edit, label = { Text(n.uppercase()) }) } }
            if (auth != "none") {
                OutlinedTextField(user, { user = it }, label = { Text("Username") }, singleLine = true, enabled = edit, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(pass, { pass = it }, label = { Text("Password (kosong = pertahankan)") }, singleLine = true, enabled = edit, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            } else Text("NONE akan menghapus kredensial APN tersimpan saat save.", style = MaterialTheme.typography.bodySmall)
            Button(onClick = { change(Change("cellular/apn.sh", obj("action" to "save", "index" to chosen, "name" to name, "apn" to apn, "pdp_type" to pdp, "auth_type" to auth, "username" to if (auth == "none") "" else user, "password" to if (auth == "none") "" else pass), "Simpan APN $apn di CID $chosen ($pdp / $auth)")) }, enabled = edit && valid, modifier = Modifier.fillMaxWidth()) { Text("Save APN") }
            val active = profile.optBoolean("enabled")
            OutlinedButton(onClick = { change(Change("cellular/apn.sh", obj("action" to "toggle", "index" to chosen, "enabled" to !active), "${if (active) "Nonaktifkan" else "Aktifkan"} CID $chosen")) }, enabled = edit && profile.has("enabled") && profile.value("apn", "").isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text(if (active) "Deactivate context" else "Activate context") }
        }
    }
}


private data class ModeChoice(val title: String, val description: String, val pref: String, val disable: Int)
private val modeChoices = listOf(
    ModeChoice("Auto", "Pemilihan RAT otomatis; SA dan NSA diizinkan.", "AUTO", 0),
    ModeChoice("LTE Only", "Hanya LTE / 4G.", "LTE", 0),
    ModeChoice("LTE + 5G", "LTE dan NR; SA serta NSA diizinkan.", "LTE:NR5G", 0),
    ModeChoice("5G NSA + LTE", "SA dinonaktifkan; LTE tetap diperlukan sebagai anchor dan dapat menjadi fallback.", "LTE:NR5G", 1),
    ModeChoice("5G SA Only", "NR standalone, tanpa fallback LTE.", "NR5G", 2)
)
@Composable private fun ModeEditor(data: JSONObject, busy: Boolean, change: (Change) -> Unit) {
    val actualPref = data.optString("mode_pref")
    val actualDisable = data.optInt("nr5g_disable_mode", -1)
    val actual = modeChoices.firstOrNull { it.pref == actualPref && it.disable == actualDisable }
    var selected by remember { mutableStateOf(actual) }
    Panel("Network Mode") {
        Detail("Konfigurasi aktual", actual?.title ?: "$actualPref / NR disable $actualDisable")
        Detail("Mode preference", actualPref)
        Detail("NR disable", when(actualDisable) { 0 -> "SA + NSA diizinkan"; 1 -> "SA disabled"; 2 -> "NSA disabled"; else -> "Tidak diketahui" })
        modeChoices.forEach { mode ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                RadioButton(selected = selected == mode, onClick = { selected = mode }, enabled = !busy)
                Column(Modifier.weight(1f)) { Text(mode.title); Text(mode.description, style = MaterialTheme.typography.bodySmall) }
            }
        }
        Text("Mode adalah preferensi modem, bukan jaminan registrasi 5G. Band/cell lock yang ada tetap berlaku. Profil otomatis QManager dapat menimpa mode.", style = MaterialTheme.typography.bodySmall)
        Button(onClick = { selected?.let { mode -> change(Change("cellular/settings.sh", obj("mode_pref" to mode.pref, "nr5g_disable_mode" to mode.disable), "Network mode: ${mode.title}")) } }, enabled = !busy && selected != null && selected != actual, modifier = Modifier.fillMaxWidth()) { Text("Apply Network Mode") }
    }
}
