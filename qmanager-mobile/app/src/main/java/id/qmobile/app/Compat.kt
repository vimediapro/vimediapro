package id.qmobile.app

import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale

/*
 * Compatibility layer: QManager API ↔ AT Command Fallback.
 *
 * Aturan:
 *  - READ: coba endpoint QManager resmi. Bila gagal (HTTP error, bukan JSON, success:false seperti
 *    "parse_failed", atau format tidak dikenal) → baca via AT (read-only) melalui at_cmd/send_command.sh.
 *  - WRITE: memakai jalur yang sama dengan sumber data hasil READ terakhir (yang sudah diverifikasi).
 *    Tidak ada fallback write otomatis setelah POST gagal/timeout.
 *  - Membuka/refresh halaman hanya menjalankan perintah baca (GET, AT query "?"/"=?").
 */

data class EndpointCheck(val key: String, val httpStatus: Int?, val verdict: String, val detail: String, val raw: String, val time: Long = System.currentTimeMillis())
data class ModemIdentity(val model: String?, val revision: String?, val firmware: String?)

sealed interface ApnWrite { val cid: Int }
data class ApnSave(override val cid: Int, val name: String, val apn: String, val pdp: String, val auth: String, val username: String, val password: String, val reattach: Boolean) : ApnWrite
data class ApnToggle(override val cid: Int, val enable: Boolean) : ApnWrite

class ModemGateway(val api: Api) {
    private val checks: MutableMap<String, EndpointCheck> = Collections.synchronizedMap(LinkedHashMap())
    @Volatile var identity: ModemIdentity? = null; private set
    @Volatile private var authSupport: AuthSupport? = null
    @Volatile var apnSource: DataSource? = null; private set
    @Volatile var modeSource: DataSource? = null; private set
    private val hints: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet())

    fun checks(): List<EndpointCheck> = synchronized(checks) { checks.values.toList() }
    private fun record(key: String, http: Int?, verdict: String, detail: String, raw: String) {
        checks[key] = EndpointCheck(key, http, verdict, detail, redact(raw).take(1500))
    }

    // ----------------------------------------------------------------------- transport

    /** Kirim AT command via send_command.sh. [sensitive] = output berisi kredensial; tidak dicatat di diagnostik. */
    suspend fun at(command: String, sensitive: Boolean = false): AtResult {
        val r = api.rawRequest("at_cmd/send_command.sh", JSONObject().put("command", command))
        val res = if (r.code !in 200..299) AtResult(command, false, "", "HTTP ${r.code}") else atResultFromJson(command, jsonOrNull(r.body))
        val key = if (sensitive) "AT " + command.substringBefore(',') + (if (command.contains(',')) ",…" else "") else "AT $command"
        record(key, r.code, if (res.ok) "OK" else "ERROR", res.error.orEmpty(), if (sensitive) "[tidak dicatat: dapat berisi kredensial]" else res.text)
        return res
    }
    private suspend fun atOrThrow(command: String, step: String): AtResult {
        val r = at(command)
        if (!r.ok) throw IOException("$step gagal ($command → ${r.error ?: "ERROR"}). Langkah sebelumnya mungkin sudah diterapkan; baca ulang sebelum mencoba lagi.")
        return r
    }
    private suspend fun apiGet(path: String): Pair<RawResponse, JSONObject?> {
        val r = api.rawRequest(path)
        return r to jsonOrNull(r.body)
    }
    private fun apiFailure(r: RawResponse, json: JSONObject?): String = when {
        r.code !in 200..299 -> "HTTP ${r.code}"
        json == null -> "respons bukan JSON"
        json.has("success") && !json.optBoolean("success", true) -> listOf(json.optString("error"), json.optString("detail")).filter { it.isNotBlank() }.joinToString(": ").ifBlank { "success:false" }
        else -> "format respons tidak dikenali"
    }

    // ----------------------------------------------------------------------- identity

    suspend fun identify(force: Boolean = false): ModemIdentity {
        identity?.takeIf { !force }?.let { return it }
        val ati = at("ATI")
        val gmr = at("AT+QGMR")
        return ModemIdentity(
            if (ati.ok) parseAtiModel(ati.text) else null,
            if (ati.ok) parseAtiRevision(ati.text) else null,
            if (gmr.ok) parseQgmr(gmr.text) else null,
        ).also { identity = it }
    }
    private suspend fun identifySafely(): ModemIdentity? = try { identify() } catch (e: AuthRequired) { throw e } catch (_: Exception) { null }

    private suspend fun detectAuthSupport(): AuthSupport {
        authSupport?.let { return it }
        // Test command "=?" hanya membaca kemampuan, tidak mengubah konfigurasi.
        val q = at("AT+QICSGP=?").ok
        val c = at("AT+CGAUTH=?").ok
        return AuthSupport(q, c).also { authSupport = it }
    }

    // ----------------------------------------------------------------------- APN

    suspend fun readApn(): ApnState {
        val (r, json) = apiGet("cellular/apn.sh")
        val parsed = if (r.code in 200..299) parseApnApi(json) else null
        if (parsed != null) {
            if (json?.optString("data_source") == "at") hints += "APN: kontrak legacy 6-slot (QManager-RM520N)"
            record("GET cellular/apn.sh", r.code, "KOMPATIBEL", "legacy 6-slot (${parsed.size} profil)", r.body)
            apnSource = DataSource.API
            return ApnState(DataSource.API, parsed, AuthSupport(false, false), "Endpoint apn.sh kompatibel")
        }
        val reason = apiFailure(r, json)
        if (json?.optJSONArray("cids") != null) hints += "APN: kontrak v2 slot (QManager RM551E) — tidak dipakai untuk write"
        record("GET cellular/apn.sh", r.code, "TIDAK KOMPATIBEL → AT fallback", reason, r.body)

        val cg = at("AT+CGDCONT?")
        if (!cg.ok) throw IOException("API APN gagal ($reason) dan AT fallback AT+CGDCONT? gagal (${cg.error}).")
        val contexts = parseCgdcont(cg.text)
        val activation = at("AT+CGACT?").let { if (it.ok) parseCgact(it.text) else null }
        val support = detectAuthSupport()
        val auth = mutableMapOf<Int, AuthInfo>()
        if (support.qicsgp) {
            contexts.forEach { c -> val q = at("AT+QICSGP=${c.cid}", sensitive = true); if (q.ok) parseQicsgp(q.text)?.let { auth[c.cid] = it } }
        } else if (support.cgauth) {
            at("AT+CGAUTH?").takeIf { it.ok }?.let { auth.putAll(parseCgauth(it.text)) }
        }
        val addresses = at("AT+CGPADDR").let { if (it.ok) parseCgpaddr(it.text) else null }
        apnSource = DataSource.AT
        return ApnState(DataSource.AT, buildAtProfiles(contexts, activation, auth, addresses), support, "apn.sh: $reason")
    }

    /** Tulis APN memakai jalur sumber data yang sudah terverifikasi. Mengembalikan ringkasan langkah. */
    suspend fun writeApn(state: ApnState, op: ApnWrite): String {
        val profile = state.profiles.firstOrNull { it.cid == op.cid } ?: throw IOException("CID ${op.cid} tidak ada pada hasil baca terakhir")
        if (profile.protected) throw IOException("${profile.protectionReason} tidak boleh diubah")
        return when (state.source) {
            DataSource.API -> when (op) {
                is ApnSave -> { api.request("cellular/apn.sh", JSONObject().put("action", "save").put("index", op.cid).put("name", op.name).put("apn", op.apn).put("pdp_type", op.pdp)
                    .put("auth_type", op.auth).put("username", if (op.auth == "none") "" else op.username).put("password", if (op.auth == "none") "" else op.password)); "POST cellular/apn.sh (save)" }
                is ApnToggle -> { api.request("cellular/apn.sh", JSONObject().put("action", "toggle").put("index", op.cid).put("enabled", op.enable)); "POST cellular/apn.sh (toggle)" }
            }
            DataSource.AT -> when (op) {
                is ApnToggle -> { atOrThrow("AT+CGACT=${if (op.enable) 1 else 0},${op.cid}", "Aktivasi PDP context"); "AT+CGACT" }
                is ApnSave -> writeApnAt(state, profile, op)
            }
        }
    }

    private suspend fun writeApnAt(state: ApnState, profile: ApnProfile, op: ApnSave): String {
        require(op.apn.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,99}"))) { "APN tidak valid" }
        require(listOf(op.username, op.password).all { s -> s.length <= 64 && s.none { it == '"' || it == '\\' || it.isISOControl() } }) { "Username/password mengandung karakter tidak valid" }
        val steps = mutableListOf<String>()
        val wantsAuth = op.auth != "none"
        val hadAuth = profile.authType != null && profile.authType != "none"
        when {
            state.auth.qicsgp -> {
                var pass = op.password
                if (wantsAuth && pass.isEmpty()) {
                    // Pertahankan password tersimpan: dibaca langsung sebelum ditulis, tidak disimpan/dicatat.
                    pass = at("AT+QICSGP=${op.cid}", sensitive = true).takeIf { it.ok }?.let { qicsgpPassword(it.text) }.orEmpty()
                }
                val cmd = if (wantsAuth) "AT+QICSGP=${op.cid},${pdpToCtxType(op.pdp)},\"${op.apn}\",\"${op.username}\",\"$pass\",${authToCode(op.auth)}"
                          else "AT+QICSGP=${op.cid},${pdpToCtxType(op.pdp)},\"${op.apn}\",\"\",\"\",0"
                val r = at(cmd, sensitive = true)
                if (!r.ok) throw IOException("Penulisan autentikasi (AT+QICSGP) gagal: ${r.error}. Belum ada perubahan APN lain yang ditulis.")
                steps += "AT+QICSGP"
            }
            wantsAuth && !state.auth.cgauth -> throw IOException("Firmware tidak mendukung AT+QICSGP maupun AT+CGAUTH; autentikasi APN tidak dapat ditulis.")
        }
        atOrThrow("AT+CGDCONT=${op.cid},\"${pdpToAt(op.pdp)}\",\"${op.apn}\"", "Penulisan APN (AT+CGDCONT)"); steps += "AT+CGDCONT"
        if (!state.auth.qicsgp && state.auth.cgauth && (wantsAuth || hadAuth)) {
            val cmd = if (wantsAuth) "AT+CGAUTH=${op.cid},${authToCode(op.auth)},\"${op.username}\",\"${op.password}\"" else "AT+CGAUTH=${op.cid},0"
            val r = at(cmd, sensitive = true)
            if (!r.ok) throw IOException("APN tersimpan, tetapi autentikasi (AT+CGAUTH) gagal: ${r.error}. Baca ulang sebelum mencoba lagi.")
            steps += "AT+CGAUTH"
        }
        if (op.reattach) {
            atOrThrow("AT+COPS=2", "Detach jaringan (AT+COPS=2)"); steps += "AT+COPS=2"
            val again = at("AT+COPS=0")
            if (!again.ok) throw IOException("APN tersimpan dan modem sudah detach, tetapi AT+COPS=0 gagal (${again.error}). Jalankan AT+COPS=0 dari AT Tools untuk registrasi ulang.")
            steps += "AT+COPS=0"
        }
        return steps.joinToString(" → ")
    }

    // ----------------------------------------------------------------------- Network mode

    suspend fun readMode(): ModeState {
        val ident = identifySafely()
        val ratOrder = try {
            val (pr, pj) = apiGet("cellular/network_priority.sh")
            val order = pj?.takeIf { pr.code in 200..299 && it.optBoolean("success", false) }?.optString("order")?.takeIf { it.isNotBlank() }
            record("GET cellular/network_priority.sh", pr.code, if (order != null) "KOMPATIBEL (info)" else "TIDAK TERSEDIA", order?.let { "rat_acq_order=$it" } ?: apiFailure(pr, pj), pr.body)
            order
        } catch (e: AuthRequired) { throw e } catch (_: Exception) { null }

        val (r, json) = apiGet("cellular/settings.sh")
        val v = if (r.code in 200..299) parseModeApi(json) else null
        val fiveGModel = ident?.model?.let { isFiveGModel(it) }
        if (v != null && v.nr5gDisable != null) {
            hints += "Network mode: field ${if (json?.optJSONObject("settings") != null) "settings." else ""}${v.nr5gKey}"
            record("GET cellular/settings.sh", r.code, "KOMPATIBEL", "mode_pref=${v.modePref}, ${v.nr5gKey}=${v.nr5gDisable}", r.body)
            val rats = normalizeModePref(v.modePref)
            modeSource = DataSource.API
            return ModeState(DataSource.API, v.modePref, rats, v.nr5gDisable, true, null, fiveGModel ?: if ("NR5G" in rats) true else null, ratOrder, "Endpoint settings.sh kompatibel")
        }
        val reason = if (v != null) "settings.sh tidak menyertakan nr5g_disable_mode/nr5g_mode" else apiFailure(r, json)
        record("GET cellular/settings.sh", r.code, "TIDAK KOMPATIBEL → AT fallback", reason, r.body)

        val m = at("AT+QNWPREFCFG=\"mode_pref\"")
        val pref = if (m.ok) parseQnwprefcfg(m.text, "mode_pref") else null
        if (pref == null) throw IOException("API network mode gagal ($reason) dan AT+QNWPREFCFG=\"mode_pref\" tidak terbaca (${m.error ?: "format tidak dikenal"}).")
        val n = at("AT+QNWPREFCFG=\"nr5g_disable_mode\"")
        val nr = if (n.ok) parseQnwprefcfg(n.text, "nr5g_disable_mode")?.trim()?.toIntOrNull()?.takeIf { it in 0..2 } else null
        val caps = at("AT+QNWPREFCFG=?").let { if (it.ok) parseSupportedModePrefs(it.text) else null }
        val rats = normalizeModePref(pref)
        val fiveG = fiveGModel ?: caps?.contains("NR5G") ?: if ("NR5G" in rats || nr != null) true else null
        modeSource = DataSource.AT
        return ModeState(DataSource.AT, pref, rats, nr, nr != null, caps, fiveG, ratOrder, "settings.sh: $reason")
    }

    /** Terapkan mode setelah verifikasi kemampuan. Mengembalikan nilai mode_pref yang ditulis. */
    suspend fun writeMode(state: ModeState, choice: ModeChoice): String {
        state.blockedReason(choice)?.let { throw IOException(it) }
        val pref = modePrefToWrite(choice, state.rats)
        when (state.source) {
            DataSource.API -> {
                val body = JSONObject().put("mode_pref", pref)
                // Backend QManager-RM520N membaca "nr5g_mode"; dokumentasi API memakai "nr5g_disable_mode". Nilai sama dikirim di kedua key.
                if (state.nr5gSupported) body.put("nr5g_mode", choice.disable).put("nr5g_disable_mode", choice.disable)
                api.request("cellular/settings.sh", body)
            }
            DataSource.AT -> {
                if (state.nr5gSupported) atOrThrow("AT+QNWPREFCFG=\"nr5g_disable_mode\",${choice.disable}", "Penulisan nr5g_disable_mode")
                atOrThrow("AT+QNWPREFCFG=\"mode_pref\",$pref", "Penulisan mode_pref")
            }
        }
        return pref
    }

    // ----------------------------------------------------------------------- Diagnostics

    /** Jalankan pemeriksaan read-only untuk semua endpoint & AT terkait. */
    suspend fun runDiagnostics() {
        identify(force = true)
        for (path in listOf("at_cmd/fetch_data.sh", "device/about.sh")) {
            val (r, json) = apiGet(path)
            record("GET $path", r.code, if (r.code in 200..299 && json != null && json.optBoolean("success", true)) "OK" else "GAGAL", apiFailure(r, json).takeIf { r.code !in 200..299 || json == null || !json.optBoolean("success", true) }.orEmpty(), r.body)
        }
        try { readApn() } catch (e: AuthRequired) { throw e } catch (e: Exception) { record("APN (gabungan)", null, "GAGAL", e.message.orEmpty(), "") }
        try { readMode() } catch (e: AuthRequired) { throw e } catch (e: Exception) { record("Network mode (gabungan)", null, "GAGAL", e.message.orEmpty(), "") }
        listOf("AT+CGDCONT?", "AT+CGACT?", "AT+QICSGP=?", "AT+CGAUTH=?", "AT+QNWPREFCFG=\"mode_pref\"", "AT+QNWPREFCFG=\"nr5g_disable_mode\"").forEach { cmd ->
            if (checks["AT $cmd"] == null) at(cmd)
        }
    }

    fun report(appVersion: String, connection: String): String = buildString {
        val id = identity
        appendLine("QManager Mobile — Diagnostic Report")
        appendLine("Generated : ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())}")
        appendLine("App       : $appVersion")
        appendLine("Connection: ${redact(connection)}")
        appendLine("Model     : ${id?.model ?: "Unknown"}")
        appendLine("Revision  : ${id?.revision ?: "Unknown"}")
        appendLine("Firmware  : ${id?.firmware ?: "Unknown"}")
        appendLine("Backend   : versi tidak diekspos oleh endpoint read-only (cek OTA tidak dijalankan)")
        hints.toList().forEach { appendLine("            $it") }
        appendLine("APN source: ${apnSource?.label ?: "belum dibaca"}")
        appendLine("Mode src  : ${modeSource?.label ?: "belum dibaca"}")
        appendLine()
        appendLine("== Endpoint compatibility ==")
        val list = checks()
        list.forEach { c -> appendLine("- ${c.key} | HTTP ${c.httpStatus ?: "-"} | ${c.verdict}${if (c.detail.isNotBlank()) " | " + redact(c.detail) else ""}") }
        appendLine()
        appendLine("== Raw responses (disensor, maks 1500 karakter) ==")
        list.filter { it.raw.isNotBlank() }.forEach { c -> appendLine("### ${c.key} (HTTP ${c.httpStatus ?: "-"})"); appendLine(c.raw.trim()); appendLine() }
        appendLine("Tidak menyertakan: password, cookie sesi, IMEI, IMSI, ICCID, nomor telepon, isi SMS.")
    }
}
