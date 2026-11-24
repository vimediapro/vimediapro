package id.qmobile.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private const val SMS_ENDPOINT = "cellular/sms.sh"
private data class SmsEntry(val address: String, val name: String, val content: String, val timestamp: String, val storage: String, val indexes: List<Int>, val identity: String, val outbound: Boolean, val status: String) {
    val canDelete: Boolean get() = if (storage == "SENT") identity.isNotBlank() else storage in listOf("ME", "SM") && indexes.isNotEmpty()
}
private data class Inbox(val entries: List<SmsEntry>, val usage: String, val smsc: String, val incomplete: Boolean)
private fun parseInbox(raw: JSONObject): Inbox {
    check(raw.optBoolean("success", false)) { "Backend tidak mengonfirmasi pembacaan SMS" }
    val values = raw.optJSONArray("messages") ?: error("Format daftar SMS tidak didukung")
    val entries = (0 until values.length()).map { i ->
        val item = values.getJSONObject(i)
        check(item.has("content") && (item.has("sender") || item.has("addr"))) { "Format pesan tidak didukung" }
        val address = item.value("addr", item.value("sender", ""))
        val indexes = item.optJSONArray("indexes")?.let { a -> (0 until a.length()).map { a.optInt(it, -1) }.takeIf { it.all { n -> n >= 0 } } } ?: emptyList()
        val storage = item.value("storage", "")
        SmsEntry(address, item.value("display_name", address), item.optString("content"), item.value("timestamp"), storage, indexes, item.value("identity", ""), storage == "SENT" || item.optString("direction") == "outbound" || item.optInt("type", -1) == 0, item.value("status", ""))
    }
    val storage = raw.optJSONObject("storage")
    return Inbox(entries, "${storage.value("used")} / ${storage.value("total")}", raw.optJSONObject("smsc").value("addr"), (raw.optJSONObject("storage_errors")?.length() ?: 0) > 0)
}
private sealed class SmsAction {
    data class Send(val phone: String, val body: String): SmsAction()
    data class Delete(val entry: SmsEntry): SmsAction()
}
private fun validPhone(phone: String) = phone.matches(Regex("\\+?[0-9]{1,20}"))
// Conservative single-part limit. Plain GSM-compatible subset, no extension alphabet.
private fun messageLimit(text: String): Int = if (text.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it in " !\"#%&'()*+,-./:;<=>?@_\n\r" }) 160 else 70

@Composable internal fun SmsCenter(api: Api, busy: Boolean, onBusy: (Boolean) -> Unit, onAuth: () -> Unit) {
    val scope = rememberCoroutineScope()
    var inbox by remember { mutableStateOf<Inbox?>(null) }
    var notice by remember { mutableStateOf("") }
    var failed by remember { mutableStateOf(false) }
    var filter by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var recipient by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var composing by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<SmsAction?>(null) }
    var page by remember { mutableIntStateOf(0) }
    fun run(action: SmsAction? = null) {
        if (busy) return
        onBusy(true); failed = false; notice = ""
        scope.launch {
            var accepted = false
            var attempted = false
            try {
                when (action) {
                    is SmsAction.Send -> {
                        check(validPhone(action.phone) && action.body.isNotBlank() && action.body.length <= messageLimit(action.body)) { "Nomor atau panjang pesan tidak valid" }
                        attempted = true
                        val result = api.request(SMS_ENDPOINT, JSONObject().put("action", "send").put("phone", action.phone).put("message", action.body))
                        check(result.optBoolean("success", false)) { "Pengiriman tidak dikonfirmasi" }
                        accepted = true; body = ""; recipient = ""; composing = false
                    }
                    is SmsAction.Delete -> {
                        // Re-read before deletion: SIM/modem slots can be reused by other clients.
                        val fresh = parseInbox(api.request(SMS_ENDPOINT)); inbox = fresh
                        check(!fresh.incomplete && fresh.entries.count { it == action.entry } == 1 && action.entry.canDelete) { "Pesan berubah atau penyimpanan tidak lengkap. Pilih kembali pesan setelah refresh." }
                        val item = action.entry
                        val payload = JSONObject().put("action", "delete").put("storage", item.storage)
                        if (item.storage == "SENT") payload.put("identities", JSONArray().put(item.identity))
                        else payload.put("indexes", JSONArray(item.indexes))
                        attempted = true
                        val result = api.request(SMS_ENDPOINT, payload)
                        check(result.optBoolean("success", false)) { "Penghapusan tidak dikonfirmasi" }
                        accepted = true
                    }
                    null -> Unit
                }
                val fresh = parseInbox(api.request(SMS_ENDPOINT)); inbox = fresh; page = 0
                if (accepted) {
                    if (action is SmsAction.Delete && fresh.entries.any { it == action.entry }) { failed = true; notice = "Backend menerima penghapusan, tetapi pesan masih terlihat. Periksa ulang." }
                    else notice = if (action is SmsAction.Send) "Modem menerima pengiriman; bukan konfirmasi diterima oleh tujuan." else "Pesan dihapus dari daftar yang terbaca."
                }
                if (fresh.incomplete) { failed = true; notice += " Sebagian penyimpanan SMS gagal dibaca. Daftar tidak lengkap." }
            } catch (e: CancellationException) { throw e }
            catch (e: AuthRequired) { onAuth() }
            catch (e: Exception) {
                failed = true
                notice = when {
                    accepted -> "Operasi diterima backend, tetapi refresh gagal. Jangan ulangi operasi; refresh dahulu."
                    attempted -> "Hasil operasi tidak terkonfirmasi: ${e.message}. Periksa daftar sebelum mengulang; SMS bisa sudah terkirim."
                    else -> "Gagal: ${e.message}"
                }
                // Require successful refresh before further mutations after errors.
                inbox = null
            } finally { onBusy(false) }
        }
    }
    Panel("SMS Management") {
        Text("Pesan SIM yang terpasang di modem, bukan SMS dari ponsel Android.", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { run() }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Refresh") }
            Button(onClick = { recipient = ""; body = ""; composing = true }, enabled = !busy && inbox != null, modifier = Modifier.weight(1f)) { Text("New SMS") }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (notice.isNotBlank()) Text(notice, color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        inbox?.let { data ->
            Detail("Storage ME + SIM", data.usage)
            Detail("SMSC", data.smsc)
            if (data.incomplete) Text("Daftar tidak lengkap; hapus dinonaktifkan.", color = MaterialTheme.colorScheme.error)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("All", "Inbox", "Sent").forEachIndexed { i, label -> FilterChip(selected = filter == i, onClick = { filter = i; page = 0 }, enabled = !busy, label = { Text(label) }) }
            }
            OutlinedTextField(query, { query = it; page = 0 }, label = { Text("Cari nomor / isi pesan") }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !busy)
        }
    }
    if (composing) Panel("Compose SMS") {
        OutlinedTextField(recipient, { recipient = it }, label = { Text("Nomor tujuan / short code") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !busy)
        OutlinedTextField(body, { body = it }, label = { Text("Pesan") }, minLines = 3, modifier = Modifier.fillMaxWidth(), enabled = !busy)
        Text("${body.length} / ${messageLimit(body)} karakter (batas konservatif satu SMS). Biaya operator berlaku.", style = MaterialTheme.typography.bodySmall)
        Button(onClick = { pending = SmsAction.Send(recipient.trim(), body) }, enabled = !busy && inbox != null && validPhone(recipient.trim()) && body.isNotBlank() && body.length <= messageLimit(body), modifier = Modifier.fillMaxWidth()) { Text("Send SMS") }
        TextButton(onClick = { composing = false; recipient = ""; body = "" }, enabled = !busy) { Text("Batal") }
    }
    inbox?.let { data ->
        val messages = data.entries.filter { (filter == 0 || (filter == 2) == it.outbound) && (query.isBlank() || listOf(it.address, it.name, it.content).any { field -> field.contains(query, ignoreCase = true) }) }
        if (messages.isEmpty()) Panel("Messages") { Text("Tidak ada pesan pada filter ini.") }
        messages.drop(page * 20).take(20).forEach { item ->
            Panel(if (item.outbound) "Sent • ${item.name}" else "Inbox • ${item.name}") {
                Detail("Nomor", item.address.ifBlank { "—" }); Detail("Waktu modem", item.timestamp); Detail("Storage", item.storage.ifBlank { "—" })
                if (item.outbound && item.status.isNotBlank()) Detail("Status modem", item.status)
                SelectionContainer { Text(item.content) }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { recipient = item.address; body = ""; composing = true }, enabled = !busy && validPhone(item.address)) { Text("Reply") }
                    TextButton(onClick = { recipient = ""; body = item.content; composing = true }, enabled = !busy) { Text("Forward") }
                    TextButton(onClick = { pending = SmsAction.Delete(item) }, enabled = !busy && !data.incomplete && item.canDelete) { Text("Delete", color = if (item.canDelete && !busy && !data.incomplete) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)) }
                }
            }
        }
        if (messages.size > 20) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { page-- }, enabled = !busy && page > 0) { Text("Previous") }
            Text("${page + 1} / ${(messages.size + 19) / 20}")
            TextButton(onClick = { page++ }, enabled = !busy && (page + 1) * 20 < messages.size) { Text("Next") }
        }
    }
    pending?.let { action ->
        val prompt = when (action) {
            is SmsAction.Send -> "Kirim ke ${action.phone} melalui SIM modem?\n\n${action.body}\n\nBiaya SMS operator dapat berlaku."
            is SmsAction.Delete -> "Hapus pesan dari ${action.entry.address} (${action.entry.storage}) secara permanen?"
        }
        AlertDialog(onDismissRequest = { pending = null }, title = { Text(if (action is SmsAction.Send) "Konfirmasi pengiriman" else "Konfirmasi hapus") }, text = { Text(prompt) }, confirmButton = { TextButton(enabled = !busy, onClick = { pending = null; run(action) }) { Text(if (action is SmsAction.Send) "Kirim" else "Hapus") } }, dismissButton = { TextButton(onClick = { pending = null }) { Text("Batal") } })
    }
}
