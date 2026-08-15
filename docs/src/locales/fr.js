export default {
  code: 'fr',
  flag: '🇫🇷',
  name: 'Français',
  title: "DashCast — Manuel d'utilisation",
  manualName: "Manuel d'utilisation",
  meta: 'v1.7.0 · BYD Seal / Dolphin / Atto 3 · DiLink 3 & DiLink 5 · Android 10–13',
  tocTitle: '📋 Sommaire',

  intro: {
    title: '0. Introduction',
    lead:
      "DashCast affiche n'importe quelle application Android de l'écran central de votre BYD sur le combiné d'instruments (l'écran de jauges numérique derrière le volant). Maps, Waze, Spotify ou ABRP directement devant vous — et avec le mode Layouts, plusieurs applications à la fois, chacune dans sa zone. Il s'installe comme une application normale et ne modifie rien dans le système.",
    bullets: [
      '✅ Compatible DiLink 3 (Seal EU / 6125F) et DiLink 5 (unités de tête BYD récentes).',
      "✅ Aucune modification système : DashCast s'installe comme une app classique.",
      '✅ ADB local en TCP — aucun ordinateur nécessaire après la première autorisation.',
      "✅ 13 langues d'interface, choisies au premier démarrage, modifiables à tout moment.",
      "✅ Miroir tactile temps réel : pilotez le cluster depuis l'écran central.",
      '✅ Mode Layouts : plusieurs apps côte-à-côte sur le cluster, zones dessinées au doigt.',
      "✅ Lancement automatique : projection + app (ou layout favori) dès l'ouverture de DashCast.",
      '✅ Marges (overscan) mémorisées par application.',
      '✅ Flèches de guidage sur le HUD pare-brise DiLink 3 (firmware compatible).',
      "✅ Assistant point d'accès Wi-Fi intégré pour DiLink 3 (utilisez votre propre SIM).",
      '✅ Signalement de bug sans clavier, un tap pour envoyer les diagnostics au support.',
      "✅ Mises à jour OTA automatiques qui s'installent en silence et relancent l'app.",
    ],
    note:
      "💡 Prérequis unique : activer le débogage ADB sans fil dans Paramètres BYD → Options pour les développeurs. Au premier lancement, une boîte de dialogue « Autoriser le débogage ? » apparaît — cochez « Toujours autoriser depuis cet ordinateur » et validez. Vous n'aurez plus jamais à le refaire.",
  },

  sections: [
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
          text: 'Pour changer de langue plus tard : Réglages → Langue. Appliquée à la volée.',
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

    {
      id: 'main',
      screen: 'screen-2',
      title: '2. Écran principal — Apps & Cluster',
      lead:
        "L'écran d'accueil de DashCast. À gauche : toutes vos applications avec recherche, filtres et favoris, plus la barre de navigation latérale. À droite : l'aperçu temps réel du cluster, les boutons Miroir plein écran / Arrêter projection, et — en mode Layouts — le sélecteur repliable « Cluster layout ».",
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
          title: '🗂️ Sélecteur de layout cluster (replié par défaut)',
          text:
            "En mode Layouts, un en-tête compact « CLUSTER LAYOUT » se trouve sous les boutons. Touchez-le pour le déplier : « Lancer les apps du layout », plus une carte par layout enregistré (Mode libre / vos préréglages / ＋ Gérer). Il est replié par défaut pour que l'aperçu en direct garde toute sa hauteur.",
        },
        {
          title: '📺 Bouton flottant',
          text:
            "Un bouton 📺 persiste par-dessus les autres apps : tap = ouvrir le miroir, appui long = bascule rapide entre les dernières apps projetées.",
        },
        {
          title: '🧭 Barre de navigation latérale',
          text:
            "Accès rapide à Apps, Réglages, Système, Journal, le signalement de bug et — sur DiLink 3 avec votre propre SIM — l'assistant point d'accès.",
        },
      ],
      howTo: {
        title: 'Comment projeter une app sur le cluster',
        steps: [
          "Repérez l'app voulue (ex. Maps) — recherche ou filtres si besoin.",
          "Touchez son icône → la projection démarre et le cluster bascule sur l'app en ~2 s.",
          "L'aperçu droit affiche en direct ce qui est sur le cluster.",
          'Pour saisir du texte : « Miroir plein écran » → tapez votre adresse → tout est répliqué.',
          'Pour arrêter : « Arrêter projection » — le cluster repasse en BYD natif.',
        ],
      },
      tipsTitle: 'Astuces',
      tips: [
        "💡 Auto-launch : choisissez une app (appui long → Auto-launch) pour qu'elle se projette automatiquement à chaque démarrage de DashCast — la projection s'active toute seule.",
        "💡 Layout favori : la carte sélectionnée dans le sélecteur « Cluster layout » est celle que le démarrage automatique activera (voir la section Layouts).",
        "💡 Marges : si l'app déborde du cluster, Réglages → Marges, sliders horizontal/vertical. Mémorisé par app.",
      ],
    },

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
          title: '🚗 Démarrer avec le véhicule',
          text:
            'Si activé, DashCast démarre avec la voiture et restaure la dernière app projetée (ou le layout favori). Sinon, lancez-le depuis le tiroir BYD.',
        },
        {
          title: '🗂️ Mode Layouts',
          text:
            "Active la projection multi-applications avec zones personnalisées (nécessite le Proxy ADB Daemon, géré automatiquement). Fait apparaître le sélecteur « Cluster layout » sur l'écran principal et l'onglet Layouts.",
        },
        {
          title: '⭐ Layout favori automatique',
          text:
            "Au lancement de DashCast : active la projection cluster, le layout favori, puis lance les apps liées à chaque zone. Votre configuration multi-apps complète, sans un seul tap.",
        },
        {
          title: '⚡ Pré-créer les slots au démarrage',
          text:
            "Prépare les écrans virtuels du layout favori dès l'ouverture (sans lancer les apps) — l'activation du layout est ensuite quasi instantanée.",
        },
        {
          title: '📶 Utiliser ma propre SIM (DiLink 3)',
          text:
            "Détermine si l'assistant point d'accès apparaît dans la barre de navigation. Laissez-le activé si vous connectez la voiture via les données de votre téléphone/SIM. Voir la section Point d'accès.",
        },
        {
          title: '📦 Mises à jour OTA',
          text:
            "DashCast vérifie les nouvelles versions sur GitHub à chaque lancement. Les mises à jour s'installent désormais en silence et relancent l'app toutes seules (voir la section Mises à jour). Cochez « Inclure les pré-versions » pour le canal bêta.",
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
          "Déplacez le slider horizontal jusqu'à ce que les bords gauche/droit soient corrects.",
          "Idem pour le vertical, puis « Appliquer » — ajustement à chaud, sans redémarrer l'app.",
          'Le réglage est sauvegardé pour cette app uniquement.',
        ],
      },
      note:
        '⚠️ Si vous changez le type de cluster, arrêtez puis relancez la projection pour que la restauration utilise le bon mode.',
    },

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
            "Sur le canvas (reproduction du cluster), glissez le doigt pour tracer un rectangle. Un dialogue s'ouvre : nom, position/dimensions précises au pixel, et application à lier.",
        },
        {
          title: '🔗 Lier une application',
          text:
            "Chaque zone peut être liée à une app : à l'activation du layout, l'app se lance automatiquement dans sa zone. Une zone sans app reste libre — vous y placez ce que vous voulez plus tard.",
        },
        {
          title: '✋ Déplacer & redimensionner',
          text:
            "Glissez une zone pour la déplacer ; les poignées blanches aux coins redimensionnent. Les bords s'aimantent automatiquement aux bords du cluster et aux zones voisines.",
        },
        {
          title: '✏️ Modifier une zone existante',
          text:
            "Touchez une zone (sur le canvas ou son chip en bas) : renommez-la, ajustez sa géométrie, changez l'app liée, ou supprimez-la. Appui long sur une zone = suppression rapide.",
        },
        {
          title: '💾 Layouts enregistrés',
          text:
            "Sauvegardez autant de layouts que voulu (« Nav+Media », « Triple écran »…). Le panneau latéral les liste avec Activer / Désactiver / Modifier / Supprimer.",
        },
        {
          title: '⭐ Favori & démarrage automatique',
          text:
            "Le bouton « Favori » (ou un tap sur la carte du sélecteur de l'écran principal) désigne le layout que « Layout favori automatique » activera au lancement de DashCast — projection comprise.",
        },
      ],
      howTo: {
        title: 'Créer votre premier layout',
        steps: [
          'Activez « Mode Layouts » dans Réglages.',
          "Ouvrez l'onglet Layouts (barre latérale).",
          'Glissez le doigt sur le canvas pour dessiner la première zone (ex. moitié gauche).',
          'Dans le dialogue : nommez-la, touchez « Lier une application » → choisissez Waze → Ajouter.',
          'Dessinez la seconde zone (moitié droite), liez Spotify.',
          '« Enregistrer » → nommez le layout (ex. Nav+Media).',
          '« Favori » pour le sélectionner, puis activez-le : les deux apps se lancent chacune dans leur zone.',
        ],
      },
      tipsTitle: 'Astuces',
      tips: [
        "💡 Combiné avec « Layout favori automatique » (Réglages), votre configuration multi-apps complète se met en place toute seule à chaque démarrage de DashCast.",
        "💡 Le mini-aperçu de chaque carte du sélecteur montre les zones réelles du layout — reconnaissable d'un coup d'œil.",
        "💡 Une app refuse de s'afficher dans sa zone ? Certaines apps imposent leur format ; essayez une zone plus proche du 16:9.",
      ],
      note:
        "ℹ️ Le mode Layouts s'appuie sur le Proxy ADB Daemon (démarré automatiquement). Premier démarrage à froid : comptez 6–8 s avant que les apps apparaissent — c'est la séquence d'activation du cluster.",
    },

    {
      id: 'hud',
      screen: 'screen-2',
      title: '5. Flèches de navigation HUD (DiLink 3)',
      lead:
        "Sur les voitures DiLink 3 dont l'affichage tête haute (HUD) pare-brise prend en charge les flèches de virage, DashCast peut afficher le guidage détaillé sur le HUD à partir de votre app de navigation — la flèche de manœuvre et la distance jusqu'à celle-ci, directement sur le pare-brise.",
      mockupLabel: "Voir l'écran 2 (Principal)",
      featuresTitle: 'Comment ça marche',
      features: [
        {
          title: '🧭 Guidage depuis Maps / Waze',
          text:
            "DashCast lit la notification de guidage détaillé que votre app de navigation publie déjà (Google Maps, Waze) et transmet la manœuvre + la distance au HUD via le bus CAN de la voiture. Aucune app supplémentaire n'est nécessaire.",
        },
        {
          title: '🚗 Dépend du firmware',
          text:
            "Seuls les firmwares HUD DiLink 3 récents peuvent afficher les flèches. Si le vôtre ne le peut pas, les flèches n'apparaîtront tout simplement pas — DashCast ne peut pas ajouter une capacité que le matériel du HUD n'a pas.",
        },
        {
          title: '➡️ Symboles de direction corrects',
          text:
            "Les manœuvres tout droit / à gauche / à droite correspondent au symbole HUD approprié, avec le décompte en direct de la distance jusqu'au virage.",
        },
      ],
      howTo: {
        title: 'Comment obtenir les flèches sur le HUD',
        steps: [
          "Assurez-vous que l'accès aux notifications est accordé à DashCast (il le demande à la première utilisation).",
          "Allumez le HUD pare-brise et réglez-le sur un mode d'affichage navigation dans le menu HUD BYD.",
          'Démarrez un itinéraire dans Google Maps ou Waze.',
          "La flèche de manœuvre et la distance apparaissent sur le HUD à l'approche de chaque virage.",
        ],
      },
      note:
        "ℹ️ Les flèches HUD sont une fonctionnalité DiLink 3 et dépendent du firmware de votre HUD. Si rien ne s'affiche, votre HUD est peut-être antérieur à la prise en charge des flèches — c'est une limite matérielle, pas un bug DashCast.",
    },

    {
      id: 'hotspot',
      screen: 'screen-3',
      title: "6. Assistant point d'accès Wi-Fi (DiLink 3)",
      lead:
        "Sur DiLink 3, si vous connectez la voiture à Internet via votre propre SIM/téléphone, l'assistant point d'accès maintient ce partage de connexion actif pour que la navigation et le streaming continuent de fonctionner. Il n'apparaît dans la barre de navigation que lorsqu'il vous concerne.",
      mockupLabel: "Voir l'écran 3 (Réglages)",
      featuresTitle: 'Fonctionnalités',
      features: [
        {
          title: '📶 Maintien de connexion',
          text:
            "Réactive le partage Wi-Fi au réveil de la voiture (ex. après avoir mis le contact ACC) pour que vous n'ayez pas à le réactiver manuellement à chaque trajet.",
        },
        {
          title: '👁️ État en direct',
          text:
            "Indique si le point d'accès est actif et combien de clients sont connectés, pour confirmer que la voiture est bien en ligne.",
        },
        {
          title: "⚙️ Affiché seulement quand c'est utile",
          text:
            "L'entrée Point d'accès n'apparaît que sur DiLink 3 et uniquement tant que « Utiliser ma propre SIM » est activé dans Réglages. Sur les autres configurations, elle reste masquée.",
        },
      ],
      howTo: {
        title: "Comment l'utiliser",
        steps: [
          'Réglages → assurez-vous que « Utiliser ma propre SIM » est activé.',
          "Ouvrez « Point d'accès » depuis la barre de navigation.",
          "Démarrez / confirmez le partage — l'état indique qu'il est actif.",
          'Il se réactive tout seul au prochain réveil de la voiture.',
        ],
      },
      note:
        "ℹ️ Cet assistant est destiné aux voitures DiLink 3 connectées via vos propres données. Si votre voiture dispose de son propre forfait de données intégré, vous n'en avez pas besoin.",
    },

    {
      id: 'bugreport',
      screen: 'screen-6',
      title: '7. Signaler un problème (signalement de bug)',
      lead:
        "Un outil de signalement de bug sans clavier, directement dans la voiture. En trois taps, vous choisissez ce qui n'a pas fonctionné ; DashCast capture un instantané de diagnostic borné (journaux + état système) et l'envoie directement au canal de support — sans saisie, sans câble.",
      mockupLabel: "Voir l'écran 6 (Signalement)",
      featuresTitle: 'Comment ça marche',
      features: [
        {
          title: '1️⃣ Catégorie',
          text:
            'Choisissez le domaine concerné : miroir, une app, son, connexion, blocage, HUD… Six grandes tuiles, au tap uniquement.',
        },
        {
          title: '2️⃣ App',
          text:
            "DashCast détecte automatiquement l'app actuellement sur le cluster et la propose, avec « Aucune app spécifique » et « Autre ».",
        },
        {
          title: '3️⃣ Problème',
          text:
            "Choisissez le symptôme le plus proche dans une courte liste. Un champ de texte libre optionnel vous permet d'ajouter des détails si vous le souhaitez — mais ce n'est jamais obligatoire.",
        },
        {
          title: '📎 Diagnostics automatiques',
          text:
            "Le rapport rassemble les journaux récents et l'état système/cluster au moment du problème — exactement ce dont le support a besoin, capturé pour vous.",
        },
        {
          title: '🚀 Envoi en un tap',
          text:
            "Si un canal de support est configuré, le rapport est envoyé directement ; sinon, DashCast ouvre le menu de partage Android pour l'envoyer par Telegram, e-mail ou GitHub.",
        },
        {
          title: "📺 Depuis n'importe où",
          text:
            "Le bouton flottant 📺 et la barre de navigation ouvrent tous deux l'outil de signalement, pour que vous puissiez envoyer un rapport même pendant qu'une autre app est projetée.",
        },
      ],
      howTo: {
        title: 'Comment envoyer un rapport',
        steps: [
          'Ouvrez le signalement de bug (barre de navigation ou bouton flottant).',
          'Touchez la catégorie correspondant au problème.',
          "Confirmez l'app (ou choisissez « Aucune app spécifique »).",
          'Choisissez le problème le plus proche ; ajoutez une note si utile.',
          'Touchez Envoyer — les diagnostics partent au support automatiquement.',
        ],
      },
      note:
        '🔒 Avant l\'envoi, DashCast retire le numéro de série du véhicule, les noms de réseaux Wi-Fi, les adresses matérielles et les positions. Restent le journal DashCast et celui du système Android, copiés tels quels — ils contiennent ce que les autres applications ont écrit. On vous demande votre accord une fois avant tout envoi, et rien ne quitte la voiture si vous refusez.',
    },

    {
      id: 'system',
      screen: 'screen-5',
      title: '8. Rapport système',
      lead:
        "Tableau de bord en lecture seule : versions, displays détectés et état en direct des services DashCast. C'est le premier écran à consulter quand quelque chose semble anormal.",
      mockupLabel: "Voir l'écran 5 (Système)",
      featuresTitle: 'Informations affichées',
      features: [
        {
          title: '🖥️ Displays',
          text:
            'Écran principal (résolution, densité) et display virtuel du cluster avec son état en temps réel.',
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
        "💡 Si quelque chose semble anormal, consultez d'abord cet écran, puis envoyez un rapport depuis le signalement de bug.",
      ],
    },

    {
      id: 'journal',
      screen: 'screen-6',
      title: '9. Journal',
      lead:
        "Journal interne de DashCast : toutes les actions importantes (projections, restaurations, erreurs ADB, mises à jour) y sont tracées en continu. Utile pour comprendre un comportement inattendu ; c'est aussi la donnée que le signalement de bug joint pour vous.",
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
            "Chaque ligne est préfixée par l'heure locale (HH:mm:ss.mmm) ; les durées des opérations longues sont mesurées.",
        },
      ],
      howTo: {
        title: 'Préférez le signalement de bug',
        steps: [
          "Pour la plupart des problèmes, utilisez le signalement de bug (section 7) — il capture le journal et l'état système automatiquement.",
          "L'écran Journal est là quand vous voulez lire la trace vous-même ou partager uniquement le journal brut.",
        ],
      },
      note:
        '🔒 Le journal enregistre ce que fait DashCast, y compris les noms de paquets et la sortie des commandes exécutées. Le partager depuis cet écran l\'envoie tel quel — le filtrage qui retire le numéro de série du véhicule, les noms de réseaux et les positions s\'applique aux rapports de bug, pas ici.',
    },
  ],

  faq: {
    title: '10. FAQ — Questions fréquentes',
    items: [
      {
        question: '❓ Le cluster reste noir quand je touche une app',
        answer:
          "Trois causes possibles : (1) ADB sans fil désactivé — vérifiez Paramètres BYD → Options pour les développeurs. (2) Un service arrêté — écran Système, relancez la ligne rouge. (3) L'app vient de planter — retouchez son icône. Toujours bloqué ? Envoyez un rapport depuis le signalement de bug.",
      },
      {
        question: "❓ L'image dépasse / est rognée sur le cluster",
        answer:
          "Réglages → Marges : ajustez les sliders horizontal/vertical jusqu'à ce que les bords soient corrects. Mémorisé par app — vous ne le ferez qu'une fois.",
      },
      {
        question: "❓ Comment revenir au tableau de bord BYD d'origine ?",
        answer:
          "Touchez « Arrêter projection » sur l'écran principal : DashCast restaure le cluster natif avec la taille définie dans Réglages. Si le combiné semble figé, écran Système → « Replay projection » puis arrêtez à nouveau.",
      },
      {
        question: '❓ Mon layout favori ne se lance pas au démarrage',
        answer:
          "Vérifiez les trois conditions dans Réglages : « Mode Layouts » activé, « Layout favori automatique » activé, et un layout marqué ⭐ favori (sélecteur de l'écran principal ou bouton Favori de l'onglet Layouts). Au démarrage à froid, comptez 6–8 s.",
      },
      {
        question: '❓ Pas de flèches de navigation sur mon HUD',
        answer:
          "Les flèches HUD sont une fonctionnalité DiLink 3 et nécessitent un firmware HUD capable de les afficher. Assurez-vous que l'accès aux notifications est accordé, que le HUD est allumé dans un mode d'affichage navigation, et qu'un itinéraire est en cours dans Maps/Waze. Si rien ne s'affiche, votre firmware HUD est probablement antérieur à la prise en charge des flèches — une limite matérielle, pas un bug.",
      },
      {
        question: "❓ Ai-je besoin de l'assistant point d'accès ?",
        answer:
          "Uniquement sur DiLink 3 si vous connectez la voiture à Internet via le partage de votre propre SIM/téléphone. Il maintient ce partage actif d'un réveil à l'autre. Si votre voiture a son propre forfait de données, ignorez-le — il reste masqué tant que « Utiliser ma propre SIM » n'est pas activé.",
      },
      {
        question: "❓ Comment les mises à jour s'installent-elles maintenant ?",
        answer:
          "DashCast vérifie GitHub à chaque lancement. Quand une mise à jour est téléchargée, elle s'installe en silence et relance l'app elle-même — sans invite « Installer ? ». Sur une voiture où ce n'est pas possible, elle revient à l'installateur système normal. La toute première mise à jour après votre passage à une version dotée de cette fonctionnalité peut encore demander une fois.",
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
          "⚠️ Apps avec DRM (Netflix, Disney+, Prime Video) : peuvent refuser de s'afficher sur un écran virtuel — limitation Android, pas DashCast.",
        ],
      },
      {
        question: '❓ Mises à jour : stable ou bêta ?',
        answer:
          "Le canal stable (par défaut) est testé sur véhicule avant publication. Le canal bêta (Réglages → Mises à jour → « Inclure les pré-versions ») reçoit les nouveautés dès leur compilation — utile pour tester en avance, avec un risque de régressions temporaires.",
      },
      {
        question: '❓ Je veux contribuer ou signaler un bug',
        answer:
          "Utilisez le signalement de bug intégré à l'app pour la voie la plus rapide (il joint les diagnostics pour vous). Pour le code et les demandes de fonctionnalités : GitHub https://github.com/Kiroha/byd-dashcast — Issues pour les bugs, Discussions pour les questions.",
      },
    ],
  },

  footer:
    'DashCast est un projet open-source distribué sous licence MIT. Aucune affiliation avec BYD Auto Co., Ltd.',
};
