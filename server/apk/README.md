# APK serviti dal postino

Questa cartella e' servita da `GET /scarica/pactum-figlio.apk` e
`GET /scarica/pactum-genitore.apk` (route `app/routes/distribuzione.py`).

## Cosa ci va

Gli **APK release firmati** delle due app, con questi nomi esatti:

- `pactum-figlio.apk`
- `pactum-genitore.apk`

Li copia qui la build (`assembleRelease`, stessa chiave di firma di sempre,
`versionCode` crescente). La versione pubblicizzata alle app si aggiorna in
`../app/versioni.json` (letto a caldo da `GET /api/versione`).

## Cosa NON ci va

Gli APK **non si committano** (`server/apk/*.apk` e' in `.gitignore`): sono
artefatti di build, pesano, e vanno consegnati fuori dal repo. In questa cartella
resta versionato solo questo README e `.gitkeep`. Finche' un APK non e' stato
copiato qui, `GET /scarica/pactum-*.apk` risponde con una pagina 404 gentile.

La chiave di firma vive fuori dal repo e da OneDrive (`C:\Users\andre\pactum-keys\`,
vedi piano tappa 6): custodirla per sempre, `versionCode` sempre crescente.
