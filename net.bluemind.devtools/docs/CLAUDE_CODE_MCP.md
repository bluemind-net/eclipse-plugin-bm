# Lancer les BM Plugin Tests depuis Claude Code

Le plugin Eclipse expose un serveur MCP local (HTTP JSON-RPC, loopback uniquement) qui permet
à Claude Code de déclencher un test dans l'Eclipse qui tourne et de récupérer le résultat
(outcome + stdout + stderr + stacktraces).

## 1. Dans Eclipse (une seule fois)

**Window → Preferences → BlueMind**, cocher **"Enable MCP server for Claude Code"**
(activé par défaut à partir de la 1.4.0).

Au démarrage d'Eclipse, le plugin écrit un fichier de config :

```
~/.config/bluemind/mcp/eclipse-<hash>.json
```

- `<hash>` = SHA-256 (tronqué à 12 chars) du chemin absolu du workspace Eclipse courant.
- Le fichier est `chmod 600`.
- Chaque instance Eclipse a son propre fichier → plusieurs Eclipse en parallèle (une par branche)
  sont correctement distingués.
- Le fichier est supprimé proprement quand Eclipse s'arrête (ou quand on désactive le MCP).

Contenu :

```json
{
  "url":        "http://127.0.0.1:<port>/mcp",
  "token":      "<bearer token généré à chaque démarrage>",
  "authHeader": "Authorization",
  "authScheme": "Bearer",
  "workspace":  "/chemin/absolu/du/workspace/eclipse",
  "repoRoot":   "/chemin/absolu/du/repo BlueMind (racine, dérivée du POM global)",
  "branch":     "nom-de-la-branche-git-courante",
  "projects":   ["net.bluemind.foo", "net.bluemind.foo.tests", "..."],
  "pid":        12345,
  "writtenAt":  1745280000000
}
```

## 2. Dans Claude Code

### Approche recommandée : instructions dans CLAUDE.md (pas de MCP client à configurer)

Puisque l'URL et le token changent à chaque démarrage d'Eclipse ET par workspace, la voie la
plus robuste est que Claude Code fasse ses requêtes **directement via `curl`**.

Ajoute ceci dans le `CLAUDE.md` du repo BlueMind (ou dans un doc partagé avec l'équipe) :

````markdown
## Lancer des tests dans Eclipse

Pour lancer un BM Plugin Test dans l'Eclipse qui tourne avec le plugin BM Test Runner :

1. Trouver le fichier de config de l'Eclipse ouvert sur ce workspace :

   ```bash
   # Le workspace Eclipse est généralement distinct du repo git — il peut se trouver dans
   # ~/dev/eclipse-workspace/, ~/workspaces/bm/, etc. Demander à l'utilisateur si inconnu.
   # On scanne tous les fichiers et on prend le premier (ou on filtre par workspace si besoin).
   ls ~/.config/bluemind/mcp/eclipse-*.json
   ```

2. Extraire `url` et `token` du fichier (il peut y en avoir plusieurs si plusieurs Eclipse
   tournent — choisir celui dont `workspace` correspond, ou celui dont `projects` contient
   le bundle concerné).

3. Appeler l'un des 4 outils via JSON-RPC. Les noms d'outil :
   - `refresh_projects` — `{ "projects": ["net.bluemind.foo", "net.bluemind.foo.tests"] }`
     → refresh filesystem + build incrémental + attend la fin des jobs, retourne les erreurs
     de compil s'il y en a. **À appeler après chaque édition de fichier et avant un `run_*`**
     pour garantir que les tests tournent avec le code compilé à jour.
   - `run_bundle_tests` — `{ "project": "net.bluemind.foo.tests", "mode": "run" }`
   - `run_class_tests`  — `{ "project": "...", "className": "net.bluemind.foo.tests.MyTest" }`
   - `run_test_method`  — `{ "project": "...", "className": "...", "methodName": "testFoo" }`

   `mode` est optionnel, `run` (défaut) ou `debug`.

   Exemple complet (bundle entier) :

   ```bash
   CFG=$(ls ~/.config/bluemind/mcp/eclipse-*.json | head -1)
   URL=$(jq -r .url "$CFG")
   TOKEN=$(jq -r .token "$CFG")

   curl -s -X POST "$URL" \
     -H "Authorization: Bearer $TOKEN" \
     -H "Content-Type: application/json" \
     --max-time 1800 \
     -d '{
       "jsonrpc": "2.0",
       "id": 1,
       "method": "tools/call",
       "params": {
         "name": "run_bundle_tests",
         "arguments": { "project": "net.bluemind.foo.tests" }
       }
     }' | jq -r '.result.content[0].text'
   ```

4. Le résultat est un **résumé court** (markdown) :
   - Status PASSED / FAILED
   - Compteurs (total/passed/failed/errored/ignored) et durée
   - Liste des failures (class#method, sans stacktrace)
   - **Chemins vers les artefacts locaux** : `stdout.log`, `stderr.log`, `failures.md`
     sous `~/.cache/bluemind/mcp/runs/<horodatage>-<slug>/`
   - Les 50 derniers runs sont conservés, les plus anciens sont purgés automatiquement

5. Si le résumé ne suffit pas, lire les artefacts localement :
   ```bash
   cat ~/.cache/bluemind/mcp/runs/<dir>/failures.md   # traces des échecs
   tail -n 500 ~/.cache/bluemind/mcp/runs/<dir>/stderr.log
   grep -n ERROR ~/.cache/bluemind/mcp/runs/<dir>/stdout.log
   ```
   → ça évite de faire transiter des centaines de Ko via la réponse MCP.

Ne pas lancer plusieurs tools/call en parallèle — le serveur sérialise les runs et renvoie
une erreur si un run est déjà actif.
````

### Sélection du bon Eclipse quand plusieurs tournent

Si plusieurs Eclipse sont ouverts sur des branches différentes, les fichiers de config coexistent.
Stratégies de sélection pour Claude Code, du plus précis au plus flexible :

1. **Par nom de projet** : cherche le fichier dont `projects[]` contient le bundle visé.
   ```bash
   jq -r 'select(.projects[] == "net.bluemind.foo.tests") | .url' ~/.config/bluemind/mcp/eclipse-*.json
   ```
2. **Par workspace** : demander à l'utilisateur le chemin du workspace Eclipse, filtrer sur `.workspace`.
3. **Fallback** : s'il n'y en a qu'un, le prendre.

### Alternative : déclarer un serveur MCP dans `.mcp.json`

Si tu préfères que Claude Code voit les outils MCP "officiellement" (UI auto-discovery), tu peux
créer `~/.claude.json` ou `<repo>/.mcp.json` :

```json
{
  "mcpServers": {
    "bm-eclipse": {
      "type": "http",
      "url": "http://127.0.0.1:PORT/mcp",
      "headers": { "Authorization": "Bearer TOKEN" }
    }
  }
}
```

Inconvénient : l'URL et le token changent à chaque redémarrage d'Eclipse → il faut regénérer
ce fichier. Un script `bin/bm-mcp-config.sh` peut le faire :

```bash
#!/usr/bin/env bash
CFG=$(ls ~/.config/bluemind/mcp/eclipse-*.json | head -1)
jq -n --arg url "$(jq -r .url "$CFG")" --arg tok "$(jq -r .token "$CFG")" '
{ mcpServers: { "bm-eclipse": { type: "http", url: $url, headers: { Authorization: ("Bearer " + $tok) } } } }
' > .mcp.json
```

Pour la plupart des usages quotidiens, la première approche (curl + instructions CLAUDE.md)
est plus simple et supporte naturellement le multi-Eclipse.

## 3. Surface d'outils

| Tool              | Arguments                                        | Description                                         |
|-------------------|--------------------------------------------------|-----------------------------------------------------|
| `refresh_projects`| `projects` (array)                               | Refresh + build + check compile errors              |
| `run_bundle_tests`| `project` (+ `mode?`)                            | Tous les tests d'un bundle `*.tests`                |
| `run_class_tests` | `project`, `className` (FQN) (+ `mode?`)         | Tous les `@Test` d'une classe                       |
| `run_test_method` | `project`, `className`, `methodName` (+ `mode?`) | Une seule méthode `@Test`                           |

Un `tools/call` bloque la réponse HTTP jusqu'à la fin de l'opération (timeout interne 30 min
pour les runs de test ; un refresh typique se mesure en secondes).

**Workflow recommandé** : après chaque édition de fichier côté Claude Code, appeler
`refresh_projects` avec la liste des bundles touchés avant le `run_*`. Ça garantit qu'Eclipse
voit les changements disque et qu'un nouveau cycle de compil a eu lieu — sinon le launch peut
tourner avec un `.class` obsolète.

## 4. Sécurité

- Bind **127.0.0.1 uniquement** — aucun accès réseau externe.
- Token Bearer aléatoire (32 bytes base64url), régénéré à chaque démarrage.
- Fichier de config `chmod 600`.
- Pas de chemin d'exécution arbitraire : seuls les 3 outils ci-dessus sont exposés, et chacun
  n'accepte que des noms de projet / classe / méthode — résolus via l'API workspace Eclipse.

## 5. Interactive Code Review (ICR)

Le même serveur MCP expose des outils pour la revue de code interactive **native dans Eclipse**.
L'utilisateur sélectionne du code dans un éditeur, fait **clic droit → Ask Claude (ICR)…**
(ou `Ctrl+Alt+C`) et saisit une question ou une demande de modification. Claude (le skill `/icr`)
écoute via `icr_next`, agit (réponse et/ou édition de fichier) puis répond via `icr_reply` ;
la réponse s'affiche inline dans Eclipse sous forme de fil de commentaires (glyphe `💬`).

Contrairement aux runs de test, ici **c'est Eclipse qui produit les événements** et Claude qui les
draine en long-poll : `icr_next` bloque ~25 s puis renvoie `{"status":"idle"}` si rien n'est arrivé,
sinon `{"status":"thread","thread":{…}}`. Chaque outil renvoie son payload en **JSON** dans le
contenu texte (parser avec `jq -r '.result.content[0].text' | jq …`).

| Tool        | Arguments                | Description                                                    |
|-------------|--------------------------|----------------------------------------------------------------|
| `icr_start` | `source?`, `repo?`       | Démarre/reprend une session, active le menu, re-queue le pending|
| `icr_list`  | —                        | Liste tous les fils (JSON) — catch-up après reconnexion        |
| `icr_next`  | —                        | Long-poll du prochain fil à traiter (`idle` / `thread`)        |
| `icr_reply` | `threadId`, `body`       | Poste la réponse de Claude (markdown rendu dans le popup)      |
| `icr_stop`  | —                        | Termine la session (désactive le menu)                         |

Le champ `thread` contient : `id`, `filePath` (relatif au workspace), `absolutePath`, `startLine`,
`endLine`, `selectedText`, `body`, `status`, `replies[]` (conversation complète). Le skill `/icr`
orchestre la boucle ; après une édition il appelle `refresh_projects` pour recompiler avant de
répondre.

**N'appelle pas ces outils ICR directement en curl.** Passe par les scripts fixes fournis dans
`.claude/scripts/icr/` (installés en `~/.claude/scripts/icr/`, cf. README) : `icr_init.sh` (résout
url+token), `icr_call.sh <tool> [json]` (appel générique : `icr_start`, `icr_next`, `icr_stop`…),
`icr_poll.sh` (long-poll en tâche de fond) et `icr_reply.sh <threadId> <fichier>` (réponse lue depuis
un fichier). Le curl/jq inline ou une fonction shell autour de curl se font bloquer par l'heuristique
d'obfuscation de Claude Code et coûtent beaucoup plus de tokens.

## 6. Gestion du workspace (sync projets, working sets, doctor)

Le même serveur MCP expose des outils pour maintenir le workspace Eclipse aligné sur le disque et
diagnostiquer les erreurs de compilation.

**Alignement disque ↔ workspace :**

| Tool                 | Arguments                                              | Description                                                          |
|----------------------|---------------------------------------------------------|-----------------------------------------------------------------------|
| `sync_projects`      | `path?`, `apply?` (déf. false), `removeObsolete?` (déf. true) | Diff disque ↔ workspace : à importer / obsolètes / déplacés. Dry-run par défaut |
| `list_projects`      | `scope?` (`all`/`errors`/`closed`)                     | Lecture seule : chemin relatif au repo, ouvert/fermé, working sets, erreurs/warnings, **`tracked`** (au moins un fichier suivi par git sous le répertoire — `null` si le statut git n'a pas pu être résolu) (bloc JSON complet) |
| `workspace_info`     | —                                                       | Repo root, branche git courante, chemin workspace — utile en multi-instance |
| `sync_working_sets`  | `path?`, `apply?` (déf. false), `reset?` (déf. false), `layout?` (`flat2`/`flat3`/`hybrid`, déf. `hybrid`) | Working sets d'après l'arbo. `layout` : `flat2` (2 niveaux, `open/parent` énorme), `flat3` (3 niveaux), `hybrid` (2 niveaux sauf `open/parent` en 3, queues < 5 projets dans `open/parent/~misc`). Ne touche jamais un set fait main. `reset` supprime tout et recrée |

**Diagnostic / réparation de compilation :**

| Tool                    | Arguments                                              | Description                                                          |
|-------------------------|---------------------------------------------------------|-----------------------------------------------------------------------|
| `get_problems`          | `projects?`, `severity?`, `waitForBuild?` (déf. false) | Marqueurs JDT/PDE. Lecture seule (ne build pas). `waitForBuild` attend que le workspace cesse de rafraîchir **et** de builder → snapshot fiable. Renvoie **tous** les marqueurs dans un bloc JSON (le markdown est plafonné), chacun avec `problemId` (l'`IProblem`), `problemKind` (`undefined-type`, `import-not-found`, `undefined-name`, `abstract-method-not-implemented`, `classpath-incorrect`, `hierarchy-has-problems`, `missing-type-in-signature`, `other`) et `unresolvedName` (le nom manquant, non traduit — premier argument du problème, ou **dernier** pour `missing-type-in-signature`, dont l'argument 0 est le type déclarant) — **plus aucune raison de parser un message** |
| `get_build_status`      | —                                                       | Eclipse est-il occupé ? Compte les jobs des quatre familles (refresh manuel/auto, build manuel/auto) et renvoie `settled` + `activeFamilies`. Point de synchro avant `get_problems` |
| `get_bundle_state`      | `bundles?`                                              | **La vérité terrain PDE.** Par bundle : `source` (`workspace`/`target`), `resolved`, `resolverErrors` (`MISSING_REQUIRE_BUNDLE`, `MISSING_IMPORT_PACKAGE`, `SINGLETON_SELECTION`, …), `requires` (plages de versions et `reexport` déjà résolus), et **`metadataInvisible`** : les métadonnées PDE présentes sur disque mais absentes de la vue Eclipse. Un nommé pour lequel `PluginRegistry` n'a pas de modèle mais qui existe **fermé** dans le workspace sort `source: workspace, closed: true` plutôt que dans `unknown` — fermé n'est pas « ni workspace ni target ». Sans `bundles` : les modèles du workspace, sans les `exports`. Avec `bundles` : la recherche où qu'ils soient, `exports`/`imports` inclus |
| `locate_type`           | `names`                                                 | **Où vit un type ?** Par nom : `jdt-visible` (le modèle JDT le connaît → l'arbre de ressources est bon, seul l'état de build est périmé), `disk-only` (`<Nom>.java` est dans un dossier source d'un projet ouvert mais JDT ne le voit pas → arbre désynchronisé du disque), `closed-provider` (`<Nom>.java` est dans un dossier source d'un projet **fermé** → le type existe, on ne le regarde juste pas ; ouvrir le fournisseur) ou `nowhere` (ni JDT, ni le disque d'un projet ouvert, ni celui d'un fermé — vraie erreur de code, ou sortie de codegen dont l'entrée a disparu). Plus `jdtProjects`, `diskProjects`, `diskPath` (relatif à la racine repo) et `tracked` (suivi par git). Attend l'indexeur JDT avant de répondre |
| `doctor_snapshot`       | `severity?`, `waitForBuild?` (déf. true), `bundles?`   | Agrégat lecture seule pour le doctor : `workspace_info` + `get_problems` + `list_projects` + `get_bundle_state` en **un** bloc JSON, donc un aller-retour au lieu de quatre (MCP sérialise). Ajoute `unresolvedTypes` (chaque nom distinct qu'un marqueur d'erreur ne résout pas, localisé comme `locate_type`, dédupliqué, plafonné à 200 avec `unresolvedTypesTruncated`) et `settled` / `settleRounds` (le workspace était-il vraiment au repos à la lecture des marqueurs). Faits purs, aucun verdict |
| `open_projects`         | `projects`                                              | Ouvre des projets fermés + build incrémental + rapport d'erreurs |
| `close_projects`        | `projects`                                              | Miroir d'`open_projects` : ferme des projets ouverts + build incrémental (pour que les dépendants voient le fournisseur désormais fermé). Aucun contenu disque touché, aucun consentement demandé (comme `open_projects`) — état IDE local, trivialement réversible |
| `remove_projects`       | `projects`                                              | Retire des projets du workspace **et supprime leur résidu disque** (`.project`, `.classpath`, `.settings/`, `bin/`, `target/`) — seule exception à « jamais de contenu disque supprimé ». Tout ou rien : refuse la liste entière si un projet a au moins un fichier suivi par git (ou statut git non résolu) ou du contenu au-delà de ce résidu. Consentement requis (`workspace.consent.projects`) |
| `reload_target_platform`| —                                                       | Recharge/re-résout la target platform active (après changement de la définition ou du repo p2) |
| `clean_projects`        | `projects?`, `build?` (déf. true)                       | Project > Clean : purge l'état de build et reconstruit |
| `import_projects`       | `path?`                                                 | Importe les projets Eclipse présents sur disque et absents du workspace |
| `apply_workspace_batch` | `open?`, `close?`, `import?`, `refresh?`, `clean?`, `deleteGenerated?`, `path?`, `build?` (déf. true) | Batch atomique du doctor : suspend l'auto-build, puis en **une** opération workspace supprime / ouvre / ferme / importe / refresh, puis clean et **un seul** build, et restaure l'auto-build. Consentement requis **seulement** si `open` ou `import` est non vide — `close` suit `close_projects` : aucun consentement, état IDE local trivialement réversible. `deleteGenerated` est **tout ou rien** : chaque chemin doit être sous un `kind="src"` généré de son projet, non suivi par git et sous la racine repo — un seul échec annule toute la liste, et rien n'est supprimé (`deleted` / `refusedDeletes` en sortie) |
| `doctor_status`         | `phase` (`start`/`end`), `detail?`                      | Entrée de progression purement informative pendant un rebuild Maven **externe**. Aucune règle d'ordonnancement (ne verrouille pas le workspace), non annulable utilement, auto-fermeture au bout de 20 min |

`sync_projects`, `sync_working_sets` et `apply_workspace_batch` en mode `apply` sont protégés par un consentement
utilisateur — préférences **`workspace.consent.projects`** / **`workspace.consent.workingsets`**
(`ask` / `always` / `never`, **Window → Preferences → BlueMind**). En mode `ask`, une boîte de
dialogue Eclipse apparaît (timeout 60 s = refus ponctuel, sans modifier la préférence) ; le choix
vaut pour toute la session Eclipse, et peut être mémorisé de façon permanente via la case à cocher.
Le contenu disque n'est en général jamais touché : un projet obsolète est retiré du workspace
(`deleteContent=false`), jamais supprimé. Seule exception : `remove_projects`, sur un projet dont
**aucun fichier de tout le sous-arbre** n'est suivi par git et dont le contenu ne dépasse pas le
résidu Eclipse/Maven attendu — alors `deleteContent=true`, avec consentement.

Le consentement d'`apply_workspace_batch` est **conditionnel** : il n'est demandé que si `open` ou
`import` est non vide. `refresh_projects` et `clean_projects` ne sont gardés par rien quand on les
appelle seuls — et à juste titre : ils invalident de l'état de build, ils ne changent pas
l'appartenance au workspace. Les router à travers le batch ne doit pas les faire passer sous un
consentement dont ils n'ont pas besoin.

`apply_workspace_batch` suspend l'auto-build pour ouvrir/importer un lot puis faire **un seul** build,
et le restaure toujours — dans un `try/finally`, plus un filet au `start()`/`stop()` du plugin qui
relit la préférence **`workspace.autobuild.saved`** (valeur d'auto-build sauvegardée avant suspension)
pour ne jamais laisser le workspace avec l'auto-build coupé après un crash en plein batch.

Scripts (`.claude/scripts/eclipse/`, installés comme les scripts ICR — cf. README). L'entrée
principale pour un triage est **`bm-eclipse-doctor`**, qui classe chaque projet en erreur **par un
fait** (pas par un symptôme ni par la forme d'un message), lu dans `doctor_snapshot` :

| Fait | Remède |
|---|---|
| `metadataInvisible` non vide | `refresh` de la dépendance |
| Absente du workspace, `.project` sur disque | `import` |
| Fermée | `open`, tout le transitif, sans plafond de rayon — son `refresh` est chaîné dans le **même** batch (sinon un projet rouvert lit son `src/` comme manquant jusqu'à la passe suivante) |
| Dossier source déclaré manquant/vide | rebuild Maven |
| `resolved: true` mais le dépendant a toujours ses marqueurs | `clean` du dépendant |
| Ni workspace ni disque | `reload_target_platform` |
| Type non résolu, `jdt-visible` | `clean` du dépendant |
| Type non résolu, `disk-only` | `refresh` + `clean` du fournisseur, `clean` du dépendant |
| Type non résolu, `closed-provider` | `open` du fournisseur (même mécanisme que « Fermée » ci-dessus) |
| Type non résolu, `nowhere`, dans un dossier généré, non suivi par git | suppression du paquet entier (pas seulement les fichiers de même préfixe) + `clean` |
| `Import-Package` non résolu, fournisseur trouvé dans l'index `Export-Package` (disque) | même traitement qu'un `Require-Bundle` non résolu (`open`/`refresh`/etc. sur le fournisseur) |
| Projet sans aucun fichier suivi par git dans tout son sous-arbre | `remove-project` (retrait workspace + résidu disque) — vérifié en premier, avant tout autre fait du projet |
| Marqueur JDT « build path is incomplete » | fait nommé `broken-classpath`, **hand-off explicite, jamais de remède automatique** |
| Aucun des précédents | vraie erreur de code → hand-off |

Une cascade produit **une** décision sur la racine : le graphe des bundles non résolus est remonté
jusqu'à ses feuilles avant de décider. Les quatre faits « type non résolu » viennent d'`unresolvedTypes`,
donc du plugin, et pas d'une heuristique du script ; le fournisseur retenu doit être dans la
fermeture `Require-Bundle` du demandeur, sinon le cas sort nommé
(`source-invisible-out-of-closure`) au lieu d'un refresh sur un homonyme.

Le codegen GWT périmé est invisible à PDE (les deux bundles restent *resolved*, l'erreur est une
erreur JDT dans les sources générées) et se décide sur deux signaux déterministes : marqueur dans un
dossier source **généré**, et sources de l'`.api` plus récentes que les sources générées du `.gwt`.
Le pairage a **trois** états : `paired` + fraîcheur → rebuild automatique de la paire ; `paired`
sans fraîcheur → rapport et `--fix-gwt` sur demande ; `no-counterpart` → la question ne se pose pas,
donc jamais `report-only` (codegen orphelin ou hand-off).

Options : `--apply` (boucle jusqu'au point fixe, 4 passes max ; par passe **un** réacteur Maven et
**un** build Eclipse — le réacteur passe avant le batch et s'y plie s'ils se recoupent, sinon tourne
en parallèle du batch dans un thread ; arrêt immédiat si une passe ne réduit pas le nombre d'erreurs
et que le plan ne change pas ; jamais de modif de code versionné), `--sync` (`sync_projects` puis
`sync_working_sets` avant la boucle — l'ordre compte : le sync change l'ensemble des projets),
`--fix-gwt` (forçage explicite), `--report` (timings par appel,
faits des projets en erreur et des fournisseurs cités, une ligne par type non résolu, candidats
écartés de chaque décision), `--report-all-facts`, `--report-out <chemin>`, `--summary`, `--json`.

Le doctor imprime des lignes de traçabilité sur **stdout** — `[doctor:run]`, `[doctor:decision]`,
`[doctor:decision-blocked]` (une décision écartée par la mémoire des `no-effect` ou par la garde
`clean`), `[doctor:outcome]`, `[doctor:pass]`, `[doctor:deleted]`, `[doctor:delete-refused]`,
`[doctor:batch-error]` (les `errors[]` du batch), `[doctor:removed]` / `[doctor:remove-refused]`
(`remove_projects`), `[doctor:collateral]` (marqueurs en hand-off sur un projet déjà ciblé par un
remède de la même passe, vs pas), `[doctor:handoff]`, `[doctor:handoff-reattached]` (un marqueur
hand-off dont le nom est déjà couvert par une décision racine du même projet), `[doctor:handoff-total]`
(uniquement sur le plan **final** — plus d'annonce provisoire à chaque passe), `[doctor:unresolved]`,
`[doctor:end]` — au format `clé=valeur`. La plus utile est `outcome` : pour chaque remède, les
erreurs avant/après sur les projets visés et un verdict `resolved` / `partial` / `no-effect` /
`progressed` / `regressed`. `progressed` distingue, à compte d'erreurs constant, un classement juste
qu'il fallait rejouer (l'ensemble des noms non résolus a changé) d'un vrai `no-effect` (rien n'a
changé) — seul ce dernier alimente la mémoire qui empêche de rejouer un remède déjà prouvé inefficace
dans ce run. L'attribution est **exclusive** (le delta d'un projet compte dans une seule ligne),
restreinte à la cohorte connue à l'entrée de passe (un projet révélé en cours de passe — bundle
ouvert, projet importé — compte dans `revealed`, jamais en régression d'une cohorte dont il n'a
jamais fait partie), et `[doctor:pass]`/`[doctor:end]` la rendent vérifiable : `attributed` doit
valoir `errors_before - errors_after`, et `blind` isole le delta des remèdes appliqués sans
classement (gratuits parce que le batch build de toute façon). `regressed` se déclenche aussi au
niveau passe (cohorte en hausse), remonté dans `[doctor:end]`. Rien n'est écrit sur disque sauf si
`--report-out <chemin>` est donné (markdown + `.jsonl`), à l'exception de `remove_projects` (résidu
d'une coquille non suivie par git — voir plus haut).

Wrappers unitaires, si besoin de granularité : `bm-eclipse-status` (instances Eclipse joignables +
nb de tools, `--projects` pour lister les projets), `bm-eclipse-call` (invocateur générique),
`bm-eclipse-sync` (`sync_projects` + `sync_working_sets` optionnel — `--working-sets-only` saute
`sync_projects` pour ne faire que les working sets —, plus la gestion open/close :
`--close`/`--open <noms>` par nom exact, `--close-group`/`--open-group {gwt,closure}` sur des
groupements de projets rarement travaillés identifiés par chemin disque, et `--focus <noms>` qui
ouvre l'ensemble donné et ferme tout le reste — la sélection de l'ensemble « nécessaire » pour une
classe/un ticket/un sujet reste un raisonnement de l'appelant (`locate_type`, `get_bundle_state`),
pas une heuristique du script), `bm-eclipse-projects`
(**toujours avec `--name`** : sans filtre la sortie fait plusieurs Mo à cause de la colonne working
sets) et `bm-eclipse-problems` (lecture seule), `bm-classpath-check` (sources générées manquantes,
pur filesystem) et `bm-rebuild-module` (rebuild Maven ciblé + refresh ; déduit tous les profils
Maven gatants en remontant les `pom.xml` du module jusqu'à la racine ; pas de `-am` par défaut, retry
avec `-am` seulement si le réacteur échoue sans ; `--no-refresh` pour un appelant qui plie le refresh
dans son propre batch Eclipse). Skills associées :
`eclipse-sync` (synchronisation guidée) et `eclipse-doctor` (réparation du mécanique, puis hand-off
sur les vraies erreurs de code).
