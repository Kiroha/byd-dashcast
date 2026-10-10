# SendInfo2 : transmettre aussi les distances et le temps formatés

Le message OEM `NaviInfo` prévoit la distance avant la prochaine manœuvre,
la distance totale restante et le temps restant, sous forme numérique et
textuelle. DashCast envoyait les nombres par `sendInfo2(4, ...)`, mais
laissait les trois champs texte vides. Le correctif complète ces champs.
Il ne prouve pas encore leur affichage physique sur le combiné.

## Contrat retrouvé dans l'application OEM

Source locale décompilée, conservée hors dépôt :
`/home/ccarre/app_byd/re_hud/src/com.example.amapservice/sources/com/example/amapservice/AmapService.java`.

- L349–359 : `SEG_REMAIN_DIS`, `ROUTE_REMAIN_DIS` et `ROUTE_REMAIN_TIME`
  alimentent respectivement `curToSegmentDist`, `routeRemainDist` et
  `routeRemainTime` (mètres, mètres, secondes).
- L421–439 : les versions formatées sont conservées dans
  `SegRemainDisAuto`, `routrRemainDisAuto`, `routrRemainTimeAuto`. Un texte
  absent est remplacé par `"-1"`.
- L157–159 : `reSetGuideInfo()` remet les trois textes à `"-1"`.
- L765–773 : ces chaînes sont sérialisées avec les nombres dans le même
  FlatBuffer, puis transmises à `sendInfo2(4, bytes)`.

Ces éléments établissent le contrat d'entrée OEM. Ils ne démontrent pas
que le firmware exige ces chaînes pour afficher une distance.

## Comportement du correctif

- `ClusterNavPusher` remplit les trois textes, sans changer les nombres,
  les icônes, les sorties sélectionnées, l'activation ni les reprises.
- `NavigationTextFormatter` reprend exactement les fonctions auparavant
  privées de `HudController`. La voie broadcast utilise le même format
  qu'avant : mètres sous 1 000 m, kilomètres avec une décimale ensuite,
  minutes entières ou heures/minutes. Le séparateur reste celui de
  `Locale.US`, même si la langue Android utilise une virgule.
- Une donnée absente ou négative produit `"-1"` sur la voie `sendInfo2`.
  Une donnée réellement nulle reste `"0 m"` ou `"0 min"`. Distance totale
  et temps restant sont traités indépendamment ; aucun total n'est inventé.
- La fin de trajet remet les trois textes à `"-1"`, en conservant l'état
  d'arrêt 9, l'icône -1 et les valeurs numériques -1.
- Le banc Diag `sendInfo2` et le balayage d'icônes incluent aussi les
  textes. Le banc demande une distance avant manœuvre de 300 à 100 m,
  une distance totale de `"1.2 km"` et un temps de `"5 min"`.

Aucune chaîne d'interface, permission, sélection de source ou modification
du protocole Satellite n'est ajoutée. Les sources Maps installées dans la
voiture et Satellite utilisent la même voie de sortie quand le combiné
est sélectionné. Les informations absentes à la source restent absentes.

## Vérifications et validation dans la voiture

Sept nouveaux tests reproduisent les textes manquants avant correction.
Les tests ciblés passent ensuite. Ils décodent la trame du vrai contrôleur
sur un Binder d'enregistrement, couvrent les seuils m/km et min/h, zéro,
les locales française/arabe, les totaux absents ou invalides, les
disponibilités indépendantes, les mises à jour, la perte des totaux et
l'arrêt. Ils vérifient aussi le texte broadcast existant et le trajet
notification Maps → writer → combiné.

Vérifications complètes : **956 tests JVM dans 180 suites**, sans échec,
erreur ni test ignoré ; **10 scénarios JavaScript Satellite** et **35 tests
du scanner de publication** réussis. `lintRelease` ne contient aucune
erreur ni avertissement. Les builds debug et release réussissent et le
scanner de confiance accepte l'APK release non debuggable avec la
signature habituelle. `graphify update .` actualise le graphe avec les six
avertissements de parsing déjà présents. La copie d'archivage du mapping
est exclue du build local pour préserver le mapping de la version publique
inchangée ; les assets figés et leurs hashes ont été revérifiés.

Sur une compilation contenant le correctif, tester d'abord un trajet Maps
sans lancer le banc Diag au préalable : distance avant la manœuvre,
totaux lorsqu'ils sont disponibles dans la notification, puis arrêt du
guidage. Utiliser ensuite le banc à l'arrêt pour isoler les champs avec
les valeurs connues ci-dessus ; terminer le dialogue de résultat pour
déclencher le nettoyage. Photographier le combiné et conserver un rapport
pendant un échec.

Le correctif est local à ce stade. L'APK public **1.9.11-beta / 650**
ne contient pas encore cette modification ; aucun asset publié n'est
remplacé par la compilation de vérification.
