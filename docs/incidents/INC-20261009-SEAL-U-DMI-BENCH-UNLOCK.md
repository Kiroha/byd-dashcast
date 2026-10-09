# SEAL U DM-i : guidage visible après le banc SendInfo2

Date du retour : 2026-10-09. Véhicule déclaré : **SEAL U DM-i, DiLink 3**, pré-release **1.9.7-beta / 646**, sorties **HUD + combiné** sélectionnées.

## Observation et limites

L'utilisateur rapporte un premier itinéraire sans affichage, puis des icônes visibles pendant le banc **SendInfo2 NaviInfo** de Diag. Un nouvel itinéraire affiche ensuite le guidage sur le combiné.

- La photo du banc, à **15:08**, montre une flèche droite et un nom de route de test DashCast sur le combiné. Le journal visible indique `SET_HUD_SWITCH=1`, `sendInfo(5,0)` puis des `sendInfo2(4, ...)` répétés.
- La photo de l'itinéraire, à **15:11**, montre dans Maps un rond-point avec première sortie et **80 m**, et sur le combiné un pictogramme de rond-point portant **1**. Ce pictogramme diffère des trois icônes du banc (tout droit, gauche, droite), ce qui confirme au moins une mise à jour de guidage après le banc.
- Les photos ne montrent pas le pare-brise et ne confirment donc pas l'affichage HUD. Elles ne montrent pas non plus les appels du premier itinéraire, une reprise après redémarrage ou la commande responsable du déblocage.

Ce retour est une preuve supplémentaire de rendu sur le combiné de ce véhicule. Il ne démontre pas à lui seul que le banc a ouvert un canal absent du chemin automatique.

## Comparaison des deux chemins

| Étape | Banc SendInfo2 | Guidage automatique avec HUD + combiné |
|---|---|---|
| Préparation du proxy | Connexion et listener CAN demandés à l'ouverture de Diag | Connexion et listener CAN entretenus par le keeper |
| Allumage du HUD | `SET_HUD_SWITCH=1`, systématique | Déjà demandé au début de la session lorsque le HUD est sélectionné |
| Activation AutoContainer | `sendInfo(5,0,"")`, systématique | Déjà présente ; jusqu'ici mémorisée par un simple booléen local |
| Contenu natif | `sendInfo2(4, NaviInfo)`, six envois par manœuvre de test | Même canal, contenu issu des notifications Maps/Waze effectivement décodées |
| Rendu | Observation physique | Le succès des appels ne confirme pas le rendu physique |

Références : [banc](../../app/src/main/java/com/byd/dashcast/hud/HudDiagActivity.kt), [controller](../../app/src/main/java/com/byd/dashcast/hud/HudController.kt), [sortie cluster](../../app/src/main/java/com/byd/dashcast/hud/ClusterNavPusher.kt).

Le banc peut refaire une initialisation défaillante ou perdue, préparer un proxy froid, ou modifier un état HUD persistant. En mode HUD + combiné, les deux commandes d'activation existent déjà dans le chemin automatique. Copier une nouvelle commande ou activer le HUD en mode cluster seul n'est pas justifié par ces photos.

## Faiblesses reproduites et correctif

Trois régressions ont été reproduites contre un Binder factice avant modification :

1. Après remplacement du proxy, le booléen local empêchait de renvoyer `sendInfo(5,0)` au nouveau proxy.
2. Après une erreur `sendInfo2`, l'activation restait mémorisée et le prochain contenu était envoyé sans réinitialisation du mode.
3. Lors d'un remplacement du proxy pendant un envoi, le contenu pouvait arriver au nouveau proxy sans activation par ce dernier.

Le cache suit maintenant l'identité du proxy. Une erreur d'envoi invalide l'activation ; le prochain guidage frais la redemande. Un changement de proxy pendant l'envoi autorise une seule réactivation et réémission du même instantané frais. Un quatrième test vérifie qu'une succession de remplacements ne crée pas une boucle et que la récupération reprend sur la prochaine trame fraîche.

Les trois cas de régression passent après correction. Les tests conservent **zéro appel CAN et zéro broadcast Amap en mode cluster seul**. Aucun changement de mapping, de préférence, de commande OEM ou de reconnaissance d'image n'est ajouté.

Validation complète : **840 tests / 167 suites**, aucun échec, erreur ou skip ; **0 anomalie au lint release** ; APK release compilé. Graphe AST actualisé. Ces résultats valident les appels et leur ordre hors véhicule, pas le rendu physique après un démarrage à froid.

Le journal des rapports enregistre désormais le résultat natif de `SET_HUD_SWITCH`, l'activation CAN, le résultat de `sendInfo(5,0)` et les erreurs/réinitialisations du canal cluster. Les entrées ne contiennent ni texte de notification ni coordonnées ; « demandé » ou « accepté » ne signifie pas « rendu ».

Ce correctif est inclus dans **1.9.8-beta / 647** et n'est pas dans la 1.9.7-beta publiée. Il couvre les défauts reproduits ; **leur rôle dans le déblocage de cette SEAL U reste à confirmer**. Une réinitialisation silencieuse du service OEM qui accepte les données sans les afficher et conserve le même proxy ne peut pas être détectée par ces seuls retours. [Notes de version et essai](../releases/1.9.8-beta.md).

## Analyse des cinq rapports du 9 octobre

Collecte ciblée dans **Bugs Reports (sujet 2)** et **Hud Reports (sujet 4)** du groupe Telegram utilisé par le projet. Les légendes des messages **98 et 99** contiennent explicitement `Telegram: @giino01` : leur attribution est maintenant vérifiée. Les trois archives HUD n'ont pas de champ auteur ; leur firmware, leur version et leur état technique concordent avec ces rapports et le retour photographique déclaré. Les deux premiers bancs utilisent notamment le même processus DashCast **18080** que le rapport 99. Le troisième utilise **6028** ; cela montre un autre processus, sans établir la cause du redémarrage ni un redémarrage du véhicule.

Les fichiers et métadonnées de provenance sont conservés hors dépôt dans `/home/ccarre/app_byd/hud_reports/telegram_20261009_giino01/`. Les anciennes copies des messages 98 et 99 ont été vérifiées par leur empreinte. Aucun texte de rue, destination ou compte personnel issu des journaux n'est reproduit ici.

| Message | Fichier | SHA-256 |
|---|---|---|
| 98 | `byd_bugreport_20261009_142755_d5b58f9d62ad46a5b7fefc66c216faca.txt` | `463f3062a0ca6d30de68e4fee734eccd59c5730e464f739b551edd74e56447bb` |
| 99 | `byd_bugreport_20261009_150415_74500d85f19c48f09f2d7964a7f1d1a1.txt` | `41a8d45fa1f3aee0aad42c1063724399a20266fe1591a57860f280a5a9a930b9` |
| 100 | `hud_sendinfo2bench_20261009_150858.zip` | `514c438002ec1d7efdca14c52e7fcdb712e60a20cf569590f76261ed229e241e` |
| 101 | `hud_sendinfo2bench_20261009_152309.zip` | `c2503f7cae48eca5adf5475d90984c61bdad2e257c0f38629017e48ac206267f` |
| 102 | `hud_sendinfo2bench_20261009_153147.zip` | `eda31bf40033fcd888fe8aff65979b0b3abee279f6695fd3dbc5df1c0ca26f16` |

Les cinq rapports indiquent **1.9.7-beta / 646**, Android 10 / DiLink 3 et le firmware **`6125f_1for2_USER_SIGN_SX326_202602032334_Q2700`**, différent du SX361 de la SEAL du pilote principal. Les heures ci-dessous sont celles de la voiture, cohérentes avec l'heure de Paris et les réceptions Telegram UTC + 2 h.

### Chronologie vérifiable

| Heure | Source | Observation |
|---|---|---|
| 14:15:19 | Rapport 98, journal L10199–10201 | Proxy shell prêt, UID 2000, PID 4374, protocole 25 ; reconnexion du keeper réussie |
| 14:20:21–14:27:18 | Rapport 98, événements L7580–8282 | **9 publications/mises à jour** de la notification Maps Morphe de catégorie `navigation`, canal `1_foreground_1` |
| 14:27:55 | Rapport 98 | Absence de flèche déclarée ; accès aux notifications accordé ; aucun `NAV PARSE` conservé |
| 14:45:10.258 | Rapport 99, événements L6772–6794 | Android arrête DashCast PID 3941 (`stop com.byd.dashcast`), prévoit la relance du listener, puis démarre PID 18080 pour ce service à 14:45:10.555 |
| 14:45:11.534 | Rapport 99, journal L10547–10549 | Le keeper récupère le **même proxy PID 4374** ; le proxy n'a pas besoin du banc pour être présent |
| 14:58:29–15:03:20 | Rapport 99, événements L8023–8532 | **126 mises à jour** de la notification Maps Morphe, toujours de catégorie `navigation` |
| 15:04:15 | Rapport 99 | Deuxième absence de flèche déclarée ; accès accordé ; aucun `NAV PARSE` conservé |
| 15:08:58 | Archive 100, `01_can_bench.txt` | Activation HUD, mode AutoContainer 5 et **18 trames NaviInfo** sans exception rapportée ; résultat utilisateur **YES — arrow on CLUSTER** |
| 15:11 | Photo fournie dans la conversation | Rond-point première sortie dans Maps et glyphe numéroté 1 sur le combiné, différent des icônes du banc |
| 15:22:57.648–649 | Archive 101, `03_logcat.txt` L182/L184 | **Notification Morphe reçue par DashCast, distance 100 m, manœuvre introuvable** ; `bigText` absent |
| 15:23:09 | Archive 101, `01_can_bench.txt` | Deuxième séquence de 18 trames ; résultat **YES — arrow on CLUSTER** |
| 15:31:47 | Archive 102, `01_can_bench.txt` et `03_logcat.txt` | Troisième séquence de 18 trames ; résultat **YES — arrow on CLUSTER**, dans le processus DashCast PID 6028 |

Les compteurs **9 et 126** portent sur des événements système de publication/mise à jour, pas sur 135 manœuvres distinctes ni sur 135 notifications effectivement reçues par le listener. Ils établissent que Maps Morphe publiait du guidage avant le banc ; la présence d'un simple processus Maps n'est plus la seule preuve disponible.

### 1. Le rendu natif sur le combiné fonctionne pendant les bancs

Chacune des trois archives contient la même séquence : `SET_HUD_SWITCH=1`, `sendInfo(5,0)`, puis six trames chacune pour les icônes natives **9, 2 et 3**, distances **300 à 100 m**, état de navigation **1**. Les trois confirmations désignent **le combiné**, même si l'en-tête historique dit « HUD arrow visible ». Elles ne valident pas le pare-brise ni chaque direction séparément.

Les passages conservés de logcat montrent le contrôle AutoContainer avec **callingUid = 2000** dans le service OEM PID **1910**. Ce même service est déjà présent dans les deux rapports précédant le banc. Les `ok` du banc signifient absence d'exception, car le banc utilise la méthode void et ne conserve pas le retour natif de l'activation. Le rendu déclaré et la première photo apportent la preuve physique supplémentaire.

### 2. Le décodage Maps reste une panne distincte, observée après le premier banc

L'archive 101 conserve une notification **`app.morphe.android.apps.maps`** à **15:22:57.648**, suivie une milliseconde plus tard de `no maneuver found in:`. Le titre contient **100 m**, le texte ne contient qu'un nom de voie, `bigText` est absent et `subText` contient un résumé d'itinéraire. La capture RAW est donc active à cet instant. La notification n'a pas été rejetée comme application inconnue : elle a atteint le parseur.

Le code demande une direction et une distance avant de produire `HudNavigationData`. Cette notification ne peut donc pas déclencher un envoi automatique. **Un réarmement AutoContainer ne résout pas ce cas.** La petite icône n'est pas nommée dans cette trace de rejet ; on ne peut pas lui attribuer `maps_2025` par analogie avec la SEAL SX361. Les archives ne contiennent ni bitmap ni capture de la notification ; elles ne prouvent pas non plus que `largeIcon` est présent ou exploitable sur cette version Maps.

Ce constat rejoint la limitation d'acquisition des manœuvres Maps déjà observée sur la SEAL SX361, tout en constituant une preuve indépendante sur SX326. La photo du rond-point prouve qu'une manœuvre différente du banc a été affichée après le premier essai ; elle ne démontre pas que toutes les notifications suivantes sont décodées.

### 3. Le diagnostic « NavSeen: no » est incomplet pour Maps Morphe

Les deux rapports choisissent **`HudNavApp: maps`**. Dans la 1.9.7-beta, `isNavPackage()` accepte Morphe pour le guidage, mais **`matchesNavKey("maps", pkg)` n'accepte encore que Maps officiel et ReVanced**. `navSeenSummary()` et `recentNavStatus()` excluent donc les timestamps Morphe enregistrés par le listener lorsqu'ils évaluent ce choix Maps.

Il s'agit d'un défaut réel du diagnostic et du contrôle du formulaire de rapport. Il **n'empêche pas lui-même l'envoi du guidage**, mais peut annoncer `NavSeen: no` alors qu'une notification Morphe a été reçue. Les en-têtes seuls ne permettent donc pas de conclure « aucun itinéraire lancé » ou « listener déconnecté ». Le code source a été vérifié contre le commit publié. Après cette analyse, le candidat suivant corrige le filtre et couvre Morphe « reçu mais non décodé » et « décodé » par deux cas contre le vrai listener, reproduits en échec avant correction. Le défaut reste présent dans la 1.9.7-beta publiée.

### 4. Les traces prouvent une relance du processus, pas une panne persistante du listener

L'arrêt de **14:45:10** n'est pas un crash Java identifié de DashCast : l'événement système porte le motif `stop com.byd.dashcast` et s'inscrit dans un retour depuis les applications récentes. Android relance le processus pour `MapNotificationListenerService` environ **297 ms** après l'arrêt. Le keeper démarre à 14:45:10.949 et retrouve le proxy à 14:45:11.534.

Cela justifie de vérifier les reprises en arrière-plan, mais ne prouve pas que `onListenerConnected()` a été confirmé ni que la relance aurait échoué. La 1.9.7-beta ne journalise pas cet état de connexion ; la supervision déjà préparée pour la prochaine version l'ajoute. À **15:22:57**, la réception RAW confirme que le listener fonctionne au moins à cet instant.

Un crash natif séparé de **`com.google.android.gms.unstable`** apparaît à **14:19:08** dans le premier rapport. Les publications de guidage Morphe commencent ensuite à 14:20:21. Rien dans ces captures ne relie ce crash au déblocage du combiné ou à un crash DashCast.

### Limites de collecte qui empêchent d'identifier la commande de déblocage

- Les fenêtres **main** des rapports de bug sont beaucoup plus courtes que leurs événements système : **14:27:19–14:27:56** pour 98 et **15:04:03–15:04:22** pour 99. Les dernières publications Morphe visibles dans les événements sont respectivement **14:27:18** et **15:03:20**, hors de ces fenêtres main. L'absence de messages du parseur dans logcat ne démontre donc pas qu'il n'a jamais reçu ces publications. Aucun `NAV PARSE` ne subsiste dans les journaux DashCast embarqués, mais les rejets sans capture RAW n'y sont pas inscrits.
- Les logcats des bancs couvrent seulement **15:08:44–15:08:58**, **15:22:55–15:23:09** et **15:31:27–15:31:47**. Ils ne couvrent pas la photo du guidage à **15:11**, ni les premières activations des bancs. Le troisième fichier porte aussi `[output truncated]`.
- Les fichiers `04_hud_state.txt` ne contiennent **aucun événement** pour `0x38B0001C`, `0x38B0000D` ou `0x42E00008` : ces identifiants ne figurent que dans l'en-tête explicatif. Les deux derniers sont plafonnés à 1 000 événements, avec **2 303** puis **1 087** événements supprimés. Le drain CAN est dominé par d'autres signaux ; il ne prouve pas que le HUD a changé d'état.
- Les lignes `Suppressing notification from package by user request` ne nomment pas Maps. Dans l'archive 102, le contexte proche concerne notamment YouTube Music Morphe. Elles ne démontrent pas un blocage des notifications Maps ; une notification Morphe est effectivement reçue dans l'archive 101.
- Il n'existe dans ces cinq fichiers aucune comparaison complète des **activations automatiques et de leurs retours** avant/après le banc. Le proxy et AutoContainer sont déjà présents avant celui-ci. Une réactivation de leur état natif reste plausible ; aucune commande précise n'est établie comme cause.

## Suite ciblée

Les preuves donnent trois axes distincts : **corriger le diagnostic Morphe**, **compléter l'acquisition des manœuvres Maps**, et **valider sur véhicule les reprises du listener et du canal natif déjà préparées**. Le correctif de cache AutoContainer reste utile pour les défauts reproduits, mais les rapports ne prouvent pas qu'un remplacement du proxy ou une exception d'envoi a causé le premier échec de @giino01.

Après installation d'une version contenant les reprises, vérifier un premier itinéraire après redémarrage **sans ouvrir Diag**, puis capturer le défaut avant de lancer le banc et le guidage fonctionnel après. Les rapports doivent conserver connexion du listener, nombre de notifications reçues/rejetées, motif de rejet sans texte de route, résultats d'activation et dernier résultat d'envoi. Une confirmation physique séparée HUD/combiné reste nécessaire. Le corpus autorisé des images de notification Maps est encore requis avant de calibrer la reconnaissance de grandes icônes.

**Préparation de la prochaine version :** le suivi reçoit maintenant des compteurs par étape et des journaux de rejet sans texte de route, même lorsque la capture RAW est désactivée. Les rapports de bug et les archives du banc les conservent ; le banc ajoute aussi le journal DashCast dans `06_navigation.txt`. Diag propose un export ponctuel, confirmé, des petites et grandes icônes Maps disponibles, limité à quatre notifications et 256 pixels par dimension. Ce nouvel outil est traduit dans les 13 langues et ne commande pas le canal OEM. La reconnaissance automatique reste à calibrer avec les images réelles. [Fonctionnement et essai](../openbyd-2.5/NAVIGATION_DIAGNOSTICS_AND_ICON_CAPTURE.md).
