export default {
  "code": "it",
  "flag": "🇮🇹",
  "name": "Italiano",
  "title": "DashCast — Manuale Utente",
  "meta": "v0.7.2 · BYD Seal EU · DiLink 3.0 · Android 10",
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
    "text": "Scegli la tua lingua.",
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
        "tone": "green",
        "marker": "④",
        "label": "Ripristina",
        "text": "Ripristina BYD."
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
        "text": "Le app installate. ❌ per arrestare."
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
    "intro": "",
    "titleLabel": "Impostazioni",
    "clusterTypeLabel": "Tipo",
    "clusterOptions": [],
    "marginsLabel": "Margini",
    "horizontalMarginLabel": "Orizzontali",
    "verticalMarginLabel": "Verticali",
    "applyButton": "Applica",
    "resetButton": "Reset",
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
    }
  },
  "tools": {
    "title": "8. Strumenti",
    "intro": "",
    "table": {
      "headers": [],
      "rows": []
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
