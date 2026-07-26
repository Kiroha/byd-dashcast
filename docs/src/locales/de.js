export default {
  code: 'de',
  flag: '🇩🇪',
  name: 'Deutsch',
  title: 'DashCast — Benutzerhandbuch',
  manualName: 'Benutzerhandbuch',
  meta: 'v1.7.0 · BYD Seal / Dolphin / Atto 3 · DiLink 3 & DiLink 5 · Android 10–13',
  tocTitle: '📋 Inhaltsverzeichnis',

  intro: {
    title: '0. Einführung',
    lead:
      'DashCast zeigt jede Android-App vom zentralen BYD-Bildschirm auf dem Kombiinstrument (dem digitalen Anzeigedisplay hinter dem Lenkrad) an. Maps, Waze, Spotify oder ABRP direkt vor Ihnen — und mit dem Layouts-Modus mehrere Apps gleichzeitig, jede in ihrer eigenen Zone. Es wird wie eine normale App installiert und ändert nichts am System.',
    bullets: [
      '✅ Funktioniert mit DiLink 3 (Seal EU / 6125F) und DiLink 5 (neuere BYD-Headunits).',
      '✅ Keine Systemmodifikation: DashCast wird wie jede andere App installiert.',
      '✅ Lokales ADB über TCP — nach der ersten Autorisierung kein Computer mehr nötig.',
      '✅ 13 Oberflächensprachen, Auswahl beim ersten Start, jederzeit änderbar.',
      '✅ Echtzeit-Touch-Spiegel: Steuern Sie das Cockpit vom zentralen Bildschirm aus.',
      '✅ Layouts-Modus: mehrere Apps nebeneinander auf dem Cockpit, Zonen mit dem Finger gezeichnet.',
      '✅ Autostart: Projektion + App (oder Favoriten-Layout), sobald DashCast öffnet.',
      '✅ Ränder (Overscan) pro App gespeichert.',
      '✅ Abbiegepfeile auf dem DiLink-3-Windschutzscheiben-HUD (unterstützte Firmware).',
      '✅ Integrierter Wi-Fi-Hotspot-Helfer für DiLink 3 (eigene SIM nutzen).',
      '✅ Tastaturfreier Fehlerberichterstatter, ein Tipp sendet Diagnosedaten an den Support.',
      '✅ Automatische OTA-Updates, die still installieren und die App neu starten.',
    ],
    note:
      '💡 Einzige Voraussetzung: drahtloses ADB-Debugging in den BYD-Einstellungen → Entwickleroptionen aktivieren. Beim ersten Start erscheint der Dialog „Debugging zulassen?“ — „Von diesem Computer immer zulassen“ ankreuzen und bestätigen. Dieser Schritt ist nie zu wiederholen.',
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
        'Der Startbildschirm von DashCast. Links: alle Ihre Apps mit Suche, Filtern und Favoriten sowie die seitliche Navigationsleiste. Rechts: die Echtzeit-Cockpit-Vorschau, die Buttons Vollbild-Spiegel / Projektion stoppen und — im Layouts-Modus — der einklappbare „Cockpit-Layout“-Auswahlbereich.',
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
          title: '🗂️ Cockpit-Layout-Auswahl (standardmäßig eingeklappt)',
          text:
            'Im Layouts-Modus sitzt eine kompakte Kopfzeile „COCKPIT-LAYOUT“ unter den Buttons. Antippen zum Ausklappen: „Layout-Apps starten“ sowie eine Karte pro gespeichertem Layout (Freier Modus / Ihre Vorlagen / ＋ Verwalten). Standardmäßig eingeklappt, damit die Live-Vorschau ihre volle Höhe behält.',
        },
        {
          title: '📺 Schwebender Button',
          text:
            'Ein 📺-Button bleibt über anderen Apps sichtbar: Tippen = Spiegel öffnen, langes Drücken = Schnellwechsel zwischen zuletzt projizierten Apps.',
        },
        {
          title: '🧭 Seitliche Navigationsleiste',
          text:
            'Schnellzugriff auf Apps, Einstellungen, System, Protokoll, den Fehlerberichterstatter und — auf DiLink 3 mit eigener SIM — den Hotspot-Helfer.',
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
        '💡 Favoriten-Layout: Die im „Cockpit-Layout“-Auswahlbereich gewählte Karte ist die, die der Autostart aktiviert (siehe Abschnitt Layouts).',
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
            'Wenn aktiviert, startet DashCast mit dem Auto und stellt die zuletzt projizierte App (oder das Favoriten-Layout) wieder her. Andernfalls starten Sie es aus dem BYD-Drawer.',
        },
        {
          title: '🗂️ Layouts-Modus',
          text:
            'Aktiviert die Multi-App-Projektion mit eigenen Zonen (benötigt den Proxy ADB Daemon, automatisch verwaltet). Blendet den „Cockpit-Layout“-Auswahlbereich auf dem Hauptbildschirm und den Layouts-Tab ein.',
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
          title: '📶 Eigene SIM nutzen (DiLink 3)',
          text:
            'Steuert, ob der Hotspot-Helfer in der Navigationsleiste erscheint. Lassen Sie es aktiviert, wenn Sie das Auto über die Daten Ihres eigenen Telefons/Ihrer eigenen SIM anbinden. Siehe Abschnitt Hotspot.',
        },
        {
          title: '📦 OTA-Updates',
          text:
            'DashCast prüft bei jedem Start GitHub auf neue Versionen. Updates installieren jetzt still und starten die App von selbst neu (siehe Abschnitt Updates). „Vorabversionen einbeziehen“ ankreuzen für den Beta-Kanal.',
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
          'Dasselbe vertikal, dann „Anwenden“ — Anpassung im laufenden Betrieb, kein App-Neustart.',
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
            'Auf dem Canvas (einer Nachbildung des Cockpits) ziehen Sie mit dem Finger ein Rechteck. Ein Dialog öffnet sich: Name, pixelgenaue Position/Abmessungen und die zu verknüpfende App.',
        },
        {
          title: '🔗 Eine App verknüpfen',
          text:
            'Jede Zone kann mit einer App verknüpft werden: Bei der Layout-Aktivierung startet die App automatisch in ihrer Zone. Eine Zone ohne App bleibt frei — platzieren Sie später etwas dort.',
        },
        {
          title: '✋ Verschieben & Größe ändern',
          text:
            'Zone ziehen zum Verschieben; die weißen Eckgriffe ändern die Größe. Kanten rasten automatisch an Cockpit-Rändern und Nachbarzonen ein.',
        },
        {
          title: '✏️ Bestehende Zone bearbeiten',
          text:
            'Zone antippen (auf dem Canvas oder ihrem Chip unten): umbenennen, Geometrie anpassen, verknüpfte App ändern oder löschen. Langes Drücken auf eine Zone = Schnelllöschung.',
        },
        {
          title: '💾 Gespeicherte Layouts',
          text:
            'Speichern Sie beliebig viele Layouts („Nav+Media“, „Dreifach“…). Das Seitenpanel listet sie mit Aktivieren / Deaktivieren / Bearbeiten / Löschen.',
        },
        {
          title: '⭐ Favorit & Autostart',
          text:
            'Der „Favorit“-Button (oder ein Tipper auf die Auswahlkarte des Hauptbildschirms) bestimmt das Layout, das „Automatisches Favoriten-Layout“ beim DashCast-Start aktiviert — Projektion inklusive.',
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
        '💡 Die Mini-Vorschau auf jeder Auswahlkarte zeigt die echten Zonen des Layouts — auf einen Blick erkennbar.',
        '💡 Eine App erscheint nicht in ihrer Zone? Manche Apps erzwingen ihr Seitenverhältnis; versuchen Sie eine Zone näher an 16:9.',
      ],
      note:
        'ℹ️ Der Layouts-Modus nutzt den Proxy ADB Daemon (automatisch gestartet). Erster Kaltstart: 6–8 s, bis die Apps erscheinen — das ist die Aktivierungssequenz des Cockpits.',
    },

    {
      id: 'hud',
      screen: 'screen-2',
      title: '5. HUD-Navigationspfeile (DiLink 3)',
      lead:
        'Auf DiLink-3-Fahrzeugen, deren Windschutzscheiben-Head-up-Display Abbiegepfeile unterstützt, kann DashCast die Abbiegeführung Ihrer Navigations-App auf dem HUD anzeigen — den Manöverpfeil und die Entfernung dazu, direkt auf der Windschutzscheibe.',
      mockupLabel: 'Bildschirm 2 ansehen (Haupt)',
      featuresTitle: 'So funktioniert es',
      features: [
        {
          title: '🧭 Führung von Maps / Waze',
          text:
            'DashCast liest die Abbiegehinweis-Benachrichtigung, die Ihre Navigations-App ohnehin sendet (Google Maps, Waze), und leitet Manöver + Entfernung über den CAN-Bus des Autos an das HUD weiter. Keine zusätzliche App nötig.',
        },
        {
          title: '🚗 Firmware-abhängig',
          text:
            'Nur neuere DiLink-3-HUD-Firmwares können Pfeile darstellen. Kann Ihre das nicht, erscheinen die Pfeile einfach nicht — DashCast kann keine Fähigkeit hinzufügen, die die HUD-Hardware nicht besitzt.',
        },
        {
          title: '➡️ Korrekte Richtungssymbole',
          text:
            'Geradeaus- / Links- / Rechts-Manöver werden dem passenden HUD-Symbol zugeordnet, mit der live herunterzählenden Entfernung zur Abbiegung.',
        },
      ],
      howTo: {
        title: 'So erhalten Sie Pfeile auf dem HUD',
        steps: [
          'Stellen Sie sicher, dass DashCast Benachrichtigungszugriff hat (wird bei der ersten Nutzung abgefragt).',
          'Schalten Sie das Windschutzscheiben-HUD ein und stellen Sie es im BYD-HUD-Menü auf einen Navigations-Anzeigemodus.',
          'Starten Sie eine Route in Google Maps oder Waze.',
          'Der Manöverpfeil und die Entfernung erscheinen auf dem HUD, wenn Sie sich jeder Abbiegung nähern.',
        ],
      },
      note:
        'ℹ️ HUD-Pfeile sind eine DiLink-3-Funktion und hängen von Ihrer HUD-Firmware ab. Wenn nichts erscheint, ist Ihr HUD möglicherweise älter als die Pfeilunterstützung — das ist eine Hardware-Grenze, kein DashCast-Fehler.',
    },

    {
      id: 'hotspot',
      screen: 'screen-3',
      title: '6. Wi-Fi-Hotspot-Helfer (DiLink 3)',
      lead:
        'Auf DiLink 3 hält der Hotspot-Helfer, wenn Sie das Auto über Ihre eigene SIM/Ihr eigenes Telefon mit dem Internet verbinden, diese Anbindung aufrecht, damit Navigation und Streaming weiterlaufen. Er erscheint nur dann in der Navigationsleiste, wenn er für Sie relevant ist.',
      mockupLabel: 'Bildschirm 3 ansehen (Einstellungen)',
      featuresTitle: 'Funktionen',
      features: [
        {
          title: '📶 Keep-alive',
          text:
            'Reaktiviert die Wi-Fi-Anbindung, wenn das Auto aufwacht (z. B. nach dem Einschalten von ACC), damit Sie sie nicht bei jeder Fahrt manuell neu aktivieren müssen.',
        },
        {
          title: '👁️ Live-Status',
          text:
            'Zeigt, ob der Hotspot aktiv ist und wie viele Clients verbunden sind, sodass Sie bestätigen können, dass das Auto tatsächlich online ist.',
        },
        {
          title: '⚙️ Nur bei Bedarf angezeigt',
          text:
            'Der Hotspot-Eintrag erscheint nur auf DiLink 3 und nur, solange „Eigene SIM nutzen“ in den Einstellungen aktiviert ist. Bei anderen Konfigurationen bleibt er verborgen.',
        },
      ],
      howTo: {
        title: 'So verwenden Sie ihn',
        steps: [
          'Einstellungen → stellen Sie sicher, dass „Eigene SIM nutzen“ aktiviert ist.',
          '„Hotspot“ in der Navigationsleiste öffnen.',
          'Anbindung starten / bestätigen — der Status zeigt, dass sie aktiv ist.',
          'Sie reaktiviert sich beim nächsten Aufwachen des Autos von selbst.',
        ],
      },
      note:
        'ℹ️ Dieser Helfer ist für DiLink-3-Fahrzeuge gedacht, die über Ihre eigenen Daten angebunden sind. Wenn Ihr Auto einen eigenen integrierten Datentarif hat, brauchen Sie ihn nicht.',
    },

    {
      id: 'bugreport',
      screen: 'screen-6',
      title: '7. Ein Problem melden (Fehlerberichterstatter)',
      lead:
        'Ein tastaturfreier Fehlerberichterstatter im Auto. In drei Tipps wählen Sie, was schiefging; DashCast erfasst eine begrenzte Diagnose-Momentaufnahme (Protokolle + Systemzustand) und sendet sie direkt an den Support-Kanal — kein Tippen, keine Kabel.',
      mockupLabel: 'Bildschirm 6 ansehen (Bericht)',
      featuresTitle: 'So funktioniert es',
      features: [
        {
          title: '1️⃣ Kategorie',
          text:
            'Wählen Sie den betroffenen Bereich: Spiegel, eine App, Ton, Verbindung, Einfrieren, HUD… Sechs große Kacheln, nur antippen.',
        },
        {
          title: '2️⃣ App',
          text:
            'DashCast erkennt automatisch die aktuell auf dem Cockpit laufende App und bietet sie an, dazu „Keine bestimmte App“ und „Andere“.',
        },
        {
          title: '3️⃣ Problem',
          text:
            'Wählen Sie das nächstliegende Symptom aus einer kurzen Liste. Ein optionales Freitextfeld lässt Sie bei Bedarf Details hinzufügen — es ist aber nie erforderlich.',
        },
        {
          title: '📎 Automatische Diagnose',
          text:
            'Der Bericht bündelt die jüngsten Protokolle und den System-/Cockpit-Zustand zum Zeitpunkt des Problems — genau das, was der Support braucht, für Sie erfasst.',
        },
        {
          title: '🚀 Senden mit einem Tipp',
          text:
            'Ist ein Support-Kanal konfiguriert, wird der Bericht direkt hochgeladen; andernfalls öffnet DashCast das Android-Teilen-Menü, damit Sie ihn per Telegram, E-Mail oder GitHub senden können.',
        },
        {
          title: '📺 Von überall',
          text:
            'Der schwebende 📺-Button und die Navigationsleiste öffnen beide den Berichterstatter, sodass Sie einen Bericht einreichen können, während eine andere App projiziert wird.',
        },
      ],
      howTo: {
        title: 'So senden Sie einen Bericht',
        steps: [
          'Den Fehlerberichterstatter öffnen (Navigationsleiste oder schwebender Button).',
          'Auf die zum Problem passende Kategorie tippen.',
          'Die App bestätigen (oder „Keine bestimmte App“ wählen).',
          'Das nächstliegende Problem wählen; bei Bedarf eine Notiz hinzufügen.',
          'Auf Senden tippen — die Diagnosedaten gehen automatisch an den Support.',
        ],
      },
      note:
        '🔒 Der Bericht enthält nur DashCast-Protokolle und technischen Geräte-/Cockpit-Zustand — keine Kontakte, keine GPS-Position, keine App-Inhalte.',
    },

    {
      id: 'system',
      screen: 'screen-5',
      title: '8. Systembericht',
      lead:
        'Schreibgeschütztes Dashboard: Versionen, erkannte Displays und Live-Zustand der DashCast-Dienste. Der erste Bildschirm, den Sie prüfen sollten, wenn etwas seltsam aussieht.',
      mockupLabel: 'Bildschirm 5 ansehen (System)',
      featuresTitle: 'Angezeigte Informationen',
      features: [
        {
          title: '🖥️ Displays',
          text:
            'Hauptbildschirm (Auflösung, Dichte) und das virtuelle Cockpit-Display mit seinem Echtzeit-Status.',
        },
        {
          title: '⚙️ Dienste',
          text:
            'ClusterService (Projektion), MirrorDaemon (Spiegel), Proxy ADB Daemon (privilegierte Operationen), AdbLocalClient (ADB-Tunnel) — jeweils mit grünem/rotem Punkt und einem Neustart-Button, wenn gestoppt.',
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
        '💡 Wenn etwas seltsam aussieht, prüfen Sie zuerst diesen Bildschirm und senden Sie dann einen Bericht über den Fehlerberichterstatter.',
      ],
    },

    {
      id: 'journal',
      screen: 'screen-6',
      title: '9. Protokoll',
      lead:
        'Das interne Protokoll von DashCast: Jede wichtige Aktion (Projektionen, Wiederherstellungen, ADB-Fehler, Updates) wird kontinuierlich aufgezeichnet. Nützlich, um unerwartetes Verhalten zu verstehen; es sind auch die Daten, die der Fehlerberichterstatter für Sie anhängt.',
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
        title: 'Bevorzugen Sie den Fehlerberichterstatter',
        steps: [
          'Verwenden Sie für die meisten Probleme den Fehlerberichterstatter (Abschnitt 7) — er erfasst das Protokoll plus den Systemzustand automatisch.',
          'Der Protokoll-Bildschirm ist da, wenn Sie die Spur selbst lesen oder nur das reine Protokoll teilen möchten.',
        ],
      },
      note:
        '🔒 Keine persönlichen Daten (Kontakte, GPS-Position, App-Inhalte) werden protokolliert — nur DashCast-Aktionen und technische Rückgabecodes.',
    },
  ],

  faq: {
    title: '10. FAQ — Häufige Fragen',
    items: [
      {
        question: '❓ Das Cockpit bleibt schwarz, wenn ich eine App antippe',
        answer:
          'Drei mögliche Ursachen: (1) Drahtloses ADB deaktiviert — BYD-Einstellungen → Entwickleroptionen prüfen. (2) Ein gestoppter Dienst — System-Bildschirm, rote Zeile neu starten. (3) Die App ist gerade abgestürzt — Symbol erneut antippen. Immer noch blockiert? Senden Sie einen Bericht über den Fehlerberichterstatter.',
      },
      {
        question: '❓ Das Bild ragt hinaus / ist auf dem Cockpit beschnitten',
        answer:
          'Einstellungen → Ränder: die horizontalen/vertikalen Slider anpassen, bis die Ränder stimmen. Pro App gespeichert — nur einmal nötig.',
      },
      {
        question: '❓ Wie komme ich zum originalen BYD-Cockpit zurück?',
        answer:
          '„Projektion stoppen“ auf dem Hauptbildschirm antippen: DashCast stellt das native Cockpit mit der eingestellten Größe wieder her. Wenn das Display hängt: System-Bildschirm → „Projektions-Replay“, dann erneut stoppen.',
      },
      {
        question: '❓ Mein Favoriten-Layout startet nicht beim Öffnen',
        answer:
          'Prüfen Sie die drei Bedingungen in den Einstellungen: „Layouts-Modus“ aktiv, „Automatisches Favoriten-Layout“ aktiv und ein Layout als ⭐ Favorit markiert (Auswahl auf dem Hauptbildschirm oder Favorit-Button im Layouts-Tab). Beim Kaltstart 6–8 s einplanen.',
      },
      {
        question: '❓ Keine Navigationspfeile auf meinem HUD',
        answer:
          'HUD-Pfeile sind eine DiLink-3-Funktion und benötigen eine HUD-Firmware, die sie darstellen kann. Stellen Sie sicher, dass der Benachrichtigungszugriff gewährt ist, das HUD in einem Navigations-Anzeigemodus eingeschaltet ist und eine Route in Maps/Waze läuft. Wenn nichts erscheint, ist Ihre HUD-Firmware wahrscheinlich älter als die Pfeilunterstützung — eine Hardware-Grenze, kein Fehler.',
      },
      {
        question: '❓ Brauche ich den Hotspot-Helfer?',
        answer:
          'Nur auf DiLink 3, wenn Sie das Auto über die Anbindung Ihrer eigenen SIM/Ihres eigenen Telefons online bringen. Er hält diese Anbindung über Aufwachvorgänge hinweg aufrecht. Wenn Ihr Auto einen eigenen Datentarif hat, ignorieren Sie ihn — er bleibt verborgen, sofern „Eigene SIM nutzen“ nicht aktiviert ist.',
      },
      {
        question: '❓ Wie installieren sich Updates jetzt?',
        answer:
          'DashCast prüft bei jedem Start GitHub. Wenn ein Update heruntergeladen ist, installiert es sich still und startet die App selbst neu — keine „Installieren?“-Abfrage. Auf einem Auto, wo das nicht möglich ist, greift es auf den normalen System-Installer zurück. Das allererste Update, nachdem Sie zu einer Version mit dieser Funktion gewechselt sind, fragt möglicherweise noch einmal.',
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
          'Verwenden Sie den In-App-Fehlerberichterstatter für den schnellsten Weg (er hängt die Diagnosedaten für Sie an). Für Code- und Funktionswünsche: GitHub https://github.com/Kiroha/byd-dashcast — Issues für Bugs, Discussions für Fragen.',
      },
    ],
  },

  footer:
    'DashCast ist ein Open-Source-Projekt unter MIT-Lizenz. Keine Verbindung zu BYD Auto Co., Ltd.',
};
