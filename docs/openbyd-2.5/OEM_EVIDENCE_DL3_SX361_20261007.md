# Export OEM HUD — DiLink 3 / SX361 (2026-10-07)

Le nouvel export confirme **DashCast 1.9.6-beta / build 645** sur la SEAL
Android 10 / DiLink 3.0 deja identifiee. Le correctif de collecte est verifie
sur ce ZIP : les neuf executables precedemment corrompus sont maintenant des
ELF64 AArch64 analysables. Les quatre APK OEM, les seize bibliotheques natives
et les deux fichiers de framework sont identiques a ceux du
[6 octobre](OEM_EVIDENCE_DL3_SX361_20261006.md).

La cible reste le **guidage du combine via AutoContainer, HUD desactive**.
Cet export ne contient toujours aucun recepteur SOME/IP et ne prouve pas le
rendu du guidage sur le vehicule.

## Provenance et controles

- Archive : `byd_apk_20261007_192600.zip`.
- Reception Telegram : topic de diagnostic, message 94, le 2026-10-07 a
  17:29:15 UTC / 19:29:15 Europe/Paris.
- Taille : **9 928 175 octets** ; **39 fichiers**, **21 272 233 octets**
  decompresses.
- SHA-256 : `e1e67105d34eb763cf12e4757a0e87789ff1b1294204e168ad60142ab12a39ca`.
- Entete : `DashCast 1.9.6-beta (645) — BYD AUTO BYD AUTO DiLink3.0 API 29`.
- CRC du ZIP et des quatre APK : valides ; signatures des quatre APK
  verifiees avec `apksigner`, identites avec `aapt dump badging`.
- Les neuf executables passent la verification des tables, segments et
  sections ELF ; `readelf -h/-l/-d/-Ws` les lit sans erreur ni avertissement.
  Aucun executable OEM n'a ete lance.

Le collecteur 645 est installe et fonctionne. L'export ne permet pas de
determiner si l'installation a ete manuelle ou OTA. Les archives, binaires,
dumps et rapports techniques bruts restent hors du depot.

## Systeme et recepteurs

Les valeurs suivantes sont relues dans les fichiers de ce nouvel export,
et concordent avec l'export precedent :

| Preuve | Valeur |
|---|---|
| Android / SDK | `10` / `29` |
| Fingerprint | `BYD-AUTO/DiLink3.0/DiLink3.0:10/QKQ1.210910.001/eng.build.20260610.082733:user/release-keys` |
| Firmware | `6125f_1for2_USER_SIGN_SX361_202606100404_Q2700` |
| `persist.sys.car.type` | `138` |
| `sys.car.protocol` | `CANFD` |
| `ro.vehicle.type` | `DiLink50_5.0UI` |

`com.ts.car.someip.service` et `com.byd.naviauto` sont absents de
l'inventaire. Leurs commandes `pm path` retournent `[EMPTY_OUTPUT]`, et leurs
dumps indiquent `Unable to find package`. La propriete `DiLink50_5.0UI` ne
justifie donc toujours pas une activation SOME/IP sur ce systeme API29.

`03_native_backend.txt` releve `init.svc.FissionSvcProxyd=running`, un
processus `fission_service[ivi]` et `com.xdja.containerservice` en cours.
Ces observations etablissent la presence des services ; elles ne constituent
pas un acquittement d'une trame de navigation ni une preuve du rendu.

## Comparaison des APK et du framework

Les empreintes des quatre APK sont identiques a celles du 6 octobre :

| APK | versionCode / versionName | SHA-256 |
|---|---|---|
| `com.byd.auto.permission` | `29` / `10` | `6e7121be1344665fae6c33705ae3568b56684774455c86955d322900ec6d5dee` |
| `com.byd.clusterdebug` | `10601004` / `1.6.1.4.2511111851.b5f575c` | `27f1892efeda90358bb8653cd03b42ea4a23a46198fa8687dd5c6e283376b7d1` |
| `com.example.amapservice` | `1` / `1.0` | `f3147928df1400c97b6d59b8dc82f1cbd9dfc2bd95b6982f89671caaab829464` |
| `com.xdja.containerservice` | `29` / `10` | `1b9eef499db47d19a847b77aeaebf823f47449e9d0bc047793aa4433da9a0be9` |

`ext.jar` et `services.vdex` sont egalement identiques. L'analyse precedente
d'AmapService reste applicable : son recepteur broadcast ecrit sur CAN.
Le mode cluster seul de DashCast conserve donc la voie directe
`sendInfo(5, 0, "")` puis `sendInfo2(4, NaviInfo)` ; il ne sollicite pas ce
broadcast Amap ni la voie CAN/HUD.

Le manifeste de collecte est identique au precedent. Les gros fichiers,
notamment `libBydCluster.so`, `cluster_theme1.rcc`, `cluster_theme2.rcc`,
`framework.jar` et `services.jar`, restent exclus par le plafond par fichier.
Ces exclusions ne signalent pas une absence sur le vehicule.

## Executables natifs recuperes

Les tailles archivees correspondent maintenant aux tailles annoncees dans
le manifeste. Chaque fichier est ELF64, little-endian, AArch64 ; toutes ses
plages de donnees de segments et de sections sont dans les bornes du fichier.
Aucune sequence de remplacement UTF-8 `EF BF BD` n'est presente.

| Executable | Octets | SHA-256 |
|---|---:|---|
| `fission` | 33 248 | `a765568f9411b39cc719f90b6803d9144718e81f491c73b8e255f5cc512fdb0f` |
| `fission_cbox_disp_mgr` | 12 056 | `982eb420935781bbefa23a86e3724b51c795b0818295b988d7bcd26f02cab73b` |
| `fission_cbox_prop_event` | 12 056 | `982eb420935781bbefa23a86e3724b51c795b0818295b988d7bcd26f02cab73b` |
| `fission_corebox` | 12 056 | `982eb420935781bbefa23a86e3724b51c795b0818295b988d7bcd26f02cab73b` |
| `fission_ivi_reboot_timeout` | 11 584 | `9e384febfd28e803c155ff2db06a33fb32fba73164362350c740b5a76abf3fe3` |
| `fission_ps` | 11 560 | `499bc503e48234c2c66602557aeec6b74f3c0b2ea7167dfb89e5e9473d0f2f9b` |
| `fission_screencap` | 16 216 | `6f9a268b32e6d6ea573000387d7c9da587b4e63c75db9e6e1297c04152996c79` |
| `fissiond` | 125 088 | `31d39dfc63ffdae993ae5003fc6d392409a1a4c482609cb36f2fcbd8343fd93e` |
| `fissiontsrv` | 11 712 | `73b5fcedf53a4fd41f611df71bfde385f1e217658090d66c922a65f1557c9218` |

Les trois petits executables `fission_cbox_disp_mgr`,
`fission_cbox_prop_event` et `fission_corebox` sont identiques entre eux.
Leurs chaines de caracteres decrivent des commandes de proprietes,
d'evenements et d'alimentation d'affichage ; les imports incluent
`SurfaceComposerClient::setDisplayPowerMode` et les fonctions d'evenements
Fission. Cette inspection ne fournit pas un nouveau contrat de guidage HUD
ou de contenu `NaviInfo`.

Une piste de preuve visuelle devient analysable : l'aide embarquee de
`fission_screencap` decrit `-d` comme une selection **0=IVI, 1=cluster** et
`-p` comme la sortie PNG. Son import `ComposerService::setFiexedSysId` est
present. C'est une indication statique, sans validation d'acces ni de capture
sur cette SEAL. Les identifiants de cette aide ne doivent pas etre repris
pour les autres outils : l'aide `fission_cbox_disp_mgr` indique au contraire
**cluster=0, IVI=1**. Aucune commande d'affichage, de propriete ou de
redemarrage n'a ete executee pour cette analyse.

## Prochaine validation pour la SEAL

1. Dans **1.9.6-beta / build 645**, choisir **Parametres -> Guidage de
   navigation : general ON, HUD OFF, combine ON** et autoriser l'acces aux
   notifications.
2. Lancer un guidage Maps/Waze reconnu, puis observer direction, distance,
   mises a jour et effacement du combine a la fin. La prochaine notification
   fraiche ouvre le choix selectionne ; changer les reglages ne rejoue pas
   une notification ancienne.
3. Si le combine reste vide, transmettre un bug report depuis Diag pendant
   ce guidage, avec le resultat observe. Comparer la reception de la
   notification et les retours AutoContainer avant de modifier le transport.

Le nouvel export valide la correction de collecte et la presence des
composants deja analyses. Il ne clot pas la validation physique du cluster ;
les modes HUD seul et les deux sorties necessitent un vehicule equipe d'un HUD.
