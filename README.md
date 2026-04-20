# MyBYDApp — BYD Cluster Launcher & Mirror

Application Android pour **BYD Seal EU** (DiLink 3.0 — Android 10) permettant d'envoyer
n'importe quelle app sur l'écran instrument cluster, de la contrôler via un miroir tactile
en temps réel, et de diagnostiquer les APIs BYD.

> **Testé sur** : BYD Seal EU 2024 — DiLink 3.0 (XDJA/Qualcomm 6125F) — Android 10 (API 29)

---

## Fonctionnalités

| # | Fonctionnalité | Description |
|---|---|---|
| 1 | **Liste des apps** | Toutes les apps installées (RecyclerView trié) |
| 2 | **→ Cluster** | Envoie une app sur le cluster (trampoline ADB uid=2000 + display=1 FREEFORM) |
| 3 | **→ Écran principal** | Déplace une app du cluster vers le display 0 |
| 4 | **Miroir tactile** | SurfaceView temps réel du cluster via `SurfaceControl.createDisplay()` + touch forwarding |
| 5 | **Split 50/50** | Deux apps côte à côte sur le cluster (force-stop + relaunch avec `--bounds`) |
| 6 | **Contrôle clavier** | Boutons ←/⌂/↑/↓/Vol+/Vol− via `InputManager.injectInputEvent()` |
| 7 | **Restaurer BYD** | `sendInfo(18+0)` → Qt reprend le contrôle du cluster |
| 8 | **Cluster d'origine** | `sendInfo(30+18+0)` → remet la bonne résolution + Qt |
| 9 | **⚙ Paramètres** | Taille écran cluster : 8.8" / 12.3" (défaut Seal EU) / 10.25" |
| 10 | **🔧 Diagnostic** | 4 tests ADB (permissions, restauration cluster, taille display, BootReceiver Freedom) |
| 11 | **📋 Rapport système** | Displays, permissions, build tags, signature APK |
| 12 | **Journal temps réel** | LogActivity — niveaux DEBUG/INFO/WARN/ERROR, filtres,  export |
| 13 | **☁  Export** | Push vers remote log analytics (HMAC-SHA256, table `BYDAppLog_CL`) |
| 14 | **Multilingue** | Français / Anglais, choix au premier lancement |

---

## Architecture du code

```
app/src/main/java/com/byd/myapp/
├── MainActivity.java           — Écran principal 15", liste apps, miroir cluster, split
├── WelcomeActivity.java        — Choix de langue (premier lancement)
├── DiagActivity.java           — Tests 1–4 (ADB, restauration, taille, BootReceiver)
├── SysInfoActivity.java        — Rapport système + partage
├── ClusterService.java         — Foreground service : projection cluster indépendante du cycle de vie
├── AdbLocalClient.java         — Toute la logique ADB (dadb, localhost:5555)
├── AppListAdapter.java         — RecyclerView (→ Cluster / ← Principal / → Cluster / ✕)
├── AppLogger.java              — Journal singleton (niveaux, 3000 entrées, saveToFile, share)
├── LogExporter.java       — HTTP Data Collector → remote log analytics
├── LogActivity.java            — Journal temps réel (filtres, auto-scroll, )
├── FloatingLogButton.java      — Overlay flottant (DEBUG uniquement)
├── LocaleHelper.java           — Persistance langue (SharedPreferences)
└── dashboard/
    ├── ClusterManager.java          — Séquence activation cluster (sendInfo 30+16, fallback Freedom)
    ├── DashboardDisplayHelper.java  — Détection VirtualDisplay cluster (DisplayManager + polling)
    ├── DashboardLauncher.java       — Lancement app sur display principal (setLaunchDisplayId)
    ├── ClusterTrampolineActivity.java — Trampoline exporté lancé via ADB uid=2000 sur display 1
    ├── ClusterMirrorManager.java    — Miroir SurfaceControl (createDisplay + Transaction + touch)
    └── ClusterInputForwarder.java   — Injection MotionEvent/KeyEvent sur le cluster
```

---

## Mécanisme principal

### Activation du cluster

```
sendInfo(1000, 30)   → cluster en mode Seal EU 12.3" (bonne résolution, pas d'étirement ADAS)
attendre ~1 s
sendInfo(1000, 16)   → Qt en standby (全屏投屏开启) — libère la surface pour notre app
attendre ~2 s
am start --display 1 --windowingMode 5 ClusterTrampolineActivity --es target_package <pkg>
```

`sendInfo` est envoyé via **ADB relay** (uid=2000) car notre app (uid=10xxx) est bloquée
par `AutoContainerService.checkSendPermissionAndAllowType()`.

### Lancement d'une app sur le cluster

Le trampoline `ClusterTrampolineActivity` est **exporté** dans le Manifest. ADB shell
(uid=2000) le lance sur `display=1` avec `--windowingMode 5` (FREEFORM). Une fois sur
display 1, le trampoline lance l'app cible via `startActivity()` sans `setLaunchDisplayId`
— la task hérite du display source.

> **Pourquoi un trampoline ?** Notre APK est signé avec la `platform.keystore` SDK BYD
> (CN=Android — AOSP testkey), pas avec la vraie clé `auto_api` BYD (CN=auto_api, O=比亚迪).
> `INTERNAL_SYSTEM_WINDOW` n'est donc pas accordée à notre app (uid=10xxx). ADB shell
> (uid=2000) la possède sur cette ROM → il lance notre trampoline exporté.

### Miroir temps réel

```java
// 1. Déverrouiller les APIs @hide Android (même mécanisme que WindowManagement v1.2)
VMRuntime.setHiddenApiExemptions(["Landroid/", "Lcom/android/", "Ljava/lang/"]);

// 2. Créer un display virtuel miroir
IBinder token = SurfaceControl.createDisplay("byd_cluster_mirror", false);
// fallback secure=true si null (WindowManagement v1.2 utilise true sur DiLink 3.0)

// 3. Projeter le display cluster sur la SurfaceView
SurfaceControl.Transaction tx = new SurfaceControl.Transaction();
tx.setDisplaySurface(token, surfaceViewSurface);
tx.setDisplayLayerStack(token, displayId << 16);  // convention SurfaceFlinger Android 10
tx.setDisplayProjection(token, ROTATION_0, srcRect, dstRect);
tx.apply();
```

Touch : `MotionEvent.setDisplayId(clusterDisplayId)` + `InputManager.injectInputEvent()`.

### Restauration

```
am force-stop <app>                  → libère la surface Qt
am force-stop com.xdja.clusterdemo   → stoppe Freedom (évite qu'il reprenne display 1)
sendInfo(1000, 18)                   → 投屏关闭 — fermer projection
sendInfo(1000, 0)                    → 主机恢复仪表视频流 — Qt reprend
```

---

## Prérequis de build

| Outil | Version |
|---|---|
| JDK | 11 (Temurin recommandé) |
| Android SDK | API 29 compileSdk, **SDK BYD v1.0.5** comme sdk.dir |
| AGP | 7.4.2 |
| Gradle wrapper | 7.6 |

### SDK BYD

Ce projet requiert le SDK BYD v1.0.5 (`android.jar` modifié avec `android.hardware.bydauto.*`).

> Le SDK n'est **pas inclus** dans ce dépôt (propriétaire).  
> Extraire dans : `../sdk/SDK_v1.0.5/byd-auto_sdk_windows/`  
> Configurer `local.properties` :

```properties
sdk.dir=/chemin/vers/sdk/SDK_v1.0.5/byd-auto_sdk_windows
=<remote log analytics workspace ID>
=<clé primaire >
```

### Signature

L'APK doit être signé avec `platform.keystore` (SDK BYD) pour les permissions `signature`
(`INJECT_EVENTS`, `BYDAUTO_*_COMMON`).

```
app/keystore/platform.keystore
  alias: androiddebugkey | storepass/keypass: android
```

La configuration dans `app/build.gradle` applique cette signature pour debug **et** release.

---

## Build

```bash
cd MyBYDApp
./gradlew assembleDebug
# APK → app/build/outputs/apk/debug/app-debug.apk
```

---

## Permissions

| Permission | Type | Usage |
|---|---|---|
| `INJECT_EVENTS` | signature | Injection touch/keys sur le cluster |
| `SYSTEM_ALERT_WINDOW` | dangerous | Overlay flottant (FloatingLogButton) |
| `FOREGROUND_SERVICE` | normal | ClusterService |
| `INTERNET` | normal | remote log analytics export |
| `BYDAUTO_*_COMMON` (×11) | dangerous | Données véhicule (speed, energy, gearbox…) |
| `BYDAUTO_*_GET` | signature | Lecture étendue (non accordable sans vraie clé BYD) |

Les permissions `dangerous` sont accordées via `pm grant` au premier lancement (TEST 1 — Diagnostic).

---

## Service AutoContainer (cluster)

- Binder : `ServiceManager.getService("AutoContainer")`
- Transaction `#2` = `sendInfo(int type, int infoInt, String infoStr)`
- Accès via ADB relay : `service call AutoContainer 2 i32 1000 i32 <cmd> s16 ""`

| cmd | Action | Confirmé |
|-----|--------|---------|
| 30 | Cluster 12.3" Seal EU | ✅ 16/04/2026 |
| 16 | Qt standby (activer projection) | ✅ 16/04/2026 |
| 18 | Fermer projection | ✅ 16/04/2026 |
| 0  | Rafraîchir flux Qt | ✅ |
| 1  | **⛔ NE PAS UTILISER** — déconnecte Qt entièrement (détruit display 1) | — |

---

## Freedom (com.xdja.clusterdemo)

Freedom est démarré automatiquement si le VirtualDisplay cluster (`fission_*`) est absent.  
`AutoDisplayService` (com.xdja.containerservice) crée le VirtualDisplay au boot :
```
createVirtualDisplay("fission_testVirtualSurface", 1920, 1080, 320, qtSurface, 11)
flags 11 = PUBLIC | PRESENTATION | OWN_CONTENT_ONLY
```

Fichier de config Freedom :
```
/sdcard/Android/data/com.xdja.clusterdemo/data/properties.xml
```
HashMap Java sérialisé (ObjectOutputStream) : `{"navigationType": Integer(1)}`  
→ `navigationType=1` = 全屏导航 (plein écran). Valeur par défaut (fichier absent) = 0 →
Freedom retourne immédiatement sans créer le VirtualDisplay.

---

## Diagnostic terrain

1. **TEST 1** → connexion ADB + `pm grant` permissions `_COMMON`
2. **TEST 2** → restauration cluster (sendInfo 30→16→18→0)
3. **TEST 3** → changement taille display cluster (cmd 29/30/31)
4. **TEST 4** → broadcast BOOT_COMPLETED vers BootReceiver Freedom (headless)

### Récupérer les logs sans câble

```bash
adb pull /sdcard/Android/data/com.byd.myapp/files/
```

### Requêtes KQL  (workspace `law-byd-app`, francecentral)

```kql
BYDAppLog_CL | order by TimeGenerated desc | take 200
BYDAppLog_CL | where Level_s in ("WARN","ERROR") | order by TimeGenerated desc
BYDAppLog_CL | where Tag_s in ("ClusterMirrorManager","AdbLocalClient","ClusterManager")
```

---

## Historique versions

| Version | versionCode | Résumé |
|---------|-------------|--------|
| **2.04** | 109 | Sanity check : suppression code mort + fix `resolveLayerStack()` (`displayId<<16`) |
| **2.03** | 108 | `unlockHiddenApis()` VMRuntime + `createDisplay` fallback `secure=true` |
| **2.02** | 107 | Fix Freedom (activité + check fission), split relaunch bounds, btn → Cluster, miroir placeholder |
| **2.01** | 106 | `startFreedom()` : écriture `navigationType=1` via ObjectOutputStream |
| **2.00** | 105 | Freedom headless (retour auto premier plan), refactor ClusterService foreground |
| 1.94 | 99 | Split 50/50 cluster (`launchTrampolineWithBounds`) |
| 1.91 | 96 | Miroir SurfaceControl temps réel (remplace screenshot bitmap), Freedom reset 全屏 |
| 1.73 | 74 | Trampoline exporté + lancement via ADB uid=2000 (contourne INTERNAL_SYSTEM_WINDOW) |
| 1.46 | 47 | Séquence cmd30 avant cmd16 — corrige étirement ADAS |
| 1.34 | 35 | TEST 10 validé en voiture ✅ |
