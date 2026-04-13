# BYD Auto App — Contexte projet complet

> Fichier de référence à conserver dans git pour reprise du contexte sur un autre poste ou après compact IA.  
> Dernière mise à jour : 13/04/2026 — v1.29

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
|---------|-------------|--------|-----|
| **1.29** | **30** | `8da5160` | Speed/gear `--`/ERR : suppr. checkSelfPermission guard + getInstance() direct en try/catch |
| 1.28 | 29 | `037cc0c` | TEST 10 : ajout sendInfo(53) avant/après projection |
| 1.27 | 28 | `0973ace` | ADAS window stretching : makeCustomAnimation(0,0) + FLAG_NO_ANIMATION + window flags + CMD 53 |
| 1.26 | 27 | `1d5dd23` | Boutons Activer/Restaurer : isDashboardAvailable() → mCurrentDashboardApp != null |
| 1.25 | 26 | `e1aeb31` | Docs : figer commandes AutoContainer confirmées en voiture |

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
| `dashboard/ClusterMirrorManager.java` | Capture screenshot display 1 via SurfaceControl |

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
| 53  | 2D ADAS切換 — toggle ADAS 2D Seal EU | Non testé en voiture (v1.27) |

### Séquence ACTIVATION

```
sendInfo(1000, 53)   → toggle ADAS (masquer overlay ADAS pendant transition)
sendInfo(1000, 16)   → Qt standby
attendre ~2s
startActivity sur display 1 (FREEFORM mode, ActivityOptions.setLaunchDisplayId)
```

### Séquence RESTAURATION

```
BYDDashboardActivity.finishIfActive()   → libère la surface
sendInfo(1000, 18)   → fermer projection ✅
sendInfo(1000, 0)    → rafraîchir flux Qt ✅
sendInfo(1000, 53)   → restaurer ADAS
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
| TEST 10 | `runDisplayOneLaunch()` — sendInfo(53+16) + am start --display 1 | Fixé v1.27/v1.28 |

### TEST 10 séquence (v1.28)

1. `[Avant]` dump stack display 1
2. `sendInfo(53)` — toggle ADAS 2D *avant* activation
3. `sendInfo(16)` — Qt standby
4. stack display 1 brut
5. `sendInfo(18)` — fermer projection
6. `sendInfo(0)` — rafraîchir flux Qt
7. `sendInfo(53)` — toggle ADAS 2D *après* restauration
8. `[Après]` dump stack display 1
9. Logcat

---

## Règles BYD critiques

1. Permissions `BYDAUTO_*_COMMON` → `pm grant` AVANT `getInstance()` si possible
2. `getInstance(Context)` → toujours vérifier `!= null`
3. APK signé avec `platform.keystore` (obligatoire pour toute permission `signature`)
4. Listeners : register dans `onResume()`, unregister dans `onPause()`
5. `ctrl_source` AC : `AC_CTRL_SOURCE_VOICE` ou `AC_CTRL_SOURCE_UI_KEY`
6. Ne jamais utiliser `cmd=1` (AutoContainer) → détruit display 1

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
