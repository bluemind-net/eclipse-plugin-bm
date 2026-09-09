---
name: eclipse-sync
description: Synchronise le workspace Eclipse BlueMind avec le disque (import de projets, retrait des obsolètes, working sets optionnels) et gère l'ouverture/fermeture des projets (par nom, par groupement gwt/closure, ou par "bundle de travail") via bm-eclipse-sync. Use when the user wants to sync the Eclipse workspace after a branch change, import new projects, organize working sets, close/open specific projects, or focus the workspace on what they're currently working on. Triggers: "sync", "sync le workspace", "sync eclipse", "j'ai changé de branche", "importe les nouveaux projets", "orga", "orga les working sets", "organise les working sets", "sync working sets", "ferme ces projets", "ferme les gwt/closure", "je vais bosser sur X, ferme le reste", "focus sur ce ticket".
---

# Sync du workspace Eclipse

## Vocabulaire

Quatre mots courts couvrent les déclencheurs de ce skill et de `eclipse-doctor`, à connaître dans
les deux sens (les employer, et reconnaître qu'un utilisateur les emploie) :

- **sync** — ce skill, projets seuls (import/retrait, `bm-eclipse-sync --apply` sans
  `--working-sets`) ;
- **orga** — ce skill, working sets seuls (`bm-eclipse-sync --apply --working-sets-only`, qui
  saute `sync_projects`) ;
- **repair** (« répare ») — skill `eclipse-doctor`, réparation seule, sur les problèmes déjà là ;
- **prepare** — mot-ombrelle, skill `eclipse-doctor` (`--sync` y enchaîne déjà sync projets +
  working sets + réparation en un appel) : voir `eclipse-doctor` § Vocabulaire.

`orga`/« organise » ne fait jamais un sync de projets en plus, et inversement — chacun des deux
reste un appel à but unique (cf. « Points d'attention » plus bas).

## Défauts personnels

Avant de suivre les défauts ci-dessous (par défaut : dry-run puis confirmation explicite avant
`--apply`, working sets seulement sur demande, etc.), vérifier si l'utilisateur a mémorisé pour
lui-même des défauts différents (ex: working sets inclus systématiquement, ou une confirmation qui
ne serait plus nécessaire) — typiquement dans `~/.claude/CLAUDE.md` ou sa mémoire personnelle. Si
oui, ils priment sur les défauts documentés ici, **y compris le dry-run et la confirmation
eux-mêmes** : ce qui suit est un défaut de ce skill, pas un absolu.

**Premier contact avec un utilisateur** : si rien dans sa mémoire/son `CLAUDE.md` ne mentionne de
défaut personnel pour ce skill, le dire une fois avant d'agir (puis proposer de le noter côté
utilisateur pour ne pas le redemander) :
- Eclipse a son **propre** dialogue de consentement pour les changements de workspace, indépendant
  de ce skill, réglable sur `ask`/`always`/`never` (**Window → Preferences → BlueMind**,
  `workspace.consent.projects`/`workingsets`) ;
- ce skill a sa **propre** habitude de confirmation dans la conversation (ici : par défaut il
  demande toujours avant `--apply`), personnalisable séparément de celle d'Eclipse ;
- la **portée** des défauts (working sets inclus par défaut ou non, disposition, reset, etc.) est
  elle aussi personnalisable, indépendamment du consentement.

## Déroulé

1. `bm-eclipse-status` — vérifie qu'une instance Eclipse est joignable. Sinon, expliquer et s'arrêter.
2. `bm-eclipse-sync` (dry-run, sans `--apply`) — présenter le diff (à importer / obsolètes / déplacés) à
   l'utilisateur.
3. Confirmation explicite dans la conversation → `bm-eclipse-sync --apply`. Le plugin peut aussi demander
   confirmation côté Eclipse (préférence `ask`) — prévenir que ce dialogue peut apparaître.
4. Working sets : **uniquement** si demandé explicitement (requête initiale, ou en réaction au rapport).
   - « orga » / « organise les working sets » **seul** (pas de sync de projets demandé) →
     `bm-eclipse-sync --working-sets-only` (dry-run, saute `sync_projects`), diff, confirmation,
     puis `bm-eclipse-sync --apply --working-sets-only`.
   - « sync et organise » / working sets demandés en même temps qu'un sync de projets →
     `bm-eclipse-sync --working-sets` (dry-run, projets **et** working sets dans le même appel),
     diff, confirmation, puis `bm-eclipse-sync --apply --working-sets`.
   - Disposition (`--working-sets-layout`, défaut `hybrid`) : `flat2` = 2 niveaux (peu de sets, mais
     `open/parent` énorme), `flat3` = 3 niveaux, `hybrid` = 2 niveaux sauf `open/parent` en 3 avec les
     queues < 5 projets dans `open/parent/~misc`. Proposer le défaut `hybrid` ; ne changer que si
     l'utilisateur le demande.
   - Remise à plat (« refais mes working sets », « repars de zéro », « vire mes vieux sets ») → mode reset :
     `bm-eclipse-sync --reset-working-sets` (dry-run) liste **nommément** tous les sets qui seraient
     supprimés, y compris ceux faits main. Confirmation explicite obligatoire avant
     `bm-eclipse-sync --apply --reset-working-sets`.
5. `bm-eclipse-problems` pour l'état final. S'il reste des erreurs, proposer d'enchaîner sur la skill
   `eclipse-doctor`.

## Ouverture / fermeture de projets

Trois formes, toutes via `bm-eclipse-sync`, toutes en dry-run par défaut (même règle de
confirmation que le sync ci-dessus, avec la même clause « sauf défaut personnel mémorisé »).

**Si la demande implique aussi de vérifier/réparer la compilation** (« ouvre X et vérifie que ça
compile », ou tout simplement parce qu'ouvrir/fermer peut révéler ou masquer des erreurs — voir le
cas mapi/registry ci-dessous), préférer les mêmes flags portés directement par
`bm-eclipse-doctor` (skill `eclipse-doctor`, section « Ouverture/fermeture combinée à une
réparation ») plutôt que deux appels séparés : le doctor les applique **avant** sa propre lecture
de diagnostic, dans le même appel — pas d'aller-retour à faire soi-même en enchaînant
`bm-eclipse-sync` puis `bm-eclipse-doctor` après avoir vu le résultat. `bm-eclipse-sync` seul reste
le bon outil quand aucune réparation n'est voulue (juste changer la forme du workspace).

1. **Par nom** — « ferme net.bluemind.foo » → `bm-eclipse-sync --close net.bluemind.foo` (dry-run),
   confirmation, puis `--apply`. Noms exacts, séparés par des virgules pour plusieurs à la fois.

   **« ouvre X » nu (sans autre précision) veut dire ouvrir X** — `bm-eclipse-sync --open X`, le
   reste du workspace n'est pas touché. Ce qui bascule sur `--focus X` (point 3 plus bas — ouvre X
   **et** ce qu'il requiert, ferme tout le reste) : une demande qui qualifie explicitement
   l'ouverture comme un rétrécissement — « ouvre **juste** X », « **seulement** X », « focus sur
   X », « je vais bosser sur X, ferme le reste ». Défaut **surchargeable par mémoire utilisateur**
   dans l'autre sens (un utilisateur qui veut que « ouvre X » nu ferme systématiquement le reste
   peut le mémoriser).
   Pierre a inversé ce défaut le 01/09 après un incident réel : un focus déclenché implicitement
   par un « ouvre X » nu avait fermé `net.bluemind.jdbc.pgsql.provider`, fourni par point
   d'extension et donc invisible à la fermeture mécanique Require-Bundle (voir point 3) ; un test
   lancé ensuite a échoué avec `No connection factory found for dbtype PGSQL`, se faisant passer
   pour une vraie régression alors que c'était un effet de bord de la fermeture. `--open` simple
   n'a pas ce risque : il n'écarte jamais un projet déjà ouvert.
2. **Par groupement** — « ferme les gwt », « ferme les closure » → `bm-eclipse-sync --close-group
   gwt` / `--close-group closure` (dry-run, liste tout ce qui serait fermé), confirmation, `--apply`.
   Les deux groupes sont identifiés par **chemin disque**, pas par un motif de nom :
   - `gwt` = `open/ui/gwt-libs/*` + `open/ui/gwt-ui-libs/*` (miroirs générés d'API/UI GWT, un par
     bundle) — exclut délibérément `net.bluemind.gwtconsoleapp.base` (le point d'entrée de
     l'appli GWT) et `net.bluemind.webmodule.gwtserver` (le servlet RPC serveur), qui ne sont pas
     des projets « rarement travaillés » au même titre ;
   - `closure` = `open/ui/closures/*` (bundles closure-compiler, y compris des libs tierces logées
     là comme `ydn-base`/`ydn-db`/`relief`).

   **Fermer un groupe par défaut, systématiquement (sans redemander), est une préférence
   personnelle** — même mécanique que les défauts personnels du doctor/sync : si l'utilisateur l'a
   mémorisé pour lui-même (typiquement `~/.claude/CLAUDE.md` ou sa mémoire), l'appliquer sans
   redemander à chaque fois ; sinon, c'est une action sur demande comme les autres, avec dry-run et
   confirmation.
3. **Bundle de travail (« focus »)** — « je vais bosser sur telle classe/tel ticket/tel sujet » :
   identifier l'ensemble de projets **nécessaire** (raisonnement de la conversation — `locate_type`,
   `get_bundle_state`, remontée des dépendances directes/transitives depuis la classe ou le module
   concerné ; ce n'est **pas** une heuristique câblée dans le script), puis appeler
   `bm-eclipse-sync --focus <liste>` (dry-run : ouvre l'ensemble donné **et tout ce qu'il requiert
   transitivement** — Require-Bundle, lu sur disque pour compter les fournisseurs fermés aussi —,
   ferme tout le reste des projets ouverts). C'est un batch potentiellement énorme (plusieurs
   centaines de fermetures) — toujours montrer le compte avant `--apply`, jamais juste la liste
   tronquée sans le total.
   Cette clôture est mécanique (Require-Bundle uniquement) : elle ne remplace pas le raisonnement
   de la conversation pour ce qu'un focus target nécessite au-delà (extension point, dépendance
   runtime non déclarée en Require-Bundle...) — repérer ça reste le rôle de `locate_type`/
   `get_bundle_state` en amont. Ce que la clôture couvre déjà, en revanche, ne se ferme plus jamais
   par erreur : avant le 14/08 (FEATBL-3661), un focus à 2 projets fermait 362 fournisseurs sans
   regarder s'ils étaient requis, et `bm-eclipse-doctor --focus <liste> --apply` (même appel)
   protégeait tout ce close de masse contre la réouverture — 30292 erreurs redevenaient un
   hand-off `protected-conflict` au lieu d'être réparées. Le hand-off protégé reste possible, mais
   seulement pour un vrai conflit entre deux volontés explicites de l'appel (garder X fermé ET
   garder X ouvert), plus jamais comme sous-produit du close de masse (voir `eclipse-doctor` §
   fermeture protégée).

   **Les features PDE requises par un seed ne se ferment jamais par un focus**, même si rien ne
   les référence en Require-Bundle : une feature n'est pas un bundle, donc la clôture mécanique ne
   peut structurellement jamais la voir. Deux sources, protégées séparément dans `plan_focus`
   (`_eclipse_workspace.py`) :
   - **`net.bluemind.tests.feature`** — universelle, ajoutée d'office à tout lancement de test MCP
     (`BmTestLaunchShortcut.buildSelectedFeatures`, `net.bluemind.devtools`), protection statique
     (`TEST_LAUNCH_ALWAYS_OPEN`) ;
   - **les `<requirement><type>eclipse-feature</type></requirement>` du `pom.xml`** de chaque seed
     — propres au projet (ex. `net.bluemind.exchange.mapi.feature` pour les `*.tests` mapi),
     lues dynamiquement (`pom_feature_requirements`, même parsing que
     `BmTestLaunchShortcut.readExtraFeatureRequirements`).

   Incident du 01/09 : un focus sur 5 projets de test mapi avait fermé les deux à la fois
   (`net.bluemind.tests.feature` et `net.bluemind.exchange.mapi.feature`) ; tout test lancé
   ensuite échouait sur `No connection factory found for dbtype PGSQL` (le launch retombe sur une
   copie target platform de la feature au lieu du workspace) — pas un vrai échec, un effet de bord
   invisible tant qu'on ne sait pas que ces features existent. Protection mécanique désormais dans
   le script, pas un rappel à faire manuellement.

`open_projects`/`close_projects` ne demandent **aucun** consentement côté plugin (contrairement à
`sync_projects`/`sync_working_sets`) : état IDE local, rien sur disque, réversible en un appel. La
seule garde est donc la confirmation de ce skill.

## Points d'attention

- Par défaut, jamais d'`--apply` sans dry-run montré à l'utilisateur et confirmation explicite
  dans la conversation (sauf défaut personnel mémorisé — voir « Défauts personnels »).
- Lancer l'`--apply` en tâche de fond et afficher au lancement le `tail -f <chemin absolu de sortie>`,
  repris **verbatim** du retour de l'outil (jamais reconstruit) — le script ne connaît pas ce chemin.
- Les working sets ne sont **jamais** synchronisés par défaut, seulement sur demande.
- `--working-sets-only` saute `sync_projects` (c'est le mot « orga ») ; `--working-sets` (sans
  `-only`) fait les deux dans le même appel.
- Le contenu disque n'est jamais touché — seuls les imports/retraits/ouvertures/fermetures dans le
  workspace Eclipse.
- `--close`/`--open`/`--close-group`/`--open-group`/`--focus` sont mutuellement exclusifs entre eux
  et avec le sync de projets par défaut : donner l'un d'eux fait un appel à but unique, il ne lance
  pas `sync_projects` en plus.
