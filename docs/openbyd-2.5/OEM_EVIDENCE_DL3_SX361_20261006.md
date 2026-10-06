# Export OEM HUD — DiLink 3 / SX361 (2026-10-06)

L'export identifie un systeme **Android 10 / API 29, DiLink 3.0, SX361**.
Les packages `com.ts.car.someip.service` et `com.byd.naviauto` sont introuvables
sur ce systeme. Le pilote est une **BYD SEAL sans HUD** : sa cible immediate
est le guidage du **combine d'instruments via AutoContainer**, avec le
pilotage HUD desactive. La voie CAN/HUD reste destinee aux vehicules DL3
equipes d'un HUD.
Cet export ne permet pas de valider un profil SOME/IP.

## Provenance et integrite

- Archive : `byd_apk_20261006_062116.zip`, recue le 2026-10-06.
- Taille : 9 917 459 octets ; 39 fichiers, 21 354 803 octets decompresses.
- SHA-256 : `442953328140677e88b4c5733a13339f2abfb4bfd581d1c287b7a712671eba1d`.
- Collecteur : DashCast **1.9.5-beta / versionCode 644**. Le nouveau build est
  donc installe sur le vehicule et l'extraction fonctionne ; cela ne valide pas
  encore une installation OTA ulterieure.
- CRC du ZIP et des quatre APK : valides. Les signatures des quatre APK ont ete
  verifiees avec `apksigner`.

Les binaires OEM, le ZIP et les dumps bruts restent dans le dossier d'analyse
local, hors du depot. Seules les conclusions et empreintes sont documentees ici.

## Systeme cible

| Preuve | Valeur |
|---|---|
| Android / SDK | `10` / `29` |
| Fingerprint | `BYD-AUTO/DiLink3.0/DiLink3.0:10/QKQ1.210910.001/eng.build.20260610.082733:user/release-keys` |
| Firmware | `6125f_1for2_USER_SIGN_SX361_202606100404_Q2700` |
| `persist.sys.car.type` | `138` |
| `sys.car.protocol` | `CANFD` |
| `ro.vehicle.type` | `DiLink50_5.0UI` |

L'utilisateur confirme une BYD SEAL sans HUD, Android 10, DiLink 3.0 et cette
version de firmware. L'annee du vehicule n'est pas etablie. La chaine
`DiLink50_5.0UI` ne suffit pas a classer ce systeme en DL5/SOME/IP : elle coexiste
ici avec API 29, le fingerprint DL3 et l'absence des packages recepteurs.

## Recepteurs et fichiers disponibles

Dans `07_hud_someip_receiver.txt`, les deux commandes `pm path` renvoient
`[EMPTY_OUTPUT]`. Les deux dumps de package indiquent explicitement
`Unable to find package`. L'inventaire complet ne contient aucun des deux
packages. La liste des services SOME/IP en cours est vide. Ce sont des preuves
concordantes d'absence dans ce systeme inventorie, et non un refus de copie ou
un depassement de budget pour un APK present.

La recherche XML ne trouve pas de correspondance et signale l'absence du
repertoire `/system_ext/etc/permissions`. Cette sortie seule ne permettrait
pas de conclure sur les droits d'un service present sur un autre firmware.

Les quatre APK sont identiques, octet pour octet, aux fichiers du precedent
dump DL3 local. `ext.jar` est egalement identique. Les versions sont :

| APK | versionCode | versionName |
|---|---|---|
| `com.byd.clusterdebug` | `10601004` | `1.6.1.4.2511111851.b5f575c` |
| `com.byd.auto.permission` | `29` | `10` |
| `com.xdja.containerservice` | `29` | `10` |
| `com.example.amapservice` | `1` | `1.0` |

Empreinte de l'APK AmapService :
`f3147928df1400c97b6d59b8dc82f1cbd9dfc2bd95b6982f89671caaab829464`.
Un nouveau desassemblage de cet APK confirme l'enregistrement dynamique de
`AUTONAVI_STANDARD_BROADCAST_SEND`, puis le chemin `sendNavigateInfoToCAN`
vers `BYDAutoInstrumentDevice.set`. La table `TurnIdMapToCAN` contient les
29 valeurs deja analysees. Le recepteur Amap utilise par DashCast est donc bien
present. Ce recepteur ecrit aussi sur CAN : le mode cluster seul ne doit donc
pas le solliciter. La presence des APK n'etablit pas le rendu du combine sur
cette SEAL ; un essai physique reste necessaire. Cette voiture ne peut pas
valider un HUD qu'elle ne possede pas.

Le manifeste de collecte explique les autres manques : `framework.jar`
(28 Mo), `services.jar` (14 Mo), `services.odex` (30 Mo) et `libBydCluster.so`
(29 Mo) depassent le plafond par fichier. Ils ne sont pas declares absents.
Le framework DL3 plus ancien reste une reference distincte, pas une preuve du
code exact de ce firmware SX361.

## Defaut de l'export des executables

Neuf fichiers natifs sans extension sont inutilisables dans ce ZIP : `fission`,
`fission_cbox_disp_mgr`, `fission_cbox_prop_event`, `fission_corebox`,
`fission_ivi_reboot_timeout`, `fission_ps`, `fission_screencap`, `fissiond`,
`fissiontsrv`. Leurs tables de sections ELF pointent hors du fichier ; les
octets invalides en UTF-8 ont ete remplaces par `EF BF BD`. Par exemple,
`fissiond` contient 31 663 de ces sequences.

La cause est dans `HudCaptureSupport.zipDir` : l'extension vide appartient a
la liste des fichiers texte a anonymiser. Les octets ELF passent alors par
`readText()` puis `toByteArray()`. Le manifeste est ecrit avant cette conversion,
ce qui explique les tailles archivees superieures aux tailles annoncees.
Les APK et les bibliotheques `.so` suivent le chemin binaire et ne subissent
pas cette conversion. Les seize bibliotheques de cet export ont une table de
sections ELF comprise dans le fichier.

Un correctif local reconnait la signature ELF pour les fichiers sans extension
et les copie directement. Les textes sans extension restent anonymises, y
compris dans le dossier `native`, et un fichier explicitement nomme `.txt`
reste traite comme du texte meme s'il commence par cette signature.

Le test de conservation ELF32/ELF64 echoue avant correction et passe apres.
Un second test verifie le maintien de l'anonymisation des textes. Ce correctif
est inclus dans le candidat 1.9.6-beta / build 645, et non dans l'APK 1.9.5-beta. Les octets perdus dans ce
ZIP ne sont pas recuperables : un nouvel export depuis un build corrige sera
necessaire si l'analyse de ces executables est requise.

Verification locale du correctif : **798 tests / 163 suites**, aucun echec,
erreur ou skip ; **lint release : 0 issue**. `graphify update .` termine.

## Suite du plan HUD

1. Dans **1.9.6-beta / build 645**, choisir **Parametres ->
   Guidage de navigation : general ON, HUD OFF, combine ON**. Autoriser
   l'acces aux notifications et lancer un guidage Maps/Waze reconnu.
2. Verifier sur cette SEAL SX361 le debut/update/stop du combine, direction
   et distance, puis l'arret general et la reprise avec une notification
   fraiche. Le mode cluster seul utilise AutoContainer sans commande CAN ni
   broadcast Amap. Les modes HUD seul et les deux necessitent un autre
   vehicule equipe d'un HUD pour leur validation physique.
3. Pour SOME/IP, obtenir un export d'un vehicule possedant effectivement
   `com.ts.car.someip.service`. L'APK recepteur, ses ACL et son contrat serveur
   restent a etablir pour les profils UI7/CN D5/Launcher.
4. Integrer le correctif ZIP au prochain build avant de recollecter les
   executables natifs, si ces preuves deviennent necessaires.
