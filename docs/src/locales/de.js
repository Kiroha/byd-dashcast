export default {
  code: 'de',
  flag: '🇩🇪',
  name: 'Deutsch',
  title: 'DashCast — Benutzerhandbuch',
  manualName: 'Benutzerhandbuch',
  meta: 'v1.4.x · BYD Seal EU · DiLink 3.0 · Android 10',
  tocTitle: '📋 Inhaltsverzeichnis',

  intro: {
    title: '0. Einführung',
    lead:
      'DashCast zeigt jede Android-App vom zentralen BYD-Bildschirm auf dem Kombiinstrument (digitales Cockpit) an. Maps, Waze, Spotify oder ABRP direkt hinter dem Lenkrad — und mit dem Layouts-Modus mehrere Apps gleichzeitig, jede in ihrer eigenen Zone. Ganz ohne Systemmodifikation.',
    bullets: [
      '✅ Kompatibel mit BYD Seal EU (DiLink 3.0, Firmware Di3.0 / 6125F).',
      '✅ Keine Systemmodifikation: DashCast wird wie eine normale App installiert.',
      '✅ Lokales ADB über TCP — nach der ersten Autorisierung kein Computer mehr nötig.',
      '✅ 13 Sprachen, Auswahl beim ersten Start.',
      '✅ Echtzeit-Touch-Spiegel: Steuern Sie das Cockpit vom zentralen Bildschirm aus.',
      '✅ Layouts-Modus: mehrere Apps nebeneinander auf dem Cockpit, Zonen mit dem Finger gezeichnet.',
      '✅ Autostart: Projektion + App (oder Favoriten-Layout) sobald DashCast startet.',
      '✅ Ränder (Overscan) pro App gespeichert.',
      '✅ Integrierte OTA-Updates (optionaler Beta-Kanal).',
    ],
    note:
      '💡 Einzige Voraussetzung: drahtloses ADB-Debugging in den BYD-Einstellungen → Entwickler aktivieren. Beim ersten Start erscheint der Dialog „Debugging zulassen?“ — „Immer zulassen“ ankreuzen und bestätigen. Dieser Schritt ist nie zu wiederholen.',
  },

  sections: [
    {
      id: 'welcome',
      screen: 'screen-1',
      title: '1. Willkommensbildschirm — Sprachauswahl',
      lead:
        'Beim allerersten Start zeigt DashCast ein Raster mit den 13 verfügbaren Sprachen. Tippen Sie auf Ihre Sprache: Die Wahl wird gespeichert und der Bildschirm erscheint nicht mehr. Die Sprache kann jederzeit in den Einstellungen geändert werden.',
      mockupLabel: 'Bildschirm 1 ansehen (Willkommen)',
      featuresTitle: 'Details',
      features: [
        {
          title: '13 unterstützte Sprachen',
          text:
            "Français, English, Deutsch, Italiano, Türkçe, Español, Polski, Русский, Українська, العربية, O'zbekcha, Қазақша, Беларуская. Die gewählte Sprache wird sofort angewendet, kein Neustart nötig.",
        },
        {
          title: 'Automatische Leserichtung',
          text:
            'Arabisch wechselt automatisch zum Rechts-nach-links-Layout (RTL): Die Navigationsleiste wandert nach rechts, Listen werden gespiegelt.',
        },
        {
          title: 'Jederzeit änderbar',
          text: 'Sprache später ändern: Einstellungen → Sprache. Wird sofort angewendet.',
        },
      ],
      howTo: {
        title: 'So geht’s',
        steps: [
          'DashCast starten (blaues Symbol im BYD-App-Drawer).',
          'Der Willkommensbildschirm mit dem Sprachraster erscheint.',
          'Tippen Sie auf Ihre Sprache. Die Oberfläche wechselt sofort.',
          'Der Hauptbildschirm öffnet sich — fertig.',
        ],
      },
    },

    {
      id: 'main',
      screen: 'screen-2',
      title: '2. Hauptbildschirm — Apps & Cockpit',
      lead:
        'Der zentrale Bildschirm von DashCast. Links: alle Apps mit Suche, Filtern und Favoriten. Rechts: die Echtzeit-Vorschau des Cockpits, die Buttons Vollbild-Spiegel / Projektion stoppen und das Layout-Karussell für Ihre bevorzugte Multi-App-Anordnung.',
      mockupLabel: 'Bildschirm 2 ansehen (Haupt)',
      featuresTitle: 'Alles, was Sie tun können',
      features: [
        {
          title: '👆 Kurzes Tippen — projizieren',
          text:
            'Tippen Sie auf eine App, um sie aufs Cockpit zu senden. Ist die Projektion nicht aktiv, startet sie automatisch (~2 s Aufwärmzeit), dann erscheint die App hinter dem Lenkrad.',
        },
        {
          title: '👆⏱️ Langes Drücken — Aktionsmenü',
          text:
            'App gedrückt halten: ⭐ Favorit, Auto-Launch (diese App bei jedem DashCast-Start projizieren), Zum Cockpit / Hauptbildschirm verschieben, ✕ Stopp erzwingen.',
        },
        {
          title: '🔍 Suche & Filter',
          text:
            'Die Suchleiste filtert beim Tippen (Name oder Paket). Kategorie-Chips (Alle / Navigation / Medien…) gruppieren die Apps; der ▦-Button wechselt Liste/Raster.',
        },
        {
          title: '🚦 Echtzeit-Cockpit-Vorschau',
          text:
            'Das rechte Panel spiegelt live das Cockpit. Ihre Berührungen auf der Vorschau werden an die projizierte App weitergeleitet — Scrollen, Zoomen, Tastatur, alles funktioniert.',
        },
        {
          title: '👁️ Vollbild-Spiegel',
          text:
            'Erweitert die Vorschau auf den ganzen zentralen Bildschirm: ideal, um eine Adresse in Maps mit der vollen Tastatur einzugeben. Alles wird in Echtzeit aufs Cockpit repliziert.',
        },
        {
          title: '⏹ Projektion stoppen',
          text:
            'Beendet die Projektion sauber und stellt das originale BYD-Cockpit (Geschwindigkeit, Anzeigen, ADAS) mit der in den Einstellungen gewählten Größe wieder her.',
        },
        {
          title: '🗂️ Layout-Karussell',
          text:
            'Unter den Buttons zeigt jede Karte eine Mini-Vorschau der Zonen eines Layouts. Karte antippen = Favoriten-Layout (Stern + blauer Rand). „Freier Modus“ deaktiviert Layouts; „＋ Verwalten“ öffnet den Editor.',
        },
        {
          title: '📺 Schwebender Button',
          text:
            'Ein 📺-Button bleibt über anderen Apps sichtbar: Tippen = Spiegel öffnen, langes Drücken = Schnellwechsel zwischen zuletzt projizierten Apps.',
        },
      ],
      howTo: {
        title: 'So projizieren Sie eine App aufs Cockpit',
        steps: [
          'Gewünschte App finden (z. B. Maps) — bei Bedarf Suche oder Filter.',
          'Auf das Symbol tippen → die Projektion startet, das Cockpit wechselt in ~2 s zur App.',
          'Die rechte Vorschau zeigt live das Cockpit.',
          'Text eingeben: „Vollbild-Spiegel“ → Adresse tippen → alles wird repliziert.',
          'Beenden: „Projektion stoppen“ — das Cockpit kehrt zu BYD nativ zurück.',
        ],
      },
      tipsTitle: 'Tipps',
      tips: [
        '💡 Auto-Launch: Wählen Sie eine App (langes Drücken → Auto-Launch), damit sie bei jedem DashCast-Start automatisch projiziert wird — die Projektion aktiviert sich von selbst.',
        '💡 Favoriten-Layout: Die im Karussell gewählte Karte ist die, die der Autostart aktiviert (siehe Abschnitt Layouts).',
        '💡 Ränder: Wenn die App über das Cockpit hinausragt: Einstellungen → Ränder, horizontale/vertikale Slider. Pro App gespeichert.',
      ],
    },

    {
      id: 'settings',
      screen: 'screen-3',
      title: '3. Einstellungen',
      lead:
        'Die globalen Optionen: Cockpit-Größe, Sprache, Ränder, Startverhalten, Layouts-Modus und Updates. Die Seitenleiste bleibt verfügbar — wechseln Sie zwischen Bildschirmen, ohne Ihre Position zu verlieren.',
      mockupLabel: 'Bildschirm 3 ansehen (Einstellungen)',
      featuresTitle: 'Hauptbereiche',
      features: [
        {
          title: '📺 Cockpit-Typ',
          text:
            'Physische Größe Ihres Kombiinstruments: 8.8″, 12.3″ (empfohlen beim Seal EU — behebt ADAS-Verzerrung) oder 10.25″. Wird von „Projektion stoppen“ zur Wiederherstellung des richtigen Modus verwendet.',
        },
        {
          title: '↔️↕️ Ränder (Overscan)',
          text:
            'Horizontale/vertikale Slider (0–200 px) zum Ausgleich abgeschnittener Ränder. Pro App gespeichert: Maps kann 80 px haben, während Spotify bei 0 bleibt. „Anwenden“ passt die laufende Projektion an.',
        },
        {
          title: '🚗 Start mit dem Fahrzeug',
          text:
            'Wenn aktiviert, startet DashCast mit dem Auto und stellt die zuletzt projizierte App wieder her. Andernfalls starten Sie es aus dem BYD-Drawer.',
        },
        {
          title: '🗂️ Layouts-Modus',
          text:
            'Aktiviert die Multi-App-Projektion mit eigenen Zonen (benötigt den Proxy ADB Daemon, automatisch verwaltet). Blendet das Karussell auf dem Hauptbildschirm und den Layouts-Tab ein.',
        },
        {
          title: '⭐ Automatisches Favoriten-Layout',
          text:
            'Beim Start von DashCast: aktiviert die Cockpit-Projektion, das Favoriten-Layout und startet die mit jeder Zone verknüpften Apps. Ihr komplettes Multi-App-Setup, ohne einen einzigen Tipper.',
        },
        {
          title: '⚡ Slots beim Start vorbereiten',
          text:
            'Bereitet die virtuellen Displays des Favoriten-Layouts beim Öffnen vor (ohne die Apps zu starten) — die Aktivierung des Layouts ist dann fast sofort.',
        },
        {
          title: '📦 OTA-Updates',
          text:
            'DashCast prüft bei jedem Start GitHub auf neue Versionen. „Vorabversionen einbeziehen“ ankreuzen für den Beta-Kanal (Neuheiten früher, weniger Stabilität).',
        },
        {
          title: '🌐 Sprache',
          text: '13 Sprachen — der Wechsel ist sofort.',
        },
      ],
      howTo: {
        title: 'So passen Sie die Ränder einer App an',
        steps: [
          'Projizieren Sie die anzupassende App (z. B. Waze).',
          'Einstellungen → Ränder.',
          'Horizontalen Slider bewegen, bis die linken/rechten Ränder stimmen.',
          'Dasselbe vertikal, dann „Anwenden“ — Anpassung im laufenden Betrieb.',
          'Die Einstellung wird nur für diese App gespeichert.',
        ],
      },
      note:
        '⚠️ Wenn Sie den Cockpit-Typ ändern, stoppen Sie die Projektion und starten Sie sie neu, damit die Wiederherstellung den richtigen Modus verwendet.',
    },

    {
      id: 'layouts',
      screen: 'screen-7',
      title: '4. Layouts — mehrere Apps auf dem Cockpit',
      lead:
        'Der Layouts-Modus teilt das Cockpit in eigene Zonen, jede mit ihrer eigenen App: Waze links, Spotify rechts, zum Beispiel. Sie zeichnen die Zonen mit dem Finger, verknüpfen eine App mit jeder Zone, und das Layout aktiviert sich mit einem Tipper — oder von selbst beim Start.',
      mockupLabel: 'Bildschirm 7 ansehen (Layouts)',
      featuresTitle: 'Funktionen',
      features: [
        {
          title: '✏️ Eine Zone zeichnen',
          text:
            'Auf dem Canvas (Nachbildung des 1920×720-Cockpits) ziehen Sie mit dem Finger ein Rechteck. Ein Dialog öffnet sich: Name, pixelgenaue Position/Abmessungen und die zu verknüpfende App.',
        },
        {
          title: '🔗 Eine App verknüpfen',
          text:
            'Jede Zone kann mit einer App verknüpft werden: Bei der Layout-Aktivierung startet die App automatisch in ihrer Zone. Eine Zone ohne App bleibt frei.',
        },
        {
          title: '✋ Verschieben & Größe ändern',
          text:
            'Zone ziehen zum Verschieben; die weißen Eckgriffe ändern die Größe. Kanten rasten automatisch an Cockpit-Rändern und Nachbarzonen ein.',
        },
        {
          title: '✏️ Bestehende Zone bearbeiten',
          text:
            'Zone antippen (auf dem Canvas oder ihrem Chip unten): umbenennen, Geometrie anpassen, verknüpfte App ändern oder löschen. Langes Drücken = Schnelllöschung.',
        },
        {
          title: '💾 Gespeicherte Layouts',
          text:
            'Speichern Sie beliebig viele Layouts („Nav+Media“, „Dreifach“…). Das Seitenpanel listet sie mit Aktivieren / Deaktivieren / Bearbeiten / Löschen.',
        },
        {
          title: '⭐ Favorit & Autostart',
          text:
            'Der „Favorit“-Button (oder ein Tipper auf die Karussell-Karte des Hauptbildschirms) bestimmt das Layout, das „Automatisches Favoriten-Layout“ beim DashCast-Start aktiviert — Projektion inklusive.',
        },
      ],
      howTo: {
        title: 'Ihr erstes Layout erstellen',
        steps: [
          '„Layouts-Modus“ in den Einstellungen aktivieren.',
          'Layouts-Tab öffnen (Seitenleiste).',
          'Mit dem Finger die erste Zone auf dem Canvas zeichnen (z. B. linke Hälfte).',
          'Im Dialog: benennen, „App verknüpfen“ antippen → Waze wählen → Hinzufügen.',
          'Zweite Zone zeichnen (rechte Hälfte), Spotify verknüpfen.',
          '„Speichern“ → Layout benennen (z. B. Nav+Media).',
          '„Favorit“ zum Auswählen, dann aktivieren: Beide Apps starten, jede in ihrer Zone.',
        ],
      },
      tipsTitle: 'Tipps',
      tips: [
        '💡 Zusammen mit „Automatisches Favoriten-Layout“ (Einstellungen) baut sich Ihr Multi-App-Setup bei jedem DashCast-Start von selbst auf.',
        '💡 Die Mini-Vorschau jeder Karussell-Karte zeigt die echten Zonen des Layouts — auf einen Blick erkennbar.',
        '💡 Eine App erscheint nicht in ihrer Zone? Manche Apps erzwingen ihr Seitenverhältnis; versuchen Sie eine Zone näher an 16:9.',
      ],
      note:
        'ℹ️ Der Layouts-Modus nutzt den Proxy ADB Daemon (automatisch gestartet). Erster Kaltstart: 6–8 s, bis die Apps erscheinen — das ist die Aktivierungssequenz des Cockpits.',
    },

    {
      id: 'diagnostics',
      screen: 'screen-4',
      title: '5. Diagnose',
      lead:
        'Internes Dashboard für Situationen, in denen die Projektion nicht wie erwartet funktioniert. Die meisten Benutzer brauchen es nie — es existiert für Support und Debugging.',
      mockupLabel: 'Bildschirm 4 ansehen (Diagnose)',
      featuresTitle: 'Verfügbare Werkzeuge',
      features: [
        {
          title: 'Verbindungstests',
          text:
            'Prüft den lokalen ADB-Tunnel (localhost:5555), den Zustand des ClusterService und das Vorhandensein des virtuellen Cockpit-Displays.',
        },
        {
          title: 'Plattform-Sonden',
          text:
            'DiLink-Erkennung (2/3/4/5), Display-Inventar, BYD-Fahrzeug-API-Instanziierung (Geschwindigkeit, Energie) und BYDAUTO-Berechtigungsstatus.',
        },
        {
          title: 'Teilbarer Bericht',
          text:
            'Erstellt einen vollständigen Bericht (System, Displays, Dienste, Berechtigungen, Daemon-Metriken), exportierbar als Text für den Support.',
        },
      ],
      howTo: {
        title: 'Wann diesen Tab verwenden',
        steps: [
          'Das Cockpit bleibt schwarz nach dem Antippen einer App → ClusterService und virtuelles Display prüfen.',
          'Die App meldet „ADB nicht verfügbar“ → Button „ADB testen“.',
          'Der Support bittet um einen Bericht → erstellen und teilen.',
        ],
      },
      note: 'ℹ️ Die Buttons sind reine Lesetests, sofern nicht ausdrücklich anders angegeben.',
    },

    {
      id: 'sysinfo',
      screen: 'screen-5',
      title: '6. Systembericht',
      lead:
        'Schreibgeschütztes Dashboard: Versionen, erkannte Displays und Live-Zustand der DashCast-Dienste. Der erste Bildschirm, den Sie prüfen sollten, wenn etwas seltsam aussieht.',
      mockupLabel: 'Bildschirm 5 ansehen (System)',
      featuresTitle: 'Angezeigte Informationen',
      features: [
        {
          title: '🖥️ Displays',
          text:
            'Hauptbildschirm (Auflösung, Dichte) und das virtuelle Cockpit-Display (1920×720) mit Echtzeit-Status.',
        },
        {
          title: '⚙️ Dienste',
          text:
            'ClusterService (Projektion), MirrorDaemon (Spiegel), Proxy ADB Daemon (privilegierte Operationen), AdbLocalClient (ADB-Tunnel) — jeweils mit grünem/rotem Punkt und Neustart-Button.',
        },
        {
          title: '📱 Versionen',
          text:
            'Installierte DashCast-Version, BYD-Firmware, Android/API-Version, DiLink-Build-Kennungen.',
        },
        {
          title: '🔁 Projektions-Replay',
          text:
            'Button zum erneuten Abspielen der vollständigen Cockpit-Aktivierungssequenz (nützlich, wenn das Kombiinstrument in einem Zwischenzustand hängt).',
        },
      ],
      tipsTitle: 'Tipps',
      tips: [
        '💡 „Proxy ADB Daemon“ muss grün sein (RUN) für den Layouts-Modus — andernfalls auf seine Zeile tippen, um ihn neu zu starten.',
        '💡 Der vollständige Bericht kann von diesem Bildschirm exportiert werden, um einen Bug-Report zu begleiten.',
      ],
    },

    {
      id: 'journal',
      screen: 'screen-6',
      title: '7. Protokoll',
      lead:
        'Das interne Protokoll von DashCast: Alle wichtigen Aktionen (Projektionen, Wiederherstellungen, ADB-Fehler, Updates) werden kontinuierlich aufgezeichnet. Nützlich, um unerwartetes Verhalten zu verstehen oder dem Support einen Bericht zu liefern.',
      mockupLabel: 'Bildschirm 6 ansehen (Protokoll)',
      featuresTitle: 'Funktionen',
      features: [
        {
          title: '🔍 Filter',
          text:
            'Nach Stufe (DEBUG / INFO / WARN / ERROR) oder Stichwort filtern (z. B. „ADB“, „Maps“, „error“).',
        },
        {
          title: '🎨 Farbcode',
          text:
            '🟢 INFO — normaler Betrieb. 🟠 WARN — Achtung. 🔴 ERROR — Fehlschlag. ⚪ DEBUG — technisches Detail.',
        },
        {
          title: '📤 Teilen',
          text:
            'Exportiert das Protokoll als .txt und öffnet das Android-Teilen-Menü. Enthält DashCast-Version und BYD-Modell.',
        },
        {
          title: '⏰ Zeitstempel',
          text:
            'Jede Zeile trägt die Ortszeit (HH:mm:ss.mmm); lange Operationen werden gemessen.',
        },
      ],
      howTo: {
        title: 'Einen Bug-Report senden',
        steps: [
          'Problem reproduzieren.',
          'Protokoll öffnen → „Teilen“.',
          'Kanal wählen (Telegram, E-Mail, GitHub Issues).',
          'Die angehängte Datei enthält die Spur und den Kontext (Version, Modell, Firmware).',
        ],
      },
      note:
        '🔒 Keine persönlichen Daten (Kontakte, GPS-Position, App-Inhalte) werden protokolliert — nur DashCast-Aktionen und technische Rückgabecodes.',
    },
  ],

  faq: {
    title: '8. FAQ — Häufige Fragen',
    items: [
      {
        question: '❓ Das Cockpit bleibt schwarz, wenn ich eine App antippe',
        answer:
          'Drei mögliche Ursachen: (1) Drahtloses ADB deaktiviert — BYD-Einstellungen → Entwickler prüfen. (2) Ein gestoppter Dienst — System-Tab, rote Zeile neu starten. (3) Die App ist gerade abgestürzt — Symbol erneut antippen.',
      },
      {
        question: '❓ Das Bild ragt hinaus / ist beschnitten',
        answer:
          'Einstellungen → Ränder: Slider anpassen, bis die Ränder stimmen. Pro App gespeichert — nur einmal nötig.',
      },
      {
        question: '❓ Wie komme ich zum originalen BYD-Cockpit zurück?',
        answer:
          '„Projektion stoppen“ auf dem Hauptbildschirm antippen: DashCast stellt das native Cockpit mit der eingestellten Größe wieder her. Wenn das Display hängt: System-Bildschirm → „Projektions-Replay“, dann erneut stoppen.',
      },
      {
        question: '❓ Mein Favoriten-Layout startet nicht beim Öffnen',
        answer:
          'Prüfen Sie die drei Bedingungen in den Einstellungen: „Layouts-Modus“ aktiv, „Automatisches Favoriten-Layout“ aktiv, und ein Layout als ⭐ Favorit markiert (Karussell oder Favorit-Button im Layouts-Tab). Beim Kaltstart 6–8 s einplanen.',
      },
      {
        question: '❓ Entleert DashCast die 12-V-Batterie?',
        answer:
          'Nein — DashCast stoppt mit dem Auto. Kein Hintergrunddienst bleibt bei abgestelltem Motor aktiv.',
      },
      {
        question: '❓ Welche Apps funktionieren auf dem Cockpit?',
        items: [
          '✅ Navigation: Google Maps, Waze, Yandex Navi, OsmAnd, ABRP, Magic Earth.',
          '✅ Medien: Spotify, YouTube, YouTube Music (Querformat bevorzugen).',
          '✅ System: Kamera, Wetter, Kalender.',
          '⚠️ DRM-Apps (Netflix, Disney+, Prime Video): können die Anzeige auf einem virtuellen Display verweigern — eine Android-Einschränkung, nicht DashCast.',
        ],
      },
      {
        question: '❓ Updates: stabil oder Beta?',
        answer:
          'Der stabile Kanal (Standard) wird vor der Veröffentlichung im Fahrzeug getestet. Der Beta-Kanal (Einstellungen → Updates → „Vorabversionen einbeziehen“) erhält Neuheiten sofort — nützlich zum frühen Testen, mit dem Risiko vorübergehender Regressionen.',
      },
      {
        question: '❓ Ich möchte beitragen oder einen Bug melden',
        answer:
          'GitHub: https://github.com/Kiroha/byd-dashcast — Issues für Bugs, Discussions für Fragen. Fügen Sie einen Protokoll-Export bei, um die Diagnose zu beschleunigen.',
      },
    ],
  },

  footer:
    'DashCast ist ein Open-Source-Projekt unter MIT-Lizenz. Keine Verbindung zu BYD Auto Co., Ltd.',
};
