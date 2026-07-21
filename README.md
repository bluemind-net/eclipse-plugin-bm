# BlueMind Developer Tools — Plugin Eclipse

Plugin Eclipse pour le développement BlueMind. Il fournit cinq fonctionnalités :

- **Lancement rapide des tests** : clic droit sur un projet `*.tests` pour lancer les JUnit Plugin Tests avec une configuration préconfigurée.
- **Synchronisation POM → workspace** : détecte automatiquement les changements dans `open/global/pom.xml` (via inotify) et propose de mettre à jour la configuration Eclipse en conséquence.
- **Serveur MCP pour Claude Code** : expose un endpoint HTTP JSON-RPC local (loopback + token) permettant à Claude Code de déclencher des tests dans l'Eclipse qui tourne et de récupérer stdout/stderr + outcome. Voir [docs/CLAUDE_CODE_MCP.md](net.bluemind.devtools/docs/CLAUDE_CODE_MCP.md).
- **Vue « Branch Changed Files »** : liste les fichiers modifiés sur la branche courante par rapport au merge-base avec l'upstream (committed / staged / unstaged), avec ouverture du fichier ou de l'éditeur de comparaison. Menu *Window → Show View → BlueMind → Branch Changed Files*.
- **Interactive Code Review (ICR)** : revue de code interactive native. On sélectionne du code dans un éditeur, *clic droit → Ask Claude (ICR)…* (ou `Ctrl+Alt+C`) pour poser une question ou demander une modification ; Claude (skill `/icr`) écoute via le serveur MCP, agit, et répond inline sous forme de fil de commentaires (glyphe `💬`). Voir [docs/CLAUDE_CODE_MCP.md](net.bluemind.devtools/docs/CLAUDE_CODE_MCP.md) §5.

## Installation

1. **Help → Install New Software...**
2. **Add...** → Name: `BlueMind Developer Tools`, Location: `https://bluemind-net.github.io/eclipse-plugin-bm/`
3. Cocher **BlueMind Developer Tools** → Finish
4. Redémarrer Eclipse

## Lancement des tests

Le plugin offre plusieurs niveaux de granularité pour lancer les JUnit Plugin Tests :

- **Projet** : clic droit sur un projet `*.tests` → **Run As → BM Plugin Tests**
- **Classe** : clic droit sur une classe de test (Package Explorer, Outline) → **Run As → BM Plugin Tests**
- **Méthode** : clic droit sur une méthode `@Test` (Outline, ou curseur dans l'éditeur) → **Run As → BM Plugin Tests**

Le plugin crée une launch configuration PDE JUnit préconfigurée avec `net.bluemind.tests.feature` et gère automatiquement les fragments (Fragment-Host). Les classes abstraites sont ignorées.

### Code Mining (optionnel)

Le plugin peut afficher des indicateurs `▶ Run` / `▶ Debug` au-dessus des méthodes `@Test` et des classes de test directement dans l'éditeur. Cette fonctionnalité est **désactivée par défaut**.

Pour l'activer : **Window → Preferences → BlueMind** → cocher "Show Run/Debug code mining above test methods".

## Synchronisation POM

Le plugin surveille le fichier `bluemind-all/open/global/pom.xml` et réagit quand les propriétés suivantes changent (par exemple après un `git switch` ou `git pull`) :

| Propriété POM | Action |
|---|---|
| `tycho.testArgLine` | Met à jour les arguments VM du JRE par défaut d'Eclipse |
| `docker.devenv.tag` | Résolu dans `tycho.testArgLine` (`-Dbm.docker.tag=...`) |
| `target-platform-version` | Met à jour l'URL du repository P2 dans la target platform active et la recharge |

Quand un décalage est détecté, une boîte de dialogue propose de synchroniser. Si tout est en sync, rien ne s'affiche.

Le check peut aussi être lancé manuellement via le menu **BlueMind → Check POM Sync...**

### Options JVM locales (`~/.config/bluemind/jvm.options`)

Si le fichier `~/.config/bluemind/jvm.options` existe, son contenu est ajouté aux arguments VM du JRE, après ceux du POM. Cela permet d'ajouter des options spécifiques à sa machine sans modifier le POM partagé.

Format : une option par ligne, `#` pour les commentaires.

```
# Exemple ~/.config/bluemind/jvm.options
-Xmx8g
-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005
```

## Code Review interactif (ICR) — skill `/icr`

La revue de code interactive est pilotée depuis Claude Code par la commande `/icr`. Cette commande
est fournie dans ce dépôt : [`.claude/commands/icr.md`](.claude/commands/icr.md).

### Mise en place

1. **Activer le serveur MCP** : dans Eclipse, **Window → Preferences → BlueMind** → cocher
   *« Enable MCP server for Claude Code »*. Le plugin écrit alors `~/.config/bluemind/mcp/eclipse-*.json`
   (url + token loopback). `jq` doit être installé.
2. **Installer la commande `/icr` et ses scripts** — `/icr` appelle des scripts fixes attendus dans
   `~/.claude/scripts/icr/` (ça évite le curl/jq inline, bloqué par l'heuristique d'obfuscation de
   Claude Code). Copier — ou lier, pour suivre les mises à jour du dépôt — la commande et les scripts :
   ```bash
   mkdir -p ~/.claude/commands ~/.claude/scripts/icr
   cp .claude/commands/icr.md  ~/.claude/commands/       # ou: ln -sf "$PWD/.claude/commands/icr.md" ~/.claude/commands/
   cp .claude/scripts/icr/*.sh ~/.claude/scripts/icr/    # ou: ln -sf "$PWD"/.claude/scripts/icr/*.sh ~/.claude/scripts/icr/
   chmod +x ~/.claude/scripts/icr/*.sh
   ```
   Puis autoriser ces scripts dans `~/.claude/settings.json`, formes tilde **et** absolue :
   `Bash(~/.claude/scripts/icr/*)` et `Bash(/home/<vous>/.claude/scripts/icr/*)`.

### Utilisation

1. Lancer `/icr` dans Claude Code depuis le dépôt à relire (`/icr [--source <branche>] [--repo <chemin>]`).
2. Dans Eclipse, sélectionner du code → *clic droit → Ask Claude (ICR)…* (ou `Ctrl+Alt+C`) pour poser
   une question ou demander une modification. Claude répond inline (glyphe `💬`).
3. La vue **Window → Show View → BlueMind → Code Review Conversations** liste tous les fils ouverts ;
   un double-clic ouvre le fichier sur la ligne concernée.

> La commande `/icr` est documentée pas à pas dans le fichier lui-même ; le détail du protocole MCP
> est dans [docs/CLAUDE_CODE_MCP.md](net.bluemind.devtools/docs/CLAUDE_CODE_MCP.md) §5.

## Build local

Prérequis : Java 21+, Maven 3.9.9+

```bash
mvn clean verify
```

Le p2 repository est généré dans `net.bluemind.devtools.site/target/repository/`.
