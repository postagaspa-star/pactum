# Pactum — server "postino" (FastAPI + SQLite)

1. `python -m venv .venv` poi `.venv\Scripts\activate` (Windows) o `source .venv/bin/activate` (Linux/NAS)
2. `pip install -r requirements.txt`
3. `uvicorn app.main:create_app --factory --reload` → prova su http://127.0.0.1:8000/api/salute
4. Token dev: figlio `dev-token-figlio`, genitore `dev-token-genitore` — in produzione impostare `PACTUM_TOKEN_FIGLIO`, `PACTUM_TOKEN_GENITORE` e `PACTUM_DB` (percorso del database). Opzionale: `PACTUM_TIMEZONE` (fuso del patto per i bucket giorno/settimana, default `Europe/Rome`)
   - (v3) `PACTUM_TOKEN_GENITORE` è il token del genitore 1, `PACTUM_TOKEN_FIGLIO` quello del dispositivo 1 (il telefono delle app 0.7). Gli altri dispositivi si collegano col codice di 6 cifre creato dall'app del genitore (`POST /api/abbina`). Al primo avvio della v3 il database si migra da solo, in una transazione: tutti i dati esistenti vanno al figlio 1 / dispositivo 1.
5. Produzione: impostare `PACTUM_ENV=prod`. Con `prod` il server **rifiuta di partire** se un token e' ancora il default, e' piu' corto di 24 caratteri o i due token sono uguali. Deploy sul NAS: vedi [../docs/deploy-nas.md](../docs/deploy-nas.md), `docker-compose.yml` (radice) e `.env.example`.
6. Test: `pytest` (dalla cartella `server/`)
