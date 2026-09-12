# Audit CHK Crypto — bot, MCP et lots d’ordres

Référence auditée : `main`, commit `99092b33a540d481bebe15e1dac32be2382435c7`, APK 0.9.8 / code 33. Inventaire : 84 fichiers, dont les sources Android, ressources, configuration Gradle et workflow de compilation. Les fonctions Supabase actives ont été lues séparément : leur source était absente du dépôt Android.

## Architecture constatée

- Android Kotlin, interface native construite principalement en code, minSdk 26, compile/target 35. Le projet existant n’est pas converti vers un autre langage ou SDK.
- Portefeuilles Binance/Bybit, graphiques et indicateurs, alertes, notes, sauvegarde et journal Bot sont des modules distincts.
- `BotEngine` évalue les règles locales et crée des propositions. `AutoTradeExecutor` les réclame auprès de Supabase, puis `BybitTradeClient` les place avec les clés Android. Le claim conditionnel et l’identifiant Bybit déterministe existent déjà.
- `MarketWatchService` hébergeait analyse, règles, annulations et placements dans une seule boucle. Le traitement Auto-Trade était espacé d’au moins une minute, augmenté du temps de l’analyse et des appels réseau.
- `TradePushService` existe dans les sources mais n’est ni déclaré dans le Manifest ni démarré. Il n’a donc pas été utilisé pour la correction.
- La file est persistante dans `chk_trade_proposals`. Le bot doit continuer indépendamment de la fin du tour ChatGPT. Une réponse du MCP n’arrête pas cette boucle Android.
- Mise à jour : workflow existant, signature stable via OIDC/Render, publication et téléchargement intégrés. Aucun changement du code de mise à jour ou du workflow `build-apk.yml`.

## Constats et corrections préparées

| Constat vérifié | Conséquence | Correction |
|---|---|---|
| Limites 10 USDC dans Android, plusieurs fonctions et une contrainte SQL | Modifier seulement l’interface ne suffit pas | Plafond par ordre 30 USDC dans les couches identifiées ; migration SQL nécessaire |
| Valeurs par défaut : 3 ordres et 30 USDC cumulés/jour | Un lot de 5 peut être bloqué malgré une file correcte | Ancienne valeur standard 3 portée à 5 ; budget quotidien inchangé ; autres valeurs personnalisées conservées |
| `canExecute` refusé suivi de `continue` silencieux | Le MCP ne connaît pas la raison du blocage | Rapport authentifié `report_blocked`, motif et date dans le résultat de la proposition |
| Une seule lecture des pending par passage | Les arrivées suivantes attendent le cycle de surveillance | Drainage, relecture après passage, ordre chronologique, arrêt sur désactivation |
| Verrou Auto-Trade limité à une instance | Plusieurs instances peuvent contourner le compteur quotidien | Verrou commun au processus et exécution automatique sérialisée |
| `markResult` enveloppé dans `runCatching` et ignoré | Ordre placé mais serveur laissé en processing | Accusés persistants, renvoi du seul accusé et traitement serveur idempotent |
| État de repli `SENT` retourné comme résultat exécuté | Faux succès possible avant preuve Bybit | Relecture obligatoire avec identifiant et statut Bybit ; état incertain sans renvoi |
| Lecture vide après erreur POST interprétée comme refus | L’absence momentanée de résultat ne prouve pas le refus | État incertain conservé ; réconciliation en lecture seule |
| SELL Bot calculé au cours courant, ordre LIMIT au prix cible | Valeur réelle différente du budget annoncé | Quantité calculée au prix LIMIT et budget vérifié avant envoi |
| Pas de contrat de lot vérifié dans le code serveur disponible | ChatGPT peut ne préparer/vérifier que le premier ordre | Actions `create_trade_batch` / `wait_trade_batch` : validation préalable, insertion atomique, identifiants idempotents, suivi de chaque ID |

Le lot est une demande groupée, pas cinq requêtes Bybit simultanées. Les placements sont séquentiels afin de contrôler les soldes et les compteurs entre chaque ordre. Une erreur sur un élément n’abandonne pas les suivants autorisés.

## Sens des confirmations

`pending` = proposition en attente ; `processing` = réclamée, résultat à vérifier ; `placed` = statut enregistré `executed`, identifiant Bybit et état `New`, `PartiallyFilled` ou `Filled`. Un LIMIT placé n’est pas nécessairement rempli. `SENT`, une expiration, une annulation ou un résultat manquant ne valident jamais le lot.

Une réponse de vérification limitée à 20 secondes peut rester incomplète. Le MCP doit continuer à appeler `wait_trade_batch` avec les mêmes IDs ; il ne doit jamais recréer le lot pour le relancer. `allConfirmed` est la seule condition permettant d’annoncer tous les placements confirmés.

## Limites et points restant à traiter

- Aucun incident passé reproduit sur téléphone : lors de la lecture de la base, toutes les 122 propositions conservées étaient en statut exécuté, aucune pending/processing. Ce constat ne prouve pas la cause précise de chaque demande interrompue.
- Les fonctions Supabase sont accessibles, mais le code du serveur Render exposant les outils MCP n’est pas dans ce dépôt. Les deux actions préparées doivent être raccordées à ses outils et son plafond 10 éventuel doit être vérifié. Ne pas annoncer le parcours MCP entièrement corrigé avant ce raccordement.
- L’outil Render exige la confirmation explicite de l’espace de travail avant accès aux services. L’espace retourné est `My Workspace`. Pas de modification Render effectuée sans cette confirmation.
- Les budgets quotidiens personnalisés restent actifs. Cinq ordres de 30 USDC nécessitent 150 USDC de plafond quotidien ; cette augmentation n’est pas effectuée implicitement.
- Android peut suspendre le service selon la batterie et les restrictions système. Aucun test appareil/Doze réalisé ici.
- La réservation locale est conservatrice en cas d’envoi incertain. Un processing sans ordre retrouvé reste à vérifier ; il n’est jamais renvoyé automatiquement.
- Annulation/remplacement : le serveur existant écrit l’annulation puis le remplacement dans deux opérations. Une panne entre les deux reste un risque distinct ; ce changement ne refond pas ce mécanisme.
- Le README historique décrivait seulement la confirmation manuelle, alors que les autorisations Auto-Trade existent depuis 0.9.7.
- Aucun Gradle Wrapper n’est présent dans le dépôt : la CI installe Gradle 8.10.2. Ne pas affirmer avoir exécuté `./gradlew` localement.

## Validation

Tests serveur hors marché réel : plafond 30, rejet 30.01, valeurs invalides, cinq confirmations, réponses partielles, statuts non confirmés, blocage/expiration, isolation de compte, répétition idempotente et invalidité du cinquième élément sans insertion des quatre premiers.

Tests Kotlin de la file : cinq éléments dont arrivées pendant traitement, poursuite après erreur, absence de double traitement dans un passage, désactivation avant l’ordre suivant. Compilation et tests à contrôler sur GitHub Actions avant publication.

Aucun ordre réel ni annulation réelle n’a été créé pour les essais.

## Raccordement du 12 septembre 2026

Espace Render confirmé par l’utilisateur : My Workspace. Service existant identifié : `chk-binance-workspace-mcp`, dépôt technique `Chasmet/APK-Installer-Web-CHK`, branche `binance-portfolio-app`, commit de raccordement `2561e17c0f8fd263a089bc2b360b377ed63e375f`. Le point d’entrée existant `server-v16.mjs` charge un module isolé `trading-extension.mjs` ; aucune nouvelle couche de proxy ni nouveau service n’a été créé.

Le catalogue MCP expose les deux outils de lot et le plafond de 30 USDC. L’ancien outil `create_trade_proposal` attend aussi une vérification ; si la lecture échoue, il conserve l’ID de la proposition dans son résultat. L’identité du compte est imposée par le serveur et ne peut pas être remplacée par les arguments du connecteur. Le proxy de signature est identique octet pour octet.

Contrainte SQL 30 USDC appliquée et relue. Fonctions actives déployées : `chk-trade-proposals` v7, `chk-bot-proposals` v2, `chk-cancel-proposals` v4, `chk-mcp-bridge` v3. Sauvegardes des sources et branches antérieures conservées. Le budget quotidien et les autorisations de l’APK restent distincts.

Validation : 10 tests du lot et 5 tests du raccordement passent sans marché réel. Les 4 tests Kotlin et la compilation Android de la PR réussissent. La publication signée reste réalisée exclusivement par le workflow APK d’origine. Les observations « raccordement requis » ci-dessus décrivent l’étape initiale de l’audit et sont remplacées par ce compte rendu.
