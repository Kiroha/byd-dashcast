package com.byd.dashcast.report

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The redactor, from both directions.
 *
 * Half of these cases assert that something is REMOVED. The other half assert that something is
 * KEPT, and those are the ones that matter most: the rules were measured against 160 real reports
 * precisely because the obvious patterns destroy the fields this project diagnoses with. If a
 * later rule starts eating display identifiers or window scales, these go red before anyone ships
 * a redactor that makes every cluster report useless.
 *
 * Every value below is invented. The shapes come from the corpus; the contents do not.
 */
class RedactorTest {

    private val SALT = "test-salt"

    private fun redact(s: String) = Redactor.redact(s, SALT)

    // ── what must disappear ──────────────────────────────────────────────────────────────────

    @Test
    fun `the vin property is removed but its key survives`() {
        val r = redact("[persist.sys.cloud.last_vin]: [LC0CE4CC1S0123456]")
        assertFalse("the value is gone", r.text.contains("LC0CE4CC1S0123456"))
        assertTrue("the key stays, so a reader knows what was there",
            r.text.contains("[persist.sys.cloud.last_vin]"))
        assertTrue(r.text.contains("<vin:"))
        assertEquals(1, r.counts["vin-prop"])
    }

    @Test
    fun `a bare vin key is removed too`() {
        val r = redact("BydAuto: getAutoVIN -> vin=LC0CE4CC1S0123456 ok")
        assertFalse(r.text.contains("LC0CE4CC1S0123456"))
        assertTrue(r.text.contains("vin=<vin:"))
    }

    @Test
    fun `a wifi network name is removed and the quotes are kept`() {
        val r = redact("""WifiService: state screen=on SSID: "Livebox-ABCD" rssi=-52""")
        assertFalse(r.text.contains("Livebox-ABCD"))
        assertTrue("quotes survive so the line still parses", r.text.contains("\"<ssid:"))
        assertTrue(r.text.contains("rssi=-52"))
    }

    @Test
    fun `hardware addresses are removed`() {
        val r = redact("wpa_supplicant: RX frame da=aa:bb:cc:dd:ee:11 sa=aa:bb:cc:dd:ee:22")
        assertFalse(r.text.contains("aa:bb:cc:dd:ee:11"))
        assertFalse(r.text.contains("aa:bb:cc:dd:ee:22"))
        assertEquals(2, r.counts["mac"])
    }

    @Test
    fun `a position fix is removed`() {
        val r = redact("01-01 12:00:00 I CameraDaemon: GpsMonitor: GPS: 48.858370,2.294481 alt=35")
        assertFalse(r.text.contains("48.858370"))
        assertFalse("the rest of the line goes too — we do not parse a format we do not own",
            r.text.contains("alt=35"))
        assertTrue(r.text.contains("GPS: <coords:"))
    }

    // ── what must survive: the fields this project diagnoses with ────────────────────────────

    @Test
    fun `a display unique id is never touched`() {
        // 829 occurrences across 132 of the 160 corpus reports. A free-floating 17-character VIN
        // rule eats every one of them, and with them every cluster diagnosis this project makes.
        val line = "DisplayDeviceInfo{\"Écran intégré, displayId 0\", uniqueId \"local:4619827259835644672\"}"
        val r = redact(line)
        assertEquals("byte for byte", line, r.text)
        assertEquals("no rule fired at all", 0, r.total)
    }

    @Test
    fun `window manager scales are never touched`() {
        // 2019 occurrences across 115 reports. A naive decimal-coordinate rule eats these and calls
        // it location data; in the corpus that is 92% of everything such a rule matches.
        val line = "mBounds=[0,0][1280,480], globalScale=1.000000, windowScale=(1.0,1.0)"
        assertEquals(line, redact(line).text)
    }

    @Test
    fun `words that merely contain v-i-n are left alone`() {
        val line = "widevine: level=L1 · removing task 42 · CarDrivingRestriction.apk"
        assertEquals(line, redact(line).text)
    }

    @Test
    fun `broadcast and all-zero addresses are kept`() {
        // They identify nobody, and seeing them in a frame is itself the diagnostic.
        val line = "RX frame da=ff:ff:ff:ff:ff:ff sa=00:00:00:00:00:00"
        assertEquals(line, redact(line).text)
        assertEquals(0, redact(line).total)
    }

    @Test
    fun `a physical display id survives`() {
        val line = "mPhysicalDisplayId=4619827259835644672 mState=ON"
        assertEquals(line, redact(line).text)
    }

    // ── the token contract ──────────────────────────────────────────────────────────────────

    @Test
    fun `the same device reads as the same device within one report`() {
        val r = redact("first aa:bb:cc:dd:ee:11 ... later aa:bb:cc:dd:ee:11 ... other aa:bb:cc:dd:ee:22")
        val tokens = Regex("<mac:([0-9a-f]{4})>").findAll(r.text).map { it.groupValues[1] }.toList()
        assertEquals(3, tokens.size)
        assertEquals("the same address gives the same label", tokens[0], tokens[1])
        assertNotEquals("a different address gives a different one", tokens[0], tokens[2])
    }

    @Test
    fun `the same device does not read as the same device across two reports`() {
        // This is the whole point of a per-report salt: correlation is useful inside one report and
        // is exactly what identifies someone across several.
        val a = Redactor.redact("aa:bb:cc:dd:ee:11", "salt-one").text
        val b = Redactor.redact("aa:bb:cc:dd:ee:11", "salt-two").text
        assertNotEquals(a, b)
    }

    // ── the report-level contract ───────────────────────────────────────────────────────────

    @Test
    fun `a clean report comes back unchanged and says so`() {
        val clean = "Device: DL5.1 trinket\nVersion: 1.8.28-beta build 618\nCluster launch OK"
        val r = redact(clean)
        assertEquals(clean, r.text)
        assertTrue(r.summary().contains("nothing matched"))
    }

    @Test
    fun `the summary names what was removed and how much`() {
        val r = redact("""SSID: "Home" and aa:bb:cc:dd:ee:11 and aa:bb:cc:dd:ee:22""")
        assertTrue(r.summary(), r.summary().contains("wifi-ssid=1"))
        assertTrue(r.summary(), r.summary().contains("mac=2"))
    }

    @Test
    fun `an empty report does not blow up`() {
        assertEquals("", redact("").text)
        assertEquals(0, redact("").total)
    }

    @Test
    fun `redaction is idempotent`() {
        // A second pass must not re-token the tokens: a report that is redacted twice by some
        // future double-wiring has to come out the same, not scrambled again.
        val once = redact("""SSID: "Home" mac aa:bb:cc:dd:ee:11 vin=LC0CE4CC1S0123456""").text
        assertEquals(once, Redactor.redact(once, SALT).text)
    }

    // ── the unquoted network name, and the two bugs the rule had ─────────────────────────────

    @Test
    fun `an unquoted network name is removed`() {
        // The sharpest string in the corpus: a possessive hotspot name is a first name plus a
        // phone model, and it belongs to a passenger who never saw the notice.
        val r = redact("WifiConfig SSID: Someone's iPhone, MAC: 1a:2b:3c:4d:5e:6f")
        assertFalse(r.text.contains("Someone's iPhone"))
        assertTrue("the comma-separated structure survives", r.text.contains(", MAC: "))
        assertTrue(r.text.contains("<ssid:"))
    }

    @Test
    fun `the separator is not swallowed`() {
        // The rule's first version let its whitespace matcher backtrack to zero, so the "value" it
        // captured was the space before the quote and every quoted SSID got a second, empty token.
        val r = redact("""SSID: "Home"""")
        assertEquals("exactly one network was seen", 1, r.counts["wifi-ssid"])
    }

    @Test
    fun `a token is not relabelled by the rule that wrote it`() {
        // <ssid:1f4a> contains the letters s-s-i-d followed by a colon, so the unquoted rule
        // matched inside its own output and produced a new label on every pass.
        val once = redact("SSID: HomeNet")
        val twice = Redactor.redact(once.text, SALT)
        assertEquals(once.text, twice.text)
        assertEquals(0, twice.total)
    }

    @Test
    fun `a bssid is an address not a network name`() {
        val r = redact("scan BSSID: 1a:2b:3c:4d:5e:6f level=-60")
        assertEquals("it belongs to the address rule", null, r.counts["wifi-ssid"])
        assertEquals(1, r.counts["mac"])
        assertTrue("the key itself stays readable", r.text.contains("BSSID: "))
    }

    // ── the space-separated forms, which shipped 39 names in INC-20260826-194829 ─────────────
    //
    // Both lines below are the VERBATIM shapes taken from that report, with invented network
    // names and addresses. The shape is the whole point: every SSID test above uses `SSID:`, a
    // form this ROM emits exactly zero times in a 10,097-line report, so the rules passed CI
    // while the real frames walked straight through them.

    @Test
    fun `a network name in a wpa_supplicant frame is removed`() {
        val r = redact(
            "08-26 19:48:24.516 15836 15836 D wpa_supplicant: wlan0: BSS: Add new id 530 " +
                "BSSID 1a:2b:3c:4d:5e:6f SSID 'Neighbour-1A2B' freq 2437 HESSID a1:b2:c3:d4:e5:f6"
        )
        assertFalse("the network name is what leaked", r.text.contains("Neighbour-1A2B"))
        assertTrue(r.text.contains("<ssid:"))
        assertEquals("one network on this line", 1, r.counts["wifi-ssid"])
        // The address rule already worked on this exact line; the name survived between two
        // redacted addresses, which is what made the gap so easy to miss.
        assertEquals("BSSID and HESSID are addresses", 2, r.counts["mac"])
        assertTrue("the frame stays readable", r.text.contains("BSS: Add new id 530"))
        assertTrue("the quotes are kept", r.text.contains("SSID '<ssid:"))
        assertTrue("the band survives", r.text.contains("freq 2437"))
    }

    @Test
    fun `a network name in a LOWI scan record is removed`() {
        val r = redact(
            "08-26 19:48:24.402 1101 1101 D LOWI-9.3.0.1.: [MEAS_INFO] RSSI: -71, CPL: 0, " +
                "AGE: 122,  SSID Neighbour-1A2B, TSFDelta: 0x1ac"
        )
        assertFalse(r.text.contains("Neighbour-1A2B"))
        assertEquals(1, r.counts["wifi-ssid"])
        assertTrue("the comma-separated structure survives", r.text.contains(", TSFDelta: 0x1ac"))
        assertTrue("the signal strength is diagnostic and stays", r.text.contains("RSSI: -71"))
    }

    @Test
    fun `the space-separated rules leave the neighbouring keys alone`() {
        // BSSID and HESSID end in the letters S-S-I-D followed by a space, which is exactly what
        // the new rules match on. The lookbehind is the only thing keeping them out.
        val r = redact("BSSID 1a:2b:3c:4d:5e:6f HESSID a1:b2:c3:d4:e5:f6")
        assertEquals("neither is a network name", null, r.counts["wifi-ssid"])
        assertEquals(2, r.counts["mac"])
        assertTrue(r.text.contains("BSSID "))
        assertTrue(r.text.contains("HESSID "))
    }

    @Test
    fun `an empty network name is left alone`() {
        // `SSID ''` appears in the corpus for hidden networks. It identifies nobody, and its
        // presence in the frame is itself the diagnostic.
        val r = redact("BSS: Add new id 12 SSID '' freq 2437")
        assertEquals(null, r.counts["wifi-ssid"])
        assertTrue(r.text.contains("SSID ''"))
    }

    @Test
    fun `a space-separated name is not relabelled on a second pass`() {
        val once = redact("SSID 'HomeNet' freq 2437")
        val twice = Redactor.redact(once.text, SALT)
        assertEquals(once.text, twice.text)
        assertEquals(0, twice.total)
    }

    // ── the classes a second, larger measurement pass found ──────────────────────────────────

    @Test
    fun `the vin cloud token is removed wherever it appears`() {
        // 1164 occurrences across 111 of 160 reports, 25 distinct cars. 1048 sat behind a `vin=`
        // and were already caught; 116 arrived through five other carriers and were not.
        val r = redact("AutoiotService: reporting byd0123456789abcdef to cloud")
        assertFalse(r.text.contains("byd0123456789abcdef"))
        assertTrue(r.text.contains("<vin:"))
    }

    @Test
    fun `a plain vin keeps its manufacturer prefix and loses the rest`() {
        val r = redact("BodyworkDevice: LC0CE4CC1S0123456")
        assertTrue("the WMI names the manufacturer, which is triage context",
            r.text.contains("LC0<vin:"))
        assertFalse(r.text.contains("LC0CE4CC1S0123456"))
    }

    @Test
    fun `an install path is not an activation blob`() {
        // The value class matches 4042 times across the corpus; ~2986 are install paths, and they
        // are how a triager answers the commonest question there is: which build is installed.
        val line = "/data/app/~~AbCdEfGhIjKlMnOpQrStUv==/com.byd.dashcast-WxYzAbCdEfGhIjKlMnOpQr==/base.apk"
        assertEquals(line, redact(line).text)
    }

    @Test
    fun `an activation blob behind its own tag is removed`() {
        val r = redact("Activation before:AbCdEfGhIjKlMnOpQrStUv== after:ok")
        assertFalse(r.text.contains("AbCdEfGhIjKlMnOpQrStUv=="))
        assertTrue(r.text.contains("<activation>"))
    }

    @Test
    fun `a vendor service name is not an email address`() {
        // The naive email pattern matches 1977 times with 98.7% false positives, mostly these.
        // Deleting them strips the ROM's own vendor-stack identity out of half the corpus.
        val line = "vendor.qti.bluetooth@1.0-ibs_handler started; android.hardware.graphics.composer@2.4-service"
        assertEquals(line, redact(line).text)
    }

    @Test
    fun `a real email address is removed`() {
        val r = redact("GoogleAuth: signing in someone.else@example.com ok")
        assertFalse(r.text.contains("someone.else@example.com"))
        assertTrue(r.text.contains("<email>"))
    }

    @Test
    fun `a google account name is removed`() {
        val r = redact("GmsAuthManagerSvc: getToken: account: someone@example.com scope=oauth")
        assertFalse(r.text.contains("someone@example.com"))
        assertTrue(r.text.contains("<account>"))
    }

    @Test
    fun `the loopback verdict is never touched`() {
        // 667 occurrences across 20 reports, and the single most reproduced diagnostic line in the
        // corpus: it is why journal-only reports exist at all. Any blanket IPv4 rule eats it, and
        // that is 72% of everything such a rule would match.
        val line = "failed to connect to localhost/127.0.0.1 (port 5555): ECONNREFUSED"
        assertEquals(line, redact(line).text)
    }

    // ── the reporter's handle, which does not stay in its header ─────────────────────────────

    @Test
    fun `the handle is kept in its header and removed everywhere else`() {
        // The finding: the same string comes back as the tester's Wi-Fi network name, 64 times
        // across 14 reports. Redacting only the header would have left the interesting half.
        val r = redact("Telegram: @somebody\nWifiService: SSID: \"somebody\" connected\n")
        assertTrue("the maintainer must still be able to answer the report",
            r.text.contains("Telegram: @somebody"))
        assertTrue("but not the same string used as a network name",
            r.text.contains("<reporter:"))
        assertEquals(1, r.counts["reporter"])
    }

    @Test
    fun `the cross-pass runs before the network name is replaced`() {
        // Order dependency: if the SSID rule got there first the occurrence would become a generic
        // <ssid:...> and the link between the tester and that network would vanish rather than be
        // recorded as the same token.
        val r = redact("Telegram: @somebody\nSSID: \"somebody\"\n")
        assertTrue(r.text.contains("<reporter:"))
        assertEquals("the SSID rule found nothing left to do", null, r.counts["wifi-ssid"])
    }

    @Test
    fun `a very short handle is left alone`() {
        // Below four characters the cross-pass starts eating ordinary words, and no nickname is
        // worth shredding prose for.
        val r = redact("Telegram: @ab\nthe cab was late and the lab was closed\n")
        assertEquals(null, r.counts["reporter"])
        assertTrue(r.text.contains("the cab was late"))
    }

    @Test
    fun `no header means no cross-pass`() {
        val line = "WifiService: SSID: \"somebody\" connected"
        assertEquals(null, redact(line).counts["reporter"])
    }

    // ─── framework position carriers (the AUD-004 gap) ──────────────────────────────────────

    /**
     * Every line below is copied verbatim out of a real report in the corpus, not invented. They
     * are the four shapes `Location[` / `Loc[` takes on these ROMs, and before GPS_FRAMEWORK every
     * one of them left the car with the position intact while the consent notice told the driver
     * positions were removed.
     */
    @Test
    fun `the framework location carrier is redacted in all four real shapes`() {
        val real = listOf(
            "06-22 08:03:45.218   657  1128 V GnssLocationProvider: incoming location Location[gps,655440,265461 hAcc=3.7900925 et=+20h4m5s141ms alt=185.0020751953125 vAcc=2.5 vel=0.0 sAcc=0.080622576]",
            "07-15 14:14:26.971   338  1346 V GnssLocationProvider: reportLoc Loc[1,335222,373752 hAcc=4 et=+1d1h15m3s48ms -23.19012451171875 3.79 318.4 vAcc=3 sAcc=2 bAcc=25]",
            "18:05:26.089  1222  1844 D LocationManagerService: incoming location: Location[,571343,836721 hAcc=4 et=+4m55s125ms alt=516.86]",
            "V GnssLocationProvider: reportLocation Loc[fused,655440,265461 hAcc=4]",
        )
        for (line in real) {
            val r = redact(line)
            assertEquals("one position removed from: $line", 1, r.counts["gps"])
            assertTrue("token missing in: ${r.text}", r.text.contains("<coords:"))
        }
    }

    /** The AOSP form, for a ROM that prints the pair the way the platform documents it. */
    @Test
    fun `the AOSP space-separated form is redacted too`() {
        val r = redact("D LocationManagerService: incoming location: Location[fused 48.858370,2.294481 hAcc=12.0]")
        assertEquals(1, r.counts["gps"])
        assertFalse(r.text.contains("48.858370"))
        assertFalse(r.text.contains("2.294481"))
    }

    /**
     * What must survive. The accuracy, altitude and satellite figures are the diagnostic content
     * and identify nobody; removing them would cost triage and protect no one.
     */
    @Test
    fun `only the coordinate pair goes, the diagnostics stay`() {
        val r = redact("Location[gps,655440,265461 hAcc=3.79 alt=185.002 vel=0.0 {Bundle[{satellites=27, maxCn0=41}]}]")
        assertFalse("the position must be gone", r.text.contains("655440,265461"))
        assertTrue("hAcc must stay", r.text.contains("hAcc=3.79"))
        assertTrue("altitude must stay", r.text.contains("alt=185.002"))
        assertTrue("satellite count must stay", r.text.contains("satellites=27"))
        assertTrue("the carrier must stay readable", r.text.contains("Location[gps,"))
    }

    /**
     * 235 lines in the corpus are `reportLocation [ hAcc=…` — the framework already omitted the
     * coordinates. There is nothing to remove, and matching them would make the summary line
     * claim positions were redacted when none were present.
     */
    @Test
    fun `a location line with no coordinates is not counted`() {
        val line = "07-17 17:09:23.987   590  1329 V GnssLocationProvider: reportLocation [ hAcc=4 et=+3h53m11s744ms =283.43585205078125 vAcc=3 sAcc=0 bAcc=???]"
        val r = redact(line)
        assertEquals("nothing to redact here", null, r.counts["gps"])
        assertEquals(line, r.text)
    }

    /** The same fix logged twice reads as the same place — the corpus has 164 repeats of one. */
    @Test
    fun `the same position yields the same token within one report`() {
        val r = redact("A Location[gps,655440,265461 hAcc=4]\nB Location[gps,655440,265461 hAcc=4]\n")
        val tokens = Regex("<coords:([^>]+)>").findAll(r.text).map { it.groupValues[1] }.toList()
        assertEquals(2, tokens.size)
        assertEquals("a stationary car must still read as one place", tokens[0], tokens[1])
    }

    /** And a different place must not collide with it. */
    @Test
    fun `a different position yields a different token`() {
        val r = redact("A Location[gps,655440,265461 hAcc=4]\nB Location[gps,335222,373752 hAcc=4]\n")
        val tokens = Regex("<coords:([^>]+)>").findAll(r.text).map { it.groupValues[1] }.toList()
        assertEquals(2, tokens.size)
        assertNotEquals(tokens[0], tokens[1])
    }

    /** The pre-existing third-party carrier must keep working — this rule was not replaced. */
    @Test
    fun `the GpsMonitor carrier still works alongside the framework one`() {
        val r = redact(
            "I CameraDaemon: GpsMonitor: GPS: 48.858370,2.294481 alt=35\n" +
            "V GnssLocationProvider: incoming location Location[gps,655440,265461 hAcc=4]\n"
        )
        assertEquals("both carriers counted under one name", 2, r.counts["gps"])
        assertFalse(r.text.contains("48.858370"))
        assertFalse(r.text.contains("655440,265461"))
    }

    /** Idempotence: a second pass must not tokenise the token. */
    @Test
    fun `redacting twice changes nothing the second time`() {
        val once = redact("Location[gps,655440,265461 hAcc=4]").text
        val twice = redact(once)
        assertEquals(null, twice.counts["gps"])
        assertEquals(once, twice.text)
    }

    /** The OEM's charge-point lookup posts the car's position as query parameters. Real line. */
    @Test
    fun `a position carried as named parameters is redacted`() {
        val r = redact("""E m_tag   : postPar:{"param":"language=zh_CN&lon=3.12092513&lat=50.6654332&dataType=charge","sourceType":0}""")
        assertEquals(2, r.counts["gps"])
        assertFalse(r.text.contains("3.12092513"))
        assertFalse(r.text.contains("50.6654332"))
        assertTrue("the keys stay so the carrier is still identifiable", r.text.contains("lon=<coords:"))
        assertTrue("the rest of the parameter string is untouched", r.text.contains("dataType=charge"))
    }

    /**
     * A zero position identifies nobody, and its presence is the diagnostic — the fix had not
     * arrived yet. Same reasoning as the all-zero MAC.
     */
    @Test
    fun `a zero coordinate is kept`() {
        val line = "AutoNavi: 拥堵length:0 toDist:-1 lon:0.0 0.0 roadSpeed:0"
        val r = redact(line)
        assertEquals(null, r.counts["gps"])
        assertEquals(line, r.text)
    }

    /** The window manager's decimal pairs are 92% of the corpus noise and must survive untouched. */
    @Test
    fun `window manager scale factors are not mistaken for a position`() {
        val line = "InputDispatcher: globalScale=1.000000, windowScale=(1.000000,1.000000), touchableRegion=[0,0][1920,720]"
        val r = redact(line)
        assertEquals(null, r.counts["gps"])
        assertEquals(line, r.text)
    }
}
