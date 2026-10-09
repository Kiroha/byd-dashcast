# SEAL SX361 — seul le demi-tour apparait dans le combine (2026-10-09)

Les deux rapports **1.9.7-beta / build 646** confirment que les notifications
Maps Morphe arrivent maintenant dans le listener. Le blocage restant est
l'acquisition de la manoeuvre : la petite icone relevee est le logo generique
`maps_2025`, et la plupart des textes ne contiennent aucune instruction de
virage. Le listener actuel ne lit pas `Notification.getLargeIcon()`.

**« Faites demi-tour » est reconnu dans le texte** ; les notifications
distance/route sont rejetees faute de direction reconnue. Il ne faut pas
transformer une route sans direction en virage ou en « tout droit » par defaut.

Le pilote rapporte avoir vu uniquement une fleche de demi-tour et fournit
une photo du combine montrant ce glyphe en haut de l'ecran. Cette observation
concorde avec le parsing du demi-tour dans le rapport. C'est une premiere
preuve visuelle de guidage sur ce pilote SX361, sans validation des autres
manoeuvres, des distances affichees ou de l'effacement physique.

## Rapports et integrite

| Message | Capture | Reception Europe/Paris | Octets | Lignes | SHA-256 |
|---|---|---|---:|---:|---|
| 96 | `byd_bugreport_20261009_134002_6e44d4abfbee42efbaf05badffea401c.txt` | 2026-10-09 13:40:42 | 1 160 226 | 9 959 | `c1c7804edabce87872f945ca3b82912bf89d7bdcc95bdd58cd91f56608d86545` |
| 97 | `byd_bugreport_20261009_134051_edd5dcb5d6514bdfac2b415c88420728.txt` | 2026-10-09 13:41:10 | 1 132 515 | 9 422 | `601d8e4fe2df3618a389455e715c0e000f17fc64116e234146c32c13cbbc435f` |

Les fichiers bruts restent hors du depot. Les rues, destinations, resumes
d'itineraire et identifiants personnels ne sont pas recopies ici.

## Preuves communes et difference entre les captures

- Les deux rapports indiquent **1.9.7-beta (646)** et Android 10 / DiLink 3.0.
- Acces aux notifications : **`granted`**, lignes 908-909 du premier et
  367-368 du second.
- Choix effectif a **13:37:39.203** :
  `NavigationOutputs(hud=false, cluster=true)`, lignes 9880 et 9331.
  Le pilote teste donc bien **cluster seul**.
- Aucun `NAV UNSUPPORTED` : le rejet du package corrige dans 1.9.7 n'apparait
  plus. Les deux journaux recoivent `app.morphe.android.apps.maps`.
- Le premier journal contient **54** entrees `NAV RAW`, le second **57**.
  Ils recouvrent la meme session ; leur union apres deduplication contient
  **57 notifications**, pas 111 observations independantes. Le logcat du
  premier repete aussi six de ces entrees.
- Huit notifications portent le texte de demi-tour, une porte une instruction
  « Prendre la direction ... », et les 48 autres n'ont aucun mot-cle de
  manoeuvre reconnu. Beaucoup ne donnent que la distance et la route ;
  certaines correspondent a un etat transitoire sans distance.
- `android.bigText` est vide dans les 57 captures. Le texte etendu ne fournit
  donc pas les directions manquantes.

Ligne 9950 du premier rapport, 9401 du second :

```text
[13:40:13.352][INFO][MapNavListener] NAV PARSE icon=9 src=text smallIcon='maps_2025' titleLen=4 textLen=15 -> dist=90 road=no eta=yes
```

Le code interne **9** est `ICON_U_TURN_LEFT`. `ClusterNavPusher.toAmapIcon(9)`
produit **8**, le code du demi-tour gauche dans `NaviInfo.nextTurnIcon`.
Les mises a jour de distance suivantes restent du meme type de manoeuvre.
`NAV PARSE` est volontairement journalise une fois par couple icone/route :
une seule ligne ne signifie pas qu'une seule trame a ete envoyee.

Le premier logcat montre explicitement `no maneuver found` pour plusieurs
notifications comportant une distance positive et une route. Le code appelle
`isCompleteGuidance` avant `HudController` et refuse ces valeurs : elles
n'atteignent pas le mapping des icones cluster.

Le second rapport contient a **13:40:49.361** le declenchement du watchdog
apres 12 secondes sans mise a jour. La derniere notification de demi-tour
capturee date de **13:40:36.346**. Ce journal prouve une demande de fermeture,
pas que le glyphe a visuellement disparu. La photo indique 13:42 sur le
combine, mais les rapports ne couvrent pas ce moment et ne permettent pas
d'etablir sa relation temporelle exacte avec les emissions.

## Cause et limite de la preuve

Le listener fait actuellement : **nom de ressource de la petite icone ->
mots-cles du texte -> refus si la manoeuvre reste inconnue**. Un logo
`maps_2025` ne distingue pas gauche, droite, tout droit ou rond-point. Ajouter
ce nom au mapping ferait attribuer la meme direction a toutes les manoeuvres.
Modifier les valeurs AutoContainer n'apporterait aucune direction aux
notifications rejetees en amont.

La prochaine source a verifier est la **grande icone de notification Maps**.
L'analyse statique precedente d'OpenBYD 2.5 etablit qu'il charge
`Notification.getLargeIcon()` et classe son image ; cela identifie un point
d'acquisition, pas une preuve de la presence ou du format exact de l'image
dans cette version Morphe. Voir le
[contrat Maps et ses limites](../openbyd-2.5/OPENBYD_2_5_HUD_INTEROP.md).

Les deux rapports recus sont des fichiers texte : ils ne contiennent ni
bitmap de manoeuvre ni metadonnees `largeIcon`. La photo du combine apporte
la preuve du rendu du demi-tour, pas l'image source des autres manoeuvres.
Le commutateur existant **Capture raw nav-notification text** capture le texte
uniquement ; il ne constitue pas un export des images.

## Suite du lot Maps

1. Obtenir des images de notification Maps avec une manoeuvre connue, en
   particulier gauche/droite/tout droit, puis verifier la source `largeIcon`
   sur cette version. Une capture de la notification deployee fournit une
   premiere reference visuelle ; les bitmaps originaux restent preferables
   pour calibrer et tester la reconnaissance.
2. Implementer une reconnaissance independante a partir d'un corpus autorise,
   sur le worker borne existant. Conserver le refus des images inconnues ou
   ambigues ; ne pas reprendre le registre de signatures/assets OpenBYD.
3. Inclure les changements d'image dans la deduplication : textes identiques
   et nouvelle fleche doivent produire une nouvelle generation de guidage.
   Associer image, distance et source dans la meme notification/session.
4. Verifier gauche/droite/tout droit/demi-tour, distances et effacement sur le
   combine du pilote. Le seul demi-tour observe ne valide pas la table complete.

Analyse et documentation uniquement : aucun protocole, mapping d'icone,
controle de fraicheur ou comportement de production n'est modifie ici.
