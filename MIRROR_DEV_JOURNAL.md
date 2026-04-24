# Journal de Développement - Fonctionnalité Cluster Mirror

Ce document répertorie l'historique complet des tentatives, modifications et analyses concernant le lancement du miroir d'applications sur l'écran du cluster (Display 2) via le démon ADB. Cela évite de perdre le contexte lors de l'écrasement des logs.

## Contexte
- **Rom :** DiLink 3.0 / 5.0 (Snapdragon 665)
- **App :** MyBYDApp (uid=10100+)
- **Démon ADB :** Lancement avec uid=2000 (shell). Accès garanti à SurfaceFlinger et InputManager.
- **Display 1 :** Écran natif cluster. Lancement d'app tierce bloqué sans signature système (`SafeActivityOptions.checkPermissions`).
- **Display 2 :** VirtualDisplay créé par le système d'exploitation (`fission_bg_xdjaVirtualSurface` pour xdja.containerservice). Dimensions 1920x720.

---

## Chronologie des BUGS et CORRECTIFS

### Test ≤ v2.23 : Le "ClusterTrampoline"
- **Problème :** Écran noir sur le miroir (Display 2). 
- **Explication :** L'application envoyait une `ClusterTrampolineActivity` (transparente) sur l'écran 2 pour contourner le `SecurityException`. Mais sur le VirtualDisplay, notre propre Activity bloquait la Surface ou l'interceptait.
- **Solution (à partir de v2.25) :** Lancement de l'Activity *directement* via la commande ADB Shell `am start`, sans passer par le trampoline. Le mot "trampoline" a été purgé des codes de lancement liés au `VirtualDisplay`.

### Test v2.24 : Paramètre `am start`
- **Problème :** L'instruction `am start` crashe avec l'argument `--activity-new-task`.
- **Explication :** La version Android du DiLink parse mal les chaînes textuelles longues pour les flags d'Intent.
- **Solution :** Utilisation directe de la valeur décimale bitmask combinée (`FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK`) = `-f 268468224`.

### Test v2.25 : Failed transaction avec `windowingMode`
- **Problème :** Transaction AMS échouée (`Failed transaction (2147483646)`) et 0 layer attaché à la Surface, donc écran noir.
- **Explication :** L'ajout de l'argument `--windowingMode 5` à `am start` provoquait le plantage de la commande au niveau de l'`ActivityManager` système. L'application cible n'était jamais rendue.
- **Solution (v2.26) :** Suppression de `--windowingMode 5`. 

### Test v2.26 : Plantage silencieux du MirrorDaemon
- **Problème :** Le Daemon devait récupérer la Surface (ContentProvider IPC) mais l'appel `createFromParcel()` crashait à cause d'un flux binaire désynchronisé.
- **Solution :** Ajout d'un tag booléen (`hasSurface=1`) explicite avant d'écrire et de lire la surface.

### Test actuel (Post-2.26) : Analyse en cours
- *Toujours écran noir*. Le `dumpsys SurfaceFlinger` indique toujours une "Failed transaction (2147483646)" côté `cmd`. Il faut approfondir la commande `am start` ou chercher si la projection (`setDisplayProjection`) sur le daemon pose souci.

---

## État actuel de l'Architecture (Miroir)

1. L'application lance *via ADB* la commande :
   `am start --display 2 -f 268468224 -n com.telenav.app.arp/...`
2. L'application récupère sa propre `Surface` locale (où afficher le miroir).
3. Elle stocke l'instance de `Surface` dans un `ContentProvider` (`MirrorProvider`).
4. Elle lance via ADB le démon `app_process` (uid=2000).
5. Elle envoie le broadcast `com.byd.myapp.MIRROR_DAEMON_READY`.
6. Le démon (MirrorDaemon) s'éveille, fait une requête au ContentProvider, récupère la `Surface` via IPC.
7. Le démon appelle `SurfaceControl.createDisplay` et applique une `Transaction` avec `setDisplaySurface` et `setDisplayProjection`.

## Pistes d'investigation en cours
- `Failed transaction (2147483646)` sur la commande AMS `am start` ou l'appel `cmd shortcut`.
- Le numéro `2147483646` correspond au code d'erreur `FAILED_TRANSACTION` d'un Binder.
- Est-ce que `am start` tente de communiquer avec un service mort ou bloqué par le conteneur Auto ?
- L'utilisation de `am start-activity` au lieu de `am start` peut-elle contourner ce problème ? (Noté dans les logs `am start-activity -W` fonctionnait pour le trampoline sur l'écran 1).

### Test v2.27 : Remplacement pur et simple de "am start" par appel natif Daemon
- **Problème identifié (v2.26) :** L'Activity tiers n'est toujours pas rendue sur le VirtualDisplay. L'ActivityManager système crash avec une "Failed transaction" lors de l'exécution en ligne de commande de "am start", indiquant une corruption ou une incompatibilité dans le parsing des arguments ADB par BYD pour Android 10.
- **Solution (v2.27) :** `am start` a été totalement DÉBRANCHÉ. À la place, l'application principale diffuse un Intent (`com.byd.myapp.MIRROR_DAEMON_LAUNCH`). Le démon (qui tourne comme `uid=2000` et possède la permission système `MANAGE_ACTIVITY_STACKS`) intercepte ce Broadcast en arrière-plan et déclenche lui-même l'application tierce via les API Java standards : `context.startActivity()` et `ActivityOptions.setLaunchDisplayId(2)`.
Cela contourne tous les bugs de "shell" et d'arguments ADB.

## v2.28 - Deep BYD API Matching
- L'analyse decompilée de `WindowManagement` a montré qu'ils n'utilisent absolument pas `am start` ou le contexte classique pour lancer l'app sur le display externe.
- À la place, ild utilisent une réflexion bas niveau sur `ActivityManager` via `IActivityManager`.
- L'appel spécifique utilisé et reproduit dans le daemon est : `IActivityManager.startActivityAsUser(null, null, intent, null, null, null, 0, 0, null, options.toBundle(), -2)` (où -2 = `UserHandle.USER_CURRENT`).
- Cette modification permet d'outrepasser l'exception `Failed transaction (2147483646)` en injectant parfaitement les mêmes paramètres que ceux attendus par la ROM BYD modifiée.

## v2.29 - Back to basics (Lancement depuis l'application)
- *Problème identifié dans v2.28* : Le daemon (exécuté via `app_process` au nom de `uid=2000`) a essuyé un `java.lang.SecurityException` lors de l'appel direct car bien qu'il ait accès aux APIs privées, il n'a pas d'identité "package" avec la permission cachée `INTERNAL_SYSTEM_WINDOW` exigée par le `SafeActivityOptions` d'Android 10 sur ce VirtualDisplay.
- *Constat* : Notre application (`com.byd.myapp`) se voit BIEN accorder les droits `INTERNAL_SYSTEM_WINDOW` grâce à notre vérification de signature réussie avec le système BYD (confirmé par notre classe de test `SigDump` qui parse `dumpsys`).
- *Solution* : Abandonner le lancement en ligne de commande/via le Daemon ADB. Refactoring complet de `ClusterService.java` dans la méthode `launchOnDashboard()` pour utiliser DIRECTEMENT la réflexion sur `android.app.IActivityManager` depuis notre thread principal `uid=10xxx` avec notre propre `packageName()`.
- *Pourquoi c'est mieux* : En exécutant cela depuis notre app, l'OS reçoit une demande validée par l'ActivityManager de la part d'une App autorisée par la ROM.
