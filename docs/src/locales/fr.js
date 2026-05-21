export default {
  code: 'fr',
  flag: '🇫🇷',
  name: 'Français',
  title: "DashCast — Manuel d'utilisation",
  manualName: "Manuel d'utilisation",
  meta: 'v0.9.92-alpha · BYD Seal EU · DiLink 3.0 · Android 10',
  tocTitle: '📋 Sommaire',

  intro: {
    title: '0. Introduction',
    lead:
      "DashCast permet d'afficher n'importe quelle application Android de l'écran central de votre BYD sur le combiné d'instruments (cluster numérique). Vous pouvez ainsi avoir Maps, Waze, Spotify ou YouTube directement devant le volant, tout en gardant les fonctions natives BYD (vitesse, jauges, autonomie) accessibles à tout moment.",
    bullets: [
      "✅ Compatible BYD Seal EU (DiLink 3.0, firmware Di3.0 / 6125F).",
      "✅ Aucune modification système : DashCast s'installe comme une app classique.",
      "✅ ADB local en TCP — aucun ordinateur nécessaire après la première autorisation.",
      "✅ 12 langues d'interface, choix au premier démarrage.",
      "✅ Mises à jour OTA intégrées (canal alpha optionnel).",
      "✅ Marges d'overscan mémorisées par application (chaque app garde son ajustement).",
      "✅ Mode plein écran tactile pour piloter le cluster depuis l'écran central.",
      "✅ Mode split (deux applications côte-à-côte sur le cluster).",
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
        "Au tout premier lancement, DashCast affiche une grille avec les 12 langues disponibles. Touchez votre langue pour la sélectionner ; ce choix est mémorisé et l'écran de bienvenue ne réapparaîtra plus. Vous pourrez le changer à tout moment via Réglages → Langue.",
      mockupLabel: "Voir l'écran 1 (Welcome)",
      featuresTitle: 'Détails',
      features: [
        {
          title: '12 langues prises en charge',
          text:
            "Français, English, Deutsch, Italiano, Türkçe, Español, Русский, Українська, العربية, O'zbekcha, Қазақша, Беларуская. La langue choisie est appliquée immédiatement sans redémarrage.",
        },
        {
          title: 'Sens de lecture automatique',
          text:
            "L'arabe bascule automatiquement en mise en page droite-à-gauche (RTL) : la barre de navigation passe à droite, les listes s'inversent, les icônes restent lisibles.",
        },
        {
          title: 'Modifiable à tout moment',
          text:
            "Pour changer de langue plus tard : appui long sur le logo DashCast en haut de la barre latérale → 🌐 Langue. La nouvelle langue est appliquée à la volée.",
        },
      ],
      howTo: {
        title: 'Comment faire',
        steps: [
          "Lancez DashCast (icône bleue dans le tiroir d'apps BYD).",
          "L'écran de bienvenue s'affiche avec la grille 4×3 des langues.",
          "Touchez votre langue. L'interface bascule immédiatement.",
          "L'écran principal s'ouvre — vous êtes prêt à utiliser DashCast.",
        ],
      },
      note:
        "ℹ️ Si vous changez de langue alors qu'une projection est en cours, la projection continue sans interruption ; seule l'interface DashCast est traduite.",
    },

    // ────── 2. MAIN ──────────────────────────────────────────────────
    {
      id: 'main',
      screen: 'screen-2',
      title: '2. Écran principal — Apps & Cluster',
      lead:
        "C'est l'écran central de DashCast. À gauche, la liste de toutes vos applications installées avec recherche, filtres par catégorie et favoris. À droite, l'aperçu temps réel du cluster avec les actions principales : aperçu plein écran, capture, reconnexion, arrêt de la projection.",
      mockupLabel: "Voir l'écran 2 (Main)",
      featuresTitle: 'Tout ce que vous pouvez faire',
      features: [
        {
          title: '🔍 Barre de recherche',
          text:
            "Tapez quelques lettres pour filtrer la liste à la volée (fonctionne sur le nom de l'app et le package). Le bouton ▦ à droite bascule entre liste et grille d'icônes.",
        },
        {
          title: '🏷️ Filtres par catégorie',
          text:
            "Les puces colorées (Toutes / Navigation / Média / Communication / Système) regroupent automatiquement vos apps. Le compteur entre parenthèses indique combien d'apps sont visibles.",
        },
        {
          title: '⭐ Favoris épinglés',
          text:
            "La section « Favoris » regroupe en haut les apps que vous utilisez le plus. Pour ajouter ou retirer un favori : appui long sur l'app → ⭐ Ajouter/Retirer des favoris.",
        },
        {
          title: '👆 Tap court — projeter',
          text:
            "Touchez une app pour l'envoyer immédiatement sur le cluster. Si la projection n'était pas active, elle démarre automatiquement (préchauffage du cluster, ~2 s).",
        },
        {
          title: '👆⏱️ Appui long — menu d\'actions',
          text:
            "Maintenez l'appui sur n'importe quelle app pour ouvrir un menu plein écran : ⭐ Favori, Auto-launch (lancer dès le démarrage), Déplacer vers cluster / écran principal, ✕ Forcer l'arrêt.",
        },
        {
          title: '🚦 Aperçu cluster temps réel',
          text:
            "Le panneau de droite reflète en direct ce qui s'affiche sur le combiné (vitesse, rapport, batterie, autonomie). La latence affichée (12 ms) confirme que la connexion est fluide.",
        },
        {
          title: '👁️ Aperçu plein écran',
          text:
            "Touchez « Aperçu plein écran » pour étendre la prévisualisation à l'écran central entier. Pratique pour taper une adresse dans Maps avec le clavier complet : votre saisie est répliquée sur le cluster.",
        },
        {
          title: '📸 Capture',
          text:
            "Le bouton « Capturer » enregistre l'état actuel du cluster en PNG dans /sdcard/Pictures/DashCast/. Utile pour partager un trajet ou diagnostiquer un affichage.",
        },
        {
          title: '↻ Reconnecter',
          text:
            "Si l'app affichée a planté ou ne réagit plus, « Reconnecter » force la relance du flux vidéo sans toucher au cluster d'origine.",
        },
        {
          title: '⏹ Arrêter mirror',
          text:
            "Termine proprement la projection. Tap court = arrêt simple (le cluster repasse en BYD natif via ADB). Appui long = menu enrichi avec « Restaurer cluster d'origine » qui force la séquence de restauration en utilisant la taille d'écran définie dans Réglages.",
        },
      ],
      howTo: {
        title: 'Comment projeter une app sur le cluster',
        steps: [
          "Sur l'écran principal, repérez l'app que vous voulez (ex. Maps).",
          "Touchez son icône → la projection démarre, le cluster bascule sur l'app en ~2 s.",
          "L'aperçu droit affiche en direct ce qui est sur le cluster.",
          "Pour saisir du texte (recherche d'adresse), touchez « Aperçu plein écran » → l'app passe sur tout l'écran central → tapez votre adresse → tout est répliqué sur le cluster.",
          "Pour arrêter : touchez « Arrêter mirror » (le cluster repasse en BYD natif).",
        ],
      },
      tipsTitle: 'Astuces',
      tips: [
        "💡 Auto-launch : activez ce switch sur une app pour qu'elle se projette automatiquement à chaque démarrage de DashCast.",
        "💡 Mode split : depuis le menu long-press d'une seconde app, choisissez « Envoyer en split » pour afficher 2 apps côte-à-côte sur le cluster.",
        "💡 Marges : si l'app déborde du cluster, ouvrez Réglages → Marges et ajustez les sliders. Le réglage est mémorisé par app.",
        "💡 Plein écran tactile : en mode aperçu plein écran, vos doigts sur l'écran central pilotent réellement l'app — clavier, scroll, gestes, tout fonctionne.",
      ],
    },

    // ────── 3. SETTINGS ──────────────────────────────────────────────
    {
      id: 'settings',
      screen: 'screen-3',
      title: '3. Réglages',
      lead:
        "L'écran Réglages regroupe les options globales et le réglage de l'image projetée. La barre latérale gauche reste accessible — vous pouvez basculer entre Apps, Réglages, Diag, Système et Journal sans perdre votre position.",
      mockupLabel: "Voir l'écran 3 (Réglages)",
      featuresTitle: 'Sections disponibles',
      features: [
        {
          title: '📺 Type de cluster',
          text:
            "Choisissez la taille physique de votre combiné : 8.8″ (sendInfo cmd 29), 12.3″ Seal EU (cmd 30, défaut), ou 10.25″ (cmd 31). Cette valeur est utilisée notamment par « Restaurer cluster d'origine » pour relancer le bon mode.",
        },
        {
          title: '🌐 Langue',
          text:
            "12 langues disponibles. Le changement est instantané — pas de redémarrage de DashCast nécessaire.",
        },
        {
          title: '↔️ Marge horizontale (overscan)',
          text:
            "Slider 0–200 px. Ajoute des bandes noires gauche/droite pour compenser les bords coupés de votre cluster. La valeur est mémorisée par application — Maps peut avoir 80 px tandis que Spotify reste à 0.",
        },
        {
          title: '↕️ Marge verticale (overscan)',
          text:
            "Slider 0–200 px. Idem pour le haut/bas. Les marges combinées s'appliquent à la VirtualDisplay donc l'app projetée ne « voit » jamais les zones coupées.",
        },
        {
          title: '✅ Appliquer / 🔄 Réinitialiser',
          text:
            "« Appliquer » envoie immédiatement les nouvelles marges à la projection en cours. « Réinitialiser » remet l'app courante à 0/0.",
        },
        {
          title: '📦 Mises à jour OTA',
          text:
            "DashCast vérifie automatiquement les nouvelles versions sur GitHub Releases. Cochez « Inclure les pré-versions » pour recevoir le canal alpha (mises à jour plus fréquentes mais expérimentales).",
        },
        {
          title: '🚗 Lancement au démarrage du véhicule',
          text:
            "Si activé, DashCast démarre automatiquement avec la voiture et restaure la dernière app projetée. Sinon, vous le lancez manuellement depuis le tiroir BYD.",
        },
      ],
      howTo: {
        title: "Comment ajuster les marges d'une app",
        steps: [
          "Démarrez la projection de l'app à ajuster (ex. Waze).",
          "Allez dans Réglages → Marges.",
          "Déplacez le slider horizontal jusqu'à ce que les bords gauche/droit soient corrects.",
          "Idem pour le slider vertical.",
          "Touchez « Appliquer » → la projection est ajustée à chaud, sans redémarrer l'app.",
          "Le réglage est sauvegardé pour cette app uniquement (chaque app peut avoir ses propres marges).",
        ],
      },
      note:
        "⚠️ Si vous changez le type de cluster, redémarrez DashCast pour que les valeurs de référence soient bien re-calculées.",
    },

    // ────── 4. DIAGNOSTICS ───────────────────────────────────────────
    {
      id: 'diagnostics',
      screen: 'screen-4',
      title: '4. Diagnostics',
      lead:
        "L'onglet Diagnostics est un tableau de bord interne réservé aux situations où la projection ne fonctionne pas comme prévu. La majorité des utilisateurs n'en aura jamais besoin — il est exposé pour le support et le débogage.",
      mockupLabel: "Voir l'écran 4 (Diagnostics)",
      featuresTitle: 'Outils disponibles',
      features: [
        {
          title: 'État du service ClusterService',
          text:
            "Vérifie que le service Android qui gère la projection tourne. En cas de « non lié », un bouton permet de le relancer.",
        },
        {
          title: 'État de la VirtualDisplay',
          text:
            "Affiche l'ID de l'écran virtuel créé pour le cluster, sa résolution, et si une Surface Qt y est bien attachée.",
        },
        {
          title: 'Connexion ADB locale',
          text:
            "Test rapide du tunnel ADB → localhost:5555. Si le test échoue, c'est généralement parce que le débogage sans fil a été désactivé dans les paramètres BYD.",
        },
        {
          title: 'Logcat ciblé',
          text:
            "Capture les 200 dernières lignes de logcat filtrées sur DashCast / AutoContainer / xdja. Bouton « Partager » pour envoyer le rapport.",
        },
      ],
      howTo: {
        title: 'Quand utiliser cet onglet',
        steps: [
          "Le cluster reste noir après avoir touché une app → vérifiez l'état ClusterService et VirtualDisplay.",
          "L'app affiche « ADB non disponible » → onglet Diag → bouton « Tester ADB ».",
          "Le support technique vous demande un rapport → onglet Diag → « Partager le logcat ».",
          "Une mise à jour vient d'être installée et vous voulez confirmer la version active.",
        ],
      },
      note:
        "ℹ️ Cet onglet ne modifie rien par lui-même : les boutons sont des tests en lecture seule, sauf indication explicite.",
    },

    // ────── 5. SYSTEM INFO ───────────────────────────────────────────
    {
      id: 'sysinfo',
      screen: 'screen-5',
      title: '5. Informations système',
      lead:
        "Tableau de bord en lecture seule sur l'environnement matériel et logiciel. C'est ici que vous trouverez la version DashCast, le firmware BYD, la version Android, et l'identifiant de votre cluster.",
      mockupLabel: "Voir l'écran 5 (Système)",
      featuresTitle: 'Informations affichées',
      features: [
        {
          title: '🚗 Véhicule',
          text:
            "Modèle BYD détecté, VIN (si disponible), build firmware (ex. Di3.0 / 6125F), date de compilation du firmware.",
        },
        {
          title: '📱 Android',
          text:
            "Version Android (10), niveau de l'API (29), patch de sécurité, build ID DiLink.",
        },
        {
          title: '🔌 DashCast',
          text:
            "Version installée, versionCode, canal de mise à jour (stable / alpha), date du dernier check OTA, lien vers les notes de version.",
        },
        {
          title: '🖥️ Cluster',
          text:
            "Type détecté (8.8″ / 12.3″ / 10.25″), résolution réelle, ID de la VirtualDisplay actuelle, package Qt actif (com.xdja.containerservice).",
        },
        {
          title: '📦 Apps suivies',
          text:
            "Nombre d'apps détectées par DashCast, nombre d'apps épinglées en favoris, nombre d'apps avec auto-launch activé.",
        },
      ],
      tipsTitle: 'Astuces',
      tips: [
        "💡 Tap long sur une ligne d'information pour copier la valeur dans le presse-papier (utile pour un rapport de bug).",
        "💡 Le bouton « Exporter » en bas de l'écran enregistre toutes les infos dans un fichier texte (/sdcard/DashCast/sysinfo.txt).",
      ],
    },

    // ────── 6. JOURNAL ───────────────────────────────────────────────
    {
      id: 'journal',
      screen: 'screen-6',
      title: '6. Journal',
      lead:
        "Journal interne de DashCast : trace toutes les actions importantes (projections, restaurations, erreurs ADB, mises à jour). Utile pour comprendre un comportement inattendu ou fournir un rapport au support.",
      mockupLabel: "Voir l'écran 6 (Journal)",
      featuresTitle: 'Fonctionnalités',
      features: [
        {
          title: '🔍 Filtre',
          text:
            "Saisissez un mot-clé pour ne garder que les lignes pertinentes (ex. « ADB », « Maps », « error »). Le filtre est insensible à la casse.",
        },
        {
          title: '🎨 Code couleur',
          text:
            "🟢 INFO (vert) — opération normale. 🟠 WARN (orange) — attention. 🔴 ERROR (rouge) — quelque chose a échoué. ⚪ DEBUG (gris) — détail technique.",
        },
        {
          title: '🗑 Effacer',
          text:
            "Vide le journal. La trace logcat système n'est pas affectée — seul l'historique DashCast en mémoire est purgé.",
        },
        {
          title: '📤 Partager',
          text:
            "Exporte le journal courant en .txt et ouvre le menu de partage Android (e-mail, Telegram, fichier). Inclut automatiquement la version DashCast et le modèle BYD.",
        },
        {
          title: '⏰ Horodatage',
          text:
            "Chaque ligne est préfixée par l'heure locale (HH:mm:ss.mmm). Les durées d'opérations longues (lancement Maps, restauration cluster) sont mesurées et affichées.",
        },
      ],
      howTo: {
        title: 'Envoyer un rapport de bug',
        steps: [
          "Reproduisez le problème (ex. l'app reste noire au démarrage).",
          "Allez dans Journal.",
          "Touchez « Partager ».",
          "Choisissez votre canal (Telegram, e-mail, GitHub Issues).",
          "Le fichier .txt joint contient toute la trace + le contexte (version, modèle, firmware).",
        ],
      },
      note:
        "🔒 Aucune donnée personnelle (contacts, position GPS, contenu d'app) n'est journalisée — uniquement les actions DashCast et les codes de retour techniques.",
    },
  ],

  faq: {
    title: '7. FAQ — Questions fréquentes',
    items: [
      {
        question: '❓ Le cluster reste noir quand je touche une app',
        answer:
          "Trois causes possibles : (1) ADB sans fil désactivé — vérifiez Paramètres BYD → Développeur. (2) ClusterService non démarré — onglet Diag, bouton Relancer. (3) L'app vient de planter — touchez « Reconnecter » dans le panneau droit.",
      },
      {
        question: "❓ L'image dépasse / est rognée sur le cluster",
        answer:
          "Ouvrez Réglages → Marges et ajustez les sliders horizontal/vertical jusqu'à ce que les bords soient corrects. Le réglage est mémorisé pour cette app — vous ne le ferez qu'une fois.",
      },
      {
        question: "❓ Comment revenir au tableau de bord BYD d'origine ?",
        answer:
          "Tap court sur « Arrêter mirror » suffit dans 95 % des cas. Si le cluster reste figé, faites un appui long sur ce même bouton → menu → « Restaurer cluster d'origine » : DashCast force la séquence sendInfo correspondant à votre type de cluster.",
      },
      {
        question: "❓ Est-ce que DashCast vide la batterie 12 V ?",
        answer:
          "Non — DashCast s'arrête automatiquement quand la voiture s'éteint (broadcast Android.intent.action.SCREEN_OFF + déconnexion BMS). Aucun service de fond ne reste actif moteur coupé.",
      },
      {
        question: '❓ Je veux contribuer ou signaler un bug',
        answer:
          "GitHub : https://github.com/Kiroha/byd-dashcast — section Issues pour les bugs, Discussions pour les questions. Joignez toujours un export du Journal (onglet Journal → Partager) pour accélérer le diagnostic.",
      },
      {
        question: "❓ Quelles apps fonctionnent sur le cluster ?",
        items: [
          "✅ Navigation : Google Maps, Waze, Yandex Navi, OsmAnd, Magic Earth.",
          "✅ Média : Spotify, YouTube, YouTube Music, Netflix (préférer landscape).",
          "✅ Communication : Telegram (mode lecture seule), WhatsApp (notifications).",
          "✅ Système : caméra, météo, agenda.",
          "⚠️ Apps avec DRM Widevine L1 (Disney+, Prime Video) : peuvent refuser de s'afficher sur un VirtualDisplay — c'est une limitation Android, pas DashCast.",
        ],
      },
      {
        question: '❓ Mises à jour : stable ou alpha ?',
        answer:
          "Le canal stable (par défaut) est testé sur véhicule au moins 1 semaine avant publication. Le canal alpha (à activer dans Réglages → Mises à jour) reçoit les nouveautés dès qu'elles sont compilées — utile pour tester en avance, mais peut introduire des régressions temporaires.",
      },
    ],
  },

  footer:
    'DashCast est un projet open-source distribué sous licence GPL-3.0. Aucune affiliation avec BYD Auto Co., Ltd.',
};
