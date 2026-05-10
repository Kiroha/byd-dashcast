export default {
  "code": "kk",
  "flag": "🇰🇿",
  "name": "Қазақша",
  "title": "DashCast — Пайдаланушы нұсқаулығы",
  "meta": "v0.5.1 · BYD Seal EU · DiLink 3.0 · Android 10",
  "manualName": "Пайдаланушы нұсқаулығы",
  "tocTitle": "📋 Мазмұны",
  "sections": [
    "1. Шолу",
    "2. Бірінші іске қосу — тілді таңдау",
    "3. Негізгі экран",
    "4. Қолданбаны аспаптар панеліне шығару",
    "5. Проекция кезінде — басқару панелі",
    "6. Проекцияны тоқтату",
    "7. Баптаулар",
    "8. ⋮ мәзірі — қосымша құралдар",
    "9. FAQ және ақауларды жою"
  ],
  "overview": {
    "title": "1. Шолу",
    "text": "DashCast — мультимедиа экранындағы кез келген қолданбаны BYD көлігіңіздің цифрлық аспаптар панеліне шығаруға мүмкіндік беретін Android қолданбасы. Навигация, музыка, видео — орталық экранда жұмыс істейтіннің бәрін жүргізуші алдындағы дисплейге бағыттауға болады.",
    "bullets": [
      "✅ Compatible BYD Seal EU (DiLink 3.0, firmware Di3.0 / 6125F)",
      "✅ Aucune modification système nécessaire",
      "✅ ADB local (TCP, localhost) — pas besoin d'ordinateur une fois configuré",
      "✅ Détection automatique de la déconnexion d'application",
      "✅ Mises à jour OTA (Over-The-Air) intégrées",
      "✅ Overscan sauvegardé indépendamment par application",
      "✅ Affichage en mode Grille ou Liste",
      "✅ Force-stop d'urgence (Croix Rouge) par application"
    ],
    "note": "💡 Алғышарт: Баптаулар → Әзірлеуші параметрлері → Сымсыз debugging (немесе «ADB over network») бөлімінде ADB TCP debugging қосыңыз. Бұл бір рет жасалады. DashCast алғаш іске қосылғанда “Allow USB debugging?” терезесі шығады — Always allow from this computer түймесін басыңыз."
  },
  "firstLaunch": {
    "title": "2. Бірінші іске қосу — тілді таңдау",
    "text": "Бірінші іске қосқанда сәлемдесу экраны көрсетіледі. Тілді таңдау үшін он түйменің бірін басыңыз. Таңдау сақталады — тілді ⋮ мәзірінен өзгертпейінше, бұл экран қайта шықпайды.",
    "welcomeSubtitle": "Аспаптар панелі контроллері",
    "welcomeHint": "Тілді таңдаңыз\nPlease select your language",
    "caption": "Тілді таңдау экраны — тек бірінші іске қосқанда көрсетіледі"
  },
  "main": {
    "title": "3. Écran principal",
    "text": "L'écran principal est composé de deux zones : une barre de statut en haut (fond bleu foncé) et une liste d'applications installées en dessous. Vous pouvez basculer entre affichage en liste classique ou en grille (icônes) via le menu ⋮.",
    "status": "① Dashboard : non connecté",
    "buttons": [
      "② Activer Projection",
      "③ Arrêter Projection",
      "④ Restaurer Dashboard d'origine",
      "⑤ ⋮",
      "✕",
      "✕",
      "✕",
      "✕"
    ],
    "listTitle": "⑥ Applications installées",
    "apps": [
      "Maps",
      "YouTube",
      "Spotify",
      "Waze"
    ],
    "caption": "Écran principal — aucune application projetée (état initial)",
    "annotations": [
      {
        "tone": "",
        "marker": "①",
        "label": "Statut",
        "text": "Indique l'état de la connexion au tableau de bord. Passe à « Dashboard : [NomApp] » quand une application est active."
      },
      {
        "tone": "",
        "marker": "②",
        "label": "Activer Projection",
        "text": "Établit la connexion avec le cluster et prépare l'envoi d'une application. À appuyer en premier."
      },
      {
        "tone": "red",
        "marker": "③",
        "label": "Arrêter Projection",
        "text": "Ferme la projection en cours sans restaurer le tableau de bord d'origine BYD."
      },
      {
        "tone": "green",
        "marker": "④",
        "label": "Restaurer Dashboard d'origine",
        "text": "Ferme la projection ET réaffiche le tableau de bord BYD natif (vitesse, jauges…)."
      },
      {
        "tone": "gray",
        "marker": "⑤",
        "label": "Menu ⋮",
        "text": "Accès aux Paramètres, Diagnostic, Rapport système, Logs, changement de langue et bascule Grille/Liste."
      },
      {
        "tone": "gray",
        "marker": "⑥",
        "label": "Liste des applications",
        "text": "Toutes les apps installées. Appuyez sur une app pour la projeter, ✕ pour la fermer, ❌ pour forcer l'arrêt complet du processus."
      }
    ]
  },
  "projection": {
    "title": "4. Projeter une application sur le tableau de bord",
    "steps": [
      "Appuyez sur « Activer Projection » (bouton bleu en haut). Le statut passe à « Démarrage cluster… ». La connexion ADB locale s'établit et le cluster passe en mode projection.",
      "Tapez sur l'application souhaitée dans la liste. DashCast déplace l'application vers le display du tableau de bord. Le statut passe à « Dashboard : [Nom de l'app] ».",
      "Le panneau de contrôle apparaît en bas de l'écran. Les valeurs d'overscan sauvegardées pour cette application sont appliquées automatiquement."
    ],
    "activeStatus": "Dashboard : Maps ✓",
    "buttons": [
      "Activer Projection",
      "📺 Miroir",
      "Arrêter Projection",
      "Restaurer Dashboard d'origine",
      "⋮",
      "← Principal",
      "✕",
      "→ Cluster",
      "✕",
      "→ Cluster",
      "✕",
      "📐 Ajuster",
      "⬛⬛ Split",
      "Masquer ▼"
    ],
    "listTitle": "Applications installées",
    "apps": [
      "Maps",
      "YouTube",
      "Spotify"
    ],
    "controlLabel": "Contrôle cluster",
    "controlApp": "Maps",
    "mirrorText": "Affichage actif sur le cluster ✓",
    "caption": "Écran principal — Maps est projetée sur le tableau de bord",
    "annotations": [
      {
        "tone": "green",
        "marker": "●",
        "label": "Barre verte",
        "text": "Indicateur visuel sur chaque item : l'app est actuellement sur le cluster."
      },
      {
        "tone": "",
        "marker": "→",
        "label": "→ Cluster",
        "text": "Envoie une autre application sur le cluster (remplace celle en cours)."
      },
      {
        "tone": "gray",
        "marker": "←",
        "label": "← Principal",
        "text": "Renvoie l'application vers l'écran central (la retire du cluster)."
      },
      {
        "tone": "teal",
        "marker": "📺",
        "label": "Miroir",
        "text": "Affiche un aperçu en temps réel du contenu du tableau de bord dans DashCast."
      },
      {
        "tone": "red",
        "marker": "❌",
        "label": "Croix Rouge (Force Stop)",
        "text": "Force l'arrêt complet du processus d'une application bloquée et la retire des Récents."
      },
      {
        "tone": "gray",
        "marker": "🔲",
        "label": "Vue Grille / Liste",
        "text": "Basculez l'affichage entre liste classique et grille d'icônes via le menu ⋮."
      }
    ]
  },
  "control": {
    "title": "5. Pendant la projection — Panneau de contrôle",
    "intro": "Lorsqu'une application est active sur le cluster, un panneau sombre apparaît en bas de l'écran principal avec quatre fonctionnalités :",
    "mirror": {
      "title": "5.1 Miroir (📺 Miroir)",
      "text": "Appuyez sur 📺 Miroir dans la barre de statut pour afficher une copie du tableau de bord directement dans DashCast. Vous pouvez interagir avec cette copie par toucher — les événements sont transmis au cluster.",
      "note": "Le miroir utilise SurfaceControl pour capturer le display. Si le miroir ne s'affiche pas, un screenshot automatique toutes les 2 secondes prend le relais."
    },
    "resize": {
      "title": "5.2 Ajuster (📐 Overscan par application)",
      "text": "Le bouton 📐 Ajuster affiche deux curseurs : Marge Largeur et Marge Hauteur. Ces valeurs rognent les bords de l'image projetée sur le cluster. Elles sont sauvegardées individuellement pour chaque application et ré-appliquées automatiquement à chaque lancement via wm overscan.",
      "note": "💡 Valeurs recommandées pour le Seal EU : Largeur 80 px, Hauteur 50 px."
    },
    "split": {
      "title": "5.3 Mode Split (⬛⬛ Split)",
      "text": "Appuyez sur ⬛⬛ Split pour partager le tableau de bord entre deux applications :",
      "items": [
        "Plein écran — Une seule app occupe tout le cluster",
        "⬜⬛ Gauche (50 %) — L'app principale à gauche, la seconde à droite",
        "⬛⬜ Droite (50 %) — L'app principale à droite"
      ],
      "extra": "En mode Split, une deuxième application peut être sélectionnée dans la liste. Elle occupera l'autre moitié du cluster."
    },
    "hide": {
      "title": "5.4 Masquer le panneau",
      "text": "Appuyez sur Masquer ▼ pour replier le panneau de contrôle et retrouver la liste complète des applications."
    }
  },
  "stopping": {
    "title": "6. Arrêter la projection",
    "intro": "Deux boutons permettent d'arrêter la projection :",
    "table": {
      "headers": [
        "Bouton",
        "Comportement",
        "Quand l'utiliser"
      ],
      "rows": [
        [
          "Arrêter Projection",
          "Coupe la projection. Le tableau de bord reste vide (noir).",
          "Si vous souhaitez juste stopper l'affichage provisoirement."
        ],
        [
          "Restaurer Dashboard d'origine",
          "Coupe la projection ET réactive l'affichage BYD natif (vitesse, autonomie, jauges…).",
          "En fin d'utilisation — pour retrouver le tableau de bord BYD normal."
        ]
      ]
    },
    "warning": "⚠️ Si vous quittez DashCast sans appuyer sur l'un de ces boutons, la projection reste active sur le cluster jusqu'au prochain redémarrage du service."
  },
  "settings": {
    "title": "7. Paramètres",
    "intro": "Accédez aux Paramètres via le menu ⋮ → ⚙️ Paramètres. L'écran contient deux sections :",
    "titleLabel": "Paramètres",
    "clusterTypeLabel": "Type de cluster",
    "clusterOptions": [
      "8,8 pouces (cmd=29)",
      "12,3 pouces (cmd=30) — Seal EU",
      "10,25 pouces (cmd=31)"
    ],
    "marginsLabel": "Marges d'affichage (overscan global)",
    "horizontalMarginLabel": "Gauche / Droite :",
    "verticalMarginLabel": "Haut / Bas :",
    "applyButton": "Appliquer maintenant",
    "resetButton": "Réinitialiser (80 / 50)",
    "caption": "Page Paramètres",
    "type": {
      "title": "7.1 Type de cluster",
      "text": "Sélectionnez la taille de l'écran de votre tableau de bord. Pour le BYD Seal EU, sélectionnez 12,3 pouces (cmd=30). Ce réglage détermine la commande envoyée au cluster lors de l'activation."
    },
    "margins": {
      "title": "7.2 Marges d'affichage globales (overscan)",
      "text": "Réglez les marges pour cadrer parfaitement le contenu dans la zone visible de l'écran. Ces marges s'appliquent globalement. Pour des réglages par application, utilisez le bouton 📐 Ajuster dans le panneau de contrôle.",
      "items": [
        "Gauche / Droite — Marge horizontale (0–200 px des deux côtés)",
        "Haut / Bas — Marge verticale (0–200 px en haut et en bas)"
      ],
      "applyText": "Cliquez Appliquer maintenant pour voir le résultat immédiatement si une application est en cours de projection. Les valeurs sont mémorisées entre les sessions.",
      "note": "💡 Valeurs par défaut recommandées pour le Seal EU : Gauche/Droite = 80 px, Haut/Bas = 50 px."
    }
  },
  "tools": {
    "title": "8. Menu ⋮ — Outils supplémentaires",
    "intro": "Le bouton ⋮ en haut à droite ouvre un menu avec les entrées suivantes :",
    "table": {
      "headers": [
        "Option",
        "Description"
      ],
      "rows": [
        [
          "⚙️ Paramètres",
          "Type de cluster + réglage des marges overscan globales"
        ],
        [
          "🔲 Grille / Liste",
          "Bascule l'affichage des applications entre liste classique et grille d'icônes (5 colonnes)"
        ],
        [
          "🔧 Diagnostic",
          "Tests avancés pour développeurs — connexion ADB, displays, taille écran cluster, sniffer ADB"
        ],
        [
          "📋 Rapport système",
          "Génère un rapport complet (displays, APIs BYD, permissions) — utile pour le support"
        ],
        [
          "📜 Logs",
          "Journal de bord en temps réel — filtre par tag/niveau, partage par mail ou fichier (appui long pour Telegram)"
        ],
        [
          "🌐 Langue",
          "Retourne à l'écran de sélection de langue"
        ]
      ]
    },
    "logs": {
      "title": "Journal de bord (📜 Logs)",
      "header": "📋 Journal de bord",
      "clearButton": "Effacer",
      "shareButton": "Partager",
      "filterPlaceholder": "Filtrer (tag / message / niveau)…",
      "lines": [
        "[INFO ] ClusterService → Cluster display connected: id=1",
        "[INFO ] launchOnDashboard OK → com.google.android.apps.maps",
        "[DEBUG] watchdog started for com.google.android.apps.maps pid=4821",
        "[WARN ] setTaskWindowingMode: SecurityException (expected)",
        "[INFO ] wm overscan applied on display 1 inset=80,50"
      ],
      "caption": "Journal de bord — filtre en temps réel, export disponible"
    }
  },
  "faq": {
    "title": "9. FAQ & Résolution de problèmes",
    "items": [
      {
        "question": "❓ La popup « Autoriser le débogage ADB ? » ne s'affiche pas",
        "answer": "Vérifiez que le Débogage ADB TCP est bien activé dans les paramètres développeur de l'infodivertissement. Si l'option est absente, activez d'abord le mode développeur (appuyer 7 fois sur le numéro de build dans À propos).",
        "items": []
      },
      {
        "question": "❓ L'application ne s'affiche pas sur le cluster après la sélection",
        "answer": "",
        "items": [
          "Assurez-vous d'avoir appuyé sur Activer Projection avant de sélectionner l'app.",
          "Certaines applications refusent d'être lancées sur un display secondaire (restrictions internes). Consultez les Logs pour voir le message d'erreur.",
          "Essayez de fermer puis rouvrir DashCast, puis recommencez la séquence."
        ]
      },
      {
        "question": "❓ Le contenu est rogné ou décalé sur le cluster",
        "answer": "Utilisez le bouton 📐 Ajuster dans le panneau de contrôle pour régler précisément les marges par application. Les valeurs globales dans Paramètres s'appliquent en fallback.",
        "items": []
      },
      {
        "question": "❓ Une application est bloquée / figée sur le cluster",
        "answer": "Appuyez sur la ❌ Croix Rouge à côté de l'application dans la liste. Cela force l'arrêt complet du processus et nettoie les Récents. L'application est alors prête à être relancée.",
        "items": []
      },
      {
        "question": "❓ Les boutons ← Principal et ✕ restent visibles après avoir fermé l'app",
        "answer": "DashCast détecte automatiquement la fermeture des applications (surveillance via /proc). Si l'interface reste bloquée, appuyez sur Arrêter Projection pour forcer la réinitialisation.",
        "items": []
      }
    ]
  },
  "footer": "DashCast v0.5.1 — BYD Seal EU · DiLink 3.0 · Android 10 · github.com/Kiroha/byd-dashcast"
};
