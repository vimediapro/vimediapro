package id.qmobile.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Settings > Diagnostics. Hanya menjalankan perintah baca; laporan sudah disensor. */
@Composable internal fun DiagnosticsPanel(gateway: ModemGateway, connection: String, appVersion: String, busy: Boolean, onBusy: (Boolean) -> Unit, onAuth: () -> Unit) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var report by remember { mutableStateOf(gateway.report(appVersion, connection)) }
    var message by remember { mutableStateOf("") }
    var failed by remember { mutableStateOf(false) }
    val checks = remember(report) { gateway.checks() }
    Panel("Diagnostics") {
        Text("Menjalankan pemeriksaan read-only: ATI, AT+QGMR, GET apn.sh / settings.sh / network_priority.sh / fetch_data.sh / about.sh, serta query AT (CGDCONT?, CGACT?, QICSGP=?, CGAUTH=?, QNWPREFCFG). Tidak mengubah konfigurasi modem.", style = MaterialTheme.typography.bodySmall)
        Button(onClick = {
            onBusy(true); message = ""; failed = false
            scope.launch {
                try { gateway.runDiagnostics(); message = "Diagnostics selesai." }
                catch (e: CancellationException) { throw e }
                catch (e: AuthRequired) { onAuth() }
                catch (e: Exception) { failed = true; message = "Diagnostics tidak lengkap: ${e.message}" }
                finally { report = gateway.report(appVersion, connection); onBusy(false) }
            }
        }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Memproses…" else "Jalankan Diagnostics") }
        OutlinedButton(onClick = { clipboard.setText(AnnotatedString(report)); failed = false; message = "Diagnostic report disalin ke clipboard (sudah disensor)." }, modifier = Modifier.fillMaxWidth()) { Text("Copy Diagnostic Report") }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (message.isNotEmpty()) Text(message, color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
    }
    Panel("Modem & backend") {
        val id = gateway.identity
        Detail("Model", id?.model ?: "Unknown")
        Detail("Revision", id?.revision ?: "Unknown")
        Detail("Firmware", id?.firmware ?: "Unknown")
        Detail("Versi backend", "Tidak diekspos endpoint read-only")
        Detail("Koneksi", connection)
        Detail("APN data source", gateway.apnSource?.label ?: "Belum dibaca")
        Detail("Mode data source", gateway.modeSource?.label ?: "Belum dibaca")
    }
    if (checks.isNotEmpty()) Panel("Endpoint compatibility") {
        checks.forEach { c ->
            Column {
                Text(c.key, style = MaterialTheme.typography.labelLarge)
                Text("HTTP ${c.httpStatus ?: "-"} • ${c.verdict}${if (c.detail.isNotBlank()) " • " + redact(c.detail) else ""}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    Panel("Laporan (disensor)") {
        SelectionContainer { Text(report, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
    }
}
