# MyBYDApp — BYD Instrument Cluster Launcher

Application Android pour véhicules **BYD Seal / Atto / Han** permettant d'envoyer n'importe quelle app installée sur l'écran instrument cluster (derrière le volant), de la contrôler à distance, et de diagnostiquer les APIs BYD en temps réel.

> **Testé sur** : BYD Seal EU 2024 — DiLink 3.0 — Android 10 (API 29)

---

## Fonctionnalités

| # | Fonctionnalité | Description |
|---|---|---|
| 1 | **Liste des apps** | Toutes les apps installées (RecyclerView trié) |
| 2 | **→ Dashboard** | Envoie une app sur le cluster via `setLaunchDisplayId` (réflexion) |
| 3 | **→ Écran principal** | Renvoie une app sur le display 0, restaure le cluster BYD automatiquement |
| 4 | **Restaurer BYD** | HOME intent sur le display secondaire → rétablit l'interface BYD d'origine |
| 5 | **Panel contrôle cluster** | Touchpad + boutons (←/⌂/↑/↓/Vol+/Vol−) via `InputManager.injectInputEvent()` |
| 6 | **⚙ Diagnostic** | 6 tests — Presentation API, réflexion, ADB TCP, sendInfo AutoContainer, lancement réel sur cluster |
| 7 | **📋 Rapport système** | Rapport texte complet (displays, permissions, APIs BYD) + sauvegarde + partage |
| 8 | **📊 BYD Live** | Données véhicule temps réel (vitesse, rapport, mode énergie, regen) + journal |
| 9 | **Journal (LogActivity)** | Log en temps réel par niveau (DEBUG/INFO/WARN/ERROR), filtres, auto-scroll |
| 10 | **☁ Azure Export** | Push log vers Azure Log Analytics (HMAC-SHA256) — table `BYDAppLog_CL` |
| 11 | **Partager .log** | Génère un fichier `.log` horodaté en pièce jointe + push Azure simultané |
| 12 | **Multilingue** | Français / Anglais, choix au premier lancement, modifiable via 🌐 |

---

## Architecture

```
app/src/main/java/com/byd/myapp/
├── MainActivity.java           — Écran principal 15", liste apps, barre statut
├── WelcomeActivity.java        — Premier lancement — choix de la langue
├── DiagActivity.java           — Tests de compatibilité et diagnostic cluster
├── SysInfoActivity.java        — Rapport système complet + partage
├── BYDLiveActivity.java        — Données BYD en temps réel + AppLogger
├── LogActivity.java            — Journal temps réel (niveaux, filtres, Azure)
├── FloatingLogButton.java      — Service overlay flottant (tap=log, long press=clear)
├── AzureLogExporter.java       — Export HTTP Data Collector → Azure Log Analytics
├── AppLogger.java              — Journal singleton (niveaux, throwable, saveToFile, share)
├── AppListAdapter.java         — Adapter RecyclerView (2 actions par ligne)
├── LocaleHelper.java           — Persistance locale (SharedPreferences)
├── model/
│   └── AppInfo.java
└── dashboard/
    ├── DashboardLauncher.java       — setLaunchDisplayId via réflexion
    ├── DashboardDisplayHelper.java  — Détection display secondaire
    ├── DashboardPresentation.java   — Presentation API (fallback)
    ├── BYDDashboardActivity.java    — Widget BYD (vitesse/batterie/rapport)
    ├── ClusterManager.java          — Binder direct AutoContainer (AIDL)
    ├── ClusterMirrorManager.java     — Capture screenshot display 1
    └── ClusterInputForwarder.java   — Injection MotionEvent/KeyEvent sur le cluster
```

---

## Prérequis

| Outil | Version |
|---|---|
| JDK | 11 (Temurin recommandé) |
| Android SDK | API 25 — **BYD modifié** (voir ci-dessous) |
| Gradle | 6.9.4 |
| AGP | 3.6.4 |

### SDK BYD

Ce projet requiert le SDK Android BYD v1.0.5 (`android.jar` modifié avec les APIs `android.hardware.bydauto.*`).

> Le SDK BYD **n'est pas inclus** dans ce dépôt (propriétaire).  
> Placer le SDK extrait dans : `../sdk/SDK_v1.0.5/byd-auto_sdk_windows/`  
> Puis configurer `local.properties` :

```properties
sdk.dir=/chemin/vers/sdk/SDK_v1.0.5/byd-auto_sdk_windows
```

---

## Signature

L'APK **doit** être signé avec la `platform.keystore` du système BYD pour obtenir les permissions système (`INJECT_EVENTS`, `BYDAUTO_*`).

La keystore n'est **pas incluse** dans ce dépôt. Copier le fichier dans :

```
app/keystore/platform.keystore
```

Paramètres de signature (`app/build.gradle`) :
```groovy
signingConfigs {
    bydPlatform {
        storeFile     file('./keystore/platform.keystore')
        storePassword 'android'
        keyAlias      'androiddebugkey'
        keyPassword   'android'
    }
}
```

---

## Build

```bash
export JAVA_HOME=/path/to/jdk11
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew assembleDebug
# APK → app/build/outputs/apk/debug/app-debug.apk
```

---

## Mécanisme principal

L'envoi d'une app sur le cluster repose sur la méthode `@hide` d'AOSP :

```java
ActivityOptions opts = ActivityOptions.makeBasic();
Method m = ActivityOptions.class.getDeclaredMethod("setLaunchDisplayId", int.class);
m.setAccessible(true);
m.invoke(opts, clusterDisplayId);
context.startActivity(launchIntent, opts.toBundle());
```

Fonctionne sans root depuis une app signée `platform.keystore`.

Le **contrôle du cluster non tactile** injecte des événements via :

```java
InputManager im = (InputManager) context.getSystemService(Context.INPUT_SERVICE);
Method inject = InputManager.class.getDeclaredMethod("injectInputEvent",
        InputEvent.class, int.class);
inject.invoke(im, motionEvent, 0 /* INJECT_INPUT_EVENT_MODE_ASYNC */);
```

---

## APIs BYD utilisées

| Package | Device | Usage |
|---|---|---|
| `bydauto.speed` | `BYDAutoSpeedDevice` | Vitesse temps réel (km/h) |
| `bydauto.energy` | `BYDAutoEnergyDevice` | Mode énergie (EV/HEV/FUEL), puissance regen |
| `bydauto.gearbox` | `BYDAutoGearboxDevice` | Rapport de boîte (P/R/N/D/S/M) |

Permission requise avant `getInstance()` :
```xml
<uses-permission android:name="android.permission.BYDAUTO_SPEED_COMMON"/>
<uses-permission android:name="android.permission.BYDAUTO_ENERGY_COMMON"/>
<uses-permission android:name="android.permission.BYDAUTO_GEARBOX_COMMON"/>
```

---

## Permissions AndroidManifest

```xml
<uses-permission android:name="android.permission.INJECT_EVENTS"/>
<uses-permission android:name="android.permission.BYDAUTO_SPEED_COMMON"/>
<uses-permission android:name="android.permission.BYDAUTO_SPEED_GET"/>
<uses-permission android:name="android.permission.BYDAUTO_ENERGY_COMMON"/>
<uses-permission android:name="android.permission.BYDAUTO_ENERGY_GET"/>
<uses-permission android:name="android.permission.BYDAUTO_GEARBOX_COMMON"/>
<uses-permission android:name="android.permission.BYDAUTO_GEARBOX_GET"/>
```

---

## Diagnostic terrain (sans câble USB)

1. Ouvrir **⚙ Diagnostic** → lancer TEST 10 → vérifier que le cluster passe en mode app
2. Ouvrir **📊 BYD Live** → vérifier que les 3 devices retournent ✓
3. Ouvrir **Journal** (badge flottant ou menu) → vérifier les événements lifecycle
4. Bouton **☁ Azure** → `"✅ N entrées envoyées (HTTP 200)"`
5. Bouton **Partager** → share chooser + fichier `.log` en pièce jointe + push Azure simultané
6. Si problème → **📋 Rapport** → **📤 Partager** → envoyer par email/WhatsApp

### Récupérer les logs sans ouvrir l'app
```bash
adb pull /sdcard/Android/data/com.byd.myapp/files/
```

### Requêtes KQL Azure (portail `law-byd-app`)
```kql
BYDAppLog_CL | order by TimeGenerated desc | take 200
BYDAppLog_CL | where Level_s in ("WARN","ERROR") | order by TimeGenerated desc
```

---

## Localisation

| Fichier | Langue |
|---|---|
| `res/values/strings.xml` | Français (défaut) |
| `res/values-en/strings.xml` | Anglais |

