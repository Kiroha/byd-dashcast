export default {
  code: 'it',
  flag: '🇮🇹',
  name: 'Italiano',
  title: 'DashCast — Manuale utente',
  manualName: 'Manuale utente',
  meta: 'BYD Seal / Dolphin / Atto 3 · DiLink 3 & DiLink 5 · Android 10–13',
  tocTitle: '📋 Indice',

  intro: {
    title: '0. Introduzione',
    lead:
      "DashCast mostra qualsiasi applicazione Android dello schermo centrale della tua BYD sul quadro strumenti (il display digitale degli indicatori dietro il volante). Maps, Waze, Spotify o ABRP proprio davanti a te — e con la modalità Layout, più app contemporaneamente, ognuna nella sua zona. Si installa come una normale app e non modifica nulla nel sistema.",
    bullets: [
      '✅ Funziona su DiLink 3 (Seal EU / 6125F) e DiLink 5 (head unit BYD più recenti).',
      '✅ Nessuna modifica di sistema: DashCast si installa come qualsiasi altra app.',
      '✅ ADB locale su TCP — nessun computer necessario dopo la prima autorizzazione.',
      "✅ 13 lingue d'interfaccia, scelte al primo avvio, modificabili in qualsiasi momento.",
      '✅ Mirror touch in tempo reale: controlla il cluster dallo schermo centrale.',
      '✅ Modalità Layout: più app affiancate sul cluster, zone disegnate col dito.',
      '✅ Avvio automatico: proiezione + app (o layout preferito) appena DashCast si apre.',
      '✅ Margini (overscan) memorizzati per applicazione.',
      "✅ Frecce di svolta sull'HUD del parabrezza DiLink 3 (firmware supportato).",
      '✅ Helper hotspot Wi-Fi integrato per DiLink 3 (usa la tua SIM).',
      '✅ Segnalazione bug senza tastiera, un tocco per inviare la diagnostica al supporto.',
      "✅ Aggiornamenti OTA automatici che si installano in silenzio e riavviano l'app.",
    ],
    note:
      '💡 Unico prerequisito: attivare il debug ADB wireless in Impostazioni BYD → Opzioni sviluppatore. Al primo avvio appare la finestra «Consentire il debug?» — spunta «Consenti sempre da questo computer» e conferma. Non dovrai mai più ripeterlo.',
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
          'Avvia DashCast (icona blu nel drawer delle app BYD).',
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
        "La schermata principale di DashCast. A sinistra: tutte le tue applicazioni con ricerca, filtri e preferiti, più la barra di navigazione laterale. A destra: l'anteprima del cluster in tempo reale, i pulsanti Mirror a schermo intero / Ferma proiezione, e — in modalità Layout — il selettore comprimibile «Layout cluster».",
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
          title: '🗂️ Selettore layout cluster (compresso per impostazione predefinita)',
          text:
            "In modalità Layout, sotto i pulsanti compare un'intestazione compatta «LAYOUT CLUSTER». Toccala per espanderla: «Avvia le app del layout», più una scheda per ogni layout salvato (Modalità libera / i tuoi preset / ＋ Gestisci). È compressa per impostazione predefinita così l'anteprima in tempo reale mantiene tutta la sua altezza.",
        },
        {
          title: '📺 Pulsante flottante',
          text:
            'Un pulsante 📺 resta sopra le altre app: tocco = apri il mirror, pressione lunga = cambio rapido tra le ultime app proiettate.',
        },
        {
          title: '🧭 Barra di navigazione laterale',
          text:
            "Accesso rapido ad App, Impostazioni, Sistema, Registro, la segnalazione bug e — su DiLink 3 con la tua SIM — l'helper Hotspot.",
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
        "💡 Layout preferito: la scheda selezionata nel selettore «Layout cluster» è quella che l'avvio automatico attiverà (vedi sezione Layout).",
        "💡 Margini: se l'app deborda dal cluster, Impostazioni → Margini, slider orizzontale/verticale. Memorizzato per app.",
      ],
    },

    {
      id: 'settings',
      screen: 'screen-3',
      title: '3. Impostazioni',
      lead:
        "Le opzioni globali: dimensione del cluster, lingua, margini, comportamento all'avvio, modalità Layout e aggiornamenti. La barra laterale resta disponibile — passa da una schermata all'altra senza perdere la posizione.",
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
            "Se attivo, DashCast parte con l'auto e ripristina l'ultima app proiettata (o il layout preferito). Altrimenti lancialo dal drawer BYD.",
        },
        {
          title: '🗂️ Modalità Layout',
          text:
            'Attiva la proiezione multi-applicazione con zone personalizzate (richiede il Proxy ADB Daemon, gestito automaticamente). Mostra il selettore «Layout cluster» sulla schermata principale e la scheda Layout.',
        },
        {
          title: '⭐ Layout preferito automatico',
          text:
            "All'avvio di DashCast: attiva la proiezione del cluster, il layout preferito, e lancia le app collegate a ogni zona. La tua configurazione multi-app completa, senza un solo tocco.",
        },
        {
          title: "⚡ Pre-creare gli slot all'avvio",
          text:
            "Prepara gli schermi virtuali del layout preferito all'apertura (senza lanciare le app) — l'attivazione del layout è poi quasi istantanea.",
        },
        {
          title: '📶 Usa la mia SIM (DiLink 3)',
          text:
            "Controlla se l'helper Hotspot appare nella barra di navigazione. Lascialo attivo se colleghi l'auto tramite i dati del tuo telefono/SIM. Vedi la sezione Hotspot.",
        },
        {
          title: '📦 Aggiornamenti OTA',
          text:
            "DashCast verifica GitHub a ogni avvio per nuove versioni. Ora gli aggiornamenti si installano in silenzio e riavviano l'app da soli (vedi la sezione Aggiornamenti). Spunta «Includi pre-versioni» per il canale beta.",
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
        "La modalità Layout divide il cluster in zone personalizzate, ognuna con la propria applicazione: Waze a sinistra, Spotify a destra, per esempio. Disegni le zone col dito, colleghi un'app a ogni zona, e il layout si attiva con un tocco — o da solo all'avvio.",
      mockupLabel: 'Vedi schermata 7 (Layout)',
      featuresTitle: 'Funzionalità',
      features: [
        {
          title: '✏️ Disegnare una zona',
          text:
            'Sul canvas (replica del cluster), trascina il dito per tracciare un rettangolo. Si apre una finestra: nome, posizione/dimensioni al pixel, e app da collegare.',
        },
        {
          title: "🔗 Collegare un'applicazione",
          text:
            "Ogni zona può essere collegata a un'app: all'attivazione del layout, l'app si lancia automaticamente nella sua zona. Una zona senza app resta libera — puoi metterci qualcosa in seguito.",
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
            "Il pulsante «Preferito» (o un tocco sulla scheda del selettore della schermata principale) designa il layout che «Layout preferito automatico» attiverà all'avvio di DashCast — proiezione inclusa.",
        },
      ],
      howTo: {
        title: 'Crea il tuo primo layout',
        steps: [
          'Attiva «Modalità Layout» nelle Impostazioni.',
          'Apri la scheda Layout (barra laterale).',
          'Trascina il dito sul canvas per disegnare la prima zona (es. metà sinistra).',
          "Nella finestra: assegnale un nome, tocca «Collega un'applicazione» → scegli Waze → Aggiungi.",
          'Disegna la seconda zona (metà destra), collega Spotify.',
          '«Salva» → dai un nome al layout (es. Nav+Media).',
          '«Preferito» per selezionarlo, poi attivalo: le due app si lanciano, ognuna nella sua zona.',
        ],
      },
      tipsTitle: 'Suggerimenti',
      tips: [
        '💡 Combinato con «Layout preferito automatico» (Impostazioni), la tua configurazione multi-app completa si monta da sola a ogni avvio di DashCast.',
        "💡 La mini-anteprima di ogni scheda del selettore mostra le zone reali del layout — riconoscibile a colpo d'occhio.",
        "💡 Un'app rifiuta di apparire nella sua zona? Alcune app impongono il loro formato; prova una zona più vicina al 16:9.",
      ],
      note:
        'ℹ️ La modalità Layout si appoggia al Proxy ADB Daemon (avviato automaticamente). Primo avvio a freddo: conta 6–8 s prima che le app appaiano — è la sequenza di attivazione del cluster.',
    },

    {
      id: 'hud',
      screen: 'screen-2',
      title: '5. Frecce di navigazione HUD (DiLink 3)',
      lead:
        "Sulle auto DiLink 3 il cui Head-Up Display sul parabrezza supporta le frecce di svolta, DashCast può disegnare la guida passo-passo sull'HUD a partire dalla tua app di navigazione — la freccia della manovra e la distanza da essa, direttamente sul parabrezza.",
      mockupLabel: 'Vedi schermata 2 (Principale)',
      featuresTitle: 'Come funziona',
      features: [
        {
          title: '🧭 Guida da Maps / Waze',
          text:
            "DashCast legge la notifica di guida passo-passo che la tua app di navigazione già pubblica (Google Maps, Waze) e inoltra la manovra + la distanza all'HUD tramite il bus CAN dell'auto. Non serve alcuna app aggiuntiva.",
        },
        {
          title: '🚗 Dipende dal firmware',
          text:
            "Solo i firmware HUD DiLink 3 più recenti possono disegnare le frecce. Se il tuo non può, le frecce semplicemente non appariranno — DashCast non può aggiungere una funzione che l'hardware dell'HUD non possiede.",
        },
        {
          title: '➡️ Glifi di direzione corretti',
          text:
            'Le manovre dritto / sinistra / destra corrispondono al glifo HUD adeguato, con la distanza alla svolta in conto alla rovescia in tempo reale.',
        },
      ],
      howTo: {
        title: "Come ottenere le frecce sull'HUD",
        steps: [
          "Assicurati che l'accesso alle notifiche sia concesso a DashCast (lo chiede al primo utilizzo).",
          "Accendi l'HUD sul parabrezza e impostalo su una modalità di visualizzazione navigazione dal menu HUD di BYD.",
          'Avvia un percorso in Google Maps o Waze.',
          "La freccia della manovra e la distanza appaiono sull'HUD man mano che ti avvicini a ogni svolta.",
        ],
      },
      note:
        'ℹ️ Le frecce HUD sono una funzione DiLink 3 e dipendono dal firmware del tuo HUD. Se non appare nulla, il tuo HUD potrebbe essere precedente al supporto delle frecce — è un limite hardware, non un bug di DashCast.',
    },

    {
      id: 'hotspot',
      screen: 'screen-3',
      title: '6. Helper hotspot Wi-Fi (DiLink 3)',
      lead:
        "Su DiLink 3, se colleghi l'auto a internet tramite la tua SIM/telefono, l'helper Hotspot mantiene attivo quel tethering in modo che navigazione e streaming continuino a funzionare. Appare nella barra di navigazione solo quando è rilevante per te.",
      mockupLabel: 'Vedi schermata 3 (Impostazioni)',
      featuresTitle: 'Funzionalità',
      features: [
        {
          title: '📶 Keep-alive',
          text:
            "Riattiva il tethering Wi-Fi quando l'auto si risveglia (es. dopo aver acceso l'ACC) così non devi riabilitarlo manualmente a ogni viaggio.",
        },
        {
          title: '👁️ Stato in tempo reale',
          text:
            "Mostra se l'hotspot è attivo e quanti client sono connessi, così puoi confermare che l'auto è davvero online.",
        },
        {
          title: '⚙️ Mostrato solo quando utile',
          text:
            'La voce Hotspot appare solo su DiLink 3 e solo mentre «Usa la mia SIM» è attiva nelle Impostazioni. In altre configurazioni resta nascosta.',
        },
      ],
      howTo: {
        title: 'Come usarlo',
        steps: [
          'Impostazioni → assicurati che «Usa la mia SIM» sia attiva.',
          'Apri «Hotspot» dalla barra di navigazione.',
          'Avvia / conferma il tethering — lo stato mostra che è attivo.',
          "Si riattiva da solo la prossima volta che l'auto si risveglia.",
        ],
      },
      note:
        'ℹ️ Questo helper è per le auto DiLink 3 collegate tramite i tuoi dati. Se la tua auto ha un proprio piano dati integrato, non ne hai bisogno.',
    },

    {
      id: 'bugreport',
      screen: 'screen-6',
      title: '7. Segnala un problema (segnalazione bug)',
      lead:
        "Una segnalazione bug in auto, senza tastiera. In tre tocchi scegli cosa è andato storto; DashCast cattura un'istantanea diagnostica limitata (log + stato del sistema) e la invia direttamente al canale di supporto — senza digitare, senza cavi.",
      mockupLabel: 'Vedi schermata 6 (Segnalazione)',
      featuresTitle: 'Come funziona',
      features: [
        {
          title: '1️⃣ Categoria',
          text:
            "Scegli quale area è interessata: mirror, un'app, audio, connessione, blocco, HUD… Sei grandi riquadri, solo da toccare.",
        },
        {
          title: '2️⃣ App',
          text:
            "DashCast rileva automaticamente l'app attualmente sul cluster e la propone, più «Nessuna app specifica» e «Altro».",
        },
        {
          title: '3️⃣ Problema',
          text:
            'Scegli il sintomo più vicino da un breve elenco. Un campo di testo libero opzionale ti permette di aggiungere dettagli se vuoi — ma non è mai obbligatorio.',
        },
        {
          title: '📎 Diagnostica automatica',
          text:
            'La segnalazione raccoglie i log recenti e lo stato del sistema/cluster al momento del problema — esattamente ciò di cui il supporto ha bisogno, catturato per te.',
        },
        {
          title: '🚀 Invio con un tocco',
          text:
            'Se è configurato un canale di supporto, la segnalazione viene caricata direttamente; altrimenti DashCast apre il menu di condivisione Android così puoi inviarla tramite Telegram, e-mail o GitHub.',
        },
        {
          title: '📺 Da ovunque',
          text:
            "Il pulsante flottante 📺 e la barra di navigazione aprono entrambi la segnalazione, così puoi inviare un report anche mentre un'altra app è proiettata.",
        },
      ],
      howTo: {
        title: 'Come inviare una segnalazione',
        steps: [
          'Apri la segnalazione bug (barra di navigazione o pulsante flottante).',
          'Tocca la categoria che corrisponde al problema.',
          "Conferma l'app (o scegli «Nessuna app specifica»).",
          'Scegli il problema più vicino; aggiungi una nota se utile.',
          'Tocca Invia — la diagnostica va al supporto automaticamente.',
        ],
      },
      note:
        '🔒 Prima dell\'invio, DashCast rimuove il numero di telaio, i nomi delle reti Wi-Fi, gli indirizzi fisici e le posizioni. Restano il registro di DashCast e quello del sistema Android, copiati così come sono: contengono ciò che hanno scritto le altre app. Ti viene chiesto una volta prima di ogni invio e, se rifiuti, niente esce dall\'auto.',
    },

    {
      id: 'system',
      screen: 'screen-5',
      title: '8. Report di sistema',
      lead:
        'Pannello in sola lettura: versioni, display rilevati e stato in diretta dei servizi DashCast. La prima schermata da consultare quando qualcosa sembra anomalo.',
      mockupLabel: 'Vedi schermata 5 (Sistema)',
      featuresTitle: 'Informazioni mostrate',
      features: [
        {
          title: '🖥️ Display',
          text:
            'Schermo principale (risoluzione, densità) e display virtuale del cluster con il suo stato in tempo reale.',
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
        '💡 Se qualcosa sembra anomalo, controlla prima questa schermata, poi invia una segnalazione dalla segnalazione bug.',
      ],
    },

    {
      id: 'journal',
      screen: 'screen-6',
      title: '9. Registro',
      lead:
        'Il registro interno di DashCast: tutte le azioni importanti (proiezioni, ripristini, errori ADB, aggiornamenti) sono tracciate in continuo. Utile per capire un comportamento inatteso; è anche il dato che la segnalazione bug allega per te.',
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
        title: 'Preferisci la segnalazione bug',
        steps: [
          'Per la maggior parte dei problemi, usa la segnalazione bug (sezione 7) — cattura automaticamente il registro più lo stato del sistema.',
          'La schermata Registro è qui quando vuoi leggere tu stesso la traccia o condividere solo il registro grezzo.',
        ],
      },
      note:
        '🔒 Il registro annota ciò che fa DashCast, compresi i nomi dei pacchetti e l\'output dei comandi eseguiti. Condividerlo da questa schermata lo invia così com\'è: il filtro che rimuove il numero di telaio, i nomi delle reti e le posizioni agisce sulle segnalazioni, non qui.',
    },
  ],

  faq: {
    title: '10. FAQ — Domande frequenti',
    items: [
      {
        question: "❓ Il cluster resta nero quando tocco un'app",
        answer:
          "Tre cause possibili: (1) ADB wireless disattivato — verifica Impostazioni BYD → Opzioni sviluppatore. (2) Un servizio fermo — schermata Sistema, riavvia la riga rossa. (3) L'app è appena crashata — ritocca la sua icona. Ancora bloccato? Invia una segnalazione dalla segnalazione bug.",
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
        question: "❓ Il mio layout preferito non parte all'avvio",
        answer:
          'Verifica le tre condizioni nelle Impostazioni: «Modalità Layout» attiva, «Layout preferito automatico» attivo, e un layout marcato ⭐ preferito (selettore della schermata principale o pulsante Preferito della scheda Layout). Al primo avvio a freddo, conta 6–8 s.',
      },
      {
        question: '❓ Nessuna freccia di navigazione sul mio HUD',
        answer:
          "Le frecce HUD sono una funzione DiLink 3 e richiedono un firmware HUD in grado di disegnarle. Assicurati che l'accesso alle notifiche sia concesso, che l'HUD sia acceso in una modalità di visualizzazione navigazione e che un percorso sia in corso in Maps/Waze. Se non appare nulla, il firmware del tuo HUD è probabilmente precedente al supporto delle frecce — un limite hardware, non un bug.",
      },
      {
        question: "❓ Mi serve l'helper Hotspot?",
        answer:
          "Solo su DiLink 3 se colleghi l'auto a internet tramite il tethering della tua SIM/telefono. Mantiene attivo quel tethering tra un risveglio e l'altro. Se la tua auto ha un proprio piano dati, ignoralo — resta nascosto a meno che «Usa la mia SIM» non sia attiva.",
      },
      {
        question: '❓ Come si installano ora gli aggiornamenti?',
        answer:
          "DashCast verifica GitHub a ogni avvio. Quando un aggiornamento viene scaricato si installa in silenzio e riavvia l'app da solo — nessuna richiesta «Installare?». Su un'auto dove ciò non è possibile, ricorre al normale programma di installazione del sistema. Il primissimo aggiornamento dopo il passaggio a una versione con questa funzione potrebbe ancora chiederlo una volta.",
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
          "Usa la segnalazione bug integrata nell'app per la via più rapida (allega la diagnostica per te). Per il codice e le richieste di funzionalità: GitHub https://github.com/Kiroha/byd-dashcast — Issues per i bug, Discussions per le domande.",
      },
    ],
  },

  footer:
    'DashCast è un progetto open-source distribuito sotto licenza MIT. Nessuna affiliazione con BYD Auto Co., Ltd.',
};
