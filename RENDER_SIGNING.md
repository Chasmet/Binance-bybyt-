# CHK Crypto — signature Android stable

Depuis la version 0.6.1, les builds de la branche `main` récupèrent la clé de signature Android depuis Render via un jeton GitHub Actions OIDC vérifié côté serveur.

- Aucun keystore ni mot de passe n'est stocké dans ce dépôt public.
- Les pull requests ne reçoivent jamais le matériel de signature et compilent uniquement une APK debug de validation.
- Seuls les runs `push` / `workflow_dispatch` de `.github/workflows/build-apk.yml` sur `refs/heads/main` peuvent demander la clé stable.
- L'APK release est vérifiée avec `apksigner` avant publication dans GitHub Actions Artifacts.
