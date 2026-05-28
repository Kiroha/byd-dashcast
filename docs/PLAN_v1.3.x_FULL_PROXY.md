# Plan v1.3.x — Full ProxyDaemon migration

**Status:** PLANNED (not started). Wait for DL5 user feedback on v1.2.8 before opening v1.3.x branch.
**Branch (future):** `beta/1.3.0-full-proxy` (à créer depuis `main` une fois v1.2.x stabilisé et mergé).
**Author:** plan rédigé le 23/05/2026 à l'issue de la session v1.2.8.
**Pré-requis bloquants :**
- v1.2.x mergée en `main` (DL5 keyboard bridge + resize fix validés terrain).
- Retour testeur DL5 confirmant que `BetaProxyClient` est stable sur DL5 (cf. risque résiduel §5).
- Pas de régression P0/P1 ouverte sur DL3 prod.

---

## 1. Objectif

Router **toutes** les commandes ADB shell de l'application via le `ProxyDaemon` (`BetaProxyClient` → `ShellGateway`), à l'exception explicite :
1. du bootstrap du daemon lui-même,
2. de sa boucle de récupération / lecture de log,
3. des tests et sondes diagnostiques qui doivent exercer le chemin legacy par design.

**Bénéfices attendus :**
- **Latence** : single-digit ms par appel typed-verb vs ~80–200 ms par connexion ADB legacy (handshake RSA inclus). Le premier `wm overscan` après ouverture cluster passe de ~1.5 s (bootstrap + handshake) à ~5 ms si le daemon est déjà chaud.
- **Pas de re-prompt RSA** côté utilisateur.
- **Choke point unique** : les gardes (regex `WM_DISPLAY_ZERO`, futurs gardes de sécurité) ne sont définis qu'une fois.
- **Observabilité** : un seul endroit à profiler / logguer.
- **Robustesse DL5** : le proxy contourne déjà certaines isolations per-display (utilisé pour `injectKeyEvent` v1.2.8).

---

## 2. État de départ (snapshot après v1.2.8 / commit `7e94519`)

### 2.1 Ce qui passe DÉJÀ par le proxy

- `ShellGateway.execShell*` (hot path) :
  - Typed verb `wm overscan L,T,R,B -d N` → `BetaProxyClient.setOverscan(...)`.
  - Typed verb `wm overscan reset -d N` → idem.
  - Typed verb `pidof <pkg>` → `BetaProxyClient.getPidsByPackage(...)`.
  - Sinon → `BetaProxyClient.runShell(cmd)`.
  - Fallback dernier recours → `AdbLocalClient.executeShellWithResult`.
- `AdbLocalClient.executeShellWithResult` lui-même teste `BetaConfig.isProxyDaemonEnabled(ctx) && !isDiLink5Safe(ctx)` à 3 endroits (lignes 556, 650, 753) et bascule sur des wrappers `BetaProxyClient.forceStopPackage` / `autoContainerSendInfo`.

### 2.2 Garde-fou existant

- `ShellGateway.WM_DISPLAY_ZERO` (v1.2.8 build 199) bloque tout `wm overscan|size|density … -d 0` avant les deux chemins (proxy ET legacy).

### 2.3 Auto-start du daemon

- **Aucun** auto-start aujourd'hui. Le daemon est démarré lazy au premier `BetaProxyClient.connect(ctx)` déclenché par `ShellGateway` ou par les call sites internes d'`AdbLocalClient`. Premier appel = coût bootstrap (~500 ms – 1.5 s) + risque de tomber sur le fallback legacy si le daemon n'est pas prêt à temps.

---

## 3. Inventaire exact des call sites `AdbLocalClient.executeShell*` (snapshot 23/05/2026)

**21 sites au total** dans `app/src/main/java/`. Catégorisation :

### A — DÉJÀ via ShellGateway (rien à faire)

| Fichier | Ligne | Commande |
|---|---|---|
| `MainActivity.java` | 578 | `am start` post-resize |
| `MainActivity.java` | 1549 | `pidof <clusterPkg>` |
| `MainActivity.java` | 1591 | `pidof <mainPkg>` |
| `MainActivity.java` | 1701 | `wm overscan` resize Apply |
| `ClusterService.java` | 571 | `wm overscan` insets |
| `ClusterService.java` | 662 | `wm overscan reset -d 1` |
| `ClusterService.java` | 694 | resize fallback |

### B — À MIGRER vers ShellGateway (priorité v1.3.0)

| Fichier | Ligne | Commande | Justification |
|---|---|---|---|
| `SettingsActivity.java` | 499 | `wm overscan h,v,h,v -d clusterId` | Typed-verb dispo dans le proxy → gain latence direct. |
| `ClusterService.java` | 637 | `am start --display N -a … -n pkg/cls --activity-clear-task` | Hot path DL5 fallback launch. Tombera sur `runShell` du proxy (pas de typed-verb dédié actuellement — futur typed verb candidat). |

### C — Internes au PROXY (FIGÉS — interdiction de proxifier)

| Fichier | Ligne | Commande | Raison |
|---|---|---|---|
| `beta/BetaProxyClient.java` | 612 | `BOOTSTRAP_CMD` (push + `app_process` daemon) | C'est ce qui DÉMARRE le daemon → boucle infinie sinon. |
| `beta/BetaProxyClient.java` | 243 | `READ_LOG_CMD` lecture log daemon | Doit fonctionner même si daemon crashé. |

### D — TESTS / DIAGNOSTIC (whitelist explicite, garder direct)

| Fichier | Ligne | Rôle |
|---|---|---|
| `beta/BetaTestRunner.java` | 467 | `id -u` via legacy — test S0 « legacy path works » |
| `beta/BetaTestRunner.java` | 516 | `id -u` comparaison uid legacy vs proxy |
| `mirror/MirrorTestRunner.java` | 522 | Diagnostic mirror — exerce explicitement legacy |
| `dilink2/DiLink2TestRunner.java` | 867 | S-tier DL2 (proxy potentiellement inactif / incompatible sur DL2) |
| `dilink5/DiLink5TestRunner.java` | 1175 | `runShellSync` helper interne aux tests DL5 |
| `SysInfoActivity.java` | 816 | `echo ok` — sonde « AdbLocalClient reachable » dans la HUD système |

### E — DiagActivity (à arbitrer en v1.3.0)

| Fichier | Ligne | Commande |
|---|---|---|
| `DiagActivity.java` | 909, 962, 1016, 1042, 1071, 1080, 1108 | Diagnostics divers (header, kill, bg, …) |

**Recommandation par défaut :** laisser DiagActivity en direct (page de diagnostic dev, on veut le legacy brut). Alternative : ajouter un toggle UI "via proxy" dans DiagActivity plutôt que migrer en dur. À discuter au lancement de v1.3.0.

---

## 4. Whitelist formelle (règle de gouvernance v1.3.0+)

**Règle générale :** tout `executeShell*` en production doit passer par `ShellGateway`. Les seules exceptions autorisées sont les fichiers ci-dessous.

```text
beta/BetaProxyClient.java          # bucket C — bootstrap + log read
beta/BetaTestRunner.java           # bucket D — tests proxy vs legacy
mirror/MirrorTestRunner.java       # bucket D — diagnostic mirror
dilink2/DiLink2TestRunner.java     # bucket D — tests DL2 (proxy peut être OFF)
dilink5/DiLink5TestRunner.java     # bucket D — tests DL5 (runShellSync helper)
SysInfoActivity.java               # bucket D — sonde HUD legacy
DiagActivity.java                  # bucket E — diagnostic dev (sauf migration)
```

**Tout autre call site `AdbLocalClient.executeShell*` introduit hors de cette whitelist doit faire échouer la CI.**

---

## 5. Plan d'implémentation v1.3.0

### Étape 1 — Auto-start du daemon (gain immédiat, risque ~nul)

**Fichier cible :** `MainActivity.onCreate` OU nouvelle `Application` subclass (préféré pour couvrir les wake-ups par broadcast / service hors UI).

**Pseudo-code :**

```java
// Dans Application.onCreate (préféré) ou MainActivity.onCreate
sBackgroundExecutor.execute(() -> {
    try {
        if (!BetaConfig.isProxyDaemonEnabled(this)) return;
        if (isDiLink5Safe(this)) return; // tant que le garde DL5 est en place
        if (BetaProxyClient.isConnected()) return; // idempotent
        boolean ok = BetaProxyClient.connect(this);
        AppLogger.i("AppInit", "proxy auto-start: " + (ok ? "OK pid=" + BetaProxyClient.getDaemonPid() : "FAILED"));
    } catch (Throwable t) {
        AppLogger.w("AppInit", "proxy auto-start crashed: " + t.getMessage());
    }
});
```

**Garde-fous obligatoires :**
- Off-main-thread strict (pas de blocage UI).
- `try/catch (Throwable)` — l'auto-start ne doit JAMAIS crasher l'app.
- Respecte le toggle `BetaConfig.isProxyDaemonEnabled` (kill-switch utilisateur).
- Respecte `!isDiLink5Safe` tant que l'étape 2 n'est pas validée.

**Bonus :** enregistrer un BroadcastReceiver léger pour `ProxyDaemonMain.ACTION_PROXY_CONNECTED` (déjà émis par le daemon) pour loguer pid/version dès qu'il est prêt → observabilité.

### Étape 2 — Garde DL5 dans ShellGateway (alignement comportemental)

**Observation v1.2.8 :** `AdbLocalClient` route via proxy avec `BetaConfig.isProxyDaemonEnabled(ctx) && !isDiLink5Safe(ctx)` (lignes 556/650/753), mais `ShellGateway` n'a PAS ce garde DL5. À vérifier au démarrage de v1.3.0 : potentiellement déjà un mini-bug à corriger.

**Décision à prendre :** soit on étend le proxy au DL5 (si feedback testeur OK), soit on aligne `ShellGateway` sur la même politique `&& !isDiLink5Safe`.

### Étape 3 — Migration des 2 sites bucket B

1. `SettingsActivity.java:499` → remplacer `AdbLocalClient.executeShellWithResult` par `ShellGateway.execShellWithResult` (signature identique : `(ctx, cmd, Callback)`).
2. `ClusterService.java:637` → idem pour le `am start --display`.

**Test de non-régression obligatoire :**
- DL3 Seal EU : Apply overscan via Settings → marges appliquées.
- DL5 : am start fallback fonctionne (lancer une app cluster qui ne supporte pas moveTaskToDisplay).

### Étape 4 — Annotation marqueur + lint CI

1. Créer `@LegacyShellAllowed` (annotation, `RetentionPolicy.SOURCE`) à apposer sur les méthodes des fichiers bucket C+D+E pour matérialiser la whitelist dans le code.
2. Tâche Gradle custom (ou hook git pre-commit) qui :
   - grep `AdbLocalClient\.executeShell` dans `src/main/java`,
   - fail si le fichier n'est pas dans la whitelist §4.

**Exemple skeleton Gradle :**

```gradle
task lintNoDirectAdbShell {
    doLast {
        def whitelist = [
            'beta/BetaProxyClient.java',
            'beta/BetaTestRunner.java',
            'mirror/MirrorTestRunner.java',
            'dilink2/DiLink2TestRunner.java',
            'dilink5/DiLink5TestRunner.java',
            'SysInfoActivity.java',
            'DiagActivity.java', // si on garde
        ]
        def offenders = []
        fileTree('app/src/main/java').matching {
            include '**/*.java'
        }.each { f ->
            if (f.text =~ /AdbLocalClient\.executeShell/) {
                def rel = f.path.replace("${rootDir}/app/src/main/java/com/byd/dashcast/", '')
                if (!whitelist.any { rel.endsWith(it) }) offenders << rel
            }
        }
        if (offenders) throw new GradleException(
            "Direct AdbLocalClient.executeShell* found outside whitelist:\n  " +
            offenders.join('\n  ') +
            "\nUse ShellGateway.execShell* instead, or add to whitelist in build.gradle if intentional."
        )
    }
}
preBuild.dependsOn lintNoDirectAdbShell
```

### Étape 5 — (Optionnel) DiagActivity toggle "via proxy"

Si on veut garder les deux chemins testables côte à côte : ajouter un `Switch` "via ProxyDaemon" en haut de DiagActivity, qui bascule entre `AdbLocalClient.executeShell*` et `ShellGateway.execShell*` pour les 7 call sites. Permet aux devs de comparer comportements / latences à la volée.

---

## 6. Risques & points d'attention

### 6.1 Couverture des verbes

Tout ce que le daemon n'implémente pas en typed-verb doit retomber proprement sur `runShell`. C'est déjà le cas dans `ShellGateway`. Vérifier que les nouveaux call sites migrés (notamment `am start --display`) fonctionnent via `runShell` ou bénéficient d'un nouveau typed-verb dédié.

### 6.2 Sémantique d'erreur

`AdbLocalClient.Callback` (`onSuccess(stdout)` / `onError(msg)`) doit être préservée bit-for-bit pour les call sites migrés (sinon régression silencieuse). `ShellGateway.execShellWithResult` respecte déjà cette signature → OK.

### 6.3 Mode proxy OFF (kill-switch)

Le chemin direct doit rester fonctionnel et testable. Pattern actuel `if (isProxyDaemonEnabled) { gateway } else { direct }` est le bon — à conserver.

### 6.4 Garde DL5

Cf. étape 2. Décision à prendre au lancement de v1.3.0 selon le retour testeur DL5 sur v1.2.8.

### 6.5 Ordre d'init

`ClusterService.onCreate` doit pouvoir attendre que le daemon soit ready avant de router les premières commandes (sinon premier overscan = legacy + race). Avec l'auto-start étape 1, le daemon est prêt avant que l'utilisateur ouvre le cluster (sauf race extrême sur cold start). Si race observée → ajouter un petit `BetaProxyClient.waitConnected(timeoutMs)` dans `ClusterService.onCreate`.

### 6.6 DL2

DL2 n'a jamais utilisé `BetaProxyClient` (port 5555 utilisateur, pas de cluster). Garder le bypass DL2 dans `AdbLocalClient` et `ShellGateway`.

### 6.7 Boucle infinie potentielle

Si le bootstrap (`BetaProxyClient.bootstrap` → `AdbLocalClient.executeShellWithResult(BOOTSTRAP_CMD)`) passait par `ShellGateway`, on aurait : `ShellGateway` → `BetaProxyClient.connect` → `bootstrap` → `ShellGateway` → … Garde-fou : bucket C est sacro-saint, marqué par annotation + lint.

---

## 7. Critères de Done v1.3.0

- [ ] Auto-start du daemon implémenté (Application ou MainActivity.onCreate).
- [ ] Garde DL5 dans `ShellGateway` aligné sur `AdbLocalClient` (ou levé si feedback DL5 OK).
- [ ] `SettingsActivity:499` et `ClusterService:637` migrés vers `ShellGateway`.
- [ ] Annotation `@LegacyShellAllowed` créée et apposée sur les méthodes bucket C+D+E.
- [ ] Lint Gradle CI qui fail sur tout `AdbLocalClient.executeShell` hors whitelist.
- [ ] Tests de non-régression :
  - DL3 Seal EU : overscan + resize + am start fonctionnent identiquement à v1.2.x.
  - DL5 testeur : keyboard bridge + resize + am start fonctionnent, latence du premier overscan mesurée < 50 ms (vs ~1.5 s en v1.2.8).
  - DL2 : aucune régression (proxy inactif, chemin direct conservé).
- [ ] CHANGELOG.md mis à jour avec section v1.3.0.
- [ ] Pre-release GitHub `v1.3.0-beta` publiée pour testeurs.

---

## 7.b — Typed verb `removeTaskByPackage` (héritage v1.2.9)

**Contexte :** les bugs DL3 remontés le 23/05/2026 (long-press « Kill » laisse la tuile dans Recents, Stop projection relance Waze fullscreen sur display 0) ont reçu un correctif basique en v1.2.9 :
- sérialisation `move → forceStop` dans `doKillApp` (via `LaunchCallback`),
- inversion d'ordre dans `restoreBydDashboard` / `originCluster` (`forceStop` AVANT `moveSessionAppsToMainDisplay`),
- `verifyForceStop` actif : escalade `kill -9 <pids>` via `BetaProxyClient.runShell` si le process survit après 500 ms.

**Limite résiduelle :** sur BYD AUTO Android 10/12, `am force-stop` et `IActivityManager.forceStopPackage` tuent le **process** mais **ne suppriment pas** la `TaskRecord` de la pile Recents. Le code actuel tente une purge via :
```
dumpsys activity recents | grep TaskRecord | grep <pkg> → sed → TaskId(s)
for t in $TASKS; do
    am task remove $t
    app_process … com.byd.dashcast.daemon.TaskRemover $t
done
```
`am task remove` n'existe pas systématiquement sur BYD AUTO ; le fallback `TaskRemover` via `app_process` peut rejeter silencieusement (uid=2000 sans permission `REMOVE_TASKS`). Résultat : tuile fantôme dans Recents qui peut respawn l'activité.

**Solution robuste v1.3.x — typed verb daemon-side :**

Nouveau transaction code dans `ProxyDaemonMain` (cible : v1.3.0 ou un patch v1.3.1) :

```java
// ProxyDaemonMain.java
public static final int TXN_REMOVE_TASKS_BY_PACKAGE = TXN_FORCE_STOP_PACKAGE + 1;

// Côté daemon (tourne en uid=2000 via app_process, contexte stable, accès APIs cachées) :
case TXN_REMOVE_TASKS_BY_PACKAGE: {
    data.enforceInterface(DESCRIPTOR);
    String pkg = data.readString();
    int userId = data.readInt(); // 0 = current user, -1 = all
    int removed = removeTasksByPackageInternal(pkg, userId);
    reply.writeNoException();
    reply.writeInt(removed);
    return true;
}

private int removeTasksByPackageInternal(String pkg, int userId) {
    int count = 0;
    try {
        Class<?> atmClass  = Class.forName("android.app.ActivityTaskManager");
        Object   iatm      = atmClass.getMethod("getService").invoke(null);
        // API 29+ : getRecentTasks(maxNum, flags, userId) → List<RecentTaskInfo>
        Object recents = iatm.getClass()
                .getMethod("getRecentTasks", int.class, int.class, int.class)
                .invoke(iatm, 64 /* max */, 0x0002 /* RECENT_IGNORE_UNAVAILABLE */, userId);
        java.util.List<?> list = (java.util.List<?>) recents.getClass()
                .getMethod("getList").invoke(recents);
        for (Object info : list) {
            // RecentTaskInfo.realActivity (ComponentName) ou .baseIntent (Intent)
            android.content.ComponentName ca = (android.content.ComponentName)
                    info.getClass().getField("realActivity").get(info);
            String taskPkg = (ca != null) ? ca.getPackageName() : null;
            if (pkg.equals(taskPkg)) {
                int taskId = info.getClass().getField("persistentId").getInt(info);
                try {
                    iatm.getClass().getMethod("removeTask", int.class).invoke(iatm, taskId);
                    count++;
                } catch (Throwable t) {
                    AppLogger.w(TAG, "removeTask " + taskId + " failed: " + t.getMessage());
                }
            }
        }
    } catch (Throwable t) {
        AppLogger.w(TAG, "removeTasksByPackage(" + pkg + ") error: " + t.getMessage());
    }
    return count;
}
```

**Côté client :** `BetaProxyClient.removeTasksByPackage(String pkg, int userId) -> int`, appelée :
1. Depuis `AdbLocalClient.forceStopApp` (juste après le force-stop, en remplaçant le bloc `dumpsys + sed + am task remove + TaskRemover`).
2. Depuis `restoreBydOnCluster` / `restoreOriginCluster` (après `forceStopPackage` typed).

**Bénéfices :**
- Purge atomique via une seule API native (pas de pipeline shell fragile).
- `removeTask` invoqué depuis le daemon `app_process` qui a un contexte stable et historiquement accepté pour ces appels sur la ROM BYD.
- Élimine la race avec `dumpsys activity recents` (output peut changer pendant le parse).
- Tuile Recents vraiment supprimée → plus de respawn possible.

**Plan de migration :**
1. Implémenter `TXN_REMOVE_TASKS_BY_PACKAGE` côté daemon + client.
2. Bumper `PROTOCOL_VERSION` de 2 → 3 (premier vrai bump depuis v1.1.6).
3. Remplacer le bloc `cleanRecentsCmd` shell dans `AdbLocalClient.forceStopApp` par un appel typed, avec fallback legacy si daemon indisponible.
4. Tests de validation :
   - Long-press Kill → ouvrir Recents → tuile absente.
   - Stop projection avec Waze sur cluster → cluster restauré, AUCUN flash Waze sur display 0, Recents propre.
   - DL5 testeur : même chose.

**Critère de Done v1.3.x complémentaire :** ajouter cette migration à la liste §7 (Critères de Done) et à la matrice de validation §7.b.

---

## 8. Hors-scope v1.3.x

À NE PAS faire dans v1.3.x (à reporter en v1.4.x ou plus tard) :

- Bump du `PROTOCOL_VERSION` du daemon → 3 **autorisé** uniquement pour ajouter `TXN_REMOVE_TASKS_BY_PACKAGE` (cf. §7.b). Pas d'autre bump.
- Nouveaux typed-verbs au-delà de ceux déjà supportés (`wm overscan`, `pidof`). Les nouveaux verbes utilisés (`am start --display`, etc.) passent par `runShell` du proxy — typed-verb à venir dans un futur cycle si profiling le justifie.
- Refonte de `BetaProxyClient` ou du daemon lui-même.
- Migration des appels DL2 (toujours en direct, daemon non déployé sur DL2).
- Suppression du chemin legacy : à garder comme fallback / kill-switch indéfiniment.

---

## 9. Références

- Conversation source : session du 23/05/2026 (post v1.2.8 build 199).
- Snapshot grep réalisé sur commit `7e94519` (HEAD de `beta/1.2.0-dilink5` au moment de la rédaction).
- Fichiers clés à relire avant de démarrer v1.3.0 :
  - `app/src/main/java/com/byd/dashcast/beta/ShellGateway.java` (gateway actuel + garde WM_DISPLAY_ZERO).
  - `app/src/main/java/com/byd/dashcast/beta/BetaProxyClient.java` (bootstrap + handshake + transactions).
  - `app/src/main/java/com/byd/dashcast/AdbLocalClient.java` (lignes 556, 650, 753 — bascules proxy existantes).
  - `app/src/main/java/com/byd/dashcast/beta/BetaConfig.java` (kill-switch + helpers).
