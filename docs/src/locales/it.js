export default {
  code: 'it',
  flag: '🇮🇹',
  name: 'Italiano',
  title: 'DashCast — Manuale utente',
  manualName: 'Manuale utente',
  meta: 'v0.9.92-alpha · BYD Seal EU · DiLink 3.0 · Android 10',
  tocTitle: '📋 Indice',

  intro: {
    title: '0. Introduzione',
    lead:
      "DashCast permette di mostrare qualsiasi app Android dallo schermo centrale BYD sul cruscotto digitale. Puoi avere Maps, Waze, Spotify o YouTube direttamente dietro al volante mantenendo accessibili in ogni momento le indicazioni native BYD (velocità, indicatori, autonomia).",
    bullets: [
      "✅ Compatibile con BYD Seal EU (DiLink 3.0, firmware Di3.0 / 6125F).",
      "✅ Nessuna modifica al sistema: DashCast si installa come app normale.",
      "✅ ADB locale TCP — nessun computer dopo la prima autorizzazione.",
      "✅ 12 lingue, scelte al primo avvio.",
      "✅ Aggiornamenti OTA integrati (canale alpha opzionale).",
      "✅ Margini overscan salvati per ogni app.",
      "✅ Specchio a schermo intero touch per pilotare il cluster dalla centrale.",
      "✅ Modalità split (due app affiancate sul cluster).",
    ],
    note:
      "💡 Prerequisito unico: attivare il debug ADB wireless in Impostazioni BYD → Sviluppatore. Al primo avvio appare un dialogo « Consentire il debug? » — spunta « Consenti sempre » e conferma. Non dovrai mai ripeterlo.",
  },

  sections: [
    {
      id: 'welcome',
      screen: 'screen-1',
      title: '1. Schermata di benvenuto — scelta lingua',
      lead:
        "Al primissimo avvio DashCast mostra una griglia con le 12 lingue disponibili. Tocca quella che preferisci; la scelta viene salvata e la schermata di benvenuto non riapparirà più. Puoi cambiarla in qualsiasi momento da Impostazioni → Lingua.",
      mockupLabel: 'Apri schermata 1 (Benvenuto)',
      featuresTitle: 'Dettagli',
      features: [
        {
          title: '12 lingue supportate',
          text:
            "Français, English, Deutsch, Italiano, Türkçe, Español, Русский, Українська, العربية, O'zbekcha, Қазақша, Беларуская. La lingua scelta viene applicata subito, senza riavvio.",
        },
        {
          title: 'Direzione di lettura automatica',
          text:
            "L'arabo passa automaticamente al layout da destra a sinistra (RTL): la barra di navigazione si sposta a destra, le liste si invertono, le icone restano leggibili.",
        },
        {
          title: 'Modificabile in qualsiasi momento',
          text:
            "Per cambiare lingua in seguito: pressione lunga sul logo DashCast in alto nella barra laterale → 🌐 Lingua. La nuova lingua si applica al volo.",
        },
      ],
      howTo: {
        title: 'Come si fa',
        steps: [
          "Avvia DashCast (icona blu nel drawer app BYD).",
          "La schermata di benvenuto mostra la griglia 4×3 di lingue.",
          "Tocca la tua lingua. L'interfaccia cambia immediatamente.",
          "Si apre la schermata principale — DashCast è pronto.",
        ],
      },
      note:
        "ℹ️ Se cambi lingua mentre è attiva una proiezione, questa prosegue senza interruzione; viene tradotta solo l'interfaccia DashCast.",
    },

    {
      id: 'main',
      screen: 'screen-2',
      title: '2. Schermata principale — App e Cluster',
      lead:
        "Schermata centrale di DashCast. A sinistra la lista di tutte le app installate con ricerca, filtri di categoria e preferiti. A destra la preview live del cluster con le azioni principali: schermo intero, screenshot, riconnetti, ferma proiezione.",
      mockupLabel: 'Apri schermata 2 (Principale)',
      featuresTitle: 'Tutto ciò che puoi fare',
      features: [
        {
          title: '🔍 Barra di ricerca',
          text:
            "Digita qualche lettera per filtrare la lista al volo (cerca sia nel nome sia nel package). Il pulsante ▦ a destra alterna lista e griglia.",
        },
        {
          title: '🏷️ Filtri di categoria',
          text:
            "I chip colorati (Tutte / Navigazione / Multimedia / Comunicazione / Sistema) raggruppano le app automaticamente. Il numero tra parentesi indica quante sono visibili.",
        },
        {
          title: '⭐ Preferiti fissati',
          text:
            "La sezione « Preferiti » mantiene le tue app più usate in alto. Per aggiungere/togliere: pressione lunga sull'app → ⭐ Aggiungi/Rimuovi dai preferiti.",
        },
        {
          title: '👆 Tocco breve — proietta',
          text:
            "Tocca un'app per inviarla subito al cluster. Se la proiezione non era attiva, parte automaticamente (warm-up cluster ~2 s).",
        },
        {
          title: '👆⏱️ Pressione lunga — menu azioni',
          text:
            "Tieni premuta una qualsiasi app per aprire un menu a schermo intero: ⭐ Preferito, Auto-avvio (con la proiezione), Sposta su cluster / centrale, ✕ Forza arresto.",
        },
        {
          title: '🚦 Preview live del cluster',
          text:
            "Il pannello destro rispecchia ciò che è sul cluster (velocità, marcia, batteria, autonomia). La latenza (12 ms) conferma una connessione corretta.",
        },
        {
          title: '👁️ Preview a schermo intero',
          text:
            "Tocca « Schermo intero » per estendere la preview a tutto il display centrale. Utile per scrivere un indirizzo in Maps con tastiera completa: tutto viene replicato sul cluster.",
        },
        {
          title: '📸 Screenshot',
          text:
            "Il pulsante « Cattura » salva la vista del cluster come PNG in /sdcard/Pictures/DashCast/. Utile per condividere un percorso o documentare un problema.",
        },
        {
          title: '↻ Riconnetti',
          text:
            "Se l'app proiettata si è bloccata, « Riconnetti » ripristina il flusso video senza toccare il cluster originale.",
        },
        {
          title: '⏹ Ferma specchio',
          text:
            "Termina la proiezione in modo pulito. Tocco breve = stop morbido (cluster torna a BYD nativo via ADB). Pressione lunga = menu arricchito con « Ripristina cluster originale » che forza il ripristino con la dimensione cluster definita nelle Impostazioni.",
        },
      ],
      howTo: {
        title: 'Come proiettare un\'app sul cluster',
        steps: [
          "Nella schermata principale individua l'app (es. Maps).",
          "Tocca l'icona → la proiezione parte, il cluster mostra l'app in ~2 s.",
          "Il pannello destro mostra in tempo reale ciò che vede il cluster.",
          "Per scrivere (cercare un indirizzo): tocca « Schermo intero » → l'app si estende sulla centrale → digita l'indirizzo → tutto si riflette sul cluster.",
          "Per fermare: tocca « Ferma specchio » (il cluster torna al BYD nativo).",
        ],
      },
      tipsTitle: 'Suggerimenti',
      tips: [
        "💡 Auto-avvio: attiva questo interruttore su un'app per proiettarla automaticamente a ogni avvio di DashCast.",
        "💡 Modalità split: dal menu lungo di una seconda app scegli « Invia come split » per avere 2 app affiancate sul cluster.",
        "💡 Margini: se l'app deborda dal cluster, apri Impostazioni → Margini e regola i cursori. Salvato per app.",
        "💡 Touch a schermo intero: in modalità schermo intero le tue dita sulla centrale pilotano davvero l'app — tastiera, scroll, gesture, tutto funziona.",
      ],
    },

    {
      id: 'settings',
      screen: 'screen-3',
      title: '3. Impostazioni',
      lead:
        "Le Impostazioni raccolgono le opzioni globali e la regolazione dell'immagine della proiezione. La barra sinistra resta disponibile: puoi muoverti tra App, Impostazioni, Diag, Sistema e Diario senza perdere la posizione.",
      mockupLabel: 'Apri schermata 3 (Impostazioni)',
      featuresTitle: 'Sezioni disponibili',
      features: [
        {
          title: '📺 Tipo di cluster',
          text:
            "Seleziona la dimensione fisica del cruscotto: 8.8″ (sendInfo cmd 29), 12.3″ Seal EU (cmd 30, default) o 10.25″ (cmd 31). Valore usato in particolare da « Ripristina cluster originale ».",
        },
        {
          title: '🌐 Lingua',
          text:
            "12 lingue disponibili. Cambio istantaneo, nessun riavvio.",
        },
        {
          title: '↔️ Margine orizzontale (overscan)',
          text:
            "Cursore 0–200 px. Aggiunge bande nere a sinistra/destra per compensare bordi tagliati sul tuo cluster. Salvato per app: Maps può usare 80 px e Spotify 0.",
        },
        {
          title: '↕️ Margine verticale (overscan)',
          text:
            "Cursore 0–200 px. Idem sopra/sotto. I margini combinati si applicano a livello di VirtualDisplay: l'app non « vede » le zone tagliate.",
        },
        {
          title: '✅ Applica / 🔄 Reset',
          text:
            "« Applica » spinge i nuovi margini sulla proiezione attiva subito. « Reset » riporta l'app corrente a 0/0.",
        },
        {
          title: '📦 Aggiornamenti OTA',
          text:
            "DashCast verifica GitHub Releases automaticamente. Spunta « Includi pre-release » per il canale alpha (più frequente ma sperimentale).",
        },
        {
          title: '🚗 Avvio automatico col veicolo',
          text:
            "Se attivo, DashCast parte con la macchina e ripristina l'ultima app proiettata. Altrimenti lo avvii manualmente.",
        },
      ],
      howTo: {
        title: 'Come regolare i margini di un\'app',
        steps: [
          "Proietta l'app da regolare (es. Waze).",
          "Apri Impostazioni → Margini.",
          "Sposta il cursore orizzontale finché i bordi sx/dx vanno bene.",
          "Idem col verticale.",
          "Tocca « Applica » → la proiezione si aggiorna in diretta, senza riavviare l'app.",
          "L'impostazione viene salvata solo per quella app (ognuna ha i suoi margini).",
        ],
      },
      note:
        "⚠️ Se cambi il tipo di cluster, riavvia DashCast per ricalcolare i valori di riferimento.",
    },

    {
      id: 'diagnostics',
      screen: 'screen-4',
      title: '4. Diagnostica',
      lead:
        "La scheda Diag è un pannello interno per casi in cui la proiezione non si comporta come previsto. La maggior parte degli utenti non ne avrà mai bisogno — è pensata per supporto e debug.",
      mockupLabel: 'Apri schermata 4 (Diag)',
      featuresTitle: 'Strumenti',
      features: [
        {
          title: 'Stato di ClusterService',
          text:
            "Verifica che il servizio Android responsabile della proiezione sia attivo. Se « non collegato », un pulsante lo riavvia.",
        },
        {
          title: 'Stato del VirtualDisplay',
          text:
            "Mostra l'ID del display virtuale creato per il cluster, la sua risoluzione e se è agganciato un Surface Qt.",
        },
        {
          title: 'Connessione ADB locale',
          text:
            "Test rapido del tunnel ADB verso localhost:5555. Se fallisce, di solito è stato disattivato l'ADB wireless dalle Impostazioni BYD.",
        },
        {
          title: 'logcat mirato',
          text:
            "Cattura le ultime 200 righe di logcat filtrate su DashCast / AutoContainer / xdja. Il pulsante « Condividi » invia il report.",
        },
      ],
      howTo: {
        title: 'Quando usare questa scheda',
        steps: [
          "Cluster nero dopo aver toccato un'app → controlla ClusterService e VirtualDisplay.",
          "L'app dice « ADB non disponibile » → Diag → pulsante « Test ADB ».",
          "Il supporto chiede un report → Diag → « Condividi logcat ».",
          "Hai appena installato un aggiornamento e vuoi confermare la versione attiva.",
        ],
      },
      note:
        "ℹ️ Questa scheda non modifica nulla da sola: i pulsanti eseguono test in sola lettura salvo dove indicato.",
    },

    {
      id: 'sysinfo',
      screen: 'screen-5',
      title: '5. Informazioni di sistema',
      lead:
        "Pannello in sola lettura sull'ambiente hardware/software. Qui trovi la versione DashCast, il firmware BYD, la versione Android e l'identificativo del cluster.",
      mockupLabel: 'Apri schermata 5 (Sistema)',
      featuresTitle: 'Informazioni mostrate',
      features: [
        {
          title: '🚗 Veicolo',
          text:
            "Modello BYD rilevato, VIN (se disponibile), build firmware (es. Di3.0 / 6125F), data di build.",
        },
        {
          title: '📱 Android',
          text:
            "Versione Android (10), API level (29), patch di sicurezza, build ID DiLink.",
        },
        {
          title: '🔌 DashCast',
          text:
            "Versione installata, versionCode, canale (stable / alpha), ultimo controllo OTA, link alle note di rilascio.",
        },
        {
          title: '🖥️ Cluster',
          text:
            "Tipo rilevato (8.8″ / 12.3″ / 10.25″), risoluzione reale, ID VirtualDisplay attivo, package Qt attivo (com.xdja.containerservice).",
        },
        {
          title: '📦 App tracciate',
          text:
            "Numero app rilevate, preferiti fissati, app con auto-avvio.",
        },
      ],
      tipsTitle: 'Suggerimenti',
      tips: [
        "💡 Pressione lunga su una riga per copiare il valore negli appunti (utile per un bug report).",
        "💡 Il pulsante « Esporta » in fondo salva tutto in un file di testo (/sdcard/DashCast/sysinfo.txt).",
      ],
    },

    {
      id: 'journal',
      screen: 'screen-6',
      title: '6. Diario',
      lead:
        "Diario interno di DashCast: registra ogni azione importante (proiezioni, ripristini, errori ADB, aggiornamenti). Utile per capire un comportamento inatteso o inviare un report al supporto.",
      mockupLabel: 'Apri schermata 6 (Diario)',
      featuresTitle: 'Funzionalità',
      features: [
        {
          title: '🔍 Filtro',
          text:
            "Inserisci una parola chiave per mantenere solo le righe rilevanti (es. « ADB », « Maps », « error »). Il filtro non distingue maiuscole/minuscole.",
        },
        {
          title: '🎨 Codice colore',
          text:
            "🟢 INFO (verde) — funzionamento normale. 🟠 WARN (arancio) — attenzione. 🔴 ERROR (rosso) — errore. ⚪ DEBUG (grigio) — dettaglio tecnico.",
        },
        {
          title: '🗑 Svuota',
          text:
            "Svuota il diario. Il logcat di sistema non è influenzato — viene cancellata solo la cronologia in memoria di DashCast.",
        },
        {
          title: '📤 Condividi',
          text:
            "Esporta il diario corrente come .txt e apre il selettore di condivisione Android (email, Telegram, file). Include automaticamente versione DashCast e modello BYD.",
        },
        {
          title: '⏰ Timestamp',
          text:
            "Ogni riga è preceduta dall'orario locale (HH:mm:ss.mmm). Le operazioni lunghe (avvio Maps, ripristino) vengono misurate e mostrate.",
        },
      ],
      howTo: {
        title: 'Come inviare un bug report',
        steps: [
          "Riproduci il problema (es. l'app resta nera all'avvio).",
          "Apri Diario.",
          "Tocca « Condividi ».",
          "Scegli il canale (Telegram, email, GitHub Issues).",
          "Il .txt allegato contiene la traccia completa con il contesto (versione, modello, firmware).",
        ],
      },
      note:
        "🔒 Nessun dato personale (contatti, GPS, contenuti delle app) viene registrato — solo azioni DashCast e codici tecnici di ritorno.",
    },
  ],

  faq: {
    title: '7. FAQ — Domande frequenti',
    items: [
      {
        question: '❓ Il cluster resta nero quando tocco un\'app',
        answer:
          "Tre cause possibili: (1) ADB wireless disattivato — controlla Impostazioni BYD → Sviluppatore. (2) ClusterService non attivo — Diag, pulsante « Riavvia ». (3) L'app è appena crashata — tocca « Riconnetti » nel pannello destro.",
      },
      {
        question: '❓ L\'immagine deborda / viene tagliata sul cluster',
        answer:
          "Apri Impostazioni → Margini e regola i cursori orizzontale/verticale finché i bordi vanno bene. Salvato per app, una volta sola.",
      },
      {
        question: '❓ Come torno al cruscotto BYD originale?',
        answer:
          "Un tocco breve su « Ferma specchio » basta nel 95 % dei casi. Se il cluster si blocca, pressione lunga sullo stesso pulsante → menu → « Ripristina cluster originale »: DashCast forza la sequenza sendInfo del tipo di cluster.",
      },
      {
        question: '❓ DashCast scarica la batteria 12 V?',
        answer:
          "No — DashCast si ferma automaticamente allo spegnimento dell'auto (Android.intent.action.SCREEN_OFF + broadcast di disconnessione BMS). Nessun servizio resta attivo a motore spento.",
      },
      {
        question: '❓ Voglio contribuire o segnalare un bug',
        answer:
          "GitHub: https://github.com/Kiroha/byd-dashcast — Issues per i bug, Discussions per le domande. Allega sempre un'esportazione del Diario (Diario → Condividi) per accelerare la diagnosi.",
      },
      {
        question: '❓ Quali app funzionano sul cluster?',
        items: [
          "✅ Navigazione: Google Maps, Waze, Yandex Navi, OsmAnd, Magic Earth.",
          "✅ Multimedia: Spotify, YouTube, YouTube Music, Netflix (preferire l'orizzontale).",
          "✅ Comunicazione: Telegram (modalità lettura), WhatsApp (notifiche).",
          "✅ Sistema: fotocamera, meteo, calendario.",
          "⚠️ App con DRM Widevine L1 (Disney+, Prime Video) possono rifiutare di renderizzare su VirtualDisplay — limitazione Android, non DashCast.",
        ],
      },
      {
        question: '❓ Aggiornamenti: stable o alpha?',
        answer:
          "Il canale stable (default) è testato in auto per almeno 1 settimana prima della pubblicazione. L'alpha (Impostazioni → Aggiornamenti) riceve i build appena compilati — utile per anticipare, ma può introdurre regressioni temporanee.",
      },
    ],
  },

  footer:
    'DashCast è un progetto open-source distribuito con licenza GPL-3.0. Nessuna affiliazione con BYD Auto Co., Ltd.',
};
