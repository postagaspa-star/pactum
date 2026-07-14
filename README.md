# Pactum

App Android di "patto" per adolescenti (15-17): **il figlio si dà le proprie regole, il genitore verifica**. Niente blocchi: l'app è un testimone, non un carceriere. Distribuzione: APK sideload da browser.

- **Cosa e perché (missione inclusa):** [docs/concept.md](docs/concept.md)
- **Il ragionamento e le analisi dietro le scelte:** [docs/analisi-e-ragionamento.md](docs/analisi-e-ragionamento.md)
- **Come:** [docs/architettura.md](docs/architettura.md)

| Cartella | Contenuto |
|---|---|
| `app-figlio/` | App Android del figlio (Kotlin/Compose) |
| `app-genitore/` | App Android del genitore |
| `server/` | Il "postino": FastAPI + SQLite, Docker sul NAS |
