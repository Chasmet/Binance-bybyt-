# Audit complémentaire — APK 0.9.10 et MCP 18

Base auditée : Android `0c0e915d`, MCP Render `2561e17c`, APK 0.9.9. Ce document complète l’audit initial du 11 septembre.

## Constats vérifiés

| Observation | Conséquence | Correction |
|---|---|---|
| Le vrai `POST /mcp tools/list` exposait déjà 58 outils, dont les deux outils de lot et les schémas à 30 USDC ; le connecteur rapportait encore 10 USDC et ne montrait pas les lots | Le catalogue conservé par le client peut différer du serveur | Catalogue versionné, outils de lot en tête, notification SSE `tools/list_changed`, descriptions actualisées et accès de compatibilité explicite |
| Anciens schémas dans les couches v11/v8 encore fondés sur 10 USDC | Régression possible selon le chemin utilisé | Configuration commune `MAX_ORDER_USDC`, issue de l’environnement, plafonnée à 30, utilisée par le catalogue et les contrôles |
| Une proposition du 12 septembre est restée `processing` de 12:29:40 à 12:34:26 avant l’accusé d’un ordre Bybit existant | Le délai du connecteur ne décrit pas le résultat financier | Lecture Bybit par OrderLinkId et réparation conditionnelle du résultat, reprise avec les mêmes identifiants |
| Index unique `account_fingerprint, symbol, side` sur toutes les propositions actives | Deux tranches RENDER SELL ne pouvaient pas être insérées dans un lot | Index conservé pour les propositions historiques sans batch ; clés déterministes et unicité compte/lot/position pour les lots |
| Lecture réseau pouvant échouer après un POST accepté | Risque de faux échec et de nouvel envoi | Recherche exacte dans les ordres actifs et l’historique ; une erreur de lecture ne prouve jamais l’absence |
| Curseur tactile persistant, arrondis du zoom à chaque mouvement, changement de doigt non suivi | Déplacement bloqué, petits pincements ignorés, sauts | Modes de gestes, zoom ancré au début du pincement, transfert du doigt actif, déplacement fractionnaire et défilement vertical du parent |

## Flux livré

Le serveur valide et découpe toute la demande avant une insertion atomique. Le `batchId` UUID et la position produisent des IDs persistants, également calculables avant la réponse HTTP. Les mêmes IDs retrouvent le même lot après perte de réponse. Le bot réclame chaque proposition ; les prédécesseurs du lot doivent avoir une confirmation Bybit ou un échec explicite avant le prochain envoi.

Auto-Trade confirme selon les autorisations déjà réglées dans l’APK. Avant le POST, une réservation persistante limite chaque proposition à deux tentatives espacées d’au moins 60 secondes. Avant une reprise, les recherches Bybit doivent réussir sans retrouver l’ordre. Un OrderLinkId retrouvé est réconcilié, jamais recréé. Les anciennes propositions sans suivi de tentative restent en récupération en lecture seule.

Les accusés Android sont conservés localement et renvoyés séparément. Un accusé perdu ou retardé ne déclenche pas un nouvel ordre. Le MCP utilise les clés serveur existantes uniquement pour les lectures de vérification ; les placements restent dans l’APK.

`OPEN`, `PARTIALLY_FILLED`, `FILLED`, `REJECTED` et `FAILED` sont renvoyés individuellement. Les situations d’attente, d’expiration, d’annulation et de blocage restent explicites. `OPEN` signifie placé, pas rempli. Une lecture Bybit indisponible produit `UNKNOWN`, jamais un succès reconstitué à partir d’un ancien `OPEN`.

Les requêtes ont une attente bornée. `reportReady=false` impose de poursuivre `wait_trade_batch` avec les mêmes IDs ; il ne faut pas demander à l’utilisateur de ressaisir les ordres. Le serveur ne peut pas prolonger indéfiniment un appel ni forcer le comportement d’un client ChatGPT interrompu.

Pour un catalogue client ancien, `create_note` accepte explicitement `kind="TRADE_BATCH"` avec un contenu JSON `{batchId,orders}`, ou `kind="TRADE_BATCH_STATUS"` avec `{proposalIds}`. Ces deux kinds utilisent le traitement des lots et ne créent pas de note. La description indique clairement que TRADE_BATCH peut déclencher Auto-Trade. Les autres notes gardent leur fonctionnement.

## Montants et limites

- Ordre physique et remplacement : maximum 30 USDC. Le schéma et la validation suivent la même configuration.
- Lot : maximum 20 ordres physiques. Une intention LIMIT dépassant 30 est découpée selon le pas et le minimum Bybit, sans dépasser son montant autorisé.
- Cas vérifié : 23,84 RENDER à 1,47, pas de 0,01 → 20,40 RENDER (29,988 USDC) et 3,44 RENDER (5,0568 USDC).
- Le budget quotidien, le nombre maximal d’ordres et les autorisations de l’APK restent applicables. Le découpage ne les augmente pas.
- Pour un ordre isolé, `request_id` fournit une idempotence explicite. Un ancien client sans cet argument bénéficie seulement d’un regroupement des demandes identiques dans une fenêtre de dix minutes ; le suivi par IDs est obligatoire après une réponse incertaine.

## Vérification et publication

24 tests Node réussis : catalogue, plafond, split, validation atomique, cinq confirmations, panne de lecture, perte d’accusé, bridge 503 et absence de double création. 12 tests Android réussis : file, reprises, serveur Bybit simulé avec POST accepté puis HTTP 500, curseur, pincement, changement de doigt et ancrage historique. Compilation Android réussie sur GitHub Actions.

Le test SQL `server/tests/submission-guards.sql` a été exécuté avec un compte synthétique dans une transaction annulée : plusieurs ordres de même paire, isolation compte/appareil, accès RPC réservé au service, ordre des confirmations, délai de reprise, maximum de tentatives et ancien garde-fou sans batch. Aucun ordre financier ni annulation réelle n’a été créé pour les essais.

Migrations appliquées : `durable_batch_recovery_and_submission_reservations`, `allow_same_pair_batch_orders`. Fonctions Supabase : `chk-mcp-bridge` v4 et `chk-trade-proposals` v8, authentifications existantes conservées. Source Render : `e788e781f5cf9f27c4e51a1f63d4cd3b4ae4ea56`, service existant de My Workspace.

Le contrôle Supabase a également confirmé l’absence de RLS sur `chk_cancel_proposals` et `chk_chart_state`, avec des droits anonymes de lecture et d’écriture. Après vérification des clients Android et des fonctions actives, qui utilisent les accès serveur authentifiés, la migration `protect_cancel_and_chart_tables` a activé RLS sur les deux tables. Le rôle anonyme ne voit plus aucune ligne ; le service conserve son accès. Aucun accès public n’a été ajouté. [Référence du contrôle Supabase](https://supabase.com/docs/guides/database/database-linter?lint=0013_rls_disabled_in_public).

Le code de mise à jour, le workflow APK d’origine et le proxy de signature ont été comparés aux sources initiales et sont identiques. La publication utilise toujours la signature stable existante. Aucun test tactile sur le téléphone réel, de suspension Android/Doze ou de cycle financier complet en production n’a été effectué.

Références : [recherche des ordres Bybit](https://bybit-exchange.github.io/docs/v5/order/order-list), [catalogue d’outils MCP](https://modelcontextprotocol.io/specification/2025-06-18/server/tools), [transport MCP](https://modelcontextprotocol.io/specification/2025-06-18/basic/transports).
