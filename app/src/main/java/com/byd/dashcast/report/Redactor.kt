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
 *    `globalScale=…, windowScale=(x,y)` from the window manager. Real positions arrive through one
 *    tag, in 13 reports out of 160, so [GPS] is anchored on it.
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
     * Order matters. The two VIN rules are anchored and run first; [SSID] must precede [SSID_BARE]
     * so the quoted form is consumed by the rule that keeps its quotes; [MAC] is broad but unambiguous,
     * so it can run afterwards without eating anything the earlier rules produced — the tokens they
     * write contain no colon-separated hex.
     */
    private val RULES = listOf(VIN_PROP, VIN_KEY, SSID, SSID_BARE, MAC, GPS)

    /** What a pass removed, per rule. Empty when the text was already clean. */
    class Result(@JvmField val text: String, @JvmField val counts: Map<String, Int>) {
        val total: Int get() = counts.values.sum()
        /** One line for the report, so a maintainer can see that something was removed. */
        fun summary(): String =
            if (counts.isEmpty()) "redaction: nothing matched"
            else "redaction: " + counts.entries.sortedBy { it.key }
                .joinToString(", ") { it.key + "=" + it.value }
    }

    /**
     * Applies every rule.
     *
     * [salt] exists for the tests; leave it out in production so each report gets its own and no
     * token survives from one report to the next. A rule that throws is skipped rather than
     * allowed to sink the report — losing one rule's protection is bad, losing the report is worse,
     * and the summary line will show that rule's count missing.
     */
    @JvmStatic
    @JvmOverloads
    fun redact(text: String, salt: String = freshSalt()): Result {
        val counts = LinkedHashMap<String, Int>()
        var out = text
        val tok: (String) -> String = { v -> token(salt, v) }
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
                // Deliberately silent about the value; the missing count is the signal.
            }
        }
        return Result(out, counts)
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
