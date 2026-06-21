# Audit de migration Kotlin — branche `switch-kotlin`

> Date : 2026-06-12 · Base : v1.5.1 (build 437) · 123 fichiers Java, ~41 500 lignes, 0 fichier Kotlin, 0 test.

## 1. État des lieux

| Élément | Valeur | Impact migration |
|---|---|---|
| AGP | 8.13.2 | Dernier 8.x ; AGP 9.x = migration majeure séparée (voir plafonds) |
| Gradle | 8.14.5 | Dernier 8.x ; ≥ 8.13 requis par AGP 8.13 ; dans la fenêtre KGP 2.4.0 |
| Java | source/target 17 | Débloqué par AGP 8 ; `kotlinOptions.jvmTarget = '17'` aligné |
| compileSdk / target / min | 33 / 29 / 28 | Aucun blocage Kotlin |
| SDK | android.jar BYD custom (bydauto APIs) | Kotlin compile contre le même jar — vérifié OK |
| Tests | **aucun** | Le build + lint 0/0 sont les seuls filets de sécurité → migration par petits lots obligatoire |

### Toolchain mis en place sur cette branche (vérifié : `assembleDebug` BUILD SUCCESSFUL)

- `build.gradle` racine : `kotlin-gradle-plugin:2.2.0` (compilateur K2 ; fenêtre de support Gradle 7.6.3+ / AGP 7.3.1+ → couvre notre stack sans bump AGP).
- `app/build.gradle` : `apply plugin: 'kotlin-android'` + `kotlinOptions { jvmTarget = '11' }` + `compileOptions` Java 11.
- Les `.kt` vivent dans `src/main/java/` (ramassés automatiquement par KGP) — pas de réorganisation de dossiers.
- `packagingOptions` excluait déjà `META-INF/*.kotlin_module` : sans effet négatif pour un APK applicatif, conservé tel quel.
- Coût APK : kotlin-stdlib ≈ +1,7 Mo avant minify (minifyEnabled est false).

### Plafonds de version et comment les lever (état juin 2026)

| Cible | Bloqué par | Pour lever |
|---|---|---|
| Kotlin 2.4.0 (actuel) | — | Dernière version publiée ✅ (fenêtre : Gradle 7.6.3–9.5.0, AGP 8.5.2–9.1.0) |
| Java 17 (actuel) | — | Java 21 source n'apporterait rien (code converti en Kotlin, ART API 29) et exigerait un JDK 21 local |
| AGP 8.13.2 / Gradle 8.14.5 (actuels) | Migration AGP 9 | Voir checklist ci-dessous |
| AGP 9.0/9.1 + Gradle 9.x | Chantier dédié | **Checklist AGP 9** : ① réécrire `applicationVariants.all` (nommage APK) en `androidComponents.onVariants()` — l'ancienne Variant API est supprimée ; ② Kotlin intégré par défaut (`kotlin-android` incompatible — opt-out `android.builtInKotlin=false` possible) ; ③ Build Tools 36.0.0 minimum à installer dans le SDK BYD custom ; ④ nouveaux defaults (`enableAppCompileTimeRClass`, R8 resource shrinking…) ; ⑤ Gradle 9.1+ requis. Plafond Kotlin : KGP 2.4.0 supporte AGP ≤ 9.1.0 → **AGP 9.2.x exclu** tant que KGP ne l'étend pas |

### Adaptations AGP 8 réalisées (toutes vérifiées : debug + release + lint 0/0)

- `buildFeatures { buildConfig true }` — AGP 8 ne génère plus BuildConfig par défaut (7 classes l'utilisent).
- `lintOptions` → `lint` (l'ancien bloc a été supprimé d'AGP 8).
- `android:extractNativeLibs="false"` du manifest → `packaging.jniLibs.useLegacyPackaging = false` (équivalent exact).
- `org.gradle.unsafe.configuration-cache` → `org.gradle.configuration-cache` (clé stabilisée Gradle 8).
- `app/lint.xml` : 4 nouveaux checks du lint 8.10 documentés et neutralisés (orientation fixe = produit voiture ; Aligned16KB = .so exclus de l'APK + matériel 4 Ko ; UnspecifiedRegisterReceiverFlag = no-op sous API 33 **et dangereux à "corriger"** car le daemon hors-process envoie ces broadcasts ; AndroidGradlePluginVersion = pin volontaire).

**Prérequis machine** : `sourceCompatibility ≥ 9` déclenche le `JdkImageTransform` d'AGP qui exige un **JDK complet avec `jlink`** (un JRE ne suffit pas). Sur cette machine : Temurin 17 dans `~/.jdks/jdk-17.0.19+10`, déclaré via `org.gradle.java.home` dans `~/.gradle/gradle.properties`. Le `core-for-system-modules.jar` requis est bien présent dans le SDK BYD (platforms/android-33).

## 2. Cartographie des risques par package

### Tier 1 — Faible risque : modèles, prefs, domaine (≈ 8 fichiers)
`model/` (AppInfo, AppShortcut), `domain/cluster/` (SplitSlot, ProjectionStateProvider), `data/prefs/ClusterPrefs`, `fission/LayoutPreset`, `util/`.
Classes de données et helpers sans état système. **Pilotes déjà convertis** (voir §4).

### Tier 2 — Risque moyen : UI et adapters (≈ 35 fichiers)
`ui/main`, `ui/diag`, `ui/nav`, `ui/settings`, `ui/log`, `fission/Layout*`, adapters RecyclerView.
Attention : DiagActivity = 3 280 lignes, MainActivity = 1 882 lignes — à convertir **en dernier dans ce tier**, après extraction éventuelle de sous-contrôleurs (pattern SplitController déjà en place).

### Tier 3 — Risque élevé : à convertir en dernier (≈ 50 fichiers)
- **`proxy/` + `proxy/daemon/`** (24 fichiers) : le daemon tourne hors process via `app_process` ; contrat binaire des verbes (ATTACH_SLOT…), `Phase4*Verbs` ; toute régression est invisible au build.
- **Réflexion sur APIs cachées** : 35 fichiers utilisent `getDeclaredMethod`/`Class.forName` (IActivityTaskManager, auto_container…). La conversion auto Java→Kotlin produit des nullités optimistes (`!!`) dangereuses ici — conversion **manuelle uniquement**.
- **Concurrence** : 38 fichiers avec `synchronized`/`static volatile` (AppLogger, BetaProxyClient, ClusterService.sIsRunning…). En Kotlin : `@Volatile`, `@JvmStatic`, `synchronized()` — sémantique à préserver champ par champ.
- **TestRunners DiLink2/4/5** (~6 800 lignes) : batteries de diagnostic terrain, ROM-spécifiques, faible valeur de conversion / risque élevé → **les migrer en dernier, voire jamais**.

## 3. Pièges d'interop identifiés (règles pour toute la migration)

1. **Accès direct aux champs publics depuis Java** (`app.isFavorite`, `info.shortcuts`…) : toujours annoter `@JvmField`, sinon Kotlin génère des getters et tous les call sites Java cassent.
2. **Constantes statiques** (`AppInfo.CATEGORY_*`, `SplitSlot.MAX_INDEX`) : `const val` en `companion object` → reste accessible en Java sans `.Companion`.
3. **Méthodes statiques appelées depuis Java** : `@JvmStatic` dans le companion.
4. **Surcharges avec valeurs par défaut** : `@JvmOverloads` pour conserver les signatures Java existantes.
5. **Nullabilité** : le SDK BYD custom n'a pas d'annotations `@Nullable` → tout type venant des APIs bydauto arrive en *platform type* (`String!`). Déclarer explicitement nullable (`?`) côté Kotlin, jamais `!!`.
6. **Enums protocolaires** : `SplitSlot.ordinal` fait partie du contrat daemon — ne jamais réordonner les entrées.
7. **Signature plateforme** : aucun impact (le signing config bydPlatform est indépendant du langage).

## 4. Réalisé sur cette branche (lot 0 — pilote)

- ✅ Toolchain Kotlin 1.9.24 branché, `assembleDebug` vert.
- ✅ `AppShortcut.java` → `AppShortcut.kt` (`@JvmField`, icon nullable).
- ✅ `AppInfo.java` → `AppInfo.kt` (`@JvmField` sur tous les champs, `const val` pour les catégories, mots-clés de catégorisation extraits en listes).
- ✅ `SplitSlot.java` → `SplitSlot.kt` (enum + `const val MAX_INDEX`). Note : aucun usage externe trouvé — candidat dead-code à confirmer.

## 4 bis. Réalisé — lot 1 (Tier 1 sûr)

- ✅ `ProjectionStateProvider.java` → `.kt` (interface ; param `Runnable?` nullable ; toujours implémentée par ClusterService + 3 classes anonymes Java).
- ✅ `LocaleHelper.java` → `.kt` (`object` + `@JvmStatic` + `const val` ; les 2 écritures prefs inline via l'extension core-ktx `edit { }` — déjà au classpath via appcompat — pour garder lint UseKtx à 0).
- ✅ `LayoutPreset.java` → `.kt` (`@JvmField` sur tous les champs publics mutés en place ; `@JvmStatic fromJson` ; `@Throws(Exception)` pour préserver le try/catch de LayoutPrefs ; `optString(name, null)` réécrit via `has()`+`isNull()` → strictement équivalent, sans passer null à un arg `@NonNull`).
- ✅ `ClusterPrefs.java` → `.kt` (`object` + `@JvmStatic` + `const val` ; constantes de clés package-private passées `private` (aucun lecteur externe, vérifié grep) ; setters recevant `null` au call site gardés `String?` → pas de NPE Kotlin ; surcharge `isGridMode` via `@JvmOverloads`).
- **AppLogger sorti du lot 1** : voir lot 1 bis ci-dessous.
- Vérif : compile Kotlin+Java forcée sans warning sur les 4 fichiers, `assembleDebug`+`assembleRelease` verts. Validé en test terrain (1.6.5-beta).

## 4 ter. Réalisé — lot 1 bis (`util/AppLogger`, concurrence)

- ✅ `AppLogger.java` → `.kt` (`object` + `@JvmStatic`). Concurrence préservée à l'identique : `LOCK` (`Any()`), blocs `synchronized(LOCK)`, `java.util.ArrayDeque.pollFirst()` (contrat null-si-vide, ≠ `kotlin.collections.ArrayDeque`), compteurs `IntArray` incrémentaux, `sChangeStamp`, snapshot hors-lock dans `get()`.
- `enum class Level` et `class Entry` (constructeur `internal`, personne ne l'instancie hors AppLogger) ; champs `Entry` en `@JvmField val` (lus directement par LogActivity/LogAdapter/SysInfoActivity).
- Nullabilité fidèle : `tag`/`msg` non-null (vérifié — aucun appelant ne passe null, les `null` trouvés étaient dans des `x==null?"":x`) ; `Throwable?`, `content`/`reportText`/`file` nullable, `context` de `pruneOldFiles` nullable (tous gérés explicitement comme en Java).
- `ThreadLocal.withInitial` + `!!` documenté aux 2 call sites (jamais null par construction) ; `@Suppress("DEPRECATION")` sur `shareReportToTelegram` (`getPackageInfo(String,int)` obligatoire en API 29).
- Vérif : compile forcée 0 warning sur AppLogger, `assembleDebug`+`assembleRelease` verts, lint 0/0.

## 4 quater. Réalisé — lot 2 (data) — `data/apps/AppRepository`

- ✅ `AppRepository.java` → `.kt` (`class` instanciable, 1 instance dans MainActivity). Concurrence préservée : executor mono-thread daemon (`Thread(...).apply { isDaemon = true }`), `@Volatile mCachedApps`, `Handler(mainLooper)`, `submit(Runnable { … })` explicite (évite l'ambiguïté Callable).
- `Callback` → `fun interface` (SAM, implémentée par lambdas Java dans MainActivity/AppListCoordinator).
- Nullabilité fidèle : `setFavorite(packageName: String)` non-null (toujours déréférencé) ; `setAutoLaunch(packageName: String?)` nullable pour préserver le contrat « enable=false pour effacer » (court-circuit `enable && pkg == …`) ; `icon: Drawable?` (loadIcon peut renvoyer null) ; `getApps()` jamais null (`?: emptyList()`).
- Champs `AppInfo` mutés via `@JvmField` ; tri `sortWith` à 4 niveaux identique (catégorie → favori → launchCount desc → `compareTo(ignoreCase=true)`).
- `@Suppress("DEPRECATION")` sur `queryPackageManager` (`queryIntentActivities(Intent,int)` obligatoire en API 29).
- Adapters (`AppListAdapter`, `AppActionSheet`, `AppListCoordinator`) **gardés pour un lot ultérieur** — zone modifiée activement par l'utilisateur.
- Vérif : compile forcée 0 warning, `assembleDebug`+`assembleRelease` verts, lint 0/0.

## 4 quinquies. Réalisé — lot 3a (`ui/log`)

- ✅ `LogActivity.java` + `LogAdapter.java` → `.kt` (première Activity + RecyclerView.Adapter convertis ; consommateurs de l'API AppLogger).
- Views via `lateinit` (présentes dans le layout) ; vues navrail optionnelles en `View?` ; `mLevelFilter: AppLogger.Level?` nullable ; `Runnable` anonyme (auto-référence `this` dans `postDelayed`).
- Pièges traités : `regionMatches` réordonné (signature Kotlin ≠ Java) ; `e.threadName != null` (toujours vrai, Entry.threadName non-null) remplacé par `!= "main"` ; `-1L` explicite pour les `Long` ; `when` exhaustif sur `Level` (le `case INFO/default` Java).
- Le nom qualifié de l'Activity est inchangé → manifest + `LogActivity.class` (8 call sites Java) et `Intent(..., LogActivity::class.java)` continuent de fonctionner.
- Vérif : compile forcée 0 warning, `assembleDebug`+`assembleRelease` verts, lint 0/0.

## 4 sexies. Réalisé — lot 3b (`ui/settings/SettingsActivity`)

- ✅ `SettingsActivity.java` → `.kt` (660 l ; écran réglages).
- **17 constantes publiques `PREF_*`/`DEFAULT_*`** (consommées par ~12 classes Java : MainActivity, BootReceiver, ClusterService, HotspotActivity, UpdateChecker…) déplacées en `const val` dans un `companion object` → toujours accessibles `SettingsActivity.PREF_X` depuis Java. Les `const val` qui délèguent à `ClusterPrefs.X` compilent car ces dernières sont aussi `const val`.
- `PREF_CLUSTER_TYPE`/`DEFAULT_CLUSTER_TYPE`/`PREF_VISUAL_OVERSCAN_MODE` (internes, grep-vérifié) passés `private const val`.
- Vues : `lateinit` pour les présentes, `View?`/`CompoundButton?` pour les optionnelles (cbAdasWindowFix, cbCompactAppsPanel, flSafeZone, navrail) avec safe-calls ; `@Volatile mDestroyed` ; écritures prefs via KTX `edit { }` ; `Uri.parse` → `String.toUri` ; `Context.DISPLAY_SERVICE`/`MODE_PRIVATE` qualifiés ; `AdbLocalClient.Callback` override non-null (usage `error.trim()`).
- Vérif : compile forcée 0 warning, `assembleDebug`+`assembleRelease` verts, lint 0/0.

## 4 septies. Réalisé — lot 3c-1 (`ui/hotspot/HotspotActivity`)

- ✅ `HotspotActivity.java` → `.kt` (849 l ; la plus complexe du lot 3 : 3 Handlers/Runnables — watchdog, stats, uptime —, polling ADB, parsing regex de clients).
- Runnables anonymes → `object : Runnable` (auto-réf `this` dans `postDelayed`) ; callbacks `AdbLocalClient.Callback` params nullable (checks `out != null`).
- Vues : `lateinit` pour les non-checkées (tvStatus, btnStart/Stop/Toggle/Open, swAutoStartBoot, tvStatRx/Tx), nullable+safe-calls pour les checkées (tvUpdate, swWatchdog, tvWatchdogStatus, clients/uptime).
- `HClient` (nested) + `parseClients` (parser pur) en `private` (aucun test réel dans le repo) dans le companion ; `Matcher.group(1)` géré `?.`/`?: continue` (pas de `!!`).
- Couleurs ARGB en `private val … .toInt()` (interdit en `const`) ; `@Suppress("DEPRECATION")` sur `getPackageInfo`/`getApplicationInfo` (API 29) ; `Uri.parse` → `String.toUri` ; `@SuppressLint("SetTextI18n")` ciblé sur le compteur de clients (entier pur ; le lint Kotlin est plus strict que le Java sur `.toString()`).
- Vérif : compile forcée 0 warning, `assembleDebug`+`assembleRelease` verts, lint 0/0.

## 4 octies. Réalisé — lot 3c-2 (`ui/diag/SysInfoActivity`) — fin du lot 3

- ✅ `SysInfoActivity.java` → `.kt` (1435 l ; **le plus gros fichier converti**). Génération de rapport diagnostic sur executor, réflexion BYD (Class.forName + invoke), 4 `ThreadLocal<SimpleDateFormat>`, caches `ConcurrentHashMap`, callbacks ADB imbriqués, construction dynamique de lignes de services.
- `@Suppress("DEPRECATION")` + `@SuppressLint("SetTextI18n")` au niveau classe (comme le Java) ; `ThreadLocal.get()!!` documenté ; caches/patterns/helpers statiques → `companion object`.
- Pièges traités : variable `val` Java → `value` (mot réservé Kotlin) ; **conflit `Process`** (`android.os.Process` importé vs `java.lang.Process` de `ProcessBuilder`) → type local qualifié ; `when` sur `AppLogger.Level` ; les 2 surcharges `addServiceRow` fusionnées via `alwaysClickable: Boolean = false` ; checks morts retirés (`addServiceRow` retourne un `View` non-null) ; `v.toString()` réflexion → passé directement à `String.format` (gère null).
- Callbacks `AdbLocalClient.Callback` params nullable (`String?`) avec usages adaptés (`raw ?: ""`, `out?.trim()`).
- Vérif : compile forcée 0 warning, `assembleDebug`+`assembleRelease` verts, lint 0/0.

**Lot 3 (activities secondaires) terminé** : ui/log, Settings, Hotspot, SysInfo — toutes en Kotlin. 17 fichiers Kotlin au total.

## 4 nonies. Réalisé — lot 2 bis (adapters liste d'apps)

- ✅ `AppListAdapter.java` → `.kt` (RecyclerView.Adapter ; interface `OnSendToDashboardListener` implémentée par MainActivity Java ; ViewHolder nested ; shortcuts/popup/tints). `getAppAt` en `internal` (nested class non-inner). Couleurs `const val` (positives) + `ConstantState` via KTX `Int.toDrawable()`. `adapterPosition` (recyclerview 1.1.0, pas `bindingAdapterPosition` qui est 1.2.0+).
- ✅ `AppActionSheet.java` → `.kt` (`object` + `@JvmStatic fun show` appelé par MainActivity ; interface `Host` ; bottom sheet + dialogs DPI). `showDpiDialog`/`showDpiCustomDialog` en `private`.
- ✅ `AppListCoordinator.java` → `.kt` (`class` ; interface `Host` ; constructeur 10 params dont vues nullable ; `lateinit mAdapter` assigné dans `init`). `dpToPx` mort retiré (le seul utilisé est dans LayoutManagerActivity).
- Interop : les 3 interfaces (`OnSendToDashboardListener`, `AppActionSheet.Host`, `AppListCoordinator.Host`) restent implémentées par **MainActivity (Java)** — types de retour nullable côté Kotlin, compatibles. KTX adopté (`isVisible`, `toDrawable`) + `@SuppressLint("SetTextI18n")` ciblé.
- Vérif : compile forcée 0 warning, `assembleDebug`+`assembleRelease` verts, lint 0/0.

## 4 decies. Réalisé — lot 4 (`MainActivity`)

- ✅ `MainActivity.java` → `.kt` (**2036 l — le plus gros et le plus central**). God class du launcher : 16 interfaces implémentées (ClusterService.Listener + 15 Host de coordinateurs/adapters), lifecycle cluster, callbacks imbriqués, touch forwarding.
- Tous les `override` des 16 interfaces matchent du premier coup (3 Kotlin strictes + 13 Java platform) — une seule erreur de compilation (Int→Float implicite Java sur `setProjection`).
- Nullabilité fine : coordinateurs checkés/accédés tôt (broadcast receiver enregistré avant `setupCoordinators`) en **nullable + safe-calls** (mNav, mMirror, mFullscreen, mSplit, mClusterControl, mInsetOverlay…) ; ceux toujours initialisés-puis-utilisés en `lateinit` (mAppListCoordinator, mUsageTracker, mTimeoutManager, mInsetApplicator, mSessionTracker, mDashboardLauncher) — avec `::x.isInitialized` là où le Java checkait `!= null`.
- Callbacks `ServiceConnection`/`ClusterService.LaunchCallback`/`AdbLocalClient.Callback` → `object :`; threads/runnables → lambdas ; statiques (`sOrphanSnifferKillDone`, `sAdbWarningShown`, TAG) → companion. KTX `isVisible`.
- Vérif : compile forcée 0 warning, `assembleDebug`+`assembleRelease` verts, lint 0/0. **À tester en voiture en priorité (cœur de l'app).**

**21 fichiers Kotlin.** Validé terrain (1.6.31-beta). Reste : zones à risque réflexion/binder.

## 4 undecies. Réalisé — lot 5a (`infrastructure/task` — TaskFinder + impls)

- ✅ `TaskFinder` (interface) + ses 4 implémentations → `.kt` : `AmTaskFinder`, `AdbLocalTaskFinder`, `ProxyTaskFinder`, `ChainedTaskFinder`. **Aucune réflexion** — premier pas dans le tier 3, sous-lot net.
- Interop avec `ClusterService` (resté Java, seul consommateur) : `@Throws(TaskFinderException::class)` sur `findTaskId` **obligatoire** pour que le `catch (TaskFinder.TaskFinderException)` Java compile (sinon checked-exception « never thrown ») ; `const val NOT_FOUND` en `companion object` reste lisible `TaskFinder.NOT_FOUND` depuis Java ; les impls Kotlin l'écrivent qualifié (pas hérité du companion d'interface). Exception imbriquée `TaskFinderException` en `class` à 2 constructeurs.
- `parseFromRecents`/`parseFromActivities` (statiques, appelées par ClusterService + AdbLocalTaskFinder) → `@JvmStatic` dans le companion ; patterns pré-compilés conservés. `vararg finders` pour ChainedTaskFinder. `AtomicReference<String?>()` (valeur initiale null). `@Suppress("DEPRECATION")` sur `findTaskId` d'AmTaskFinder (`getRunningTasks` **et** `RunningTaskInfo.id` dépréciés).
- `ReflectionTaskResizer`/`TaskResizer`/`ShellTaskResizer`/`ChainedTaskResizer` **gardés Java** (réflexion) pour le sous-lot suivant.
- Vérif : compile forcée 0 warning, `assembleDebug`+`assembleRelease` verts, lint 0/0.

## 4 duodecies. Réalisé — lot 5b (`infrastructure/task` resizers — **1ʳᵉ réflexion convertie**)

- ✅ `TaskResizer` (interface + `ResizeException`) + `ReflectionTaskResizer`, `ShellTaskResizer`, `ChainedTaskResizer` → `.kt`. **`infrastructure/task` est désormais 100 % Kotlin.**
- **`ReflectionTaskResizer` = premier code à réflexion converti** (manuel, nullités explicites) : `int.class` → `Int::class.javaPrimitiveType` (indispensable pour que `getMethod("resizeTask", int, Rect, int)` matche la vraie signature) ; `iatm: Any?` (fidèle au Java qui ne suppose pas non-null) ; **ordre des `catch` préservé** (`ResizeException` avant `Exception`) + re-throw pour ne pas réemballer ; `ite.getTargetException() != null ? … : ite` → `ite.targetException ?: ite` ; `lastError is Exception` smart-cast pour le 2ᵉ ctor (cause `Throwable?` nullable).
- Interop `ClusterService` (Java) : `@Throws(ResizeException::class)` sur `resize` pour le `catch (TaskResizer.ResizeException)`. `ChainedTaskResizer(context)` instancié tel quel.
- `ShellTaskResizer` : `\$?` shell échappé (`echo \"exit=\$?\"`), patterns awk `\\{` conservés (= `\{`, valide en Kotlin car `\\`+`{`), `toLowerCase(Locale.ROOT)` → `lowercase(Locale.ROOT)`, callbacks `AdbLocalClient.Callback` params `String?` (`out?.trim() ?: ""`). Fire-and-forget : ne throw jamais (inchangé).
- Vérif : compile forcée 0 warning, `assembleDebug`+`assembleRelease` verts, lint 0/0.

## 4 terdecies. Réalisé — lot 5c (`infrastructure/launch`)

- ✅ `AppLauncher` (interface + `LaunchException`) + `ShellAppLauncher`, `IamAppLauncher`, `PlatformAdaptiveAppLauncher` → `.kt`. **`infrastructure/` est désormais 100 % Kotlin** (hors `AdbLocalClient`, classe à part).
- **Aucun consommateur runtime** : `PlatformAdaptiveAppLauncher` est seulement *importé* (mort) par `ClusterService` — jamais instancié ni appelé (ClusterService lance via ses propres `startActivityViaIAM/Shell` inline). Sous-lot le moins risqué ; l'import mort reste valide (même nom qualifié → non touché).
- `IamAppLauncher` (réflexion `startActivityAsUser`, 11 params) : `int.class` → `Int::class.javaPrimitiveType` (3 occurrences) pour matcher la signature reflective ; `iam: Any?` ; fallback `Context.startActivity` puis `LaunchException` (cause `Throwable` non-null, seul usage). `ShellAppLauncher` fire-and-forget, `launchIntent?.component ?: throw`. `PlatformAdaptiveAppLauncher` passe `context` brut aux 2 sous-launchers (comme le Java — chacun applique `applicationContext`).
- `@Throws(LaunchException::class)` conservé par cohérence (aucun `catch` Java externe ne l'exige, mais l'import dans ClusterService suggère un branchement futur).
- Vérif : compile forcée 0 warning, `assembleDebug`+`assembleRelease` verts, lint 0/0.

## 4 quaterdecies. Réalisé — lot 5d-1 (`cluster/ClusterSessionTracker`)

- ✅ `ClusterSessionTracker.java` → `.kt` (1ᵉʳ fichier du package `cluster/`, aucune réflexion). Suivi du set d'apps lancées sur le cluster + pipeline d'éviction (move→display 0 + force-stop) pour restoreBydDashboard/originCluster.
- Interop Kotlin↔Kotlin (seul consommateur = MainActivity, déjà converti) : `onAllDone` gardé en `Runnable` (la lambda trailing de MainActivity se SAM-convertit ; et `Handler.post(onAllDone)` l'attend). Callbacks `LaunchCallback`/`AdbLocalClient.Callback` → `object :` (params `String?`).
- `LinkedHashSet` (ordre d'insertion préservé), `!main.isNullOrEmpty()` + smart-cast, `mPkgs` en `MutableSet<String>`, `setSessionClusterPkgs(HashSet(mPkgs))`. `pkg` non-null dans `evictNext` (liste `List<String>`) → check `== null` mort retiré.
- Vérif : compile forcée 0 warning, `assembleDebug`+`assembleRelease` verts, lint 0/0.

## 4 quindecies. Réalisé — lot 5d-2a (`cluster/dpi/` système DPI)

- ✅ `ClusterDpiPrefs.java` + `ClusterDpiManager.java` → `.kt` (overrides DPI par app sur le cluster via `wm density … -d <id>` ; garde de sécurité `displayId > 0` préservée — display 0 jamais touché).
- `object` + `@JvmStatic` (ClusterDpiManager appelé par ClusterService Java : `applyForLaunch`, `restore`, `cacheSnapshot`) ; `const val SETTLE_MS = 150L`, `MIN_DPI/MAX_DPI`. ClusterDpiPrefs consommé par AppActionSheet (Kotlin) — `@JvmStatic` gardé par sécurité.
- Concurrence préservée : `sCache` (HashMap) + `synchronized(sCache) { … }` (forme valeur-de-retour, ex. `val v = synchronized(sCache){ sCache[id] } ?: 0`). `Math.max/min(clamp)` → `dpi.coerceIn(MIN_DPI, MAX_DPI)` ; écriture prefs via KTX `edit { }` (lint UseKtx) ; `pkg.isNullOrEmpty()`.
- Vérif : compile forcée 0 warning, `assembleDebug`+`assembleRelease` verts, lint 0/0.

## 5. Plan de lots suivants

| Lot | Contenu | Validation |
|---|---|---|
| 5a ✅ | `infrastructure/task` : TaskFinder + 4 impls | build vert |
| 5b ✅ | `infrastructure/task` resizers (dont `ReflectionTaskResizer` réflexion) — **task/ 100 % Kotlin** | build vert |
| 5c ✅ | `infrastructure/launch` (dont `IamAppLauncher` réflexion) — **infrastructure/ 100 % Kotlin** (hors AdbLocalClient) | build vert |
| 5d-1 ✅ | `cluster/ClusterSessionTracker` | build vert |
| 5d-2a ✅ | `cluster/dpi/` système DPI (ClusterDpiPrefs, ClusterDpiManager) | build vert |
| 5d-2b | `cluster/dpi/` éditeur (ResizeFrameView View custom, ClusterResizeActivity — réflexion) — fix resize validé terrain (1.6.38) | run terrain |
| 5d-3 | `cluster/display/` (DashboardDisplayHelper, DashboardLauncher, ClusterManager — réflexion/binder) | run terrain DL3/DL5 |
| 5e | `cluster/` cœur réflexion/binder (ClusterService, ClusterMirrorManager, ClusterInputForwarder) — manuel, champ par champ | run terrain DL3/DL5 |
| 5f | `infrastructure/AdbLocalClient` (869 l, socket ADB local) | run terrain |
| 6 (option) | `proxy/daemon` (contrat binaire) + TestRunners | batteries diag |

Règle générale : **un lot = un commit = build vert**, jamais de conversion automatique IDE sans relecture des `!!` et de la nullabilité.
