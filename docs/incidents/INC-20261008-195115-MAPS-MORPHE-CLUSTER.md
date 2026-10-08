# SEAL SX361 — aucun guidage Maps sur le combine (2026-10-08)

La cause identifiee est un **rejet du package Maps avant le parsing**.
Le vehicule utilise `app.morphe.android.apps.maps`, alors que **DashCast
1.9.6-beta / build 645** ne reconnait que Google Maps standard, Maps ReVanced
et Waze. Les notifications de cette application ne peuvent donc pas atteindre
`HudController` ou `ClusterNavPusher` dans ce build.

Le pilote confirme n'avoir rien vu sur le combine de sa SEAL sans HUD.
Le rapport montre un guidage Maps actif et l'acces aux notifications accorde.
Il ne permet pas encore d'evaluer le rendu AutoContainer apres correction
de ce premier blocage.

## Rapport analyse

- Message Telegram 95 du topic de diagnostic, recu le **2026-10-08 a
  19:51:41 Europe/Paris / 17:51:41 UTC**.
- Fichier : `byd_bugreport_20261008_195115_b2c3628e724a4d94aa58b56925951c81.txt`.
- Taille : **1 109 096 octets**, **10 514 lignes**.
- SHA-256 : `a758cb99427245ec7a7c15da12af1e52b487022cb5a3bc618805c1f6967131ff`.
- Collecteur : **1.9.6-beta (645)**, Android 10 / API 29 / DiLink 3.0.

Le rapport et ses preuves brutes restent hors du depot. Les numeros de lignes
ci-dessous referencent ce fichier ; aucun texte de rue, destination, compte
Telegram ou inventaire complet des applications n'est recopie ici.

## Preuves du blocage

| Observation | Emplacement du rapport | Consequence |
|---|---|---|
| Acces aux notifications : `granted` | 1251-1252 | L'autorisation est presente au moment de la collecte |
| Package installe : `app.morphe.android.apps.maps`, version `26.33.02.961351034` | 9709, 9796-9799 | L'identifiant de Maps differe des deux variantes admises |
| `NavigationService` de ce package actif | 9994-9995 | Le guidage Maps est en cours |
| 24 entrees `notification_enqueue` pour ce package, `category=navigation` | Section LOGCAT EVENTS ; notamment 8257-8264 | Les notifications de navigation sont bien publiees |
| Quatre `MapNavListener: NAV UNSUPPORTED` dans le journal | 10467, 10492, 10494, 10509 | Le listener recoit des notifications de navigation exclues |
| Zero `NAV PARSE`, `nav update`, `cluster nav-mode` ou `ClusterNavPusher` dans le rapport | Recherche sur tout le fichier | Aucune preuve de guidage analyse ou d'activation du combine pendant cette capture |
| `AutoContainer` et `AutoContainerNative` dans `service list` | 9208, 9211 | Les services sont enregistres ; cela ne valide pas un envoi ou le rendu |
| `init.svc.FissionSvcProxyd=running` | 9214 | Le backend Fission releve est demarre |

Le marqueur `pkgHash` du journal est un HMAC propre a l'installation : il ne
permet pas de retrouver un nom de package a partir du hash. L'identite de
Maps est etablie par les sections packages, services et notifications du dump.
Le rejet de **ce package precis** est confirme independamment par le code
et par le test de reproduction.

Dans [MapNotificationListenerService.kt](../../app/src/main/java/com/byd/dashcast/hud/MapNotificationListenerService.kt),
`onNotificationPosted` appelle `isNavPackage` avant de lire le texte ou
l'icone. En 1.9.6-beta, cette fonction compare exclusivement :

- `com.google.android.apps.maps` ;
- `app.revanced.android.apps.maps` ;
- `com.waze`.

Une notification Morphe est donc rejetee meme si elle contient une manoeuvre
et une distance valides. Ni le mapping des fleches, ni le transport cluster
ne sont atteints. La ligne `sharedUser=com.google.android.apps.maps` du dump
ne remplace pas le package reel transmis dans `StatusBarNotification`.

## Reglages pendant l'essai

Le journal releve ces sorties effectives :

| Heure du journal | HUD | Combine |
|---|---|---|
| 19:49:59.191 | ON | OFF |
| 19:49:59.757 | ON | ON |
| 19:50:00.775 | OFF | ON |
| 19:50:01.696 | ON | ON |
| 19:50:03.032 | OFF | OFF |
| 19:50:04.604 | ON | ON |

Le dernier choix observe est **les deux sorties**, et non cluster seul.
Le combine reste cependant selectionne apres 19:50:04 : cette configuration
n'explique pas le rejet du package. Pour les prochains essais sur la SEAL
sans HUD, conserver **general ON, HUD OFF, combine ON**. Les valeurs OFF/OFF
sont les sorties effectives et ne suffisent pas a distinguer un arret general
d'une deselection des deux destinations.

## Correctif local et reproduction

Ajouter **uniquement** `app.morphe.android.apps.maps` a la liste explicite
existante. La meme fonction couvre les nouvelles notifications, le rescan
a la reconnexion, la reprise d'une autre source et le retrait de la
notification. Le parsing, les trames `NaviInfo`, les controles de plateforme
et les preferences de destinations sont conserves.

Trois nouveaux cas verifient :

1. Une notification Morphe reconnue produit une activation AutoContainer,
   un `NaviInfo` fleche droite / 200 m, puis un effacement a son retrait,
   sans CAN ni broadcast Amap en mode cluster seul.
2. Une source Morphe reste eligible lors de la disparition d'une autre source.
3. Un package inconnu ressemblant a Morphe reste exclu.

**Avant correction :** 16 tests cibles executes, deux echecs attendus :
zero trame AutoContainer au lieu d'une pour Morphe, et aucune source Morphe
disponible lors de la reprise. **Apres correction :** les 16 tests passent.
Voir [NavigationOutputRoutingTest.kt](../../app/src/test/java/com/byd/dashcast/hud/NavigationOutputRoutingTest.kt)
et [MapNotificationSourceFailoverTest.kt](../../app/src/test/java/com/byd/dashcast/hud/MapNotificationSourceFailoverTest.kt).

Verification apres correction : **116 tests / 20 suites** HUD et CAN,
aucun echec, erreur ou skip ; **lint release : 0 issue**. Cette execution
est ciblee sur le guidage et les operations CAN, pas sur la suite applicative
complete. Aucune nouvelle chaine utilisateur ni traduction n'est ajoutee.

Le correctif est inclus dans **1.9.7-beta / build 646** et reste **absent de
l'APK 1.9.6-beta deja publie**. Les [notes et consignes d'essai](../releases/1.9.7-beta.md)
precisent les reglages du pilote et les limites de validation.

## Limites et validation suivante

Les notifications sont presentes, mais le rapport ne contient pas leur texte
brut ni une manoeuvre effectivement parsee. Les tests utilisent une
notification synthetique avec direction et distance reconnues : ils prouvent
le passage dans le pipeline et la separation HUD/cluster, pas le format
exact de toutes les notifications Maps 26.33 ni le rendu sur cette voiture.

Apres installation d'un build corrige, tester le demarrage, les changements
de manoeuvre/distance et la fin du guidage avec **HUD OFF / combine ON**.
Si le combine reste vide, analyser une nouvelle trace : d'abord `NAV PARSE`,
puis les retours d'activation et d'envoi AutoContainer. Une presence de
service ou un envoi accepte ne suffit pas a declarer le combine compatible.
