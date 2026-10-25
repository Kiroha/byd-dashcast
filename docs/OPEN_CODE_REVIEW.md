# Open Code Review dans Codex

Installation vérifiée le 25 octobre 2026 : OCR **1.13.3** (`375f64c`),
plugin Codex **1.0.0**, source marketplace
`375f64cd6f67a48a135da83cfcf59c52669a09cb` du dépôt
[alibaba/open-code-review](https://github.com/alibaba/open-code-review).
Git 2.43.0 (minimum upstream 2.41), Node 24.22.1 et Codex CLI 0.164.0.
Le plugin natif est installé et activé pour cet utilisateur ; ses compétences
seront disponibles dans une nouvelle tâche Codex. Aucun doublon de skill manuel.

## Utilisation

Dans Codex, demander par exemple :

> Utilise $open-code-review-delegate pour revoir mes changements, sans les modifier.

La délégation utilise le modèle de la session Codex : aucune clé API OCR ni
configuration de fournisseur supplémentaire. Le skill `$open-code-review`
classique utilise `ocr review` et nécessite un fournisseur LLM distinct ;
les instructions du projet privilégient donc explicitement la délégation.

Depuis la racine du dépôt :

```bash
ocr delegate preview --format json
ocr delegate rule --format json app/src/main/java/com/byd/dashcast/MainActivity.kt

# Comparaison avec la branche de base choisie (remplacer les références)
ocr delegate preview --format json --from main --to HEAD

# Un commit choisi
ocr delegate preview --format json --commit <commit>
```

`preview` sélectionne les fichiers et `rule` résout les consignes. Ces commandes
ne produisent pas à elles seules une revue : Codex lit ensuite les diffs, le
contexte et les tests, applique les règles et rend des constats avec fichier,
ligne, déclencheur et conséquence. Chaque fichier sélectionné doit être revu
ou explicitement écarté avec une raison. Le routage triage/propriétaire unique,
les spécialistes pertinents et le verifier restent applicables.

## Règles du projet

[`.opencodereview/rule.json`](../.opencodereview/rule.json) est chargé
automatiquement depuis la racine Git, sans `--rule`. La première entrée
correspondante gagne ; `merge_system_rule: true` conserve les règles intégrées
propres au langage. Les entrées couvrent Android/BYD et ses daemons, les outils
Qt/QML du cluster, le relais Azure/Node et les autres fichiers.

Les tests Kotlin/Java, JS du relais et Python du cluster sont explicitement
inclus car OCR exclut ces tests par défaut. QML est inclus pour les ressources
cluster. `include` contourne les exclusions par défaut ; ce n'est pas une liste
exclusive. Les exclusions du projet gardent la priorité sur ces inclusions.
Les artefacts, sorties de build, graphes, diagnostics et fichiers de secrets
ne sont pas des cibles de revue. Les templates `.env.*` sont également exclus
par prudence dans cette configuration.

Les filtres ne sont pas une sandbox : Codex peut lire d'autres fichiers pour
le contexte, et doit respecter les instructions concernant les secrets et les
diagnostics. La preuve statique/JVM ne valide pas la compatibilité d'une voiture.
Aucune commande ADB, effet véhicule ou activation expérimentale n'est nécessaire
pour une revue. Les fixtures exclues peuvent être consultées comme contexte
quand elles ne contiennent pas de données sensibles.

Pour sauvegarder des rapports, utiliser `.opencodereview/results/`, ignoré par
Git et par OCR. Ne pas placer de clé ou de configuration utilisateur OCR dans
le dépôt. Le mode OCR avec fournisseur stocke sa configuration utilisateur dans
`~/.opencodereview/config.json` ; il n'a pas été configuré ici.

## Installation et mise à jour

```bash
npm install -g @alibaba-group/open-code-review@latest
codex plugin marketplace add alibaba/open-code-review
codex plugin add open-code-review-codex@open-code-review --json
```

Sur une installation existante :

```bash
npm install -g @alibaba-group/open-code-review@latest
codex plugin marketplace upgrade open-code-review --json
codex plugin add open-code-review-codex@open-code-review --json
```

Ouvrir ensuite une nouvelle tâche Codex pour recharger le plugin. Pour reproduire
l'installation vérifiée, installer le paquet npm `@1.13.3` et ajouter le
marketplace avec `--ref 375f64cd6f67a48a135da83cfcf59c52669a09cb`.

## Vérifications locales

```bash
ocr --version
codex plugin list --marketplace open-code-review --json
ocr delegate preview --format json
ocr delegate rule --format json app/src/main/java/com/byd/dashcast/MainActivity.kt
ocr rules check app/src/main/java/com/byd/dashcast/MainActivity.kt
```

Les contrôles d'installation n'appellent aucun LLM OCR et n'effectuent aucun
accès véhicule. Une revue complète du code par le modèle n'est pas un test
d'installation ; elle se lance ensuite avec le skill de délégation.

Références : [intégration Codex](https://github.com/alibaba/open-code-review/blob/main/plugins/open-code-review/README.md),
[règles et filtres](https://github.com/alibaba/open-code-review/blob/main/pages/src/content/docs/en/review-rules.md),
[skill de délégation](https://github.com/alibaba/open-code-review/blob/main/plugins/open-code-review/skills/open-code-review-delegate/SKILL.md).
