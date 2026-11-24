package id.qmobile.app

import org.json.JSONArray
import org.json.JSONObject

/*
 * Parser murni (tanpa Android API) untuk respons QManager dan AT command.
 * Semua parser toleran: field kosong, quoted CSV, kapitalisasi, angka berbentuk string,
 * dan jumlah PDP context yang berbeda antar firmware (RG501Q-EU, RG520N-EU, RM520N-GL).
 */

enum class DataSource(val label: String) { API("QManager API"), AT("AT Command Fallback") }

// ---------------------------------------------------------------------------------------------
// AT helpers
// ---------------------------------------------------------------------------------------------

data class AtResult(val command: String, val ok: Boolean, val text: String, val error: String?)

private val atErrorLine = Regex("^(ERROR|\\+CME ERROR.*|\\+CMS ERROR.*|COMMAND NOT SUPPORT.*)$", RegexOption.IGNORE_CASE)

/** Ubah respons JSON send_command.sh (atau varian fork) menjadi AtResult. */
fun atResultFromJson(command: String, json: JSONObject?): AtResult {
    if (json == null) return AtResult(command, false, "", "Respons bukan JSON")
    val text = listOf("response", "output", "result", "data").firstNotNullOfOrNull { k -> json.opt(k)?.takeIf { it is String } as String? } ?: ""
    val success = !(json.has("success") && !json.optBoolean("success", true))
    val errLine = text.lineSequence().map { it.trim() }.firstOrNull { atErrorLine.matches(it) }
    val error = when {
        !success -> listOf(json.optString("error"), json.optString("detail")).filter { it.isNotBlank() }.joinToString(": ").ifBlank { "command_failed" }
        errLine != null -> errLine
        else -> null
    }
    return AtResult(command, error == null, text, error)
}

/** Pisahkan field AT bergaya CSV. Mendukung nilai dalam tanda kutip yang berisi koma, dan field kosong. */
fun splitAtCsv(raw: String): List<String> {
    val out = mutableListOf<String>()
    val cur = StringBuilder()
    var quoted = false
    var wasQuoted = false
    for (c in raw) {
        when {
            c == '"' -> { quoted = !quoted; wasQuoted = true }
            c == ',' && !quoted -> { out += if (wasQuoted) cur.toString() else cur.toString().trim(); cur.clear(); wasQuoted = false }
            quoted -> cur.append(c)
            c.isWhitespace() && (wasQuoted || cur.isEmpty()) -> Unit
            else -> cur.append(c)
        }
    }
    out += if (wasQuoted) cur.toString() else cur.toString().trim()
    return out
}

/** Ambil isi baris yang diawali prefix (mis. "+CGDCONT:"), tanpa prefix. */
fun atLines(text: String, prefix: String): List<String> = text.replace("\\r", "\r").replace("\\n", "\n").lineSequence()
    .map { it.trim() }.filter { it.startsWith(prefix, ignoreCase = true) }.map { it.substring(prefix.length).trim() }.toList()

private fun String.cleanInt(): Int? = trim().trim('"').toIntOrNull()

// ---------------------------------------------------------------------------------------------
// APN
// ---------------------------------------------------------------------------------------------

data class PdpContext(val cid: Int, val pdpType: String?, val apn: String)

fun normalizePdp(raw: String?): String? = when (raw?.trim()?.trim('"')?.uppercase()) {
    null, "" -> null
    "IP", "IPV4" -> "ipv4"
    "IPV6" -> "ipv6"
    "IPV4V6", "IPV6V4" -> "ipv4v6"
    else -> raw.trim().trim('"').lowercase()
}
fun pdpToAt(pdp: String): String = when (pdp) { "ipv4" -> "IP"; "ipv6" -> "IPV6"; else -> "IPV4V6" }
fun pdpToCtxType(pdp: String): Int = when (pdp) { "ipv4" -> 1; "ipv6" -> 2; else -> 3 }
fun authFromCode(code: Int?): String? = when (code) { 0 -> "none"; 1 -> "pap"; 2 -> "chap"; 3 -> "pap/chap"; else -> null }
fun authToCode(auth: String): Int = when (auth) { "pap" -> 1; "chap" -> 2; else -> 0 }

/** AT+CGDCONT? → daftar PDP context. Contoh: +CGDCONT: 1,"IPV4V6","internet","0.0.0.0",0,0,0,0 */
fun parseCgdcont(text: String): List<PdpContext> = atLines(text, "+CGDCONT:").mapNotNull { line ->
    val f = splitAtCsv(line)
    val cid = f.getOrNull(0)?.cleanInt() ?: return@mapNotNull null
    PdpContext(cid, normalizePdp(f.getOrNull(1)), f.getOrNull(2).orEmpty())
}.distinctBy { it.cid }.sortedBy { it.cid }

/** AT+CGACT? → cid → aktif. Contoh: +CGACT: 1,1 */
fun parseCgact(text: String): Map<Int, Boolean> = atLines(text, "+CGACT:").mapNotNull { line ->
    val f = splitAtCsv(line)
    val cid = f.getOrNull(0)?.cleanInt() ?: return@mapNotNull null
    val st = f.getOrNull(1)?.cleanInt() ?: return@mapNotNull null
    cid to (st == 1)
}.toMap()

data class AuthInfo(val authType: String?, val username: String?, val hasPassword: Boolean?)

/** AT+QICSGP=<cid> → +QICSGP: <ctxtype>,"<apn>","<user>","<pass>",<auth>. Password TIDAK disimpan, hanya keberadaannya. */
fun parseQicsgp(text: String): AuthInfo? {
    val f = atLines(text, "+QICSGP:").firstOrNull()?.let { splitAtCsv(it) } ?: return null
    if (f.size < 5) return null
    return AuthInfo(authFromCode(f[4].cleanInt()), f[2], f[3].isNotEmpty())
}
/** Password tersimpan (dipakai hanya saat menulis ulang tanpa mengubah password; tidak pernah ditampilkan/dicatat). */
fun qicsgpPassword(text: String): String? = atLines(text, "+QICSGP:").firstOrNull()?.let { splitAtCsv(it) }?.getOrNull(3)

/** AT+CGAUTH? → +CGAUTH: <cid>,<auth>[,"<user>"] */
fun parseCgauth(text: String): Map<Int, AuthInfo> = atLines(text, "+CGAUTH:").mapNotNull { line ->
    val f = splitAtCsv(line)
    val cid = f.getOrNull(0)?.cleanInt() ?: return@mapNotNull null
    cid to AuthInfo(authFromCode(f.getOrNull(1)?.cleanInt()), f.getOrNull(2), null)
}.toMap()

/** AT+CGPADDR → cid → (ipv4, ipv6). Mendukung alamat dengan/tanpa kutip dan IPv6 bentuk desimal 16 oktet. */
fun parseCgpaddr(text: String): Map<Int, Pair<String?, String?>> = atLines(text, "+CGPADDR:").mapNotNull { line ->
    val f = splitAtCsv(line)
    val cid = f.getOrNull(0)?.cleanInt() ?: return@mapNotNull null
    val addrs = f.drop(1).map { it.trim() }.filter { it.isNotEmpty() && it != "0.0.0.0" && it.trim('0', '.', ':').isNotEmpty() }
    val v4 = addrs.firstOrNull { !it.contains(':') && it.split('.').size == 4 }
    val v6 = addrs.firstOrNull { it.contains(':') || it.split('.').size == 16 }
    cid to (v4 to v6)
}.toMap()

data class ApnProfile(
    val cid: Int,
    val apn: String?,            // null = tidak diketahui; "" = tidak didefinisikan
    val pdpType: String?,
    val active: Boolean?,
    val authType: String?,
    val username: String?,
    val hasPassword: Boolean?,
    val ipv4: String?,
    val ipv6: String?,
    val dns: String?,
    val name: String?,
    val apnType: String?,
    val status: String?,
) {
    val defined: Boolean get() = !apn.isNullOrEmpty() || pdpType != null
    /** IMS / Emergency tidak boleh diubah. CID 2/3 dikunci konservatif (slot operator IMS/SOS di Quectel). */
    val protected: Boolean get() {
        val a = apn.orEmpty().lowercase(); val t = apnType.orEmpty().lowercase()
        return cid in 2..3 || t in setOf("ims", "emergency", "sos") || a in setOf("ims", "sos", "emergency") || a.startsWith("ims.") || a.startsWith("sos.")
    }
    val protectionReason: String get() = when {
        apnType.orEmpty().lowercase() == "ims" || apn.orEmpty().lowercase().let { it == "ims" || it.startsWith("ims.") } -> "Profil IMS (VoLTE)"
        apnType.orEmpty().lowercase() in setOf("emergency", "sos") || apn.orEmpty().lowercase().let { it in setOf("sos", "emergency") || it.startsWith("sos.") } -> "Profil Emergency/SOS"
        else -> "CID $cid dicadangkan operator untuk IMS/SOS"
    }
}

data class AuthSupport(val qicsgp: Boolean, val cgauth: Boolean) { val any get() = qicsgp || cgauth }

private fun JSONObject.str(key: String): String? = if (!has(key) || isNull(key)) null else optString(key)
private fun JSONObject.intLike(key: String): Int? = when (val v = opt(key)) {
    is Number -> v.toInt(); is String -> v.trim().toIntOrNull(); else -> null
}
private fun JSONObject.boolLike(key: String): Boolean? = when (val v = opt(key)) {
    is Boolean -> v; is Number -> v.toInt() != 0
    is String -> when (v.trim().lowercase()) { "1", "true", "yes", "on" -> true; "0", "false", "no", "off" -> false; else -> null }
    else -> null
}

/**
 * Format API apn.sh "legacy 6-slot" (QManager-RM520N): {success, profiles:[{index, apn, pdp_type, auth_type, enabled, ...}]}.
 * Return null bila format tidak dikenali (mis. kontrak v2 QManager RM551E atau error parse_failed) → pakai AT fallback.
 */
fun parseApnApi(json: JSONObject?): List<ApnProfile>? {
    if (json == null || (json.has("success") && json.boolLike("success") == false)) return null
    val arr = json.optJSONArray("profiles") ?: return null
    val out = (0 until arr.length()).mapNotNull { i ->
        val p = arr.optJSONObject(i) ?: return@mapNotNull null
        val cid = p.intLike("index") ?: return@mapNotNull null
        if (cid !in 1..16) return@mapNotNull null
        fun blankToNull(s: String?) = s?.takeIf { it.isNotBlank() }
        ApnProfile(
            cid = cid,
            apn = p.str("apn") ?: "",
            pdpType = normalizePdp(p.str("pdp_type")),
            active = p.boolLike("enabled"),
            authType = blankToNull(p.str("auth_type"))?.lowercase(),
            username = p.str("username"),
            hasPassword = p.boolLike("has_password"),
            ipv4 = blankToNull(p.str("ipv4_address")),
            ipv6 = blankToNull(p.str("ipv6_address")),
            dns = blankToNull(p.str("dns1")),
            name = blankToNull(p.str("name")),
            apnType = blankToNull(p.str("apn_type")),
            status = blankToNull(p.str("connect_progress")),
        )
    }.distinctBy { it.cid }.sortedBy { it.cid }
    return out.takeIf { it.isNotEmpty() }
}

/** Gabungkan hasil AT menjadi profil APN. Info yang tidak tersedia tetap null (ditampilkan "Unknown"). */
fun buildAtProfiles(
    contexts: List<PdpContext>, activation: Map<Int, Boolean>?, auth: Map<Int, AuthInfo>, addresses: Map<Int, Pair<String?, String?>>?,
): List<ApnProfile> {
    val cids = (contexts.map { it.cid } + (activation?.keys ?: emptySet())).filter { it in 1..16 }.distinct().sorted()
    return cids.map { cid ->
        val ctx = contexts.firstOrNull { it.cid == cid }
        val active = activation?.get(cid) ?: if (activation != null) false else null
        val ip = addresses?.get(cid)
        ApnProfile(cid, ctx?.apn ?: "", ctx?.pdpType, active, auth[cid]?.authType, auth[cid]?.username, auth[cid]?.hasPassword,
            ip?.first, ip?.second, null, null, null,
            when { ip?.first != null || ip?.second != null -> "connected"; active == true -> "active"; active == false -> "inactive"; else -> null })
    }
}

// ---------------------------------------------------------------------------------------------
// Network mode
// ---------------------------------------------------------------------------------------------

private val ratAliases = mapOf("NR" to "NR5G", "5G" to "NR5G", "NR5G" to "NR5G", "4G" to "LTE", "LTE" to "LTE", "3G" to "WCDMA", "UMTS" to "WCDMA", "WCDMA" to "WCDMA", "AUTO" to "AUTO")

/** "lte:nr5g" / "NR5G:LTE" / "\"LTE\"" → daftar RAT terurut (urutan = prioritas). */
fun normalizeModePref(raw: String?): List<String> = raw.orEmpty().trim().trim('"').uppercase()
    .split(':', ',', ';', '/', '+', ' ').map { it.trim().trim('"') }.filter { it.isNotEmpty() }.map { ratAliases[it] ?: it }.distinct()

data class ModeValues(val modePref: String, val nr5gDisable: Int?, val nr5gKey: String?)

private fun numberLike(v: Any?): Int? = when (v) { is Number -> v.toInt(); is String -> v.trim().trim('"').toIntOrNull(); else -> null }

/**
 * Parser GET cellular/settings.sh. Variasi yang ditemukan:
 *  - QManager-RM520N (kode): {success, settings:{mode_pref:"LTE:NR5G", nr5g_mode:0, ...}}
 *  - Dokumentasi API:        {success, mode_pref:"AUTO", nr5g_disable_mode:0}
 *  - Fork lain:              {data:{...}} / nilai numerik string / kapitalisasi berbeda.
 */
fun parseModeApi(json: JSONObject?): ModeValues? {
    if (json == null) return null
    if (json.has("success") && json.opt("success").let { it == false || it == "false" || it == 0 }) return null
    val candidates = listOfNotNull(json, json.optJSONObject("settings"), json.optJSONObject("data"), json.optJSONObject("network_mode"), json.optJSONObject("cellular"))
    for (o in candidates) {
        val keys = o.keys().asSequence().toList()
        val prefKey = keys.firstOrNull { it.equals("mode_pref", true) || it.equals("modePref", true) || it.equals("mode_preference", true) } ?: continue
        val pref = o.opt(prefKey)?.toString()?.trim()?.takeIf { it.isNotEmpty() && it != "null" } ?: continue
        val nrKey = keys.firstOrNull { k -> listOf("nr5g_disable_mode", "nr5g_mode", "nr5gDisableMode", "nr5g_disable", "nr5gMode").any { k.equals(it, true) } }
        val nr = nrKey?.let { numberLike(o.opt(it)) }?.takeIf { it in 0..2 }
        return ModeValues(pref, nr, nrKey)
    }
    return null
}

/** AT+QNWPREFCFG="<key>" → nilai. Contoh: +QNWPREFCFG: "mode_pref",LTE:NR5G */
fun parseQnwprefcfg(text: String, key: String): String? = atLines(text, "+QNWPREFCFG:").firstNotNullOfOrNull { line ->
    val f = splitAtCsv(line)
    if (f.firstOrNull()?.trim()?.equals(key, true) == true) f.drop(1).joinToString(",").trim().takeIf { it.isNotEmpty() } else null
}

/** AT+QNWPREFCFG=? → nilai mode_pref yang didukung, bila firmware mencantumkannya. Null bila tidak bisa dipastikan. */
fun parseSupportedModePrefs(text: String): Set<String>? {
    val line = atLines(text, "+QNWPREFCFG:").firstOrNull { it.contains("mode_pref", true) } ?: return null
    val inside = Regex("\\(([^)]*)\\)").findAll(line).flatMap { it.groupValues[1].split(',', '/', '|') }
        .flatMap { normalizeModePref(it) }.filter { it in setOf("AUTO", "LTE", "NR5G", "WCDMA") }.toSet()
    return inside.takeIf { it.isNotEmpty() }
}

data class ModeChoice(val title: String, val description: String, val rats: Set<String>, val pref: String, val disable: Int) {
    val needsNr5gParam: Boolean get() = disable != 0
    val needsNr5g: Boolean get() = "NR5G" in rats || pref == "AUTO"
}
val modeChoices = listOf(
    ModeChoice("Auto", "Pemilihan RAT otomatis; SA dan NSA diizinkan.", setOf("AUTO"), "AUTO", 0),
    ModeChoice("LTE Only", "Hanya LTE / 4G.", setOf("LTE"), "LTE", 0),
    ModeChoice("LTE + 5G", "LTE dan NR; SA serta NSA diizinkan.", setOf("LTE", "NR5G"), "LTE:NR5G", 0),
    ModeChoice("5G NSA + LTE", "SA dinonaktifkan; LTE tetap diperlukan sebagai anchor dan dapat menjadi fallback.", setOf("LTE", "NR5G"), "LTE:NR5G", 1),
    ModeChoice("5G SA Only", "NR standalone, tanpa fallback LTE.", setOf("NR5G"), "NR5G", 2),
)

/** Cocokkan konfigurasi aktual dengan preset, tanpa memedulikan urutan prioritas RAT. Null = konfigurasi kustom. */
fun matchModeChoice(rats: List<String>, disable: Int?): ModeChoice? {
    val set = rats.toSet()
    return when {
        set == setOf("AUTO") && (disable == null || disable == 0) -> modeChoices[0]
        set == setOf("LTE") -> modeChoices[1]
        set == setOf("LTE", "NR5G") && (disable == null || disable == 0) -> modeChoices[2]
        set == setOf("LTE", "NR5G") && disable == 1 -> modeChoices[3]
        set == setOf("NR5G") && disable != 1 -> modeChoices[4]
        else -> null
    }
}

/** Nilai mode_pref yang ditulis: bila set RAT sama dengan yang aktif, pertahankan urutan prioritas yang ada. */
fun modePrefToWrite(choice: ModeChoice, currentRats: List<String>): String =
    if (choice.rats.size > 1 && currentRats.toSet() == choice.rats) currentRats.joinToString(":") else choice.pref

fun nr5gDisableLabel(v: Int?): String = when (v) { 0 -> "0 — SA + NSA diizinkan"; 1 -> "1 — SA dinonaktifkan (NSA)"; 2 -> "2 — NSA dinonaktifkan (SA)"; else -> "Unknown / tidak didukung" }

data class ApnState(val source: DataSource, val profiles: List<ApnProfile>, val auth: AuthSupport, val reason: String)

data class ModeState(
    val source: DataSource, val raw: String, val rats: List<String>, val nr5gDisable: Int?, val nr5gSupported: Boolean,
    val supportedPrefs: Set<String>?, val fiveG: Boolean?, val ratOrder: String?, val reason: String,
) {
    val actual: ModeChoice? get() = matchModeChoice(rats, nr5gDisable)
    /** Verifikasi kemampuan modem untuk pilihan mode. Null = diizinkan, selain itu alasan penolakan. */
    fun blockedReason(choice: ModeChoice): String? = when {
        choice.needsNr5gParam && !nr5gSupported -> "Butuh nr5g_disable_mode, tidak didukung firmware"
        choice.pref != "AUTO" && "NR5G" in choice.rats && supportedPrefs != null && "NR5G" !in supportedPrefs -> "NR5G tidak tercantum di kemampuan modem"
        choice.pref != "AUTO" && "NR5G" in choice.rats && fiveG == false -> "Modem tidak terdeteksi mendukung 5G"
        choice.pref == "LTE" && supportedPrefs != null && "LTE" !in supportedPrefs -> "LTE tidak tercantum di kemampuan modem"
        else -> null
    }
}

// ---------------------------------------------------------------------------------------------
// Modem identity
// ---------------------------------------------------------------------------------------------

/** ATI → model. Contoh: "Quectel\nRM520N-GL\nRevision: RM520NGLAAR03A07M4G\nOK" */
fun parseAtiModel(text: String): String? {
    val lines = text.replace("\\r", "\r").replace("\\n", "\n").lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
    lines.firstOrNull { Regex("^R[A-Z]\\d{3}[A-Z]?(-[A-Z0-9]{2,4})?$").matches(it) }?.let { return it }
    val i = lines.indexOfFirst { it.equals("Quectel", true) }
    return lines.getOrNull(i + 1)?.takeIf { i >= 0 && !it.startsWith("Revision", true) && it != "OK" }
}
fun parseAtiRevision(text: String): String? = text.replace("\\n", "\n").lineSequence().map { it.trim() }.firstOrNull { it.startsWith("Revision:", true) }?.substringAfter(':')?.trim()
/** AT+QGMR → firmware lengkap. */
fun parseQgmr(text: String): String? = text.replace("\\r", "\r").replace("\\n", "\n").lineSequence().map { it.trim() }
    .firstOrNull { it.isNotEmpty() && it != "OK" && !it.startsWith("AT", true) && !atErrorLine.matches(it) }?.removePrefix("+QGMR:")?.trim()
fun isFiveGModel(model: String?): Boolean = model?.uppercase()?.let { Regex("^R[GM]5\\d{2}").containsMatchIn(it) } == true

// ---------------------------------------------------------------------------------------------
// Redaction for diagnostics
// ---------------------------------------------------------------------------------------------

private val sensitiveKeys = listOf(
    "password", "passwd", "pass", "secret", "token", "cookie", "session", "qm_session", "imei", "imsi", "iccid", "msisdn", "meid",
    "phone", "phone_number", "number", "sender", "recipient", "message", "messages", "body", "content", "text", "sms", "username", "user",
)
private val keyStringValue = Regex("\"(${sensitiveKeys.joinToString("|")})\"\\s*:\\s*\"(?:[^\"\\\\]|\\\\.)*\"", RegexOption.IGNORE_CASE)
private val keyNumberValue = Regex("\"(${sensitiveKeys.joinToString("|")})\"\\s*:\\s*-?\\d+", RegexOption.IGNORE_CASE)
private val keyArrayValue = Regex("\"(messages|sms|iccid|imei|imsi)\"\\s*:\\s*\\[[^\\]]*\\]", RegexOption.IGNORE_CASE)
private val longIds = Regex("(?<![\\dA-Fa-f])\\d{14,20}[Ff]?(?![\\dA-Fa-f])")
private val phoneNumbers = Regex("\\+\\d{8,15}")
private val qicsgpLine = Regex("(\\+QICSGP:\\s*\\d+\\s*,\\s*\\\\?\"[^\"\\\\]*\\\\?\"\\s*,\\s*)\\\\?\"[^\"\\\\]*\\\\?\"(\\s*,\\s*)\\\\?\"[^\"\\\\]*\\\\?\"", RegexOption.IGNORE_CASE)
private val ipv4 = Regex("(?<![\\d.])(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})(?![\\d.])")
private val ipv6 = Regex("(?<![0-9A-Fa-f:])(?:[0-9A-Fa-f]{1,4}:){3,7}[0-9A-Fa-f]{1,4}(?![0-9A-Fa-f:])|(?<![0-9A-Fa-f:])(?:[0-9A-Fa-f]{1,4}:){1,6}:(?:[0-9A-Fa-f]{1,4}(?::[0-9A-Fa-f]{1,4}){0,5})?(?![0-9A-Fa-f:])")

private fun isPrivateV4(a: Int, b: Int): Boolean = a == 10 || a == 127 || a == 0 || (a == 192 && b == 168) || (a == 172 && b in 16..31) || (a == 169 && b == 254)

/** Sensor password, cookie, IMEI/IMSI/ICCID, nomor telepon, isi SMS, dan IP publik. */
fun redact(raw: String): String {
    var s = raw
    s = keyArrayValue.replace(s) { "\"${it.groupValues[1]}\":[\"[REDACTED]\"]" }
    s = keyStringValue.replace(s) { "\"${it.groupValues[1]}\":\"[REDACTED]\"" }
    s = keyNumberValue.replace(s) { "\"${it.groupValues[1]}\":\"[REDACTED]\"" }
    s = qicsgpLine.replace(s) { "${it.groupValues[1]}\"[REDACTED]\"${it.groupValues[2]}\"[REDACTED]\"" }
    s = longIds.replace(s, "[REDACTED-ID]")
    s = phoneNumbers.replace(s, "[REDACTED-PHONE]")
    s = ipv4.replace(s) { m ->
        val p = m.groupValues.drop(1).map { it.toInt() }
        if (p.any { it > 255 } || isPrivateV4(p[0], p[1])) m.value else "${p[0]}.x.x.x"
    }
    s = ipv6.replace(s) { m -> if (m.value.lowercase().startsWith("fe80") || m.value == "::") m.value else "[IPv6-REDACTED]" }
    return s
}

fun jsonOrNull(raw: String): JSONObject? = try { JSONObject(raw) } catch (_: Exception) { null }
@Suppress("unused") internal fun JSONArray.objectsList(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
