export default {
  code: 'en',
  flag: '🇬🇧',
  name: 'English',
  title: 'DashCast — User Manual',
  manualName: 'User Manual',
  meta: 'v0.9.92-alpha · BYD Seal EU · DiLink 3.0 · Android 10',
  tocTitle: '📋 Contents',

  intro: {
    title: '0. Introduction',
    lead:
      "DashCast lets you display any Android app from the central BYD screen on the digital instrument cluster. You can have Maps, Waze, Spotify or YouTube directly behind the steering wheel while still keeping the native BYD readouts (speed, gauges, range) accessible at any time.",
    bullets: [
      "✅ Compatible with BYD Seal EU (DiLink 3.0, Di3.0 / 6125F firmware).",
      "✅ No system modification: DashCast installs as a regular app.",
      "✅ Local TCP ADB — no computer needed after the first authorisation.",
      "✅ 12 interface languages, picked on first launch.",
      "✅ Built-in OTA updates (optional alpha channel).",
      "✅ Per-app overscan margins (each app keeps its own adjustment).",
      "✅ Touchable full-screen mirror to drive the cluster from the central display.",
      "✅ Split mode (two apps side by side on the cluster).",
    ],
    note:
      "💡 One-time prerequisite: enable wireless ADB debugging in BYD Settings → Developer. On first launch a dialog asks « Allow debugging? » — tick « Always allow » and confirm. You never need to repeat this step.",
  },

  sections: [
    {
      id: 'welcome',
      screen: 'screen-1',
      title: '1. Welcome screen — language picker',
      lead:
        "On the very first launch, DashCast shows a grid with the 12 available languages. Tap the one you want; your choice is saved and the welcome screen will not show up again. You can change the language any time from Settings → Language.",
      mockupLabel: 'Open screen 1 (Welcome)',
      featuresTitle: 'Details',
      features: [
        {
          title: '12 supported languages',
          text:
            "Français, English, Deutsch, Italiano, Türkçe, Español, Русский, Українська, العربية, O'zbekcha, Қазақша, Беларуская. The selected language is applied immediately, no restart.",
        },
        {
          title: 'Automatic reading direction',
          text:
            "Arabic switches automatically to right-to-left layout (RTL): the navigation rail moves to the right side, lists invert, icons stay readable.",
        },
        {
          title: 'Changeable any time',
          text:
            "To change the language later: long-press the DashCast logo at the top of the side rail → 🌐 Language. The new language is applied on the fly.",
        },
      ],
      howTo: {
        title: 'How to do it',
        steps: [
          "Launch DashCast (blue icon in the BYD app drawer).",
          "The welcome screen shows the 4×3 language grid.",
          "Tap your language. The interface switches immediately.",
          "The main screen opens — you are ready to use DashCast.",
        ],
      },
      note:
        "ℹ️ If you change language while a projection is running, the projection keeps going uninterrupted; only the DashCast UI is translated.",
    },

    {
      id: 'main',
      screen: 'screen-2',
      title: '2. Main screen — Apps & Cluster',
      lead:
        "The central DashCast screen. On the left, the list of every installed app with search, category filters and favourites. On the right, the live cluster preview with the main actions: full-screen preview, screenshot, reconnect, stop the projection.",
      mockupLabel: 'Open screen 2 (Main)',
      featuresTitle: 'Everything you can do',
      features: [
        {
          title: '🔍 Search bar',
          text:
            "Type a few letters to filter the list on the fly (matches both app name and package). The ▦ button on the right toggles between list and grid view.",
        },
        {
          title: '🏷️ Category filters',
          text:
            "The coloured chips (All / Navigation / Media / Communication / System) automatically group your apps. The number in parentheses tells you how many apps are visible.",
        },
        {
          title: '⭐ Pinned favourites',
          text:
            "The « Favourites » section keeps your most-used apps at the top. To add or remove a favourite: long-press the app → ⭐ Add/Remove from favourites.",
        },
        {
          title: '👆 Short tap — project',
          text:
            "Tap an app to send it to the cluster immediately. If the projection wasn't already running, it starts automatically (cluster warm-up, ~2 s).",
        },
        {
          title: '👆⏱️ Long press — actions menu',
          text:
            "Hold any app to open a full-screen menu: ⭐ Favourite, Auto-launch (start with the projection), Move to cluster / main display, ✕ Force stop.",
        },
        {
          title: '🚦 Live cluster preview',
          text:
            "The right pane mirrors what is shown on the cluster (speed, gear, battery, range). The latency value (12 ms) confirms the link is healthy.",
        },
        {
          title: '👁️ Full-screen preview',
          text:
            "Tap « Full-screen preview » to expand the live preview to the whole central display. Handy to type an address in Maps with the full keyboard: every input is mirrored to the cluster.",
        },
        {
          title: '📸 Screenshot',
          text:
            "The « Screenshot » button saves the current cluster view as a PNG into /sdcard/Pictures/DashCast/. Useful to share a route or troubleshoot a glitch.",
        },
        {
          title: '↻ Reconnect',
          text:
            "If the projected app froze or stopped responding, « Reconnect » re-establishes the video stream without touching the original cluster.",
        },
        {
          title: '⏹ Stop mirror',
          text:
            "Cleanly ends the projection. Short tap = soft stop (cluster goes back to native BYD via ADB). Long press = enriched menu with « Restore origin cluster » which forces the restore sequence using the cluster size from Settings.",
        },
      ],
      howTo: {
        title: 'How to project an app onto the cluster',
        steps: [
          "On the main screen, find the app you want (e.g. Maps).",
          "Tap its icon → projection starts, the cluster switches to the app in ~2 s.",
          "The right pane shows live what is on the cluster.",
          "To type text (search an address), tap « Full-screen preview » → the app expands to the whole central display → type your address → everything mirrors to the cluster.",
          "To stop: tap « Stop mirror » (the cluster returns to native BYD).",
        ],
      },
      tipsTitle: 'Tips',
      tips: [
        "💡 Auto-launch: enable this switch on an app to project it automatically every time DashCast starts.",
        "💡 Split mode: from the long-press menu of a second app, choose « Send as split » to display 2 apps side by side on the cluster.",
        "💡 Margins: if the app spills off the cluster, open Settings → Margins and adjust the sliders. The setting is per-app.",
        "💡 Touch full-screen: in full-screen preview mode, your fingers on the central display actually drive the app — keyboard, scroll, gestures, everything works.",
      ],
    },

    {
      id: 'settings',
      screen: 'screen-3',
      title: '3. Settings',
      lead:
        "The Settings screen gathers global options and projection image tuning. The left rail stays available — you can switch between Apps, Settings, Diag, System and Journal without losing your place.",
      mockupLabel: 'Open screen 3 (Settings)',
      featuresTitle: 'Available sections',
      features: [
        {
          title: '📺 Cluster type',
          text:
            "Pick the physical size of your instrument cluster: 8.8″ (sendInfo cmd 29), 12.3″ Seal EU (cmd 30, default), or 10.25″ (cmd 31). This value is used in particular by « Restore origin cluster » to fire the correct mode.",
        },
        {
          title: '🌐 Language',
          text:
            "12 languages available. Switch is instant — no DashCast restart needed.",
        },
        {
          title: '↔️ Horizontal margin (overscan)',
          text:
            "Slider 0–200 px. Adds black bars on the left/right to compensate for clipped edges on your cluster. The value is stored per app — Maps may use 80 px while Spotify stays at 0.",
        },
        {
          title: '↕️ Vertical margin (overscan)',
          text:
            "Slider 0–200 px. Same for top/bottom. Combined margins are applied at the VirtualDisplay level so the projected app never « sees » the clipped zones.",
        },
        {
          title: '✅ Apply / 🔄 Reset',
          text:
            "« Apply » pushes the new margins to the running projection immediately. « Reset » brings the current app back to 0/0.",
        },
        {
          title: '📦 OTA updates',
          text:
            "DashCast checks GitHub Releases for new versions automatically. Tick « Include pre-releases » to get the alpha channel (more frequent but experimental updates).",
        },
        {
          title: '🚗 Auto-start with vehicle',
          text:
            "When enabled, DashCast starts together with the car and restores the last projected app. Otherwise, you launch it manually from the BYD app drawer.",
        },
      ],
      howTo: {
        title: "How to fine-tune an app's margins",
        steps: [
          "Project the app you want to tune (e.g. Waze).",
          "Open Settings → Margins.",
          "Move the horizontal slider until the left/right edges look right.",
          "Same with the vertical slider.",
          "Tap « Apply » → the projection is updated live, no app restart.",
          "The setting is saved for that app only (each app keeps its own margins).",
        ],
      },
      note:
        "⚠️ If you change the cluster type, restart DashCast so the reference values are recomputed.",
    },

    {
      id: 'diagnostics',
      screen: 'screen-4',
      title: '4. Diagnostics',
      lead:
        "The Diagnostics tab is an internal dashboard reserved for cases where projection misbehaves. Most users will never need it — it is exposed for support and debugging.",
      mockupLabel: 'Open screen 4 (Diagnostics)',
      featuresTitle: 'Available tools',
      features: [
        {
          title: 'ClusterService state',
          text:
            "Checks that the Android service driving the projection is running. If « not bound », a button restarts it.",
        },
        {
          title: 'VirtualDisplay state',
          text:
            "Shows the ID of the virtual display created for the cluster, its resolution, and whether a Qt Surface is attached.",
        },
        {
          title: 'Local ADB connection',
          text:
            "Quick test of the ADB tunnel to localhost:5555. If the test fails, wireless debugging has usually been disabled in BYD settings.",
        },
        {
          title: 'Targeted logcat',
          text:
            "Captures the last 200 logcat lines filtered on DashCast / AutoContainer / xdja. The « Share » button sends the report.",
        },
      ],
      howTo: {
        title: 'When to use this tab',
        steps: [
          "The cluster stays black after tapping an app → check ClusterService and VirtualDisplay state.",
          "The app says « ADB unavailable » → Diag tab → « Test ADB » button.",
          "Support asks for a report → Diag → « Share logcat ».",
          "An update was just installed and you want to confirm the running version.",
        ],
      },
      note:
        "ℹ️ This tab does not change anything by itself: the buttons run read-only checks unless explicitly stated.",
    },

    {
      id: 'sysinfo',
      screen: 'screen-5',
      title: '5. System Information',
      lead:
        "Read-only dashboard about your hardware/software environment. This is where you find the DashCast version, BYD firmware, Android version and your cluster identifier.",
      mockupLabel: 'Open screen 5 (System)',
      featuresTitle: 'Information shown',
      features: [
        {
          title: '🚗 Vehicle',
          text:
            "Detected BYD model, VIN (if available), firmware build (e.g. Di3.0 / 6125F), firmware build date.",
        },
        {
          title: '📱 Android',
          text:
            "Android version (10), API level (29), security patch, DiLink build ID.",
        },
        {
          title: '🔌 DashCast',
          text:
            "Installed version, versionCode, update channel (stable / alpha), date of last OTA check, link to release notes.",
        },
        {
          title: '🖥️ Cluster',
          text:
            "Detected type (8.8″ / 12.3″ / 10.25″), real resolution, current VirtualDisplay ID, active Qt package (com.xdja.containerservice).",
        },
        {
          title: '📦 Tracked apps',
          text:
            "Number of apps detected by DashCast, number of pinned favourites, number of apps with auto-launch enabled.",
        },
      ],
      tipsTitle: 'Tips',
      tips: [
        "💡 Long-press a row to copy the value to the clipboard (handy for a bug report).",
        "💡 The « Export » button at the bottom dumps everything to a text file (/sdcard/DashCast/sysinfo.txt).",
      ],
    },

    {
      id: 'journal',
      screen: 'screen-6',
      title: '6. Journal',
      lead:
        "DashCast's internal log: traces every important action (projections, restorations, ADB errors, updates). Useful to understand unexpected behaviour or send a report to support.",
      mockupLabel: 'Open screen 6 (Journal)',
      featuresTitle: 'Features',
      features: [
        {
          title: '🔍 Filter',
          text:
            "Type a keyword to keep only relevant lines (e.g. « ADB », « Maps », « error »). The filter is case-insensitive.",
        },
        {
          title: '🎨 Colour code',
          text:
            "🟢 INFO (green) — normal operation. 🟠 WARN (orange) — attention. 🔴 ERROR (red) — something failed. ⚪ DEBUG (grey) — technical detail.",
        },
        {
          title: '🗑 Clear',
          text:
            "Empties the journal. The system logcat trace is not affected — only the in-memory DashCast history is cleared.",
        },
        {
          title: '📤 Share',
          text:
            "Exports the current journal as .txt and opens the Android share sheet (e-mail, Telegram, file). Automatically includes the DashCast version and BYD model.",
        },
        {
          title: '⏰ Timestamps',
          text:
            "Each line is prefixed with the local time (HH:mm:ss.mmm). Long-running operations (Maps launch, cluster restore) are measured and shown.",
        },
      ],
      howTo: {
        title: 'How to send a bug report',
        steps: [
          "Reproduce the issue (e.g. the app stays black after launch).",
          "Open Journal.",
          "Tap « Share ».",
          "Pick your channel (Telegram, e-mail, GitHub Issues).",
          "The attached .txt holds the full trace plus context (version, model, firmware).",
        ],
      },
      note:
        "🔒 No personal data (contacts, GPS location, app content) is logged — only DashCast actions and technical return codes.",
    },
  ],

  faq: {
    title: '7. FAQ — frequent questions',
    items: [
      {
        question: '❓ The cluster stays black when I tap an app',
        answer:
          "Three possible causes: (1) wireless ADB disabled — check BYD Settings → Developer. (2) ClusterService not running — Diag tab, « Restart » button. (3) The app just crashed — tap « Reconnect » in the right pane.",
      },
      {
        question: '❓ The image overflows / is clipped on the cluster',
        answer:
          "Open Settings → Margins and adjust the horizontal/vertical sliders until the edges look right. The setting is stored for that app only — you only do it once.",
      },
      {
        question: '❓ How do I get back to the original BYD dashboard?',
        answer:
          "A short tap on « Stop mirror » is enough 95 % of the time. If the cluster is stuck, long-press the same button → menu → « Restore origin cluster »: DashCast forces the sendInfo sequence matching your cluster type.",
      },
      {
        question: '❓ Will DashCast drain the 12 V battery?',
        answer:
          "No — DashCast stops automatically when the car powers off (Android.intent.action.SCREEN_OFF + BMS disconnect broadcasts). No background service stays alive after the engine is off.",
      },
      {
        question: '❓ I want to contribute or report a bug',
        answer:
          "GitHub: https://github.com/Kiroha/byd-dashcast — Issues for bugs, Discussions for questions. Always attach a Journal export (Journal tab → Share) to speed up the diagnosis.",
      },
      {
        question: '❓ Which apps work on the cluster?',
        items: [
          "✅ Navigation: Google Maps, Waze, Yandex Navi, OsmAnd, Magic Earth.",
          "✅ Media: Spotify, YouTube, YouTube Music, Netflix (prefer landscape).",
          "✅ Communication: Telegram (read-only mode), WhatsApp (notifications).",
          "✅ System: camera, weather, calendar.",
          "⚠️ Apps using Widevine L1 DRM (Disney+, Prime Video) may refuse to render on a VirtualDisplay — this is an Android limitation, not DashCast.",
        ],
      },
      {
        question: '❓ Updates: stable or alpha?',
        answer:
          "The stable channel (default) is tested on a vehicle for at least 1 week before publishing. The alpha channel (enable in Settings → Updates) gets new builds as soon as they are compiled — handy to test ahead, but may introduce temporary regressions.",
      },
    ],
  },

  footer:
    'DashCast is an open-source project distributed under the GPL-3.0 licence. Not affiliated with BYD Auto Co., Ltd.',
};
