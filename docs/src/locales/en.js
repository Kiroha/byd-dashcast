export default {
  code: 'en',
  flag: '🇬🇧',
  name: 'English',
  title: 'DashCast — User Manual',
  manualName: 'User Manual',
  meta: 'v1.4.x · BYD Seal EU · DiLink 3.0 · Android 10',
  tocTitle: '📋 Table of contents',

  intro: {
    title: '0. Introduction',
    lead:
      'DashCast displays any Android application from your BYD central screen on the instrument cluster (digital gauge display). Maps, Waze, Spotify or ABRP right behind the steering wheel — and with Layouts mode, several apps at once, each in its own zone. All without modifying the system.',
    bullets: [
      '✅ Compatible with BYD Seal EU (DiLink 3.0, firmware Di3.0 / 6125F).',
      '✅ No system modification: DashCast installs like a regular app.',
      '✅ Local ADB over TCP — no computer needed after the first authorisation.',
      '✅ 13 interface languages, chosen at first launch.',
      '✅ Real-time touch mirror: control the cluster from the central screen.',
      '✅ Layouts mode: several apps side by side on the cluster, zones drawn with a finger.',
      '✅ Auto-start: projection + app (or favourite layout) as soon as DashCast opens.',
      '✅ Margins (overscan) remembered per application.',
      '✅ Built-in OTA updates (optional beta channel).',
    ],
    note:
      '💡 Single prerequisite: enable wireless ADB debugging in BYD Settings → Developer. On first launch, an “Allow debugging?” dialog appears — tick “Always allow” and confirm. You will never have to do this again.',
  },

  sections: [
    {
      id: 'welcome',
      screen: 'screen-1',
      title: '1. Welcome screen — language selection',
      lead:
        'On the very first launch, DashCast shows a grid with the 13 available languages. Tap yours: the choice is remembered and the screen never reappears. You can change the language at any time in Settings.',
      mockupLabel: 'View screen 1 (Welcome)',
      featuresTitle: 'Details',
      features: [
        {
          title: '13 supported languages',
          text:
            "Français, English, Deutsch, Italiano, Türkçe, Español, Polski, Русский, Українська, العربية, O'zbekcha, Қазақша, Беларуская. The chosen language is applied immediately, no restart needed.",
        },
        {
          title: 'Automatic reading direction',
          text:
            'Arabic automatically switches to right-to-left (RTL) layout: the navigation bar moves to the right and lists are mirrored.',
        },
        {
          title: 'Changeable at any time',
          text: 'To change the language later: Settings → Language. Applied on the fly.',
        },
      ],
      howTo: {
        title: 'How to',
        steps: [
          'Launch DashCast (blue icon in the BYD app drawer).',
          'The welcome screen appears with the language grid.',
          'Tap your language. The interface switches immediately.',
          'The main screen opens — you are ready.',
        ],
      },
    },

    {
      id: 'main',
      screen: 'screen-2',
      title: '2. Main screen — Apps & Cluster',
      lead:
        'DashCast’s central screen. Left: all your applications with search, filters and favourites. Right: the real-time cluster preview, the Fullscreen mirror / Stop projection buttons, and the layout carousel to pick your favourite multi-app arrangement.',
      mockupLabel: 'View screen 2 (Main)',
      featuresTitle: 'Everything you can do',
      features: [
        {
          title: '👆 Short tap — project',
          text:
            'Tap an app to send it to the cluster. If projection is not active, it starts automatically (~2 s warm-up), then the app appears behind the wheel.',
        },
        {
          title: '👆⏱️ Long press — action menu',
          text:
            'Hold an app: ⭐ Favourite, Auto-launch (project this app on every DashCast start), Move to cluster / main screen, ✕ Force stop.',
        },
        {
          title: '🔍 Search & filters',
          text:
            'The search bar filters as you type (name or package). Category chips (All / Navigation / Media…) group your apps; the ▦ button toggles list/grid.',
        },
        {
          title: '🚦 Real-time cluster preview',
          text:
            'The right panel mirrors what is on the gauge display, live. Your touches on the preview are forwarded to the projected app — scroll, zoom, keyboard, everything works.',
        },
        {
          title: '👁️ Fullscreen mirror',
          text:
            'Expands the preview to the whole central screen: ideal for typing an address in Maps with the full keyboard. Everything is replicated on the cluster in real time.',
        },
        {
          title: '⏹ Stop projection',
          text:
            'Cleanly ends the projection and restores the original BYD dashboard (speed, gauges, ADAS) with the screen size set in Settings.',
        },
        {
          title: '🗂️ Layout carousel',
          text:
            'Below the buttons, each card shows a mini preview of a layout’s zones. Tap a card to make it the favourite layout (star + blue border). “Free mode” disables layouts; “＋ Manage” opens the editor.',
        },
        {
          title: '📺 Floating button',
          text:
            'A 📺 button persists on top of other apps: tap = open the mirror, long press = quick-switch between recently projected apps.',
        },
      ],
      howTo: {
        title: 'How to project an app onto the cluster',
        steps: [
          'Find the app you want (e.g. Maps) — search or filters if needed.',
          'Tap its icon → projection starts and the cluster switches to the app in ~2 s.',
          'The right preview shows live what is on the cluster.',
          'To type text: “Fullscreen mirror” → type your address → everything is replicated.',
          'To stop: “Stop projection” — the cluster returns to native BYD.',
        ],
      },
      tipsTitle: 'Tips',
      tips: [
        '💡 Auto-launch: pick one app (long press → Auto-launch) to have it projected automatically on every DashCast start — projection activates by itself.',
        '💡 Favourite layout: the card selected in the carousel is the one auto-start will activate (see the Layouts section).',
        '💡 Margins: if the app overflows the cluster, Settings → Margins, horizontal/vertical sliders. Remembered per app.',
      ],
    },

    {
      id: 'settings',
      screen: 'screen-3',
      title: '3. Settings',
      lead:
        'Global options: cluster size, language, margins, startup behaviour, Layouts mode and updates. The side bar stays available — switch screens without losing your place.',
      mockupLabel: 'View screen 3 (Settings)',
      featuresTitle: 'Main sections',
      features: [
        {
          title: '📺 Cluster type',
          text:
            'Physical size of your gauge display: 8.8″, 12.3″ (recommended on Seal EU — fixes ADAS stretching) or 10.25″. Used by “Stop projection” to restore the right mode.',
        },
        {
          title: '↔️↕️ Margins (overscan)',
          text:
            'Horizontal/vertical sliders (0–200 px) to compensate clipped edges. Remembered per application: Maps can have 80 px while Spotify stays at 0. “Apply” adjusts the live projection.',
        },
        {
          title: '🚗 Start with the vehicle',
          text:
            'If enabled, DashCast starts with the car and restores the last projected app. Otherwise launch it from the BYD drawer.',
        },
        {
          title: '🗂️ Layouts mode',
          text:
            'Enables multi-app projection with custom zones (requires the Proxy ADB Daemon, managed automatically). Shows the carousel on the main screen and the Layouts tab.',
        },
        {
          title: '⭐ Auto favourite layout',
          text:
            'On DashCast launch: activates cluster projection, the favourite layout, then launches the apps bound to each zone. Your full multi-app setup, without a single tap.',
        },
        {
          title: '⚡ Pre-create slots at startup',
          text:
            'Prepares the favourite layout’s virtual displays as soon as DashCast opens (without launching the apps) — activating the layout is then near-instant.',
        },
        {
          title: '📦 OTA updates',
          text:
            'DashCast checks GitHub for new versions on every launch. Tick “Include pre-releases” for the beta channel (features earlier, less stability).',
        },
        {
          title: '🌐 Language',
          text: '13 languages — switching is instant.',
        },
      ],
      howTo: {
        title: 'How to adjust an app’s margins',
        steps: [
          'Project the app to adjust (e.g. Waze).',
          'Settings → Margins.',
          'Move the horizontal slider until the left/right edges are correct.',
          'Same for vertical, then “Apply” — live adjustment, no app restart.',
          'The setting is saved for this app only.',
        ],
      },
      note:
        '⚠️ If you change the cluster type, stop and restart the projection so the restore uses the right mode.',
    },

    {
      id: 'layouts',
      screen: 'screen-7',
      title: '4. Layouts — several apps on the cluster',
      lead:
        'Layouts mode splits the cluster into custom zones, each showing its own application: Waze on the left, Spotify on the right, for example. You draw the zones with a finger, bind an app to each zone, and the layout activates in one tap — or by itself at startup.',
      mockupLabel: 'View screen 7 (Layouts)',
      featuresTitle: 'Features',
      features: [
        {
          title: '✏️ Draw a zone',
          text:
            'On the canvas (a replica of the 1920×720 cluster), drag your finger to draw a rectangle. A dialog opens: name, pixel-precise position/dimensions, and the app to bind.',
        },
        {
          title: '🔗 Bind an application',
          text:
            'Each zone can be bound to an app: when the layout activates, the app launches automatically in its zone. A zone without an app stays free — place anything there later.',
        },
        {
          title: '✋ Move & resize',
          text:
            'Drag a zone to move it; the white corner handles resize it. Edges snap automatically to the cluster borders and to neighbouring zones.',
        },
        {
          title: '✏️ Edit an existing zone',
          text:
            'Tap a zone (on the canvas or its chip below): rename it, adjust its geometry, change the bound app, or delete it. Long press a zone = quick delete.',
        },
        {
          title: '💾 Saved layouts',
          text:
            'Save as many layouts as you like (“Nav+Media”, “Triple screen”…). The side panel lists them with Activate / Deactivate / Edit / Delete.',
        },
        {
          title: '⭐ Favourite & auto-start',
          text:
            'The “Favourite” button (or a tap on the main-screen carousel card) designates the layout that “Auto favourite layout” will activate on DashCast launch — projection included.',
        },
      ],
      howTo: {
        title: 'Create your first layout',
        steps: [
          'Enable “Layouts mode” in Settings.',
          'Open the Layouts tab (side bar).',
          'Drag your finger on the canvas to draw the first zone (e.g. left half).',
          'In the dialog: name it, tap “Link an application” → pick Waze → Add.',
          'Draw the second zone (right half), bind Spotify.',
          '“Save” → name the layout (e.g. Nav+Media).',
          '“Favourite” to select it, then activate it: both apps launch, each in its zone.',
        ],
      },
      tipsTitle: 'Tips',
      tips: [
        '💡 Combined with “Auto favourite layout” (Settings), your full multi-app setup builds itself on every DashCast start.',
        '💡 The mini preview on each carousel card shows the layout’s actual zones — recognisable at a glance.',
        '💡 An app refuses to display in its zone? Some apps enforce their aspect ratio; try a zone closer to 16:9.',
      ],
      note:
        'ℹ️ Layouts mode relies on the Proxy ADB Daemon (started automatically). First cold start: allow 6–8 s before apps appear — that is the cluster activation sequence.',
    },

    {
      id: 'diagnostics',
      screen: 'screen-4',
      title: '5. Diagnostics',
      lead:
        'Internal dashboard for situations where projection does not work as expected. Most users will never need it — it exists for support and debugging.',
      mockupLabel: 'View screen 4 (Diagnostics)',
      featuresTitle: 'Available tools',
      features: [
        {
          title: 'Connection tests',
          text:
            'Checks the local ADB tunnel (localhost:5555), the ClusterService state and the presence of the cluster’s virtual display. Each test shows a structured result.',
        },
        {
          title: 'Platform probes',
          text:
            'DiLink detection (2/3/4/5), display inventory, BYD vehicle API instantiation (speed, energy) and BYDAUTO permission status.',
        },
        {
          title: 'Shareable report',
          text:
            'Generates a full report (system, displays, services, permissions, daemon metrics) exportable as text for support.',
        },
      ],
      howTo: {
        title: 'When to use this tab',
        steps: [
          'The cluster stays black after tapping an app → check ClusterService and the virtual display.',
          'The app reports “ADB unavailable” → “Test ADB” button.',
          'Support asks for a report → generate and share it.',
        ],
      },
      note: 'ℹ️ Buttons are read-only tests unless explicitly stated.',
    },

    {
      id: 'sysinfo',
      screen: 'screen-5',
      title: '6. System report',
      lead:
        'Read-only dashboard: versions, detected displays and live state of DashCast services. The first screen to check when something looks wrong.',
      mockupLabel: 'View screen 5 (System)',
      featuresTitle: 'Displayed information',
      features: [
        {
          title: '🖥️ Displays',
          text:
            'Main screen (resolution, density) and the cluster’s virtual display (1920×720) with its real-time state.',
        },
        {
          title: '⚙️ Services',
          text:
            'ClusterService (projection), MirrorDaemon (mirror), Proxy ADB Daemon (privileged operations), AdbLocalClient (ADB tunnel) — each with a green/red dot and a restart button when stopped.',
        },
        {
          title: '📱 Versions',
          text:
            'Installed DashCast version, BYD firmware, Android/API version, DiLink build identifiers.',
        },
        {
          title: '🔁 Projection replay',
          text:
            'Button to replay the full cluster activation sequence (useful if the gauge display got stuck in an intermediate state).',
        },
      ],
      tipsTitle: 'Tips',
      tips: [
        '💡 “Proxy ADB Daemon” must be green (RUN) for Layouts mode — otherwise tap its row to restart it.',
        '💡 The full report can be exported from this screen to accompany a bug report.',
      ],
    },

    {
      id: 'journal',
      screen: 'screen-6',
      title: '7. Log',
      lead:
        'DashCast’s internal log: every important action (projections, restores, ADB errors, updates) is traced continuously. Useful to understand unexpected behaviour or provide a report to support.',
      mockupLabel: 'View screen 6 (Log)',
      featuresTitle: 'Features',
      features: [
        {
          title: '🔍 Filters',
          text:
            'Filter by level (DEBUG / INFO / WARN / ERROR) or by keyword (e.g. “ADB”, “Maps”, “error”).',
        },
        {
          title: '🎨 Colour code',
          text:
            '🟢 INFO — normal operation. 🟠 WARN — attention. 🔴 ERROR — failure. ⚪ DEBUG — technical detail.',
        },
        {
          title: '📤 Share',
          text:
            'Exports the log as .txt and opens the Android share sheet. Includes the DashCast version and BYD model.',
        },
        {
          title: '⏰ Timestamps',
          text:
            'Every line is prefixed with local time (HH:mm:ss.mmm); long operations are measured.',
        },
      ],
      howTo: {
        title: 'Send a bug report',
        steps: [
          'Reproduce the problem.',
          'Open Log → “Share”.',
          'Pick your channel (Telegram, e-mail, GitHub Issues).',
          'The attached file contains the trace and the context (version, model, firmware).',
        ],
      },
      note:
        '🔒 No personal data (contacts, GPS position, app content) is logged — only DashCast actions and technical return codes.',
    },
  ],

  faq: {
    title: '8. FAQ — Frequently asked questions',
    items: [
      {
        question: '❓ The cluster stays black when I tap an app',
        answer:
          'Three possible causes: (1) wireless ADB disabled — check BYD Settings → Developer. (2) A stopped service — System tab, restart the red row. (3) The app just crashed — tap its icon again.',
      },
      {
        question: '❓ The image overflows / is cropped on the cluster',
        answer:
          'Settings → Margins: adjust the horizontal/vertical sliders until the edges are correct. Remembered per app — you only do it once.',
      },
      {
        question: '❓ How do I get the original BYD dashboard back?',
        answer:
          'Tap “Stop projection” on the main screen: DashCast restores the native cluster with the size set in Settings. If the display seems stuck, System screen → “Projection replay”, then stop again.',
      },
      {
        question: '❓ My favourite layout does not start at launch',
        answer:
          'Check the three conditions in Settings: “Layouts mode” enabled, “Auto favourite layout” enabled, and a layout marked ⭐ favourite (main-screen carousel or the Favourite button in the Layouts tab). On a cold start, allow 6–8 s.',
      },
      {
        question: '❓ Does DashCast drain the 12 V battery?',
        answer:
          'No — DashCast stops with the car. No background service stays active with the engine off.',
      },
      {
        question: '❓ Which apps work on the cluster?',
        items: [
          '✅ Navigation: Google Maps, Waze, Yandex Navi, OsmAnd, ABRP, Magic Earth.',
          '✅ Media: Spotify, YouTube, YouTube Music (prefer landscape).',
          '✅ System: camera, weather, calendar.',
          '⚠️ DRM apps (Netflix, Disney+, Prime Video): may refuse to display on a virtual display — an Android limitation, not DashCast.',
        ],
      },
      {
        question: '❓ Updates: stable or beta?',
        answer:
          'The stable channel (default) is tested on a vehicle before release. The beta channel (Settings → Updates → “Include pre-releases”) gets features as soon as they are built — useful for early testing, with a risk of temporary regressions.',
      },
      {
        question: '❓ I want to contribute or report a bug',
        answer:
          'GitHub: https://github.com/Kiroha/byd-dashcast — Issues for bugs, Discussions for questions. Attach a Log export to speed up diagnosis.',
      },
    ],
  },

  footer:
    'DashCast is an open-source project distributed under the MIT license. No affiliation with BYD Auto Co., Ltd.',
};
