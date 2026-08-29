package com.byd.dashcast.report

import java.security.MessageDigest

/**
 * Removes the identifiers a diagnostic report does not need from the text before it leaves the car.
 *
 * ## Every rule here was measured, none was guessed
 *
 * The rules below were built against a corpus of 160 real reports (116 MB) from real cars, and the
 * measurement overturned the obvious design twice. Both traps are worth stating, because anyone
 * adding a rule later will be tempted by exactly the same shortcuts:
 *
 *  - **A free-floating 17-character VIN match is catastrophic.** In the corpus it fires 1370 times
 *    and not once on a VIN. It fires on `uniqueId=local:<digits>` — the display identifier, present
 *    829 times across 132 of the 160 reports, and the single field every cluster diagnosis in this
 *    project depends on. The VIN is reached by its key instead ([VIN_PROP], [VIN_KEY]), which is
 *    where it actually lives: `persist.sys.cloud.last_vin` in 85 reports, a bare `vin=` in 73.
 *  - **A free-floating decimal coordinate pair is 92% noise.** 593 of its 646 corpus hits are
 *    `globalScale=…, windowScale=(x,y)` from the window manager. Positions are therefore reached
 *    through their carrier, never their shape: [GPS] for the third-party `GpsMonitor` tag,
 *    [GPS_FRAMEWORK] for the platform's own `Location[…]` / `Loc[…]`, [GPS_KEYED] for the
 *    `lat=`/`lon=` parameters the OEM services post. The first version of this
 *    file had only [GPS] and, measured later across 178 reports, that covered 16 positions while
 *    218 in 19 reports left the car untouched — the anchor was right, the inventory of anchors
 *    was not.
 *
 * The lesson generalises: anchor on the key, never on the shape, unless the shape is unambiguous.
 * [MAC] is the one exception — a six-group colon-separated hex address has no other meaning in this
 * corpus, and its 369 hits are all real.
 *
 * ## What replaces a value
 *
 * Not a fixed marker. Each value becomes a short token derived from it, so two lines about the same
 * device still read as the same device inside one report — which is most of what the value was
 * doing for triage. The salt is per-report and random, so the same phone produces a different token
 * in the next report: correlation survives where it helps, and dies where it identifies.
 *
 * ## What this does not do
 *
 * It does not make a report anonymous, and the consent notice must not start claiming that. Free
 * text the user typed, package names, and whatever another application chose to log all remain. It
 * removes the identifiers that were arriving without anyone intending to collect them.
 */
object Redactor {

    /**
     * True when a value has already been through a pass.
     *
     * Without this the SSID rule re-tokens its own output: `SSID: "<ssid:779b>"` still matches
     * `SSID: "..."`, so a second pass produces a different label for the same network and the two
     * reports stop agreeing. Found by the idempotence test, not by reading the regex.
     */
    private fun isToken(v: String): Boolean =
        v.length > 2 && v.startsWith("<") && v.endsWith(">") && v.contains(':')

    /** A single substitution, kept separate so one bad pattern cannot take the report down. */
    private class Rule(
        val name: String,
        val regex: Regex,
        /** Given the match, returns the replacement. [token] hashes a value into a short label. */
        val replace: (MatchResult, (String) -> String) -> String,
    )

    /**
     * `[persist.sys.cloud.last_vin]: [<value>]` — the getprop sweep. One per report that has it,
     * which is 85 of the 160 in the corpus.
     */
    private val VIN_PROP = Rule(
        "vin-prop",
        Regex("""(\[persist\.sys\.cloud\.[a-z_]*vin[a-z_]*\]\s*:\s*\[)([^\]\n]{4,})(\])""",
            RegexOption.IGNORE_CASE),
    ) { m, tok ->
        if (isToken(m.groupValues[2])) m.value
        else m.groupValues[1] + "<vin:" + tok(m.groupValues[2]) + ">" + m.groupValues[3]
    }

    /**
     * A bare `vin=` / `vin: ` key, 1051 hits across 73 reports.
     *
     * The leading word boundary is what keeps `widevine` and `removing` — which both contain the
     * three letters — out of it: neither has a boundary before the `v`.
     */
    private val VIN_KEY = Rule(
        "vin-key",
        Regex("""\b(vin)(\s*[:=]\s*\[?)([A-Za-z0-9]{6,25})""", RegexOption.IGNORE_CASE),
    ) { m, tok ->
        if (isToken(m.groupValues[3])) m.value
        else m.groupValues[1] + m.groupValues[2] + "<vin:" + tok(m.groupValues[3]) + ">"
    }

    /**
     * `SSID: "<name>"` — 258 hits across 93 reports, so a majority of them name a Wi-Fi network.
     *
     * These are not only home networks: the corpus shows phone hotspot names, which are model plus
     * owner name, belonging to passengers rather than to the person who accepted the notice.
     */
    private val SSID = Rule(
        "wifi-ssid",
        Regex("""(SSID\s*[:=]\s*")([^"\n]{1,32})(")""", RegexOption.IGNORE_CASE),
    ) { m, tok ->
        if (isToken(m.groupValues[2])) m.value
        else m.groupValues[1] + "<ssid:" + tok(m.groupValues[2]) + ">" + m.groupValues[3]
    }

    /**
     * The same field without quotes — 30 of the corpus's 73 true `SSID:` occurrences.
     *
     * Most of those 30 are addresses the ROM already partly masked, and losing them costs nothing.
     * A handful are real network names in possessive form, which is to say someone's first name
     * followed by their phone's model. Those are the single most identifying strings the corpus
     * holds, and the quoted rule walked straight past them.
     *
     * Stops at a comma because the surrounding line is `SSID: <name>, MAC: <addr>` — the comma is
     * the field separator, and eating past it would take the structure the reader needs. The
     * negative lookahead on the quote keeps this rule off what [SSID] already handled, and the
     * lookbehind keeps it off `BSSID`, which is an address and belongs to [MAC].
     */
    private val SSID_BARE = Rule(
        "wifi-ssid",
        // Three guards, each for a failure this rule actually had. `<` in the lookbehind: without
        // it the rule matched the `ssid:` inside its own `<ssid:1f4a>` token and relabelled it on
        // every pass. `[^\s,"<]` on the first character: without it, `\s*` backtracked to zero and
        // the "value" captured was the separating space. `[A-Za-z]` in the lookbehind keeps it off
        // BSSID, which is an address and belongs to [MAC].
        Regex("""(?<![A-Za-z<])(SSID\s*[:=]\s*)(?!")([^\s,"<][^,"\n]{0,31})""",
            RegexOption.IGNORE_CASE),
    ) { m, tok ->
        if (isToken(m.groupValues[2].trim())) m.value
        else m.groupValues[1] + "<ssid:" + tok(m.groupValues[2].trim()) + ">"
    }

    /**
     * `SSID '<name>'` — what this ROM's `wpa_supplicant` actually prints, with a SPACE where both
     * rules above require a `:` or an `=`.
     *
     * INC-20260826-194829 shipped 39 neighbourhood network names to a Telegram group because of
     * that one character. Its own line is the proof that the gap was never a deliberate limit:
     *
     *     BSS: Add new id 530 BSSID 20:66:cf:**:**:00 SSID 'Freebox-3C9F3C' freq 2437 HESSID <mac:267c>
     *
     * [MAC] redacted the BSSID and the HESSID on that same line, and the name sitting between them
     * survived. Nothing failed in CI because every SSID test was written against the `SSID:` form,
     * which this ROM never emits — 0 occurrences in a 10,097-line report.
     *
     * An empty `SSID ''` is deliberately left alone: `{1,32}` cannot match it, a nameless network
     * identifies no one, and its presence in the frame is itself the diagnostic.
     */
    private val SSID_SPACED_QUOTED = Rule(
        "wifi-ssid",
        Regex("""(?<![A-Za-z<])(SSID[ \t]+')([^'\n]{1,32})(')""", RegexOption.IGNORE_CASE),
    ) { m, tok ->
        if (isToken(m.groupValues[2])) m.value
        else m.groupValues[1] + "<ssid:" + tok(m.groupValues[2]) + ">" + m.groupValues[3]
    }

    /**
     * `SSID <name>,` — the Qualcomm LOWI scan record, where the name is bare and the comma is the
     * field separator:
     *
     *     ..., CPL: 0, AGE: 122,  SSID Freebox-8D6410, TSFDelta: 0x1ac...
     *
     * Same two lookbehind guards as [SSID_BARE], for the same two reasons: `[A-Za-z]` keeps it off
     * `BSSID` and `HESSID`, which are addresses and belong to [MAC], and `<` keeps it off the
     * `<ssid:...>` token this rule writes.
     *
     * `[ \t]` rather than `\s`, so it cannot reach across a line ending and swallow the first field
     * of the next record. Like [SSID_BARE] it runs to the comma rather than to the first space,
     * which means a line such as `SSID not found` has its two words tokenised. That trade is taken
     * on purpose: the cost is one unreadable status word, and the cost the other way round is a
     * network name published in a group chat.
     */
    private val SSID_SPACED_BARE = Rule(
        "wifi-ssid",
        Regex("""(?<![A-Za-z<])(SSID[ \t]+)(?!['"])([^\s,'"<][^,'"\n]{0,31})""",
            RegexOption.IGNORE_CASE),
    ) { m, tok ->
        if (isToken(m.groupValues[2].trim())) m.value
        else m.groupValues[1] + "<ssid:" + tok(m.groupValues[2].trim()) + ">"
    }

    /**
     * Any six-group MAC. Unambiguous in this corpus: 369 hits, all of them real addresses.
     *
     * Broadcast and all-zero are deliberately kept. They identify nobody, and their presence in a
     * `wpa_supplicant` frame is itself the diagnostic — replacing them would delete signal and
     * protect no one.
     */
    private val MAC = Rule(
        "mac",
        Regex("""\b(?:[0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}\b"""),
    ) { m, tok ->
        val v = m.value.lowercase()
        if (v == "ff:ff:ff:ff:ff:ff" || v == "00:00:00:00:00:00") m.value
        else "<mac:" + tok(v) + ">"
    }

    /**
     * The one real source of position in the corpus: 13 reports out of 160.
     *
     * Anchored on the tag and eats the rest of the line, because what follows the marker is a fix
     * whose format we do not control and should not try to parse.
     */
    private val GPS = Rule(
        "gps",
        Regex("""(GpsMonitor[^\n]{0,40}?GPS:\s*)([^\n]+)"""),
    ) { m, tok ->
        if (isToken(m.groupValues[2])) m.value
        else m.groupValues[1] + "<coords:" + tok(m.groupValues[2]) + ">"
    }

    /**
     * The framework's own position carrier — the one [GPS] never covered.
     *
     * [GPS] is anchored on `GpsMonitor`, a third-party dashcam tag. It is real (16 hits in the
     * corpus) but it is not what the platform emits, and the platform emits far more: measured
     * across 178 real reports, `Location[` / `Loc[` carries **218 positions in 19 reports**,
     * against 16 in 16 for `GpsMonitor`. The AUD-004 counter-verification quoted one of these
     * lines as its reason for keeping the finding at P1, and the fix then shipped without
     * covering it.
     *
     * These ROMs print the pair with the decimal point implied, which is why it does not look
     * like a coordinate at a glance: `Location[gps,655440,265461 hAcc=3.79 …]` is 65.5440, 26.5461
     * — roughly ten metres. Four shapes exist in the corpus and all four are positional:
     * `Loc[N,lat,lon` (164), `Location[gps,lat,lon` (44), `Location[,lat,lon` (6, empty provider)
     * and `Loc[fused,lat,lon` (4). The AOSP form `Location[gps 48.858370,2.294481` is matched too.
     *
     * Safe to anchor this way, and measured rather than assumed: of every `Loc[`/`Location[`
     * occurrence in the corpus, **zero** fall outside this pattern, so there is no shape it
     * silently misses and no non-positional shape it would eat. That is the opposite of the bare
     * decimal pair the class KDoc warns about, which was 92% noise — the bracket plus the
     * provider slot is the anchor doing the work.
     *
     * Only the pair is replaced. `hAcc`, `alt`, `vel` and the satellite bundle stay, because they
     * are the diagnostic content and they identify nobody. The token is derived from the pair, so
     * a stationary car logging the same fix 164 times still reads as one place.
     *
     * Deliberately NOT matched: `reportLocation [ hAcc=4 …` and `incoming location: [ hAcc=4 …`,
     * 235 lines in the corpus where the framework already omitted the coordinates. There is
     * nothing there to remove, and matching them would inflate the count the summary line reports.
     */
    private val GPS_FRAMEWORK = Rule(
        "gps",
        Regex("""\b(Loc(?:ation)?\[)([A-Za-z0-9]*[ ,])(-?\d+(?:\.\d+)?,-?\d+(?:\.\d+)?)"""),
    ) { m, tok ->
        if (isToken(m.groupValues[3])) m.value
        else m.groupValues[1] + m.groupValues[2] + "<coords:" + tok(m.groupValues[3]) + ">"
    }


    /**
     * Position carried as a named parameter, which is how the OEM's own services leak it.
     *
     * Measured on the same 178 reports: 14 occurrences in 7 reports, all of them real and all in
     * range. Two carriers, and neither is anything DashCast writes —
     * `postPar:{"param":"language=zh_CN&lon=3.12092513&lat=50.6654332&dataType=charge"}` from the
     * charge-point lookup, and a nav log line `lon:0.0 0.0`.
     *
     * Anchored on the key, per the rule this file states at the top: the shape alone is 92% noise
     * (727 free decimal pairs survive both position rules in the corpus, and the sample is
     * `globalScale=1.000000, windowScale=(1.000000,1.000000)` — the window manager, every time).
     * `lat=` / `lon=` / `latitude=` / `longitude=` followed by a decimal has no second meaning
     * here: of the 14 hits, zero fall outside ±90 / ±180.
     *
     * A zero value is kept, for the same reason [MAC] keeps the all-zero address: it identifies
     * nobody, and `lon:0.0` in a nav frame is itself the diagnostic — the fix had not arrived yet.
     */
    private val GPS_KEYED = Rule(
        "gps",
        Regex("""(?i)\b(lat|latitude|lon|lng|longitude)(\s*[=:]\s*)(-?\d+\.\d+)"""),
    ) { m, tok ->
        val v = m.groupValues[3]
        if (v.toDoubleOrNull() == 0.0) m.value
        else m.groupValues[1] + m.groupValues[2] + "<coords:" + tok(v) + ">"
    }

    /**
     * The VIN-derived cloud token: the literal `byd` followed by exactly 16 hex digits.
     *
     * The heaviest identifier in the corpus — 1164 occurrences across 111 of the 160 reports, 25
     * distinct vehicles, and each token binds to exactly one device string. [VIN_KEY] was already
     * taking 1048 of them because they usually sit behind a `vin=`; 116 do not, and reached the
     * report through five other carriers (the getprop sweep, BYDAutoBodyworkDevice, AutoiotService,
     * BydDataCollector, DiCarSDK). Anchoring on the value instead of the key catches every carrier
     * at once, including the ones nobody has written yet.
     *
     * Self-anchoring and safe: a literal prefix plus a fixed hex length matched nothing else in
     * 141 MB — never a version string, a fingerprint, a package name or a stack frame. The prefix
     * is matched case-insensitively, which picks up 4 further occurrences the case-sensitive form
     * missed.
     *
     * Runs before [VIN_KEY] so the whole class is treated identically wherever it appears.
     */
    private val VIN_CLOUD = Rule(
        "vin-cloud",
        Regex("""byd[0-9a-f]{16}(?![0-9a-fA-F])""", RegexOption.IGNORE_CASE),
    ) { m, tok -> "<vin:" + tok(m.value.lowercase()) + ">" }

    /**
     * A real ISO-3779 VIN, anchored on its world-manufacturer prefix.
     *
     * Four occurrences in two reports — rare, and the only place the actual chassis number appears
     * in plain form rather than as the cloud token. The WMI anchor is what makes it safe: the bare
     * 17-character VIN charset fires 1370 times on this corpus with a 0.29% true-positive rate,
     * because 1361 of those are 17-DIGIT display identifiers. Requiring a leading `L`,
     * case-sensitively, excludes every one of them — and `local:` is lowercase.
     *
     * The three-character prefix is kept: it names the manufacturer, which is triage context, and
     * carries nothing about the individual car.
     */
    private val VIN_RAW = Rule(
        "vin-raw",
        Regex("""\b(L[A-HJ-NPR-Z0-9]{2})[A-HJ-NPR-Z0-9]{14}\b"""),
    ) { m, tok -> m.groupValues[1] + "<vin:" + tok(m.value) + ">" }

    /**
     * The activation blob, anchored on the one tag that carries it.
     *
     * 1043 occurrences across 70 reports. The lookbehind is the entire rule: the value class —
     * 22 base64 characters then `==` — matches 4042 times across 139 reports, and about 2986 of
     * those are `/data/app/~~<blob>==/<pkg>-<blob>==/base.apk` install paths, which are the
     * build-and-split evidence for DashCast and every navigation app under test. Removing them
     * would blind the most common triage question there is: which build is actually installed.
     */
    private val ACTIVATION = Rule(
        "activation",
        Regex("""(?<=before:)[A-Za-z0-9+/]{22}=="""),
    ) { _, _ -> "<activation>" }

    /**
     * A Google account name, behind either of the two tags that log one. 17 occurrences, 3 reports.
     */
    private val GMS_ACCOUNT = Rule(
        "account",
        Regex("""((?:GmsAuthManagerSvc: getToken: account:)|""" +
              """(?:GmsAuthenticator: getAuthToken: Account \{name=))(\s*)([^\s,}]{1,128})"""),
    ) { m, _ -> m.groupValues[1] + m.groupValues[2] + "<account>" }

    /**
     * An e-mail address. 26 real ones in the corpus, and every guard below was measured.
     *
     * The naive form — `[\w.%+-]+@[\w.-]+\.[A-Za-z]{2,}` — matches 1977 times with 98.7% false
     * positives, dominated by versioned vendor HAL and AIDL service names shaped like
     * `a.b.c@1.0-service.suffix`. Deleting those strips the ROM's own vendor-stack identity out of
     * 81 of the 160 reports.
     *
     * Two guards do the work. Refusing a digit or a colon straight after the `@` takes 1977 matches
     * down to 26 on its own. The closed list of top-level domains is what keeps the rule off
     * `IInputMethod$Stub$Proxy@<hash>` and off the AudioFocus client ids this application writes
     * into its own journal.
     */
    private val EMAIL = Rule(
        "email",
        Regex("""(?<![\w.%+-])[A-Za-z0-9](?:[A-Za-z0-9._%+-]{0,62}[A-Za-z0-9])?""" +
              """@(?![\d:])(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\.)+""" +
              """(?:com|net|org|edu|gov|io|me|info|biz|eu|co|fr|de|es|it|uk|nl|be|pl|pt|ru|cn|ch|""" +
              """at|se|dk|no|fi|cz|sk|hu|ro|gr|ie|il|tr|ua|us|ca|au|br|mx|jp|kr|in)(?![\w-])"""),
    ) { _, _ -> "<email>" }

    /**
     * Order matters. The two VIN rules are anchored and run first; [SSID] must precede [SSID_BARE]
     * and [SSID_SPACED_QUOTED] must precede [SSID_SPACED_BARE], so in each pair the quoted form is
     * consumed by the rule that keeps its quotes; [MAC] is broad but unambiguous,
     * so it can run afterwards without eating anything the earlier rules produced — the tokens they
     * write contain no colon-separated hex.
     */
    /**
     * A Telegram bot token, in the URL that leaks it.
     *
     * Every other rule here protects the TESTER. This one protects the project: the token is the
     * credential AUD-001 took out of the APK, and the one asset in this system whose loss lets a
     * stranger post into — and read — the diagnostics channel.
     *
     * The vector is narrow and was not obvious. [TelegramBugReporter] builds
     * `https://api.telegram.org/bot<TOKEN>/sendDocument`, and several `java.net` / `java.io`
     * exceptions carry the request URL in their own message. One logged exception puts the token
     * in the journal — and the journal is copied verbatim into every report sent afterwards. The
     * journal DOES pass through this redactor (BugReportCapture assembles it into the body before
     * the single redact call), so the gap was never that it went unfiltered; it was that no rule
     * here recognised a bot token.
     *
     * The primary fix is at the log site, which scrubs before writing. This is the backstop,
     * because a backstop is what catches the next call site nobody thought of.
     *
     * Measured on the 178-report corpus: 0 matches. That is the wanted answer twice over — no
     * token has leaked to date, AND the pattern costs nothing in false positives. The hash bound
     * is loosened to 30-50 characters only when the `bot` prefix is present, because that prefix
     * is already decisive on its own.
     */
    private val TELEGRAM_TOKEN = Rule(
        "bot_token",
        Regex("""\bbot(\d{6,12}):([A-Za-z0-9_-]{30,50})"""),
    ) { m, tok -> "bot<token:" + tok(m.groupValues[1] + ":" + m.groupValues[2]) + ">" }

    /**
     * The same credential written without the `bot` URL prefix — a raw token pasted into a log
     * line or a provisioning file.
     *
     * Kept tighter than the prefixed form above, because nothing else vouches for it here — but
     * NOT pinned to the canonical 35-character hash. A first draft was, and its own test caught
     * why that is wrong: one character of drift in the format and the rule silently stops matching
     * the thing it exists to catch, with no failure anywhere to say so. A credential rule that can
     * fail closed on a format change is not a credential rule.
     *
     * The bound is measured, not guessed. Across the 178-report corpus, {35,35}, {32,45}, {30,50}
     * and even {25,60} all match ZERO times — 8-12 digits, a colon, then thirty-odd characters of
     * the URL-safe alphabet simply does not occur by accident in a logcat or a dumpsys. {32,45} is
     * chosen as the widest bound that still describes a token rather than a shape.
     */
    private val TELEGRAM_TOKEN_BARE = Rule(
        "bot_token",
        Regex("""\b(\d{8,12}:[A-Za-z0-9_-]{32,45})\b"""),
    ) { m, tok -> "<token:" + tok(m.groupValues[1]) + ">" }

    private val RULES = listOf(
        VIN_PROP, VIN_CLOUD, VIN_KEY, VIN_RAW, ACTIVATION,
        GMS_ACCOUNT, EMAIL, SSID, SSID_BARE, SSID_SPACED_QUOTED, SSID_SPACED_BARE,
        MAC, GPS, GPS_FRAMEWORK, GPS_KEYED,
        TELEGRAM_TOKEN, TELEGRAM_TOKEN_BARE)

    /**
     * Every name a pass can report on, including the cross-pass [redactReporter], which is not in
     * [RULES]. De-duplicated because four rules share `wifi-ssid` on purpose, and sorted so the
     * footer line is stable from one report to the next and diffable across a corpus.
     */
    private val ALL_RULE_NAMES: List<String> =
        (RULES.map { it.name } + "reporter").distinct().sorted()

    /**
     * What a pass removed, per rule. [counts] holds only rules that actually removed something —
     * an absent key means "this rule fired nothing", which is what most callers want to assert.
     * [failed] names the rules whose regex threw, which is a different thing entirely.
     */
    class Result(
        @JvmField val text: String,
        @JvmField val counts: Map<String, Int>,
        /** Rules whose pass threw. Their protection was not applied to this text. */
        @JvmField val failed: Set<String> = emptySet(),
    ) {
        val total: Int get() = counts.values.sum()

        /**
         * One line for the report, naming EVERY rule — including the ones that found nothing.
         *
         * The old line listed only what had matched, which made two opposite worlds produce the
         * same text. INC-20260826-194829 printed `redaction: gps=1, mac=14, vin-prop=1` while 59
         * neighbourhood network names went out in the clear, because the SSID rules wanted a `:`
         * that this ROM never writes. A reader had no way to tell "no Wi-Fi names in this report"
         * from "the Wi-Fi rule is broken" — and that is why the gap survived 178 reports.
         *
         * So an explicit `=0` is now evidence that a rule ran and found nothing, and `=ERR` says
         * the rule threw and its protection was NOT applied. The line is longer on purpose: it is
         * the only place a silently dead rule can show itself.
         */
        fun summary(): String =
            "redaction: " + ALL_RULE_NAMES.joinToString(", ") { name ->
                name + "=" + if (failed.contains(name)) "ERR" else (counts[name] ?: 0).toString()
            }
    }


    /**
     * The header line the wizard writes when a tester gives a contact handle.
     *
     * Anchored on the line start because the word `Telegram` occurs 929 times across 127 reports
     * and every other occurrence is the `org.telegram.messenger` package name or a path under
     * `/storage/.../Telegram/`.
     */
    private val REPORTER_HEADER = Regex("""(?m)^Telegram:[ \t]*(\S.*)$""")

    /**
     * Tokenises the reporter's handle everywhere EXCEPT the header line that carries it.
     *
     * The finding this exists for: the handle does not stay in the header. The same string comes
     * back deeper in the same tester's report as the name of the Wi-Fi network they are connected
     * to — 64 occurrences across 14 reports — and again in IME candidate lists and as the local
     * part of an e-mail address. 115 header lines, 67 incidental occurrences elsewhere. Redacting
     * only the header would have left the interesting half untouched.
     *
     * The header stays verbatim, deliberately. It is the one field the driver typed in order to be
     * contacted, the consent notice already says it is sent, and the bot rather than the tester is
     * the Telegram sender — so tokenising it would break the follow-up loop outright and protect
     * someone from a disclosure they made on purpose. Redacting it too is a defensible stricter
     * policy; it costs contactability, and that cost should be chosen rather than defaulted into.
     *
     * Must run BEFORE the SSID rules. Otherwise the occurrence that matters most — the handle
     * sitting in a network name — is replaced by a generic `<ssid:…>` and the link between the two
     * disappears instead of being recorded as the same token.
     *
     * Short stems are left alone: below four characters the cross-pass starts matching ordinary
     * words, and a rule that eats prose to protect a four-letter nickname is a bad trade.
     */
    private fun redactReporter(text: String, tok: (String) -> String): Pair<String, Int> {
        val m = REPORTER_HEADER.find(text) ?: return text to 0
        val stem = m.groupValues[1].trim().removePrefix("@").trim()
        if (stem.length < 4 || !stem.any { it.isLetter() }) return text to 0
        val cross = Regex("""(?<![\w@./-])@?""" + Regex.escape(stem) + """(?![\w.-])""",
            RegexOption.IGNORE_CASE)
        var n = 0
        val label = "<reporter:" + tok(stem.lowercase()) + ">"
        fun pass(part: String) = cross.replace(part) { n++; label }
        // Rebuilt around the header's own span, so the line itself cannot be touched.
        val out = pass(text.substring(0, m.range.first)) +
            m.value +
            pass(text.substring(m.range.last + 1))
        return out to n
    }

    /**
     * Applies every rule.
     *
     * [salt] exists for the tests; leave it out in production so each report gets its own and no
     * token survives from one report to the next. A rule that throws is skipped rather than
     * allowed to sink the report — losing one rule's protection is bad, losing the report is worse,
     * and the summary line names the rule `=ERR`, so a lost protection reads as a failure rather
     * than as a clean report.
     */
    @JvmStatic
    @JvmOverloads
    fun redact(text: String, salt: String = freshSalt()): Result {
        val counts = LinkedHashMap<String, Int>()
        val failed = LinkedHashSet<String>()
        var out = text
        val tok: (String) -> String = { v -> token(salt, v) }
        // Before the rules: the cross-pass needs the handle to still be findable inside the
        // network names the SSID rules are about to replace.
        try {
            val (t, n) = redactReporter(out, tok)
            out = t
            if (n > 0) counts["reporter"] = n
        } catch (_: Throwable) {
            failed.add("reporter")
        }
        for (r in RULES) {
            try {
                var n = 0
                out = r.regex.replace(out) { m ->
                    // Count substitutions, not matches. A broadcast address matches [MAC] and is
                    // deliberately returned untouched; counting it would have the summary line
                    // tell a maintainer that two addresses were removed when none were.
                    val rep = r.replace(m, tok)
                    if (rep != m.value) n++
                    rep
                }
                // Accumulate: the quoted and bare SSID rules share a name on purpose, because a
                // reader of the summary line cares how many networks were removed, not by which
                // of two patterns.
                if (n > 0) counts[r.name] = (counts[r.name] ?: 0) + n
            } catch (_: Throwable) {
                // Still deliberately silent about the VALUE — printing it would defeat the rule.
                // The FAILURE, though, is now named: a rule that threw reports `=ERR`, which no
                // longer reads like a rule that simply found nothing. See [Result.summary].
                failed.add(r.name)
            }
        }
        return Result(out, counts, failed)
    }

    /** Short, stable within one pass, meaningless outside it. */
    private fun token(salt: String, value: String): String = try {
        val d = MessageDigest.getInstance("SHA-256").digest((salt + value).toByteArray())
        "%02x%02x".format(d[0], d[1])
    } catch (_: Throwable) {
        "----"
    }

    private fun freshSalt(): String =
        java.util.UUID.randomUUID().toString()
}
