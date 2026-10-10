# SX326 : compléter les icônes Maps à partir de l'APK fourni

Le [rapport du 10 octobre, message 110](https://t.me/c/4472712700/110)
confirme que le listener reçoit Maps et que les virages gauche/droite sont
décodés. Les autres images de manœuvre sont rejetées avant l'envoi OEM.
L'APK fourni permet de constituer les références sans demander une capture
en voiture pour chaque direction.

## Preuves et portée

- Rapport : `byd_bugreport_20261010_154452_cc694dd1223043fdb1df3c86246a5204.txt`,
  1 242 136 octets, SHA-256
  `8524e305e26ea86e3232d6563e73d53fb56c707bcc0f9ffcc9fc917566cd6591`.
- DashCast **1.9.10-beta / 649**, Android 10 / DiLink 3, firmware
  `6125f_1for2_USER_SIGN_SX326_202602032334_Q2700`.
- Lignes 1022–1030 : listener connecté, HUD + combiné sélectionnés,
  418 observations = 5 doublons + 75 décodages + 31 rejets sans guidage
  + 302 sans manœuvre + 5 sans distance. Les 75 tentatives sont acceptées
  par au moins une sortie, avec zéro indisponibilité ou erreur. Ce résultat
  de transport ne certifie pas chaque rendu physique.
- Le journal conserve 413 textes RAW distincts entre 15:28:00.743 et
  15:45:08.793, sans correspondance dans les 210 mots-clés de production et
  sans `bigText` non vide. Ne pas additionner les répétitions logcat. Ce
  journal et le compteur instantané ne sont pas des fenêtres identiques.
- Les neuf transitions `NAV PARSE` du journal sont des icônes internes
  1/2 provenant de `large_icon`. Les photos fournies montrent gauche/droite
  et l'absence de guidage du combiné pour deux formes de rond-point.

Les rapports bruts, leurs données de trajet et les sources décompilées
restent hors dépôt. L'analyse locale est dans
`/home/ccarre/app_byd/hud_reports/telegram_20261010_giino01/`.

## APK et acquisition des références

APK : `Google-Maps-Morphe-26.33.02.961351034-patches-1.40.0.apk`,
282 977 034 octets, SHA-256
`5fa52cfb6f6dfe10efb0b2961a1569d07efaa9615f2de8df1de80a8dd87e05e5`.
Package `app.morphe.android.apps.maps`, version `26.33.02.961351034` :
identiques à ceux du rapport. L'APK et le travail d'analyse restent dans
`/home/ccarre/app_byd/tiers_apk/Gmaps/`.

Les 64 drawables `maneuver_*` décrivent notamment les nouvelles turn cards.
Le chemin de notification effectivement retrouvé est différent :
`brgz` sélectionne `zao.g`, `zao` associe des SVG `R.raw.ic_*` et une
réflexion horizontale, `cbjj.a` appelle `brgj.a` pour rendre un bitmap carré,
et `brhk.c` appelle `fxl.n(bitmap)`. `jgb` transmet ensuite l'icône à
`Notification.Builder.setLargeIcon`. L'analyse JADX contient des erreurs
dans d'autres méthodes ; ce constat n'est pas une exécution de Maps sur ART.

Le rendu à 54 pixels de la flèche gauche du vecteur de l'APK et la capture
réelle du 9 octobre ont les mêmes dimensions et limites. Leurs masques au
seuil alpha 128 diffèrent d'un seul pixel. Cette comparaison relie le corpus
à une image réellement reçue ; elle ne valide pas encore les autres formes
sur le véhicule.

Le générateur [build_maps_corpus.py](../../tools/navigation/build_maps_corpus.py)
vérifie le hash de l'APK et la présence exacte de chaque SVG décodé dans son
ZIP. Le manifeste de tests conserve les hashes SVG/PNG, le chemin original
dans l'APK, la réflexion et le libellé attendu. CairoSVG 2.8.2 rasterise
indépendamment les 38 formes retenues. Trois pictogrammes inconnus ou de
transport constituent des exemples négatifs. Le registre propriétaire
OpenBYD n'est pas utilisé.

Pour reproduire, installer CairoSVG 2.8.2 et Pillow dans un environnement
Python isolé, décoder les ressources avec `rtk proxy apktool d -s`, puis :

```sh
rtk proxy python3 tools/navigation/build_maps_corpus.py \
  --apk /path/to/Google-Maps-Morphe-26.33.02.961351034-patches-1.40.0.apk \
  --decoded /path/to/apktool-output --repo /path/to/MyBYDApp
```

## Comportement du correctif

- Le matcher gauche/droite déjà validé est conservé. Le corpus ajoute tout
  droit, demi-tours, directions légères/aiguës, bifurcations, insertions,
  arrivées et formes de rond-point dans les deux sens de circulation.
- Le plan de couverture et le plan d'emphase sont comparés séparément :
  un cercle grisé ne doit pas effacer le trajet blanc qui permet de
  distinguer le sens du rond-point. L'identité de déduplication comprend
  les deux plans. Les tolérances spatiales et de confiance ne sont pas
  élargies ; le corpus modèle les rééchantillonnages et fonds uniformes.
- Des formes portant le même sens de guidage partagent un résultat :
  départ/insertion sans côté donnent tout droit ; bifurcation et virage
  léger donnent la direction légère correspondante.
- Les rond-points non numérotés utilisent les variantes internes existantes
  15/16/19 (sens antihoraire) ou 17/18/20 (horaire). Tout droit utilise
  19/20 ; les directions gauche/droite utilisent les variantes angulaires
  disponibles. L'entrée, la sortie générique et le demi-tour dans un
  rond-point restent représentés par une variante générique. Le combiné
  reçoit `nextTurnIcon` 17/11 avec `roungAboutNum=0`.
  L'angle d'une flèche ne permet pas de connaître le numéro de sortie.
- Si la même notification nomme explicitement une sortie de 1 à 10,
  l'image apporte le sens de circulation et le texte le numéro. Cela
  corrige aussi le texte générique « 3e sortie », précédemment traité
  comme une sortie d'autoroute, et le sens par défaut du parseur textuel.
  Un numéro absent ou hors plage n'est ni inventé ni repris du dernier
  guidage. Les instructions concrètes gauche/droite/demi-tour conservent
  leur priorité.
- Les images inconnues, ambiguës, tronquées, colorées ou composites sont
  rejetées dans les cas couverts par les tests. Aucune nouvelle permission,
  capture continue, dépendance Maps à l'exécution ou modification du
  protocole Satellite n'est introduite.

## Validation et essai restant

Les tests utilisent des bitmaps indépendants du matcher, leurs tailles
rééchantillonnées, des fonds noirs/blancs et des cas négatifs. Les tests
du vrai listener, routeur, contrôleur et Binder d'enregistrement contrôlent
les codes natifs, distances, numéros de sortie et zéro commande CAN ou
broadcast Amap en mode combiné seul. Les scénarios existants gauche/droite,
déduplication, watchdog et changement de source restent couverts.

Vérifications locales du 10 octobre :

- `testDebugUnitTest` : **949 tests, 180 suites, aucun échec ni test ignoré**.
- `node --test tools/satellite/test_receiver.mjs` : **10 tests réussis**.
- `lintRelease` : **aucune erreur ni avertissement** dans le rapport XML.
- `assembleDebug` et `assembleRelease` : réussis.
- Contrôle de l'APK release par le scanner de publication de confiance :
  réussi (application non debuggable, signature habituelle, contrôles de
  contenu). Le numéro local reste `1.9.10-beta / 649` ; cet APK reconstruit
  n'est pas l'asset immuable déjà publié.
- `graphify update .` : graphe actualisé. Six avertissements de parsing
  concernent des fichiers déjà présents, extérieurs à ce correctif.

Le rendu des nouvelles manœuvres sur le combiné et le HUD nécessite encore
un trajet de validation. Ce correctif est inclus dans le build
[1.9.11-beta / 650](../releases/1.9.11-beta.md) ; ses notes précisent les
résultats du contrôle de publication et le SHA-256 de l'APK.
