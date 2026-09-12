# Raccordement au serveur existant

Les fonctions Supabase et la contrainte SQL ont été déployées le 12 septembre 2026. Le module MCP est raccordé au serveur existant (commit 2561e17c). La liste ci-dessous décrit la procédure et les vérifications de publication.

1. Confirmer l’espace Render puis identifier le service `chk-binance-workspace-mcp` et récupérer son code réel. Sauvegarder sa branche/source avant modification. Ne pas toucher à `/ci/android-signing` ni aux anciennes routes.
2. Vérifier les plafonds serveur qui ne figurent pas dans le dépôt Android. Les porter à 30 USDC pour les propositions bot/MCP.
3. Appliquer `raise_order_ceiling.sql` via la migration Supabase, puis relire la contrainte.
4. Déployer les quatre fonctions Supabase fournies avec leurs authentifications existantes et `verify_jwt=false` déjà utilisé en production. Inclure `batch.ts` avec `chk-mcp-bridge`.
5. Raccorder `batchTools` au gestionnaire MCP `tools/list` et `handleBatchTool` au gestionnaire `tools/call`, via le client bridge authentifié existant. Le compte vient de l’identité serveur, jamais d’un argument de l’utilisateur. Ne pas créer de nouveau service Render ou de nouvelles clés.
6. Valider sur un double de Bybit : cinq demandes, arrivée tardive, perte d’accusé, redémarrage, plafond, déconnexion, doublon. Ne pas créer d’ordres financiers de test en production.
7. Après succès CI et vérification du raccordement, publier Android par le workflow existant. Vérifier sa Release et sa signature, sans modifier le système de mise à jour.

Le budget quotidien reste inchangé ; cinq ordres à 30 USDC ne sont pas autorisés avec un plafond journalier de 30 USDC.
