# BM Test Runner — Plugin Eclipse

Plugin Eclipse pour lancer les tests BlueMind (JUnit Plugin Tests) en un clic droit sur un projet `*.tests`.

## Installation

1. **Help → Install New Software...**
2. **Add...** → Name: `BM Test Runner`, Location: `https://bluemind-net.github.io/eclipse-plugin-bm/`
3. Cocher **BlueMind Developer Tools** → Finish
4. Redémarrer Eclipse

## Utilisation

Clic droit sur un projet `*.tests` → **Run As → BM Plugin Tests**.

Le plugin crée une launch configuration JUnit Plugin Test préconfigurée avec `net.bluemind.tests.feature` et la lance. Si une configuration existe déjà pour ce projet, elle est réutilisée.

## Build local

Prérequis : Java 21+, Maven 3.9+

```bash
mvn clean verify
```

Le p2 repository est généré dans `net.bluemind.devtools.testrunner.site/target/repository/`.
