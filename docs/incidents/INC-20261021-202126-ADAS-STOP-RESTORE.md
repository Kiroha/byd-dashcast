# ADAS non restauré après Stop — fin native annoncée trop tôt

Rapport Telegram Bugs Reports de @kiroha50 du **21 octobre 2026 à 20:21**,
message 146, canal `-1004472712700`. DashCast **1.9.52-beta / 691**,
DiLink3.0, Android **10 / API 29**. Le rapport brut reste hors dépôt dans
`hud_reports/telegram_20261021_2021/` :
`byd_bugreport_20261021_202126_64b3a20b065e4e62a6f928a38ff8050e (1).txt`.
SHA-256 : `9407988de6dea45cdfe72ef3e7558592549cafc21a42f8d90a270e1de41c5bf8`.

## Chronologie observée

Les lignes concernent le rapport texte, heures de la voiture.

| Heure | Preuve | Interprétation |
| --- | --- | --- |
| 20:20:55.995–999 | `originCluster() cmd=31`, `restoreOriginCluster screenSize=31` (10791–10792) | Stop emprunte la restauration native avec taille/thème. |
| 20:20:58.014 | `beta restoreOriginCluster typed ok (2015ms)` (10793) | Le callback de succès part après 18 → 2 s → 0. |
| 20:20:58.019–029 | `stopProjectionNoAdb`, puis `Original cluster restored` (10794–10801) | La récupération ADAS est autorisée avant la dernière commande native. |
| 20:21:00.228 | Appel AutoContainer accepté sous UID 2000 (8393) | Compatible avec la commande 12 de récupération ; numéro non conservé par ce log. |
| 20:21:01.023–027 | Appel AutoContainer, puis `screenSize(cmd=31) sent in background` (8394, 10807) | La commande 31 est encore envoyée trois secondes après le callback. |
| 20:21:01.605–710 | Dernier appel AutoContainer et `restore delivery=true; rendered state unknown` (8395, 10808) | Compatible avec la commande 53 finale ; livraison logicielle, pas ACK de visibilité. |

Le rapport établit le chevauchement de la restauration native avec la récupération
ADAS. Il ne conserve pas les numéros de commande dans les cinq lignes AutoContainer,
ni une preuve que le natif a effectivement traité ou ignoré 53. Le constat de la
fenêtre absente vient du signalement utilisateur.

## Cause et preuve native

`AdbLocalClient.restoreOriginCluster` annonçait un succès juste après la commande 0,
puis attendait encore trois secondes avant 31 dans les chemins daemon et ADB.
`MainActivity.originCluster` ne préparait pas la récupération avant l'éviction,
contrairement à l'autre restauration. Le service préparait donc sa récupération
au callback prématuré : délai de tranquillité d'une seconde, 40 trames, 12,
attente de 1 200 ms, puis 53. La commande 31 tardive pouvait s'intercaler entre 12 et 53.

La preuve REA conservée pour SX361 dans
`tiers_apk/Electro/analysis-rea-20261019/native-parity-contract/clusterDebug.json`
et son export `.txt`, preuve
`ev_8fded3880b347307f2465603a590dea4e98173a5ce8dc26869825193102c7f33`,
décompile `BusinessUi1::clusterDebug` à `0x248090`, bibliothèque
`libBydDataSource.so`, SHA-256
`02dfc7f5aa046167a3d829c69ef8ee97fd301cf9081450b86366319566c10ae8`.
Le natif ignore les appels distants de moins d'une seconde et actualise son horodatage
même pour un appel ignoré. La commande 31 change `g_ro_clu_size` et appelle
`updateThemeRcc` ; elle ne représente pas un simple réglage cosmétique. La commande
53 force le type ADAS à zéro. L'écart de **582 ms** entre les deux derniers appels
AutoContainer rend plausible une commande 53 ignorée. Cette explication physique
reste une inférence ; le défaut d'ordonnancement côté DashCast est établi.

## Correctif

Le Stop capture son propriétaire et révoque le masquage avant toute éviction.
Le callback existant attend désormais la dernière commande de taille dans les deux
transports, puis ferme le transport ADB avant le succès. L'interface reste en cours
de restauration trois secondes supplémentaires ; la barrière existante bloque
les relances jusqu'à cette vraie fin. La séquence native et les trames/délais ADAS
restent identiques ; seule leur frontière de complétion est corrigée.

Un nouveau propriétaire invalide les anciennes écritures avant 18, 0 et la taille,
y compris une reprise de compatibilité ADB. Un propriétaire terminé reste autorisé
jusqu'à la création d'un remplaçant. Les callbacks UI vérifient aussi cette identité.
Erreur de taille ou interruption complète la récupération une seule fois, avant
les conditions de fin/destruction de l'Activity. Une erreur de callback ne provoque
ni autre callback ni répétition de la fermeture native. Le journal et la restauration
manuelle sont conservés. La réponse finale ADB exige un code de sortie réussi et
la validation existante des erreurs textuelles et du statut Parcel.

## Validation locale

Validation indépendante finale : **75 tests / 10 suites**, aucun échec, erreur ou
test ignoré. Builds debug et release avec R8 et bootstrap natif réussis ; lint
sans erreur, avec 20 avertissements existants hors fichiers modifiés.
`git diff --check` propre ; graphe du projet actualisé.

Les tests du vrai helper avec un daemon simulé couvrent la commande finale bloquée,
l'échec final, l'interruption, le remplacement du propriétaire à chaque délai,
l'annulation avant fermeture et l'exception du callback. Les tests existants couvrent
la récupération coalescée, son délai de tranquillité et le rejet d'erreur Parcel.
Le transport ADB réel et la visibilité physique ne sont pas simulés par ces tests.

## Limites de validation

La vérification de propriétaire puis l'appel Binder/ADB ne sont pas atomiques : une
opération déjà admise peut terminer après remplacement. Aucun test hôte ne prouve
la visibilité du panneau ni tous les effets MCU du firmware. Aucun appel véhicule
n'a été exécuté pour ce correctif. Un essai à l'arrêt reste nécessaire : masquer,
arrêter la projection avec taille 31, vérifier le retour ADAS dans ses conditions
OEM normales, puis répéter après restauration manuelle et avec Stop rapprochés.
