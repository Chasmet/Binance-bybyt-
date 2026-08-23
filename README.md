# CHK Binance + Bybit Workspace

Application Android personnelle regroupant deux espaces : **Binance** et **Bybit**.

## Version 0.3

### Binance
- Connexion API Binance en **lecture seule**.
- Clé API et Secret Key chiffrés localement avec Android Keystore.
- Portefeuille et valorisation estimée EUR / USDT.
- Historique Spot, PRU estimé, achats/ventes et P/L estimée.
- Synchronisation privée avec le **CHK Binance Workspace** existant.
- Alertes de prix persistantes et notifications Android.

### Bybit EU
- Connexion directe à l'API V5 EEE : `https://api.bybit.eu`.
- API Key et Secret Key chiffrés localement avec Android Keystore.
- Portefeuille UNIFIED et valorisation estimée EUR / USD.
- Historique des exécutions Spot et PRU estimé.
- Lecture des informations de la clé API et de ses permissions Spot.
- Synchronisation privée Bybit vers le même backend **CHK Crypto Workspace**.
- Aucun Secret Bybit n'est envoyé à Supabase, GitHub ou ChatGPT.

### Ordres limite Bybit
La clé Bybit peut posséder la permission **Spot Trader**, mais la v0.3 ne contient volontairement **aucun appel de création d'ordre**. L'étape suivante ajoutera uniquement les ordres **Spot Limit**, sans levier, après validation par e-mail.

### Bloc-notes CHK partagé
- Notes privées communes à Binance et Bybit.
- Types rapides : **ACHAT**, **VENTE**, **ALERTE**, **NOTE**.
- Stockage privé Supabase ; aucune note financière n'est publiée dans GitHub.

## Sécurité

- Ne jamais activer les retraits pour les clés utilisées par cette application.
- Les secrets API restent chiffrés sur le téléphone.
- Les snapshots Workspace ne contiennent ni API Key brute ni Secret Key.

## APK

Le workflow `Build Android APK` compile automatiquement `app-debug.apk` et le publie comme artefact GitHub Actions.
