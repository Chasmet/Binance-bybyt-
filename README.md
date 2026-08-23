# CHK Binance + Bybit Workspace

Application Android personnelle pour regrouper deux espaces : **Binance** et **Bybit**.

## Version 0.1

- Onglet **Binance** actif en premier.
- Connexion Binance API en **lecture seule** (clé API + secret stockés localement sur l'appareil).
- Synchronisation du portefeuille Spot et valorisation approximative en USDT/EUR.
- Historique local des dernières synchronisations.
- Onglet **Bybit** déjà présent, prêt pour le futur connecteur Bybit.
- **Bloc-notes CHK** local pour écrire des ordres, niveaux d'achat/vente, alertes et analyses.
- Thème sombre mobile.
- Build APK automatique avec GitHub Actions.

## Sécurité

Créer une clé Binance dédiée avec uniquement les permissions nécessaires à la lecture. **Ne jamais activer les retraits**. Le dépôt ne contient aucune clé API.

Le bloc-notes est local pour éviter de publier des informations financières dans ce dépôt public. Une synchronisation privée assistant → application pourra être branchée ensuite via un backend privé.

## APK

Le workflow `Build Android APK` génère `app-debug.apk` dans les artefacts GitHub Actions.
