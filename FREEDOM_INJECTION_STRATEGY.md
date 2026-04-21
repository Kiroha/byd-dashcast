# Stratégie d'Injection Silencieuse (Freedom Payload)

Ce document décrit la méthode pour rendre l'utilisation de Freedom 100% transparente pour l'utilisateur final. L'objectif est qu'un utilisateur n'ait besoin de télécharger et d'installer **que** `MyBYDApp`, sans jamais se soucier d'installer, configurer ou masquer l'application Freedom.

---

## 1. Le Problème (Pourquoi on ne peut pas fusionner le code)

Le système DiLink de BYD utilise un service système nommé `auto_container` (géré par `com.xdja.containerservice`) pour contrôler l'affichage sur l'écran du tableau de bord (Cluster).

Ce service système lit le fichier de configuration système `/system/etc/container_comm_cfg.json`. Ce fichier agit comme une **liste blanche stricte** qui autorise un seul et unique Package Android à demander la création de l'affichage étendu : `com.xdja.clusterdemo` (le package de Freedom).

Android utilise le protocole **Binder** pour toutes les communications inter-processus (IPC). Binder transmet de manière sécurisée (depuis le noyau Linux) l'UID de l'application appelante. Il est donc **impossible d'usurper l'identité** d'un package sans un accès Root complet. Fusionner le code de Freedom dans `MyBYDApp` ne fonctionnerait pas, car le système identifierait notre package (`com.byd.myapp`) et rejetterait l'appel.

---

## 2. La Solution : Le Cheval de Troie (Payload ADB)

Puisque nous avons réussi à obtenir un accès ADB local (avec l'UID 2000, qui possède les droits d'installation d'applications), nous pouvons packager une version altérée de Freedom directement _à l'intérieur_ de `MyBYDApp`, et forcer son installation silencieuse en arrière-plan.

### Étape 1 : Obscurcir Freedom (Retrait de l'icône)
1. Décompiler l'APK original de Freedom (`apktool d freedom.apk`).
2. Ouvrir le fichier `AndroidManifest.xml` de Freedom.
3. Rechercher le bloc `<intent-filter>` de `MainActivity`.
4. **Supprimer** la ligne suivante :
   `<category android:name="android.intent.category.LAUNCHER" />`
5. Recompiler l'APK (`apktool b freedom`) et le signer.
*Résultat : Freedom devient un service système "fantôme". Il n'apparaît plus dans la liste des applications de la voiture.*

### Étape 2 : Intégration dans MyBYDApp
1. Renommer cet APK modifié en `freedom_payload.apk`.
2. Placer ce fichier dans le dossier des ressources brutes de notre projet : `app/src/main/res/raw/freedom_payload.apk`.

### Étape 3 : Extraction au Runtime
Au premier démarrage de `MyBYDApp`, le code Java extrait l'APK de ses ressources vers un dossier temporaire accessible par ADB.
```java
// Exemple schématique
InputStream in = context.getResources().openRawResource(R.raw.freedom_payload);
File outFile = new File(context.getExternalCacheDir(), "freedom_payload.apk");
// Copie du flux...
```

### Étape 4 : Installation Silencieuse via ADB local
Une fois extrait, on utilise notre `AdbLocalClient` existant pour l'installer sans aucune fenêtre de confirmation pour l'utilisateur.
```bash
# Désinstallation préalable au cas où une ancienne version avec une signature différente existe
pm uninstall com.xdja.clusterdemo

# Installation silencieuse de notre payload
pm install -r -d /sdcard/Android/data/com.byd.myapp/cache/freedom_payload.apk
```

---

## 3. Déroulement de l'Expérience Utilisateur Finale

Une fois cette stratégie implémentée, voici comment se déroulera l'installation pour un nouvel utilisateur :

1. L'utilisateur installe `MyBYDApp` *.apk* depuis une clé USB.
2. Il lance l'application.
3. L'application demande l'accès ADB Local (le guide d'activation normal).
4. **[Invisible]** MyBYDApp détecte que `com.xdja.clusterdemo` n'est pas installé (ou pas notre version).
5. **[Invisible]** MyBYDApp extrait `freedom_payload.apk` et exécute `pm install`.
6. L'utilisateur clique sur "Démarrer le Cluster".
7. **[Invisible]** MyBYDApp écrit dans `properties.xml` et fait le `am broadcast BOOT_COMPLETED` (méthode de la v2.10).
8. Le Cluster bascule en mode projection, l'écran de navigation s'affiche.

L'utilisateur n'a vu ni de seconde application à installer, ni d'icône polluante dans sa liste d'applications DiLink, ni de flash d'écran. 

---

## 4. Points de vigilance (Edge Cases)

* **Conflit de signature :** Si l'utilisateur avait déjà installé le Freedom "officiel", les signatures APK ne correspondront pas avec notre payload. La commande `pm install -r -d` va échouer. C'est pourquoi la commande `pm uninstall com.xdja.clusterdemo` préalable est impérative.
* **Espace de stockage :** L'extraction de l'APK ajoute quelques mégaoctets au stockage de l'application parente.
* **Mises à jour BYD :** Si le constructeur bloque la commande `pm install` via ADB shell local à l'avenir (ce qui est rare mais possible sur des ROMs verrouillées), cette méthode échouera avec une erreur `SecurityException`. Il faudra alors que l'utilisateur installe l'APK manuellement en solution de repli.