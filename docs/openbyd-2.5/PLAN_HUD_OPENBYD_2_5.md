# Plan d'integration HUD OpenBYD 2.5 dans DashCast

Statut : collecte OEM depuis Diag ajoutee au lot 0, lot 1 implemente avec choix HUD/cluster independants et socle transport du lot 2 ajoute hors vehicule ; SOME/IP reste inactif. Plan initial : 2026-09-12.
Derniere verification : 2026-10-08 (bug report Maps Morphe du pilote DL3 / SX361).
Contrat de reference : [OPENBYD_2_5_HUD_INTEROP.md](OPENBYD_2_5_HUD_INTEROP.md).

## Audit disponible

Reprise apres le plantage : **29 tests du banc smali passent**, 9415 fichiers identiques entre deux extractions, 80/828 methodes exercees. [Banc et limites](audit/README.md). Les encodeurs des trois profils sont compares a protobufjs pour les fixtures couvertes ; cela ne valide pas le serveur OEM ni le rendu.

Un framework **DL3/API29** reel precise les bornes et controles SDK. Ses constantes `TURN_KIND_*` different des noms/codes OpenBYD : le portage doit conserver des tables par protocole/profil, pas generaliser une enum. L'archive DL5 disponible inventorie le service SOME/IP mais **ne contient pas son APK**. Le nouvel [export pilote DL3 / SX361](OEM_EVIDENCE_DL3_SX361_20261006.md) confirme la presence d'AmapService et l'absence des deux packages SOME/IP/naviauto sur ce systeme. La voie DL3 est a conserver pour ce pilote ; le lot 0 des profils SOME/IP reste bloque sur un recepteur et un vehicule pilote correspondants.

## Decision d'architecture

Conserver la voie DiLink 3 validee ; ajouter une sortie SOME/IP independante, puis enrichir l'acquisition Maps/Waze. Ne pas porter OpenBYD en bloc.

Objectif du premier increment : une manoeuvre et sa distance envoyees par DashCast au HUD d'un profil SOME/IP identifie, avec debut/fin de session et sans regression CAN/cluster. Rue et ETA uniquement si le profil recepteur les expose. Les trois profils ne seront pas declares compatibles simultanement.

## Point de depart verifie

| Existant | Consequence pour l'integration |
|---|---|
| [HudController.kt](../../app/src/main/java/com/byd/dashcast/hud/HudController.kt#L156) orchestre CAN, `ClusterNavPusher` et Amap apres selection d'une sortie prouvee | Ne pas autoriser les FID DL3 sur un autre systeme ; SOME/IP reste sans transport tant que le profil n'est pas valide |
| [CanNavigationBatches.kt](../../app/src/main/java/com/byd/dashcast/system/CanNavigationBatches.kt) definit activation, guidage, effacement et ETA | Conserver l'ordre des ecritures, UTF-16LE, heartbeat et absence intentionnelle de `0x43F01030` |
| [MapNotificationListenerService.kt](../../app/src/main/java/com/byd/dashcast/hud/MapNotificationListenerService.kt#L105) possede un writer serie et `LatestValueDispatcher` | Reutiliser le mecanisme borne ; ne pas creer un executor emetteur par source GPS |
| Le listener utilise actuellement petite icone/texte, puis refuse le guidage incomplet | Ajouter les nouvelles sources sans introduire le repli OpenBYD "inconnu = tout droit" |
| [HudDeliveryTracker.kt](../../app/src/main/java/com/byd/dashcast/hud/HudDeliveryTracker.kt) et le watchdog distinguent contenu/delivery/liveness | Etendre ce suivi aux sorties selectionnees ; un keepalive SOME/IP ne doit jamais rafraichir l'age de la source |
| [HudController.kt](../../app/src/main/java/com/byd/dashcast/hud/HudController.kt#L542) cible un recepteur Amap et partage son mapping avec `ClusterNavPusher` | Garder le mapping valide du vehicule ; ne pas copier le mapping ou la triple diffusion OpenBYD |

Les noms nouveaux ci-dessous (`HudOutput`, `SomeIpHudTransport`, etc.) sont des propositions internes a DashCast, pas des API OEM relevees dans l'APK.

## Lots et criteres de sortie

### Lot 0 : identifier le recepteur et verrouiller la reference

- Collecter Android/build DiLink, type/plateforme vehicule, presence du service SOME/IP, son APK et `dumpsys package`, XML de permissions pertinents. Lire le FID de configuration HUD uniquement via une API de lecture deja disponible et appropriee au vehicule.
- Pour CN D5, verifier aussi `com.byd.naviauto` : noms de drawables et resource IDs utilises par OpenBYD, presence/repli, taille et orientation des PNG. Les assets HUD de repli cites par son code sont absents de l'APK analyse ; ne pas les supposer disponibles.
- Verifier `exported`, permission de binding, ACL UID/certificat, signature de l'interface et contrats TX4/5/6 ; confronter les profils du rapport aux messages attendus par le service.
- Confronter les espaces de codes : valeur emise par OpenBYD, enum du SDK DL3 local, protocole Amap/container et glyphe observe. Valider visuellement le mapping du profil cible avant de modifier une table DashCast deja confirmee.
- Reutiliser les fixtures et le rapport de couverture maintenant disponibles ; terminer les branches non exercees du profil retenu et confronter les messages au serveur OEM. Le banc est un modele borne, pas une validation exhaustive du DEX sur ART.
- Comparer les JAR reels aux stubs si l'on doit modifier l'acces SDK. Ne pas conclure qu'une presence de package ou un binding reussi garantit un HUD compatible.
- Constituer un dossier de fixtures versionnees par profil : manoeuvres, payloads attendus, traces de resultats anonymisees. Pas d'APK OEM ni d'images extraites d'OpenBYD ajoutes au depot.
- Executer les tests de reference HUD et CAN avant toute modification ; retenir une trace DL3 de debut/update/stop comme reference de non-regression.

**Collecte depuis DashCast ajoutee le 2026-10-03 :** utiliser **Diag -> BYD APK Extraction** dans un APK contenant cette mise a jour. Le meme bouton cible maintenant `com.ts.car.someip.service` puis `com.byd.naviauto` avant les anciennes cibles de projection, sous les limites de taille existantes. Il conserve l'inventaire et le manifeste des fichiers copies/refuses ; une limite de taille ou un refus de lecture n'est pas une absence du package. Le statut "projection deja analysee" de DiLink 5.0 ne bloque plus cette collecte HUD.

Le ZIP ajoute `07_hud_someip_receiver.txt` : proprietes Android/build/vehicule explicites, `pm path` des deux packages, dumps de package non filtres pour le recepteur, ses ressources, DashCast et l'identite shell, liste des services SOME/IP en cours et correspondances XML permissions/sysconfig avec chemin et contexte. Les erreurs, sorties vides et troncatures sont visibles. La collecte lit l'etat du vehicule ; elle ne fait aucun binding SOME/IP ni emission de guidage. Elle emprunte la redaction et les canaux de rapport existants. Noter le nom du ZIP transmis ; si l'envoi echoue, le diagnostic affiche son chemin local et tente le partage quand la taille le permet. Fournir aussi modele/annee/version DiLink du vehicule. L'analyse de ce ZIP doit encore confirmer que l'APK recepteur a effectivement ete recupere et choisir les preuves suivantes ; aucune compatibilite n'est deduite de la seule presence du package.

Verification de cette extension : **791 tests complets passes**, dont **8 nouveaux cas de collecte/selection/budget** ; `:app:lintRelease` a **0 erreur/avertissement/information**. Suite complete et lint reexecutes avec `--rerun-tasks`, puis graphe AST actualise. La collecte enrichie fait partie du candidat **1.9.4-beta (versionCode 643)** ; voir les [notes de version et instructions de collecte](../releases/1.9.4-beta.md). Ce nouvel APK n'a pas encore ete teste sur vehicule.

**Sortie :** un premier profil candidat choisi sur preuve, droits d'acces connus, criteres de test physique definis. Le developpement hors vehicule des lots suivants peut commencer ; l'activation SOME/IP sur vehicule reste bloquee tant que cette validation manque.

**Export pilote recu le 2026-10-06 :** `byd_apk_20261006_062116.zip`, produit par **1.9.5-beta / build 644**, identifie Android 10 / DL3 et le firmware `6125f_1for2_USER_SIGN_SX361_202606100404_Q2700`, confirme par l'utilisateur. Les quatre APK OEM sont intacts et identiques au precedent dump DL3 ; le nouveau desassemblage d'AmapService confirme le recepteur broadcast et son chemin CAN. `pm path`, les dumps de package et l'inventaire indiquent l'absence de `com.ts.car.someip.service` et `com.byd.naviauto` : leur manque dans le ZIP n'est pas un echec de copie. Ne pas deduire un profil SOME/IP de la seule propriete `ro.vehicle.type=DiLink50_5.0UI` sur ce systeme API29.

L'analyse decouvre aussi neuf executables sans extension corrompus par la conversion texte du zipper. Le correctif local conserve les ELF32/ELF64 sans relacher l'anonymisation des textes ; les APK et `.so` de cet export restent exploitables. Ce correctif est inclus dans le candidat 1.9.6-beta / build 645, et non dans l'APK 1.9.5-beta. [Preuves, limites et suite pour le pilote](OEM_EVIDENCE_DL3_SX361_20261006.md).

**Nouvel export recu le 2026-10-07 :** `byd_apk_20261007_192600.zip`, produit par la pre-release publiee **1.9.6-beta / build 645**, confirme le meme firmware SX361 et la presence du collecteur corrige sur la SEAL. Les neuf executables sont maintenant des ELF64 AArch64 valides : tables, segments et sections controles, lecture `readelf` sans erreur. Les quatre APK signes, les seize `.so` et les deux fichiers de framework sont identiques a ceux du 6 octobre ; les packages SOME/IP/naviauto restent absents. L'aide de `fission_screencap` fournit une piste de capture du combine, encore non verifiee sur vehicule. La prochaine preuve attendue pour ce pilote est le rendu du guidage AutoContainer en mode cluster seul, et non un nouvel export identique. [Comparaison, empreintes et essais restants](OEM_EVIDENCE_DL3_SX361_20261007.md).

**Essai Maps recu le 2026-10-08 :** le pilote ne voit aucun guidage sur le combine. Le rapport 1.9.6-beta montre l'acces aux notifications accorde, Maps `app.morphe.android.apps.maps` en navigation et des notifications publiees, mais aucun `NAV PARSE`. Ce package n'est pas dans la liste reconnue par le listener : il est rejete avant tout guidage AutoContainer. Le correctif **1.9.7-beta / build 646** ajoute ce package precis, avec reproduction de l'echec puis tests du guidage/effacement en mode cluster seul et de la reprise de source. Le dernier choix journalise est les deux sorties ; le prochain essai doit conserver HUD OFF / combine ON. Le correctif n'est pas dans la 1.9.6-beta publiee, et le rendu physique reste a verifier apres installation d'un build corrige. [Analyse et limites](../incidents/INC-20261008-195115-MAPS-MORPHE-CLUSTER.md) ; [notes et essais 1.9.7-beta](../releases/1.9.7-beta.md).

### Lot 1 : separer le routage sans changer la voie DL3

Points d'ancrage : `HudController`, `ClusterNavPusher`, `MapNotificationListenerService`, [HudNavigationData.kt](../../app/src/main/java/com/byd/dashcast/hud/HudNavigationData.kt).

- Introduire un petit contrat `HudOutput` avec debut, update, fin et resultat explicite. Envelopper d'abord le comportement DL3 existant sans changer ses ecritures ni son cycle de vie.
- Ajouter un selecteur testable : `OFF`, `LEGACY_DL3`, `SOMEIP(profil)` ; `AUTO` ne choisit qu'une voie prouvee compatible. Inconnu = aucune nouvelle ecriture, pas de CAN de secours sur un modele non reconnu.
- Distinguer HUD pare-brise, guidage du cluster et projection d'apps. La nouvelle sortie ne doit ni activer le container DL3 ni modifier le virtual display ou l'injection de touches.
- Conserver les points d'entree publics et les constructeurs existants de `HudNavigationData`. Ajouter, si necessaire, une enveloppe de session avec source, generation et horodatage monotone plutot que remodeler tous les parseurs.
- Faire passer les commandes debut/fin et les callbacks de transport par le meme ordre de session ; une fin ne peut pas etre perdue par la coalescence des mises a jour. Une generation invalidee ne peut plus reactiver le HUD.

**Sortie :** memes trames CAN/Amap/cluster, memes conditions d'activation et d'expiration sur DL3 ; aucun appel CAN/Setting/cluster DL3 en mode SOME/IP ou sur plateforme inconnue. Aucun comportement AAOS nouveau dans ce lot.

**Mise en oeuvre du 2026-09-14 :** [HudOutput.kt](../../app/src/main/java/com/byd/dashcast/hud/HudOutput.kt) definit le contrat `begin/update/end`, le resultat explicite, les modes et les profils. `HudController` enveloppe la voie DL3 existante, conserve la sortie effectivement ouverte pour la fermeture et resout le mode de production `AUTO` vers DL3 uniquement lorsque le filtre DL3/non-AAOS existant est satisfait. Aucun transport SOME/IP, reglage utilisateur, droit Android ou comportement de projection n'a ete ajoute. Le selecteur refuse un profil non prouve et ne fait aucun fallback CAN.

Verification : **760 tests unitaires** passent apres `--rerun-tasks`, dont les cinq cas de [HudOutputSelectorTest.kt](../../app/src/test/java/com/byd/dashcast/hud/HudOutputSelectorTest.kt) ; `:app:lintDebug` passe. Le banc OpenBYD reste a **29 PASS, 0 FAIL/SKIP**. Cela valide le refactoring et les modeles hors vehicule, pas le rendu physique.

**Choix des destinations ajoute le 2026-10-06 :** le pilote est une **BYD SEAL sans HUD**, Android 10 / DL3 / SX361. Son premier objectif est le guidage sur le combine derriere le volant ; l'absence de HUD ne doit pas bloquer cette sortie. **Parametres -> Guidage de navigation** propose un interrupteur general et deux choix persistants **HUD (pare-brise)** / **Combine d'instruments**. Les quatre combinaisons sont possibles ; l'arret general conserve les choix individuels. Le defaut reste les deux sorties pour les installations existantes.

Le mode **cluster seul** utilise `ClusterNavPusher` / `sendInfo2(4, NaviInfo)` sans activation, guidage ni effacement CAN/HUD. Le mode **HUD seul** utilise la voie CAN sans activation ni guidage AutoContainer. Le broadcast OEM Amap ne reste actif que lorsque les deux destinations sont choisies : son recepteur ecrit lui-meme sur CAN et ne convient donc pas a un mode exclusif. L'activation du cluster ne depend plus de l'acceptation CAN. La fermeture suit les ressources de l'ancienne session, jamais les nouveaux reglages.

Un changement de preference invalide la deduplication, annule la trame en attente et ferme l'ancienne session sur le writer serie. La prochaine notification de navigation fraiche ouvre le nouveau choix, meme si son texte est identique ; aucun replay d'une ancienne notification a partir des reglages. Le watchdog protege aussi le cluster seul. Ce choix ne modifie pas la projection d'apps et n'autorise aucun profil SOME/IP non valide. La separation des appels est testee hors vehicule ; le rendu et l'absence d'effets indirects des registres CAN sur le combine en mode HUD seul doivent encore etre verifies sur un vehicule equipe des deux ecrans. Ces reglages sont inclus dans le candidat 1.9.6-beta / build 645 ; ils ne sont pas dans la 1.9.5-beta.

Verification : **14 nouveaux tests**, dont les appels reels du controller/listener contre un Binder daemon factice ([routage](../../app/src/test/java/com/byd/dashcast/hud/NavigationOutputRoutingTest.kt), [preferences](../../app/src/test/java/com/byd/dashcast/hud/NavigationOutputPreferencesTest.kt)). Ils couvrent les quatre choix, l'effacement de l'ancienne destination, le refus CAN independant du cluster, la plateforme inconnue, le watchdog du cluster seul, la notification identique apres changement et l'annulation d'une trame en attente pendant une emission. Suite complete : **812 tests / 165 suites**, aucun echec, erreur ou skip. Lint release : **0 issue** ; APK release compile. Graphe AST actualise. Cela ne remplace pas les essais physiques du pilote.

### Lot 2 : implementer un seul profil SOME/IP

**Socle commun du 2026-10-03, profil encore a choisir :**

- [SomeIpHudBinderProtocol.kt](../../app/src/main/java/com/byd/dashcast/hud/SomeIpHudBinderProtocol.kt) implemente TX4/5/6 avec le token verifie, les identifiants `Long`, les deux longueurs de TX6, la lecture des exceptions et du statut, et le recyclage des deux Parcel dans `finally`. Un retour serveur negatif est conserve ; une transaction refusee ou une reponse tronquee ne devient pas un faux succes.
- [SomeIpHudTransport.kt](../../app/src/main/java/com/byd/dashcast/hud/SomeIpHudTransport.kt) utilise le composant OEM explicite depuis le contexte applicatif. Les callbacks Android publient seulement l'etat de connexion ; verification du descripteur, enregistrement de mort Binder et transactions doivent etre appeles sur le worker serie du proprietaire. Chaque binding a une identite distincte ; fermeture pendant le binding, callbacks perimes et reponses apres fermeture sont invalides. Une deconnexion suspend les emissions ; mort Binder, binding mort ou nul liberent l'enregistrement. Le proprietaire doit demander tout nouveau binding apres verification de sa session/source ; aucune donnee n'est rejouee automatiquement.
- La fermeture libere binding et death recipient sans emettre automatiquement TX5. Le profil devra definir les services qu'il peut arreter sans perturber la navigation native. Un appel Binder deja en cours ne peut pas etre interrompu ; son resultat tardif ne peut pas compter comme livraison de la session fermee.
- [SomeIpHudTransportTest.kt](../../app/src/test/java/com/byd/dashcast/hud/SomeIpHudTransportTest.kt) couvre le marshalling Android Parcel, les erreurs et les courses de connexion sous Robolectric API29, avec un Binder et un contexte de binding simules. Cela ne prouve ni une transaction Binder interprocessus reelle, ni l'ACL OEM, ni le rendu.

Ce socle n'est instancie par aucun chemin de production ou diagnostic. Aucun encodeur, profil compatible, preference, permission ou nouveau daemon n'est ajoute. Il ne clot pas le lot 2 : l'APK recepteur, le vehicule pilote, les payloads du profil retenu et l'observation physique restent a obtenir selon le lot 0. Les etapes restantes sont :

- Raccorder le socle `SomeIpHudTransport` au debut/update/fin du seul profil retenu, avec son inventaire de services et un rebind subordonne a la validite de session/source.
- Conserver le binding depuis l'app, comme la branche SOME/IP d'OpenBYD. Si l'ACL impose un autre contexte, decider a partir de cette preuve d'une extension du proxy existant et de son protocole ; ne pas creer un nouveau daemon ni modifier les permissions arbitrairement.
- Separer transport et encodage du profil. Utiliser une API protobuf pour les champs verifies ; conserver numeros/types/enveloppes exacts, y compris les deux longueurs de TX6. Pas de semantique inventee pour les champs opaques.
- Implementer uniquement le profil choisi au lot 0, puis un autre encodeur par profil valide. Ne pas choisir UI7 plutot que CN D5 uniquement parce que sa trame est plus courte.
- Produire des PNG originaux ou reutiliser les assets DashCast adaptes quand ils sont requis. Ne pas redistribuer les ressources OpenBYD/OEM. Verifier taille, transparence et orientation sur le recepteur.
- Completer les tests du banc avec des PNG valides et une geometrie issue de donnees valides : leurs buffers et coordonnees sont aujourd'hui substitues, et le faux Parcel ne valide pas a lui seul une transaction Android reelle.
- Tests avec un faux transport avant branchement : bytes exacts, metres/secondes, UTF-8 SOME/IP distinct du UTF-16LE CAN, codes propres au profil, services/topics `Long`, compteur modulo 256.
- Activer le nouveau chemin uniquement dans les diagnostics, derriere un drapeau desactive par defaut. Les trames de test synthetiques ne sont emises que pendant un test explicitement lance a l'arret.

**Sortie :** begin/update/end passent les tests hors vehicule ; le recepteur accepte les transactions ; une observation du HUD confirme au moins gauche/droite/tout droit/demi-tour, distance et effacement. Un retour SDK `0` seul ne valide pas ce lot.

**Verification hors vehicule du 2026-10-03 :** les tests de reference HUD/CAN et les **29 cas du banc OpenBYD** passent. La suite applicative complete passe avec **783 tests, 0 echec/erreur/skip**, dont **23 nouveaux tests de transport**. `:app:lintRelease` rapporte **0 erreur, 0 avertissement, 0 information**. La suite complete et lint ont ete forces avec `--rerun-tasks`, puis reexecutes sans filtre apres la derniere correction du transport. Graphify a ete actualise avec `graphify update .` (AST uniquement). Cette verification precede l'extension de collecte du lot 0 et la preparation de **1.9.4-beta** ; elle ne constitue aucun essai sur vehicule.

### Lot 3 : fiabiliser le cycle de vie et raccorder les sources actuelles

- Reutiliser d'abord les notifications deja reconnues par DashCast, avant de modifier leur parsing. Cela isole les problemes de transport des problemes d'acquisition.
- Suivre separement `session ouverte`, `transport pret`, `source fraiche`, `derniere emission acceptee`. Ne pas pretendre connaitre l'etat visuel du HUD depuis ces seuls indicateurs.
- Keepalive SOME/IP initial de 200 ms, conforme au client observe, uniquement pendant une session/source validee. Une seule emission a la fois et au plus un etat pending ; pas de rattrapage en rafale apres un blocage Binder.
- Ne jamais avancer l'horodatage de source avec une reemission. Conserver la politique de fraicheur existante comme reference et definir une politique testee pour les nouvelles sources, sans recopier le delai de 40 s d'OpenBYD.
- Distinguer fraicheur de navigation et validite de localisation : utiliser une position et un cap horodates, verifier permissions, age et precision avant de construire les coordonnees/guidelines. Ne pas reprendre les coordonnees par defaut `39.9042,116.4074` d'OpenBYD ni son cache de position sans limite d'age. Sans position valide : omettre les champs seulement si le recepteur le permet, sinon suspendre la sortie concernee avec diagnostic explicite. Tester perte/reprise du fix, cap absent et permissions retirees.
- Retirer les donnees pending au stop, a la perte de droits, au changement de source/profil et au deces Binder. Apres reconnexion, ne rejouer qu'une session encore actuelle et fraiche.
- Fermer les sorties effectivement ouvertes avant de modifier les preferences. Le banc reproduit un ancien helper SOME/IP non ferme si le mode CAN seul est lu au moment du stop ; ne pas prendre les nouvelles preferences comme inventaire des ressources actives.
- Recuperer aussi les bind en attente : stop avant `onServiceConnected`, callback tardif et echec partiel de start ne doivent laisser ni binding ni service demarre.
- Toute I/O synchrone reste hors UI et callbacks Android. Le stop doit annuler immediatement les prochains envois ; il ne peut pas garantir d'interrompre un appel Binder deja bloque. Mesurer ce risque et valider le comportement serveur a la perte du client.
- Arbitrage : source GPS explicite, stop ancien avant start nouveau ; surveiller la reprise de navigation native seulement si un signal fiable a ete identifie. Sans signal, coexistence declaree non supportee pour le profil ; ne pas entrer dans une boucle de reactivation contre le natif.
- Stop propre au profil : ni TX5 sur un service partage, ni broadcast global d'arret adoptes sans verifier l'impact sur la navigation native.

**Sortie :** demarrage en cours de route, longues etapes identiques, rafales, rejet, reconnexion, fin de route, retrait des permissions, changement Maps/Waze/profil et reprise du natif passes. Aucune fleche obsolete entretenue par le keepalive.

### Lot 4 : enrichir Maps et rendre Waze exploitable

**Maps en premier :**

- Ajouter la classification de la grande icone de notification sur un worker, en conservant les parseurs de texte/locales actuels.
- Construire un corpus de captures autorisees et une implementation independante de reconnaissance ; tester rotation, theme, contraste, tailles et variantes Maps/ReVanced. Ne pas recopier le registre de signatures comme du code produit.
- Inclure l'icone dans la detection de changement : memes textes mais nouvelle manoeuvre doivent produire une nouvelle generation. Borner le cout de comparaison et liberer/cacher les bitmaps correctement.
- Tester explicitement les divergences entre petite icone, grande icone et texte. En cas de doute, ne pas emettre une fleche devinee.

**Waze ensuite :**

- Ajouter un adaptateur de lecture d'accessibilite pour distance, rue, resume et numero de sortie ; verifier d'abord les services et aides de capture deja disponibles dans DashCast avant de creer de nouveaux composants Android.
- Si une surface projetee Waze est deja accessible, capturer uniquement la zone de fleche avec `PixelCopy` ; sinon prevoir `MediaProjection` avec consentement utilisateur et foreground service adapte. Aucune nouvelle virtual display privilegiee creee uniquement pour contourner ce consentement.
- Debuter avec 2 s entre captures, comme le client analyse ; mesurer latence et charge avant de changer cette cadence. Ignorer resultat hors session, bounds invalides, surface detruite et capture en retard.
- Fusionner texte et icone seulement pour la meme source/session et des observations suffisamment proches. Une fleche ancienne ne doit pas etre associee a la distance du virage suivant.
- Borner le numero de sortie a `1..10` ; distinguer CW/CCW sans deviner a partir de la seule locale ; conserver les codes CAN et remapper par profil de sortie.
- Si les voies sont ajoutees ensuite, inclure dans la deduplication les voies de fond ET les voies selectionnees ; le banc reproduit l'absence d'update Launcher quand seule la selection change.
- Accessibilite Maps pour completer rue/ETA : increment optionnel apres validation des notifications. Voies, cameras, vitesse et panneaux sont hors du premier increment.

**Sortie :** jeux de fixtures Maps/Waze passes, aucune emission si acquisition ambigue, ressources capture fermees au stop/revocation, retour au parsing existant seulement s'il produit lui-meme une manoeuvre valide.

### Lot 5 : reglages, diagnostics et livraison progressive

- Migration : installations DL3 gardent leur comportement. Les profils nouveaux sont opt-in et affiches seulement quand detectes/valides ; selection manuelle reservee au diagnostic tant que la compatibilite n'est pas prouvee. Aucun mode `DUAL` par defaut.
- Reglages separes pour source GPS, sortie HUD et projection cluster. Afficher permissions manquantes, profil selectionne, connexion, age de source et dernier resultat de transport, sans confondre "envoye" et "affiche".
- Reutiliser [HudDiagActivity.kt](../../app/src/main/java/com/byd/dashcast/hud/HudDiagActivity.kt) pour le banc ; un seul producteur a la fois, sequence courte, stop garanti en sortie de l'ecran. Ni trames synthetiques ni envoi en arriere-plan apres le test.
- Journalisation des etats, retours, latence et raisons d'arret ; pas de rues, coordonnees, texte de notification ou screenshots par defaut. Captures diagnostic sur consentement, avec limites de taille et retention.
- Toute nouvelle string utilisateur : FR + les 11 locales existantes, dans le meme lot. Ne pas ajouter de permissions a toutes les installations si un profil n'en a pas besoin.
- Tester un DL3 temoin et un vehicule du premier profil SOME/IP. Premiere validation a l'arret ; essais de latence en navigation seulement apres validation des commandes et de l'effacement, avec observation deleguee au passager.
- Livrer en pre-release, nouveau mode desactive par defaut, desactivation immediate et retour a la voie precedente seulement si cette voie est deja validee sur le vehicule.

**Sortie :** aucune regression DL3, matrice de compatibilite documentee par build/profil, opt-out teste. Avant APK distribuable : incrementer `versionCode` et `versionName`, `assembleRelease` uniquement. Aucun commit/push sans demande.

## Verification par lot

| Lot | Tests / preuves obligatoires |
|---|---|
| 0-1 | `CanBatchOperationTest`, `HudActivationRetryTest`, `HudControllerLivenessTest`, `HudDeliveryTrackerTest`, `HudAmapBroadcastTest`, `ClusterNavPusherTest`, `ClusterClearFrameTest` ; selection de sortie sans ecriture sur plateforme inconnue |
| 2 | Payloads de reference par profil, ordre start/event/stop, retours/exception/transaction refusee, recyclage des Parcel, choix des services, rendu observe sur vehicule |
| 3 | Horloge et transport simules : timeout source, keepalive sans rafraichissement, position/cap absents ou perimes, permissions localisation retirees, pending borne, stop prioritaire, mort Binder/rebind, callbacks perimes ; tests de retrait/failover existants |
| 4 | `MapNotificationDedupeWiringTest`, `MapNotificationSourceFailoverTest`, `MapNotificationRemovalPolicyTest`, `NavGuidancePolicyTest`, `NavTextParsersCharacterizationTest`, `NavKeywordOrderTest`, `ArabicNavParsingTest` + fixtures d'icones/capture |
| 5 | Permissions refusees/retirees, opt-out, coexistence native, fin d'app/process, projection independante, latence/charge, traductions et build release |

Verification de l'analyse, sans Android ni vehicule :

```bash
rtk proxy node docs/openbyd-2.5/audit/verify.mjs
```

Commande cible pour la reference applicative et les lots HUD :

```bash
rtk proxy ./gradlew :app:testDebugUnitTest --tests 'com.byd.dashcast.hud.*' --tests com.byd.dashcast.system.CanBatchOperationTest --offline
```

Ce sont des tests unitaires, pas un APK debug a laisser sur vehicule. Ajouter les filtres des nouveaux tests si leurs packages different. Au gate final, executer aussi la suite complete et les controles lint/i18n requis ; forcer `--rerun-tasks` pour ne pas annoncer comme resultat global un rapport issu d'une execution filtree.

## Perimetre exclu du premier increment

- Ecritures Statistic/ISA, limitation de vitesse, ADAS, voies/cameras et proprietes non indispensables au guidage de base.
- Nouvel implementation AAOS/VHAL, modification du moteur de projection ou remplacement du proxy.
- Activation automatique des trois profils, heuristique "package present = compatible", fallback CAN aveugle et diffusion Amap a tous les packages.
- Portage du contexte OpenBYD neutralisant les controles locaux, redistribution de ses stubs/classes/assets, presentation de son succes textuel comme preuve du rendu.

## Ordre recommande

1. Lot 0 et tests de reference ; commencer le lot 1 sans changer les sorties visibles.
2. Lots 2-3 sur **un profil prouve**, avec les sources deja reconnues. C'est le premier jalon fonctionnel.
3. Lot 4 Maps, puis Waze ; l'acquisition peut etre testee hors vehicule pendant la validation du recepteur.
4. Lot 5 et pre-release sur le profil pilote ; autres profils et donnees avancees uniquement en increments ulterieurs.

La principale dependance externe est la preuve cote recepteur/vehicule. Les lots 0-2 doivent aussi terminer le recoupage des branches du profil retenu et ses tests de payload : le contrat d'emission partiellement etabli ne remplace pas cette verification. Aucune estimation ferme multi-profils avant le lot 0. Le lot 1 modifie maintenant le routage de production sans changer les sorties DL3 visibles ; aucun code SOME/IP de production n'est actif.
