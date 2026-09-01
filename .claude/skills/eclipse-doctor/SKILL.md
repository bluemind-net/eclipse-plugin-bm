---
name: eclipse-doctor
description: Répare les erreurs de compilation Eclipse mécaniquement réparables (bundles fermés, projets non importés, métadonnées PDE invisibles, sources générées manquantes, état de build périmé, type introuvable côté JDT ou côté disque, codegen orphelin) et rend la main sur les vraies erreurs de code. Use when Eclipse fails to compile in ways a simple refresh doesn't fix, or when the user wants to fully prepare the workspace (sync + repair, optionally focused on a target) before starting work. Triggers: "répare eclipse", "repair", "ça compile pas dans eclipse", "regarde les problèmes eclipse", "eclipse est cassé", "diag eclipse", "prepare eclipse", "prepare mapi", "prepare <cible>".
---

# Diagnostic et réparation Eclipse

## Vocabulaire

Quatre mots courts couvrent les déclencheurs de ce skill et de `eclipse-sync`, à connaître dans les
deux sens (les employer, et reconnaître qu'un utilisateur les emploie) :

- **sync** / **orga** — skill `eclipse-sync` (projets seuls / working sets seuls) — voir
  `eclipse-sync` § Vocabulaire ;
- **repair** (« répare ») — ce skill, réparation **seule**, sur les problèmes déjà là :
  `bm-eclipse-doctor --apply`, sans `--sync` ;
- **prepare** — mot-ombrelle, ce skill : `--sync` enchaîne déjà `sync_projects` **et**
  `sync_working_sets` avant la boucle de réparation, en un seul appel
  (`bm-eclipse-doctor --sync --apply`). « prepare eclipse » = la totale, sans cible d'ouverture ;
  « prepare `<cible>` » (ex. « prepare mapi ») = la totale, puis ouvre `<cible>` dans le **même**
  appel (`--open`/`--focus`, voir « Ouverture/fermeture combinée à une réparation » ci-dessous) —
  la liste de projets derrière `<cible>` se résout comme un focus target (`bm-eclipse-projects
  --name <cible>`, `locate_type`/`get_bundle_state` si le nom seul est ambigu), pas par un groupe
  câblé en dur comme `gwt`/`closure`.

`repair` ne prend jamais `--sync` de lui-même ; `prepare` le prend toujours — c'est la seule
différence entre les deux mots.

`bm-eclipse-doctor` classe chaque projet en erreur **par un fait**, pas par un symptôme ni par la
forme d'un message. Les faits viennent du plugin (`doctor_snapshot` = `workspace_info` +
`get_problems` + `list_projects` + `get_bundle_state` + `unresolvedTypes` en un seul appel), pas
d'heuristiques sur les `MANIFEST.MF` ni de parsing de messages : un marqueur porte son `problemKind`
et son `unresolvedName` en clair.

## Défauts personnels

Avant de suivre les défauts ci-dessous (par défaut : pas de dry-run ni de confirmation avant
`--apply`, pas de rapport par défaut, etc.), vérifier si l'utilisateur a mémorisé pour lui-même des
défauts différents (ex: `--report-out` systématique vers un chemin donné, ou au contraire demander
confirmation avant d'agir) — typiquement dans `~/.claude/CLAUDE.md` ou sa mémoire personnelle. Si oui, ils priment sur
les défauts documentés ici, **y compris la confirmation elle-même** : ce qui suit est un défaut de
ce skill, pas un absolu — un utilisateur qui a mémorisé « demande-moi toujours avant d'agir » doit
être suivi. La seule chose qui ne change jamais : ce skill n'écrit jamais dans le code versionné,
quoi qu'il arrive.

**Premier contact avec un utilisateur** : si rien dans sa mémoire/son `CLAUDE.md` ne mentionne de
défaut personnel pour ce skill, le dire une fois avant d'agir (puis proposer de le noter côté
utilisateur pour ne pas le redemander) :
- Eclipse a son **propre** dialogue de consentement pour les changements de workspace, indépendant
  de ce skill, réglable sur `ask`/`always`/`never` (**Window → Preferences → BlueMind**,
  `workspace.consent.projects`/`workingsets`) ;
- ce skill a sa **propre** habitude de confirmation dans la conversation (ici : par défaut il
  applique directement sans demander), personnalisable séparément de celle d'Eclipse ;
- la **portée** des défauts (taille de batch, génération de rapport, nombre de passes, etc.) est
  elle aussi personnalisable, indépendamment du consentement.

## Déroulé

| Demande | Commande |
|---|---|
| « repair », « répare eclipse », « ça compile pas », « eclipse est cassé » | `bm-eclipse-doctor --apply` **directement** |
| « prepare eclipse », « sync et répare », « j'ai changé de branche » | `bm-eclipse-doctor --sync --apply` |
| « qu'est-ce qui cloche ? » sans réparer | `bm-eclipse-doctor` (diagnostic seul) |
| « sync et ouvre X », « ferme Y (et vérifie que ça compile) » | `bm-eclipse-doctor [--sync] --open X --close Y --apply` (voir ci-dessous) |
| « prepare mapi », « prepare `<cible>` » | `bm-eclipse-doctor --sync --open <cible> --apply` — **toujours** avec `--sync` (§ Vocabulaire) |
| « focus sur X » avec réparation dans le même appel | `bm-eclipse-doctor --focus X --apply` |

## Ouverture/fermeture combinée à une réparation

`bm-eclipse-doctor` accepte les mêmes primitives que `bm-eclipse-sync`
(`--open`/`--close`/`--open-group`/`--close-group`/`--focus`, voir la skill `eclipse-sync` pour
leur sémantique exacte) — mais **enchaînées avec la réparation dans le même appel**, plutôt qu'un
second appel séparé après avoir vu le résultat. `--focus` est exclusif avec les quatre autres
(il dit déjà « cet ensemble ouvert, tout le reste fermé » en un coup) ; `--open`/`--close`/
`--open-group`/`--close-group` se combinent librement entre eux.

**Pourquoi dans ce script et pas seulement dans `bm-eclipse-sync`** : l'ouverture/fermeture
s'applique **après** `--sync` (un nom peut viser un projet que le sync vient d'importer) mais
**avant** la lecture du snapshot qui alimente la boucle de réparation de `--apply` — jamais après.
Réparer avant de changer la forme du workspace gaspille des décisions sur des projets qui vont être
fermés, et reste aveugle à ce que les projets qu'on ouvre vont révéler. Cas réel du 14/08
(release/FEATBL-3713) : l'ouverture de 55 projets `mapi` en un appel séparé de `bm-eclipse-sync`,
sans repair enchaîné, a fait apparaître une cascade de 5291 erreurs qu'un second `bm-eclipse-doctor
--apply`, lancé après coup, a dû rattraper — deux allers-retours pour ce qu'un seul appel
`bm-eclipse-doctor --open <55 noms> --apply` couvre directement.

**Fermeture protégée pour tout le run** : un projet nommé par `--close`/`--close-group`/le
sous-ensemble à fermer de `--focus` est protégé pour le reste du run. Si la classification
ordinaire (fait `closed`/`closed-provider`) voudrait le rouvrir pour satisfaire un autre projet, le
doctor ferme **ce projet-là** à la place (fait `protected-closed`) plutôt que d'annuler
silencieusement la fermeture demandée — et propage la même protection à ce qu'il vient de fermer,
pour qu'une chaîne de dépendants orphelins converge au lieu de faire des allers-retours entre
fermeture et réouverture. Cas réel du 14/08 : fermer `mapi` a laissé des projets sans rapport avec
mapi (`oab.codec`, `ese`, `ews`, `http.server`, `outlook.addins`, `provider.core`,
`provider.pclcachestore.*`) orphelins — ils dépendaient transitivement de `mapi.codec`, ouverts en
accessoire par un repair précédent, jamais demandés pour eux-mêmes. Sans cette protection, le
doctor avait rouvert 13 projets `mapi` pour les satisfaire, défaisant exactement la fermeture
demandée ; la résolution manuelle a été de fermer les 7 orphelins plutôt que de rouvrir mapi.

**`--focus` ne protège plus son close de masse en entier** : avant le 14/08, le sous-ensemble à
fermer de `--focus` était « tout projet ouvert qui n'est pas le focus target », sans regarder si le
focus target en avait besoin — et tout ce close de masse partait en `protected_closed`. Cas réel
(FEATBL-3661) : focus sur 2 projets, 362 fermés dont une bonne partie des dépendances du focus
target, protégées contre la réouverture ; 30292 erreurs rendues en `protected-conflict` au lieu
d'être réparées. `--focus` calcule maintenant sa propre clôture Require-Bundle (disque, un
fournisseur fermé compte) et l'ouvre avec le focus target — seul ce qui reste après cette clôture
devient `to_close`/protégé. Le hand-off `protected-conflict` reste possible, mais seulement pour un
vrai conflit entre deux demandes explicites de l'appel, plus jamais comme sous-produit du close de
masse.

**Les features PDE requises par un seed ne font jamais partie du close set d'un `--focus`** —
invisibles à la clôture Require-Bundle par construction (pas des bundles). `net.bluemind.tests.feature`
(universelle, tout lancement de test MCP) et les `eclipse-feature` déclarées dans le `pom.xml` de
chaque seed (propres au projet, ex. `net.bluemind.exchange.mapi.feature` pour mapi) sont toutes les
deux protégées mécaniquement dans `plan_focus` — voir la skill `eclipse-sync` § « Bundle de travail »
pour l'incident qui l'a motivée (01/09 : les deux fermées par le même focus, tout test lancé
ensuite échouait sur une fausse panne `No connection factory found for dbtype PGSQL`).

**Conflit entre deux demandes explicites du même run** : si le projet qui aurait besoin d'être
fermé (dans le cas ci-dessus) est lui-même demandé **ouvert** ce même run (`--open`/`--focus`), ce
n'est plus mécanique — c'est deux demandes explicites qui se contredisent. Aucun des deux côtés
n'est deviné : ça sort en hand-off nommé (`fact=protected-conflict`), avec le nom du projet fermé en
cause, plutôt que de rouvrir ou fermer au hasard.

**Par défaut, pas de dry-run avant `--apply`, pas de confirmation à demander** (voir « Défauts
personnels » ci-dessus — un utilisateur peut mémoriser l'inverse) : `--apply` imprime son plan
avant d'agir et n'écrit jamais dans le code versionné. Ne demander que pour `--fix-gwt` et pour
toute modification de code. Un `open` transitif s'exécute toujours en entier, sans plafond de
rayon : le seul signal avant d'agir est le dialogue de consentement d'Eclipse lui-même
(`workspace.consent.projects`, plus haut) — un plafond côté script aurait juste dupliqué ce
signal, alors que la fermeture transitive s'ouvre de toute façon en bloc ou pas du tout (jamais
un sous-ensemble : un bundle qui manque encore un `Require-Bundle` reste non résolu quel que soit
le nombre d'autres déjà ouverts).

`--apply` boucle jusqu'au point fixe (4 passes max). Par passe : tous les remèdes workspace dans
**un** `apply_workspace_batch` (delete + open + import + refresh + clean, auto-build suspendu, un
seul build), les rebuilds Maven en **un** réacteur, puis une re-lecture. `--apply` importe de
lui-même ce que le graphe d'erreurs exige et qui est sur disque, mais ne retire jamais un projet
obsolète et ne touche jamais aux working sets — c'est `--sync` qui fait ça.

## Table de classification — un fait, un remède

| Fait sur la dépendance non résolue (ou sur le projet) | Remède |
|---|---|
| `metadataInvisible` non vide — métadonnée PDE sur disque, absente de la vue Eclipse | `refresh` de la **dépendance** |
| Absente du workspace, `.project` sur disque | `import` |
| Fermée | `open`, tout le transitif en un batch, sans plafond de rayon — son `refresh` est chaîné dans le **même** batch (sinon un projet rouvert lit son `src/` comme manquant jusqu'à la prochaine passe) |
| Dossier source déclaré manquant ou vide | rebuild Maven du module |
| `resolved: true` alors que le dépendant a toujours ses marqueurs | `clean` du **dépendant** |
| Ni dans le workspace ni sur disque | `reload_target_platform` |
| `Import-Package` non résolu, fournisseur trouvé dans l'index `Export-Package` (manifestes disque) | même traitement qu'un `Require-Bundle` non résolu sur ce fournisseur |
| Projet sans **aucun** fichier suivi par git dans tout son sous-arbre | `remove-project` — vérifié en premier, avant tout autre fait de ce projet |
| Marqueur JDT « build path is incomplete » | fait nommé `broken-classpath`, **hand-off explicite, jamais de remède automatique** |
| Marqueur sur le **projet lui-même** (pas un fichier), sans `problemKind` ni `unresolvedName`, bundle `resolved: true` | fait `build-order-stale`, `clean` du projet |
| Aucun des précédents | vraie erreur de code → hand-off, on ne touche pas |

`clean` n'est jamais tenté sur un projet dont le modèle PDE n'a ni `source=` ni `resolved=` — pas de
modèle, pas de `clean` à faire porter. Un couple (fait, remède, cible) déjà sorti `no-effect` dans ce
run n'est **jamais** redécidé une deuxième fois (mémoire valable pour la durée du run, jamais rejouée
d'un run à l'autre).

Une cascade produit **une** décision sur la racine, pas une par dépendant : le graphe des bundles
non résolus est remonté jusqu'à ses feuilles avant de décider.

### Marqueur de build-order périmé — pas un message à parser

Eclipse a son propre marqueur générique de build-order, sans rapport avec le compilateur Java :
« The project cannot be built until its prerequisite X is built. » (identifié le 14/08, cascade
mapi/registry). Il ne porte **aucun** attribut structuré (pas d'`IProblem` id, pas de nom manquant)
— le seul angle qui reste sans lire le message est où il est posé et ce que le snapshot sait déjà :
un marqueur directement sur le **projet** (pas un fichier de son arbre), sans `problemKind` ni
`unresolvedName`, alors que le bundle du projet **résout** déjà. C'est exactement le même fait que
`resolved: true` avec le dépendant encore marqué (ligne `dependency-resolved` ci-dessus) — celui-ci
juste vu depuis un marqueur de projet plutôt qu'un graphe de résolveur PDE. Se produit typiquement
juste après un batch qui vient d'ouvrir/fermer/rebuilder quelque chose à côté : le build-order
d'Eclipse n'a pas encore rattrapé le nouvel état, `clean` (puis le prochain build de la passe) le
rattrape.

### Type introuvable — quatre faits, venus du plugin

Quand un projet n'a **aucune** dépendance non résolue côté PDE mais des erreurs « le type X ne peut
pas être résolu », c'est le plugin qui tranche, via `unresolvedTypes` du snapshot (même calcul que
le tool `locate_type`). Pas une heuristique du script :

| `kind` | ce que ça veut dire | remède |
|---|---|---|
| `jdt-visible` | le modèle JDT connaît le type : l'arbre de ressources est à jour, seul l'**état de build** est périmé | `clean` du **dépendant** — rien à rafraîchir |
| `disk-only` | `<X>.java` est dans un dossier source d'un projet **ouvert**, mais JDT ne le voit pas : arbre de ressources désynchronisé du disque | `refresh` + `clean` du **fournisseur**, `clean` du dépendant |
| `closed-provider` | `<X>.java` est dans un dossier source d'un projet **fermé** : le type existe, on ne le regarde juste pas | `open` du fournisseur — même mécanisme que « Fermée » plus haut |
| `nowhere` | aucun des trois précédents : ni JDT, ni le disque d'un projet ouvert, ni celui d'un fermé — vraie erreur de code, ou sortie de codegen dont l'entrée a disparu | codegen orphelin (ci-dessous) sinon hand-off |

Les marqueurs moissonnés ne sont pas seulement les « X cannot be resolved to a type » : la famille
`missing-type-in-signature` (`problemKind` porté par le plugin — `The method m() from the type T
refers to the missing type X`, idem constructeur, lambda, inférence) compte aussi. La référence est
dans la **signature** d'un membre, pas dans la source compilée, et le nom manquant est le **dernier**
argument du problème, pas le premier — l'argument 0 y est le type déclarant, qui lui se résout très
bien. Sans ça, ces marqueurs sortaient en `problemKind=other` sans nom et partaient en hand-off
alors que le `clean` du dépendant les efface.

Garde-fous de ce classement, à connaître pour lire un run :

- **fermeture `Require-Bundle`** : seul un fournisseur que le projet en erreur require (directement
  ou transitivement) est retenu. Un homonyme hors fermeture n'est pas rafraîchi ; le cas sort
  nommé, `fact=source-invisible-out-of-closure`, pas fondu dans le hand-off ;
- **plusieurs candidats** : décision sur tous, avec `ambiguous=<n>` sur la ligne — un refresh n'est
  pas destructeur et l'outcome tranchera ;
- **volume** : au-delà de 150 projets dans un lot, le doctor **annonce** le volume et la liste
  avant d'agir. Ce n'est pas une demande de confirmation.

### Codegen orphelin

`kind=nowhere` + marqueur dans un dossier source **généré** → le fichier est une sortie de codegen
dont l'entrée n'existe plus. `--apply` le **supprime**, avec le projet nettoyé derrière — et la
sélection couvre **tout le paquet généré** concerné, pas seulement les fichiers dont le nom
commence comme le type manquant : un paquet généré tient en général tout entier au même type
disparu (bindings, wrappers async, promises), et s'arrêter au préfixe de nom voulait dire une passe
par couche restante au lieu de la fermeture entière d'un coup. La garde est **dans le plugin**,
vérifiée par celui qui supprime : sous un `kind="src"` généré, **et** non suivi par git, **et** sous
la racine du repo. La liste est **tout ou rien** — un seul fichier suivi par git et rien n'est
supprimé, avec le motif dans `refusedDeletes`. Chaque suppression sort en `[doctor:deleted]`, chaque
refus en `[doctor:delete-refused]`.

### Coquille non suivie par git — la seule autre écriture disque

Un projet dont **aucun fichier de tout le sous-arbre** n'est suivi par git (`tracked=false` du
snapshot, pas un fichier isolé) est un projet que personne ne reconnaîtrait depuis un commit — fait
`stale-project:untracked-shell`, remède `remove_projects` : retrait du workspace **et** suppression
du résidu disque (`.project`, `.classpath`, `.settings/`, `bin/`, `target/`). Deux gardes, **tout ou
rien sur la liste entière** : un statut git non résolu vaut refus comme un fichier suivi, et tout
contenu au-delà de ce résidu attendu (un `.java`, un `MANIFEST.MF` — peut-être un bundle créé et pas
encore `git add`-é) fait refuser aussi. Consentement requis, comme `open`/`import`. Chaque
suppression sort en `[doctor:removed]`, chaque refus en `[doctor:remove-refused]`. Vérifié **avant**
tout autre fait du projet : une coquille n'entre jamais dans la classification normale (pas de
`clean`, pas de hand-off séparé pour ses propres marqueurs).

Le **tout ou rien** a une conséquence à connaître : une seule garde qui tombe annule aussi les
retraits légitimes du même lot. Le doctor réessaie donc **une fois**, dans la même passe, avec les
refusés retirés, et retient les refus pour le reste du run (une garde porte sur du contenu disque
qu'aucune passe ne change — `[doctor:remove-skipped]` compte ceux qu'il ne redemande plus). Et le
verdict d'un `remove-project` se lit sur la réponse du tool (`removed[]`), **jamais sur le delta
d'erreurs** : un projet retiré n'a plus de marqueurs, un projet refusé n'en avait pas non plus, donc
le delta vaut 0 dans les deux cas. Un run de release/5.6 a annoncé 30 retraits `resolved` à trois
passes de suite alors que zéro avait eu lieu.

## Codegen GWT — trois états de pairage

PDE est **aveugle** à ce cas : quand l'`.api` gagne une méthode, les deux bundles restent
*resolved* et l'erreur est une erreur JDT dans les sources générées. Deux signaux déterministes,
indépendants de la langue de l'IDE :

- **emplacement** — l'erreur tombe dans un dossier source **généré** (`kind="src"` dont le nom
  contient `generated`) ; personne n'y écrit à la main ;
- **fraîcheur** — les sources de l'`.api` (ou son `generated/model.json`) sont plus récentes que
  les sources générées du `.gwt`.

Le second signal a besoin d'une contrepartie pour exister, d'où **trois** états et non deux :

| pairage | fraîcheur | conclusion |
|---|---|---|
| `paired` | présente | **rebuild automatique** de la paire (`.api` puis `.api.gwt`, un réacteur) |
| `paired` | absente | vrai doute → **rapport**, et `--fix-gwt` sur demande explicite |
| `no-counterpart` | **inapplicable** | jamais `report-only` : soit codegen orphelin, soit hand-off (`fact=gwt-pairing-inapplicable`) |

Le périmètre de `--fix-gwt` s'est donc **réduit** au seul cas `paired` sans fraîcheur : **demander
avant de lancer**, un signal n'est pas une preuve. Un projet sans contrepartie n'est pas un doute à
forcer, c'est une autre question.

Si un projet a des erreurs sous `generated/` **et** sous `src/`, seules les premières sont du
codegen ; les autres sont rendues à l'utilisateur.

## Ce qu'il faut restituer — par ordre de priorité

1. **Les chiffres d'abord** : `errors_before → errors_after`, dont **N réparées mécaniquement** et
   **M rendues sur K projets** (`[doctor:end]` : `decided_errors`, `handoff_errors`,
   `handoff_projects` ; recoupés par `[doctor:handoff-total]`). Un run se juge d'abord sur cette
   proportion : 9 classées sur 427 et 418 rendues, c'est un défaut de classement, pas un run réussi
   — **sauf si `guarded_errors` en explique la majorité** : un fait et un remède existent pour ces
   erreurs-là, c'est une garde (`no-effect`, `clean` sans modèle PDE) qui les retient, pas un
   classement manquant. `revealed` (erreurs de projets absents de la cohorte à
   l'entrée de passe — un bundle qui vient d'être ouvert, par exemple), `induced` (erreurs apparues
   sur des projets **en aval** de ce que la passe a touché : nettoyer ou rebuilder un fournisseur
   périme l'état de build de ses dépendants, c'est le prix du remède), `blind` (delta des remèdes
   appliqués sans classement, gratuits parce que le batch buildait de toute façon) et
   `guarded_errors`/`guarded_projects` (`[doctor:handoff-guarded]` — expliqué mais bloqué) sont de
   l'information annoncée à côté, jamais confondus avec `decided_errors`.
2. **Ce qui a été réparé et par quel remède**, ce qui reste et **pourquoi**.
3. **Lire les lignes `[doctor:outcome]` avant de conclure.** Un `verdict=no-effect` désigne un
   couple (fait, remède) qui ne marche pas : c'est le **classement** qu'il faut corriger, pas le
   remède à réessayer — et il ne sera plus jamais reproposé dans ce run. `progressed` est différent :
   à compte d'erreurs constant, l'ensemble des noms non résolus a changé, donc le classement était
   juste et rejouer était la bonne chose — ne pas le confondre avec `no-effect`. Et : **si
   `attributed` ≠ `errors_before - errors_after` dans `[doctor:pass]`, la comptabilité est fausse — le
   dire, au lieu de recopier les chiffres.** Une passe dont la cohorte connue régresse (`verdict` du
   `[doctor:pass]`) le dit aussi — et ce verdict ne tombe **que** sur la croissance qui reste
   au-dessus d'`induced` : ni un projet révélé en cours de passe, ni le collatéral des remèdes de la
   passe elle-même ne sont une régression. Une croissance intégralement `induced` se lit sur la
   passe suivante, pas comme une alarme.
4. **Les cas non classés, verbatim** : les lignes `[doctor:handoff]` avec leurs comptes et leurs
   `problem_kind`, plus `[doctor:handoff-total]`. Pas de reformulation en « des erreurs de code
   restantes », et **pas d'exemples choisis à la main à la place de la distribution**. Un hand-off
   présent dans `[doctor:handoff-guarded]` n'est **pas** un défaut de classement : le dire comme tel
   (fait + remède connus, garde nommée dans le message) au lieu de le deviner ou de le taire — ces
   gardes (`no-effect`, `clean` sans modèle PDE) n'ont pas de flag d'échappatoire, elles attendent
   une passe ou un run suivant.
5. **Toute vérification faite en dehors du doctor doit apparaître comme telle, avec sa commande et
   son résultat.** C'est le meilleur signal d'un fait manquant dans l'outil : un run qui a eu besoin
   d'un `find`, d'un `git check-ignore` ou d'un second diagnostic pour conclure désigne autant de
   champs que le snapshot devrait porter.
6. **Le payload brut quand un classement est suspecté faux** : un marqueur en JSON (avec son
   `problemId` / `problemKind` / `unresolvedName`), les `[doctor:fact]` des projets concernés, la
   `[doctor:decision]` mise en cause. Sans ça, un lecteur ne peut ni situer ni reproduire.
7. **Proposer un rapport, jamais le produire d'office** : « un cas a été mal détecté, je te fais un
   rapport à remonter ? ». Sur accord seulement. Et **proposer `--report-out <chemin>` dès qu'un
   rapport est probable** (`~/.claude/tmp/` ou le `tmp/` non versionné du repo) : ça évite de
   reconstituer plusieurs centaines de Ko de stdout après coup. **Aucun chemin absolu personnel**
   dans le contenu du rapport : relatif à la racine repo.

## Lignes de traçabilité

```
[doctor:run]           script=… plugin=… branch=… errors=427 projects_in_error=28 settled=true
[doctor:decision]      pass=1 project=<dépendant> dep=<fournisseur> fact=source-invisible:X@<fournisseur> remedy=refresh target=<fournisseur> errors=9
[doctor:outcome]       pass=1 remedy=refresh target=<a>+<b> facts=source-invisible errors_before=9 errors_after=0 verdict=resolved projects=2 decisions=3
[doctor:pass]          pass=1 errors_before=427 errors_after=418 attributed=9 unattributed=0
[doctor:deleted]       pass=1 path=/<projet>/generated/…/ITickConfigurationAsync.java
[doctor:handoff]       pass=1 project=<projet> errors=3 problem_kind=undefined-type shape="<X> cannot be resolved to a type"
[doctor:handoff-total] pass=1 errors=418 projects=28 kinds=undefined-type:390,import-not-found:28
[doctor:unresolved]    pass=1 name=AIActionDescriptor kind=disk-only provider=net.bluemind.ai.api tracked=true requested_by=2
[doctor:handoff-guarded] pass=1 errors=13 projects=4 reasons="known no-effect this run"
[doctor:end]           passes=2 errors_before=427 errors_after=0 handoff_projects=0 handoff_errors=0 decided_errors=427 revealed=12 blind=4 guarded_errors=13 guarded_projects=4
```

Autres records, moins fréquents mais à connaître pour lire un run : `[doctor:batch-error]` (les
`errors[]` bruts du batch), `[doctor:decision-blocked]` (une décision écartée par la mémoire des
`no-effect` ou par la garde `clean`), `[doctor:handoff-guarded]` (par plan final : le sous-ensemble
du hand-off qu'un fait explique déjà, retenu par une garde — `no-effect`, `clean` sans modèle PDE),
`[doctor:collateral]` (par passe : marqueurs en hand-off sur un
projet déjà ciblé par un remède de la même passe, vs pas), `[doctor:handoff-reattached]` (un marqueur
hand-off dont le nom est déjà couvert par une décision racine du même projet — n'est **plus** compté
en hand-off séparé), `[doctor:removed]` / `[doctor:remove-refused]` (coquilles non suivies par git),
`[doctor:remove-skipped]` (coquilles déjà refusées ce run, plus redemandées).
Le hand-off complet (`[doctor:handoff]` / `[doctor:handoff-total]`) ne sort **qu'une fois**, sur le
plan final — jamais à chaque passe intermédiaire.

Verdicts : `resolved` / `partial` / `no-effect` / `progressed` / `regressed` / `not-attributed` (les
projets de cette ligne ont été crédités à une autre — l'attribution est exclusive). Le préfixe
`[doctor:*]` est sur **stdout** ; le heartbeat reste sur **stderr** au format `[doctor] <phase> … Ns`.

## Réflexe de diagnostic — un seul appel

Pour « ce test échoue d'une façon qui ne ressemble pas au bug attendu » (erreur d'infra avant même
d'atteindre le corps du test, timeout inhabituel, message sans rapport avec le code touché) : `bm-eclipse-call
check_pom_sync '{}'` **avant** de conclure à une vraie régression. Lecture seule, quasi instantané
(pas de build, pas d'appel Maven — juste le `global/pom.xml` parsé et les VM args du JRE par défaut
lus en mémoire) : même comparaison que le menu **BlueMind → Check POM Sync...**.  `inSync: false`
explique souvent un échec qui n'a rien à voir avec le code (un `docker.devenv.tag` périmé, un flag
JVM ajouté depuis dans `tycho.testArgLine` et jamais resynchronisé) plutôt qu'une régression — cas
réel du 01/09 sur `StartupTests` (mapi), où ça a permis d'écarter cette piste en un appel.

Pour « où vit ce type ? » : `bm-eclipse-call locate_type '{"names":["AIActionDescriptor"]}'`. Ça
donne `jdt-visible` / `disk-only` / `closed-provider` / `nowhere`, les projets côté JDT et côté
disque, le chemin relatif à la racine repo et si git suit le fichier. **Pas** un `find` dans le repo — un `find` qui
sert à conclure est un fait qui manque à l'outil.

Pour « la dépendance est verte mais pas résolue » : `bm-eclipse-call get_bundle_state
'{"bundles":["<dep>"]}'`. Ça donne `resolved`, les `resolverErrors` PDE, `metadataInvisible` et les
`exports`. **Pas** une exploration `MANIFEST.MF` / git / pom.

Pour lister des projets : `bm-eclipse-projects --name <fragment>`. **Toujours** avec `--name` —
sans filtre la sortie fait plusieurs Mo (la colonne working sets), et elle est inutilisable comme
résultat d'outil.

## Options de sortie

- `--report` : timings par appel MCP, faits des projets **en erreur et des fournisseurs cités**,
  une ligne `[doctor:unresolved]` par nom manquant, candidats écartés de chaque décision. N'écrit
  **rien**.
- `--report-all-facts` : avec `--report`, les faits de **tous** les projets (1300+ lignes, ~200 Ko).
- `--report-out <chemin>` : **la seule chose qui écrive un fichier**, et seulement là. Deux
  fichiers : le markdown de synthèse au chemin donné, et les enregistrements `[doctor:*]` en
  `.jsonl` à côté. La sortie Maven et les rapports de batch y vont aussi — le terminal garde le
  résumé et le statut.

## Points d'attention

- **L'attente est dans le plugin — ne jamais l'écrire en shell.** `doctor_snapshot` avec
  `waitForBuild: true` joint les familles **refresh** (`FAMILY_MANUAL_REFRESH` /
  `FAMILY_AUTO_REFRESH`) **puis** build, **en boucle** jusqu'à ce qu'aucune n'ait de job en attente,
  puis attend l'indexeur JDT. Une boucle de polling shell ne fait que redoubler ça en moins fiable
  et redemande une permission à chaque fois.
- **`settled` dit si l'attente a tenu.** Le snapshot renvoie `settled` et `settleRounds` ;
  `[doctor:run]` les reporte. `settled=false` = le workspace bougeait encore (deadline 120 s ou 12
  tours atteints) : le compte d'erreurs peut ne pas être stable, le script le dit et il faut
  relancer le diagnostic plutôt que conclure. `get_build_status` compte lui aussi les jobs refresh
  et renvoie `settled` + les familles actives.
- **Consentement : une mention en passant, pas un préambule.** `apply_workspace_batch` ne sollicite
  `workspace.consent.projects` que si `open` ou `import` est non vide — un batch purement
  `refresh`/`clean`/`delete` n'ouvre rien. Selon le réglage (`allow` / `ask`), il peut ne rien
  s'ouvrir du tout. Le choix se fait **dans Eclipse**, pas dans le terminal : au plus une phrase au
  lancement, jamais un avertissement répété ni une demande de confirmation côté Claude.
- Le Maven externe affiche un Job Eclipse purement informatif (`doctor_status`) qui **ne verrouille
  pas** le workspace et s'auto-ferme au bout de 20 min. Un « Cancel » dessus ne tuerait pas le
  Maven — ne pas le proposer comme moyen d'arrêter le rebuild.
- **Toujours lancer les passes longues (`--apply`, `--sync --apply`, `--fix-gwt`) en tâche de
  fond**, et afficher au lancement le `tail -f <chemin absolu de sortie>` **verbatim** du retour de
  l'outil — ne jamais le reconstruire. Le script ne connaît pas ce chemin.
- Lancé en tâche de fond, **stdout est bufferisé** jusqu'au retour de l'appel MCP en cours : le
  terminal peut sembler figé alors que ça travaille. Le heartbeat `[doctor] … Ns` sur stderr dit
  que ça avance ; sinon `get_build_status` comme point de synchro.
- Le serveur MCP **sérialise** les appels — jamais deux `tools/call` en parallèle sur une instance.
- Multi-instance : `bm-eclipse-doctor <bundle-hint>` cible l'Eclipse qui a ce projet ; les rebuilds
  se font toujours dans le repo du workspace visé, jamais le cwd de la session.
- Si le script dit que le plugin n'expose pas `doctor_snapshot` / `get_bundle_state` /
  `doctor_status` / `locate_type` / `remove_projects`, c'est un plugin **plus ancien que la
  branche** : rebuild + réinstall + redémarrage d'Eclipse. Le contrat se vérifie par la **présence
  des tools**, jamais par un numéro de version. Ne pas contourner en rejouant les anciens tools à la
  main.
- Dernier recours si rien ne converge : escalade `make light` (skill `make`).
