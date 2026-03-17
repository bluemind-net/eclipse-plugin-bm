# BM Test Runner — Plugin Eclipse

Plugin Eclipse pour le développement BlueMind. Il fournit deux fonctionnalités :

- **Lancement rapide des tests** : clic droit sur un projet `*.tests` pour lancer les JUnit Plugin Tests avec une configuration préconfigurée.
- **Synchronisation POM → workspace** : détecte automatiquement les changements dans `open/global/pom.xml` (via inotify) et propose de mettre à jour la configuration Eclipse en conséquence.

## Installation

1. **Help → Install New Software...**
2. **Add...** → Name: `BM Test Runner`, Location: `https://bluemind-net.github.io/eclipse-plugin-bm/`
3. Cocher **BlueMind Developer Tools** → Finish
4. Redémarrer Eclipse

## Lancement des tests

Clic droit sur un projet `*.tests` → **Run As → BM Plugin Tests**.

Le plugin crée une launch configuration JUnit Plugin Test préconfigurée avec `net.bluemind.tests.feature` et la lance. Si une configuration existe déjà pour ce projet, elle est réutilisée.

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

## Build local

Prérequis : Java 21+, Maven 3.9+

```bash
mvn clean verify
```

Le p2 repository est généré dans `net.bluemind.devtools.testrunner.site/target/repository/`.
