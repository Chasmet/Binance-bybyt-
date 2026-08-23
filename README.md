# CHK Binance + Bybit Workspace

Application Android personnelle regroupant deux espaces : **Binance** et **Bybit**.

## Version 0.2

### Binance
- Connexion API Binance en **lecture seule**.
- Clé API et Secret Key chiffrés localement avec Android Keystore.
- Portefeuille et valorisation estimée EUR / USDT.
- Historique Spot, PRU estimé, achats/ventes et P/L estimée.
- Synchronisation privée avec le **CHK Binance Workspace** existant afin que ChatGPT retrouve le dernier snapshot.
- Alertes de prix persistantes et notifications Android, contrôlées environ toutes les 15 minutes.
- Aucun trading, transfert ou retrait automatique.

### Bybit
- Onglet **Bybit** déjà intégré dans la même APK.
- Structure prête pour le futur connecteur Bybit : portefeuille, historique, PRU, alertes et notes.

### Bloc-notes CHK partagé
- Notes privées liées au compte crypto synchronisé.
- Types rapides : **ACHAT**, **VENTE**, **ALERTE**, **NOTE**.
- Stockage privé Supabase ; aucune note financière n'est publiée dans ce dépôt GitHub.
- Préparé pour que ChatGPT puisse ajouter des ordres envisagés, niveaux, alertes ou analyses dans le bloc-notes.

## Sécurité

Créer une clé Binance dédiée avec uniquement les autorisations de lecture nécessaires. **Ne jamais activer les retraits** ; le trading n'est pas requis pour cette application.

Aucune API Key ou Secret Key n'est incluse dans le dépôt, l'APK, les snapshots Workspace ou le bloc-notes partagé.

## APK

Le workflow `Build Android APK` compile automatiquement `app-debug.apk` et le publie comme artefact GitHub Actions.
