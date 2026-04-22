# BYD Auto App — Contexte projet complet

> Fichier de référence à conserver dans git pour reprise du contexte sur un autre poste ou après compact IA.  
> Dernière mise à jour : 22/04/2026 — v2.12 — Perf & Sanity checks appliqués : fixed thread pool sur ADB (max 4), memory limit sur ViewHolders (AppListAdapter O(1) OnClickBindings), refactoring Gradle property loading (stream closed properly), et Gradle daemon build opts.

---

## Cible hardware

- **Véhicule** : BYD Seal EU
- **Infotainment** : DiLink 3.0 (XDJA/Qualcomm 6125F)
- **Android** : API 29 (Android 10)
- **Cluster (tableau de bord)** : display 1 dans IActivityManager, résolution 1920×480
  - N'apparaît PAS dans DisplayManager comme VirtualDisplay/PRESENTATION par son nom — il faut `getDisplays(DISPLAY_CATEGORY_PRESENTATION)` → 1er display != 0
  - VirtualDisplay créé au boot par `com.xdja.containerservice/AutoDisplayService` (`createVirtualDisplay("fission_testVirtualSurface", 1920, 1080, 320, qtSurface, 11)`)
  - `sendInfo(1000,16)` ne crée pas le display, dit juste à Qt d'arrêter de rendre dessus
- **com.byd.automap** : NON installé sur Seal EU (uniquement sur d'autres modèles)

---

## SDK

- SDK BYD extrait : `sdk/SDK_v1.0.5/byd-auto_sdk_windows/`
- API 25 modifié avec classes `android.hardware.bydauto.*` (utilisé pour compiler uniquement)
- Keystore : `byd-auto_sdk_windows/keystore/platform.keystore`
  - alias: `androiddebugkey` | storepass/keypass: `android`
  - Certificat MD5withRSA (legacy, désactivé dans JDK récent)
  - SHA256: `C8:A2:...` — signer avec cette clé est **obligatoire** pour les permissions `signature`
- Build tools : 30.0.3 (via AGP 7.4.2, auto-sélectionné)

---

## Projet principal

- Dossier : `MyBYDApp/` — git : `feature/api29-upgrade`
- Remote : `https://github.com/Kiroha/byd-dashboard.git` (privé)
- Package : `com.byd.myapp`
- `local.properties` → `sdk.dir=/home/ccarre/app_byd/sdk/SDK_v1.0.5/byd-auto_sdk_windows`
  - Aussi : `azure.workspace.id` + `azure.primary.key` (hors git, à recréer sur nouveau poste)
- `compileSdkVersion 29` / `minSdkVersion 29` / `targetSdkVersion 29`
- Signé debug ET release avec `signingConfigs.bydPlatform` (platform.keystore)
- Build : `./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`

### Git push (token requis)

```bash
git push https://TOKEN@github.com/Kiroha/byd-dashboard.git feature/api29-upgrade
# ou :
git push https://$(grep github ~/.git-credentials | sed 's|https://||' | sed 's|@github.com||')@github.com/Kiroha/byd-dashboard.git feature/api29-upgrade
```

---

## Version courante & historique

| Version | versionCode | Commit | Fix |
|---------|-------------|--------|----- |
| **2.05** | **110** | `f686f0a` | **4 bugs** : (1) **Mirror** : `SurfaceControl.createDisplay()` retourne null (ACCESS_SURFACE_FLINGER non accordé pour notre UID sur ce ROM) → fallback `screencap -d 1` via ADB (uid=2000 = accès SurfaceFlinger garanti). `AdbLocalClient.captureClusterDisplay()` + `startScreenshotLoop()` + `ImageView cluster_mirror_screenshot` dans layout. ~1 fps, suffisant pour saisie navigation. (2) **savedItem supprimé** : plus de PREF_LAST_APP ni relance auto au démarrage. (3) **Bouton Cluster** : `mMainDisplayPkg` persisté dans `PREF_MAIN_PKG` (SharedPreferences) — survit aux recréations d'Activity. Restauré dans `onCreate()` + `onClusterDisplayConnected()`. (4) **Split** : `am start --bounds` non supporté sur DiLink 3.0 → bounds passées via `--ei bounds_l/t/r/b`. `ClusterTrampolineActivity` lit et applique `ActivityOptions.setLaunchBounds(new Rect(...))` avec fallback sans bounds. |
| **2.04** | **109** | `d0e0526` | **Sanity check** : suppression code mort (`resizeTaskOnDashboard()`, `resizeTask()`, champ `mMainDisplayApp` write-only). Fix bug `ClusterMirrorManager.resolveLayerStack()` : fallback retournait `displayId` (1) au lieu de `displayId<<16` (65536) — correct pour SurfaceFlinger Android 10 / DiLink 3.0. |
| **2.03** | **108** | `62f6da9` | **unlockHiddenApis()** : `VMRuntime.setHiddenApiExemptions(["Landroid/", ...])` appelé dans `MainActivity.onCreate()` — même mécanisme que WindowManagement v1.2 — déverrouille `android.view.SurfaceControl` (@hide). `createDisplay(name, false)` puis fallback `secure=true` si null (WindowManagement utilise true sur DiLink 3.0). |
| **2.02** | **107** | `5628173` | **4 bugs** : Freedom force-stop incorrect (nom activity + check fission avant) ; Split "resizeTask not allowed" → force-stop + `launchOnDashboardWithBounds` ; bouton "→ Cluster" manquant après "← Principal" (`mMainDisplayPkg` + `btn_to_cluster`) ; miroir noir (`createDisplay→null`) → FrameLayout SurfaceView + placeholder. |
| **2.01** | **106** | `e3bd4e2` | `startFreedom()` : écrit `navigationType=1` dans `properties.xml` via `ObjectOutputStream` (HashMap Java sérialisé) au lieu de `rm` — corrige Freedom qui démarrait en mode 关闭 (navigationType=0 = défaut sans fichier lance BootReceiver.setup() qui retourne immédiatement). |
| **2.00** | **105** | — | Refactor architecture : ClusterService foreground + split 50/50 + `launchTrampolineWithBounds` + `resizeTask` (AM stack). |
| 1.91 | 96 | `db48f30` | **Miroir SurfaceControl temps réel** : `ClusterMirrorManager` réécrit — remplace screenshot bitmap (~2.5 fps) par `SurfaceControl.createDisplay + setDisplayLayerStack + setDisplayProjection` → miroir instantané du cluster dans une `SurfaceView` sur l'écran principal. `ImageView → SurfaceView` dans `activity_main.xml`. Mapping touch recalculé avec le même ratio/offset que la projection. **Freedom reset 全屏** : `startFreedom()` force-stop + supprime `properties.xml` (reset `navigationType=0` = 全屏导航) avant chaque démarrage. **FloatingLogButton debug only** : `BuildConfig.DEBUG` guard dans `MainActivity.onCreate()`. | **Suppression diagnostic v1.74**. Retiré l'appel à `dumpClusterRoutingState()` dans `ClusterService.onCreate()` ET la méthode elle-même dans `AdbLocalClient.java`. Le brute-force sendInfo(1000, N) — même restreint aux codes sûrs — est inutile depuis v1.75 puisqu'on a identifié la vraie cause (OWN_CONTENT_ONLY) et qu'on crée déjà notre VirtualDisplay owned. Évite tout impact potentiel sur le combiné en marche. Délai probe v1.75 réduit de 8 s → 4 s (puisque routedump 6 s n'est plus là). |
| 1.75 | 77 | `` | **VirtualDisplay owned par notre app**. Constat user : on crée déjà le display (id=1 fission_bg_xdjaVirtualSurface) mais rien ne s'affiche. **Vraie cause identifiée** : `AutoDisplayService` (uid system, com.xdja.containerservice) crée ce VirtualDisplay avec `flags=11 = PUBLIC|OWN_CONTENT_ONLY|PRESENTATION`. Le flag **OWN_CONTENT_ONLY (=2)** empêche les apps non-owner de lancer des activités dessus → nos `setLaunchDisplayId(1)` lancent l'activité mais elle n'est PAS rendue dans la Surface Qt. **Solution** : récupérer la même Surface Qt via `ContainerService.getQtProjectionDispInfoNative(0)` (JNI dans `libxdjacontainerservice_jni.so`) et créer NOTRE PROPRE VirtualDisplay sans OWN_CONTENT_ONLY (`flags=PUBLIC|PRESENTATION=9`). Notre app devient owner → activités tierces rendent dessus → Surface Qt reçoit → dalle combiné affiche. Codes 16/17/18 confirmés = full/half/off projection (= Freedom 全屏/小屏/关闭导航), on envoie déjà cmd 16. **Nouveaux fichiers** : `com/xdja/containerservice/QtDisplayInfo.java` + `com/xdja/containerservice/ContainerService.java` (stubs avec mêmes signatures pour binding JNI), `com/byd/myapp/dashboard/ClusterSurfaceProbe.java` (`ensureLibLoaded()` + `dumpAllQtSurfaces()` + `createOwnedClusterDisplay()` + `release()`). `ClusterService` : appel probe 8 s après boot, `mOwnedClusterDisplayId` prioritaire sur `mDisplayHelper`, `release()` dans onDestroy. **Risques** : (1) la lib JNI peut faire un check UID interne et refuser uid 10100 → null, on saura dans le log. (2) si on crée en parallèle d'AutoDisplayService, conflit possible — le log révélera. |
| 1.74.1 | 76 | `` | **Sécurisation brute-force**. Trouvé la liste officielle BYD des codes type=1000 dans `decompiled/clusterdebug/sources/com/byd/clusterdebug/SecondActivity.java`. Codes DANGEREUX exclus : 2 (allume tous warning lights = fausses alertes), 3 (éteint tous warning lights = masque vraies pannes), 41 (stress test, BYD dit "实车上请勿点击"), 91/92 (SIGABRT/SIGSEGV forcés). Codes perturbateurs exclus : 1, 17, 18, 29, 31 (modifient affichage/résolution combiné). Brute-force restreint aux codes 100% sûrs : 15 (FPS off), 20 (OSD off), 40 (dump info), 50 (CPU/RAM overlay), puis 5 (mode warnings normal = reset) et 0 (restore flux vidéo = reset final). Délai entre commandes augmenté à 300 ms. |
| 1.74 | 75 | `` | **Diagnostic routing display cluster**. Constat v1.73 : trampoline + Crunchyroll lancée OK sur display=1 (`fission_bg_xdjaVirtualSurface`) mais RIEN ne s'affiche sur la dalle combiné. Le `_bg_` indique un VirtualDisplay background placeholder NON routé vers la dalle physique. User confirme que Freedom v1.9 (com.xdja.clusterdemo, qui est WindowManagement BYD repackagé) réussit l'affichage cluster quand on coche "全屏导航". Code Freedom protégé (DES/ECB + stéganographie Unicode whitespace + réflexion massive) → impossible à RE statiquement. Nouvelle méthode `AdbLocalClient.dumpClusterRoutingState()` lancée 6 s après boot via mMainHandler : dumpsys display + SurfaceFlinger --display-id + service list (auto/display/surface/xdja/byd) + cat /system/etc/container_comm_cfg.json (whitelist AutoContainer) + ps -ef (daemon Freedom tournant ?) + dumpsys package com.xdja.clusterdemo + brute-force sendInfo(1000, N, "") pour N ∈ {0,1,2,3,5,10,15,17,18,20,29,31,32,40,50}. À tester d'abord SANS Freedom "全屏导航" actif, puis APRÈS l'avoir coché → diff révèle le routing. Pattern WindowManagement c0/j.java case 12 : `forceStopPackage + IActivityManager.startActivityAsUser(opts.setLaunchDisplayId(N), USER_CURRENT)` exécuté dans un daemon app_process spawné via ADB. |
| 1.73 | 74 | `` | **Trampoline exporté + lancement via ADB shell**. Confirmé par dump v1.72 : nos signatures (b4addb29) ≠ ROM (22216e4d), donc INTERNAL_SYSTEM_WINDOW non accordée. Le check ADB du dump a révélé que `am start --display 1 ClusterTrampolineActivity` échouait avec `not exported from uid 10100` (PAS launchDisplayId !) car le trampoline était `exported=false`. Solution : `exported=true` + lancement via ADB shell uid=2000 (qui possède INTERNAL_SYSTEM_WINDOW dans platform.xml de cette ROM). C'est le pattern qui fonctionnait le 12 avril avec BYDDashboardActivity (aussi exported=true). Nouvelle méthode `AdbLocalClient.launchTrampolineViaAdb()`. ClusterService bypass complet de mLauncher.launchOnDashboard (Context.startActivity) qui échouait toujours. |
| 1.72 | 73 | `` | Diagnostic dump complet : signatures, perms, build.tags. Révèle mismatch keystore + révèle que test direct uid=2000 échoue sur "not exported from uid 10100" (clef pour v1.73). |
| 1.70 | 71 | `` | Tentative fallback ADB shell `am start --display N` (uid=2000) — ÉCHEC : uid=2000 sur ROM Seal EU n'a pas non plus MANAGE_ACTIVITY_STACKS (contrairement à ce qui était documenté). |
| 1.69 | 70 | — | Tentative `pm grant MANAGE_ACTIVITY_STACKS` — ÉCHEC : signature\|privileged, "not a changeable permission type" |
| 1.68 | 69 | `8393ffd` | Suppression du check `isDashboardAvailable()` dans `onSendToDashboard()` — l'état interne displayId n'est pas fiable sur DiLink 3.0 ; le fallback ADB relay display=1 gère tous les cas |
| 1.67 | 68 | `746aee2` | Sanity check 7 |
| 1.62 | 63 | `24fcad7` | Sanity check 3 — `Log.*` → `AppLogger` dans ClusterManager/DashboardDisplayHelper/DashboardLauncher (journal in-app), `sendInfo` try-with-resources, imports MainActivity |
| 1.61 | 62 | `e8b0eee` | Sanity check 2 — race `ClusterMirrorManager`, boucle infinie `FloatingLogButton`, TOCTOU clés ADB, dead code `onRebind`, imports orphelins |
| 1.60 | 61 | `2687936` (tag: `apres-sanity-check`) | Sanity check 1 — dadb leak `connectAndGrant`, `AzureLogExporter` thread-safety + JSON escaping + `conn.disconnect` finally, `MainActivity` Log.i doublon, manifest `screenOrientation` |
| 1.59 | 60 | `7d26f79` (tag: `v1.59`) | Bouton 'Cluster d'origine' + settings taille cluster + fix restore sequence + suppression auto-activation |
| ~1.52 | — | `dd5edcb` | TEST 12 — boutons individuels 8.8"/12.3"/10.25" + restauration |
| ~1.51 | — | `b8107f7` | fix(log) — auto-grant `SYSTEM_ALERT_WINDOW` via ADB relay si non accordée |
| 1.50 | 51 | — | `MANAGE_ACTIVITY_STACKS` dans manifest → setLaunchDisplayId pour apps TIERCES (Navigation) |
| 1.46 | 47 | — | Séquence Seal EU : cmd30 (screen size) AVANT cmd16 → corrige stretching + bug ADAS (confirmé voiture) |
| 1.44 | 45 | — | TEST 12 : sonde taille display cluster (cmd 29/30/31 + wm size) |
| 1.43 | 44 | `0c3e4c1` | Sanity check, dead code removal |
| 1.34 | 35 | `1ffe19e` | TEST 10 validé ✅ : retrait sendInfo(53), séquence simplifiée |
| 1.29 | 30 | `8da5160` | Speed/gear `--`/ERR : suppr. checkSelfPermission guard + getInstance() direct en try/catch |
| 1.28 | 29 | `037cc0c` | TEST 10 : ajout sendInfo(53) avant/après projection |
| 1.27 | 28 | `0973ace` | ADAS window stretching : makeCustomAnimation(0,0) + FLAG_NO_ANIMATION + window flags + CMD 53 |

---

## Architecture du code

| Fichier | Rôle |
|---|---|
| `DiagActivity.java` | UI des tests de diagnostic (TEST 5 à TEST 10) |
| `AdbLocalClient.java` | Toute la logique ADB locale via `dadb` (localhost:5555) |
| `AppLogger.java` | Journal singleton (Level enum, Entry, 3000 entrées, throwable overloads, saveToFile) |
| `LogActivity.java` | UI journal temps réel (filtre, couleurs, auto-scroll, share+Azure, 500ms) |
| `FloatingLogButton.java` | Service overlay flottant (tap=LogActivity, long press=clear) |
| `AzureLogExporter.java` | Export HTTP Data Collector → Azure Log Analytics (HMAC-SHA256) |
| `dashboard/BYDDashboardActivity.java` | Activity affichée sur le cluster (display 1) |
| `dashboard/ClusterManager.java` | Binder direct vers service `AutoContainer` (AIDL) |
| `dashboard/DashboardLauncher.java` | Lance BYDDashboardActivity sur display 1 |
| `dashboard/DashboardDisplayHelper.java` | Détecte le display cluster via DisplayManager |
| `dashboard/DashboardPresentation.java` | Presentation Android sur display secondaire |
| `dashboard/ClusterInputForwarder.java` | Injection d'events input vers le cluster |
| `dashboard/ClusterMirrorManager.java` | Capture screenshot display 1 via SurfaceControl (MirrorDaemon)|
| `daemon/MirrorDaemon.java` | C++ daemon app_process via ADB pour injection native `SurfaceControl` et Socket UDP Tactile (0 latence) |

---

## Service AutoContainer (cluster) — CONFIRMÉ en voiture

- Binder : `ServiceManager.getService("AutoContainer")`
- Interface AIDL : `android.os.IAutoContainer`
- Transaction #2 = `sendInfo(int type, int infoInt, String infoStr)`

### Commandes type=1000 (clusterdebug SecondActivity)

| cmd | Signification | Usage |
|-----|--------------|-------|
| 0   | 主机恢复仪表视频流 — rafraîchir flux vidéo Qt | Après cmd 18 |
| 1   | 主机断开仪表视频流 — déconnecter Qt (Simple mode) | ⚠️ **NE PAS UTILISER** — détruit display 1 |
| 12  | 显示Adas | Sans effet sur cluster 2D Seal EU |
| 13  | 关闭Adas | Sans effet sur cluster 2D Seal EU |
| 16  | 全屏投屏开启 — **ACTIVER projection plein écran** | ✅ Confirmé en voiture |
| 17  | 半屏投屏开启 — activer demi-écran | Non testé |
| 18  | 投屏关闭 — **FERMER la projection** | ✅ Confirmé en voiture |
| 29  | 切换到8.8寸屏 — cluster 8.8" (Atto3, Dolphin...) | ❌ pas le Seal EU |
| 30  | 切换到12.3寸屏 — cluster 12.3" **Seal EU** | ✅ CONFIRMÉ 16/04/2026 |
| 31  | 切换到10.25寸屏 — cluster 10.25" (Seal U DMI...) | Non testé sur Seal EU |

### Séquence ACTIVATION — CONFIRMÉE Seal EU (16/04/2026)

```
sendInfo(1000, 30)   → passer cluster en mode Seal EU 12.3" (bonne résolution)
attendre ~1s
sendInfo(1000, 16)   → Qt standby (全屏投屏开启)
attendre ~2s
startActivity sur display 1 (FREEFORM mode, ActivityOptions.setLaunchDisplayId)
```

Implémenté dans `ClusterManager.activateClusterDisplay()` + `AdbLocalClient.runDisplayOneLaunch()`.

### Séquence RESTAURATION — CONFIRMÉE

```
BYDDashboardActivity.finishIfActive()   → libère la surface
sendInfo(1000, 18)   → 投屏关闭 — fermer projection ✅
sendInfo(1000, 0)    → 主机恢复仪表视频流 — rafraîchir flux Qt ✅
```

### Équivalent shell (debug)

```bash
service call AutoContainer 2 i32 1000 i32 16 s16 ""   # activer
service call AutoContainer 2 i32 1000 i32 18 s16 ""   # restaurer
```

---

## Permissions BYD — CONFIRMÉ en voiture (12-13/04/2026)

- `_COMMON` : type **dangerous** → `pm grant` retourne OK silencieusement
  - ⚠️ **MAIS** : `dumpsys package grants` ne montre que 9/12 → **SPEED_COMMON et GEARBOX_COMMON pas réellement accordées malgré le OK de pm grant**
  - Retour de `checkSelfPermission` = NOT_GRANTED → `getInstance()` retourne null → vitesse `--`, rapport `ERR`
  - Fix v1.29 : supprimer la garde `checkSelfPermission`, appeler `getInstance()` directement en `try/catch`
- `_GET` : type **signature** → `pm grant` refusé (expected)
- `INJECT_EVENTS` : signature — accordée si APK signé avec platform.keystore ✓
- `INTERNAL_SYSTEM_WINDOW` : signature — accordée avec platform.keystore (requis pour lancer sur display 1)
- `MANAGE_ACTIVITY_STACKS` : signature|privileged — accordée avec platform.keystore ; **requis pour setLaunchDisplayId sur apps tierces** (v1.50+)
- **setprop `persist.sys.acc.whitelist`** : refusé sur ROM Seal EU (propriété protégée)

### 12 permissions COMMON à accorder

```
AC, BODYWORK, DOOR_LOCK, ENGINE, ENERGY, GEARBOX, INSTRUMENT, LIGHT, RADAR, SAFETYBELT, SPEED, TYRE
```

(Toutes dans `COMMON_PERMS` array dans MainActivity, BYDLiveActivity, AdbLocalClient)

---

## Fixes importants appliqués

| Date | Bug | Fix |
|---|---|---|
| 12/04 | TEST 10 : crash app lors du lancement | Retiré `-S` de `am start-activity` |
| 12/04 | TEST 5 : _GET toujours refusé | Séparé test _COMMON vs _GET |
| 12/04 | COMMON_PERMS arrays incomplets | Étendus à 12 partout |
| 12/04 | `am start-activity --display 1` → SecurityException uid=2000 | Remplacé par `context.startActivity()` + `ActivityOptions.setLaunchDisplayId(1)` réflexion |
| 12/04 | display 1 = 1920×1080 faux | Hardcodé 1920×480 si mDisplayId=1 absent |
| 13/04 | ADAS stretch/expand pendant projection | `makeCustomAnimation(0,0)` + `FLAG_NO_ANIMATION` + window flags (v1.27) |
| 13/04 | Boutons Activer/Restaurer | `isDashboardAvailable()` → `mCurrentDashboardApp != null` (v1.26) |
| 13/04 | Speed `--`, Gear `ERR` | Suppr. checkSelfPermission guard, getInstance() direct try/catch (v1.29) |
| 16/04 | ADAS overlay stretch + mauvaise résolution cluster | cmd30 AVANT cmd16 dans séquence activation (v1.46) |
| 16/04 | SecurityException pour apps tierces (Navigation) sur display 1 | `MANAGE_ACTIVITY_STACKS` permission ajoutée au manifest (v1.50) |

---

## Stratégie permissions — ordre de priorité des approches

### Approche actuelle (v1.29) — PRIORITAIRE

Supprimer toute garde `checkSelfPermission` avant `getInstance()` et laisser le SDK gérer :

```java
// BYDDashboardActivity.initDevices()
try { mSpeedDevice   = BYDAutoSpeedDevice.getInstance(this);   } catch (Exception ignored) {}
try { mEnergyDevice  = BYDAutoEnergyDevice.getInstance(this);  } catch (Exception ignored) {}
try { mGearboxDevice = BYDAutoGearboxDevice.getInstance(this); } catch (Exception ignored) {}
```

### Si speed reste `--` en voiture après v1.29 → **Approche Overdrive (v1.30)**

Source : décompilation `overdrive-release-alpha-v8.5.apk` (SentryDaemon, AccSentryDaemon, CameraDaemon)

`PermissionBypassContext` = `ContextWrapper` qui fake `PERMISSION_GRANTED (0)` sur tous les checks internes du SDK :

```java
private static class PermissionBypassContext extends ContextWrapper {
    PermissionBypassContext(Context base) { super(base); }
    @Override public int checkSelfPermission(String p)               { return 0; }
    @Override public int checkPermission(String p, int pid, int uid) { return 0; }
    @Override public int checkCallingOrSelfPermission(String p)      { return 0; }
    @Override public void enforceCallingOrSelfPermission(String p, String m) {}
    @Override public void enforceCallingPermission(String p, String m)       {}
    @Override public void enforcePermission(String p, int pid, int uid, String m) {}
}

// Usage dans initDevices() :
Context bypassCtx = new PermissionBypassContext(this);
try { mSpeedDevice   = BYDAutoSpeedDevice.getInstance(bypassCtx);   } catch (Exception ignored) {}
try { mGearboxDevice = BYDAutoGearboxDevice.getInstance(bypassCtx); } catch (Exception ignored) {}
try { mEnergyDevice  = BYDAutoEnergyDevice.getInstance(bypassCtx);  } catch (Exception ignored) {}
```

**Pourquoi ça fonctionne** : le SDK BYD appelle `context.checkSelfPermission()` *en interne* dans `getInstance()`. Si le Context passé retourne toujours 0, le check est contourné.

**Différence avec BydAgent** : BydAgent n'override que 2 méthodes (`enforceCallingOrSelfPermission` + `checkCallingOrSelfPermission`), **pas `checkSelfPermission`** — ce qui est insuffisant pour le SDK BYD. Approche Overdrive = 6 méthodes overridées = complète.

### En dernier recours absolu → Pattern BydAgent

Source : `external_code/BydAgent.java` (reçu d'un tiers)

N'utiliser QUE si l'approche Overdrive ne suffit pas ET que les permissions `pm grant` sont durablement bloquées :

```java
Class<?> at = Class.forName("android.app.ActivityThread");
Object thread = at.getMethod("systemMain").invoke(null);
Context sysCtx = (Context) at.getMethod("getSystemContext").invoke(thread);
// Prérequis : android:sharedUserId="android.uid.system" dans AndroidManifest.xml
```

---

## Tests diagnostics (DiagActivity / AdbLocalClient)

| Test | Fonction | État |
|---|---|---|
| TEST 5 | `runAdbPermissionsSetup()` — setprop whitelist + pm grant _COMMON/_GET | Résultats connus (voir section permissions) |
| TEST 7 | `runAutoServiceProbe()` — sonder android.gui.BYDAutoServer | — |
| TEST 8 | `runClusterActivation()` — sendInfo via ClusterManager | — |
| TEST 9 | `runVirtualDisplayProbe()` — polling DisplayManager | — |
| TEST 10 | `runDisplayOneLaunch()` — sendInfo(30+16) + startActivity display 1 | **✅ VALIDÉ en voiture** (v1.34+) |

### TEST 10 séquence actuelle (v1.46+)

1. `sendInfo(1000, 30)` — passer cluster en mode Seal EU 12.3"
2. Attendre ~1s
3. `sendInfo(1000, 16)` — Qt standby
4. Attendre ~2s
5. `startActivity` + `setLaunchDisplayId(displayId)` → lancement sur cluster

**Restauration :**
1. `BYDDashboardActivity.finishIfActive()`
2. `sendInfo(1000, 18)` — fermer projection
3. `sendInfo(1000, 0)` — rafraîchir flux Qt

---

## Règles BYD critiques

1. Permissions `BYDAUTO_*_COMMON` → `pm grant` AVANT `getInstance()` si possible
2. `getInstance(Context)` → toujours vérifier `!= null`
3. APK signé avec `platform.keystore` (obligatoire pour toute permission `signature`)
4. Listeners : register dans `onResume()`, unregister dans `onPause()`
5. `ctrl_source` AC : `AC_CTRL_SOURCE_VOICE` ou `AC_CTRL_SOURCE_UI_KEY`
6. Ne jamais utiliser `cmd=1` (AutoContainer) → détruit display 1
7. Toujours envoyer `cmd=30` (taille écran Seal EU) AVANT `cmd=16` (projection)
8. `MANAGE_ACTIVITY_STACKS` est obligatoire pour lancer des apps tierces sur display 1

---

## 20 packages SDK disponibles

```
ac, bodywork, charging, doorlock, energy, engine, gearbox, instrument,
light, multimedia, panorama, pm2p5, radar, safetybelt, sensor, setting,
speed, statistic, time, tyre
```

---

## Logging et observabilité

### AppLogger

- Niveaux : DEBUG / INFO / WARN / ERROR
- Buffer : CopyOnWriteArrayList, MAX_ENTRIES=3000
- `saveToFile(Context)` → `byd_log_YYYYMMDD_HHmmss.log` dans `getExternalFilesDir(null)`
- Récupérable sans câble : `adb pull /sdcard/Android/data/com.byd.myapp/files/`
- `share(Context)` : FileProvider + EXTRA_STREAM + push Azure simultané

### Azure Log Analytics

- **Workspace** : `law-byd-app`, francecentral
- **WorkspaceId** : `40664deb-c8ca-46f9-aa96-84ffc50d33c7`
- **Table** : `BYDAppLog_CL`
- **Auth** : HMAC-SHA256 SharedKey, clé dans `local.properties` → `BuildConfig.AZURE_PRIMARY_KEY`
- **Colonnes** : `TimeGenerated`, `Level_s`, `Tag_s`, `Message_s`, `Thread_s`, `DeviceModel_s`, `AppVersion_s`

### Requêtes KQL utiles

```kql
BYDAppLog_CL | order by TimeGenerated desc | take 200
BYDAppLog_CL | where Level_s in ("WARN","ERROR") | order by TimeGenerated desc
BYDAppLog_CL | where Tag_s in ("DashboardLauncher","ClusterManager","AdbLocalClient")
```

---

## Remise en route sur un nouveau poste

```bash
# 1. Cloner
git clone https://TOKEN@github.com/Kiroha/byd-dashboard.git
cd byd-dashboard
git checkout feature/api29-upgrade

# 2. local.properties (non versionné — à recréer)
cat > MyBYDApp/local.properties << EOF
sdk.dir=/PATH/TO/byd-auto_sdk_windows
azure.workspace.id=40664deb-c8ca-46f9-aa96-84ffc50d33c7
azure.primary.key=AZURE_KEY_ICI
EOF

# 3. Build
cd MyBYDApp && ./gradlew assembleDebug

# 4. Installer
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 5. Permissions (à faire une fois sur le device)
adb shell pm grant com.byd.myapp android.permission.BYDAUTO_SPEED_COMMON
adb shell pm grant com.byd.myapp android.permission.BYDAUTO_GEARBOX_COMMON
adb shell pm grant com.byd.myapp android.permission.BYDAUTO_ENERGY_COMMON
# ... (voir COMMON_PERMS array dans code pour la liste complète)
```
