# SEAL SX361 : aucune flèche avec 1.9.8-beta

Analyse du 9 octobre 2026 des messages Telegram **105 à 108**, attribués au
pilote principal par sa déclaration dans la conversation. Les deux rapports
de bug portent également son identifiant Telegram. Véhicule : **SEAL sans
HUD pare-brise**, Android 10 / DiLink 3, firmware
`6125f_1for2_USER_SIGN_SX361_202606100404_Q2700`. Ne pas confondre avec la
SEAL U DM-i SX326 de l'autre testeur.

Les captures confirment **deux blocages distincts** : une manœuvre présente
dans la grande icône Maps mais ignorée par le parseur actuel, et un listener
non reconnecté après un arrêt du processus. Le banc affiche le guidage sur
le combiné, même lorsque ce listener reste déconnecté.

## Sources et intégrité

Les heures sont celles de Paris, UTC+2. Les fichiers originaux, les PNG et
`analysis.sanitized.json` sont conservés hors dépôt dans
`/home/ccarre/app_byd/hud_reports/telegram_20261009_seal_1802_1831/`.
Cette note ne contient aucun texte de rue, destination ou résumé de trajet.

| Message | Capture / réception | Fichier | Octets | SHA-256 |
|---|---|---|---:|---|
| [105](https://t.me/c/4472712700/105) | 18:02:13 / 18:02:25 | `hud_navicons_20261009_180213_6631929105976940713.zip` | 7 515 | `6b903c34e020a07f3beeb8131a481156fc64d29f52bc6040bfbf3606f7650dc1` |
| [106](https://t.me/c/4472712700/106) | 18:05:10 / 18:05:34 | `byd_bugreport_20261009_180510_8a76b82a1c6a4813ba798456bb228d94.txt` | 1 132 730 | `8e4b0945e462282a165e33a5e6bb53a5fc6e4614cb571a9f5c4eb31d6a4b233a` |
| [107](https://t.me/c/4472712700/107) | 18:30:56 / 18:31:17 | `byd_bugreport_20261009_183056_0454c9072cf64344abd2343d61d83779.txt` | 1 084 590 | `5c3913c3d79342896417370766a2afffabf152fd9c47fcb4d2912f4316e98349` |
| [108](https://t.me/c/4472712700/108) | 18:31:53 / 18:31:57 | `hud_sendinfo2bench_20261009_183153.zip` | 39 404 | `abd642d7f515c39c4c2cc143e445a261b6b3974e12dbd0f2ddb0d9ba9553fa06` |

Les quatre exports indiquent **1.9.8-beta / build 647**. Les rapports 106 et
107 donnent tous deux `granted` et les sorties effectives
`NavigationOutputs(hud=false, cluster=true)` : le pilote teste bien le
**combiné seul**. L'éligibilité du keeper confirme également qu'il a constaté
l'autorisation de son composant exact au dernier contrôle.

## 1. La grande icône contient une direction exploitable

L'archive 105 contient une notification active de
`app.morphe.android.apps.maps`, publiée à **18:01:27.293**, et deux images.
Le titre comporte quatre caractères, le texte 49, et `bigText` est vide.

| Champ | Type Android | Dimensions | Observation visuelle |
|---|---:|---|---|
| `smallIcon` | 2, ressource | 108 × 108 | Logo Maps multicolore ; ressource `maps_2025` |
| `largeIcon` | 1, bitmap | 54 × 54 | Flèche blanche de virage à gauche, sur fond transparent |

Le parseur publié lit le nom de ressource de `smallIcon`, puis cherche des
mots-clés dans le texte. **Il ne lit pas `largeIcon`.** Cette capture établit
la présence de l'image de manœuvre sur cette version Morphe ; les anciens
rapports texte ne permettaient que d'en proposer l'hypothèse.

Une seule flèche gauche ne constitue pas un corpus couvrant les autres
directions, les demi-tours ou les sorties de rond-point. Les codes natifs
SendInfo2 déjà cartographiés décrivent les icônes de sortie OEM et ne
remplacent pas la reconnaissance de ces images d'entrée Maps.

## 2. Première session : notifications reçues, aucune manœuvre décodée

Dans le rapport 106, lignes **1018 à 1026** :

```text
connected=true eligible=true retryLevel=0
currentOutputs=NavigationOutputs(hud=false, cluster=true)
observed=120 duplicate=3 parsed=0
rejected: no_guidance=5 no_maneuver=112
deliveryAttempts=0 acceptedAny=0 unavailable=0 errors=0
```

La connexion du listener est confirmée à **17:57:43.378**, ligne 10201.
Les 120 observations se répartissent exactement en trois doublons, cinq
états sans signal de guidage et 112 notifications sans manœuvre reconnue.
**Aucune tentative de guidage automatique n'atteint le contrôleur OEM.**
Il n'y a donc aucun échec d'envoi automatique à attribuer au canal cluster
dans cette session.

Le journal DashCast conserve **117** notifications RAW distinctes entre
**17:59:24.374 et 18:04:39.439**, sans mot-clé de manœuvre français ou anglais
recherché. Ne pas additionner les répétitions logcat à ce journal. Les
événements système conservés montrent séparément **82** publications de
catégorie `navigation` ; cette fenêtre partielle n'est pas un compteur de
notifications reçues par DashCast.

## 3. Seconde session : la reprise du listener n'aboutit pas

Le rapport 107 contient l'arrêt Android de DashCast PID 8746 à
**18:08:31.038**, motif `stop com.byd.dashcast`, ligne **6975**. De nombreuses
autres applications sont arrêtées au même moment. Ce fait est compatible
avec une transition du véhicule, mais ne permet pas d'en établir la cause
exacte ni d'identifier un crash DashCast.

Android redémarre DashCast PID 24184 pour `WelcomeActivity` à
**18:30:39.842**, ligne **8204**. Le keeper demande un rebind à
**18:30:40.883**, ligne **10106**. La capture du rapport indique ensuite :

```text
connected=false eligible=true retryLevel=1 lastRequestAgeMs=27066
observed=0 duplicate=0 parsed=0
deliveryAttempts=0
```

Les événements système conservent **101** publications Maps de catégorie
`navigation` entre 18:26:31.396 et 18:30:50.167. Trois se produisent après le
redémarrage de DashCast et sa première demande de reprise :
**18:30:44.836, 18:30:47.355 et 18:30:50.167**. Le zéro observation ne peut
donc pas être expliqué uniquement par l'absence d'un itinéraire publié.

Dans `06_navigation.txt` du banc 108, une deuxième demande apparaît à
**18:31:13.019**. Au moment de construire l'archive, le listener reste
`connected=false`, `retryLevel=2`, **41 110 ms** après cette demande, et les
compteurs restent à zéro. La reprise n'est pas confirmée pendant environ
73 secondes après la première demande. Les exports ne permettent pas
d'identifier le motif interne du refus ou de l'absence de binding côté
Android ; accélérer les mêmes appels sans connaître cet état ne constitue
pas un correctif démontré.

## 4. Le banc affiche bien des flèches sur le combiné

Le rapport 107 est collecté **avant** le banc 108. Le banc s'exécute de
**18:31:29.650 à 18:31:46.826 environ** selon le journal : activation
`SET_HUD_SWITCH=1`, activation AutoContainer `sendInfo(5,0)`, puis
**18 trames** `sendInfo2(4, NaviInfo)` pour tout droit, gauche et droite,
avec distances de 300 à 100 mètres.

`01_can_bench.txt` conserve la confirmation du pilote :
**`YES — arrow on CLUSTER`**. Le test sait donc afficher du contenu natif
sur cette SEAL. Les compteurs automatiques restent à zéro et le listener
reste déconnecté : le banc contourne l'acquisition Maps et ne prouve pas
qu'il l'a réparée. Il ne valide pas non plus le chemin automatique en mode
combiné seul, puisque son initialisation demande systématiquement le
commutateur HUD.

## Comparaison avec le demi-tour visible en 1.9.7

Les tags publiés `v1.9.7-beta` et `v1.9.8-beta` ont des contenus identiques
pour les tables de noms de ressources et de mots-clés, les résolveurs
d'icône par ressource et par texte, les critères de guidage complet et la
déduplication des notifications. Les deux versions ignorent `largeIcon` et
contiennent le mot-clé `faites demi-tour`.

L'[analyse de la session précédente](INC-20261009-134002-MAPS-MANEUVER-IMAGE.md)
établissait que la manœuvre de demi-tour était écrite dans le texte et
reconnue. Aucun texte de ce type ne figure dans les 117 notifications RAW
de la première session actuelle. La seconde session ne reçoit aucune
notification. Ces captures expliquent l'absence totale de guidage, mais
**ne prouvent pas la suppression du demi-tour par une modification du
parseur en 1.9.8**, ni l'absence de toute régression de reprise.

Le code satellite ajouté localement après la publication de 1.9.8 n'est
pas inclus dans son APK publié et ne peut pas être la cause de ces essais.

## Suite justifiée par ces preuves

1. Compléter l'acquisition de `largeIcon`, avec reconnaissance bornée et
   rejet des images inconnues ou ambiguës. Inclure l'identité de l'image
   dans la déduplication, car une nouvelle flèche peut arriver avec les
   mêmes textes. Calibrer avec des exports de directions connues ; la
   capture actuelle fournit une première référence gauche réelle.
2. Diagnostiquer puis corriger le binding Android après arrêt/reprise.
   Conserver son état système effectif, pas seulement le résultat d'une
   demande `requestRebind`. Pour isoler cette panne en voiture, après
   l'arrêt et à l'arrêt physique, réactiver l'accès aux notifications avec
   un itinéraire actif puis capturer un rapport **avant tout banc**.
   Une reconnexion ne suffira pas à reconnaître une flèche image seule.
3. Valider le premier guidage et sa reprise sans ouvrir Diag, puis gauche,
   droite, demi-tour, rond-points et effacement. Le banc de sortie déjà
   réussi ne nécessite pas un nouveau balayage des 29 codes OEM.

## Correctifs préparés après cette analyse

Le candidat local lit maintenant `largeIcon` sur le worker existant, avec
une limite de 256 pixels par dimension. Il compare un masque normalisé à
la flèche gauche de l'export 105 et à sa réflexion horizontale. La référence
est indépendante du registre propriétaire OpenBYD. Une variation de bord
d'un pixel permet le rééchantillonnage Android sans accepter une différence
de structure. Les images colorées, vides, ambiguës, trop grandes et les URI
sont rejetées ; les bitmaps originaux ne sont pas recyclés.

**Couverture actuelle de la reconnaissance d'image : cette forme de virage
gauche et sa forme symétrique droite.** La droite est validée contre une
réflexion dans les tests, pas une capture physique supplémentaire. Les
autres images nécessitent encore leurs références réelles ; la
reconnaissance textuelle et par ressource existante reste prioritaire,
notamment pour les demi-tours et les rond-points.

L'identité inclut l'image et les textes. Une nouvelle flèche avec les mêmes
textes est traitée ; un doublon effectivement livré entretient le guidage.
Une publication fraîche peut reprendre après expiration du watchdog.
Les générations invalident les décodages dépassés ou annulés. L'effacement,
le passage de Waze vers une notification Maps restante et l'annulation d'une
image retirée pendant que le worker est occupé sont couverts par les tests.
Le mode combiné seul conserve zéro commande CAN et zéro broadcast Amap.

Sur Android 10, après deux demandes de reprise non confirmées, le keeper
dispose d'un deuxième chemin, limité à une demande par cinq minutes : le
proxy shell **réaffirme l'autorisation exacte déjà accordée** via
`cmd notification allow_listener`. Il ne la désactive pas et vérifie à
nouveau sa présence, le composant exact et l'utilisateur propriétaire dans
la même commande shell. Une autorisation absente entraîne un refus. Le
worker vérifie également les sorties et la connexion juste avant l'appel.
Le bilan des rapports conserve le résultat de cette demande ; seul
`onListenerConnected()` confirme la connexion réelle.

Dans [AOSP Android 10, `requestBindListener`](https://android.googlesource.com/platform/frameworks/base/+/android10-release/services/core/java/com/android/server/notification/NotificationManagerService.java#3113)
appelle [`setComponentState`](https://android.googlesource.com/platform/frameworks/base/+/android10-release/services/core/java/com/android/server/notification/ManagedServices.java#707),
qui retourne immédiatement si l'état activé est inchangé. La réaffirmation
de l'approbation passe par `setPackageOrComponentEnabled`, qui demande un
rebinding même quand la valeur de l'approbation est identique. Cette
distinction motive le correctif ; son rôle exact dans l'implémentation OEM
de cette SEAL reste une inférence à valider en voiture. Une demande réussie
n'est jamais présentée comme la preuve d'une connexion ou d'un rendu.

Les cas de la capture gauche, du changement d'image sans changement de
texte et de la reprise supplémentaire ont été reproduits en échec avant
leur correction. Aucun nouveau réglage ou texte utilisateur n'est ajouté.
Les commentaires de code et le corps du commit sont en anglais. Aucun
correctif matériel déclaré et aucune nouvelle pré-release publiée ici.

Validation finale du candidat : **896 tests JVM dans 174 suites**, aucun
échec, erreur ou test ignoré ; **0 erreur et 0 avertissement au lint
release** ; compilation debug et optimisation release R8 réussies. Le
graphe AST du dépôt est actualisé. Le rendu physique des nouvelles images
et le binding OEM après arrêt/reprise nécessitent encore l'essai en voiture,
sans lancer le banc avant le premier guidage.
