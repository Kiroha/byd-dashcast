export default {
  "code": "en",
  "flag": "🇬🇧",
  "name": "English",
  "title": "DashCast — User Manual",
  "meta": "v0.7.2 · BYD Seal EU · DiLink 3.0 · Android 10",
  "manualName": "User Manual",
  "tocTitle": "📋 Table of Contents",
  "sections": [
    "1. Overview",
    "2. First Launch — Language Selection",
    "3. Main Screen",
    "4. Projecting an App to the Dashboard",
    "5. During Projection — Control Panel",
    "6. Stopping the Projection",
    "7. Settings",
    "8. ⋮ Menu — Additional Tools",
    "9. FAQ & Troubleshooting"
  ],
  "overview": {
    "title": "1. Overview",
    "text": "DashCast is an Android app that lets you project any app from the infotainment screen onto the digital instrument cluster of your BYD. Navigation, music, videos — anything running on the central screen can be redirected to the cluster.",
    "bullets": [
      "✅ Compatible with BYD Seal EU (DiLink 3.0, firmware Di3.0 / 6125F)",
      "✅ No system modification required",
      "✅ Local ADB (TCP, localhost) — no computer needed once configured",
      "✅ Automatic app disconnection detection",
      "✅ Built-in OTA (Over-The-Air) updates",
      "✅ Overscan saved independently per application",
      "✅ Grid or List display mode",
      "✅ Emergency force-stop (Red Cross) per application"
    ],
    "note": "💡 Prerequisite: Enable TCP ADB debugging in Settings → Developer → Wireless debugging. This is a one-time step. On first launch, an 'Allow debugging?' popup will appear — tap Always allow from this device."
  },
  "firstLaunch": {
    "title": "2. First Launch — Language Selection",
    "text": "On first launch, the welcome screen appears. Choose your language by tapping one of the available buttons. This choice is remembered — you won't see this screen again unless you change language from the ⋮ menu.",
    "welcomeSubtitle": "Dashboard Controller",
    "welcomeHint": "Choisissez votre langue\nPlease select your language",
    "caption": "Language selection screen — shown only on first launch"
  },
  "main": {
    "title": "3. Main Screen",
    "text": "The main screen has two areas: a status bar at the top (dark blue background) and a list of installed apps below. You can switch between list and grid (icons) view via the ⋮ menu.",
    "status": "① Dashboard: not connected",
    "buttons": [
      "② Activate Projection",
      "③ Stop Projection",
      "④ Restore Original Dashboard",
      "⑤ ⋮",
      "✕",
      "✕",
      "✕",
      "✕"
    ],
    "listTitle": "⑥ Installed apps",
    "apps": [
      "Maps",
      "YouTube",
      "Spotify",
      "Waze"
    ],
    "caption": "Main screen — no app projected (initial state)",
    "annotations": [
      {
        "tone": "",
        "marker": "①",
        "label": "Status",
        "text": "Shows the connection state to the dashboard. Changes to 'Dashboard: [AppName]' when an app is active."
      },
      {
        "tone": "",
        "marker": "②",
        "label": "Activate Projection",
        "text": "Establishes the connection to the cluster and prepares app casting. Tap this first."
      },
      {
        "tone": "red",
        "marker": "③",
        "label": "Stop Projection",
        "text": "Stops the ongoing projection without restoring the original BYD dashboard."
      },
      {
        "tone": "green",
        "marker": "④",
        "label": "Restore Original Dashboard",
        "text": "Stops the projection AND restores the native BYD dashboard (speed, gauges…)."
      },
      {
        "tone": "gray",
        "marker": "⑤",
        "label": "⋮ Menu",
        "text": "Access to Settings, Diagnostics, System Report, Logs, language change and Grid/List toggle."
      },
      {
        "tone": "gray",
        "marker": "⑥",
        "label": "App list",
        "text": "All installed apps. Tap an app to project it, ✕ to close it, ❌ to force-stop the process entirely."
      }
    ]
  },
  "projection": {
    "title": "4. Projecting an App to the Dashboard",
    "steps": [
      "Tap 'Activate Projection' (blue button at the top). The status changes to 'Starting cluster…'. The local ADB connection is established and the cluster enters projection mode.",
      "Tap the desired app in the list. DashCast moves the app to the dashboard display. The status changes to 'Dashboard: [App name]'.",
      "The control panel appears at the bottom of the screen. Overscan values saved for this app are applied automatically."
    ],
    "activeStatus": "Dashboard: Maps ✓",
    "buttons": [
      "Activate Projection",
      "📺 Mirror",
      "Stop Projection",
      "Restore Original Dashboard",
      "⋮",
      "← Main",
      "✕",
      "→ Cluster",
      "✕",
      "→ Cluster",
      "✕",
      "📐 Adjust",
      "⬛⬛ Split",
      "Hide ▼"
    ],
    "listTitle": "Installed apps",
    "apps": [
      "Maps",
      "YouTube",
      "Spotify"
    ],
    "controlLabel": "Cluster control",
    "controlApp": "Maps",
    "mirrorText": "Display active on cluster ✓",
    "caption": "Main screen — Maps is projected on the instrument cluster",
    "annotations": [
      {
        "tone": "green",
        "marker": "●",
        "label": "Green bar",
        "text": "Visual indicator on each item: the app is currently on the cluster."
      },
      {
        "tone": "",
        "marker": "→",
        "label": "→ Cluster",
        "text": "Sends another app to the cluster (replaces the current one)."
      },
      {
        "tone": "gray",
        "marker": "←",
        "label": "← Main",
        "text": "Returns the app to the central screen (removes it from the cluster)."
      },
      {
        "tone": "teal",
        "marker": "📺",
        "label": "Mirror",
        "text": "Shows a live preview of the dashboard content inside DashCast."
      },
      {
        "tone": "red",
        "marker": "❌",
        "label": "Red Cross (Force Stop)",
        "text": "Force-stops a frozen app's process entirely and removes it from Recents."
      },
      {
        "tone": "gray",
        "marker": "🔲",
        "label": "Grid / List View",
        "text": "Toggle between classic list and icon grid view via the ⋮ menu."
      }
    ]
  },
  "control": {
    "title": "5. During Projection — Control Panel",
    "intro": "When an app is active on the cluster, a dark panel appears at the bottom of the main screen with four controls:",
    "mirror": {
      "title": "5.1 Mirror (📺 Mirror)",
      "text": "Tap 📺 Mirror in the status bar to display a live copy of the cluster inside DashCast. You can interact with this copy by touch — events are forwarded to the cluster display.",
      "note": "The mirror uses SurfaceControl to capture the display. If unavailable, an automatic screenshot every 2 seconds is used as fallback."
    },
    "resize": {
      "title": "5.2 Adjust (📐 Per-app Overscan)",
      "text": "The 📐 Adjust button reveals two sliders: Width Margin and Height Margin. These crop the edges of the projected image on the cluster. Values are saved per application and automatically re-applied on each launch via wm overscan.",
      "note": "💡 Recommended values for Seal EU: Width 80 px, Height 50 px."
    },
    "split": {
      "title": "5.3 Split Mode (⬛⬛ Split)",
      "text": "Tap ⬛⬛ Split to share the cluster between two apps:",
      "items": [
        "Full screen — One app occupies the entire cluster",
        "⬜⬛ Left (50%) — Main app on the left, second app on the right",
        "⬛⬜ Right (50%) — Main app on the right"
      ],
      "extra": "In Split mode, a second app can be selected from the list. It will occupy the other half of the cluster."
    },
    "hide": {
      "title": "5.4 Hide the panel",
      "text": "Tap Hide ▼ to collapse the control panel and return to the full app list."
    }
  },
  "stopping": {
    "title": "6. Stopping the Projection",
    "intro": "Two buttons allow stopping the projection:",
    "table": {
      "headers": [
        "Button",
        "Behavior",
        "When to use"
      ],
      "rows": [
        [
          "Stop Projection",
          "Ends the projection. The dashboard stays blank (black).",
          "If you just want to temporarily stop the display."
        ],
        [
          "Restore Original Dashboard",
          "Ends the projection AND restores the native BYD display (speed, range, gauges…).",
          "At the end of use — to get the normal BYD dashboard back."
        ]
      ]
    },
    "warning": "⚠️ If you exit DashCast without tapping one of these buttons, the projection stays active on the cluster until the next service restart."
  },
  "settings": {
    "title": "7. Settings",
    "intro": "Access Settings via ⋮ → ⚙️ Settings. The screen has two sections:",
    "titleLabel": "Settings",
    "clusterTypeLabel": "Cluster type",
    "clusterOptions": [
      "8.8 inch (cmd=29)",
      "12.3 inch (cmd=30) — Seal EU",
      "10.25 inch (cmd=31)"
    ],
    "marginsLabel": "Display margins (global overscan)",
    "horizontalMarginLabel": "Left / Right:",
    "verticalMarginLabel": "Top / Bottom:",
    "applyButton": "Apply now",
    "resetButton": "Reset (80 / 50)",
    "caption": "Settings page",
    "type": {
      "title": "7.1 Cluster type",
      "text": "Select the screen size of your dashboard. For BYD Seal EU, select 12.3 inch (cmd=30). This setting determines the command sent to the cluster on activation."
    },
    "margins": {
      "title": "7.2 Global display margins (overscan)",
      "text": "Adjust the margins to perfectly frame the content within the visible screen area. These margins apply globally. For per-app settings, use the 📐 Adjust button in the control panel.",
      "items": [
        "Left / Right — Horizontal margin (0–200 px on each side)",
        "Top / Bottom — Vertical margin (0–200 px top and bottom)"
      ],
      "applyText": "Click Apply now to see the result immediately if an app is being projected. Values are saved between sessions.",
      "note": "💡 Recommended defaults for Seal EU: Left/Right = 80 px, Top/Bottom = 50 px."
    }
  },
  "tools": {
    "title": "8. ⋮ Menu — Additional Tools",
    "intro": "The ⋮ button in the top right opens a menu with the following entries:",
    "table": {
      "headers": [
        "Option",
        "Description"
      ],
      "rows": [
        [
          "⚙️ Settings",
          "Cluster type + global overscan margin settings"
        ],
        [
          "🔲 Grid / List",
          "Toggle app display between classic list and icon grid (5 columns)"
        ],
        [
          "🔧 Diagnostics",
          "Advanced developer tools — ADB connection, displays, cluster screen size, ADB sniffer"
        ],
        [
          "📋 System Report",
          "Generates a full report (displays, BYD APIs, permissions) — useful for support"
        ],
        [
          "📜 Logs",
          "Real-time log viewer — filter by tag/level, share via email or file (long-press for Telegram)"
        ],
        [
          "🌐 Language",
          "Returns to the language selection screen"
        ]
      ]
    },
    "logs": {
      "title": "Log Viewer (📜 Logs)",
      "header": "📋 Log Viewer",
      "clearButton": "Clear",
      "shareButton": "Share",
      "filterPlaceholder": "Filter (tag / message / level)…",
      "lines": [
        "[INFO ] ClusterService → Cluster display connected: id=1",
        "[INFO ] launchOnDashboard OK → com.google.android.apps.maps",
        "[DEBUG] watchdog started for com.google.android.apps.maps pid=4821",
        "[WARN ] setTaskWindowingMode: SecurityException (expected)",
        "[INFO ] wm overscan applied on display 1 inset=80,50"
      ],
      "caption": "Log viewer — real-time filter, export available"
    }
  },
  "faq": {
    "title": "9. FAQ & Troubleshooting",
    "items": [
      {
        "question": "❓ The 'Allow ADB debugging?' popup does not appear",
        "answer": "Make sure TCP ADB debugging is enabled in the infotainment developer settings. If the option is missing, enable developer mode first (tap the build number 7 times in About).",
        "items": []
      },
      {
        "question": "❓ The app does not appear on the cluster after selection",
        "answer": "",
        "items": [
          "Make sure you tapped Activate Projection before selecting the app.",
          "Some apps refuse to launch on a secondary display (internal restrictions). Check the Logs for the error message.",
          "Try closing and reopening DashCast, then repeat the sequence."
        ]
      },
      {
        "question": "❓ The content is cropped or offset on the cluster",
        "answer": "Use the 📐 Adjust button in the control panel to fine-tune the margins per app. Global margins in Settings apply as a fallback.",
        "items": []
      },
      {
        "question": "❓ An app is frozen / stuck on the cluster",
        "answer": "Tap the ❌ Red Cross next to the app in the list. This force-stops the process entirely and cleans up Recents. The app is then ready to relaunch.",
        "items": []
      },
      {
        "question": "❓ The ← Main and ✕ buttons remain visible after closing the app",
        "answer": "DashCast automatically detects app closure (via /proc monitoring). If the UI stays stuck, tap Stop Projection to force a reset.",
        "items": []
      }
    ]
  },
  "footer": "DashCast v0.7.2 — BYD Seal EU · DiLink 3.0 · Android 10 · github.com/Kiroha/byd-dashcast"
};
