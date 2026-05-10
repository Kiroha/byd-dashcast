export default {
  "code": "en",
  "flag": "🇬🇧",
  "name": "English",
  "title": "DashCast — User Manual",
  "meta": "v0.5.0-beta · BYD Seal EU · DiLink 3.0 · Android 10",
  "manualName": "User Manual",
  "tocTitle": "📋 Contents",
  "sections": [
    "1. Overview",
    "2. First Launch — Language Selection",
    "3. Main Screen",
    "4. Projecting an App onto the Dashboard",
    "5. During Projection — Control Panel",
    "6. Stopping the Projection",
    "7. Settings",
    "8. ⋮ Menu — Additional Tools",
    "9. FAQ & Troubleshooting"
  ],
  "overview": {
    "title": "1. Overview",
    "text": "DashCast is an Android application that lets you project any app from the infotainment screen onto the digital instrument cluster of your BYD vehicle. Navigation, music, videos — anything running on the centre screen can be redirected to the driver-facing cluster display.",
    "bullets": [
      "✅ Compatible with BYD Seal EU (DiLink 3.0, firmware Di3.0 / 6125F)",
      "✅ No system modification required",
      "✅ Local ADB over TCP (localhost) — no PC needed once set up",
      "✅ Automatic detection when an app is closed externally"
    ],
    "note": "💡 Prerequisite: Enable TCP ADB debugging in Settings → Developer options → Wireless debugging (or \"ADB over network\"). This is a one-time step. On first DashCast launch, an \"Allow USB debugging?\" popup appears — tap Always allow from this computer."
  },
  "firstLaunch": {
    "title": "2. First Launch — Language Selection",
    "text": "On first launch, the welcome screen is shown. Tap one of the ten buttons to choose your language. This choice is saved — you will not see this screen again unless you change the language from the ⋮ menu.",
    "welcomeSubtitle": "Dashboard Controller",
    "welcomeHint": "Choisissez votre langue\nPlease select your language",
    "caption": "Language selection screen — shown only on first launch"
  },
  "main": {
    "title": "3. Main Screen",
    "text": "The main screen has two zones: a status bar at the top (dark blue) and a list of installed apps below.",
    "status": "① Dashboard: not connected",
    "buttons": [
      "② Activate Projection",
      "③ Stop Projection",
      "④ Restore Original Dashboard",
      "⑤ ⋮",
      "✕",
      "✕",
      "✕"
    ],
    "listTitle": "⑥ Installed apps",
    "apps": [
      "Maps",
      "YouTube",
      "Spotify"
    ],
    "caption": "Main screen — no app projected (initial state)",
    "annotations": [
      {
        "tone": "",
        "marker": "①",
        "label": "Status",
        "text": "Shows the cluster connection state. Changes to \"Dashboard: [AppName]\" when an app is active."
      },
      {
        "tone": "",
        "marker": "②",
        "label": "Activate Projection",
        "text": "Connects to the cluster and prepares it for app projection. Tap this first."
      },
      {
        "tone": "red",
        "marker": "③",
        "label": "Stop Projection",
        "text": "Ends the current projection without restoring the BYD dashboard."
      },
      {
        "tone": "green",
        "marker": "④",
        "label": "Restore Original Dashboard",
        "text": "Ends projection AND restores the native BYD cluster (speed, gauges…)."
      },
      {
        "tone": "gray",
        "marker": "⑤",
        "label": "⋮ Menu",
        "text": "Access Settings, Diagnostic, System Report, Logs, and language change."
      },
      {
        "tone": "gray",
        "marker": "⑥",
        "label": "App list",
        "text": "All installed apps. Tap to project, or tap ✕ to force-close."
      }
    ]
  },
  "projection": {
    "title": "4. Projecting an App onto the Dashboard",
    "steps": [
      "Tap \"Activate Projection\" (blue button). The status changes to \"Cluster starting…\". The local ADB connection is established and the cluster enters projection mode.",
      "Tap the desired app in the list. DashCast moves the app to the cluster display. The status changes to \"Dashboard: [App Name]\".",
      "The control panel appears at the bottom of the screen, allowing you to interact with the projected app from the main screen."
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
      "📐 Resize",
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
        "tone": "red",
        "marker": "❌",
        "label": "Red Cross (Force Stop)",
        "text": "Force stops a stubborn application (kills the process gracefully in the system) and clears it from the Recents menu."
      },
      {
        "tone": "gray",
        "marker": "🔲",
        "label": "Grid / List View",
        "text": "You can toggle the application display between a classic list or a grid (icons) via the app settings."
      }
    ]
  },
  "control": {
    "title": "5. During Projection — Control Panel",
    "intro": "When an app is active on the cluster, a dark panel appears at the bottom of the main screen with three remote-control features:",
    "mirror": {
      "title": "5.1 Mirror (📺 Mirror)",
      "text": "Tap 📺 Mirror in the status bar to display a live copy of the cluster content inside DashCast. You can interact with this copy by touch — events are forwarded to the cluster display.",
      "note": "The mirror uses SurfaceControl to capture the display. If the mirror is unavailable, an automatic screenshot every 2 seconds is used as fallback."
    },
    "split": {
      "title": "5.2 Split Mode (⬛⬛ Split)",
      "text": "Tap ⬛⬛ Split to share the cluster between two apps:",
      "items": [
        "Full screen — One app occupies the entire cluster",
        "⬜⬛ Left (50%) — Main app on the left, second app on the right",
        "⬛⬜ Right (50%) — Main app on the right"
      ],
      "extra": ""
    },
    "hide": {
      "title": "5.3 Hide Panel",
      "text": "Tap Hide ▼ to collapse the control panel and see the full app list."
    }
  },
  "stopping": {
    "title": "6. Stopping the Projection",
    "intro": "",
    "table": {
      "headers": [
        "Button",
        "Behaviour",
        "When to use"
      ],
      "rows": [
        [
          "Stop Projection",
          "Ends projection. Cluster goes blank.",
          "When you want to temporarily stop the display."
        ],
        [
          "Restore Original Dashboard",
          "Ends projection AND restores the BYD native cluster (speed, range, gauges…).",
          "At end of use — to return to the normal BYD dashboard."
        ]
      ]
    },
    "warning": "⚠️ If you exit DashCast without pressing one of these buttons, the projection remains active on the cluster until the service is next restarted."
  },
  "settings": {
    "title": "7. Settings",
    "intro": "Access Settings via ⋮ → ⚙️ Settings.",
    "titleLabel": "Settings",
    "clusterTypeLabel": "Cluster type",
    "clusterOptions": [
      "8.8 inches (cmd=29)",
      "12.3 inches (cmd=30) — Seal EU",
      "10.25 inches (cmd=31)"
    ],
    "marginsLabel": "Display margins (overscan)",
    "horizontalMarginLabel": "Left / Right:",
    "verticalMarginLabel": "Top / Bottom:",
    "applyButton": "Apply now",
    "resetButton": "Reset (80 / 50)",
    "caption": "Settings screen",
    "type": {
      "title": "7.1 Cluster Type",
      "text": "Select the screen size of your instrument cluster. For the BYD Seal EU, select 12.3 inches (cmd=30)."
    },
    "margins": {
      "title": "7.2 Display Margins (Overscan)",
      "text": "Adjust margins to frame content within the visible area of the cluster screen. Curved-glass clusters often have physical edges that extend beyond the usable display area.",
      "items": [
        "Left / Right — Horizontal margin (0–200 px on each side)",
        "Top / Bottom — Vertical margin (0–200 px top and bottom)"
      ],
      "applyText": "Click Apply now to see the result immediately if an app is currently projected. Values are saved between sessions.",
      "note": "💡 Recommended defaults for the Seal EU: Left/Right = 80 px, Top/Bottom = 50 px."
    }
  },
  "tools": {
    "title": "8. ⋮ Menu — Additional Tools",
    "intro": "",
    "table": {
      "headers": [
        "Option",
        "Description"
      ],
      "rows": [
        [
          "⚙️ Settings",
          "Cluster type + overscan margin adjustment"
        ],
        [
          "🔧 Diagnostic",
          "Advanced developer tests — ADB connection, displays, cluster screen size"
        ],
        [
          "📋 System Report",
          "Generates a full report (displays, BYD APIs, permissions) — useful for support"
        ],
        [
          "📜 Logs",
          "Real-time log viewer — filter by tag/level, share by email or file"
        ],
        [
          "🌐 Language",
          "Returns to the language selection screen"
        ]
      ]
    },
    "logs": null
  },
  "faq": {
    "title": "9. FAQ & Troubleshooting",
    "items": [
      {
        "question": "❓ The \"Allow USB debugging?\" popup does not appear",
        "answer": "Check that TCP ADB debugging is enabled in the infotainment developer settings. If the option is missing, first enable developer mode (tap the build number 7 times in About).",
        "items": []
      },
      {
        "question": "❓ The app does not appear on the cluster after selection",
        "answer": "",
        "items": [
          "Make sure you tapped Activate Projection before selecting the app.",
          "Some apps refuse to launch on a secondary display. Check the Logs for the error message.",
          "Try closing and reopening DashCast, then repeat the sequence."
        ]
      },
      {
        "question": "❓ Content is clipped or offset on the cluster",
        "answer": "Adjust the display margins in ⋮ → Settings. Increase Left/Right if content overflows horizontally, Top/Bottom if it overflows vertically. Click Apply now to see the result immediately.",
        "items": []
      },
      {
        "question": "❓ The \"← Main\" and \"✕\" buttons remain visible after closing the app",
        "answer": "DashCast automatically detects app termination (via /proc monitoring). If the UI is stuck, tap Stop Projection to force a reset.",
        "items": []
      },
      {
        "question": "❓ After restarting the car, do I need to reconfigure everything?",
        "answer": "No. The cluster type and overscan margins are saved. The last projected app is also remembered. Only the ADB connection may require tapping Activate Projection again.",
        "items": []
      }
    ]
  },
  "footer": "DashCast · User Manual · English · github.com/Kiroha/byd-dashcast"
};
