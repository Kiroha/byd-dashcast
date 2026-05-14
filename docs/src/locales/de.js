export default {
  "code": "de",
  "flag": "🇩🇪",
  "name": "Deutsch",
  "title": "DashCast — Benutzerhandbuch",
  "meta": "v0.5.1 · BYD Seal EU · DiLink 3.0 · Android 10",
  "manualName": "Benutzerhandbuch",
  "tocTitle": "📋 Inhaltsverzeichnis",
  "sections": [
    "1. Übersicht",
    "2. Erster Start — Sprachauswahl",
    "3. Hauptbildschirm",
    "4. App auf das Kombiinstrument projizieren",
    "5. Während der Projektion — Steuerfeld",
    "6. Projektion beenden",
    "7. Einstellungen",
    "8. ⋮ Menü — Weitere Tools",
    "9. FAQ & Fehlerbehebung"
  ],
  "overview": {
    "title": "1. Übersicht",
    "text": "DashCast ist eine Android-App, mit der Sie beliebige Apps vom Infotainment-Bildschirm auf das digitale Kombiinstrument Ihres BYD projizieren können. Navigation, Musik, Videos — alles, was auf dem Zentralbildschirm läuft, kann auf das Cluster umgeleitet werden.",
    "bullets": [
      "✅ Kompatibel mit BYD Seal EU (DiLink 3.0, Firmware Di3.0 / 6125F)",
      "✅ Keine Systemmodifikation erforderlich",
      "✅ Lokales ADB (TCP, localhost) — nach der Einrichtung kein PC mehr nötig",
      "✅ Automatische App-Trennungserkennung",
      "✅ Integrierte OTA-Updates (Over-The-Air)",
      "✅ Overscan pro App gespeichert",
      "✅ Raster- oder Listenansicht",
      "✅ Notfall-Force-Stop (Rotes Kreuz) pro App"
    ],
    "note": "💡 Voraussetzung: Aktivieren Sie TCP-ADB-Debugging unter Einstellungen → Entwickler → Kabelloses Debugging. Dieser Schritt ist einmalig. Beim ersten Start erscheint ein Popup 'Debugging zulassen?' — tippen Sie auf Immer von diesem Gerät zulassen."
  },
  "firstLaunch": {
    "title": "2. Erster Start — Sprachauswahl",
    "text": "Beim ersten Start erscheint der Begrüßungsbildschirm mit einem 4×3-Raster (3 Spalten × 4 Zeilen) für die 12 verfügbaren Sprachen. Tippen Sie auf die Schaltfläche Ihrer Sprache. Diese Auswahl wird gespeichert — der Bildschirm erscheint nicht erneut, es sei denn, Sie ändern die Sprache über ⋮ → 🌐 Sprache.",
    "welcomeSubtitle": "Dashboard Controller",
    "welcomeHint": "Choisissez votre langue\nBitte Sprache wählen",
    "caption": "Sprachauswahl — nur beim ersten Start angezeigt"
  },
  "main": {
    "title": "3. Hauptbildschirm",
    "text": "Der Hauptbildschirm besteht aus zwei Bereichen: einer Statusleiste oben (dunkler Hintergrund) und einer Liste installierter Apps darunter. Sie können über das ⋮ Menü zwischen Listen- und Rasteransicht wechseln.",
    "status": "① Dashboard: nicht verbunden",
    "buttons": [
      "② Projektion aktivieren",
      "③ Projektion stoppen",
      "④ Original-Dashboard wiederherstellen",
      "⑤ ⋮",
      "✕",
      "✕",
      "✕",
      "✕"
    ],
    "listTitle": "⑥ Installierte Apps",
    "apps": [
      "Maps",
      "YouTube",
      "Spotify",
      "Waze"
    ],
    "caption": "Hauptbildschirm — keine App projiziert (Ausgangszustand)",
    "annotations": [
      {
        "tone": "",
        "marker": "①",
        "label": "Status",
        "text": "Zeigt den Verbindungsstatus zum Cluster. Wechselt zu 'Dashboard: [App-Name]', wenn eine App aktiv ist."
      },
      {
        "tone": "",
        "marker": "②",
        "label": "Projektion aktivieren",
        "text": "Stellt die Verbindung zum Cluster her und bereitet das App-Casting vor. Zuerst antippen."
      },
      {
        "tone": "red",
        "marker": "③",
        "label": "Projektion stoppen",
        "text": "Beendet die aktuelle Projektion, ohne das Original-BYD-Dashboard wiederherzustellen."
      },
      {
        "tone": "gray",
        "marker": "④",
        "label": "Originalanzeige wiederherstellen (⋮ Menü)",
        "text": "Über das ⋮ Menü erreichbar. Beendet die Projektion UND stellt das native BYD-Dashboard wieder her (Geschwindigkeit, Anzeigen, Reichweite…). Am Ende der Nutzung bevorzugt gegenüber 'Projektion beenden'."
      },
      {
        "tone": "gray",
        "marker": "⑤",
        "label": "⋮ Menü",
        "text": "Zugriff auf Einstellungen, Diagnose, Systembericht, Logs, Sprachwechsel und Raster/Listen-Umschalter."
      },
      {
        "tone": "gray",
        "marker": "⑥",
        "label": "App-Liste",
        "text": "Tippen Sie auf eine App, um sie auf den Cluster zu projizieren. Langes Drücken → App anheften (⭐, wird an den Listenanfang verschoben). Das Kontrollkästchen 'Auto' markiert eine App für den automatischen Start: Sie wird gesendet, sobald die Projektion beginnt. Die Schaltfläche ✕ und die Pfeile ← / → erscheinen nur bei der aktuell aktiven App (im Cluster oder auf dem Hauptbildschirm)."
      }
    ]
  },
  "projection": {
    "title": "4. App auf das Kombiinstrument projizieren",
    "steps": [
      "Tippen Sie auf 'Projektion aktivieren' (blauer Knopf oben). Der Status wechselt zu 'Cluster wird gestartet…'. Die lokale ADB-Verbindung wird hergestellt.",
      "Tippen Sie auf die gewünschte App in der Liste. DashCast verschiebt die App auf das Kombiinstrument. Der Status wechselt zu 'Dashboard: [App-Name]'.",
      "Das Steuerfeld erscheint unten. Die gespeicherten Overscan-Werte für diese App werden automatisch angewendet."
    ],
    "activeStatus": "Dashboard: Maps ✓",
    "buttons": [
      "Projektion aktivieren",
      "📺 Spiegel",
      "Projektion stoppen",
      "Original-Dashboard wiederherstellen",
      "⋮",
      "← Haupt",
      "✕",
      "→ Cluster",
      "✕",
      "→ Cluster",
      "✕",
      "📐 Anpassen",
      "⬛⬛ Split",
      "Ausblenden ▼"
    ],
    "listTitle": "Installierte Apps",
    "apps": [
      "Maps",
      "YouTube",
      "Spotify"
    ],
    "controlLabel": "Cluster-Steuerung",
    "controlApp": "Maps",
    "mirrorText": "Anzeige auf Cluster aktiv ✓",
    "caption": "Hauptbildschirm — Maps wird auf dem Kombiinstrument angezeigt",
    "annotations": [
      {
        "tone": "green",
        "marker": "●",
        "label": "Grüner Balken",
        "text": "Visueller Indikator: Diese App läuft aktuell auf dem Cluster."
      },
      {
        "tone": "",
        "marker": "→",
        "label": "→ Cluster",
        "text": "Sendet eine andere App zum Cluster (ersetzt die aktuelle)."
      },
      {
        "tone": "gray",
        "marker": "←",
        "label": "← Haupt",
        "text": "Gibt die App an den Hauptbildschirm zurück (entfernt sie vom Cluster)."
      },
      {
        "tone": "teal",
        "marker": "📺",
        "label": "Spiegel",
        "text": "Zeigt eine Live-Vorschau des Cluster-Inhalts in DashCast."
      },
      {
        "tone": "red",
        "marker": "❌",
        "label": "Rotes Kreuz (Force-Stop)",
        "text": "Erzwingt den vollständigen Prozess-Stopp einer eingefrorenen App und entfernt sie aus den Zuletzt geöffneten."
      },
      {
        "tone": "gray",
        "marker": "🔲",
        "label": "Raster / Liste",
        "text": "Zwischen Listenansicht und Rasteransicht umschalten (⋮ Menü)."
      }
    ]
  },
  "control": {
    "title": "5. Während der Projektion — Steuerfeld",
    "intro": "Wenn eine App auf dem Cluster aktiv ist, erscheint unten ein dunkles Panel mit vier Steuerfunktionen:",
    "mirror": {
      "title": "5.1 Spiegel (📺 Spiegel)",
      "text": "Tippen Sie auf 📺 Spiegel in der Statusleiste, um eine Live-Kopie des Clusters in DashCast anzuzeigen. Sie können mit dieser Kopie per Touch interagieren — Ereignisse werden an das Cluster weitergeleitet.",
      "note": "Der Spiegel nutzt SurfaceControl. Falls nicht verfügbar, werden automatisch alle 2 Sekunden Screenshots erstellt."
    },
    "resize": {
      "title": "5.2 Anpassen (📐 Overscan pro App)",
      "text": "Die Schaltfläche 📐 Anpassen zeigt zwei Schieberegler: Breitenrand und Höhenrand. Diese schneiden die Ränder des projizierten Bildes ab. Werte werden pro App gespeichert und bei jedem Start automatisch via wm overscan angewendet.",
      "note": "💡 Empfohlene Werte für Seal EU: Breite 80 px, Höhe 50 px."
    },
    "relaunch": {
      "title": "5.5 Neu starten (↺)",
      "text": "Die ↺-Schaltfläche (orange) beendet die aktuell auf dem Cluster projizierte App erzwungen und startet sie sofort neu. Nützlich, wenn die App eingefroren ist oder die Anzeige auf dem Cluster blockiert ist."
    },
    "split": {
      "title": "5.3 Split-Modus (⬛⬛ Split)",
      "text": "Tippen Sie auf ⬛⬛ Split, um das Cluster zwischen zwei Apps aufzuteilen:",
      "items": [
        "Vollbild — Eine App belegt das gesamte Cluster",
        "⬜⬛ Links (50%) — Haupt-App links, zweite App rechts",
        "⬛⬜ Rechts (50%) — Haupt-App rechts"
      ],
      "extra": "Im Split-Modus kann eine zweite App aus der Liste ausgewählt werden."
    },
    "hide": {
      "title": "5.4 Panel ausblenden",
      "text": "Tippen Sie auf Ausblenden ▼, um das Steuerfeld einzuklappen und zur vollständigen App-Liste zurückzukehren."
    }
  },
  "stopping": {
    "title": "6. Projektion beenden",
    "intro": "Zwei Schaltflächen ermöglichen das Beenden der Projektion:",
    "table": {
      "headers": [
        "Schaltfläche",
        "Verhalten",
        "Wann verwenden"
      ],
      "rows": [
        [
          "Projektion stoppen",
          "Beendet die Projektion. Das Dashboard bleibt leer (schwarz).",
          "Wenn Sie die Anzeige nur vorübergehend stoppen möchten."
        ],
        [
          "Original-Dashboard wiederherstellen",
          "Beendet die Projektion UND stellt das native BYD-Display wieder her (Geschwindigkeit, Reichweite, Anzeigen…).",
          "Am Ende der Nutzung — um das normale BYD-Dashboard zurückzubekommen."
        ]
      ]
    },
    "warning": "⚠️ Wenn Sie DashCast ohne eine dieser Schaltflächen beenden, bleibt die Projektion auf dem Cluster aktiv, bis der Dienst neu gestartet wird."
  },
  "settings": {
    "title": "7. Einstellungen",
    "intro": "Öffnen Sie die Einstellungen über ⋮ → ⚙️ Einstellungen. Der Bildschirm enthält drei Abschnitte:",
    "titleLabel": "Einstellungen",
    "clusterTypeLabel": "Cluster-Typ",
    "clusterOptions": [
      "8,8 Zoll (cmd=29)",
      "12,3 Zoll (cmd=30) — Seal EU",
      "10,25 Zoll (cmd=31)"
    ],
    "marginsLabel": "Anzeigeränder (globaler Overscan)",
    "horizontalMarginLabel": "Links / Rechts:",
    "verticalMarginLabel": "Oben / Unten:",
    "applyButton": "Jetzt anwenden",
    "resetButton": "Zurücksetzen (80 / 50)",
    "updatesLabel": "Aktualisierungen",
    "prereleaseLabel": "Vorabversionen einschließen (Alpha/Beta)",
    "prereleaseHint": "Testversionen vor der offiziellen Veröffentlichung erhalten.",
    "caption": "Einstellungsseite",
    "type": {
      "title": "7.1 Cluster-Typ",
      "text": "Wählen Sie die Bildschirmgröße Ihres Dashboards. Für BYD Seal EU wählen Sie 12,3 Zoll (cmd=30)."
    },
    "margins": {
      "title": "7.2 Globale Anzeigeränder (Overscan)",
      "text": "Passen Sie die Ränder an, um den Inhalt perfekt im sichtbaren Bereich einzurahmen. Für App-spezifische Einstellungen nutzen Sie 📐 Anpassen im Steuerfeld.",
      "items": [
        "Links / Rechts — Horizontaler Rand (0–200 px beidseitig)",
        "Oben / Unten — Vertikaler Rand (0–200 px oben und unten)"
      ],
      "applyText": "Klicken Sie auf Jetzt anwenden, um das Ergebnis sofort zu sehen, falls eine App projiziert wird. Werte werden gespeichert.",
      "note": "💡 Empfohlene Standardwerte für Seal EU: Links/Rechts = 80 px, Oben/Unten = 50 px."
    },
    "updates": {
      "title": "7.3 Aktualisierungen",
      "text": "Aktivieren Sie 'Vorabversionen einschließen (Alpha/Beta)', um Testbuilds vor dem offiziellen Release zu erhalten. Für eine manuelle Prüfung: ⋮ → 🔄 Nach Aktualisierungen suchen. Updates werden direkt von GitHub Releases heruntergeladen — kein Play Store erforderlich."
    }
  },
  "tools": {
    "title": "8. ⋮ Menü — Weitere Tools",
    "intro": "Die Schaltfläche ⋮ oben rechts öffnet ein Menü:",
    "table": {
      "headers": [
        "Option",
        "Beschreibung"
      ],
      "rows": [
        ["⚙️ Einstellungen", "Öffnet die Einstellungen: Cluster-Typ, Globale Overscan-Ränder, Aktualisierungen (Vorabversionen)."],
        ["🌐 Sprache", "Kehrt zum Sprachauswahlbildschirm zurück, um die Sprache der Benutzeroberfläche zu ändern."],
        ["🔄 Nach Updates suchen", "Prüft, ob eine neue Version von DashCast auf GitHub verfügbar ist. Falls Vorabversionen in Einstellungen aktiviert, werden auch Alpha/Beta-Versionen angeboten."],
        ["⊞ Rasteransicht / 📋 Listenansicht", "Schaltet die App-Liste zwischen Listenansicht (1 Spalte) und Rasteransicht (5 Spalten) um. Auch über die ⊞-Schaltfläche im Listenkopf erreichbar."],
        ["Originalanzeige wiederherstellen", "Beendet die Projektion UND stellt das native BYD-Dashboard wieder her. Nur während einer Projektion aktiv."],
        ["🔧 Diagnose", "Erweiterte Entwicklertools — ADB-Verbindung, VirtualDisplay-Erstellung, SurfaceFlinger-Analyse, Logcat-Sniffer für Reverse Engineering."],
        ["📋 Systembericht", "Erstellt einen vollständigen Bericht (Displays, BYD APIs, Berechtigungen, Pakete) — nützlich für den Support oder Fehlerberichte."],
        ["📜 Protokoll", "Echtzeit-Protokollansicht — nach Tag/Level filtern, per E-Mail oder Datei teilen."]
      ]
    },
    "logs": {
      "title": "Protokoll (📜 Logs)",
      "header": "📋 Protokoll",
      "clearButton": "Löschen",
      "shareButton": "Teilen",
      "filterPlaceholder": "Filtern (Tag / Nachricht / Level)…",
      "lines": [
        "[INFO ] ClusterService → Cluster display connected: id=1",
        "[INFO ] launchOnDashboard OK → com.google.android.apps.maps",
        "[DEBUG] watchdog started for com.google.android.apps.maps pid=4821",
        "[WARN ] setTaskWindowingMode: SecurityException (expected)",
        "[INFO ] wm overscan applied on display 1 inset=80,50"
      ],
      "caption": "Protokoll — Echtzeitfilter, Export verfügbar"
    }
  },
  "faq": {
    "title": "9. FAQ & Fehlerbehebung",
    "items": [
      {
        "question": "❓ Das Popup 'ADB-Debugging zulassen?' erscheint nicht",
        "answer": "Stellen Sie sicher, dass TCP-ADB-Debugging in den Entwicklereinstellungen des Infotainments aktiviert ist.",
        "items": []
      },
      {
        "question": "❓ Die App erscheint nach der Auswahl nicht auf dem Cluster",
        "answer": "",
        "items": [
          "Stellen Sie sicher, dass Sie zuerst auf Projektion aktivieren getippt haben.",
          "Manche Apps verweigern den Start auf einem sekundären Display. Prüfen Sie die Logs.",
          "Schließen und öffnen Sie DashCast erneut."
        ]
      },
      {
        "question": "❓ Der Inhalt ist auf dem Cluster abgeschnitten oder verschoben",
        "answer": "Verwenden Sie 📐 Anpassen im Steuerfeld, um die Ränder pro App zu justieren.",
        "items": []
      },
      {
        "question": "❓ Eine App ist auf dem Cluster eingefroren",
        "answer": "Tippen Sie auf das ❌ Rote Kreuz neben der App. Das erzwingt den Prozess-Stopp und bereinigt die Zuletzt geöffneten.",
        "items": []
      },
      {
        "question": "❓ Die Schaltflächen ← Haupt und ✕ bleiben nach dem App-Schließen sichtbar",
        "answer": "DashCast erkennt App-Schließungen automatisch (via /proc). Falls die UI hängt, tippen Sie auf Projektion stoppen.",
        "items": []
      }
    ]
  },
  "footer": "DashCast v0.5.1 — BYD Seal EU · DiLink 3.0 · Android 10 · github.com/Kiroha/byd-dashcast"
};
