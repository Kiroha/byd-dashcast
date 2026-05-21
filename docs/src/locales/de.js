export default {
  code: 'de',
  flag: '🇩🇪',
  name: 'Deutsch',
  title: 'DashCast — Bedienungsanleitung',
  manualName: 'Bedienungsanleitung',
  meta: 'v0.9.92-alpha · BYD Seal EU · DiLink 3.0 · Android 10',
  tocTitle: '📋 Inhalt',

  intro: {
    title: '0. Einführung',
    lead:
      "Mit DashCast können Sie jede Android-App vom mittleren BYD-Bildschirm auf das digitale Kombiinstrument (Cluster) übertragen. So haben Sie Maps, Waze, Spotify oder YouTube direkt vor dem Lenkrad, während die nativen BYD-Anzeigen (Geschwindigkeit, Anzeigen, Reichweite) jederzeit zugänglich bleiben.",
    bullets: [
      "✅ Kompatibel mit BYD Seal EU (DiLink 3.0, Firmware Di3.0 / 6125F).",
      "✅ Keine Systemmodifikation: DashCast wird wie eine reguläre App installiert.",
      "✅ Lokales TCP-ADB — kein Computer nach der ersten Autorisierung nötig.",
      "✅ 12 Sprachen, beim ersten Start auswählbar.",
      "✅ Integrierte OTA-Updates (optionaler Alpha-Kanal).",
      "✅ Overscan-Ränder werden pro App gespeichert.",
      "✅ Berührungsfähiger Vollbildspiegel zur Cluster-Steuerung vom Hauptdisplay aus.",
      "✅ Split-Modus (zwei Apps nebeneinander auf dem Cluster).",
    ],
    note:
      "💡 Einmalige Voraussetzung: Drahtloses ADB-Debugging in BYD-Einstellungen → Entwickler aktivieren. Beim ersten Start erscheint ein Dialog « Debugging zulassen? » — « Immer zulassen » ankreuzen und bestätigen. Dieser Schritt muss nie wiederholt werden.",
  },

  sections: [
    {
      id: 'welcome',
      screen: 'screen-1',
      title: '1. Willkommensbildschirm — Sprachauswahl',
      lead:
        "Beim allerersten Start zeigt DashCast eine Kachel mit den 12 verfügbaren Sprachen. Tippen Sie Ihre Sprache an; die Auswahl wird gespeichert und der Willkommensbildschirm erscheint nicht erneut. Sie können die Sprache jederzeit über Einstellungen → Sprache ändern.",
      mockupLabel: 'Bildschirm 1 öffnen (Willkommen)',
      featuresTitle: 'Details',
      features: [
        {
          title: '12 unterstützte Sprachen',
          text:
            "Français, English, Deutsch, Italiano, Türkçe, Español, Русский, Українська, العربية, O'zbekcha, Қазақша, Беларуская. Die ausgewählte Sprache wird sofort ohne Neustart angewendet.",
        },
        {
          title: 'Automatische Leserichtung',
          text:
            "Arabisch wechselt automatisch in das Rechts-nach-Links-Layout (RTL): Die Navigationsleiste wandert nach rechts, Listen werden gespiegelt, Symbole bleiben lesbar.",
        },
        {
          title: 'Jederzeit änderbar',
          text:
            "So ändern Sie die Sprache später: Lange auf das DashCast-Logo oben in der Seitenleiste drücken → 🌐 Sprache. Die neue Sprache wird sofort angewendet.",
        },
      ],
      howTo: {
        title: 'So gehen Sie vor',
        steps: [
          "Starten Sie DashCast (blaues Symbol im BYD-App-Menü).",
          "Der Willkommensbildschirm zeigt das 4×3-Sprachraster.",
          "Tippen Sie Ihre Sprache an. Die Oberfläche wechselt sofort.",
          "Der Hauptbildschirm öffnet sich — DashCast ist einsatzbereit.",
        ],
      },
      note:
        "ℹ️ Wenn Sie die Sprache während einer laufenden Projektion ändern, läuft die Projektion ohne Unterbrechung weiter; nur die DashCast-Oberfläche wird übersetzt.",
    },

    {
      id: 'main',
      screen: 'screen-2',
      title: '2. Hauptbildschirm — Apps & Cluster',
      lead:
        "Der zentrale DashCast-Bildschirm. Links die Liste aller installierten Apps mit Suche, Kategoriefiltern und Favoriten. Rechts die Live-Vorschau des Clusters mit den Hauptaktionen: Vollbildvorschau, Screenshot, Neuverbindung, Projektion stoppen.",
      mockupLabel: 'Bildschirm 2 öffnen (Hauptbildschirm)',
      featuresTitle: 'Was Sie alles tun können',
      features: [
        {
          title: '🔍 Suchleiste',
          text:
            "Tippen Sie ein paar Buchstaben, um die Liste live zu filtern (sowohl App-Name als auch Paketname). Die Schaltfläche ▦ rechts wechselt zwischen Listen- und Rasteransicht.",
        },
        {
          title: '🏷️ Kategoriefilter',
          text:
            "Die farbigen Chips (Alle / Navigation / Medien / Kommunikation / System) gruppieren Ihre Apps automatisch. Die Zahl in Klammern zeigt, wie viele Apps sichtbar sind.",
        },
        {
          title: '⭐ Angeheftete Favoriten',
          text:
            "Der Bereich « Favoriten » oben sammelt Ihre meistgenutzten Apps. Hinzufügen oder entfernen: Lange auf die App drücken → ⭐ Zu Favoriten hinzufügen/entfernen.",
        },
        {
          title: '👆 Kurzer Tipp — projizieren',
          text:
            "Tippen Sie eine App an, um sie sofort an den Cluster zu senden. Falls die Projektion noch nicht läuft, startet sie automatisch (Cluster-Aufwärmen, ~2 s).",
        },
        {
          title: '👆⏱️ Langes Drücken — Aktionsmenü',
          text:
            "Halten Sie eine App gedrückt, um ein Vollbildmenü zu öffnen: ⭐ Favorit, Auto-Launch (mit Projektion starten), An Cluster / Hauptdisplay senden, ✕ Beenden erzwingen.",
        },
        {
          title: '🚦 Live-Cluster-Vorschau',
          text:
            "Das rechte Feld spiegelt, was auf dem Cluster läuft (Geschwindigkeit, Gang, Akku, Reichweite). Die Latenz (12 ms) bestätigt eine stabile Verbindung.",
        },
        {
          title: '👁️ Vollbildvorschau',
          text:
            "Tippen Sie « Vollbildvorschau », um die Live-Vorschau auf das gesamte Hauptdisplay zu erweitern. Praktisch, um in Maps mit voller Tastatur eine Adresse einzugeben — alles wird auf den Cluster gespiegelt.",
        },
        {
          title: '📸 Screenshot',
          text:
            "Die Schaltfläche « Aufnehmen » speichert den aktuellen Cluster-Inhalt als PNG unter /sdcard/Pictures/DashCast/. Nützlich, um eine Strecke zu teilen oder ein Anzeigeproblem zu dokumentieren.",
        },
        {
          title: '↻ Neu verbinden',
          text:
            "Wenn die projizierte App eingefroren ist, stellt « Neu verbinden » den Videostream wieder her, ohne den Original-Cluster zu beeinflussen.",
        },
        {
          title: '⏹ Spiegelung beenden',
          text:
            "Beendet die Projektion sauber. Kurzer Tipp = sanfter Stopp (Cluster geht via ADB zurück zum nativen BYD). Langer Druck = erweitertes Menü mit « Original-Cluster wiederherstellen », das die Wiederherstellung mit der Cluster-Größe aus den Einstellungen erzwingt.",
        },
      ],
      howTo: {
        title: 'So projizieren Sie eine App auf den Cluster',
        steps: [
          "Auf dem Hauptbildschirm die gewünschte App finden (z. B. Maps).",
          "Auf das Symbol tippen → die Projektion startet, der Cluster wechselt in ~2 s zur App.",
          "Das rechte Feld zeigt live, was auf dem Cluster läuft.",
          "Zum Tippen (Adresssuche): « Vollbildvorschau » antippen → die App breitet sich auf dem Hauptdisplay aus → Adresse eingeben → alles wird auf den Cluster gespiegelt.",
          "Beenden: « Spiegelung beenden » antippen (der Cluster kehrt zum BYD-Original zurück).",
        ],
      },
      tipsTitle: 'Tipps',
      tips: [
        "💡 Auto-Launch: Diesen Schalter an einer App aktivieren, damit sie bei jedem DashCast-Start automatisch projiziert wird.",
        "💡 Split-Modus: Im Aktionsmenü einer zweiten App « Als Split senden » wählen, um 2 Apps nebeneinander auf dem Cluster anzuzeigen.",
        "💡 Ränder: Wenn die App über den Cluster hinausragt, Einstellungen → Ränder öffnen und die Schieberegler anpassen. Pro App gespeichert.",
        "💡 Berührungs-Vollbild: Im Vollbildvorschau-Modus steuert Ihr Finger auf dem Hauptdisplay tatsächlich die App — Tastatur, Scrollen, Gesten, alles funktioniert.",
      ],
    },

    {
      id: 'settings',
      screen: 'screen-3',
      title: '3. Einstellungen',
      lead:
        "Der Einstellungsbildschirm sammelt globale Optionen und das Bild-Tuning der Projektion. Die linke Seitenleiste bleibt verfügbar — Sie können zwischen Apps, Einstellungen, Diag, System und Journal wechseln, ohne die Position zu verlieren.",
      mockupLabel: 'Bildschirm 3 öffnen (Einstellungen)',
      featuresTitle: 'Verfügbare Bereiche',
      features: [
        {
          title: '📺 Cluster-Typ',
          text:
            "Wählen Sie die physische Größe Ihres Kombiinstruments: 8.8″ (sendInfo cmd 29), 12.3″ Seal EU (cmd 30, Standard) oder 10.25″ (cmd 31). Dieser Wert wird insbesondere von « Original-Cluster wiederherstellen » verwendet.",
        },
        {
          title: '🌐 Sprache',
          text:
            "12 Sprachen verfügbar. Umschaltung sofort — kein DashCast-Neustart nötig.",
        },
        {
          title: '↔️ Horizontaler Rand (Overscan)',
          text:
            "Schieberegler 0–200 px. Fügt schwarze Balken links/rechts hinzu, um abgeschnittene Ränder Ihres Clusters zu kompensieren. Wert pro App gespeichert — Maps kann 80 px verwenden, Spotify bleibt bei 0.",
        },
        {
          title: '↕️ Vertikaler Rand (Overscan)',
          text:
            "Schieberegler 0–200 px. Dasselbe oben/unten. Die kombinierten Ränder werden auf VirtualDisplay-Ebene angewendet, sodass die App die abgeschnittenen Bereiche nicht « sieht ».",
        },
        {
          title: '✅ Anwenden / 🔄 Zurücksetzen',
          text:
            "« Anwenden » überträgt die neuen Ränder sofort in die laufende Projektion. « Zurücksetzen » bringt die aktuelle App auf 0/0 zurück.",
        },
        {
          title: '📦 OTA-Updates',
          text:
            "DashCast prüft automatisch GitHub Releases. Aktivieren Sie « Pre-Releases einbeziehen », um den Alpha-Kanal zu erhalten (häufigere, aber experimentelle Updates).",
        },
        {
          title: '🚗 Automatischer Start mit dem Fahrzeug',
          text:
            "Wenn aktiviert, startet DashCast mit dem Auto und stellt die zuletzt projizierte App wieder her. Sonst manuell aus dem BYD-Menü starten.",
        },
      ],
      howTo: {
        title: 'So feinjustieren Sie die Ränder einer App',
        steps: [
          "Die zu justierende App projizieren (z. B. Waze).",
          "Einstellungen → Ränder öffnen.",
          "Den horizontalen Schieberegler bewegen, bis die linken/rechten Kanten passen.",
          "Dasselbe für den vertikalen Schieberegler.",
          "« Anwenden » antippen → die Projektion wird live aktualisiert, ohne App-Neustart.",
          "Die Einstellung wird nur für diese App gespeichert (jede App hat ihre eigenen Ränder).",
        ],
      },
      note:
        "⚠️ Wenn Sie den Cluster-Typ ändern, starten Sie DashCast neu, damit die Referenzwerte neu berechnet werden.",
    },

    {
      id: 'diagnostics',
      screen: 'screen-4',
      title: '4. Diagnose',
      lead:
        "Der Diagnose-Tab ist ein internes Dashboard für Fälle, in denen die Projektion nicht wie erwartet funktioniert. Die meisten Nutzer brauchen es nie — es ist für Support und Debugging gedacht.",
      mockupLabel: 'Bildschirm 4 öffnen (Diagnose)',
      featuresTitle: 'Verfügbare Werkzeuge',
      features: [
        {
          title: 'ClusterService-Status',
          text:
            "Prüft, dass der Android-Dienst, der die Projektion verwaltet, läuft. Bei « nicht gebunden » startet eine Schaltfläche ihn neu.",
        },
        {
          title: 'VirtualDisplay-Status',
          text:
            "Zeigt die ID der für den Cluster erstellten virtuellen Anzeige, ihre Auflösung und ob ein Qt-Surface angehängt ist.",
        },
        {
          title: 'Lokale ADB-Verbindung',
          text:
            "Schnelltest des ADB-Tunnels zu localhost:5555. Wenn der Test fehlschlägt, wurde meist das drahtlose Debugging in den BYD-Einstellungen deaktiviert.",
        },
        {
          title: 'Gezieltes logcat',
          text:
            "Erfasst die letzten 200 logcat-Zeilen, gefiltert nach DashCast / AutoContainer / xdja. Die Schaltfläche « Teilen » sendet den Bericht.",
        },
      ],
      howTo: {
        title: 'Wann diesen Tab verwenden',
        steps: [
          "Cluster bleibt schwarz nach App-Tipp → ClusterService und VirtualDisplay-Status prüfen.",
          "App meldet « ADB nicht verfügbar » → Diag-Tab → Schaltfläche « ADB testen ».",
          "Support fragt nach einem Bericht → Diag → « logcat teilen ».",
          "Ein Update wurde gerade installiert und Sie wollen die laufende Version bestätigen.",
        ],
      },
      note:
        "ℹ️ Dieser Tab ändert nichts von selbst: Die Schaltflächen führen schreibgeschützte Tests aus, sofern nicht anders angegeben.",
    },

    {
      id: 'sysinfo',
      screen: 'screen-5',
      title: '5. Systeminformationen',
      lead:
        "Schreibgeschütztes Dashboard zur Hardware/Software-Umgebung. Hier finden Sie die DashCast-Version, BYD-Firmware, Android-Version und die Cluster-Kennung.",
      mockupLabel: 'Bildschirm 5 öffnen (System)',
      featuresTitle: 'Angezeigte Informationen',
      features: [
        {
          title: '🚗 Fahrzeug',
          text:
            "Erkanntes BYD-Modell, FIN (falls verfügbar), Firmware-Build (z. B. Di3.0 / 6125F), Firmware-Build-Datum.",
        },
        {
          title: '📱 Android',
          text:
            "Android-Version (10), API-Level (29), Sicherheits-Patch, DiLink-Build-ID.",
        },
        {
          title: '🔌 DashCast',
          text:
            "Installierte Version, versionCode, Update-Kanal (stabil / alpha), Datum der letzten OTA-Prüfung, Link zu den Release-Notes.",
        },
        {
          title: '🖥️ Cluster',
          text:
            "Erkannter Typ (8.8″ / 12.3″ / 10.25″), tatsächliche Auflösung, aktuelle VirtualDisplay-ID, aktives Qt-Paket (com.xdja.containerservice).",
        },
        {
          title: '📦 Erfasste Apps',
          text:
            "Anzahl der von DashCast erkannten Apps, angeheftete Favoriten, Apps mit Auto-Launch.",
        },
      ],
      tipsTitle: 'Tipps',
      tips: [
        "💡 Eine Zeile lange drücken, um den Wert in die Zwischenablage zu kopieren (praktisch für einen Bug-Report).",
        "💡 Die Schaltfläche « Exportieren » unten speichert alles in einer Textdatei (/sdcard/DashCast/sysinfo.txt).",
      ],
    },

    {
      id: 'journal',
      screen: 'screen-6',
      title: '6. Journal',
      lead:
        "Internes DashCast-Logbuch: Verfolgt jede wichtige Aktion (Projektionen, Wiederherstellungen, ADB-Fehler, Updates). Hilfreich, um unerwartetes Verhalten zu verstehen oder einen Bericht an den Support zu senden.",
      mockupLabel: 'Bildschirm 6 öffnen (Journal)',
      featuresTitle: 'Funktionen',
      features: [
        {
          title: '🔍 Filter',
          text:
            "Geben Sie ein Stichwort ein, um nur relevante Zeilen zu behalten (z. B. « ADB », « Maps », « error »). Filter ist Groß-/Kleinschreibung egal.",
        },
        {
          title: '🎨 Farbcode',
          text:
            "🟢 INFO (grün) — normaler Betrieb. 🟠 WARN (orange) — Achtung. 🔴 ERROR (rot) — Fehler. ⚪ DEBUG (grau) — technisches Detail.",
        },
        {
          title: '🗑 Leeren',
          text:
            "Leert das Journal. Der System-logcat-Trace ist nicht betroffen — nur der DashCast-Speicherverlauf wird gelöscht.",
        },
        {
          title: '📤 Teilen',
          text:
            "Exportiert das aktuelle Journal als .txt und öffnet das Android-Teilen-Menü (E-Mail, Telegram, Datei). Enthält automatisch DashCast-Version und BYD-Modell.",
        },
        {
          title: '⏰ Zeitstempel',
          text:
            "Jede Zeile beginnt mit der Ortszeit (HH:mm:ss.mmm). Lange Operationen (Maps-Start, Cluster-Wiederherstellung) werden gemessen und angezeigt.",
        },
      ],
      howTo: {
        title: 'So senden Sie einen Bug-Report',
        steps: [
          "Reproduzieren Sie das Problem (z. B. App bleibt nach Start schwarz).",
          "Journal öffnen.",
          "« Teilen » antippen.",
          "Kanal wählen (Telegram, E-Mail, GitHub Issues).",
          "Die angehängte .txt enthält die vollständige Trace und den Kontext (Version, Modell, Firmware).",
        ],
      },
      note:
        "🔒 Es werden keine persönlichen Daten (Kontakte, GPS, App-Inhalte) protokolliert — nur DashCast-Aktionen und technische Rückgabecodes.",
    },
  ],

  faq: {
    title: '7. FAQ — Häufige Fragen',
    items: [
      {
        question: '❓ Cluster bleibt schwarz, wenn ich eine App antippe',
        answer:
          "Drei mögliche Ursachen: (1) drahtloses ADB deaktiviert — BYD-Einstellungen → Entwickler prüfen. (2) ClusterService nicht aktiv — Diag-Tab, Schaltfläche « Neu starten ». (3) Die App ist gerade abgestürzt — « Neu verbinden » im rechten Feld.",
      },
      {
        question: '❓ Bild läuft über / wird auf dem Cluster abgeschnitten',
        answer:
          "Einstellungen → Ränder öffnen und horizontale/vertikale Schieberegler anpassen, bis die Kanten passen. Die Einstellung wird pro App gespeichert — nur einmal nötig.",
      },
      {
        question: '❓ Wie komme ich zum BYD-Original-Dashboard zurück?',
        answer:
          "Ein kurzer Tipp auf « Spiegelung beenden » reicht in 95 % der Fälle. Wenn der Cluster festhängt, drücken Sie diese Schaltfläche lange → Menü → « Original-Cluster wiederherstellen »: DashCast erzwingt die zur Cluster-Größe passende sendInfo-Sequenz.",
      },
      {
        question: '❓ Entlädt DashCast die 12-V-Batterie?',
        answer:
          "Nein — DashCast stoppt automatisch beim Abschalten des Fahrzeugs (Android.intent.action.SCREEN_OFF + BMS-Disconnect-Broadcasts). Kein Hintergrunddienst läuft nach dem Motor-Aus weiter.",
      },
      {
        question: '❓ Ich möchte beitragen oder einen Fehler melden',
        answer:
          "GitHub: https://github.com/Kiroha/byd-dashcast — Issues für Bugs, Discussions für Fragen. Hängen Sie immer einen Journal-Export an (Journal → Teilen), um die Diagnose zu beschleunigen.",
      },
      {
        question: '❓ Welche Apps funktionieren auf dem Cluster?',
        items: [
          "✅ Navigation: Google Maps, Waze, Yandex Navi, OsmAnd, Magic Earth.",
          "✅ Medien: Spotify, YouTube, YouTube Music, Netflix (Querformat bevorzugen).",
          "✅ Kommunikation: Telegram (nur lesen), WhatsApp (Benachrichtigungen).",
          "✅ System: Kamera, Wetter, Kalender.",
          "⚠️ Apps mit Widevine-L1-DRM (Disney+, Prime Video) verweigern eventuell die Anzeige auf einem VirtualDisplay — Android-Beschränkung, nicht DashCast.",
        ],
      },
      {
        question: '❓ Updates: stabil oder alpha?',
        answer:
          "Der Stable-Kanal (Standard) wird mindestens 1 Woche im Fahrzeug getestet. Der Alpha-Kanal (in Einstellungen → Updates aktivieren) erhält neue Builds direkt nach Kompilierung — gut zum Vorab-Test, kann aber temporäre Regressionen einführen.",
      },
    ],
  },

  footer:
    'DashCast ist ein Open-Source-Projekt unter GPL-3.0-Lizenz. Keine Verbindung zu BYD Auto Co., Ltd.',
};
