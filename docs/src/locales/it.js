export default {
  "code": "it",
  "flag": "🇮🇹",
  "name": "Italiano",
  "title": "DashCast — Manuale Utente",
  "meta": "v0.5.1 · BYD Seal EU · DiLink 3.0 · Android 10",
  "manualName": "Manuale Utente",
  "tocTitle": "📋 Sommario",
  "sections": [
    "1. Panoramica",
    "2. Primo avvio — Scelta della lingua",
    "3. Schermata principale",
    "4. Proiezione sul cruscotto",
    "5. Pannello di controllo",
    "6. Fermare la proiezione",
    "7. Impostazioni",
    "8. Menu ⋮",
    "9. FAQ"
  ],
  "overview": {
    "title": "1. Panoramica",
    "text": "DashCast ti permette di proiettare qualsiasi app sul cruscotto digitale BYD.",
    "bullets": [
      "✅ BYD Seal EU",
      "✅ Nessuna modifica al sistema",
      "✅ ADB locale",
      "✅ Rilevamento automatico disconnessione app",
      "✅ Aggiornamenti OTA",
      "✅ Overscan per-app",
      "✅ Vista Griglia o Lista",
      "✅ Arresto forzato"
    ],
    "note": "💡 Abilita il debug ADB."
  },
  "firstLaunch": {
    "title": "2. Primo avvio",
    "text": "Al primo avvio, appare la schermata di benvenuto con una griglia 4×3 (3 colonne × 4 righe) che mostra le 12 lingue disponibili. Tocca il pulsante della tua lingua per selezionarla. Questa scelta viene memorizzata — la schermata non riapparirà a meno che tu non cambi lingua tramite ⋮ → 🌐 Lingua.",
    "welcomeSubtitle": "Dashboard Controller",
    "welcomeHint": "Seleziona lingua",
    "caption": "Scelta lingua"
  },
  "main": {
    "title": "3. Schermata principale",
    "text": "La schermata principale mostra lo stato e le app.",
    "status": "① Dashboard: non connesso",
    "buttons": [
      "② Attiva Proiezione",
      "③ Ferma Proiezione",
      "④ Ripristina Cruscotto Originale",
      "⑤ ⋮",
      "✕",
      "✕",
      "✕",
      "✕"
    ],
    "listTitle": "⑥ App installate",
    "apps": [
      "Maps",
      "YouTube",
      "Spotify",
      "Waze"
    ],
    "caption": "Schermata principale",
    "annotations": [
      {
        "tone": "",
        "marker": "①",
        "label": "Stato",
        "text": "Stato della connessione."
      },
      {
        "tone": "",
        "marker": "②",
        "label": "Attiva",
        "text": "Prepara la connessione."
      },
      {
        "tone": "red",
        "marker": "③",
        "label": "Ferma",
        "text": "Ferma la proiezione."
      },
      {
        "tone": "gray",
        "marker": "④",
        "label": "Ripristina Dashboard originale (menu ⋮)",
        "text": "Accessibile dal menu ⋮. Ferma la proiezione E ripristina il cruscotto BYD nativo (velocità, indicatori, autonomia…). Preferibile a 'Ferma proiezione' a fine utilizzo."
      },
      {
        "tone": "gray",
        "marker": "⑤",
        "label": "Menù",
        "text": "Strumenti extra."
      },
      {
        "tone": "gray",
        "marker": "⑥",
        "label": "App",
        "text": "Tocca un'app per proiettarla sul cluster. Pressione lunga → fissa l'app (⭐, sposta in cima alla lista). La casella 'Auto' contrassegna un'app per l'avvio automatico: viene inviata al cluster non appena la proiezione inizia. Il pulsante ✕ e le frecce ← / → appaiono solo sull'app attualmente attiva (nel cluster o nello schermo principale)."
      }
    ]
  },
  "projection": {
    "title": "4. Proiettare un'app",
    "steps": [
      "Premi Attiva.",
      "Seleziona l'app.",
      "Usa i controlli."
    ],
    "activeStatus": "Dashboard: Maps ✓",
    "buttons": [
      "Attiva Proiezione",
      "📺 Specchio",
      "Ferma Proiezione",
      "Ripristina Cruscotto Originale",
      "⋮",
      "← Principale",
      "✕",
      "→ Cluster",
      "✕",
      "→ Cluster",
      "✕",
      "📐 Regola",
      "⬛⬛ Dividi",
      "Nascondi ▼"
    ],
    "listTitle": "App installate",
    "apps": [
      "Maps",
      "YouTube",
      "Spotify"
    ],
    "controlLabel": "Controllo",
    "controlApp": "Maps",
    "mirrorText": "Attivo ✓",
    "caption": "App proiettata",
    "annotations": [
      {
        "tone": "green",
        "marker": "●",
        "label": "Verde",
        "text": "App in esecuzione."
      },
      {
        "tone": "",
        "marker": "→",
        "label": "Cluster",
        "text": "Invia."
      }
    ]
  },
  "control": {
    "title": "5. Pannello di controllo",
    "intro": "Controlli:",
    "mirror": {
      "title": "Specchio",
      "text": "",
      "note": ""
    },
    "resize": {
      "title": "Regola",
      "text": "",
      "note": ""
    },
    "relaunch": {
      "title": "5.5 Riavvia (↺)",
      "text": "Il pulsante ↺ (arancione) forza l'arresto dell'app attualmente proiettata sul cluster, poi la riavvia immediatamente dall'inizio. Utile se l'app è bloccata o la visualizzazione è ferma sul cluster."
    },
    "split": {
      "title": "Dividi",
      "text": "",
      "items": [],
      "extra": ""
    },
    "hide": {
      "title": "Nascondi",
      "text": ""
    }
  },
  "stopping": {
    "title": "6. Stop",
    "intro": "Due opzioni.",
    "table": {
      "headers": [
        "Tasto",
        "Azione",
        "Quando"
      ],
      "rows": []
    },
    "warning": "Avviso"
  },
  "settings": {
    "title": "7. Impostazioni",
    "intro": "Accedi alle Impostazioni tramite ⋮ → ⚙️ Impostazioni. La schermata contiene tre sezioni:",
    "titleLabel": "Impostazioni",
    "clusterTypeLabel": "Tipo",
    "clusterOptions": [],
    "marginsLabel": "Margini",
    "horizontalMarginLabel": "Orizzontali",
    "verticalMarginLabel": "Verticali",
    "applyButton": "Applica",
    "resetButton": "Reset",
    "updatesLabel": "Aggiornamenti",
    "prereleaseLabel": "Includi versioni pre-release (alpha/beta)",
    "prereleaseHint": "Ricevi versioni di test prima del rilascio ufficiale.",
    "caption": "",
    "type": {
      "title": "Tipo",
      "text": ""
    },
    "margins": {
      "title": "Margini",
      "text": "",
      "items": [],
      "applyText": "",
      "note": ""
    },
    "updates": {
      "title": "7.3 Aggiornamenti",
      "text": "Attiva 'Includi versioni pre-release (alpha/beta)' per ricevere build di test prima del rilascio ufficiale. Per verificare manualmente: ⋮ → 🔄 Controlla aggiornamenti. Gli aggiornamenti vengono scaricati direttamente da GitHub Releases — nessun Play Store richiesto."
    }
  },
  "tools": {
    "title": "8. Strumenti",
    "intro": "",
    "table": {
      "headers": [],
      "rows": [
        ["⚙️ Impostazioni", "Apre le impostazioni: Tipo cluster, Margini overscan globali, Aggiornamenti (pre-release)."],
        ["🌐 Lingua", "Torna alla schermata di selezione lingua per cambiare la lingua dell'interfaccia."],
        ["🔄 Controlla aggiornamenti", "Verifica se è disponibile una nuova versione di DashCast su GitHub. Se pre-release abilitata nelle Impostazioni, propone anche versioni alpha/beta."],
        ["⊞ Modalità griglia / 📋 Modalità lista", "Alterna la lista app tra modalità lista (1 colonna) e griglia (5 colonne). Accessibile anche tramite il pulsante ⊞ nell'intestazione della lista."],
        ["Ripristina Dashboard originale", "Ferma la proiezione E ripristina il cruscotto BYD nativo. Attivo solo durante una proiezione."],
        ["🔧 Diagnostica", "Strumenti avanzati per sviluppatori — connessione ADB, creazione VirtualDisplay, analisi SurfaceFlinger, sniffer logcat per reverse engineering."],
        ["📋 Report di sistema", "Genera un report completo (display, API BYD, permessi, pacchetti) — utile per il supporto o per aprire una segnalazione."],
        ["📜 Log", "Visualizzatore log in tempo reale — filtra per tag/livello, condividi via email o file."]
      ]
    },
    "logs": {
      "title": "Logs",
      "header": "",
      "clearButton": "",
      "shareButton": "",
      "filterPlaceholder": "",
      "lines": [],
      "caption": ""
    }
  },
  "faq": {
    "title": "9. FAQ",
    "items": []
  },
  "footer": "DashCast v0.5.1"
};
