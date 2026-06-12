export default {
  code: 'fr',
  flag: '🇫🇷',
  name: 'Français',
  title: "DashCast — Manuel d'utilisation",
  manualName: "Manuel d'utilisation",
  meta: 'v1.4.x · BYD Seal EU · DiLink 3.0 · Android 10',
  tocTitle: '📋 Sommaire',

  intro: {
    title: '0. Introduction',
    lead:
      "DashCast affiche n'importe quelle application Android de l'écran central de votre BYD sur le combiné d'instruments (cluster numérique). Maps, Waze, Spotify ou ABRP directement devant le volant — et avec le mode Layouts, plusieurs applications à la fois, chacune dans sa zone. Le tout sans modifier le système.",
    bullets: [
      '✅ Compatible BYD Seal EU (DiLink 3.0, firmware Di3.0 / 6125F).',
      "✅ Aucune modification système : DashCast s'installe comme une app classique.",
      '✅ ADB local en TCP — aucun ordinateur nécessaire après la première autorisation.',
      "✅ 13 langues d'interface, choix au premier démarrage.",
      '✅ Miroir tactile temps réel : pilotez le cluster depuis l\'écran central.',
      '✅ Mode Layouts : plusieurs apps côte-à-côte sur le cluster, zones dessinées au doigt.',
      '✅ Lancement automatique : projection + app (ou layout favori) dès l\'ouverture de DashCast.',
      '✅ Marges (overscan) mémorisées par application.',
      '✅ Mises à jour OTA intégrées (canal bêta optionnel).',
    ],
    note:
      "💡 Prérequis unique : activer le débogage ADB sans fil dans Paramètres BYD → Développeur. Au premier lancement, une boîte de dialogue « Autoriser le débogage ? » apparaît — cochez « Toujours autoriser » et validez. Cette étape n'est jamais à refaire.",
  },

  sections: [
    // ────── 1. WELCOME ───────────────────────────────────────────────
    {
      id: 'welcome',
      screen: 'screen-1',
      title: '1. Écran de bienvenue — choix de la langue',
      lead:
        "Au tout premier lancement, DashCast affiche la grille des 13 langues disponibles. Touchez votre langue : le choix est mémorisé et l'écran ne réapparaîtra plus. Vous pourrez changer de langue à tout moment via Réglages.",
      mockupLabel: "Voir l'écran 1 (Bienvenue)",
      featuresTitle: 'Détails',
      features: [
        {
          title: '13 langues prises en charge',
          text:
            "Français, English, Deutsch, Italiano, Türkçe, Español, Polski, Русский, Українська, العربية, O'zbekcha, Қазақша, Беларуская. La langue choisie est appliquée immédiatement, sans redémarrage.",
        },
        {
          title: 'Sens de lecture automatique',
          text:
            "L'arabe bascule automatiquement en mise en page droite-à-gauche (RTL) : la barre de navigation passe à droite et les listes s'inversent.",
        },
        {
          title: 'Modifiable à tout moment',
          text:
            'Pour changer de langue plus tard : Réglages → Langue. La nouvelle langue est appliquée à la volée.',
        },
      ],
      howTo: {
        title: 'Comment faire',
        steps: [
          "Lancez DashCast (icône bleue dans le tiroir d'apps BYD).",
          "L'écran de bienvenue s'affiche avec la grille des langues.",
          "Touchez votre langue. L'interface bascule immédiatement.",
          "L'écran principal s'ouvre — vous êtes prêt.",
        ],
      },
    },

    // ────── 2. MAIN ──────────────────────────────────────────────────
    {
      id: 'main',
      screen: 'screen-2',
      title: '2. Écran principal — Apps & Cluster',
      lead:
        "L'écran central de DashCast. À gauche : toutes vos applications avec recherche, filtres et favoris. À droite : l'aperçu temps réel du cluster, les boutons Miroir plein écran / Arrêter projection, et le carrousel de layouts pour choisir votre disposition multi-apps favorite.",
      mockupLabel: "Voir l'écran 2 (Principal)",
      featuresTitle: 'Tout ce que vous pouvez faire',
      features: [
        {
          title: '👆 Tap court — projeter',
          text:
            "Touchez une app pour l'envoyer sur le cluster. Si la projection n'est pas active, elle démarre automatiquement (~2 s de préchauffage), puis l'app apparaît devant le volant.",
        },
        {
          title: "👆⏱️ Appui long — menu d'actions",
          text:
            "Maintenez l'appui sur une app : ⭐ Favori, Auto-launch (projeter cette app à chaque démarrage de DashCast), Déplacer vers cluster / écran principal, ✕ Forcer l'arrêt.",
        },
        {
          title: '🔍 Recherche & filtres',
          text:
            "La barre de recherche filtre à la volée (nom ou package). Les puces par catégorie (Toutes / Navigation / Média…) regroupent vos apps ; le bouton ▦ bascule liste/grille.",
        },
        {
          title: '🚦 Aperçu cluster temps réel',
          text:
            "Le panneau droit reflète en direct ce qui s'affiche sur le combiné. Vos touchers sur l'aperçu sont transmis à l'app projetée — scroll, zoom, clavier, tout fonctionne.",
        },
        {
          title: '👁️ Miroir plein écran',
          text:
            "Étend l'aperçu à tout l'écran central : idéal pour taper une adresse dans Maps avec le clavier complet. Tout est répliqué sur le cluster en temps réel.",
        },
        {
          title: '⏹ Arrêter projection',
          text:
            "Termine proprement la projection et restaure le tableau de bord BYD d'origine (vitesse, jauges, ADAS) avec la taille d'écran définie dans Réglages.",
        },
        {
          title: '🗂️ Carrousel de layouts',
          text:
            "Sous les boutons, chaque carte montre un mini-aperçu des zones d'un layout. Touchez une carte pour en faire le layout favori (étoile + bordure bleue). « Mode libre » désactive les layouts ; « ＋ Gérer » ouvre l'éditeur.",
        },
        {
          title: '📺 Bouton flottant',
          text:
            "Un bouton 📺 persiste par-dessus les autres apps : tap = ouvrir le miroir, appui long = bascule rapide entre les dernières apps projetées.",
        },
      ],
      howTo: {
        title: 'Comment projeter une app sur le cluster',
        steps: [
          "Repérez l'app voulue (ex. Maps) — recherche ou filtres si besoin.",
          'Touchez son icône → la projection démarre et le cluster bascule sur l\'app en ~2 s.',
          "L'aperçu droit affiche en direct ce qui est sur le cluster.",
          'Pour saisir du texte : « Miroir plein écran » → tapez votre adresse → tout est répliqué.',
          'Pour arrêter : « Arrêter projection » — le cluster repasse en BYD natif.',
        ],
      },
      tipsTitle: 'Astuces',
      tips: [
        '💡 Auto-launch : choisissez une app (appui long → Auto-launch) pour qu\'elle se projette automatiquement à chaque démarrage de DashCast — la projection s\'active toute seule.',
        '💡 Layout favori : la carte sélectionnée dans le carrousel est celle que le démarrage automatique activera (voir section Layouts).',
        "💡 Marges : si l'app déborde du cluster, Réglages → Marges, sliders horizontal/vertical. Mémorisé par app.",
      ],
    },

    // ────── 3. SETTINGS ──────────────────────────────────────────────
    {
      id: 'settings',
      screen: 'screen-3',
      title: '3. Réglages',
      lead:
        "Les options globales : taille du cluster, langue, marges, comportement au démarrage, mode Layouts et mises à jour. La barre latérale reste accessible — basculez entre les écrans sans perdre votre position.",
      mockupLabel: "Voir l'écran 3 (Réglages)",
      featuresTitle: 'Sections principales',
      features: [
        {
          title: '📺 Type de cluster',
          text:
            "Taille physique de votre combiné : 8.8″, 12.3″ (recommandé Seal EU — corrige l'étirement ADAS) ou 10.25″. Utilisée par « Arrêter projection » pour restaurer le bon mode.",
        },
        {
          title: '↔️↕️ Marges (overscan)',
          text:
            'Sliders horizontal/vertical (0–200 px) pour compenser les bords coupés. Mémorisé par application : Maps peut avoir 80 px pendant que Spotify reste à 0. « Appliquer » ajuste la projection à chaud.',
        },
        {
          title: '🚗 Lancement au démarrage du véhicule',
          text:
            'Si activé, DashCast démarre avec la voiture et restaure la dernière app projetée. Sinon, lancez-le depuis le tiroir BYD.',
        },
        {
          title: '🗂️ Mode Layouts',
          text:
            'Active la projection multi-applications avec zones personnalisées (nécessite le Proxy ADB Daemon, géré automatiquement). Fait apparaître le carrousel sur l\'écran principal et l\'onglet Layouts.',
        },
        {
          title: '⭐ Layout favori automatique',
          text:
            "Au lancement de DashCast : active la projection cluster, le layout favori, puis lance les apps liées à chaque zone. Votre configuration multi-apps complète, sans un seul tap.",
        },
        {
          title: '⚡ Pré-créer les slots au démarrage',
          text:
            'Prépare les écrans virtuels du layout favori dès l\'ouverture (sans lancer les apps) — l\'activation du layout est ensuite quasi instantanée.',
        },
        {
          title: '📦 Mises à jour OTA',
          text:
            'DashCast vérifie les nouvelles versions sur GitHub à chaque lancement. Cochez « Inclure les pré-versions » pour le canal bêta (nouveautés plus tôt, stabilité moindre).',
        },
        {
          title: '🌐 Langue',
          text: '13 langues — le changement est instantané.',
        },
      ],
      howTo: {
        title: "Comment ajuster les marges d'une app",
        steps: [
          "Projetez l'app à ajuster (ex. Waze).",
          'Réglages → Marges.',
          'Déplacez le slider horizontal jusqu\'à ce que les bords gauche/droit soient corrects.',
          'Idem pour le vertical, puis « Appliquer » — ajustement à chaud, sans redémarrer l\'app.',
          'Le réglage est sauvegardé pour cette app uniquement.',
        ],
      },
      note:
        '⚠️ Si vous changez le type de cluster, arrêtez puis relancez la projection pour que la restauration utilise le bon mode.',
    },

    // ────── 4. LAYOUTS ───────────────────────────────────────────────
    {
      id: 'layouts',
      screen: 'screen-7',
      title: '4. Layouts — plusieurs apps sur le cluster',
      lead:
        "Le mode Layouts découpe le cluster en zones personnalisées, chacune affichant sa propre application : Waze à gauche, Spotify à droite, par exemple. Vous dessinez les zones au doigt, vous liez une app à chaque zone, et le layout s'active en un tap — ou tout seul au démarrage.",
      mockupLabel: "Voir l'écran 7 (Layouts)",
      featuresTitle: 'Fonctionnalités',
      features: [
        {
          title: '✏️ Dessiner une zone',
          text:
            "Sur le canvas (reproduction du cluster 1920×720), glissez le doigt pour tracer un rectangle. Un dialogue s'ouvre : nom, position/dimensions précises au pixel, et application à lier.",
        },
        {
          title: '🔗 Lier une application',
          text:
            "Chaque zone peut être liée à une app : à l'activation du layout, l'app se lance automatiquement dans sa zone. Une zone sans app reste libre — vous y placez ce que vous voulez plus tard.",
        },
        {
          title: '✋ Déplacer & redimensionner',
          text:
            'Glissez une zone pour la déplacer ; les poignées blanches aux coins redimensionnent. Les bords s\'aimantent automatiquement aux bords du cluster et aux zones voisines.',
        },
        {
          title: '✏️ Modifier une zone existante',
          text:
            'Touchez une zone (sur le canvas ou son chip en bas) : renommez-la, ajustez sa géométrie, changez l\'app liée, ou supprimez-la. Appui long sur une zone = suppression rapide.',
        },
        {
          title: '💾 Layouts enregistrés',
          text:
            "Sauvegardez autant de layouts que voulu (« Nav+Media », « Triple écran »…). Le panneau latéral les liste avec Activer / Désactiver / Modifier / Supprimer.",
        },
        {
          title: '⭐ Favori & démarrage automatique',
          text:
            "Le bouton « Favori » (ou un tap sur la carte du carrousel de l'écran principal) désigne le layout que « Layout favori automatique » activera au lancement de DashCast — projection comprise.",
        },
      ],
      howTo: {
        title: 'Créer votre premier layout',
        steps: [
          'Activez « Mode Layouts » dans Réglages.',
          'Ouvrez l\'onglet Layouts (barre latérale).',
          'Glissez le doigt sur le canvas pour dessiner la première zone (ex. moitié gauche).',
          'Dans le dialogue : nommez-la, touchez « Lier une application » → choisissez Waze → Ajouter.',
          'Dessinez la seconde zone (moitié droite), liez Spotify.',
          '« Enregistrer » → nommez le layout (ex. Nav+Media).',
          '« Favori » pour le sélectionner, puis activez-le : les deux apps se lancent chacune dans leur zone.',
        ],
      },
      tipsTitle: 'Astuces',
      tips: [
        '💡 Combiné avec « Layout favori automatique » (Réglages), votre configuration multi-apps complète se met en place toute seule à chaque démarrage de DashCast.',
        '💡 Le mini-aperçu de chaque carte du carrousel montre les zones réelles du layout — reconnaissable d\'un coup d\'œil.',
        "💡 Une app refuse de s'afficher dans sa zone ? Certaines apps imposent leur format ; essayez une zone plus proche du 16:9.",
      ],
      note:
        "ℹ️ Le mode Layouts s'appuie sur le Proxy ADB Daemon (démarré automatiquement). Premier démarrage à froid : comptez 6–8 s avant que les apps apparaissent — c'est la séquence d'activation du cluster.",
    },

    // ────── 5. DIAGNOSTICS ───────────────────────────────────────────
    {
      id: 'diagnostics',
      screen: 'screen-4',
      title: '5. Diagnostics',
      lead:
        "Tableau de bord interne pour les situations où la projection ne fonctionne pas comme prévu. La majorité des utilisateurs n'en aura jamais besoin — il est là pour le support et le débogage.",
      mockupLabel: "Voir l'écran 4 (Diagnostics)",
      featuresTitle: 'Outils disponibles',
      features: [
        {
          title: 'Tests de connexion',
          text:
            "Vérifie le tunnel ADB local (localhost:5555), l'état du ClusterService et la présence de l'écran virtuel du cluster. Chaque test affiche un résultat structuré.",
        },
        {
          title: 'Sondes plateforme',
          text:
            "Détection DiLink (2/3/4/5), inventaire des displays, instanciation des API véhicule BYD (vitesse, énergie) et état des permissions BYDAUTO.",
        },
        {
          title: 'Rapport partageable',
          text:
            'Génère un rapport complet (système, displays, services, permissions, métriques du daemon) exportable en texte pour le support.',
        },
      ],
      howTo: {
        title: 'Quand utiliser cet onglet',
        steps: [
          'Le cluster reste noir après avoir touché une app → vérifiez ClusterService et le display virtuel.',
          "L'app signale « ADB non disponible » → bouton « Tester ADB ».",
          'Le support vous demande un rapport → générez-le et partagez-le.',
        ],
      },
      note:
        'ℹ️ Les boutons sont des tests en lecture seule, sauf indication explicite.',
    },

    // ────── 6. SYSTEM INFO ───────────────────────────────────────────
    {
      id: 'sysinfo',
      screen: 'screen-5',
      title: '6. Rapport système',
      lead:
        'Tableau de bord en lecture seule : versions, displays détectés et état en direct des services DashCast. C\'est le premier écran à consulter quand quelque chose semble anormal.',
      mockupLabel: "Voir l'écran 5 (Système)",
      featuresTitle: 'Informations affichées',
      features: [
        {
          title: '🖥️ Displays',
          text:
            'Écran principal (résolution, densité) et display virtuel du cluster (1920×720) avec son état en temps réel.',
        },
        {
          title: '⚙️ Services',
          text:
            'ClusterService (projection), MirrorDaemon (miroir), Proxy ADB Daemon (opérations privilégiées), AdbLocalClient (tunnel ADB) — chacun avec pastille verte/rouge et bouton de relance si arrêté.',
        },
        {
          title: '📱 Versions',
          text:
            'Version DashCast installée, firmware BYD, version Android/API, identifiants build DiLink.',
        },
        {
          title: '🔁 Replay projection',
          text:
            "Bouton pour rejouer la séquence d'activation complète du cluster (utile si le combiné est resté dans un état intermédiaire).",
        },
      ],
      tipsTitle: 'Astuces',
      tips: [
        '💡 « Proxy ADB Daemon » doit être vert (RUN) pour le mode Layouts — sinon touchez sa ligne pour le relancer.',
        '💡 Le rapport complet est exportable depuis cet écran pour accompagner un signalement de bug.',
      ],
    },

    // ────── 7. JOURNAL ───────────────────────────────────────────────
    {
      id: 'journal',
      screen: 'screen-6',
      title: '7. Journal',
      lead:
        "Journal interne de DashCast : toutes les actions importantes (projections, restaurations, erreurs ADB, mises à jour) y sont tracées en continu. Utile pour comprendre un comportement inattendu ou fournir un rapport au support.",
      mockupLabel: "Voir l'écran 6 (Journal)",
      featuresTitle: 'Fonctionnalités',
      features: [
        {
          title: '🔍 Filtres',
          text:
            "Filtrez par niveau (DEBUG / INFO / WARN / ERROR) ou par mot-clé (ex. « ADB », « Maps », « error »).",
        },
        {
          title: '🎨 Code couleur',
          text:
            '🟢 INFO — opération normale. 🟠 WARN — attention. 🔴 ERROR — échec. ⚪ DEBUG — détail technique.',
        },
        {
          title: '📤 Partager',
          text:
            'Exporte le journal en .txt et ouvre le menu de partage Android. Inclut la version DashCast et le modèle BYD.',
        },
        {
          title: '⏰ Horodatage',
          text:
            'Chaque ligne est préfixée par l\'heure locale (HH:mm:ss.mmm) ; les durées des opérations longues sont mesurées.',
        },
      ],
      howTo: {
        title: 'Envoyer un rapport de bug',
        steps: [
          'Reproduisez le problème.',
          'Ouvrez Journal → « Partager ».',
          'Choisissez votre canal (Telegram, e-mail, GitHub Issues).',
          'Le fichier joint contient la trace et le contexte (version, modèle, firmware).',
        ],
      },
      note:
        "🔒 Aucune donnée personnelle (contacts, position GPS, contenu d'app) n'est journalisée — uniquement les actions DashCast et les codes de retour techniques.",
    },
  ],

  faq: {
    title: '8. FAQ — Questions fréquentes',
    items: [
      {
        question: '❓ Le cluster reste noir quand je touche une app',
        answer:
          "Trois causes possibles : (1) ADB sans fil désactivé — vérifiez Paramètres BYD → Développeur. (2) Un service arrêté — onglet Système, relancez la ligne rouge. (3) L'app vient de planter — retouchez son icône.",
      },
      {
        question: "❓ L'image dépasse / est rognée sur le cluster",
        answer:
          'Réglages → Marges : ajustez les sliders horizontal/vertical jusqu\'à ce que les bords soient corrects. Mémorisé par app — vous ne le ferez qu\'une fois.',
      },
      {
        question: "❓ Comment revenir au tableau de bord BYD d'origine ?",
        answer:
          "Touchez « Arrêter projection » sur l'écran principal : DashCast restaure le cluster natif avec la taille définie dans Réglages. Si le combiné semble figé, écran Système → « Replay projection » puis arrêtez à nouveau.",
      },
      {
        question: '❓ Mon layout favori ne se lance pas au démarrage',
        answer:
          "Vérifiez les trois conditions dans Réglages : « Mode Layouts » activé, « Layout favori automatique » activé, et un layout marqué ⭐ favori (carrousel de l'écran principal ou bouton Favori de l'onglet Layouts). Au premier démarrage à froid, comptez 6–8 s.",
      },
      {
        question: '❓ Est-ce que DashCast vide la batterie 12 V ?',
        answer:
          "Non — DashCast s'arrête avec la voiture. Aucun service de fond ne reste actif moteur coupé.",
      },
      {
        question: '❓ Quelles apps fonctionnent sur le cluster ?',
        items: [
          '✅ Navigation : Google Maps, Waze, Yandex Navi, OsmAnd, ABRP, Magic Earth.',
          '✅ Média : Spotify, YouTube, YouTube Music (préférer le mode paysage).',
          '✅ Système : caméra, météo, agenda.',
          '⚠️ Apps avec DRM (Netflix, Disney+, Prime Video) : peuvent refuser de s\'afficher sur un écran virtuel — limitation Android, pas DashCast.',
        ],
      },
      {
        question: '❓ Mises à jour : stable ou bêta ?',
        answer:
          'Le canal stable (par défaut) est testé sur véhicule avant publication. Le canal bêta (Réglages → Mises à jour → « Inclure les pré-versions ») reçoit les nouveautés dès leur compilation — utile pour tester en avance, avec un risque de régressions temporaires.',
      },
      {
        question: '❓ Je veux contribuer ou signaler un bug',
        answer:
          'GitHub : https://github.com/Kiroha/byd-dashcast — Issues pour les bugs, Discussions pour les questions. Joignez un export du Journal pour accélérer le diagnostic.',
      },
    ],
  },

  footer:
    'DashCast est un projet open-source distribué sous licence MIT. Aucune affiliation avec BYD Auto Co., Ltd.',
};
