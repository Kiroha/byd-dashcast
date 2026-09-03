# DashCast — Measurement Protocol

**Purpose.** Every finding in `report.md` is tagged `ESTIMATED` because it was derived by reading
code, not by profiling a car. This document is the procedure that upgrades findings to `MEASURED`.
Run it on a head unit, paste the artefacts back, and each finding is confirmed or refuted.

**Target:** `com.byd.dashcast` on a BYD head unit (DiLink 3.0 / 5.x, Android 10 / API-29 class,
arm64). ADB over TCP is a prerequisite of the app itself, so it is available by construction.

---

## 0. The measurement trap you must not fall into

> **`dumpsys gfxinfo com.byd.dashcast` does NOT measure the cluster.**

DashCast contributes **zero per-frame work** to the cluster image (see `architecture.md` §0). The
frames on the instrument cluster are produced by the **projected third-party app** and composited by
SurfaceFlinger. `gfxinfo` on `com.byd.dashcast` measures only the head-unit UI window — the app's own
Activity, the preview mirror, the settings screens.

So there are **two distinct frame-time questions**, and they need different commands:

| Question | Measure | Command |
|---|---|---|
| Is the **cluster** janky? | the projected app's process | `dumpsys gfxinfo <projected.pkg> framestats` + SurfaceFlinger on the cluster layer |
| Is **DashCast** stealing headroom? | DashCast's own process | `dumpsys gfxinfo com.byd.dashcast`, `top -H`, sched track in Perfetto |

The audit's whole thesis is that DashCast's failure mode is **the second**: degrading someone else's
frames by burning CPU, GPU and thermal budget. Measure accordingly.

---

## 1. Perfetto capture (primary artefact)

### 1.1 Config — `dashcast-perf.pbtx`

Save on the host, push to the unit. 90 s at 64 MB is sized for an API-29 device with the IVI stack
running alongside; raise `duration_ms` to 120000 only if the buffer holds.

```protobuf
# dashcast-perf.pbtx — DashCast cluster-projection performance capture
buffers: { size_kb: 65536  fill_policy: RING_BUFFER }
buffers: { size_kb: 8192   fill_policy: RING_BUFFER }

# ─────────────── atrace: gfx / view / sched / freq / binder ───────────────
data_sources: {
  config {
    name: "linux.ftrace"
    target_buffer: 0
    ftrace_config {
      atrace_categories: "gfx"
      atrace_categories: "view"
      atrace_categories: "sched"
      atrace_categories: "freq"
      atrace_categories: "binder_driver"
      atrace_categories: "binder_lock"
      atrace_categories: "am"
      atrace_categories: "wm"
      atrace_categories: "sm"          # SurfaceFlinger / surface manager
      atrace_categories: "idle"
      atrace_categories: "power"
      atrace_categories: "res"
      atrace_categories: "dalvik"      # GC events
      atrace_categories: "input"

      # App-level trace sections. Add the projected app here too.
      atrace_apps: "com.byd.dashcast"
      atrace_apps: "*"

      # Scheduler + wakeup attribution — this is how "what runs when nothing is
      # happening" becomes a number rather than an opinion.
      ftrace_events: "sched/sched_switch"
      ftrace_events: "sched/sched_wakeup"
      ftrace_events: "sched/sched_wakeup_new"
      ftrace_events: "sched/sched_waking"
      ftrace_events: "sched/sched_process_exec"   # fork/exec = every shell command
      ftrace_events: "sched/sched_process_fork"
      ftrace_events: "task/task_newtask"
      ftrace_events: "task/task_rename"
      ftrace_events: "power/cpu_frequency"
      ftrace_events: "power/cpu_idle"
      ftrace_events: "power/suspend_resume"
      ftrace_events: "binder/binder_transaction"
      ftrace_events: "binder/binder_transaction_received"
      ftrace_events: "binder/binder_lock"
      ftrace_events: "binder/binder_locked"
      ftrace_events: "binder/binder_unlock"
      ftrace_events: "lowmemorykiller/lowmemory_kill"
      ftrace_events: "oom/oom_score_adj_update"
      ftrace_events: "ftrace/print"

      buffer_size_kb: 16384
      drain_period_ms: 250
    }
  }
}

# ─────────────── process stats: RSS / thread count over time ───────────────
data_sources: {
  config {
    name: "linux.process_stats"
    target_buffer: 1
    process_stats_config {
      scan_all_processes_on_start: true
      proc_stats_poll_ms: 1000
    }
  }
}

# ─────────────── per-CPU load / frequency residency ───────────────
data_sources: {
  config {
    name: "linux.sys_stats"
    target_buffer: 1
    sys_stats_config {
      stat_period_ms: 1000
      stat_counters: STAT_CPU_TIMES
      stat_counters: STAT_FORK_COUNT      # ← fork count == shell-exec cost, directly
      cpufreq_period_ms: 1000
      meminfo_period_ms: 1000
    }
  }
}

# ─────────────── Android frame timeline (API 29 has partial support) ───────────────
data_sources: { config { name: "android.surfaceflinger.frametimeline" target_buffer: 0 } }
data_sources: { config { name: "android.gpu.memory"                  target_buffer: 1 } }

duration_ms: 90000
write_into_file: true
file_write_period_ms: 1000
flush_period_ms: 5000
```

### 1.2 Run it

```bash
adb push dashcast-perf.pbtx /data/local/tmp/

# 90 s capture. Start this, THEN perform the scenario in §1.3.
adb shell "cat /data/local/tmp/dashcast-perf.pbtx | perfetto --txt -c - -o /data/misc/perfetto-traces/dashcast.pftrace"

adb pull /data/misc/perfetto-traces/dashcast.pftrace ./
# open at https://ui.perfetto.dev
```

**Fallback if `perfetto` is restricted on the vendor ROM** (some BYD builds gate `traced`):

```bash
adb shell atrace --async_start -b 32768 -c \
  gfx view sched freq binder_driver am wm input dalvik power idle
#   ... run the scenario ...
adb shell atrace --async_stop -z > dashcast.atrace
# open the same way — ui.perfetto.dev reads atrace files
```

### 1.3 The scenario to perform during the capture

Do these in order, noting wall-clock offsets. **The idle segment is the most important one** — it is
where "work while nothing is happening" becomes visible.

| t | Action |
|---|---|
| 0–15 s | **IDLE.** App open, projection **NOT** active. Touch nothing. |
| 15–30 s | **IDLE, projection ACTIVE.** Start projection, then touch nothing. Cluster shows a static screen. |
| 30–45 s | **Drag** continuously on the preview mirror (exercises H1, the per-touch Binder path). |
| 45–60 s | **Navigation running** on the projected app (exercises H3, the HUD/nav write path). |
| 60–75 s | **Background DashCast** (press home). Projection stays up. Touch nothing. |
| 75–90 s | **Screen off** if the unit permits it. Touch nothing. |

Segments 1, 2, 5 and 6 answer: *does DashCast cost anything when it should cost nothing?*

---

## 2. adb commands — exact invocations

### 2.1 Frame time

```bash
# --- DashCast's OWN UI (head unit window) ---
adb shell dumpsys gfxinfo com.byd.dashcast reset      # before
#   ... run the scenario ...
adb shell dumpsys gfxinfo com.byd.dashcast framestats > gfxinfo_dashcast.txt

# --- The PROJECTED app (this is what the driver actually sees) ---
adb shell dumpsys gfxinfo <projected.pkg> reset
adb shell dumpsys gfxinfo <projected.pkg> framestats > gfxinfo_projected.txt
```
`framestats` emits one CSV row per frame with 14 nanosecond timestamps. Frame time =
`FRAME_COMPLETED - INTENDED_VSYNC`. Compute p50/p95/p99 from the CSV; do not read the histogram
summary alone, it hides the tail.

### 2.2 SurfaceFlinger — the cluster layer specifically

```bash
# 1. Find the cluster layer name. On these units it is a fission / XDJA virtual surface.
adb shell dumpsys SurfaceFlinger --list | grep -iE "fission|xdja|cluster|instrument|projection"

# 2. Per-frame latency for that exact layer (3 columns: desired, actual, frame-ready; ns)
adb shell dumpsys SurfaceFlinger --latency "<exact layer name from step 1>" > sf_latency_cluster.txt

# 3. Same for the mirror surface, to price the preview mirror's composition
adb shell dumpsys SurfaceFlinger --latency "mybyd_preview_mirror" > sf_latency_mirror.txt

# 4. Global SF state: refresh rate, composition strategy, GPU vs HWC
adb shell dumpsys SurfaceFlinger | head -120 > sf_state.txt
```
**Watch for:** layers composited by **GPU (`CLIENT`)** rather than **HWC (`DEVICE`)**. A cluster layer
falling back to GPU composition is a direct hit on a GPU shared with the IVI stack, and the preview
mirror is a plausible cause of that fallback.

**Caveat from prior incidents:** cluster screenshots on these units frequently capture the *wrong
layerStack*. Trust `--latency` on a named layer over any screenshot-based evidence.

### 2.3 Memory

```bash
# Full breakdown; the number that matters for a weeks-uptime device is TOTAL PSS growth over time.
adb shell dumpsys meminfo com.byd.dashcast > meminfo_t0.txt
#   ... 30 minutes of projection ...
adb shell dumpsys meminfo com.byd.dashcast > meminfo_t30.txt

# Leak triage — these two lines are the fastest signal:
adb shell dumpsys meminfo com.byd.dashcast | grep -E "TOTAL|Native Heap|Dalvik Heap|Graphics|Views|Activities|AppContexts|ViewRootImpl"

# Heap dump for a confirmed leak
adb shell am dumpheap com.byd.dashcast /data/local/tmp/dc.hprof
adb pull /data/local/tmp/dc.hprof && hprof-conv dc.hprof dc-conv.hprof   # then open in Android Studio / MAT
```
`Views`, `Activities`, `AppContexts` and `ViewRootImpl` counts that **rise and never fall** across
open/close cycles are the definition of a leak on this platform.

### 2.4 Thermal

```bash
adb shell dumpsys thermalservice > thermal_t0.txt
#   ... 30 minutes of projection ...
adb shell dumpsys thermalservice > thermal_t30.txt

# Raw zones (more reliable than thermalservice on vendor ROMs)
adb shell 'for z in /sys/class/thermal/thermal_zone*; do echo -n "$(cat $z/type): "; cat $z/temp; done'

# Is the SoC actually throttling?
adb shell 'cat /sys/devices/system/cpu/cpu*/cpufreq/scaling_cur_freq'
adb shell 'cat /sys/devices/system/cpu/cpu*/cpufreq/scaling_max_freq'
```

### 2.5 CPU, threads, wakeups

```bash
PID=$(adb shell pidof com.byd.dashcast)

# Per-thread CPU. This is how the thread inventory in report.md gets validated.
adb shell top -H -p $PID -n 1 -b > threads.txt
adb shell ls /proc/$PID/task | wc -l          # live thread count
adb shell cat /proc/$PID/status | grep -E "Threads|VmRSS"

# Wakeup / power attribution over a long window
adb shell dumpsys batterystats --reset
#   ... 30 minutes ...
adb shell dumpsys batterystats > batterystats.txt
adb shell dumpsys batterystats | grep -iE "com.byd.dashcast" -A 30 > batterystats_dashcast.txt

# Wakelocks held right now
adb shell dumpsys power | grep -iA 20 "Wake Locks"

# Are all three foreground services really up?
adb shell dumpsys activity services com.byd.dashcast | grep -E "ServiceRecord|isForeground|foregroundId"
```

### 2.6 Shell-exec cost (this codebase's signature cost)

Every `am` / `dumpsys` / `pidof` / `getprop` the app runs is a **fork + exec**. Count them directly:

```bash
# Global fork counter, sampled — delta over a fixed idle window = wasted process spawns
adb shell 'grep ^processes /proc/stat'   # take twice, 60 s apart, subtract

# Attribute them: in the Perfetto trace, filter the sched track for
#   sched_process_exec  where parent == the uid-2000 daemon or com.byd.dashcast
# Each exec is typically 3-15 ms of CPU on this SoC class.
```

### 2.7 Cold start

```bash
adb shell am force-stop com.byd.dashcast
adb shell am start -W -n com.byd.dashcast/.ui.welcome.WelcomeActivity
# reads: ThisTime / TotalTime / WaitTime  (TotalTime == time to initial display)

# Repeat 10x and take the median; a single run on a car is noise.
for i in $(seq 1 10); do
  adb shell am force-stop com.byd.dashcast; sleep 2
  adb shell am start -W -n com.byd.dashcast/.ui.welcome.WelcomeActivity | grep TotalTime
done
```

### 2.8 Glass-to-glass latency

No adb command produces this; it needs a camera.

1. Film **both panels in one frame** with a phone at 240 fps.
2. Touch the preview mirror at a point that causes an unmistakable visual change on the cluster.
3. Count frames between finger contact and the cluster changing. At 240 fps each frame = 4.17 ms.
4. Repeat 10 times, report median and worst case.

Cross-check against the trace: in Perfetto, measure `input` event dispatch →
`binder_transaction` to the SurfaceDaemon → the cluster layer's next `--latency` row.

---

## 3. KPIs and target thresholds

The cluster is driver-facing and safety-adjacent. **A dropped frame is better than a late frame** —
so the tail (p99) is weighted more heavily than the mean, and jitter is weighted more heavily than
throughput.

| KPI | Target | Fail | How measured |
|---|---|---|---|
| **Cluster frame time p95** | ≤ 16.7 ms (60 Hz budget) | > 22 ms | `dumpsys gfxinfo <projected.pkg> framestats`, p95 of `FRAME_COMPLETED − INTENDED_VSYNC` |
| **Cluster frame time p99** | ≤ 24 ms (one dropped frame tolerable) | > 33 ms (two frames) | same |
| **Cluster dropped frames** | < 1 / min sustained | > 5 / min | `dumpsys SurfaceFlinger --latency <cluster layer>`, rows where actual − desired > 1 vsync |
| **DashCast UI frame p95** | ≤ 16.7 ms | > 33 ms | `dumpsys gfxinfo com.byd.dashcast framestats` |
| **Glass-to-glass latency (touch → cluster)** | ≤ 100 ms median | > 150 ms median, or > 250 ms worst | 240 fps camera, §2.8 |
| **DashCast CPU — projection ACTIVE, screen idle** | **< 1 % of one core** | > 3 % | Perfetto sched track, DashCast + both uid-2000 daemons |
| **DashCast CPU — projection INACTIVE, app backgrounded** | **≈ 0 %; < 0.3 %** | > 1 % | same, capture segment 1 and 6 |
| **Wakeups / min when idle** | < 6 (i.e. ≤ 1 per 10 s across the whole app) | > 30 | `sched_wakeup` count in Perfetto, filtered to app + daemons |
| **fork/exec per idle minute** | **0** | > 4 | `sched_process_exec` in Perfetto; `/proc/stat` `processes` delta |
| **RSS after 30 min projection** | ≤ 180 MB, and **flat** | growth > 15 MB / 30 min | `dumpsys meminfo`, `TOTAL PSS` at t0 vs t30 |
| **Views / Activities / AppContexts** | returns to baseline after close | monotonic rise over 5 open/close cycles | `dumpsys meminfo` |
| **Live thread count (steady state)** | ≤ 25 | > 40 | `ls /proc/$PID/task \| wc -l` |
| **Cold start (TTID), median of 10** | ≤ 1200 ms | > 2000 ms | `am start -W` `TotalTime` |
| **SoC temperature Δ after 30 min projection** | ≤ +8 °C above the idle baseline | > +15 °C, or any throttle event | `thermal_zone*/temp` at t0 vs t30 + `scaling_cur_freq` capped |
| **Cluster layer composition** | HWC (`DEVICE`) | GPU (`CLIENT`) fallback | `dumpsys SurfaceFlinger \| grep -i composition` |
| **APK size / DEX method count** | no regression vs 1.8.38-beta | +10 % | `apkanalyzer dex packages` |

**Baseline discipline.** Every threshold above is meaningless without a control. Capture the same
90 s scenario with **DashCast force-stopped** and the projected app running standalone. The delta is
DashCast's true cost; the absolute number includes the IVI stack and tells you little.

---

## 4. Finding → measurement map

Each static finding in `report.md` is `ESTIMATED` until the measurement named in its
**HOW TO VERIFY** field is run. The mapping is one-to-one and lives in the finding itself rather than
being duplicated here, so it cannot drift.

The general pattern:

| Finding class | Confirms it | Refutes it |
|---|---|---|
| Idle/periodic work | non-zero `sched_wakeup` + `sched_process_exec` for the app during capture segments 1, 5, 6 | zero wakeups attributable to the app across all three idle segments |
| Main-thread blocking | a `Binder`/`file` slice > 8 ms on the main thread in the sched track, or a `Choreographer#doFrame` slice overrunning | no main-thread slice exceeds one vsync |
| Leak | monotonic `TOTAL PSS` / `Views` / `Activities` rise across 5 open-close cycles | counts return to baseline |
| Allocation churn | `dalvik` GC events correlated with the touch-drag segment | GC count flat across segments 3 and 6 |
| IPC chattiness | `binder_transaction` count per user action exceeds the finding's stated number | count matches or is lower |
| Thermal / power | > +8 °C Δ or a `scaling_max_freq` drop at t30 | Δ within budget, no throttle |
| Mirror composition cost | cluster layer flips to `CLIENT` composition while the mirror is up | composition unchanged with the mirror on and off |

**Upgrade procedure.** Paste the artefacts (`.pftrace`, `gfxinfo_*.txt`, `sf_latency_*.txt`,
`meminfo_t0/t30.txt`, `thermal_t0/t30.txt`, `threads.txt`) into the session. Each finding's
`CONFIDENCE` and its `ESTIMATED` tag are then rewritten to `MEASURED` with the observed number
substituted for the estimate — including findings the data **refutes**, which are struck rather than
quietly dropped.
