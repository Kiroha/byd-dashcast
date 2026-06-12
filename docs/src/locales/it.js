export default {
  code: 'it',
  flag: '🇮🇹',
  name: 'Italiano',
  title: 'DashCast — Manuale utente',
  manualName: 'Manuale utente',
  meta: 'v1.4.x · BYD Seal EU · DiLink 3.0 · Android 10',
  tocTitle: '📋 Indice',

  intro: {
    title: '0. Introduzione',
    lead:
      'DashCast mostra qualsiasi applicazione Android dello schermo centrale della tua BYD sul quadro strumenti (cluster digitale). Maps, Waze, Spotify o ABRP direttamente dietro il volante — e con la modalità Layout, più app contemporaneamente, ognuna nella sua zona. Il tutto senza modificare il sistema.',
    bullets: [
      '✅ Compatibile con BYD Seal EU (DiLink 3.0, firmware Di3.0 / 6125F).',
      '✅ Nessuna modifica di sistema: DashCast si installa come una normale app.',
      '✅ ADB locale su TCP — nessun computer necessario dopo la prima autorizzazione.',
      "✅ 13 lingue d'interfaccia, scelta al primo avvio.",
      '✅ Mirror touch in tempo reale: controlla il cluster dallo schermo centrale.',
      '✅ Modalità Layout: più app affiancate sul cluster, zone disegnate col dito.',
      '✅ Avvio automatico: proiezione + app (o layout preferito) appena DashCast si apre.',
      '✅ Margini (overscan) memorizzati per applicazione.',
      '✅ Aggiornamenti OTA integrati (canale beta opzionale).',
    ],
    note:
      '💡 Unico prerequisito: attivare il debug ADB wireless in Impostazioni BYD → Sviluppatore. Al primo avvio appare la finestra «Consentire il debug?» — spunta «Consenti sempre» e conferma. Non dovrai mai ripeterlo.',
  },

  sections: [
    {
      id: 'welcome',
      screen: 'screen-1',
      title: '1. Schermata di benvenuto — scelta della lingua',
      lead:
        'Al primissimo avvio, DashCast mostra la griglia con le 13 lingue disponibili. Tocca la tua: la scelta viene memorizzata e la schermata non riappare più. Potrai cambiare lingua in qualsiasi momento dalle Impostazioni.',
      mockupLabel: 'Vedi schermata 1 (Benvenuto)',
      featuresTitle: 'Dettagli',
      features: [
        {
          title: '13 lingue supportate',
          text:
            "Français, English, Deutsch, Italiano, Türkçe, Español, Polski, Русский, Українська, العربية, O'zbekcha, Қазақша, Беларуская. La lingua scelta è applicata subito, senza riavvio.",
        },
        {
          title: 'Direzione di lettura automatica',
          text:
            "L'arabo passa automaticamente al layout destra-sinistra (RTL): la barra di navigazione si sposta a destra e le liste si invertono.",
        },
        {
          title: 'Modificabile in ogni momento',
          text: 'Per cambiare lingua più tardi: Impostazioni → Lingua. Applicata al volo.',
        },
      ],
      howTo: {
        title: 'Come fare',
        steps: [
          "Avvia DashCast (icona blu nel drawer delle app BYD).",
          'Appare la schermata di benvenuto con la griglia delle lingue.',
          "Tocca la tua lingua. L'interfaccia cambia immediatamente.",
          'Si apre la schermata principale — sei pronto.',
        ],
      },
    },

    {
      id: 'main',
      screen: 'screen-2',
      title: '2. Schermata principale — App & Cluster',
      lead:
        "La schermata centrale di DashCast. A sinistra: tutte le tue applicazioni con ricerca, filtri e preferiti. A destra: l'anteprima in tempo reale del cluster, i pulsanti Mirror a schermo intero / Ferma proiezione, e il carosello dei layout per scegliere la tua disposizione multi-app preferita.",
      mockupLabel: 'Vedi schermata 2 (Principale)',
      featuresTitle: 'Tutto ciò che puoi fare',
      features: [
        {
          title: '👆 Tocco breve — proiettare',
          text:
            "Tocca un'app per inviarla al cluster. Se la proiezione non è attiva, parte automaticamente (~2 s di riscaldamento) e l'app appare dietro il volante.",
        },
        {
          title: '👆⏱️ Pressione lunga — menu azioni',
          text:
            "Tieni premuta un'app: ⭐ Preferito, Auto-launch (proiettare questa app a ogni avvio di DashCast), Sposta al cluster / schermo principale, ✕ Arresto forzato.",
        },
        {
          title: '🔍 Ricerca e filtri',
          text:
            'La barra di ricerca filtra mentre digiti (nome o package). I chip per categoria (Tutte / Navigazione / Media…) raggruppano le app; il pulsante ▦ alterna lista/griglia.',
        },
        {
          title: '🚦 Anteprima cluster in tempo reale',
          text:
            "Il pannello destro riflette in diretta il cluster. I tuoi tocchi sull'anteprima vengono trasmessi all'app proiettata — scroll, zoom, tastiera, tutto funziona.",
        },
        {
          title: '👁️ Mirror a schermo intero',
          text:
            "Estende l'anteprima a tutto lo schermo centrale: ideale per digitare un indirizzo in Maps con la tastiera completa. Tutto è replicato sul cluster in tempo reale.",
        },
        {
          title: '⏹ Ferma proiezione',
          text:
            'Termina la proiezione in modo pulito e ripristina il quadro BYD originale (velocità, indicatori, ADAS) con la dimensione definita nelle Impostazioni.',
        },
        {
          title: '🗂️ Carosello dei layout',
          text:
            'Sotto i pulsanti, ogni scheda mostra una mini-anteprima delle zone di un layout. Tocca una scheda per renderla layout preferito (stella + bordo blu). «Modalità libera» disattiva i layout; «＋ Gestisci» apre l\'editor.',
        },
        {
          title: '📺 Pulsante flottante',
          text:
            'Un pulsante 📺 resta sopra le altre app: tocco = apri il mirror, pressione lunga = cambio rapido tra le ultime app proiettate.',
        },
      ],
      howTo: {
        title: "Come proiettare un'app sul cluster",
        steps: [
          "Trova l'app desiderata (es. Maps) — ricerca o filtri se serve.",
          "Tocca la sua icona → la proiezione parte e il cluster passa all'app in ~2 s.",
          "L'anteprima destra mostra in diretta ciò che è sul cluster.",
          'Per digitare testo: «Mirror a schermo intero» → digita il tuo indirizzo → tutto è replicato.',
          'Per fermare: «Ferma proiezione» — il cluster torna al BYD nativo.',
        ],
      },
      tipsTitle: 'Suggerimenti',
      tips: [
        "💡 Auto-launch: scegli un'app (pressione lunga → Auto-launch) perché si proietti automaticamente a ogni avvio di DashCast — la proiezione si attiva da sola.",
        '💡 Layout preferito: la scheda selezionata nel carosello è quella che l\'avvio automatico attiverà (vedi sezione Layout).',
        "💡 Margini: se l'app deborda dal cluster, Impostazioni → Margini, slider orizzontale/verticale. Memorizzato per app.",
      ],
    },

    {
      id: 'settings',
      screen: 'screen-3',
      title: '3. Impostazioni',
      lead:
        'Le opzioni globali: dimensione del cluster, lingua, margini, comportamento all\'avvio, modalità Layout e aggiornamenti. La barra laterale resta disponibile — passa da una schermata all\'altra senza perdere la posizione.',
      mockupLabel: 'Vedi schermata 3 (Impostazioni)',
      featuresTitle: 'Sezioni principali',
      features: [
        {
          title: '📺 Tipo di cluster',
          text:
            'Dimensione fisica del tuo quadro: 8.8″, 12.3″ (consigliata su Seal EU — corregge lo stiramento ADAS) o 10.25″. Usata da «Ferma proiezione» per ripristinare la modalità giusta.',
        },
        {
          title: '↔️↕️ Margini (overscan)',
          text:
            'Slider orizzontale/verticale (0–200 px) per compensare i bordi tagliati. Memorizzato per applicazione: Maps può avere 80 px mentre Spotify resta a 0. «Applica» regola la proiezione a caldo.',
        },
        {
          title: '🚗 Avvio con il veicolo',
          text:
            "Se attivo, DashCast parte con l'auto e ripristina l'ultima app proiettata. Altrimenti lancialo dal drawer BYD.",
        },
        {
          title: '🗂️ Modalità Layout',
          text:
            'Attiva la proiezione multi-applicazione con zone personalizzate (richiede il Proxy ADB Daemon, gestito automaticamente). Fa apparire il carosello sulla schermata principale e la scheda Layout.',
        },
        {
          title: '⭐ Layout preferito automatico',
          text:
            "All'avvio di DashCast: attiva la proiezione del cluster, il layout preferito, e lancia le app collegate a ogni zona. La tua configurazione multi-app completa, senza un solo tocco.",
        },
        {
          title: '⚡ Pre-creare gli slot all\'avvio',
          text:
            "Prepara gli schermi virtuali del layout preferito all'apertura (senza lanciare le app) — l'attivazione del layout è poi quasi istantanea.",
        },
        {
          title: '📦 Aggiornamenti OTA',
          text:
            'DashCast verifica GitHub a ogni avvio. Spunta «Includi pre-versioni» per il canale beta (novità prima, meno stabilità).',
        },
        {
          title: '🌐 Lingua',
          text: '13 lingue — il cambio è istantaneo.',
        },
      ],
      howTo: {
        title: "Come regolare i margini di un'app",
        steps: [
          "Proietta l'app da regolare (es. Waze).",
          'Impostazioni → Margini.',
          'Sposta lo slider orizzontale finché i bordi sinistro/destro sono corretti.',
          "Idem in verticale, poi «Applica» — regolazione a caldo, senza riavviare l'app.",
          "L'impostazione è salvata solo per questa app.",
        ],
      },
      note:
        '⚠️ Se cambi il tipo di cluster, ferma e rilancia la proiezione perché il ripristino usi la modalità giusta.',
    },

    {
      id: 'layouts',
      screen: 'screen-7',
      title: '4. Layout — più app sul cluster',
      lead:
        'La modalità Layout divide il cluster in zone personalizzate, ognuna con la propria applicazione: Waze a sinistra, Spotify a destra, per esempio. Disegni le zone col dito, colleghi un\'app a ogni zona, e il layout si attiva con un tocco — o da solo all\'avvio.',
      mockupLabel: 'Vedi schermata 7 (Layout)',
      featuresTitle: 'Funzionalità',
      features: [
        {
          title: '✏️ Disegnare una zona',
          text:
            'Sul canvas (replica del cluster 1920×720), trascina il dito per tracciare un rettangolo. Si apre una finestra: nome, posizione/dimensioni al pixel, e app da collegare.',
        },
        {
          title: '🔗 Collegare un\'applicazione',
          text:
            "Ogni zona può essere collegata a un'app: all'attivazione del layout, l'app si lancia automaticamente nella sua zona. Una zona senza app resta libera.",
        },
        {
          title: '✋ Spostare e ridimensionare',
          text:
            'Trascina una zona per spostarla; le maniglie bianche agli angoli ridimensionano. I bordi si agganciano automaticamente ai limiti del cluster e alle zone vicine.',
        },
        {
          title: '✏️ Modificare una zona esistente',
          text:
            "Tocca una zona (sul canvas o sul suo chip in basso): rinominala, regola la geometria, cambia l'app collegata o eliminala. Pressione lunga = eliminazione rapida.",
        },
        {
          title: '💾 Layout salvati',
          text:
            'Salva quanti layout vuoi («Nav+Media», «Triplo schermo»…). Il pannello laterale li elenca con Attiva / Disattiva / Modifica / Elimina.',
        },
        {
          title: '⭐ Preferito e avvio automatico',
          text:
            'Il pulsante «Preferito» (o un tocco sulla scheda del carosello della schermata principale) designa il layout che «Layout preferito automatico» attiverà all\'avvio di DashCast — proiezione inclusa.',
        },
      ],
      howTo: {
        title: 'Crea il tuo primo layout',
        steps: [
          'Attiva «Modalità Layout» nelle Impostazioni.',
          'Apri la scheda Layout (barra laterale).',
          'Trascina il dito sul canvas per disegnare la prima zona (es. metà sinistra).',
          'Nella finestra: assegnale un nome, tocca «Collega un\'applicazione» → scegli Waze → Aggiungi.',
          'Disegna la seconda zona (metà destra), collega Spotify.',
          '«Salva» → dai un nome al layout (es. Nav+Media).',
          '«Preferito» per selezionarlo, poi attivalo: le due app si lanciano, ognuna nella sua zona.',
        ],
      },
      tipsTitle: 'Suggerimenti',
      tips: [
        '💡 Combinato con «Layout preferito automatico» (Impostazioni), la tua configurazione multi-app completa si monta da sola a ogni avvio di DashCast.',
        '💡 La mini-anteprima di ogni scheda del carosello mostra le zone reali del layout — riconoscibile a colpo d\'occhio.',
        "💡 Un'app rifiuta di apparire nella sua zona? Alcune app impongono il loro formato; prova una zona più vicina al 16:9.",
      ],
      note:
        'ℹ️ La modalità Layout si appoggia al Proxy ADB Daemon (avviato automaticamente). Primo avvio a freddo: conta 6–8 s prima che le app appaiano — è la sequenza di attivazione del cluster.',
    },

    {
      id: 'diagnostics',
      screen: 'screen-4',
      title: '5. Diagnostica',
      lead:
        'Pannello interno per le situazioni in cui la proiezione non funziona come previsto. La maggior parte degli utenti non ne avrà mai bisogno — esiste per il supporto e il debug.',
      mockupLabel: 'Vedi schermata 4 (Diagnostica)',
      featuresTitle: 'Strumenti disponibili',
      features: [
        {
          title: 'Test di connessione',
          text:
            'Verifica il tunnel ADB locale (localhost:5555), lo stato del ClusterService e la presenza dello schermo virtuale del cluster.',
        },
        {
          title: 'Sonde di piattaforma',
          text:
            'Rilevamento DiLink (2/3/4/5), inventario dei display, istanziazione delle API veicolo BYD (velocità, energia) e stato dei permessi BYDAUTO.',
        },
        {
          title: 'Report condivisibile',
          text:
            'Genera un report completo (sistema, display, servizi, permessi, metriche del daemon) esportabile come testo per il supporto.',
        },
      ],
      howTo: {
        title: 'Quando usare questa scheda',
        steps: [
          "Il cluster resta nero dopo aver toccato un'app → verifica ClusterService e il display virtuale.",
          "L'app segnala «ADB non disponibile» → pulsante «Testa ADB».",
          'Il supporto ti chiede un report → generalo e condividilo.',
        ],
      },
      note: 'ℹ️ I pulsanti sono test in sola lettura, salvo indicazione esplicita.',
    },

    {
      id: 'sysinfo',
      screen: 'screen-5',
      title: '6. Report di sistema',
      lead:
        'Pannello in sola lettura: versioni, display rilevati e stato in diretta dei servizi DashCast. La prima schermata da consultare quando qualcosa sembra anomalo.',
      mockupLabel: 'Vedi schermata 5 (Sistema)',
      featuresTitle: 'Informazioni mostrate',
      features: [
        {
          title: '🖥️ Display',
          text:
            'Schermo principale (risoluzione, densità) e display virtuale del cluster (1920×720) con stato in tempo reale.',
        },
        {
          title: '⚙️ Servizi',
          text:
            'ClusterService (proiezione), MirrorDaemon (mirror), Proxy ADB Daemon (operazioni privilegiate), AdbLocalClient (tunnel ADB) — ciascuno con pallino verde/rosso e pulsante di riavvio se fermo.',
        },
        {
          title: '📱 Versioni',
          text:
            'Versione DashCast installata, firmware BYD, versione Android/API, identificativi build DiLink.',
        },
        {
          title: '🔁 Replay proiezione',
          text:
            'Pulsante per ripetere la sequenza completa di attivazione del cluster (utile se il quadro è rimasto in uno stato intermedio).',
        },
      ],
      tipsTitle: 'Suggerimenti',
      tips: [
        '💡 «Proxy ADB Daemon» deve essere verde (RUN) per la modalità Layout — altrimenti tocca la sua riga per riavviarlo.',
        '💡 Il report completo è esportabile da questa schermata per accompagnare una segnalazione di bug.',
      ],
    },

    {
      id: 'journal',
      screen: 'screen-6',
      title: '7. Registro',
      lead:
        'Il registro interno di DashCast: tutte le azioni importanti (proiezioni, ripristini, errori ADB, aggiornamenti) sono tracciate in continuo. Utile per capire un comportamento inatteso o fornire un report al supporto.',
      mockupLabel: 'Vedi schermata 6 (Registro)',
      featuresTitle: 'Funzionalità',
      features: [
        {
          title: '🔍 Filtri',
          text:
            'Filtra per livello (DEBUG / INFO / WARN / ERROR) o per parola chiave (es. «ADB», «Maps», «error»).',
        },
        {
          title: '🎨 Codice colori',
          text:
            '🟢 INFO — operazione normale. 🟠 WARN — attenzione. 🔴 ERROR — fallimento. ⚪ DEBUG — dettaglio tecnico.',
        },
        {
          title: '📤 Condividi',
          text:
            'Esporta il registro in .txt e apre il menu di condivisione Android. Include la versione DashCast e il modello BYD.',
        },
        {
          title: '⏰ Timestamp',
          text:
            "Ogni riga è preceduta dall'ora locale (HH:mm:ss.mmm); le operazioni lunghe sono misurate.",
        },
      ],
      howTo: {
        title: 'Inviare una segnalazione di bug',
        steps: [
          'Riproduci il problema.',
          'Apri Registro → «Condividi».',
          'Scegli il tuo canale (Telegram, e-mail, GitHub Issues).',
          'Il file allegato contiene la traccia e il contesto (versione, modello, firmware).',
        ],
      },
      note:
        '🔒 Nessun dato personale (contatti, posizione GPS, contenuto delle app) viene registrato — solo le azioni DashCast e i codici di ritorno tecnici.',
    },
  ],

  faq: {
    title: '8. FAQ — Domande frequenti',
    items: [
      {
        question: "❓ Il cluster resta nero quando tocco un'app",
        answer:
          'Tre cause possibili: (1) ADB wireless disattivato — verifica Impostazioni BYD → Sviluppatore. (2) Un servizio fermo — scheda Sistema, riavvia la riga rossa. (3) L\'app è appena crashata — ritocca la sua icona.',
      },
      {
        question: "❓ L'immagine deborda / è tagliata sul cluster",
        answer:
          'Impostazioni → Margini: regola gli slider orizzontale/verticale finché i bordi sono corretti. Memorizzato per app — lo farai una sola volta.',
      },
      {
        question: '❓ Come torno al quadro BYD originale?',
        answer:
          'Tocca «Ferma proiezione» sulla schermata principale: DashCast ripristina il cluster nativo con la dimensione definita nelle Impostazioni. Se il quadro sembra bloccato: schermata Sistema → «Replay proiezione», poi ferma di nuovo.',
      },
      {
        question: '❓ Il mio layout preferito non parte all\'avvio',
        answer:
          'Verifica le tre condizioni nelle Impostazioni: «Modalità Layout» attiva, «Layout preferito automatico» attivo, e un layout marcato ⭐ preferito (carosello della schermata principale o pulsante Preferito della scheda Layout). Al primo avvio a freddo, conta 6–8 s.',
      },
      {
        question: '❓ DashCast scarica la batteria 12 V?',
        answer:
          "No — DashCast si ferma con l'auto. Nessun servizio in background resta attivo a motore spento.",
      },
      {
        question: '❓ Quali app funzionano sul cluster?',
        items: [
          '✅ Navigazione: Google Maps, Waze, Yandex Navi, OsmAnd, ABRP, Magic Earth.',
          '✅ Media: Spotify, YouTube, YouTube Music (preferire orizzontale).',
          '✅ Sistema: fotocamera, meteo, calendario.',
          '⚠️ App con DRM (Netflix, Disney+, Prime Video): possono rifiutare di mostrarsi su uno schermo virtuale — limitazione Android, non DashCast.',
        ],
      },
      {
        question: '❓ Aggiornamenti: stabile o beta?',
        answer:
          'Il canale stabile (predefinito) è testato su veicolo prima della pubblicazione. Il canale beta (Impostazioni → Aggiornamenti → «Includi pre-versioni») riceve le novità appena compilate — utile per testare in anticipo, con rischio di regressioni temporanee.',
      },
      {
        question: '❓ Voglio contribuire o segnalare un bug',
        answer:
          'GitHub: https://github.com/Kiroha/byd-dashcast — Issues per i bug, Discussions per le domande. Allega un export del Registro per accelerare la diagnosi.',
      },
    ],
  },

  footer:
    'DashCast è un progetto open-source distribuito sotto licenza MIT. Nessuna affiliazione con BYD Auto Co., Ltd.',
};
