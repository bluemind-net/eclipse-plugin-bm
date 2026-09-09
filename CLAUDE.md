# Règles de ce repo

## Un rebuild du plugin n'est pas un obstacle

Ne pas écarter une piste parce qu'elle demande de modifier `net.bluemind.devtools` : le plugin se
builde et se réinstalle en local. Si le bon endroit pour corriger est le plugin, c'est là qu'on
corrige — pas dans un contournement côté script pour éviter d'y toucher.

Les pièges du redéploiement (qualifier Tycho, cache p2) sont connus : ce ne sont pas des raisons de
s'abstenir.
