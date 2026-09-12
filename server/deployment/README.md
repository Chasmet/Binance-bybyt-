# Raccordement au serveur existant

## Complément APK 0.9.10 / MCP 18

L’espace **My Workspace** est déjà confirmé. Conserver le service et ses clés existantes.

1. Appliquer `durable_batch_recovery.sql`, puis `allow_same_pair_batch_orders.sql` (migrations déjà appliquées le 12 septembre ; ne pas les exécuter deux fois).
2. Exécuter `../tests/submission-guards.sql` : tests synthétiques dans une transaction annulée, sans appel Bybit.
3. Déployer `chk-mcp-bridge` avec `index.ts` et `batch.ts`, puis `chk-trade-proposals` avec `index.ts`. Les versions déployées sont v4 et v8. Les authentifications personnalisées restent actives.
4. Déployer le point d’entrée Render existant et les modules MCP 18 depuis la branche `binance-portfolio-app` du dépôt technique. La source testée est `e788e781`.
5. Vérifier `initialize`, le vrai catalogue `tools/list`, le signal SSE de changement et le suivi d’un ID existant. Aucun ordre valide de test en production.
6. Publier Android 0.9.10 exclusivement avec le workflow APK d’origine et comparer le certificat à la release précédente.

`protect_cancel_and_chart_tables.sql` a aussi été appliqué : RLS ferme l’accès direct public aux tables d’annulation et du graphique, utilisées via les fonctions authentifiées existantes. Le rôle serveur conserve ses accès.

Détails et limites : [audit complémentaire](../../docs/AUDIT_MCP_RECOVERY_2026-09-12.md).

## Historique du raccordement 0.9.9

Les fonctions Supabase et la contrainte SQL ont été déployées le 12 septembre 2026. Le module MCP est raccordé au serveur existant (commit 2561e17c). La liste ci-dessous décrit la procédure et les vérifications de publication.

1. Confirmer l’espace Render puis identifier le service `chk-binance-workspace-mcp` et récupérer son code réel. Sauvegarder sa branche/source avant modification. Ne pas toucher à `/ci/android-signing` ni aux anciennes routes.
2. Vérifier les plafonds serveur qui ne figurent pas dans le dépôt Android. Les porter à 30 USDC pour les propositions bot/MCP.
3. Appliquer `raise_order_ceiling.sql` via la migration Supabase, puis relire la contrainte.
4. Déployer les quatre fonctions Supabase fournies avec leurs authentifications existantes et `verify_jwt=false` déjà utilisé en production. Inclure `batch.ts` avec `chk-mcp-bridge`.
5. Raccorder `batchTools` au gestionnaire MCP `tools/list` et `handleBatchTool` au gestionnaire `tools/call`, via le client bridge authentifié existant. Le compte vient de l’identité serveur, jamais d’un argument de l’utilisateur. Ne pas créer de nouveau service Render ou de nouvelles clés.
6. Valider sur un double de Bybit : cinq demandes, arrivée tardive, perte d’accusé, redémarrage, plafond, déconnexion, doublon. Ne pas créer d’ordres financiers de test en production.
7. Après succès CI et vérification du raccordement, publier Android par le workflow existant. Vérifier sa Release et sa signature, sans modifier le système de mise à jour.

Le budget quotidien reste inchangé ; cinq ordres à 30 USDC ne sont pas autorisés avec un plafond journalier de 30 USDC.
