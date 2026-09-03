# CHK Crypto — OrderBook Hunter MCP

## Principe

OrderBook Hunter est indépendant du Bot CHK et d'Auto-Trade. Il observe les carnets Bybit EU Spot CRYPTO/USDC, mémorise les événements et ne crée/exécute aucun ordre BUY/SELL.

Le contrat MCP cible les fonctions suivantes :

- `start_orderbook_watch(symbol)`
- `stop_orderbook_watch(symbol)`
- `list_orderbook_watches()`
- `get_orderbook_hunter_status(symbol)`
- `get_orderbook_walls(symbol)`
- `get_orderbook_events(symbol, limit)`
- `get_orderbook_anomaly_score(symbol)`
- `get_orderbook_absorption(symbol)`
- `clear_orderbook_history(symbol)`
- `add_orderbook_note(symbol, text)`
- `set_orderbook_alerts(symbol, enabled)`

## Pont compatible avec le MCP canonique actuel

Tant que ces noms ne sont pas exposés directement par le serveur MCP, l'APK utilise le canal canonique `chk_crypto_notes` déjà authentifié par l'identité de l'appareil.

ChatGPT écrit une note :

- exchange : `BYBIT`
- kind : `ORDERBOOK_CONTROL`
- content : objet JSON de commande

Exemple :

```json
{"action":"start_orderbook_watch","symbol":"SKRUSDC","alerts":true,"restore":true,"requestId":"skr-watch-1"}
```

L'APK traite la commande puis écrit :

- `ORDERBOOK_RESULT` : résultat de la commande ;
- `ORDERBOOK_HUNTER_STATE` : état temporel agrégé du marché surveillé.

Exemples de commandes :

```json
{"action":"stop_orderbook_watch","symbol":"SKRUSDC"}
{"action":"list_orderbook_watches"}
{"action":"get_orderbook_hunter_status","symbol":"SKRUSDC"}
{"action":"get_orderbook_walls","symbol":"SKRUSDC"}
{"action":"get_orderbook_events","symbol":"SKRUSDC","limit":100,"minutes":30}
{"action":"get_orderbook_anomaly_score","symbol":"SKRUSDC"}
{"action":"get_orderbook_absorption","symbol":"SKRUSDC","minutes":30}
{"action":"clear_orderbook_history","symbol":"SKRUSDC"}
{"action":"add_orderbook_note","symbol":"SKRUSDC","text":"Mur BUY reculé après approche du prix"}
{"action":"set_orderbook_alerts","symbol":"SKRUSDC","enabled":true}
```

## Sécurité sémantique

Les résultats ne doivent jamais affirmer `manipulation confirmée` uniquement à partir du carnet. Les classifications autorisées sont :

- `NORMAL`
- `ACTIVITÉ INHABITUELLE`
- `SPOOFING POTENTIEL`
- `COMPORTEMENT FORTEMENT SUSPECT`
- `ANOMALIE EXTRÊME`

Même avec un score élevé, le texte doit préciser que la manipulation n'est pas confirmée sans preuve extérieure.

## Mémoire temporelle

`ORDERBOOK_HUNTER_STATE` inclut l'état courant et un agrégat des 30 dernières minutes. Les événements détaillés restent dans la base SQLite dédiée de l'APK et peuvent être demandés via `get_orderbook_events`.

Le carnet de bord utilisateur/ChatGPT est séparé de celui du Bot CHK principal.
