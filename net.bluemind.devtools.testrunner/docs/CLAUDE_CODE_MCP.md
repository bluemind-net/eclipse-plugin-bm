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

3. Appeler l'un des 3 outils via JSON-RPC. Les noms d'outil :
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

4. Le résultat est du markdown déjà formaté avec :
   - Status PASSED / FAILED
   - Compteurs (total/passed/failed/errored/ignored) et durée
   - Stacktraces des échecs
   - stdout / stderr tronqués (derniers 32 Ko)

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

| Tool              | Arguments                                        | Description                        |
|-------------------|--------------------------------------------------|------------------------------------|
| `run_bundle_tests`| `project` (+ `mode?`)                            | Tous les tests d'un bundle `*.tests` |
| `run_class_tests` | `project`, `className` (FQN) (+ `mode?`)         | Tous les `@Test` d'une classe       |
| `run_test_method` | `project`, `className`, `methodName` (+ `mode?`) | Une seule méthode `@Test`           |

Un `tools/call` bloque la réponse HTTP jusqu'à la fin du run (timeout interne 30 min).

## 4. Sécurité

- Bind **127.0.0.1 uniquement** — aucun accès réseau externe.
- Token Bearer aléatoire (32 bytes base64url), régénéré à chaque démarrage.
- Fichier de config `chmod 600`.
- Pas de chemin d'exécution arbitraire : seuls les 3 outils ci-dessus sont exposés, et chacun
  n'accepte que des noms de projet / classe / méthode — résolus via l'API workspace Eclipse.
