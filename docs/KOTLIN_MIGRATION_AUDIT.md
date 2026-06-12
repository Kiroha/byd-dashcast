# Audit de migration Kotlin — branche `switch-kotlin`

> Date : 2026-06-12 · Base : v1.5.1 (build 437) · 123 fichiers Java, ~41 500 lignes, 0 fichier Kotlin, 0 test.

## 1. État des lieux

| Élément | Valeur | Impact migration |
|---|---|---|
| AGP | 7.4.2 | Compatible Kotlin 1.9.x — **pas de bump AGP requis** |
| Gradle | 7.6.4 | Compatible KGP 1.9.24 |
| Java | sourceCompatibility 1.8 | `kotlinOptions.jvmTarget = '1.8'` aligné |
| compileSdk / target / min | 33 / 29 / 28 | Aucun blocage Kotlin |
| SDK | android.jar BYD custom (bydauto APIs) | Kotlin compile contre le même jar — vérifié OK |
| Tests | **aucun** | Le build + lint 0/0 sont les seuls filets de sécurité → migration par petits lots obligatoire |

### Toolchain mis en place sur cette branche (vérifié : `assembleDebug` BUILD SUCCESSFUL)

- `build.gradle` racine : `kotlin-gradle-plugin:1.9.24` (dernière 1.9.x, compatible AGP 7.4.2).
- `app/build.gradle` : `apply plugin: 'kotlin-android'` + `kotlinOptions { jvmTarget = '1.8' }`.
- Les `.kt` vivent dans `src/main/java/` (ramassés automatiquement par KGP) — pas de réorganisation de dossiers.
- `packagingOptions` excluait déjà `META-INF/*.kotlin_module` : sans effet négatif pour un APK applicatif, conservé tel quel.
- Coût APK : kotlin-stdlib ≈ +1,7 Mo avant minify (minifyEnabled est false).

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

## 5. Plan de lots suivants

| Lot | Contenu | Validation |
|---|---|---|
| 1 | Reste du Tier 1 : ClusterPrefs, LayoutPreset, ProjectionStateProvider, util/ | build + lint 0/0 + smoke test launcher |
| 2 | data/apps/AppRepository + adapters UI simples | build + test manuel liste d'apps |
| 3 | Activities secondaires (Settings, Log, SysInfo, Hotspot) | test manuel navrail |
| 4 | MainActivity / DiagActivity (après extraction de contrôleurs) | run terrain DL3 |
| 5 | cluster/, infrastructure/ (réflexion — manuel, champ par champ) | run terrain DL3/DL5 |
| 6 (option) | proxy/daemon + TestRunners | batteries diag complètes |

Règle générale : **un lot = un commit = build vert**, jamais de conversion automatique IDE sans relecture des `!!` et de la nullabilité.
