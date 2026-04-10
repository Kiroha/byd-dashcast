# MyBYDApp — BYD Instrument Cluster Launcher

Application Android pour véhicules **BYD Seal / Atto / Han** permettant d'envoyer n'importe quelle app installée sur l'écran instrument cluster (derrière le volant), de la contrôler à distance, et de diagnostiquer les APIs BYD en temps réel.

> **Testé sur** : BYD Seal 2024 — Android 7.1.2 (API 25)

---

## Fonctionnalités

| # | Fonctionnalité | Description |
|---|---|---|
| 1 | **Liste des apps** | Toutes les apps installées (RecyclerView trié) |
| 2 | **→ Dashboard** | Envoie une app sur le cluster via `setLaunchDisplayId` (réflexion) |
| 3 | **→ Écran principal** | Renvoie une app sur le display 0, restaure le cluster BYD automatiquement |
| 4 | **Restaurer BYD** | HOME intent sur le display secondaire → rétablit l'interface BYD d'origine |
| 5 | **Panel contrôle cluster** | Touchpad + boutons (←/⌂/↑/↓/Vol+/Vol−) via `InputManager.injectInputEvent()` |
| 6 | **⚙ Diagnostic** | 4 tests — Presentation API, réflexion, ADB TCP, **lancement réel sur cluster** |
| 7 | **📋 Rapport système** | Rapport texte complet (displays, permissions, APIs BYD) + sauvegarde + partage |
| 8 | **📊 BYD Live** | Données véhicule temps réel (vitesse, rapport, mode énergie, regen) + journal |
| 9 | **Journal (AppLogger)** | Log horodaté de tous les événements, partageable sans câble ADB |
| 10 | **Multilingue** | Français / Anglais, choix au premier lancement, modifiable via 🌐 |

---

## Architecture

```
app/src/main/java/com/byd/myapp/
├── MainActivity.java           — Écran principal 15", liste apps, barre statut
├── WelcomeActivity.java        — Premier lancement — choix de la langue
├── DiagActivity.java           — 4 tests de compatibilité
├── SysInfoActivity.java        — Rapport système complet + partage
├── BYDLiveActivity.java        — Données BYD en temps réel + AppLogger
├── AppListAdapter.java         — Adapter RecyclerView (2 actions par ligne)
├── AppLogger.java              — Journal singleton, partageable sans ADB
├── LocaleHelper.java           — Persistance locale (SharedPreferences)
├── model/
│   └── AppInfo.java
└── dashboard/
    ├── DashboardLauncher.java       — setLaunchDisplayId via réflexion
    ├── DashboardDisplayHelper.java  — Détection display secondaire
    ├── DashboardPresentation.java   — Presentation API (fallback)
    ├── BYDDashboardActivity.java    — Widget BYD (vitesse/batterie/rapport)
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

1. Ouvrir **📊 BYD Live** → vérifier que les 3 devices retournent ✓
2. Ouvrir **⚙ Diagnostic** → lancer le diagnostic → vérifier le Test 4 (lancement réel)
3. Si problème → **📋 Rapport** → **📤 Partager** → envoyer par email/WhatsApp

---

## Localisation

| Fichier | Langue |
|---|---|
| `res/values/strings.xml` | Français (défaut) |
| `res/values-en/strings.xml` | Anglais |

