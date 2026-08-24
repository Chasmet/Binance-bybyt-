# CHK Crypto — dépôt canonique

> **SOURCE DE VÉRITÉ UNIQUE DU PROJET : `Chasmet/Binance-bybyt-` / branche `main`**

Application Android personnelle CHK Crypto regroupant **Binance + Bybit EU**, avec analyse ChatGPT et propositions d’ordres Bybit confirmées dans l’application.

## Identité officielle

- Dépôt : `Chasmet/Binance-bybyt-`
- Branche : `main`
- Package Android : `com.chk.binancebybit`
- Bybit EU : `https://api.bybit.eu`
- Marché autorisé : **Spot CRYPTO/USDC uniquement**
- Plafond actuel : **10 USDC par ordre**

Tout autre dépôt, service Render, fonction Supabase ou ancien MCP est uniquement une **infrastructure technique / compatibilité**. Il ne doit jamais être traité comme un second projet CHK Crypto ou comme une autre source de vérité.

## Principe de sécurité impératif

```text
ChatGPT analyse
→ ChatGPT crée une PROPOSITION
→ la proposition apparaît dans CHK Crypto
→ l’utilisateur voit tous les paramètres
→ l’utilisateur appuie CONFIRMER ou ANNULER
→ uniquement après CONFIRMER, l’APK/gateway envoie l’ordre réel à Bybit EU Spot
```

**AUCUN ordre réel, aucune annulation réelle et aucun remplacement réel ne doivent être exécutés automatiquement par ChatGPT/MCP.**

Interdits : Futures, marge, levier, options, dérivés, retraits et transferts automatiques.

## Binance

Binance est essentiellement en lecture :

- portefeuille ;
- actifs ;
- historique Spot ;
- PRU estimé ;
- achats/ventes ;
- P/L ;
- alertes ;
- notes.

Le backend doit limiter les appels REST et utiliser cache/flux adaptés afin d’éviter les limites et bannissements temporaires Binance.

## Bybit EU

Bybit est connecté via l’API V5 EU. Les fonctions prévues sont :

- portefeuille et actifs ;
- ordres ouverts ;
- exécutions récentes ;
- règles de paire (`minOrderAmt`, `qtyStep`, `tickSize`) ;
- analyse des marchés CRYPTO/USDC ;
- propositions BUY/SELL LIMIT ou MARKET ;
- propositions d’annulation/remplacement ;
- exécution réelle uniquement après confirmation dans l’APK.

La clé doit permettre `SpotTrade`. Aucun droit de retrait n’est nécessaire ni souhaité.

## Propositions d’ordres

La file principale est la table Supabase `chk_trade_proposals`.

Une proposition contient notamment :

- BUY / SELL ;
- symbole ;
- MARKET / LIMIT ;
- montant USDC ;
- quantité de token si nécessaire ;
- prix LIMIT ;
- raison/analyse ;
- confiance ;
- statut ;
- expiration.

L’APK récupère les propositions `pending`. Le clic **CONFIRMER** réserve la proposition, vérifie l’état et les contraintes Bybit puis seulement ensuite permet l’exécution réelle.

## Annulations et remplacements

La table `chk_cancel_proposals` sert aux demandes d’annulation/remplacement.

ChatGPT peut préparer :

```text
ANNULER ordre X
→ éventuellement préparer remplacement Y
→ CHK Crypto affiche la décision
→ utilisateur CONFIRME
→ annulation réelle Bybit
→ seulement après succès, proposition de remplacement
→ nouvelle confirmation utilisateur requise pour le nouvel ordre
```

## Bloc-notes

`chk_crypto_notes` conserve la mémoire des analyses automatisées et manuelles :

- état du marché ;
- positions ;
- P/L ;
- ordres ;
- analyse 1m / 1h / 1j / 1w ;
- indicateurs ;
- décision ;
- points à surveiller au passage suivant.

## MCP / Workspace

Le connecteur utilisateur est **`@Binance Workspace`**, mais son identité fonctionnelle est **CHK Crypto Workspace**.

La surface canonique attendue expose notamment :

- `get_workspace_info`
- `get_portfolio_summary`
- `list_assets`
- `get_bybit_connection_info`
- `get_bybit_portfolio_summary`
- `list_bybit_assets`
- `list_bybit_open_orders`
- `list_bybit_recent_executions`
- `list_bybit_usdc_markets`
- `get_bybit_market_snapshot`
- `get_bybit_instrument_rules`
- `create_trade_proposal`
- `list_trade_proposals`
- `create_cancel_proposal`
- `list_cancel_proposals`
- `create_note`
- `list_notes`

Le premier contrôle d’un nouveau chat ou d’une tâche planifiée doit être `get_workspace_info`. Il doit annoncer ce dépôt canonique. Sinon aucune proposition ne doit être créée.

L’ancien service Binance-only `chk-binance-mcp` est **retiré** et ne doit plus être utilisé.

## Analyse temps réel

CHK Crypto possède un espace d’analyse séparé du portefeuille pour éviter de surcharger l’interface principale :

- chandeliers ;
- unités 1m / 5m / 15m / 1h / 4h / 1j / 1w ;
- RSI ;
- bandes de Bollinger ;
- EMA 20 / EMA 50 ;
- MACD ;
- ATR ;
- volumes ;
- supports/résistances et synthèse technique.

## Sécurité des secrets

- Ne jamais commiter API keys, secrets, keystore ou mots de passe dans ce dépôt public.
- Les secrets serveur doivent rester dans les environnements sécurisés Render/Supabase/GitHub Actions.
- La signature Android doit rester stable afin que les mises à jour puissent s’installer par-dessus l’application existante.
- Même `applicationId` + `versionCode` croissant + même signature.

## APK

Un APK réel est produit uniquement avec Gradle/GitHub Actions. Ne jamais fabriquer ou renommer un faux APK.

Le workflow officiel est :

`.github/workflows/build-apk.yml`

Toute évolution Android doit être validée par une compilation réelle avant d’être annoncée comme installable.
