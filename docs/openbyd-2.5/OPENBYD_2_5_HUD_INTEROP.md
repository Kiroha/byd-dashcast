# OpenBYD 2.5 : contrat HUD et integration DashCast

Analyse statique du 2026-09-12. Aucun vehicule connecte a `adb devices -l` ; aucun envoi au vehicule effectue.

## Provenance et notation

- Artefact : `/home/ccarre/app_byd/tiers_apk/OpenBYD2.5/OpenBYD 2.5.apk`.
- SHA-256 APK : `f96a7f0a5040acf89a2e8e3215514f8e1bab0a777ac3abe36c8baa9e0a3652b3`.
- `aapt2 dump badging` : `com.sr.openbyd`, versionName `1.0`, versionCode `1`, minSdk `29`, target/compileSdk `36`. Le nom de fichier "2.5" n'est pas la version du manifest.
- `apksigner verify --verbose --print-certs` : signature v2 valide, un signataire RSA 2048. SHA-256 certificat : `b456e46e5c243473406e16211a3f42639c7cf8cad57fae640d91aa46eed48ae8`. Aucune comparaison avec le certificat plateforme du vehicule n'a ete possible.
- `J/` signifie `/home/ccarre/app_byd/tiers_apk/OpenBYD2.5/jadx/sources/` ; `S/` signifie `/home/ccarre/app_byd/tiers_apk/OpenBYD2.5/smali/smali/`.
- `M` signifie `/home/ccarre/app_byd/tiers_apk/OpenBYD2.5/jadx/resources/AndroidManifest.xml`. `P/` abrege `J/com/sr/openbyd/` ; `D/` abrege `J/defpackage/`.
- Les references `fichier:ligne` sont les lignes physiques de la sortie JADX, pas les `.line` R8. Les exceptions sont explicitement marquees `S/`.
- **V** : verifie dans cet artefact ou le code DashCast. **H** : hypothese non validee sur le recepteur. **G** : connaissance Android generale, pas une observation vehicule.
- **T** : comportement reproduit dans le modele smali borne du banc, avec ses substitutions explicites ; ce n'est pas une observation sur ART. **O3** : source du framework DL3 local, a ne pas generaliser a tous les DiLink. `O3/` abrege `/home/ccarre/app_byd/tiers_apk/OpenBYD2.5/analysis/oem-dl3/`.

## Synthese en dix lignes

1. **V** Maps : `NotificationListenerService`, textes de notification et classification de `largeIcon` ; complement par accessibilite. [E1, E2, E4]
2. **V** Waze : textes/accessibilite et reconnaissance de la fleche par capture, pas par le listener de notifications. [E1, E3, E5]
3. **V** La source HUD est selectionnee par `hud_navigation_app`, defaut `GOOGLE_MAPS` ; ce n'est pas un arbitrage automatique entre tous les GPS actifs. [E3]
4. **V** Sortie CAN : proxy propre a OpenBYD, SDK `BYDAutoInstrumentDevice`, ecritures de FID puis appels `send*`. [E6, E7, E8]
5. **V** Le proxy est lance par ADB local et `app_process`, avec des JAR systeme et un contexte modifiant les controles de permission locaux. [E9]
6. **V** Autre sortie : Binder `ts.car.someip.sdk.ISomeIpServerInterface`, transactions 4/5/6, trois profils de trames. [E10, E11]
7. **V** Des broadcasts `AUTONAVI_STANDARD_BROADCAST_SEND` sont emis en complement, actives par defaut. Reception/effectivite non verifiees. [E6]
8. **V** DashCast possede deja la voie CAN essentielle ; aucun client SOME/IP correspondant n'a ete trouve dans son code principal. [D1, D2]
9. **V/O3** L'APK embarque des stubs ; un framework DL3 local a ensuite precise les validations et permissions du SDK, pas les droits du recepteur SOME/IP. [E12, E21]
10. Confiance forte sur les emissions client et les mappings recoupes ; compatibilite avec ton DiLink, droits serveur et affichage physique restent non testes.

## Verdicts sur les hypotheses

| Hypothese | Verdict dans cet APK | Preuve / limite |
|---|---|---|
| A1 Notifications | Trouvee pour Maps ; refutee pour Waze dans ce listener | `MapNotificationListenerService.onNotificationPosted`, `P/services/MapNotificationListenerService.java:73`, selection Maps/Yandex seulement |
| A2 Accessibilite | Trouvee pour Maps ET Waze | `BydAccessibilityService.handleGoogleMapsEvent` :266, `handleWazeEvent` :533 ; configuration XML, E3 |
| A3 AndroidX Car / service cluster AAOS | Non trouvee ; absente du flux reconstitue | Aucun `androidx.car.app`, `InstrumentClusterRenderingService` ou `CarInstrumentClusterManager` dans les recherches sur les sources ; flux E1-E8 |
| A4 Capture / reconnaissance / OCR | Capture et reconnaissance d'icones trouvees ; OCR non trouve | `WazeArrowCaptureService.captureArrow` :120 ; `D/qo1.java`, `a` ; `D/do0.java`, `b/c/d`. Aucune classe TextRecognizer/Tesseract/ML Kit trouvee |
| B1 SDK BYD | Trouvee | `CarControlImpl.sendSimpleGuidanceInfo`, :1765, et `setInstrumentFeatureValue`, :1955 |
| B2 Binder | Trouvee : Binder prive OpenBYD + service SOME/IP lie par `bindService` | `ICarControl` est INTERNE a OpenBYD. Contrat des appels SOME/IP ci-dessous ; Binder sous-jacent au SDK non accessible dans les stubs |
| B3 Broadcast constructeur | Trouvee comme sortie complementaire | `HudController.sendStandardAmapBroadcast`, :150 ; `sendBroadcast`, deux destinations explicites plus diffusion implicite |
| B4 CarPropertyManager / VHAL direct | Non trouvee ; absente du flux reconstitue | Aucun `CarPropertyManager` / `VehiclePropertyIds` trouve ; ne prejuge pas de l'implementation interne du SDK OEM |
| B5 Provider / socket / serie / fichier MCU | Non trouvee comme sortie HUD dans le flux reconstitue | Les sorties sont E8/E10/E6. Le socket ADB sert au lancement du proxy, pas a transporter directement les manoeuvres au MCU |

Les recherches negatives portent sur l'ensemble de `J/`. Une absence de motif n'est pas une preuve d'absence de tout chemin cache/obfusque ; elle n'exclut pas non plus un transport different a l'interieur des JAR/services systeme absents. Les verdicts portent sur le flux effectivement reconstitue.

## Entree et modele

### Google Maps

**V** `MapNotificationListenerService.onNotificationPosted` n'accepte que la source configuree, avec `hud_integration_enabled=true`, et une notification `FLAG_ONGOING_EVENT` (`flags & 2`). Il lit `android.title`, `android.text`, `android.subText`, puis deduplique ces trois textes. Une modification de l'icone seule ne declenche donc pas ce traitement. [E1]

**V** La coroutine `MapNotificationListenerService$onNotificationPosted$1.invokeSuspend` extrait distance/rue/duree, charge `Notification.getLargeIcon()`, puis appelle `do0.c`, `do0.d(signature, n50.e)` et `n50.a(label)`. Les noms `maneuver_*` du tableau sont les etiquettes du registre de signatures OpenBYD, PAS une API publique Maps ni des resource IDs lus dans Maps. [E2, E13]

**V** L'accessibilite lit notamment les suffixes `:id/distance_text`, `top_cue_text`, `bottom_cue_text`, `navigation_time_remaining_label`, `next_step_instruction_container`. `GoogleMapsManager` combine ces valeurs avec la notification : fenetre de fraicheur de 5 s pour distance/rue, 30 s pour donnees de trajet/limite de vitesse. [E4]

### Waze

**V** `BydAccessibilityService.handleWazeEvent` traite les evenements 2048/32, avec un espacement minimal de lecture de 200 ms. Il lit sous `com.waze:id/` : `navBarDistance`, `navBarStreetLine`, `lblArrivalTime`, `lblTimeToDestination`, `lblDistanceToDestination`, `navBarDirectionText`. Les bounds de `navBarDirection` et `laneGuidanceView` delimitent la capture. [E3]

**V** `WazeArrowCaptureService.captureArrow` utilise `PixelCopy` sur le `SurfaceView` deja projete lorsque Waze est dans le cluster ; sinon `MediaProjection`, `VirtualDisplay` et `ImageReader` capturent l'ecran central. La boucle attend 2000 ms entre captures. Aucune extraction OCR du texte dans cette chaine. [E5]

**V** `do0.b/d` construit une signature binaire 15 x 15 : correspondance exacte, puis distance de Hamming <= 4, puis translations de +/-2 cellules avec seuil <= 18. Waze utilise un seuil d'image de 0.8 ; Maps passe par `do0.c` avec 1.0. `qo1.a/b` convertit le label reconnu en code HUD. Echec de reconnaissance Waze : repli `11` (tout droit), pas abstention. [E13, E14]

**V** `WazeManager.updateNavigationTexts` conserve le numero de sortie ; `sendHudUpdate` fusionne textes et classification. Expiration apres 40 s sans actualisation des textes, controlee chaque seconde, puis `clearNavigation` ferme le HUD et la capture. [E15]

### Donnees communes et chemin de sortie

`D/k70.java:7`, classe `k70`, constructeur et `toString` : nom original conserve dans le texte `HudNavigationData`.

| Champ R8 | Sens explicitement conserve | Type |
|---|---|---|
| `a`, `b`, `c` | `iconId`, `distanceMeters`, `roadName` | `int`, `Integer`, `String` |
| `d`, `e` | `remainingDistanceMeters`, `remainingTimeSeconds` | `Integer`, `Integer` |
| `f`, `o` | `secondaryRoadName`, `iconBitmap` | `String`, `Bitmap` |
| `g/h/i`, `j/k/l` | camera type/distance/state, safety type/distance/state | `Integer` |
| `p`, `q` | `speedLimit`, `currentSpeed` | `Integer` ; unite non imposee par ce modele |

**V** Les icone/distance secondaires sont constantes `-1` dans ce modele R8. La route effective est :

```text
Maps: onNotificationPosted -> coroutine.invokeSuspend -> GoogleMapsManager.updateFromNotification
      + BydAccessibilityService.handleGoogleMapsEvent -> GoogleMapsManager.updateNavigationTexts
Waze: handleWazeEvent -> WazeManager.updateNavigationTexts
      + captureArrow -> qo1.a/b -> WazeManager.processArrowAndLanes -> sendHudUpdate
Les deux: HudController.updateNavigation -> CanBydFidStrategy.updateNavigation
          -> ICarControl -> CarControlImpl -> SDK BYD
          -> SomeIpHudHelper -> profil SOME/IP -> binder.transact(6, ...)
          + HudController.sendStandardAmapBroadcast
```

Les branches ne sont pas toutes actives en meme temps : voir le routage ci-dessous. Les appels sont lus dans E1-E11/E15, pas deduits d'un simple chemin du graphe.

## Contrat CAN verifie cote client

### API exacte

```text
android.hardware.bydauto.instrument.BYDAutoInstrumentDevice
  static synchronized BYDAutoInstrumentDevice getInstance(android.content.Context)
  int sendAutoNaviStatus(int status)
  int sendSimpleGuidanceInfo(int iconId, int distanceMeters)
  int sendNextPathName(java.lang.String roadName)
  int sendRestRouteInfo(int restHour, int restMinute, long restMileage)

android.hardware.bydauto.AbsBYDAutoDevice
  int set(int[] featureIds, android.hardware.bydauto.BYDAutoEventValue value)

android.hardware.bydauto.BYDAutoEventValue
  public int intValue
  public int[] intArrayValue
  public byte[] bufferDataValue
  public double doubleValue
```

**V** Signatures : stubs E12 et appels reels E8. L'implementation embarquee de `getInstance` leve `RuntimeException("Stub!")` : ne pas l'integrer comme SDK fonctionnel. `CarControlImpl` obtient aussi `android.hardware.bydauto.statistic.BYDAutoStatisticDevice.getInstance(Context)` et, par reflexion, `android.hardware.bydauto.setting.BYDAutoSettingDevice.getInstance(Context)`, puis le meme `set(int[], BYDAutoEventValue)`. [E8]

### FID et valeurs emises

Les noms ci-dessous sont les symboles d'OpenBYD ; les numeros sont des FID SDK, pas des IDs de trames CAN directement utilisables sur un bus.

| Domaine / FID decimal | Hex | Valeur envoyee / unite | Reference |
|---|---|---|---|
| Instrument `1138753594` | `0x43E0003A` | navigation active `2`, stop `4` ; `621` egalement accepte comme actif a la lecture | E7 :76, E8 :1391 |
| Setting `1276174357` | `0x4C10E015` | `3` a l'activation, layout navigation | E8 :1391 |
| Statistic `1083203624` | `0x40906028` | `1` au start, `0` au stop | E8 :1391 |
| Statistic `1262485592` | `0x4B400058` | `1` au start, `0` au stop ; nom OpenBYD `STATISTICS_ISA_MAP_STATUS_SET` | E8 :1391 |
| Instrument `1139806224` | `0x43F01010` | code manoeuvre, `0` a l'effacement | E8 :1765 |
| Instrument `1139806256` | `0x43F01030` | OpenBYD y ecrit AUSSI le code seul ; `0` au stop ; ne pas reintegrer aveuglement dans DashCast | E8 :1765, D2 :50 |
| Instrument `1139806232` | `0x43F01018` | distance de manoeuvre, metres entiers ; `-1` au stop | E8 :1765 |
| Instrument `1140461576` | `0x43FA1008` | `bufferDataValue`, rue en **UTF-16LE**, sans BOM ; buffer vide au stop | E8 :1635, `D/bi.java:11` |
| Instrument `1139810344` | `0x43F02028` | metres restants, `long` converti en `int` pour le FID ; `-1` au stop | E8 :1669 |
| Instrument `1139810320`, `1139810328` | `0x43F02010`, `0x43F02018` | heures `0..254`, minutes `0..59` ; `0/0` au stop | E7 :225, E8 :1669 |
| Instrument `1139810334` | `0x43F0201E` | secondes restantes ecrites a `0` apres reduction en minutes | E8 :1669 |
| Instrument `1139839008` | `0x43F09020` | minute d'horloge d'arrivee `0..59`, calculee avec `Calendar` ; `0` au stop | E8 :1669 |
| Instrument `1139834896`, `1139834904` | valeurs secondaires | icone/distance secondaires ; `-1/-1` lors de leur effacement | E8 :1744 |

**V** Bornes publiees dans le stub `BYDAutoInstrumentDevice` : `DISTANCE_MAX=16777214`, `REST_HOURE_MAX=254`, `REST_MINUTE_MAX=59`, `REST_MILEAGE_MAX=4294967294L`. Ce sont des constantes d'API, pas des bornes confirmees sur ton MCU. Le controleur client refuse une distance de manoeuvre nulle au sens `null`, ou negative ; il ne borne pas sa valeur positive. Les donnees de trajet `k70.d` restent des `Integer`, donc la chaine d'entree n'exploite pas toute la plage unsigned du `long` SDK. [E12, E6 :434, E7 :225]

### Sequence, cadence et arret

1. **V** Start CAN : `turnOnNavi()` -> `sendAutoNaviStatus(2)` : FID Instrument statut, Setting layout, les deux Statistic ci-dessus, puis appel SDK `sendAutoNaviStatus(2)`. [E7, E8 :1391/:2109]
2. **V** Guidage : `set` du code sur `0x43F01010`, puis `0x43F01030`, puis distance sur `0x43F01018`, puis SDK `sendSimpleGuidanceInfo(icon, metres)`. La rue et le trajet ont chacun leur groupe de `set`, puis l'appel SDK correspondant. [E8 :1635/:1669/:1765]
3. **V** CAN fonctionne sur changements : deduplication icone/distance, rue, trajet et voies ; pas de keepalive CAN periodique independant retrouve ici. `ensureHudActive` relit le statut lorsqu'il se croit actif et reactive si le statut n'est ni `2` ni `621`. [E7 :76/:225 ; smali `S/com/sr/openbyd/services/strategies/CanBydFidStrategy.smali:996`]
4. **V** Stop : statut `4`, effacement des registres du tableau, nettoyage supplementaire des segments/ISA/voies, puis SDK `sendAutoNaviStatus(4)`. Le layout Setting `3` n'est pas restaure a une ancienne valeur dans cette methode. [E8 :1391]
5. **V** Nettoyage supplementaire visible : Statistic `1083203600/1083203608/1083203616/1083203632/754057256/754057272/754057276=0`, `754057264=0.0` ; Setting `1285554240/1262485674/1262485694/1285554200/1285554184=0`, Statistic `1262485532/1262485556=0`, et tableaux de voies remis a leurs sentinelles. Ne pas recopier ce nettoyage ISA sans valider son domaine sur le firmware cible. [E8 :1391]

**V** Les retours SDK sont des `int`. Attention : plusieurs methodes de `CarControlImpl` ajoutent inconditionnellement `RESULT_CODE:0` apres des sous-appels retournant un texte d'erreur. Ce message n'est ni une preuve d'acceptation de tous les FID ni un accuse de reception du HUD. [E8 :1635/:1669/:1765/:1955]

### Complement SDK DL3 reel

**Provenance distincte de l'APK OpenBYD :** `/home/ccarre/app_byd/new_apk_extract/Dilink3/x2/framework/framework.jar`, SHA-256 `f3e9c41680a210cb144351540c1374e5c92fe076117ddbb7d1ed16a4c0997d7d`. Le fichier voisin `00_header.txt:1` identifie `DiLink3.0 API 29`. Il contient trois DEX. `BYDAutoInstrumentDevice` et `AbsBYDAutoDevice` ont ete reextraits depuis ce JAR apres la reprise et sont identiques aux copies O3 utilisees ci-dessous. JADX signale une erreur globale pour l'extraction instrument, non attribuee par sa sortie concise ; aucun marqueur d'echec dans les methodes de navigation citees. Cela reste une lecture statique d'un firmware particulier. [E21]

| Methode O3 | Validation dans ce SDK, avant emission |
|---|---|
| `getInstance(Context)` | `enforceCallingOrSelfPermission("android.permission.BYDAUTO_INSTRUMENT_COMMON", null)` avant creation du singleton ; device type `1007` |
| `sendSimpleGuidanceInfo(int,int)` | `simpleType` dans `0..102`, distance dans `0..16777214` ; sinon `-2147482645` |
| `sendRestRouteInfo(int,int,long)` | heures `0..254`, minutes `0..59`, metres `0..4294967294` ; mileage caste en `int` pour l'envoi |
| `sendNextPathName(String)` | non-null ; `getBytes("UnicodeLittleUnmarked")`, longueur `<=255` **octets**, pas caracteres ; sinon `-2147482645` |
| `sendAutoNaviStatus(int)` | statut `0..4` ; sinon `-2147482645` |

**O3** Les methodes `send*` ci-dessus imposent `android.permission.BYDAUTO_INSTRUMENT_SET`. `getGetPermission()` retourne `android.permission.BYDAUTO_INSTRUMENT_GET`. Ces noms sont maintenant lus dans une implementation reelle, mais leurs `protectionLevel`, leur attribution effective et les droits du vehicule cible ne sont pas prouves par cette classe. DashCast declare deja `COMMON` et `GET` dans son manifest ; aucune permission n'a ete ajoutee pendant l'audit. [E21 ; `app/src/main/AndroidManifest.xml:84`]

**O3** Le chemin generique `AbsBYDAutoDevice.set(int[], BYDAutoEventValue)` verifie d'abord l'appartenance des FID a `BYDAutoDeviceFeaturesMap` pour le device, puis la permission Android, puis le type de valeur fourni. Son avertissement `no permission to use the feature ... with this device` correspond au controle de FID, pas necessairement a une signature refusee. Les methodes specialisees `send*` ont leurs propres validations et appellent les surcharges protegees `set(device, ...)` : elles ne sont pas interchangeables avec le chemin generique en termes de validation. [E22]

**O3** Transport suivi : `AbsBYDAutoDevice` -> `BYDAutoDeviceManager` -> `Context.getSystemService("auto")` -> `android.hardware.BYDAutoManager` -> `nativeSetInt` / `nativeSetIntArray` / `nativeSetBuffer`. Ce manager consulte aussi une strategie `PMS_AUTOAPIBLACK` par package et peut refuser avant l'appel natif. Aucun nom de service Binder ni code de transaction du backend JNI n'est deduit de ces methodes natives ; ce backend reste hors de cette preuve Java. [E22, E23]

**Ne pas confondre les espaces de codes.** Le SDK O3 declare `TURN_KIND_FRONT=1`, `RIGHT=3`, `LEFT=7`, `DEST=24`, alors qu'OpenBYD nomme/emet `LEFT=1`, `RIGHT=2`, `STRAIGHT=11`, `DESTINATION=48`. Le tableau Maps/Waze plus bas est donc un mapping vers les **valeurs emises par OpenBYD**, pas une certification des glyphes de tous les HUD BYD. Ni la plage numerique acceptee par le SDK ni le nom d'une constante ne prouve l'icone rendue. Preserver les mappings deja valides de DashCast et faire un controle visuel par profil avant tout changement. [E21 :795 ; E6 :49]

## Routage et contrat SOME/IP

### Selection observee

`HudController.getStrategy` choisit toujours `CanBydFidStrategy`, qui gere lui-meme les deux voies. [E6 :105, E7]

| `hud_protocol_mode` | CAN | SOME/IP |
|---|---|---|
| `CAN_BUS_ONLY` | oui | non |
| `SOMEIP_ONLY` | non | oui |
| `DUAL` | oui | oui |
| `AUTO` (defaut) | **oui** | seulement si detecteur = SOMEIP |

**V** Detecteur : Setting FID `951058453` (`0x38B00015`), secours via FID `0` dans le proxy ; valeur `2` -> SOME/IP. Si lecture impossible (`-1`), recherche `Di300VCP`, `Di150VCP` ou `Di100VCP` dans `ro.vehicle.type` + `ro.build.car.platform`. Sinon CAN. **H** C'est une heuristique OpenBYD, pas une table de compatibilite constructeur validee. [E16]

### Transport Binder exact

**V** Action `com.ts.car.someip.SomeIpServerService`, package `com.ts.car.someip.service`. Composant explicite utilise par CN D5, et en secours UI7 : `com.ts.car.someip.service/com.ts.car.someip.service.manager.SomeIpServerService`. `bindService(..., 1)` (`BIND_AUTO_CREATE`) utilise le contexte de l'app, pas `ICarControl`. [E10 :254 ; E11]

**V** Descripteur : `ts.car.someip.sdk.ISomeIpServerInterface`. Chaque requete commence par `Parcel.writeInterfaceToken(descriptor)`. [E10]

| Code de transaction | Charge Parcel APRES le token, dans l'ordre | Reponse |
|---|---|---|
| `4` | `writeLong(serviceId)` | `readException()` puis `readInt()` |
| `5` | `writeLong(serviceId)` | idem |
| `6` | `writeInt(1)`, `writeLong(topicId)`, `writeLong(0L)`, `writeInt(payload.size)`, `writeByteArray(payload)` | idem |

**V** Appels synchrones : `binder.transact(code, data, reply, 0)`. Le `writeInt(payload.size)` ET la longueur generee par `writeByteArray` sont bien tous deux presents. Le sens serveur du `1` et du `0L` n'est pas nomme dans l'artefact. Les valeurs locales `-100` (pas de binder), `-200` (exception) sont produites par le helper, pas par le protocole OEM. L'interface AIDL COMPLETE, notamment les transactions non utilisees, n'est pas embarquee ici. [E10 :506/:1109/:1156]

Recoupement independant de la reconstruction Java : `S/com/sr/openbyd/services/strategies/SomeIpHudHelper.smali:2868` (`fireEvent`), :6407 (`startSomeIpService`) et :6770 (`stopSomeIpService`) confirment le token, l'ordre des champs, les codes et les lectures de reponse. Cela valide la forme des appels client, pas leur acceptation serveur.

### Profils et payloads

**V** `someip_hud_version` choisit `LAUNCHER_MAP_CN` (defaut), `ALTERNATIVE_UI7` ou `ALTERNATIVE_CN_D5`. Ces noms sont ceux des preferences OpenBYD, pas une identification garantie de ton firmware. Enum verifie dans `D/db1.java:5`, selection dans E10 :563.

Les payloads utilisent le wire format protobuf : entier varint (wire type 0), `double` IEEE754 little-endian (type 1), bytes/UTF-8 (type 2). Chaque message ci-dessous est enveloppe dans un champ externe `1` length-delimited : `0A <varint taille> <message>`. Aucun `.proto` OEM n'est fourni ; les numeros/types ci-dessous viennent des ecritures du client. [E10 :1225 et suivantes]

**ALTERNATIVE_UI7** : service ID `0x000B010A00010000` (`3097367205183488`), topic `0x0004010A00018001` (`1127042368241665`). [E11, `AlternativeUi7Strategy` :24/:119]

| Champ interne | Type | Valeur UI7 observee |
|---|---|---|
| `2` | varint | compteur modulo 256, initialement 0 |
| `8` | bytes | PNG manoeuvre ; taille par defaut 128 px |
| `9`, `10` | varint, UTF-8 | metres avant manoeuvre ; rue tronquee a 200 caracteres Java (`D/de1.java:267`, `S`) |
| `16` | varint | `2` |
| `19`, `20` | double | longitude, latitude mises en cache |
| `26` | UTF-8, facultatif | ETA `HH:mm` locale, duree arrondie a la minute inferieure |
| `28` | varint | gauche `3`, droite `2`, demi-tour gauche `9`, droit `10`, sinon `1` ; details ci-dessous |
| `30` | UTF-8 | liste JSON de dix points `[longitude,latitude,0]` calculee par `guideLine` |
| `31` | UTF-8 | `longitude,latitude,0` courants |
| `5`, `29` | varint, UTF-8 | si voies disponibles : nombre ; paires `back,front|` ; sentinelles de front converties a `0` |

**V** `mapToF28` : `{1,3,4,7,15,16}->3`, `{2,5,6,8,17,18}->2`, `9->9`, `10->10`, reste `1`. `guideLine` part de la position et du cap caches, avance par pas de 22 m avec approximation de 111000 m/degre ; a partir de l'iteration 5, rotation de -15 degres pour `{1,3,4,7,9}`, +15 pour `{2,5,6,8,10}`. Il s'agit de points synthetiques, pas de la geometrie de route extraite du GPS. [E10 :615/:1056]

**ALTERNATIVE_CN_D5** : meme topic principal et service UI7, plus les six services derives des topics Launcher ci-dessous. La charge est differente : [E11, `AlternativeCnD5Strategy` :24/:38/:263]

| Champs | Types / valeurs CN D5 observees |
|---|---|
| `1/2/3/4/5` | varints : `0`, compteur, metres restants, secondes restantes, nombre de voies |
| `6/7/8` | varint `6` si panneau de limitation PNG, sinon `1` ; bytes panneau/voies ; bytes PNG manoeuvre, defaut 34 px |
| `9/10/11` | metres avant manoeuvre / rue UTF-8 (200 caracteres) / limite de vitesse |
| `12/13/14/15/16` | vitesse courante / `0` / `0` / limite / `2`, varints |
| `17/18/19/20/21/22/23` | type camera / distance camera / longitude double / latitude double / vitesse courante / `0` / type safety |
| `24/25/26/27/28/29` | UTF-8 `[]` / `[]` / ETA `HH:mm` / vide ; varint manoeuvre remappee ; UTF-8 voies simplifiees |
| `30/31/32/33` | guideline synthetique UTF-8 / point de guidage UTF-8 / cap double / progression double |

**V** `mapToBydHudManeuverId` conserve `0,1,2,7,8,9,10,45..49`, ramene `{3,4,14}->3`, `{5,6,13}->5`, et `{11,12,15..44}` a `11`. Le detail des ronds-points n'est donc pas preserve par ce champ CN D5. PNG charges par les helpers BYD ; leur disponibilite effective sur ton systeme n'est pas verifiee. Point de guidage : distance de manoeuvre (50 m si <=0), cap ajuste -14.25 degres pour `{1,3,7,9}`, +45 pour `{2,5,8,10}`, puis projection locale. [E10 :318/:650/:1004]

**V** CN D5 emet aussi les topics `0x0004000C000C8003`, `0x0004000D000D8001`, `0x0004000D000D8002` avec `{1:4294967295, 2:compteur}` ; `0x0004000E000E8001` ajoute `3:1`. Ces messages ont la meme enveloppe externe `1`. [E11, `AlternativeCnD5Strategy.buildShortEvent/buildShortEventWithThree/updateNavigation` :191/:205/:263]

**Dependance d'images omise dans la premiere version du rapport :** `SomeIpHudHelper.loadBydHudPngByHudId` et `loadBydHudPng` tentent de lire les drawables de **`com.byd.naviauto`**, par nom `turn_kind_*` et, pour le premier, par resource ID numerique de secours (`hudIdToNativeResId`). La methode `Companion.prepareNativePng` contient egalement des IDs numeriques fixes. Ce sont des dependances a une version de ressources OEM, pas un format d'icone stable. [E19]

Les replis `assets/hud/turn_<id>.png` et `assets/hud_arrows/<nom>.png` sont references par le helper, mais **absents de cet APK**, dont les seuls assets listes par `unzip -Z1` sont les deux profils dexopt. Un dessin de repli existe dans `drawManeuverIcon` ; son affichage correct n'a pas ete valide. Fournir l'APK `com.byd.naviauto` permettrait de verifier les noms/IDs et le rendu attendu ; ne pas redistribuer ses images. [E19]

**LAUNCHER_MAP_CN** : messages ci-dessous, tous enveloppes dans le champ externe `1`. Les service IDs a demarrer sont l'ensemble deduplique de `0x000B000000000000 | (((topic >> 16) & 0xFFFFFFFF) << 16)`, soit six services. [E11, `LauncherMapCnStrategy` :30/:100]

| Topic hex | Champs internes emis a chaque update, hors voies dedupliquees |
|---|---|
| `0x0004000700078001` | varint `4:101` |
| `0x000482028202800B` | varints `1:code CAN`, `2:mainAction`, `3:0`, `4:metres avant manoeuvre` |
| `0x0004000700078003` | varints `17:metres restants`, `18:secondes restantes` ; doubles `11:longitude`, `12:latitude` |
| `0x000482028202800C` | bytes `1:back lanes`, `2:front lanes`, `4:tableau FF`, `5:tableau 00`, `6:00 si guidee sinon FF` ; double `9:epoch millisecondes` |
| `0x0004000C000C8001` | varint `1:2641158014` |
| `0x0004000C000C8003` | varint `1:1729875789` |
| `0x0004000D000D8001` | varints `1:3592003832`, `5:1` ; doubles `12:5.0`, `13:2.2` |
| `0x0004000D000D8002` | varint `1:3817498742` |
| `0x0004000D000D8005` | varints `1:4073768758`, `3:1` |
| `0x0004000E000E8001` | varints `1:routeId`, `3:1` ; double `4:epoch microsecondes` |
| `0x0004001700178003` | message `1:{1:routeId varint,2:compteur varint,3:epoch microsecondes double}` ; message `3` de six doubles fixes ; varint `7:7` |

**V** Dernier message, sous-champ `3`, doubles `1..6` : `1161.2184496889508`, `971.1426529964466`, `-19.15885124372106`, `0.0014609250661213498`, `-1.5712258405572703`, `0.0037437084083233626`. Leur semantique OEM n'est pas etablie. `routeId` : entier aleatoire `1000000000..9999999999` conserve pour la session. `mainAction` : `1->2`, `2->3`, `3/4->4`, `5/6->5`, `7->6`, `8->7`, `9->8`, `10->9`, `11/12->1`, sinon `0`. [E11, Launcher :100 ; E10 :976]

### Cycle de vie SOME/IP

**V** Start : `bindService`, attendre `onServiceConnected`, TX4 pour chaque service du profil. Les donnees disponibles sont envoyees au fil des updates puis reemises toutes les **200 ms** ; compteur `(counter+1)&255`. Ce rythme est celui du client, pas une exigence serveur demontree. [E10 :65/:1090/:1199 ; `P/services/strategies/SomeIpHudHelper$startKeepAliveJob$1.java:47`, `invokeSuspend`]

**V** Stop : annuler keepalive, `strategy.stopNavigation`, TX5 pour chaque service, `unbindService`. UI7 et CN D5 ne font que vider leurs caches dans `stopNavigation` ; ils n'appellent pas le `buildClearFrame` present dans le helper. Launcher envoie les voies vides et les etats d'arret sur D8001 (`5=0`, `12/13=0.0`), D8005 (`3=0`), E8001 (`3=0`) avant fermeture. [E10 :1139 ; E11 :68/:112/:248]

**Localisation :** `LocationHelper.updateVehicleLocation` utilise `LocationManager.getLastKnownLocation("gps")`, puis `"network"` selon les permissions/resultats, au plus une lecture par 5000 ms. Ce n'est pas une lecture de position depuis le SDK BYD. Valeurs initiales `lat=39.9042`, `lon=116.4074`, `heading=0` ; si aucune position n'est disponible, le cache est conserve. Aucun controle de l'age de la position ni demande d'updates de localisation dans cette methode. Les strategies lisent ces caches pour leurs coordonnees et points synthetiques : leur emission n'atteste donc pas une position reelle/fraiche du vehicule. [E17]

**T** Les encodeurs UI7, CN D5 et Launcher sont maintenant exerces dans le modele smali et compares octet par octet a protobufjs **pour les cas du banc**. La progression CN D5 utilise bien une division `double`, pas la division entiere que laisse croire le Java decompile. Les images, coordonnees, horloge et plusieurs helpers restent substitues ; il ne s'agit pas de l'execution integrale du DEX sur ART. Les champs minimaux acceptes, les constantes opaques requises, l'ACL et le rendu du recepteur restent non etablis. Pas de promesse d'un emetteur SOME/IP universel. [B1]

**Inventaire DL5 disponible, serveur absent :** l'archive `/home/ccarre/app_byd/new_apk_extract/Dilink5.0/byd_apk_20260803_190324.zip`, SHA-256 `d6d6fa3530de5653e91a9532faf5d4b2d9378f5b8d2b20b91f4ec93193384236`, contient `01_inventory.txt:155` mentionnant `com.ts.car.someip.service` a `/system/app/SomeIpService/SomeIpService.apk`. Aucun APK de ce service n'est inclus dans l'archive inspectee. Cela confirme sa presence sur le systeme inventorie, ni sur le vehicule actuellement vise, ni son ACL/AIDL. L'artefact a obtenir est donc precis, pas une demande generique de tous les APK.

## Broadcast Amap complementaire

**V** `HudController.sendStandardAmapBroadcast` appelle `sendBroadcast` trois fois : package `com.byd.amapservice`, package `com.example.amapservice`, puis intent sans package. Aucun `startService`, aucune permission recepteur transmise au `sendBroadcast`. Flags `285212672` (`0x11000000`). [E6 :150]

| Extra | Navigation | Stop |
|---|---|---|
| action | `AUTONAVI_STANDARD_BROADCAST_SEND` | identique |
| `KEY_TYPE`, `TYPE` | `int 10001`, `int 8` | `int 10001`, `int 9` |
| `EXTRA_STATE`, `EXTRA_IS_FOREGROUND` | `int 8`, `int 0` | `int 1`, `int 1` |
| `IS_BYD_MAP`, `IS_BYD_BAIDU_MAP` | `boolean true`, `boolean false` | identiques |
| `NEW_ICON` | code Amap remappe ci-dessous | `int -1` |
| `SEG_REMAIN_DIS`, `NEXT_ROAD_NAME` | `int` metres, `String` rue | `-1`, chaine vide |
| `ROUTE_REMAIN_DIS`, `ROUTE_REMAIN_TIME` | `int` metres / secondes, `-1` si inconnus | `-1`, `-1` |

**V** S'ajoutent `SEG_REMAIN_DIS_AUTO`, eventuellement `ROUTE_REMAIN_DIS_AUTO`, `ROUTE_REMAIN_TIME_AUTO` et `ROUTE_REMAIN_TIME_STRING` (textes m/km, h/min). `NEXT_NEXT_ROAD_NAME` est possible ; le caller passe `-1/-1` pour icone/distance secondaires, donc `NEXT_NEXT_TURN_ICON` et `NEXT_SEG_REMAIN_DIS` ne sont pas emis dans ce chemin. [E6 :150/:434]

**V, recoupe smali** : codes CAN -> `NEW_ICON` : `1/7->6`, `2/8->4`, `3/4->7`, `5/6->3`, `9/10->5`, `11/12->2`, `15..20->8`, `48->12`, sinon `2`. Les ronds-points numerotes `25..44` retombent donc a `2` sur cette voie. La methode Java :118 est mal decompilee et parait retourner presque toujours `2` ; preuve determinante : `S/com/sr/openbyd/services/HudController.smali:281`, `mapTurnKindToAmapBroadcastIcon`.

## Mapping Maps / Waze vers les codes CAN

**V** Sources : `D/n50.java`, methode `a(String)` ; `D/qo1.java`, methodes `a(Context,int[],int,int)` et `b(String)` ; noms des codes dans `P/services/HudController.java:49`. Les accolades ci-dessous representent exactement plusieurs labels, pas des motifs appliques a l'execution. Waze retire un prefixe parmi `car_dark_big_`, `car_big_trans_`, `big_trans_`, `big_` avant le mapping.

**T** Les tests enumerent tous les labels des fonctions `n50.a` et `qo1.b`, les prefixes Waze et des valeurs hors domaine pour les remappages de sortie. Cela verifie le mapping du client ; la reconnaissance d'images reelles et la correspondance au glyphe du firmware sont des validations differentes. Voir la divergence O3 dans le complement SDK ci-dessus.

| Manoeuvre / code CAN | Labels Maps apres classification | Labels Waze apres retrait du prefixe |
|---|---|---|
| Gauche `1` | `maneuver_{turn,on_ramp,off_ramp}_normal_left` | `direction_left` |
| Droite `2` | `maneuver_{turn,on_ramp,off_ramp}_normal_right` | `direction_right` |
| Legere/bifurcation gauche `3` | `maneuver_turn_slight_left`, `maneuver_{fork,keep}_left`, `maneuver_{on_ramp,off_ramp}_{slight,keep}_left` | `direction_exit_left` |
| Legere/bifurcation droite `5` | memes formes `_right`, plus `maneuver_slight_right` | `direction_exit_right` |
| Serree gauche `7`, droite `8` | `maneuver_{turn,on_ramp,off_ramp}_sharp_{left,right}` | pas d'entree distincte |
| Demi-tour gauche `9` | `maneuver_u_turn_left`, `maneuver_{on_ramp,off_ramp}_u_turn_left` | `direction_u_turn` |
| Demi-tour droit `10` | memes formes `_right` | `direction_u_turn_{uk,lhs}` |
| Tout droit `11` | `maneuver_straight`, `maneuver_name_change`, `maneuver_merge{,_left,_right}` | `direction_forward` ; repli inconnu |
| Depart `12` | `maneuver_depart` | pas d'entree distincte |
| Rond-point CCW, gauche `15` | `maneuver_roundabout_enter_and_exit_ccw_{slight,normal,sharp}_left` | `directions_roundabout_l` |
| Rond-point CW, gauche `16` | meme famille `_cw_*_left` | `directions_roundabout_r_lhs` |
| Rond-point CW, droite `17` | meme famille `_cw_*_right` | `directions_roundabout_l_lhs`, `directions_roundabout_r_uk` |
| Rond-point CCW, droite `18` | meme famille `_ccw_*_right` | `directions_roundabout_r` |
| Rond-point CW, droit/generique `19` | `maneuver_roundabout_{enter,exit}_cw`, `maneuver_roundabout_enter_and_exit_cw{,_straight}` | `directions_roundabout_s_lhs`, `directions_roundabout_uk_s` |
| Rond-point CCW, droit/generique `20` | memes formes `_ccw` | `directions_roundabout_s` |
| Rond-point CW demi-tour `21`, CCW `22` | `maneuver_roundabout_enter_and_exit_{cw,ccw}_u_turn` | `directions_roundabout_u_{lhs,uk}` ->21 ; `directions_roundabout_u` ->22 |
| Sortie CCW n `24+n` (`25..34`) | pas de numero explicite dans `n50.a` | rond-point reconnu + numero UI n ; generique sans numero `directions_roundabout` ->25 |
| Sortie CW n `34+n` (`35..44`) | idem | rond-point reconnu `_lhs` ou `_uk` + n ; generiques `_lhs`/`_uk` sans numero ->35 |
| Arret lateral `45` | `maneuver_destination_left` **et** `_right` | `direction_stop` |
| Destination `48` | `maneuver_destination`, `maneuver_destination_straight` | `direction_end` |

**V** Les codes `4,6,13,14,23,24,46,47,49` existent comme constantes mais ne sont pas produits par ces deux fonctions de mapping nominales. `qo1.a` ne borne pas n a `1..10` : cette plage vient des constantes de `HudController`. Smali du choix `24+n` / `34+n` : `S/qo1.smali:1350` ; ne pas reprendre le faux fall-through de la sortie Java.

## Permissions et contexte d'execution

| Surface | Verifie / exigence / limite |
|---|---|
| Manifest OpenBYD | Pas de `sharedUserId`, pas de `<uses-library>`, pas de permission `bydauto.*` declaree. La permission `com.sr.openbyd.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` est propre a l'app, protection `signature`, pas une autorisation HUD. [M] |
| Lecture des notifications | Service protege par `android.permission.BIND_NOTIFICATION_LISTENER_SERVICE`, exporte. **G** L'utilisateur autorise l'acces notifications ; l'app ne demande pas a obtenir cette permission signature comme permission dangereuse. [M:113, E1] |
| Lecture des vues Waze/Maps | Service protege par `BIND_ACCESSIBILITY_SERVICE`, `canRetrieveWindowContent=true`, flags `flagReportViewIds` et `flagRetrieveInteractiveWindows`. **G** Activation explicite du service requise ; le filtrage des touches declare par OpenBYD n'est pas necessaire a la seule lecture HUD. [M:78, E3] |
| Capture Waze | `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PROJECTION`, type de service `mediaProjection`. **V** Consentement/token `MediaProjection` demande si pas de surface Waze deja projetee. [M:17, E5, `WazeManager.startCaptureService`:119] |
| CAN / SDK | **V** ADB local authentifie, socket `127.0.0.1:5555`, puis `app_process`. Aucun `su` dans ce lancement. **G** Sans changement d'UID effectif, `app_process` herite de l'UID du shell ADB. Le `--uid=2000` place APRES `EntryPoint` est un argument Java, pas une preuve de changement d'identite. [E9] |
| Classpath SDK | **V** `/system/framework/services.jar:/system/framework/dilink-services.jar:<APK>` ; chemin natif `/system/lib64:/product/lib64:<APK>!/lib/arm64-v8a`. Ce n'est pas une declaration de shared library Android. Jar precis fournissant chaque classe et nom `<uses-library>` eventuel : inconnus sans fichiers systeme. [E9] |
| Signature / controles OEM | **V** `BydContextWrapper` renvoie permission accordee et neutralise les methodes `enforce*` locales. **G** Cela ne donne aucun droit Binder/SELinux cote serveur. Signature plateforme non prouvee necessaire ni suffisante ; droit d'une app UID ordinaire non etabli par cet APK. [E9, E12] |
| SOME/IP | **V** Binding depuis l'app, sans permission vendor explicite dans le manifest. Export/protectionLevel du service, ACL UID/certificat et restrictions SELinux inconnus : fournir son APK/dump. [E10, M] |
| Localisation / autres droits | **V** `ACCESS_FINE_LOCATION`/`ACCESS_COARSE_LOCATION` declares et localisation consommee par SOME/IP. `INTERNET` sert notamment a ADB ; overlay, queries, stockage/telephone et boot sont declares mais ne sont pas etablis comme prerequisites des seules ecritures CAN. [M, E9, E10] |

**Preparation omise dans la premiere version :** `ProxyManager$onProxyConnected$5.invokeSuspend` appelle `HudSetupHelper.autoEnableIfNeeded`. Quand l'integration est activee, ce helper tente via `ShellCommandExecutor` un `cmd notification disallow_listener`, attend 200 ms puis `cmd notification allow_listener` sur son propre listener. Cette tentative de rearmement par le proxy est distincte de l'autorisation manuelle Android decrite plus haut ; sa reussite n'a pas ete observee sur vehicule. Aucune de ces commandes n'a ete executee pendant cette analyse. [E18]

**Autre tentative privilegiee :** `ProxyManager$onProxyConnected$2.invokeSuspend` appelle `grantOverlayPermission`, qui tente `appops set <package> SYSTEM_ALERT_WINDOW allow`, puis `appops set <package> PROJECT_MEDIA allow` si le premier appel ne renvoie pas `null`. Le nom de la methode masque donc aussi un reglage de capture. La reussite, et l'effet exact de `PROJECT_MEDIA` sur l'ecran de consentement du firmware, ne sont pas prouves ici. Ne pas deduire du manifest seul qu'OpenBYD utilise uniquement le parcours manuel Android. [E20]

Ne pas inventer un `<uses-library android:name="bydauto">` ni recopier les permissions de l'app entiere dans DashCast. Le manifest du recepteur et les XML de `/system/etc/permissions/` sont necessaires pour trancher cette partie du contrat.

## Kotlin minimal pour DashCast, voie CAN

**V** Les methodes utilisees ci-dessous existent dans `CanBusController.kt`. Cet exemple est un **appel bas niveau pour une voie CAN deja validee**, pas un point d'entree universel : il n'applique pas lui-meme le filtre DL3/non-AAOS de `HudController.updateNavigation` ni son watchdog. Un proxy connecte ne prouve pas la compatibilite du vehicule. En production, conserver l'orchestrateur et son filtrage ; ne pas executer ce bloc sur une plateforme inconnue ou SOME/IP pour tester au hasard.

Appeler ces fonctions depuis **un meme executor de travail serie**, jamais le main thread, apres validation du profil et connexion du proxy. Appeler start une fois au debut de navigation, update a chaque changement, stop a la fin. Les exceptions du proxy doivent etre traitees par l'orchestrateur, pas ignorees.

```kotlin
package com.byd.dashcast.hud.interop

import com.byd.dashcast.system.CanBusController

fun startCanHud() {
    CanBusController.setNaviActive(true)
}

fun updateCanHud(turnCode: Int, distanceMeters: Int, nextRoad: String) {
    require(turnCode in 1..49)
    require(distanceMeters >= 0)
    CanBusController.sendNaviStatusHeartbeat()
    CanBusController.sendSimpleGuidance(turnCode, distanceMeters)
    CanBusController.sendNextStreetName(nextRoad)
}

fun stopCanHud() {
    CanBusController.setNaviActive(false)
}
```

Exemple de donnees : `updateCanHud(1, 150, nextRoadFromGps)` encode gauche dans 150 m selon le mapping du client. **L'acceptation de ces valeurs par le SDK du vehicule et leur affichage n'ont pas ete testes dans cette analyse** ; aucune dependance a OpenBYD n'est necessaire a l'execution.

Ce bloc reproduit le coeur de la voie CAN via l'API **existante** de DashCast, pas toutes les ecritures redondantes d'OpenBYD. Le code source isole de l'exemple est dans `/home/ccarre/app_byd/tiers_apk/OpenBYD2.5/analysis/DashCastHudExample.kt`. La compilation et les tests locaux ne prouvent pas le rendu sur un vehicule.

### Ecarts utiles pour DashCast

- **V** Le proxy, les FID principaux, les 49 codes et UTF-16LE sont deja implementes : reutiliser `CanBusController` / l'orchestrateur `hud/HudController`, pas un second proxy. [D1, D2]
- **V** `CanNavigationBatches.simpleGuidance` omet intentionnellement `0x43F01030` avec une note de refus SDK dans le corpus de testeurs ; cet APK continue de l'ecrire. Garder cette distinction. [D2 :50, E8 :1765]
- **V** Le start DashCast est statut + layout ; OpenBYD ajoute Statistic nav/ISA et des appels SDK specialises. C'est un delta observe, PAS la preuve que ces ajouts sont requis ou souhaitables sur ton vehicule. [D2 :9, E8 :1391]
- **V** DashCast envoie deja un heartbeat CAN et sait ecrire l'ETA jour/heure/minute/seconde ; OpenBYD n'ecrit directement que la minute d'horloge dans `sendRestRouteInfo`. Ne pas regresser vers ce sous-ensemble. [D1 :169/:263, D2 :105, E8 :1669]
- **V, etat de l'audit initial** Aucun transport SOME/IP n'etait present dans `app/src/main`. Depuis le 2026-10-03, un socle `SomeIpHudTransport` existe, mais reste inactif en production. L'[export pilote DL3 / SX361 du 2026-10-06](OEM_EVIDENCE_DL3_SX361_20261006.md) ne contient pas de recepteur SOME/IP installe. Cette sortie exige toujours l'identification d'un profil et de son serveur ; les codes CAN existants restent distincts.

## Risques et inconnues

### Defauts reproduits dans le banc

| Cas | Resultat reproduit / precondition | Preuve et consequence pour DashCast |
|---|---|---|
| Retour SDK negatif | Avec `set` et `sendSimpleGuidanceInfo` simulant `-7`, OpenBYD retourne quand meme un texte contenant `RESULT_CODE:0` | `CarControlImpl.sendSimpleGuidanceInfo`, `S/com/sr/openbyd/proxy/CarControlImpl.smali:14037` ; propager les vrais retours, pas le texte final |
| Binding tardif | `start` -> `stop` avant connexion -> `onServiceConnected` laisse `isBound=true` sans `unbind` | `SomeIpHudHelper$conn$1.onServiceConnected`, `S/com/sr/openbyd/services/strategies/SomeIpHudHelper$conn$1.smali:43` ; invalider les generations et detacher les connexions tardives |
| Keepalive sans age propre | Tant que les caches/session/Binder sont presents, `sendKeepAliveUpdate` reemet sans verifier l'age de source | `S/com/sr/openbyd/services/strategies/SomeIpHudHelper.smali:482` ; un watchdog de source doit rester distinct du rythme de transport |
| Mode modifie avant fermeture | Si la preference devient `CAN_BUS_ONLY` avant `CanBydFidStrategy.stopNavigation`, l'ancien helper SOME/IP n'est pas arrete | `S/com/sr/openbyd/services/strategies/CanBydFidStrategy.smali:699` ; arreter les sorties effectivement ouvertes, pas celles selectionnees par les nouvelles preferences |
| Voies Launcher | Changer seulement `front lanes` sans changer `back lanes` ne reemet pas le topic de voies | `S/com/sr/openbyd/services/strategies/LauncherMapCnStrategy.smali:853` ; dedupliquer le payload utile complet |

Ces resultats viennent des tests [transport.test.mjs](audit/transport.test.mjs), pas de logs vehicule. Le cas de mode impose une sequence de preferences : son accessibilite exacte depuis tous les callbacks UI n'est pas certifiee. Le keepalive n'est pas seul responsable de l'arret : Waze a notamment son expiration de 40 s ; le test ne pretend pas que toute session Waze est entretenue indefiniment.

### Limites restantes

- **V / H** Pas d'arbitrage explicite avec la navigation native dans les controleurs analyses. OpenBYD reactive un statut qu'il ne considere plus actif ; son stop ecrit un etat global et efface des donnees sans restaurer les precedentes. **H** Course entre producteurs et dernier ecrivain gagnant : a confirmer sur le recepteur. [E7 :76/:158, E8 :1391]
- **V / G** Transactions SOME/IP et appels SDK synchrones, groupes de FID successifs non atomiques. Utiliser un worker serie, gestion du binder mort/rebind et journalisation des retours ; ne pas bloquer UI ou callbacks d'accessibilite avec ces emissions. [E8, E10]
- **V** API UI et signatures bitmap dependent des versions/langues/themes Maps/Waze. Le repli Waze tout droit, l'expiration de 40 s et les trames conservees SOME/IP peuvent laisser une indication obsolete. Valider a l'arret les changements de manoeuvre, d'app et l'arret de route. [E1, E3, E13-E15]
- **V** Les coordonnees de repli et les positions sans controle d'age peuvent alimenter les trames SOME/IP ; les IDs de drawables OEM peuvent changer. Ne pas recopier ces choix dans DashCast sans politique explicite de validite de position et verification des images. [E17, E19]
- **V / H** Certaines ecritures concernent l'ISA/limite de vitesse, pas seulement une icone decorative. Leur effet sur les fonctions d'assistance n'est pas etabli : ne pas les ajouter au portage minimal sans contrat du firmware. [E8 :1794]
- **V** Le succes du SDK ou d'un `transact` n'atteste ni la consommation par le MCU, ni l'activation de la bonne variante de HUD. Manquent les captures `logcat`/retours et l'observation du HUD.
- A obtenir pour le systeme cible : version Android/DiLink et variante, XML de permissions et APK `com.ts.car.someip.service`, puis `com.byd.naviauto` pour ses ressources si necessaire. Le framework DL3 local leve une partie de l'inconnu SDK, mais ne remplace pas celui du vehicule cible. L'archive DL5 n'apporte pas l'APK serveur SOME/IP ; l'ACL, l'AIDL complet et la validite des profils restent ouverts.

**Prochaine collecte, en lecture seule depuis WSL** :

```bash
rtk proxy adb shell getprop ro.build.version.release
rtk proxy adb shell getprop ro.build.display.id
rtk proxy adb shell getprop ro.vehicle.type
rtk proxy adb shell getprop ro.build.car.platform
rtk proxy adb shell pm path com.ts.car.someip.service
```

Ces sorties servent a choisir le prochain artefact a extraire. Elles ne suffisent pas, seules, a certifier CAN contre SOME/IP ; le FID de configuration HUD et le comportement du recepteur restent determinants. Aucun besoin de Frida/Ghidra n'est demontre avant cette etape.

## Reproductibilite et limites d'analyse

### Reprise et verification executable

Apres reprise du 2026-09-12, le banc [verify.mjs](audit/verify.mjs) a ete relance : **29 PASS, 0 FAIL/SKIP**. Il verifie l'empreinte de l'APK, compare les **9415 fichiers smali** avec une seconde extraction identique, puis execute les cas de mapping, encodage et orchestration dans un modele smali borne.

Les charges utiles des trois profils sont comparees a une reference protobufjs pour les cas testes. Les transactions Binder sont enregistrees avec un faux transport ; Android, les images, la localisation et plusieurs helpers sont substitues explicitement. Ce n'est pas une execution sur ART ni sur le service OEM.

Le rapport genere [proof.json](audit/proof.json) recense **80/828 methodes du perimetre exercees**, **343 sites d'appels** aux composants suivis, les instructions/branches parcourues et les substitutions. Une methode exercee n'est pas necessairement couverte sur toutes ses branches. Les autres methodes restent marquees `INDEXED_NOT_EXERCISED` : ce banc ne certifie pas "zero oubli, zero erreur".

Couverture d'instructions dans les cas du banc : UI7 `buildRoadInfo` **145/145**, CN D5 `buildRoadInfoCnD5` **321/321**, Launcher `updateNavigation` **355/380**. Meme une couverture d'instructions complete ne signifie pas toutes les combinaisons, tous les chemins d'exception ni toutes les donnees reelles. Le modele utilise 82 types d'appels substitues explicites ; ses intrinseques Java sont eux-memes un sous-ensemble, pas une VM complete.

Node `v18.19.1`, protobufjs `8.8.0` ; le rapport machine conserve maintenant l'empreinte des huit scripts du banc, du lock de dependances et du corpus smali. Le [mode d'emploi](audit/README.md) precise la reproduction et les limites. Le sous-repertoire `audit/` reste ignore par la configuration Git existante ; les preuves sont locales, pas commitees. Les essais dex2jar/Enjarify + Robolectric abandonnes ne sont pas comptes comme validation.

Commande locale depuis la racine de MyBYDApp : `rtk proxy node docs/openbyd-2.5/audit/verify.mjs`. Les tests de non-regression CAN de DashCast et ce banc de protocole restent deux validations distinctes ; aucun des deux ne prouve le rendu physique du HUD.

### Relecture critique du 2026-09-12

Cette section conserve l'etat de la premiere relecture, avant le banc executable ci-dessus et les complements OEM. L'analyse initiale etait detaillee sur le chemin principal mais **non exhaustive**. Les 97 fichiers ont ete indexes par Graphify ; ce nombre n'est pas un nombre de fichiers audites integralement. Les stubs et parseurs R8 ont ete lus de facon ciblee.

Corrections de cette relecture : retrait de l'affirmation non prouvee "le SDK/FID accepte le code 1" ; precondition DL3/non-AAOS et absence de watchdog rendues explicites pour l'exemple bas niveau ; ajout de la dependance `com.byd.naviauto`, des assets de repli absents, du cache de position par defaut/non controle et des tentatives de reglage de permissions via proxy. Les recherches negatives sont maintenant qualifiees "non trouve" plutot que des preuves absolues de refutation.

| Niveau de verification | Couverture reelle |
|---|---|
| APK et sources | Manifest/signature, points d'entree Maps/Waze, appels SDK/FID, profils SOME/IP, mappings dans les fonctions citees |
| Recoupement smali cible | Switch Amap, sortie CW/CCW Waze, deduplication CAN, champs et codes TX4/5/6, references OEM et commandes du helper de preparation |
| Tests executes | 7 tests CAN existants de DashCast ; compilation du petit exemple contre ses classes ; controles de coherence des identifiants, liens et sources cites |
| Non realise | Execution des parseurs OpenBYD sur un corpus de captures, tests exhaustifs des encodeurs/profils, validation de chaque methode mal decompilee, analyse des JAR/services reels, observation du HUD/ACL sur vehicule |

**Les tests CAN ne valident ni les encodeurs SOME/IP ni le rendu HUD.** Le plan d'integration a ete complete pour terminer ces verifications sur le profil retenu ; le mecanisme principal confirme ne constitue pas une certification d'exhaustivite.

JADX execute avec `--show-bad-code --no-replace-consts --no-inline-methods` ; 14 erreurs globales annoncees. Des reconstructions erronees sont visibles dans le code HUD : les valeurs de switch Amap, le choix CW/CCW Waze et la deduplication CAN ont ete recoupes dans le smali Apktool. Les branches Java marquees "decompiled incorrectly" ne sont pas traitees comme une implementation recompilable.

Les seuls `.so` embarques, dans quatre ABI, sont `libspake2.so` et `libandroidx.graphics.path.so`. Les chaines du premier nomment `io/github/muntashirakon/crypto/spake2/Spake2Context`. Les sorties HUD retrouvees sont en Java ; une recherche `strings` sans resultat HUD n'est pas a elle seule une preuve de l'absence de toute logique native.

Graphify local, sans API : 97 fichiers `com/sr/openbyd`, 1703 noeuds, 3770 aretes, 92 communautes. Fichier `/home/ccarre/app_byd/tiers_apk/OpenBYD2.5/jadx/graphify-out/graph.json`. Les parseurs R8 de `defpackage` et les stubs ont ete lus separement. Diagnostic post-build : aucun endpoint manquant/dangling, aucun effondrement d'aretes detecte, **une self-loop**. Graphe non dirige : son plus court chemin listener -> proxy passe par une reference a `java.lang.reflect.Method`, pas par des appels. Il ne constitue donc pas la preuve du flux decrit plus haut.

Verification effectuee cote DashCast : **7 tests `CanBatchOperationTest` passes**, couvrant activation/stop, buffer UTF-16LE, ordre d'execution et propagation des rejets. Commande : `rtk proxy env -C /home/ccarre/app_byd/MyBYDApp ./gradlew :app:testDebugUnitTest --tests com.byd.dashcast.system.CanBatchOperationTest --offline`.

L'exemple Kotlin a ete compile separement avec Kotlin 2.4.0 / JDK 21 contre `app/build/intermediates/compile_app_classes_jar/debug/bundleDebugClassesToCompileJar/classes.jar`, cible JVM 17. Aucun build distribue, changement de version, installation, commit ou modification de code de production n'a ete effectue.

## Index des preuves

Les prefixes de chemins sont definis en tete. Chaque entree donne fichier, classe et methode ; une ligne est un point d'entree de lecture, pas une assertion de couverture d'une methode entiere par une seule ligne.

| Ref | Fichier : ligne ; classe / methode |
|---|---|
| E1 | `P/services/MapNotificationListenerService.java:73` ; `MapNotificationListenerService.onNotificationPosted` ; arret `onNotificationRemoved`:142, `onListenerDisconnected`:64 |
| E2 | `P/services/MapNotificationListenerService$onNotificationPosted$1.java:169` ; classe du meme nom, `invokeSuspend` ; chargement et classification de la grande icone dans la branche Maps |
| E3 | `P/services/BydAccessibilityService.java:533` ; `BydAccessibilityService.handleWazeEvent` ; routage `onAccessibilityEvent`:1168 ; `jadx/resources/res/xml/accessibility_service_config.xml:2` |
| E4 | `P/services/BydAccessibilityService.java:266`, `handleGoogleMapsEvent` ; `P/services/GoogleMapsManager.java:81`, `GoogleMapsManager.sendHudUpdate`, `updateFromNotification`:246, `updateNavigationTexts`:259 |
| E5 | `P/services/WazeArrowCaptureService.java:120` ; `WazeArrowCaptureService.captureArrow`, `captureDefaultArrowFromSurfaceView`:238, `onStartCommand`:865 ; `P/services/WazeArrowCaptureService$onStartCommand$2.java:43`, `invokeSuspend` |
| E6 | `P/services/HudController.java:105` ; `HudController.getStrategy`, `sendStandardAmapBroadcast`:150, `sendStandardAmapStopBroadcast`:235, `updateNavigation`:434 |
| E7 | `P/services/strategies/CanBydFidStrategy.java:76` ; `CanBydFidStrategy.ensureHudActive`, `isCanActive`:101, `isSomeIpActive`:115, `startNavigation`:136, `stopNavigation`:158, `updateNavigation`:225 ; enum `D/l70.java:6` |
| E8 | `P/proxy/CarControlImpl.java:1391` ; `CarControlImpl.sendAutoNaviStatus`, `sendNextPathName`:1635, `sendRestRouteInfo`:1669, `sendSecondaryGuidanceInfo`:1744, `sendSimpleGuidanceInfo`:1765, `sendSpeedLimitInfo`:1794, `setInstrumentFeatureBytes`:1906, `setInstrumentFeatureValue`:1955, `setSettingFeatureValue`:1970, `setStatisticFeatureValue`:2001 |
| E9 | `P/proxy/ProxyManager.java:152`, `ProxyManager.performStart` (commande :252) ; `P/proxy/EntryPoint.java:19`, `EntryPoint.main` ; `P/proxy/SystemContext.java:13`, `SystemContext.get` ; `P/proxy/BydContextWrapper.java:8`, classe et methodes `check*`/`enforce*` |
| E10 | `P/services/strategies/SomeIpHudHelper.java:48` ; `SomeIpHudHelper`, constantes/`bind`:254, `fireEvent`:506, `getStrategy`:563, `guideLine`:615, `mapManeuverToMainAction`:976, `mapToBydHudManeuverId`:1004, `mapToF28`:1056, `startNavigation`:1090, `startSomeIpService`:1109, `stopNavigation`:1139, `stopSomeIpService`:1156, `updateNavigation`:1199, encodeurs :1225 |
| E11 | `P/services/strategies/LauncherMapCnStrategy.java:30`, constructeur, `stopNavigation`:68, `updateNavigation`:100 ; `P/services/strategies/AlternativeUi7Strategy.java:29`, `buildRoadInfo`, `stopNavigation`:112, `updateNavigation`:119 ; `P/services/strategies/AlternativeCnD5Strategy.java:38`, `buildRoadInfoCnD5`, `stopNavigation`:248, `updateNavigation`:263 |
| E12 | `J/android/hardware/bydauto/instrument/BYDAutoInstrumentDevice.java:882`, `getInstance` ; `sendAutoNaviStatus`:1662, `sendNextPathName`:1706, `sendRestRouteInfo`:1718, `sendSimpleGuidanceInfo`:1726 ; `J/android/hardware/bydauto/AbsBYDAutoDevice.java:95`, `set(int[],BYDAutoEventValue)` |
| E13 | `D/n50.java:50`, `n50.a(String)` ; `D/do0.java:51`, `do0.b`, `c`:210, `d`:228 ; `D/n50.java:18`, registre de signatures |
| E14 | `D/qo1.java:37`, `qo1.a` ; `qo1.b`:72 ; preuve rond-point `S/qo1.smali:1350` ; le mapping Amap est recoupe dans `S/com/sr/openbyd/services/HudController.smali:281` |
| E15 | `P/services/WazeManager.java:47`, Runnable d'expiration ; `sendHudUpdate`:87, `processArrowAndLanes`:185, `updateNavigationTexts`:259 ; `D/k70.java:104`, `toString` du modele |
| E16 | `P/utils/HudProtocolDetector.java:88` ; `HudProtocolDetector.queryHudConfig`, `detectProtocol`:120 |
| E17 | `P/utils/LocationHelper.java:43`, `LocationHelper.updateVehicleLocation` ; valeurs initiales :12 et suivantes |
| E18 | `P/services/HudSetupHelper.java:46`, `HudSetupHelper.autoEnableIfNeeded` ; `P/proxy/ProxyManager$onProxyConnected$5.java:40`, `invokeSuspend` ; commandes recoupees dans `S/com/sr/openbyd/services/HudSetupHelper.smali:258` et :338 |
| E19 | `P/services/strategies/SomeIpHudHelper.java:735`, `loadAssetHudPngByHudId`, `loadBydHudPng`:808, `loadBydHudPngByHudId`:876, `Companion.prepareNativePng`:1322, `hudIdToDrawableName`:676, `hudIdToNativeResId`:703 ; noms recoupes dans `S/com/sr/openbyd/services/strategies/SomeIpHudHelper.smali:4260`, :4682, :5155 |
| E20 | `P/proxy/ProxyManager$onProxyConnected$2.java:37`, `invokeSuspend` ; `P/utils/ShellCommandExecutor.java:52`, `execute`, `grantOverlayPermission`:87, `grantProjectMedia`:97 |
| E21 | `O3/BYDAutoInstrumentDevice.java:900`, `getInstance`, permissions :387/:394, device type :889, `sendSimpleGuidanceInfo`:1645, `sendRestRouteInfo`:1681, `sendNextPathName`:1693, `sendAutoNaviStatus`:1787, constantes `TURN_KIND`:795 |
| E22 | `O3/AbsBYDAutoDevice.java:260`, `set(int[], BYDAutoEventValue)` ; `checkDeviceFeatures`:391 ; `O3/BYDAutoDeviceManager.java:22`, constructeur et `getSystemService("auto")` |
| E23 | `O3/BYDAutoManager.java:42`, declarations natives ; `initPermissionStrategy`:91, `updateApiStrategy`:114, `setInt`:146 et methodes `set*` suivantes |
| B1 | [verify.mjs](audit/verify.mjs), [proof.json](audit/proof.json), [payload.test.mjs](audit/payload.test.mjs), [input.test.mjs](audit/input.test.mjs), [transport.test.mjs](audit/transport.test.mjs) ; un rapport par methode et les substitutions, pas seulement un total de tests |
| D1 | `app/src/main/java/com/byd/dashcast/system/CanBusController.kt:42` ; `CanBusController`, `setNaviActive`, `sendNaviStatusHeartbeat`, `sendSimpleGuidance`, `sendNextStreetName`, `sendRestRoute`, `sendExpectedArrival` |
| D2 | `app/src/main/java/com/byd/dashcast/system/CanNavigationBatches.kt:6` ; `CanNavigationBatches.navigationState`, `simpleGuidance`, `nextStreetName`, `restRoute`, `expectedArrival` |

### Binder prive OpenBYD, a ne pas confondre avec le SDK

**V** `P/ipc/ICarControl.java:13`, descripteur `com.sr.openbyd.ipc.ICarControl`. La table `Stub` :370 et suivantes donne, pour le sous-ensemble HUD :

| TX | Signature cote interface privee |
|---|---|
| `22/23/45` | `String setInstrumentFeatureValue(int,int)` / `setSettingFeatureValue(int,int)` / `setStatisticFeatureValue(int,int)` |
| `24` | `String setInstrumentFeatureBytes(int,byte[])` |
| `26/27/28/29` | `String sendAutoNaviStatus(int)` / `sendSimpleGuidanceInfo(int,int)` / `sendNextPathName(String)` / `sendRestRouteInfo(int,int,long)` |
| `34/36` | `String sendSecondaryGuidanceInfo(int,int)` / `sendLaneGuidanceInfo(int[],int[],int)` |
| `37/38/39` | `int getNaviStatus()` / `String turnOnNavi()` / `String turnOffNavi()` |

Le Binder est transporte dans l'extra `proxy_binder` du broadcast explicite `com.sr.openbyd.PROXY_CONNECTED` vers `com.sr.openbyd` (`EntryPoint.main`:19). Il n'est pas publie comme un service systeme a retrouver avec `ServiceManager.getService`. DashCast n'a aucune raison de se coupler a cette interface privee : son propre proxy expose deja ses ecritures CAN.
