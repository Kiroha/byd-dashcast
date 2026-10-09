# Reprise du listener de navigation après démarrage ou veille

Implémentation : 2026-10-09. Cette modification est incluse dans **1.9.8-beta / 647** ; elle n'est pas dans la pré-release publiée 1.9.7-beta. [Notes de version et essai](../releases/1.9.8-beta.md).

Le listener HUD/cluster était reconnecté par Android, mais DashCast ne demandait pas de reconnexion si cette reprise échouait. Le hotspot disposait déjà d'une surveillance dédiée.

## Fonctionnement

- [NavigationListenerKeeper.kt](../../app/src/main/java/com/byd/dashcast/hud/NavigationListenerKeeper.kt) contrôle le listener depuis le worker permanent de `ProxyKeeperService`, à chaque heartbeat de 10 secondes, avant les opérations du proxy BYD. La récupération ne dépend pas de la présence de son Binder.
- Chaque `onStartCommand` du keeper demande aussi un contrôle sur ce même worker. `BootReceiver` démarre déjà ce service au boot et lors des `BOOT_COMPLETED` redélivrés par DiLink à la remise du contact. Aucun second timer ni nouvelle Activity n'est ajouté.
- Le keeper demande `NotificationListenerService.requestRebind` uniquement si le guidage est activé, au moins une destination est sélectionnée et l'accès à ce composant précis est autorisé. Il ne modifie ni les autorisations ni les choix HUD/cluster.
- Les callbacks du listener confirment la connexion, sa perte et sa destruction. Un jeton par instance empêche les callbacks tardifs d'une ancienne instance d'invalider la connexion actuelle. Le keeper ne conserve pas de référence au service.
- Une demande n'est jamais présentée comme une connexion réussie. Les demandes non confirmées ou rejetées sont réessayées après 30, 60, 120 puis 300 secondes, sans abandon définitif. Les contrôles rapprochés partagent la cadence et une seule demande peut être en cours. Le verrou d'état n'est pas conservé pendant l'appel Binder.
- Une connexion confirmée réinitialise cette cadence. Une autorisation retirée invalide la connexion connue, même sans callback de déconnexion ; les demandes restent suspendues jusqu'au retour de l'autorisation. L'arrêt du guidage suspend les demandes sans déconnecter un listener sain.
- L'absence d'itinéraire ou de notifications ne déclenche pas de relance. À la reconnexion, le listener conserve son traitement des notifications de navigation déjà actives, avec les filtres de packages existants et le routage sélectionné.

Les rapports de bug ajoutent `NAVIGATION LISTENER (local supervision)` : connexion confirmée, éligibilité au dernier contrôle, niveau de retry, demande en cours et âge de la dernière demande. Les transitions et demandes sont aussi inscrites dans le journal, sans contenu de notification ni coordonnées.

## Vérification hors véhicule

**21 nouveaux tests** : 12 cas de cadence, autorisation, cycle de vie et concurrence ; 8 cas d'intégration Android API29 couvrant les préférences, le composant exact, les callbacks réels, le heartbeat et les démarrages répétés du keeper ; 1 test de reprise d'une notification Morphe déjà active vers le cluster seul contre un Binder factice.

Suite complète : **836 tests / 167 suites**, aucun échec, erreur ou skip. `:app:lintRelease` : **0 anomalie**. `:app:assembleRelease` réussit. Graphe AST actualisé avec `graphify update .`.

## Essai restant sur la SEAL

1. Conserver l'accès aux notifications et choisir le cluster seul dans les paramètres. Vérifier un guidage actuellement reconnu, par exemple un demi-tour.
2. Arrêter puis remettre le contact. Lancer un nouvel itinéraire Maps depuis le lanceur de DiLink avant de rouvrir DashCast, puis exporter un rapport. Vérifier les horaires du démarrage/reconnexion et la reprise du guidage.
3. Répéter après un redémarrage complet de DiLink, puis vérifier qu'un listener connecté et sans itinéraire ne reçoit pas de demandes répétées.

Les tests prouvent la logique et les appels Android simulés, pas la reprise physique sur SX361. Un arrêt forcé de l'application peut empêcher son redémarrage automatique. La reconnaissance de la grande icône Maps reste un travail distinct : cette supervision ne rend pas décodables les manœuvres absentes du texte.
