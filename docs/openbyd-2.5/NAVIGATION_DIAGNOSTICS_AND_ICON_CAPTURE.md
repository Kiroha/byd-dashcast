# Diagnostic du guidage Maps et export des icônes

Changements préparés le **2026-10-09**, après les rapports SX326 de @giino01 et les essais SendInfo2, inclus dans **1.9.8-beta / 647**. Ils ne sont pas dans la **1.9.7-beta / 646 publiée**. [Notes de version et essai](../releases/1.9.8-beta.md).

## Diagnostic Morphe

Le choix « Maps » du formulaire de rapport utilise désormais le même filtre exact que le guidage : Maps officiel, ReVanced et Morphe. En l'absence de trame décodée récemment, une notification Morphe avec distance mais sans manœuvre donne **`parse-fail`** ; un guidage décodé récemment donne **`parsed`**. La séparation avec Waze et le refus des packages ressemblants restent conservés.

Ce statut garde sa fenêtre de **30 minutes** : il peut rester `parsed` après un rejet plus récent. Les nouveaux compteurs et le dernier motif de rejet permettent d'identifier ce rejet sans confondre l'historique récent avec la dernière notification.

Deux cas contre le vrai listener et un Binder de test reproduisaient le défaut avant la correction : Morphe décodé restait absent du diagnostic Maps ; Morphe non décodé avec 100 m était aussi annoncé absent. Ils vérifient maintenant les états du formulaire, l'envoi cluster du premier cas et l'absence d'envoi du second.

## Traces conservées dans les rapports

Le rapport de bug ajoute **NAVIGATION PIPELINE**. Chaque archive du banc HUD ajoute **`06_navigation.txt`** : connexion du listener, compteurs du pipeline, sorties effectives sélectionnées à la capture et journal DashCast. Cela conserve les activations et les rejets même si les nombreuses traces OEM ont évincé les événements du logcat Android.

Les compteurs vivent dans le processus DashCast et survivent à une reconnexion du listener. Ils repartent à zéro au redémarrage du processus ; ils ne constituent pas un historique permanent du véhicule.

| Champ | Interprétation |
|---|---|
| `currentOutputs` | Destinations effectives au moment du rapport ; distinctes des destinations d'une tentative passée |
| `observed` | Notifications éligibles d'une application prise en charge observées par le listener, y compris ses rescans ; pas un nombre de manœuvres uniques |
| `duplicate` | Contenu identique au précédent ; le suivi de livraison et de péremption reste inchangé |
| `parsed` | Notifications transformées en données de guidage complètes, avant le writer qui peut remplacer une trame en attente par une plus récente |
| `rejected` | Compteurs séparés pour absence d'extras, notification ignorée, texte vide, absence de signal de guidage, de manœuvre ou de distance |
| `deliveryAttempts` | Appels réellement exécutés par le writer, pas toutes les notifications reçues |
| `acceptedAny` | Au moins une sortie sélectionnée a accepté le guidage selon le controller ; ne confirme ni toutes les sorties ni le rendu physique |
| `unavailable` / `errors` | Retour négatif du controller, notamment sorties désactivées, ou exception du writer ; les résultats du canal natif restent dans son journal |

**`NAV REJECT`** conserve package pris en charge, motif, nom de ressource de petite icône, distance et longueurs des textes. Le texte de route et la destination ne sont pas transmis à ce nouveau suivi. Tous les rejets sont comptés ; les entrées répétées du journal sont limitées à une par **30 secondes**, avec une entrée immédiate si le package ou le motif change. Les erreurs d'envoi répétées suivent la même limite ; une erreur après une reprise réussie est immédiatement visible.

La capture RAW existante reste un choix distinct, désactivé par défaut. Les grandes icônes ne sont pas chargées ni enregistrées par le chemin normal des notifications.

## Collecter les icônes Maps réelles

Dans une version contenant ces changements :

1. Lancer un itinéraire dans Maps officiel, ReVanced ou Morphe, avec l'accès aux notifications DashCast accordé.
2. Ouvrir **Diag → Exporter les icônes Maps**, puis confirmer **Capturer**. Il s'agit d'un instantané ponctuel des notifications actuellement actives.
3. Le ZIP est enregistré localement. Choisir **Envoyer** pour le sujet support HUD, utiliser le partage système, ou le conserver sur l'appareil. La capture et l'envoi sont deux choix distincts ; l'envoi respecte aussi le consentement support existant.
4. Répéter pour plusieurs pictogrammes et indiquer le pictogramme Maps réellement affiché lors de chaque capture. Le manifeste ne devine pas de libellé de manœuvre.

La nouvelle interface de capture est traduite dans les **13 langues** de l'application. Les journaux et noms de champs techniques restent en anglais.

L'archive **`hud_navicons_<date>_<suffixe>.zip`** contient un manifeste JSON et les PNG disponibles. Le manifeste conserve heure de capture, version DashCast, API Android/build, package source, heure de publication, longueurs des textes, type d'icône et dimensions exportées. Aucun texte de notification ni clé de notification n'y est copié.

La sélection est limitée aux **quatre notifications Maps éligibles les plus récentes** ; Waze, les applications inconnues et les notifications sans catégorie navigation ni statut ongoing sont exclues. Chaque image conserve ses proportions, avec une dimension maximale de **256 pixels**. Les bitmaps de la notification ne sont pas recyclés par l'export. Les icônes URI ne sont pas ouvertes : leur type est signalé sans conserver leur chemin. Une grande icône absente, un type non pris en charge ou une erreur de chargement est indiqué explicitement dans le manifeste.

Le ZIP rejoint le stockage de rapports partageable et sa rétention existante ; les fichiers de travail sont supprimés. L'export ne demande aucune activation HUD/CAN/AutoContainer, n'émet pas de guidage et ne change pas le choix HUD/combiné. Le listener connecté n'est conservé que par une référence faible, invalidée à sa déconnexion ou destruction.

Cet export prépare le corpus indépendant prévu au lot 4. **La reconnaissance automatique des grandes icônes n'est pas encore implémentée** : les rapports texte et les trois archives de banc du 9 octobre ne contiennent pas les bitmaps nécessaires à sa calibration.

## Essai sur véhicule

Après installation de la prochaine version, lancer un premier itinéraire après redémarrage, **sans ouvrir Diag ni lancer le banc auparavant**. En cas de défaut, envoyer un rapport avant le banc puis un autre après la reprise. Les compteurs distinguent réception, décodage et tentative d'envoi ; les traces d'activation complètent le résultat du transport. Confirmer séparément ce qui est visible sur le combiné et au pare-brise.

Les exports d'icônes servent ensuite à traiter les notifications dont le motif est `no_maneuver`. Un banc réussi ne corrige pas leur décodage.

## Vérification du candidat

**854 tests / 169 suites** passent, sans échec, erreur ou test ignoré ; lint release : **0 anomalie** ; APK release compilé. Le graphe AST a été actualisé. Commande : `rtk proxy ./gradlew :app:testDebugUnitTest :app:lintRelease :app:assembleRelease --offline`.

Cette passe ajoute **14 tests** : un nouveau scénario de rapport Morphe incomplet, sept cas de compteurs et de journalisation bornée, six cas d'export d'icônes. Le cas Morphe décodé existant vérifie maintenant le diagnostic Maps et l'absence de confusion avec Waze. La reprise du listener vérifie également que la lecture diagnostique des notifications ne réémet pas de guidage. Les tests de PNG vérifient dimensions, lisibilité, conservation du bitmap source, absence de texte de route dans le manifeste, icône absente et URI non ouverte.

Ces résultats vérifient le candidat hors véhicule. Ils ne valident pas le rendu après un démarrage à froid ni la reconnaissance des icônes réelles à collecter. La publication reçoit **versionName 1.9.8-beta / versionCode 647** ; son APK signé et son mapping doivent provenir du même build du commit publié.
