package id.qmobile.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AtCsvTest {
    @Test fun quotedFieldsWithCommasAndEmpties() {
        assertEquals(listOf("1", "IPV4V6", "a,b", "", "0"), splitAtCsv("1,\"IPV4V6\",\"a,b\",,0"))
        assertEquals(listOf("1", "", ""), splitAtCsv("1,\"\","))
        assertEquals(listOf("mode_pref", "LTE:NR5G"), splitAtCsv("\"mode_pref\" , LTE:NR5G"))
    }

    @Test fun sendCommandJsonVariants() {
        val ok = atResultFromJson("AT+CGACT?", JSONObject("""{"success":true,"response":"+CGACT: 1,1\r\nOK","command":"AT+CGACT?"}"""))
        assertTrue(ok.ok); assertEquals(mapOf(1 to true), parseCgact(ok.text))
        val failed = atResultFromJson("AT+CGAUTH=?", JSONObject("""{"success":false,"error":"command_failed","command":"AT+CGAUTH=?"}"""))
        assertFalse(failed.ok); assertEquals("command_failed", failed.error)
        val errorText = atResultFromJson("AT+CGAUTH=?", JSONObject("""{"success":true,"response":"ERROR"}"""))
        assertFalse(errorText.ok)
        val cme = atResultFromJson("AT+QICSGP=?", JSONObject("""{"output":"+CME ERROR: 4"}"""))
        assertFalse(cme.ok)
        val otherField = atResultFromJson("ATI", JSONObject("""{"result":"Quectel\nRM520N-GL\nOK"}"""))
        assertTrue(otherField.ok); assertEquals("RM520N-GL", parseAtiModel(otherField.text))
    }
}

class ApnParserTest {
    private val rm520nCgdcont = """
        +CGDCONT: 1,"IPV4V6","fast.t-mobile.com","0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0",0,0,0,0,0,0,0,,0,,,,,,
        +CGDCONT: 2,"IPV4V6","ims","0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0",0,0,0,0,0,0,0,,0,,,,,,
        +CGDCONT: 3,"IPV4V6","sos","0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0",0,0,0,1,0,0,0,,0,,,,,,

        OK
    """.trimIndent()
    private val rg501qCgdcont = "+CGDCONT: 1,\"IP\",\"internet\",\"0.0.0.0\",0,0,0,0\r\n+CGDCONT: 2,\"IPV4V6\",\"IMS\",\"0.0.0.0\",0,0,0,0\r\n+CGDCONT: 5,\"IPV6\",\"\",\"\",0,0\r\nOK"

    @Test fun cgdcontRm520n() {
        val c = parseCgdcont(rm520nCgdcont)
        assertEquals(listOf(1, 2, 3), c.map { it.cid })
        assertEquals("fast.t-mobile.com", c[0].apn); assertEquals("ipv4v6", c[0].pdpType)
    }

    @Test fun cgdcontRg501qVariantsAndEmptyApn() {
        val c = parseCgdcont(rg501qCgdcont)
        assertEquals(listOf(1, 2, 5), c.map { it.cid })
        assertEquals("ipv4", c[0].pdpType); assertEquals("", c[2].apn); assertEquals("ipv6", c[2].pdpType)
    }

    @Test fun cgdcontEscapedNewlinesAndShortLines() {
        val c = parseCgdcont("+CGDCONT: 1,\"IPV4V6\",\"internet\"\\r\\n+CGDCONT: 4\\r\\nOK")
        assertEquals(listOf(1, 4), c.map { it.cid }); assertEquals("", c[1].apn); assertNull(c[1].pdpType)
    }

    @Test fun cgactAndCgpaddr() {
        assertEquals(mapOf(1 to true, 2 to false, 3 to false), parseCgact("+CGACT: 1,1\n+CGACT: 2,0\n+CGACT: 3, 0\nOK"))
        val a = parseCgpaddr("+CGPADDR: 1,\"10.20.30.40\",\"2001:DB8::1\"\n+CGPADDR: 2,0.0.0.0\n+CGPADDR: 3,\"32.1.13.184.0.0.0.0.0.0.0.0.0.0.0.1\"")
        assertEquals("10.20.30.40", a[1]?.first); assertEquals("2001:DB8::1", a[1]?.second)
        assertNull(a[2]?.first); assertNotNull(a[3]?.second)
    }

    @Test fun qicsgpAndCgauth() {
        val q = parseQicsgp("+QICSGP: 1,\"internet\",\"user\",\"secret\",1\r\nOK")!!
        assertEquals("pap", q.authType); assertEquals("user", q.username); assertEquals(true, q.hasPassword)
        val none = parseQicsgp("+QICSGP: 3,\"\",\"\",\"\",0")!!
        assertEquals("none", none.authType); assertEquals(false, none.hasPassword)
        assertNull(parseQicsgp("+QICSGP: 1,\"x\""))
        assertEquals("secret", qicsgpPassword("+QICSGP: 1,\"internet\",\"user\",\"secret\",1"))
        val g = parseCgauth("+CGAUTH: 1,2,\"bob\"\n+CGAUTH: 2,0")
        assertEquals("chap", g[1]?.authType); assertEquals("none", g[2]?.authType)
    }

    @Test fun buildProfilesMarksUnknownAndProtected() {
        val profiles = buildAtProfiles(parseCgdcont(rg501qCgdcont), null, emptyMap(), null)
        val p1 = profiles.first { it.cid == 1 }
        assertNull(p1.active); assertNull(p1.authType); assertNull(p1.ipv4); assertFalse(p1.protected)
        assertTrue(profiles.first { it.cid == 2 }.protected) // IMS
        val withAct = buildAtProfiles(parseCgdcont(rm520nCgdcont), mapOf(1 to true), emptyMap(), mapOf(1 to ("10.0.0.2" to null)))
        assertEquals(true, withAct[0].active); assertEquals(false, withAct[1].active); assertEquals("connected", withAct[0].status)
        assertTrue(withAct[2].protected) // SOS
    }

    @Test fun protectedByApnNameOutsideCid2And3() {
        val ims = ApnProfile(5, "IMS", "ipv4v6", true, null, null, null, null, null, null, null, null, null)
        val sos = ApnProfile(6, "sos.operator", "ipv4v6", true, null, null, null, null, null, null, null, null, null)
        val data = ApnProfile(4, "internet", "ipv4", true, null, null, null, null, null, null, null, null, null)
        assertTrue(ims.protected); assertTrue(sos.protected); assertFalse(data.protected)
    }

    @Test fun apiLegacyFormatParses() {
        val json = JSONObject("""{"success":true,"max_profiles":6,"data_source":"at","profiles":[
            {"index":1,"name":"","apn":"internet","pdp_type":"ipv4v6","auth_type":"none","username":"","has_password":false,"enabled":true,"apn_type":"","connect_progress":"connected","ipv4_address":"10.1.1.1","dns1":""},
            {"index":"2","apn":"ims","pdp_type":"IPV4V6","enabled":"0","apn_type":"ims"},
            {"index":4,"apn":"","pdp_type":"","enabled":false}]}""")
        val p = parseApnApi(json)!!
        assertEquals(listOf(1, 2, 4), p.map { it.cid })
        assertEquals("internet", p[0].apn); assertEquals(true, p[0].active); assertNull(p[0].dns)
        assertEquals(false, p[1].active); assertTrue(p[1].protected)
        assertFalse(p[2].defined)
    }

    @Test fun apiErrorAndV2FormatFallBack() {
        assertNull(parseApnApi(JSONObject("""{"success":false,"error":"parse_failed","detail":"Could not assemble WAN profile list"}""")))
        assertNull(parseApnApi(JSONObject("""{"success":true,"max_profiles":5,"active_profile":1,"profiles":[{"id":1,"cid":1,"apn":"x"}],"cids":[{"cid":1,"apn":"x"}]}""")))
        assertNull(parseApnApi(null))
    }
}

class ModeParserTest {
    @Test fun rm520nSettingsNested() {
        val v = parseModeApi(JSONObject("""{"success":true,"settings":{"sim_slot":1,"cfun":1,"mode_pref":"LTE:NR5G","nr5g_mode":0,"roam_pref":255},"ambr":{"lte":[],"nr5g":[]}}"""))!!
        assertEquals("LTE:NR5G", v.modePref); assertEquals(0, v.nr5gDisable); assertEquals("nr5g_mode", v.nr5gKey)
    }

    @Test fun documentedTopLevelWithStringNumber() {
        val v = parseModeApi(JSONObject("""{"success":true,"mode_pref":"AUTO","nr5g_disable_mode":"1"}"""))!!
        assertEquals("AUTO", v.modePref); assertEquals(1, v.nr5gDisable)
    }

    @Test fun capitalizationAndDataWrapper() {
        val v = parseModeApi(JSONObject("""{"data":{"Mode_Pref":"nr5g:lte","NR5G_DISABLE_MODE":" 2 "}}"""))!!
        assertEquals(listOf("NR5G", "LTE"), normalizeModePref(v.modePref)); assertEquals(2, v.nr5gDisable)
    }

    @Test fun missingNr5gAndFailures() {
        assertNull(parseModeApi(JSONObject("""{"success":true,"mode_pref":"LTE"}"""))!!.nr5gDisable)
        assertNull(parseModeApi(JSONObject("""{"success":true,"mode_pref":"LTE","nr5g_mode":7}"""))!!.nr5gDisable)
        assertNull(parseModeApi(JSONObject("""{"success":false,"error":"read_failed"}""")))
        assertNull(parseModeApi(JSONObject("""{"success":true,"settings":{"cfun":1}}""")))
    }

    @Test fun qnwprefcfgAt() {
        assertEquals("NR5G:LTE", parseQnwprefcfg("+QNWPREFCFG: \"mode_pref\",NR5G:LTE\r\n\r\nOK", "mode_pref"))
        assertEquals("1", parseQnwprefcfg("+QNWPREFCFG: \"nr5g_disable_mode\",1\nOK", "nr5g_disable_mode"))
        assertNull(parseQnwprefcfg("ERROR", "nr5g_disable_mode"))
        assertNull(parseQnwprefcfg("+QNWPREFCFG: \"mode_pref\",AUTO", "nr5g_disable_mode"))
        assertEquals(setOf("AUTO", "WCDMA", "LTE", "NR5G"), parseSupportedModePrefs("+QNWPREFCFG: \"mode_pref\",(AUTO,WCDMA,LTE,NR5G)\n+QNWPREFCFG: \"nr5g_disable_mode\",(0-2)"))
        assertNull(parseSupportedModePrefs("OK"))
    }

    @Test fun orderVariantsMatchSamePreset() {
        assertEquals("LTE + 5G", matchModeChoice(normalizeModePref("LTE:NR5G"), 0)?.title)
        assertEquals("LTE + 5G", matchModeChoice(normalizeModePref("NR5G:LTE"), 0)?.title)
        assertEquals("5G NSA + LTE", matchModeChoice(normalizeModePref("nr5g:lte"), 1)?.title)
        assertEquals("5G SA Only", matchModeChoice(normalizeModePref("NR5G"), 2)?.title)
        assertEquals("Auto", matchModeChoice(normalizeModePref("\"AUTO\""), null)?.title)
        assertEquals("LTE Only", matchModeChoice(normalizeModePref("lte"), 1)?.title)
        assertNull(matchModeChoice(normalizeModePref("LTE:WCDMA"), 0))
        assertNull(matchModeChoice(normalizeModePref("LTE:NR5G"), 2))
    }

    @Test fun writePreservesPriorityOrder() {
        val lteNr = modeChoices.first { it.title == "LTE + 5G" }
        val nsa = modeChoices.first { it.title == "5G NSA + LTE" }
        assertEquals("NR5G:LTE", modePrefToWrite(lteNr, listOf("NR5G", "LTE")))
        assertEquals("NR5G:LTE", modePrefToWrite(nsa, listOf("NR5G", "LTE")))
        assertEquals("LTE:NR5G", modePrefToWrite(lteNr, listOf("AUTO")))
        assertEquals("NR5G", modePrefToWrite(modeChoices.first { it.title == "5G SA Only" }, listOf("NR5G", "LTE")))
    }

    @Test fun capabilityGate() {
        val noNr = ModeState(DataSource.AT, "LTE:NR5G", listOf("LTE", "NR5G"), null, false, null, true, null, "")
        assertNotNull(noNr.blockedReason(modeChoices.first { it.title == "5G NSA + LTE" }))
        assertNotNull(noNr.blockedReason(modeChoices.first { it.title == "5G SA Only" }))
        assertNull(noNr.blockedReason(modeChoices.first { it.title == "LTE + 5G" }))
        val lteOnlyModem = ModeState(DataSource.AT, "LTE", listOf("LTE"), 0, true, setOf("AUTO", "LTE", "WCDMA"), false, null, "")
        assertNotNull(lteOnlyModem.blockedReason(modeChoices.first { it.title == "LTE + 5G" }))
        assertNull(lteOnlyModem.blockedReason(modeChoices.first { it.title == "Auto" }))
    }
}

class IdentityAndRedactionTest {
    @Test fun atiAndQgmr() {
        val ati = "ATI\r\nQuectel\r\nRG501Q-EU\r\nRevision: RG501QEUAAR12A11M4G\r\n\r\nOK"
        assertEquals("RG501Q-EU", parseAtiModel(ati)); assertEquals("RG501QEUAAR12A11M4G", parseAtiRevision(ati))
        assertEquals("RG520N-EU", parseAtiModel("Quectel\nRG520N-EU\nRevision: RG520NEUDCR01A02M4G\nOK"))
        assertEquals("RM520NGLAAR03A07M4G_01.200.01.200", parseQgmr("RM520NGLAAR03A07M4G_01.200.01.200\r\n\r\nOK"))
        assertTrue(isFiveGModel("RM520N-GL")); assertTrue(isFiveGModel("RG501Q-EU")); assertFalse(isFiveGModel("EC25-E"))
    }

    @Test fun redactsSecretsAndIdentifiers() {
        val raw = """{"password":"hunter2","imei":"861234567890123","iccid":"8962101234567890123F","imsi":510101234567890,"phone":"+628123456789","messages":[{"text":"hi"}],"wan_ipv4":"36.80.1.2","lan":"192.168.225.1","cookie":"qm_session=abc"}"""
        val r = redact(raw)
        listOf("hunter2", "861234567890123", "8962101234567890123F", "510101234567890", "+628123456789", "\"hi\"", "36.80.1.2", "qm_session=abc").forEach { assertFalse("leak: $it", r.contains(it)) }
        assertTrue(r.contains("192.168.225.1"))
        val q = redact("+QICSGP: 1,\"internet\",\"user\",\"secret\",1")
        assertFalse(q.contains("secret")); assertFalse(q.contains("\"user\"")); assertTrue(q.contains("internet"))
        val esc = redact("{\"response\":\"+QICSGP: 1,\\\"internet\\\",\\\"bob\\\",\\\"pw123\\\",2\"}")
        assertFalse(esc.contains("pw123")); assertFalse(esc.contains("bob"))
        assertFalse(redact("2001:4860:4860::8888 and fe80::1").contains("4860"))
        assertTrue(redact("time 23:05:21").contains("23:05:21"))
    }
}
