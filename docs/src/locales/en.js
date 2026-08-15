export default {
  code: 'en',
  flag: '🇬🇧',
  name: 'English',
  title: 'DashCast — User Manual',
  manualName: 'User Manual',
  meta: 'v1.7.0 · BYD Seal / Dolphin / Atto 3 · DiLink 3 & DiLink 5 · Android 10–13',
  tocTitle: '📋 Table of contents',

  intro: {
    title: '0. Introduction',
    lead:
      'DashCast shows any Android application from your BYD central screen on the instrument cluster (the digital gauge display behind the wheel). Maps, Waze, Spotify or ABRP right in front of you — and with Layouts mode, several apps at once, each in its own zone. It installs like a normal app and changes nothing in the system.',
    bullets: [
      '✅ Works on DiLink 3 (Seal EU / 6125F) and DiLink 5 (newer BYD head units).',
      '✅ No system modification: DashCast installs like any other app.',
      '✅ Local ADB over TCP — no computer needed after the first authorisation.',
      '✅ 13 interface languages, chosen at first launch, changeable any time.',
      '✅ Real-time touch mirror: drive the cluster from the central screen.',
      '✅ Layouts mode: several apps side by side on the cluster, zones drawn with a finger.',
      '✅ Auto-start: projection + app (or favourite layout) as soon as DashCast opens.',
      '✅ Margins (overscan) remembered per application.',
      '✅ Turn-by-turn arrows on the DiLink 3 windshield HUD (supported firmware).',
      '✅ Built-in Wi-Fi hotspot helper for DiLink 3 (use your own SIM).',
      '✅ Keyboard-free bug reporter, one tap to send diagnostics to support.',
      '✅ Automatic OTA updates that install silently and relaunch the app.',
    ],
    note:
      '💡 Single prerequisite: enable wireless ADB debugging in BYD Settings → Developer options. On first launch an “Allow debugging?” dialog appears — tick “Always allow from this computer” and confirm. You never have to do this again.',
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
        'DashCast’s home screen. Left: all your applications with search, filters and favourites, plus the side navigation rail. Right: the real-time cluster preview, the Fullscreen mirror / Stop projection buttons, and — in Layouts mode — the collapsible “Cluster layout” chooser.',
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
          title: '🗂️ Cluster layout chooser (collapsed by default)',
          text:
            'In Layouts mode a compact “CLUSTER LAYOUT” header sits under the buttons. Tap it to expand: “Launch layout apps”, plus a card per saved layout (Free mode / your presets / ＋ Manage). It is collapsed by default so the live preview keeps its full height.',
        },
        {
          title: '📺 Floating button',
          text:
            'A 📺 button stays on top of other apps: tap = open the mirror, long press = quick-switch between recently projected apps.',
        },
        {
          title: '🧭 Side navigation rail',
          text:
            'Quick access to Apps, Settings, System, Log, the bug reporter and — on DiLink 3 with your own SIM — the Hotspot helper.',
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
        '💡 Favourite layout: the card selected in the “Cluster layout” chooser is the one auto-start will activate (see the Layouts section).',
        '💡 Margins: if the app overflows the cluster, Settings → Margins, horizontal/vertical sliders. Remembered per app.',
      ],
    },

    {
      id: 'settings',
      screen: 'screen-3',
      title: '3. Settings',
      lead:
        'Global options: cluster size, language, margins, startup behaviour, Layouts mode and updates. The side rail stays available — switch screens without losing your place.',
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
            'If enabled, DashCast starts with the car and restores the last projected app (or the favourite layout). Otherwise launch it from the BYD drawer.',
        },
        {
          title: '🗂️ Layouts mode',
          text:
            'Enables multi-app projection with custom zones (requires the Proxy ADB Daemon, managed automatically). Shows the “Cluster layout” chooser on the main screen and the Layouts tab.',
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
          title: '📶 Use my own SIM (DiLink 3)',
          text:
            'Controls whether the Hotspot helper appears in the navigation rail. Leave it on if you tether the car through your own phone/SIM data. See the Hotspot section.',
        },
        {
          title: '📦 OTA updates',
          text:
            'DashCast checks GitHub for new versions on every launch. Updates now install silently and relaunch the app by themselves (see the Updates section). Tick “Include pre-releases” for the beta channel.',
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
            'On the canvas (a replica of the cluster), drag your finger to draw a rectangle. A dialog opens: name, pixel-precise position/dimensions, and the app to bind.',
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
            'The “Favourite” button (or a tap on the main-screen chooser card) designates the layout that “Auto favourite layout” will activate on DashCast launch — projection included.',
        },
      ],
      howTo: {
        title: 'Create your first layout',
        steps: [
          'Enable “Layouts mode” in Settings.',
          'Open the Layouts tab (side rail).',
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
        '💡 The mini preview on each chooser card shows the layout’s actual zones — recognisable at a glance.',
        '💡 An app refuses to display in its zone? Some apps enforce their aspect ratio; try a zone closer to 16:9.',
      ],
      note:
        'ℹ️ Layouts mode relies on the Proxy ADB Daemon (started automatically). First cold start: allow 6–8 s before apps appear — that is the cluster activation sequence.',
    },

    {
      id: 'hud',
      screen: 'screen-2',
      title: '5. HUD navigation arrows (DiLink 3)',
      lead:
        'On DiLink 3 cars whose windshield Head-Up Display supports turn arrows, DashCast can draw turn-by-turn guidance on the HUD from your navigation app — the maneuver arrow and the distance to it, right on the windshield.',
      mockupLabel: 'View screen 2 (Main)',
      featuresTitle: 'How it works',
      features: [
        {
          title: '🧭 Guidance from Maps / Waze',
          text:
            'DashCast reads the turn-by-turn notification your navigation app already posts (Google Maps, Waze) and forwards the maneuver + distance to the HUD over the car’s CAN bus. No extra app is needed.',
        },
        {
          title: '🚗 Firmware dependent',
          text:
            'Only newer DiLink 3 HUD firmwares can draw arrows. If yours cannot, the arrows simply will not appear — DashCast cannot add a capability the HUD hardware does not have.',
        },
        {
          title: '➡️ Correct direction glyphs',
          text:
            'Straight / left / right maneuvers map to the matching HUD glyph, with the live countdown distance to the turn.',
        },
      ],
      howTo: {
        title: 'How to get arrows on the HUD',
        steps: [
          'Make sure notification access is granted to DashCast (it asks on first use).',
          'Turn the windshield HUD on and set it to a navigation display mode in the BYD HUD menu.',
          'Start a route in Google Maps or Waze.',
          'The maneuver arrow and distance appear on the HUD as you approach each turn.',
        ],
      },
      note:
        'ℹ️ HUD arrows are a DiLink 3 feature and depend on your HUD firmware. If nothing shows, your HUD may predate arrow support — this is a hardware limit, not a DashCast bug.',
    },

    {
      id: 'hotspot',
      screen: 'screen-3',
      title: '6. Wi-Fi Hotspot helper (DiLink 3)',
      lead:
        'On DiLink 3, if you connect the car to the internet through your own SIM/phone, the Hotspot helper keeps that tether alive so navigation and streaming keep working. It appears in the navigation rail only when it is relevant to you.',
      mockupLabel: 'View screen 3 (Settings)',
      featuresTitle: 'Features',
      features: [
        {
          title: '📶 Keep-alive',
          text:
            'Re-arms the Wi-Fi tether when the car wakes up (e.g. after turning ACC on) so you do not have to re-enable it manually every drive.',
        },
        {
          title: '👁️ Live status',
          text:
            'Shows whether the hotspot is up and how many clients are connected, so you can confirm the car is actually online.',
        },
        {
          title: '⚙️ Shown only when useful',
          text:
            'The Hotspot entry appears only on DiLink 3 and only while “Use my own SIM” is enabled in Settings. On other setups it stays hidden.',
        },
      ],
      howTo: {
        title: 'How to use it',
        steps: [
          'Settings → make sure “Use my own SIM” is enabled.',
          'Open “Hotspot” from the navigation rail.',
          'Start / confirm the tether — the status shows it is up.',
          'It re-arms itself the next time the car wakes up.',
        ],
      },
      note:
        'ℹ️ This helper is for DiLink 3 cars tethered through your own data. If your car has its own built-in data plan you do not need it.',
    },

    {
      id: 'bugreport',
      screen: 'screen-6',
      title: '7. Report a problem (bug reporter)',
      lead:
        'A keyboard-free, in-car bug reporter. In three taps you pick what went wrong; DashCast captures a bounded diagnostic snapshot (logs + system state) and sends it straight to the support channel — no typing, no cables.',
      mockupLabel: 'View screen 6 (Report)',
      featuresTitle: 'How it works',
      features: [
        {
          title: '1️⃣ Category',
          text:
            'Pick what area is affected: mirror, an app, sound, connection, freeze, HUD… Six large tiles, tap only.',
        },
        {
          title: '2️⃣ App',
          text:
            'DashCast auto-detects the app currently on the cluster and offers it, plus “No specific app” and “Other”.',
        },
        {
          title: '3️⃣ Issue',
          text:
            'Pick the closest symptom from a short list. An optional free-text box lets you add detail if you want to — but it is never required.',
        },
        {
          title: '📎 Automatic diagnostics',
          text:
            'The report bundles the recent logs and the system/cluster state at the moment of the problem — exactly what support needs, captured for you.',
        },
        {
          title: '🚀 One-tap send',
          text:
            'If a support channel is configured, the report is uploaded directly; otherwise DashCast opens the Android share sheet so you can send it by Telegram, e-mail or GitHub.',
        },
        {
          title: '📺 From anywhere',
          text:
            'The floating 📺 button and the navigation rail both open the reporter, so you can file a report even while another app is projected.',
        },
      ],
      howTo: {
        title: 'How to send a report',
        steps: [
          'Open the bug reporter (navigation rail, or the floating button).',
          'Tap the category that matches the problem.',
          'Confirm the app (or pick “No specific app”).',
          'Pick the closest issue; add a note if useful.',
          'Tap Send — the diagnostics go to support automatically.',
        ],
      },
      note:
        '🔒 Before sending, DashCast removes the vehicle serial number, Wi-Fi network names, hardware addresses and positions. What remains is the DashCast log and the Android system log, copied as they are — they hold what other apps wrote. You are asked once before anything is sent, and nothing leaves the car if you refuse.',
    },

    {
      id: 'system',
      screen: 'screen-5',
      title: '8. System report',
      lead:
        'Read-only dashboard: versions, detected displays and live state of DashCast services. The first screen to check when something looks wrong.',
      mockupLabel: 'View screen 5 (System)',
      featuresTitle: 'Displayed information',
      features: [
        {
          title: '🖥️ Displays',
          text:
            'Main screen (resolution, density) and the cluster’s virtual display with its real-time state.',
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
        '💡 If something looks wrong, check this screen first, then send a report from the bug reporter.',
      ],
    },

    {
      id: 'journal',
      screen: 'screen-6',
      title: '9. Log',
      lead:
        'DashCast’s internal log: every important action (projections, restores, ADB errors, updates) is traced continuously. Useful to understand unexpected behaviour; it is also the data the bug reporter attaches for you.',
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
        title: 'Prefer the bug reporter',
        steps: [
          'For most problems, use the bug reporter (section 7) — it captures the log plus the system state automatically.',
          'The Log screen is here when you want to read the trace yourself or share only the raw log.',
        ],
      },
      note:
        '🔒 The log records what DashCast does, including package names and the output of the commands it runs. Sharing it from this screen sends it as it is — the filtering that removes the vehicle serial number, network names and positions runs on bug reports, not here.',
    },
  ],

  faq: {
    title: '10. FAQ — Frequently asked questions',
    items: [
      {
        question: '❓ The cluster stays black when I tap an app',
        answer:
          'Three possible causes: (1) wireless ADB disabled — check BYD Settings → Developer options. (2) A stopped service — System screen, restart the red row. (3) The app just crashed — tap its icon again. Still stuck? Send a report from the bug reporter.',
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
          'Check the three conditions in Settings: “Layouts mode” enabled, “Auto favourite layout” enabled, and a layout marked ⭐ favourite (main-screen chooser or the Favourite button in the Layouts tab). On a cold start, allow 6–8 s.',
      },
      {
        question: '❓ No navigation arrows on my HUD',
        answer:
          'HUD arrows are a DiLink 3 feature and need a HUD firmware that can draw them. Make sure notification access is granted, the HUD is on in a navigation display mode, and a route is running in Maps/Waze. If nothing shows, your HUD firmware likely predates arrow support — a hardware limit, not a bug.',
      },
      {
        question: '❓ Do I need the Hotspot helper?',
        answer:
          'Only on DiLink 3 if you get the car online through your own SIM/phone tether. It keeps that tether alive across wake-ups. If your car has its own data plan, ignore it — it stays hidden unless “Use my own SIM” is on.',
      },
      {
        question: '❓ How do updates install now?',
        answer:
          'DashCast checks GitHub on every launch. When an update is downloaded it installs silently and relaunches the app itself — no “Install?” prompt. On a car where that is not possible, it falls back to the normal system installer. The very next update after you move to a version with this feature may still ask once.',
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
          'Use the in-app bug reporter for the fastest path (it attaches diagnostics for you). For code and feature requests: GitHub https://github.com/Kiroha/byd-dashcast — Issues for bugs, Discussions for questions.',
      },
    ],
  },

  footer:
    'DashCast is an open-source project distributed under the MIT license. No affiliation with BYD Auto Co., Ltd.',
};
