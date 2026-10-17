package com.byd.dashcast.ui.diag

/** A closed reader, not a general shell facility. Source text is never executed or exported.
 * Completeness covers only the fixed scope. Imports, symlinks and process args are not read. */
internal object OemServiceMetadata {
    val script: String = """
LC_ALL=C
export LC_ALL
incomplete=0
files=0
total=0
out=0
emit() {
    n=${'$'}{#1}
    out=${'$'}((out+n+1))
    if [ "${'$'}out" -gt 196608 ]; then
        printf '%s\n' 'INCOMPLETE output_limit' 'END INCOMPLETE'
        exit 0
    fi
    printf '%s\n' "${'$'}1"
}
partial() { incomplete=1; emit "INCOMPLETE ${'$'}1"; }
safe() { case "${'$'}1" in ''|*[!A-Za-z0-9_./:+-]*) return 1;; esac; }
parse_file() {
    file=${'$'}1
    [ -e "${'$'}file" ] || [ -L "${'$'}file" ] || return 0
    files=${'$'}((files+1))
    if [ "${'$'}files" -gt 512 ]; then partial file_count_limit; return 1; fi
    safe "${'$'}file" || { partial unsafe_path; return; }
    if [ -L "${'$'}file" ]; then partial "symlink_file ${'$'}file"; return; fi
    if [ ! -f "${'$'}file" ]; then partial "nonregular_file ${'$'}file"; return; fi
    real=${'$'}(readlink -f "${'$'}file" 2>/dev/null)
    if [ "${'$'}real" != "${'$'}file" ]; then partial "noncanonical_file ${'$'}file"; return; fi
    size=${'$'}(stat -c %s "${'$'}file" 2>/dev/null)
    case "${'$'}size" in ''|*[!0-9]*) partial "stat_unavailable ${'$'}file"; return;; esac
    if [ "${'$'}size" -gt 65536 ]; then partial "file_byte_limit ${'$'}file"; return; fi
    total=${'$'}((total+size))
    if [ "${'$'}total" -gt 8388608 ]; then partial total_byte_limit; return 1; fi
    if [ ! -r "${'$'}file" ]; then partial "read_denied ${'$'}file"; return; fi
    content=${'$'}(head -c 65537 "${'$'}file" 2>/dev/null)
    if [ "${'$'}?" -ne 0 ]; then partial "read_failed ${'$'}file"; return; fi
    if [ "${'$'}{#content}" -gt 65536 ]; then partial "read_byte_limit ${'$'}file"; return; fi
    after=${'$'}(stat -c %s "${'$'}file" 2>/dev/null)
    if [ "${'$'}after" != "${'$'}size" ]; then partial "file_changed ${'$'}file"; return; fi
    emit "FILE ${'$'}file bytes=${'$'}size"
    line_no=0
    active=0
    continuation=0
    while IFS= read -r line || [ -n "${'$'}line" ]; do
        line_no=${'$'}((line_no+1))
        if [ "${'$'}line_no" -gt 512 ]; then partial "line_count_limit ${'$'}file"; break; fi
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
emit 'BEGIN OEM_INIT_METADATA_V1'
emit 'SCOPE root-init-rc and five fixed etc/init directories plus literal hw only; imports and links never followed'
emit 'LIMIT files=512 file_bytes=65536 total_bytes=8388608 lines=512 line_bytes=1024 output_bytes=196608'
for tool in head stat readlink ls; do
    if ! command -v "${'$'}tool" >/dev/null 2>&1; then partial "missing_utility ${'$'}tool"; emit 'END INCOMPLETE'; exit 0; fi
done
for file in /init*.rc; do
    if ! parse_file "${'$'}file"; then emit 'END INCOMPLETE'; exit 0; fi
done
for root in /system/etc/init /system/etc/init/hw /vendor/etc/init /vendor/etc/init/hw /odm/etc/init /odm/etc/init/hw /product/etc/init /product/etc/init/hw /system_ext/etc/init /system_ext/etc/init/hw; do
    if [ -L "${'$'}root" ]; then partial "symlink_root ${'$'}root"; continue; fi
    if [ ! -e "${'$'}root" ]; then partial "root_unavailable ${'$'}root"; continue; fi
    if [ ! -d "${'$'}root" ]; then partial "non_directory_root ${'$'}root"; continue; fi
    real=${'$'}(readlink -f "${'$'}root" 2>/dev/null)
    if [ "${'$'}real" != "${'$'}root" ]; then partial "noncanonical_root ${'$'}root"; continue; fi
    if [ ! -r "${'$'}root" ] || [ ! -x "${'$'}root" ]; then partial "root_denied ${'$'}root"; continue; fi
    metadata=${'$'}(ls -ldZ "${'$'}root" 2>/dev/null)
    if [ "${'$'}?" -ne 0 ]; then partial "root_metadata_failed ${'$'}root"; continue; fi
    emit "ROOT_METADATA ${'$'}metadata"
    for file in "${'$'}root"/*.rc; do
        if ! parse_file "${'$'}file"; then emit 'END INCOMPLETE'; exit 0; fi
    done
done
emit "TOTAL files=${'$'}files source_bytes=${'$'}total"
if [ "${'$'}incomplete" -eq 0 ]; then emit 'END SCOPED_COMPLETE'; else emit 'END INCOMPLETE'; fi
""".trimIndent()

    fun interpret(value: String): DiLinkContextPlan.Interpretation {
        val lines = value.lines()
        if (lines.firstOrNull() != "BEGIN OEM_INIT_METADATA_V1")
            return DiLinkContextPlan.Interpretation("unexpected_output", "Missing bounded init reader header")
        if (lines.any { it.startsWith("INCOMPLETE ") } || lines.lastOrNull() == "END INCOMPLETE")
            return DiLinkContextPlan.Interpretation("incomplete_metadata", "Init reader reported an omission, denial, unresolved value or limit; raw output retained, no positive observation inferred")
        if (lines.lastOrNull() != "END SCOPED_COMPLETE" || lines.none { it.startsWith("TOTAL files=") } ||
            lines.any { line -> listOf("BEGIN ", "SCOPE ", "LIMIT ", "FILE ", "SERVICE ", "ATTRIBUTE ",
                "IMPORT_NOT_FOLLOWED ", "ROOT_METADATA ", "TOTAL ", "END ").none(line::startsWith) })
            return DiLinkContextPlan.Interpretation("unexpected_output", "Init reader completion/format not recognized")
        return DiLinkContextPlan.Interpretation("ok", values = mapOf("interpretation" to
            "Only the declared fixed roots/root+hw depth and metadata fields were inspected. Imports and links were not followed; no global absence, live publisher or server implementation is established."))
    }
}
