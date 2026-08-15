# Onboarding — BlueMind Developer Tools

Ce plugin fait deux choses : il évite les manips manuelles répétitives dans Eclipse (imports de
projets, working sets, diagnostic de compilation), et il donne à Claude Code des yeux et des mains
sur ton Eclipse en cours d'exécution (lancer des tests, réparer le workspace, revue de code inline).
Détail complet de chaque fonctionnalité dans le [README.md](README.md).

## Mise en place (une fois)

1. Installe le plugin depuis le site p2 (README § Installation), redémarre Eclipse.
2. **Window → Preferences → BlueMind** → active le serveur MCP (« Enable MCP server for Claude
   Code »). C'est ce qui permet à Claude Code de parler à cet Eclipse.
3. Installe les skills `eclipse-sync`/`eclipse-doctor` (README § Gestion du workspace, "Mise en
   place") et, si tu fais de la revue de code, `/icr` (README § Code Review interactif).
4. Toute modification du workspace (import, retrait, working sets) passe par un consentement
   Eclipse indépendant des skills (`ask`/`always`/`never`, mêmes préférences BlueMind).

## Le vocabulaire du workspace

Quatre mots courts couvrent la gestion du workspace depuis Claude Code — les formulations
naturelles marchent aussi, mais ce sont les raccourcis à retenir :

| Mot | Fait quoi |
|---|---|
| **sync** | importe les nouveaux projets, retire les obsolètes — rien d'autre |
| **orga** | range les working sets d'après l'arborescence — rien d'autre |
| **repair** | répare ce qui ne compile pas, sans toucher au sync |
| **prepare** | la totale (sync + orga + repair) en un appel ; « prepare `<cible>` » (ex. « prepare mapi ») ouvre en plus `<cible>` dans le même appel |

`sync` et `orga` sont deux actions **séparées** — l'une ne déclenche jamais l'autre. `prepare` est
le mot à retenir pour tout lancer d'un coup, typiquement après un `git switch`/`git pull`.

## Ta première fois sur une branche

- **« prepare eclipse »** (ou juste « sync » si tu ne veux que l'import de projets) — après un
  changement de branche, c'est en général le bon réflexe : le workspace est aligné et ce qui ne
  compile pas est réparé mécaniquement en un seul appel.
- **« je vais bosser sur tel ticket »** — ferme tout le reste, ouvre uniquement ce dont tu as
  besoin (et ses dépendances). Le workspace reste léger.
- Ce qui reste après un `repair`/`prepare` est une vraie erreur de code, pas de la mécanique — c'est
  là que ta lecture commence.

## Au quotidien

- Clic droit sur une classe/méthode de test → **Run As → BM Plugin Tests** (ou "lance les tests"
  depuis Claude Code).
- Une revue de code ? `/icr` depuis Claude Code, puis clic droit → *Ask Claude (ICR)…* dans
  l'éditeur.
- Le POM a changé de version cible (après un `git pull`) ? Une pop-up Eclipse te le propose
  automatiquement — **BlueMind → Check POM Sync...** pour la relancer à la main.

## Pour aller plus loin

- [README.md](README.md) — toutes les fonctionnalités, en détail.
- [docs/CLAUDE_CODE_MCP.md](net.bluemind.devtools/docs/CLAUDE_CODE_MCP.md) — protocole MCP, tools
  disponibles, sécurité.
- Skills `eclipse-sync`/`eclipse-doctor` (`.claude/skills/`) — chaque `SKILL.md` a sa propre section
  « Vocabulaire » et le détail des cas particuliers (groupes gwt/closure, fermeture protégée, etc.).
