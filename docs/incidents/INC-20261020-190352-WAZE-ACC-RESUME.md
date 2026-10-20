# Waze après ACC OFF — reprise périmée et pile OEM vide

Analyse du 21 octobre 2026 du [rapport Bugs Reports du 20 octobre à 19:04](https://t.me/c/3712642112/2/141).
DashCast **1.9.51-beta / 690**, Android **10 / API 29**, produit `DiLink3.0`,
firmware `6125f_1for2_USER_SIGN_SX361_202606100404_Q2700`.
Fingerprint : `BYD-AUTO/DiLink3.0/DiLink3.0:10/QKQ1.210910.001/eng.build.20260610.082733:user/release-keys`.

Le rapport brut reste hors dépôt dans `hud_reports/telegram_20261020_kiroha50_1904/`.
SHA-256 : `c43ddf906b6622e3cf0c857dcbb673dfa316c0a7e3bcc03009ec9a645023f7f2`.
Les références de lignes ci-dessous concernent ce fichier texte.

## Observations

| Heure voiture | Preuve | Conséquence |
| --- | --- | --- |
| 18:54:05–08 | `remove-task`, destruction de Waze, retrait de tâche 32 du stack 28, puis `am_kill … stop com.waze` (7894–7983) | Waze a été arrêté par Android pendant la séquence rapportée d'ACC OFF. |
| 19:03:03–05 | Session Waze mémorisée, marqueur restauré, puis `already on cluster — show mirror only` (11130–11191) | La reprise automatique ne lance aucune activité Waze. |
| 19:03:08 | Même raccourci au clic ; le poll détecte ensuite le processus absent (11199–11206) | Le cache était périmé. |
| 19:03:33 | Réactivation de la projection et recherche Waze sans tâche (11237–11374) | La connexion au display 1 fonctionne. |
| 19:03:36–42 | Stack 28, mode 3, zéro tâche ; `REMOVE-INEFFECTIVE`, puis NPE `ActivityStack.getBounds()` dans `onConfigurationChanged:663` (11376–11445) | La vraie relance est bloquée dans le framework BYD, y compris les secours côté application. |

Le dump final confirme la pile primaire vide sur le display 1, avec ancienne
activité Waze `t-1 f` (9473–9489). Cela établit son état au moment du rapport,
sans prouver toute la séquence de corruption interne du framework.

## Correctif DashCast

La sélection issue du boot et celle issue du cache passent par la même
localisation typée de tâche. Une tâche sur le display attendu autorise le
miroir ; une absence confirmée autorise un lancement ; une tâche sur un autre
écran est déplacée sans tuer sa navigation. Une réponse inconnue préserve la
session et permet une nouvelle vérification lors d'une sélection ultérieure.

Les réponses tardives sont filtrées par génération, service et display. Un
ancien résultat de poll ne peut plus effacer une relance plus récente. Le
succès du lancement daemon via `ClusterService.daemonLaunchSync` exige son
marqueur final de complétion puis la localisation de la tâche sur le display attendu. Un transcript intermédiaire
non vide ne suffit plus. Un placement inconnu ou sur un autre écran après
la cascade n'autorise pas une nouvelle relance destructive côté application.

Si le dernier lancement sans mode explicite reproduit le NPE connu et que la
localisation typée confirme l'absence de tâche, une récupération SX361 peut
être tentée sous les conditions détaillées ci-dessous. Si elle n'est pas
admissible ou échoue, le diagnostic reste explicite. Un placement devenu
inconnu après cette tentative interdit les secours destructifs côté application.
Le nettoyage OEM existant n'est pas élargi.

## REA : correction de source et analyse SX361

La première passe utilisait le mauvais framework : ses preuves restent dans
`analysis/clusterdebug-20261015/evidence/waze-acc-resume-20261021.json`.
Le carrier d'analyse conserve les DEX de `Dilink3/x2/framework/services.jar`
(SHA-256 `fbb7c49dc4c282fe41dda78f2751e9b8c4d38bcffa4070319b54cc5457992777`).
Ce carrier n'est ni installé ni exécuté.

- `ev_d0266cf902fd2661f99b9a0d73fc197d5a12adb2de7f126a9533dc433e8f7aeb` :
  `ActivityStack.onConfigurationChanged` contient un accès à
  `display.getSplitScreenPrimaryStack().getBounds()` lors du passage en mode 4.
- `ev_de0e20c346ebfc81e5dd11533ac627f68e69567978ded0b4e29c14bfec956943` :
  `ActivityDisplay.removeStacksInWindowingModes` délègue à `removeStack` ;
  il ne constitue pas une preuve d'une autre primitive de destruction effective.
- `ev_ad7c3646c2cc69bd32d2a321e96ad911d0f8a68250f381292284b26ef669d4b4` et
  `ev_55f079de34e00c40ae992431acae800de6ad9f616d2a4b827d411d904d7b59d9` :
  un lancement explicite en mode 3 peut réutiliser une primaire existante
  si l'activité accepte le split. C'est une piste de récupération, sans preuve
  d'efficacité sur le véhicule.

Le `services.vdex` associé au JAR x2 fait
144 582 octets, SHA-256 `bdb2cca3da1993b7cb9c2dfb32083733235fc1a7db942c9e066820155e3a6926`.
Celui des exports SX361 du 7 et du 15 octobre fait 144 695 octets, SHA-256
`13a5661158b533b1b079967c5ab794036577df55699b9497c24db6f8a8f8194f`.
Les checksums de localisation DEX diffèrent aussi. L'export du 15 octobre
(message 119) exclut `services.jar` et `services.odex` par plafond de taille.
Les branches REA x2 ne sont donc pas attribuées au framework SX361 exact.
Cela ne signifie pas que le bon JAR était absent : il était déjà disponible
dans `D:\BYD Extract\Dilink3-system\system\000139\services.jar`.

La nouvelle passe utilise ce JAR, SHA-256
`0a669bb75d40327258462175a874dc791524524ab59a0028f68e7aafe0bfa187`.
Ses CRC DEX `d04bf4fe` / `347cf229` correspondent au VDEX des exports SX361.
Le carrier existant `REA-DiLink3-P4/01_FRAMEWORK/carriers/analysis-only-services.jar.apk`
(SHA-256 `adea77294e2605bc5cd4d5d584a49948579dd3f9faac44f87d04add481098a12`)
contient les deux DEX byte-identiques, comparés à nouveau pendant cette passe.
La provenance du framework client est conservée dans
`REA-DiLink3-P4/01_FRAMEWORK/CARRIER_PROVENANCE.json`.
Les carriers sont artificiels, jamais installés ni exécutés.

Les nouvelles preuves sont exportées dans
`analysis/waze-acc-sx361-20261021-evidence.json`. Dans les records REA,
`subject.digest` identifie la cible ; `normalized_result.engine.artifact_sha256`
identifie le JAR du moteur JADX, pas le firmware.

- `ev_24b11d83609f0ce76521a8388dd6ef718a9777b646262b391d5aea1560633936` :
  le chemin du NPE existe aussi sur SX361 dans `ActivityStack.onConfigurationChanged`.
- `ev_430324bcc1bbf0f7465d76a3729a222bb5a1173ce3cbe3c79952c70d0192efb5` :
  `removeStacksInWindowingModes` délègue encore à `removeStack`. Son invocation
  ne prouve pas que la pile vide a disparu.
- `ev_556d79abf4fe1f91f2c1db2ea943cb4c9ca826e16365b073ca48969f4e16f47c`,
  `ev_00bf4e875b4392a1e52aa16a896a483af2a7e5bcdd912d7e9e742093c9f9a5f8` et
  `ev_6440a1cef1a456cdf491269935f6b5a6b74d361297da2078d4396730c4e8a9b7` :
  avec un display explicite, le framework parcourt ses piles enfants avant
  le chemin de création fondé sur le registre primaire. Un mode demandé 3
  peut réutiliser la primaire existante si l'activité supporte le split,
  même si le registre primaire est incohérent.
- `ev_ce8bf05454e5c930515aec6f52950c9b14568e2d051a71ad8c28992e86917fdf`,
  `ev_2d8291fe13f2285db0f47437cdb98b1428151331b8619d234ebc80d1b317321b` et
  `ev_0be45704ab4aac0a89f88e4bc0a5b049750fd431c668296de4c6e04805780e72` :
  vérifier les capacités natives multi-window/split et le `resizeMode` de
  l'activité exacte ; ne pas forcer une capacité absente.

Ces branches étayent une récupération bornée par réemploi. Elles ne prouvent
pas son efficacité après ACC OFF : les paramètres de lancement peuvent
modifier le mode demandé, et la relecture puis le lancement ne sont pas atomiques.
La réussite doit donc être confirmée sur le display et la pile d'origine,
sans poursuivre ensuite la cascade FREEFORM, déplacement ou redimensionnement.

## Candidat de récupération bornée

La récupération s'applique au chemin classique `ClusterService`, après le
NPE caractéristique du dernier lancement simple et une absence confirmée :

- API 29 et fingerprint SX361 exact ; utilisateur courant 0.
- Display fission actif, public, nommé `fission_bg_xdjaVirtualSurface`,
  propriétaire `com.xdja.containerservice` / UID 1000, mode effectif fullscreen.
- Capacités natives multi-window/split et `resizeMode` de l'activité résolue
  positifs ; aucun forçage de capacité ni contexte de contournement de permission.
- Primaire standard unique, tâches lisibles et absentes sur le display cible,
  aucune tâche de l'application ailleurs. Les métadonnées sont relues avant l'appel.
- Une seule commande de lancement en mode 3, utilisateur et display explicites,
  sans `-S` ni `force-stop` supplémentaire.
- Validation finale du package, de la tâche, du display et du stackId d'origine.
  Le retour shell seul ne suffit pas.

Après récupération vérifiée, la géométrie existante est conservée : pas de
cascade FREEFORM, de watchdog de déplacement ni d'application automatique des
rectangles sauvegardés. Les commandes manuelles de redimensionnement restent
distinctes de cette récupération automatique.

Une annulation par nom de package présente une course : elle peut arriver
avant l'enregistrement de la demande dans le daemon ou toucher une demande
suivante du même package. Le protocole 29 ajoute donc, en fin de transaction
`launchAndForce`, un jeton Binder de validité propre à la demande. Son callback
lit uniquement les états atomiques/volatils de cycle de vie, de sélection et
de display, sans attente UI ni nouvelle commande. Le daemon le vérifie après
les lectures et immédiatement avant la tentative. Une réponse morte, invalide
ou illisible refuse la récupération.

Sans jeton ou avec un daemon antérieur au protocole 29, cette récupération
reste désactivée ; le lancement historique reste disponible. Les appels
directs des layouts ne fournissent pas ce jeton et ne déclenchent pas cette voie.
Même avec ce contrôle, la dernière vérification et le lancement Android
restent deux opérations distinctes ; aucun caractère atomique n'est revendiqué.
Le callback Binder est synchrone et n'a pas de délai maximal garanti si le
processus applicatif est vivant mais bloqué. Le nombre d'opérations de
récupération est borné, pas la durée totale des appels Binder natifs.

Le marqueur `RECOVERED-PRIMARY` suivi de `FINISH` conserve la géométrie même
si le journal contient une ancienne erreur de nettoyage. Dans ce cas
contradictoire, le résultat reste `PLACEMENT_UNCONFIRMED` : pas de faux succès
ni de nouvelle relance destructive.

## Validation restante sur SX361

Validation locale indépendante : 59 tests ciblés / 11 suites, aucun échec,
erreur ou test ignoré ; builds debug et release avec R8 réussis ; lint release
sans erreur (20 avertissements hors fichiers modifiés) ; `git diff --check`
propre. Le graphe du projet a été actualisé après la dernière modification de code.

Véhicule stationné : cycles ACC OFF/ON courts et longs, Waze conservé puis
arrêté par Android, tâche sur un autre écran, échec OEM et retour au compteur
d'origine. Contrôler l'absence de relances destructives et la conservation des
indications OEM. Les tests JVM et les analyses statiques ne remplacent pas ces essais.
Pour tester les changements daemon, utiliser une nouvelle version de build ou
redémarrer le proxy dans ce contexte stationné : la réinstallation du même
versionCode peut réutiliser l'ancien processus et ne pas charger le nouveau code.
