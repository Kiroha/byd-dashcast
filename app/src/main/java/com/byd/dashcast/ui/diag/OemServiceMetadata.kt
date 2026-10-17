package com.byd.dashcast.ui.diag

/** A closed reader, not a general shell facility. Source text is never executed or exported.
 * Completeness covers only the fixed scope. Imports, symlinks and process args are not read. */
internal object OemServiceMetadata {
    const val PAGE_COUNT = 32
    const val PAGE_ENTRIES = 16
    const val PAGE_SOURCE_BYTES = 262144
    const val PAGE_OUTPUT_BYTES = 6144
    private const val HEADER = "BEGIN OEM_INIT_METADATA_V2"
    private const val SCOPE = "SCOPE fixed root-init-rc and five etc/init plus literal hw; ordinal page only, non-atomic; imports and links never followed"
    private const val LIMIT = "LIMIT global_entry_slots=512 page_entries=16 file_bytes=65536 page_source_bytes=262144 lines=512 line_bytes=1024 page_output_bytes=6144 soft_seconds=12"

    private fun pageIdentity(page: Int) = "index=$page count=$PAGE_COUNT first=${(page - 1) * PAGE_ENTRIES + 1} last=${page * PAGE_ENTRIES}"

    fun script(page: Int): String {
        require(page in 1..PAGE_COUNT)
        return """
LC_ALL=C
export LC_ALL
incomplete=0
files=0
total=0
out=0
selected=0
first=${(page - 1) * PAGE_ENTRIES + 1}
last=${page * PAGE_ENTRIES}
started=''
previous=0
finish() {
    # These bounded numeric fields plus the optional output-limit marker fit the reserved 256 bytes.
    printf '%s\n' "TOTAL files=${'$'}files selected=${'$'}selected source_bytes=${'$'}total" 'PAGE_END ${pageIdentity(page)}'
    if [ "${'$'}incomplete" -eq 0 ]; then printf '%s\n' 'END SCOPED_COMPLETE'; else printf '%s\n' 'END INCOMPLETE'; fi
    exit 0
}
emit() {
    n=${'$'}{#1}
    out=${'$'}((out+n+1))
    if [ "${'$'}out" -gt 5888 ]; then
        incomplete=1
        printf '%s\n' 'INCOMPLETE output_limit'
        finish
    fi
    printf '%s\n' "${'$'}1"
}
partial() { incomplete=1; emit "INCOMPLETE ${'$'}1"; }
safe() { case "${'$'}1" in ''|*[!A-Za-z0-9_./:+-]*) return 1;; esac; }
check_time() {
    now=${'$'}(date +%s 2>/dev/null)
    if [ "${'$'}?" -ne 0 ]; then partial clock_unavailable; return 1; fi
    case "${'$'}now" in ''|*[!0-9]*|0[0-9]*) partial clock_unavailable; return 1;; esac
    if [ "${'$'}{#now}" -gt 10 ] || [ "${'$'}now" -gt 2147483600 ]; then partial clock_unavailable; return 1; fi
    if [ -z "${'$'}started" ]; then started=${'$'}now; previous=${'$'}now; return 0; fi
    if [ "${'$'}now" -lt "${'$'}previous" ]; then partial clock_rollback; return 1; fi
    previous=${'$'}now
    if [ "${'$'}((now-started))" -ge 12 ]; then partial time_budget; return 1; fi
}
parse_file() {
    file=${'$'}1
    safe "${'$'}file" || { partial unsafe_path; return; }
    if [ -L "${'$'}file" ]; then partial "symlink_file ${'$'}file"; return; fi
    if [ ! -f "${'$'}file" ]; then partial "nonregular_file ${'$'}file"; return; fi
    real=${'$'}(readlink -f "${'$'}file" 2>/dev/null)
    if [ "${'$'}real" != "${'$'}file" ]; then partial "noncanonical_file ${'$'}file"; return; fi
    size=${'$'}(stat -c %s "${'$'}file" 2>/dev/null)
    case "${'$'}size" in ''|*[!0-9]*) partial "stat_unavailable ${'$'}file"; return;; esac
    if [ "${'$'}{#size}" -gt 5 ] || [ "${'$'}size" -gt 65536 ]; then partial "file_byte_limit ${'$'}file"; return; fi
    if [ "${'$'}size" -gt "${'$'}((262144-total))" ]; then partial page_byte_limit; finish; fi
    total=${'$'}((total+size))
    if [ ! -r "${'$'}file" ]; then partial "read_denied ${'$'}file"; return; fi
    content=${'$'}(head -c "${'$'}size" "${'$'}file" 2>/dev/null)
    if [ "${'$'}?" -ne 0 ]; then partial "read_failed ${'$'}file"; return; fi
    if [ "${'$'}{#content}" -gt 65536 ]; then partial "read_byte_limit ${'$'}file"; return; fi
    check_time || finish
    after=${'$'}(stat -c %s "${'$'}file" 2>/dev/null)
    if [ "${'$'}after" != "${'$'}size" ]; then partial "file_changed ${'$'}file"; return; fi
    emit "FILE ${'$'}file bytes=${'$'}size"
    line_no=0
    active=0
    continuation=0
    while IFS= read -r line || [ -n "${'$'}line" ]; do
        line_no=${'$'}((line_no+1))
        if [ "${'$'}line_no" -gt 512 ]; then partial "line_count_limit ${'$'}file"; break; fi
        if [ "${'$'}((line_no % 128))" -eq 0 ]; then check_time || finish; fi
        if [ "${'$'}{#line}" -gt 1024 ]; then partial "line_byte_limit ${'$'}file:${'$'}line_no"; active=0; continue; fi
        case "${'$'}line" in *'\') partial "continuation ${'$'}file:${'$'}line_no"; continuation=1; active=0; continue;; esac
        if [ "${'$'}continuation" -eq 1 ]; then continuation=0; active=0; continue; fi
        # Tokenize only: glob expansion is disabled and no parsed text is executed.
        set -f
        set -- ${'$'}line
        set +f
        [ "${'$'}#" -gt 0 ] || continue
        case "${'$'}1" in \#*) continue;; esac
        # Android init section headers are token based; indentation has no authority.
        case "${'$'}1" in
            service)
                active=0
                if [ "${'$'}#" -lt 3 ] || ! safe "${'$'}2" || ! safe "${'$'}3"; then partial "unresolved_service ${'$'}file:${'$'}line_no"; continue; fi
                case "${'$'}3" in /*) ;; *) partial "relative_executable ${'$'}file:${'$'}line_no"; continue;; esac
                emit "SERVICE ${'$'}file:${'$'}line_no ${'$'}2 ${'$'}3"
                active=1
                ;;
            on) active=0;;
            import)
                active=0
                if [ "${'$'}#" -eq 2 ] && safe "${'$'}2"; then emit "IMPORT_NOT_FOLLOWED ${'$'}file:${'$'}line_no ${'$'}2"
                else partial "unresolved_import ${'$'}file:${'$'}line_no"; fi
                ;;
            user|group|seclabel)
                [ "${'$'}active" -eq 1 ] || continue
                field=${'$'}1
                shift
                if [ "${'$'}#" -eq 0 ] || [ "${'$'}#" -gt 16 ]; then partial "unresolved_attribute ${'$'}file:${'$'}line_no"; continue; fi
                values=''
                valid=1
                for token in "${'$'}@"; do
                    case "${'$'}token" in \#*) break;; esac
                    if ! safe "${'$'}token"; then valid=0; break; fi
                    values="${'$'}values ${'$'}token"
                done
                if [ "${'$'}valid" -eq 0 ] || [ -z "${'$'}values" ]; then partial "unresolved_attribute ${'$'}file:${'$'}line_no"; continue; fi
                emit "ATTRIBUTE ${'$'}file:${'$'}line_no ${'$'}field${'$'}values"
                ;;
        esac
    done <<EOF_METADATA
${'$'}content
EOF_METADATA
}
visit_file() {
    file=${'$'}1
    [ -e "${'$'}file" ] || [ -L "${'$'}file" ] || return 0
    files=${'$'}((files+1))
    if [ "${'$'}files" -gt 512 ]; then partial file_count_limit; finish; fi
    # Select by ordinal before any per-file external utility; rejected links still occupy a slot.
    [ "${'$'}files" -ge "${'$'}first" ] || return 0
    [ "${'$'}files" -le "${'$'}last" ] || return 0
    selected=${'$'}((selected+1))
    check_time || finish
    if [ -n "${'$'}current_root" ] && [ "${'$'}root_metadata_emitted" -eq 0 ]; then
        root_metadata_emitted=1
        metadata=${'$'}(ls -ldZ "${'$'}current_root" 2>/dev/null)
        if [ "${'$'}?" -ne 0 ]; then partial "root_metadata_failed ${'$'}current_root"
        else emit "ROOT_METADATA ${'$'}metadata"; fi
    fi
    parse_file "${'$'}file"
    check_time || finish
    if [ "${'$'}files" -eq "${'$'}last" ]; then finish; fi
}
emit '$HEADER'
emit 'PAGE ${pageIdentity(page)}'
emit '$SCOPE'
emit '$LIMIT'
for tool in head stat readlink ls date; do
    if ! command -v "${'$'}tool" >/dev/null 2>&1; then partial "missing_utility ${'$'}tool"; finish; fi
done
check_time || finish
current_root=''
for file in /init*.rc; do visit_file "${'$'}file"; done
for root in /system/etc/init /system/etc/init/hw /vendor/etc/init /vendor/etc/init/hw /odm/etc/init /odm/etc/init/hw /product/etc/init /product/etc/init/hw /system_ext/etc/init /system_ext/etc/init/hw; do
    check_time || finish
    if [ -L "${'$'}root" ]; then partial "symlink_root ${'$'}root"; continue; fi
    if [ ! -e "${'$'}root" ]; then partial "root_unavailable ${'$'}root"; continue; fi
    if [ ! -d "${'$'}root" ]; then partial "non_directory_root ${'$'}root"; continue; fi
    real=${'$'}(readlink -f "${'$'}root" 2>/dev/null)
    if [ "${'$'}real" != "${'$'}root" ]; then partial "noncanonical_root ${'$'}root"; continue; fi
    if [ ! -r "${'$'}root" ] || [ ! -x "${'$'}root" ]; then partial "root_denied ${'$'}root"; continue; fi
    current_root=${'$'}root
    root_metadata_emitted=0
    for file in "${'$'}root"/*.rc; do visit_file "${'$'}file"; done
done
check_time || finish
finish
""".trimIndent()
    }

    fun interpret(value: String, page: Int): DiLinkContextPlan.Interpretation {
        require(page in 1..PAGE_COUNT)
        val lines = value.lines()
        if (value.toByteArray(Charsets.UTF_8).size > PAGE_OUTPUT_BYTES ||
            lines.firstOrNull() != HEADER || lines.getOrNull(1) != "PAGE ${pageIdentity(page)}" ||
            lines.getOrNull(2) != SCOPE || lines.getOrNull(3) != LIMIT ||
            lines.getOrNull(lines.lastIndex - 1) != "PAGE_END ${pageIdentity(page)}" ||
            lines.count { it.startsWith("PAGE ") } != 1 || lines.count { it.startsWith("PAGE_END ") } != 1 ||
            lines.count { it.startsWith("TOTAL files=") } != 1 ||
            lines.getOrNull(lines.lastIndex - 2)?.matches(Regex("TOTAL files=[0-9]+ selected=[0-9]+ source_bytes=[0-9]+")) != true)
            return DiLinkContextPlan.Interpretation("unexpected_output", "Init page identity, declared limits or completion framing not recognized")
        val totals = lines[lines.lastIndex - 2].removePrefix("TOTAL ").split(' ')
            .map { it.substringAfter('=').toLongOrNull() }
        if (totals.any { it == null } || totals[0]!! !in 0L..(page * PAGE_ENTRIES).toLong() ||
            totals[1]!! !in 0L..PAGE_ENTRIES.toLong() || totals[2]!! !in 0L..PAGE_SOURCE_BYTES.toLong() ||
            lines.lastOrNull() !in listOf("END SCOPED_COMPLETE", "END INCOMPLETE") ||
            lines.any { line -> listOf("BEGIN ", "SCOPE ", "LIMIT ", "FILE ", "SERVICE ", "ATTRIBUTE ",
                "IMPORT_NOT_FOLLOWED ", "ROOT_METADATA ", "TOTAL ", "PAGE ", "PAGE_END ", "END ", "INCOMPLETE ").none(line::startsWith) })
            return DiLinkContextPlan.Interpretation("unexpected_output", "Init reader completion/format not recognized")
        if (lines.any { it.startsWith("INCOMPLETE ") } || lines.lastOrNull() == "END INCOMPLETE")
            return DiLinkContextPlan.Interpretation("incomplete_metadata", "Init page reported an omission, denial, clock anomaly or limit; raw output retained, no positive observation inferred")
        return DiLinkContextPlan.Interpretation("ok", values = mapOf("interpretation" to
            "Only this declared ordinal window in the fixed roots/root+hw scope was inspected. Independently collected pages are non-atomic and may overlap or omit changed entries; no global absence, live publisher or server implementation is established."))
    }
}
