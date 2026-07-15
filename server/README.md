# Pactum — server "postino" (FastAPI + SQLite)

1. `python -m venv .venv` poi `.venv\Scripts\activate` (Windows) o `source .venv/bin/activate` (Linux/NAS)
2. `pip install -r requirements.txt`
3. `uvicorn app.main:create_app --factory --reload` → prova su http://127.0.0.1:8000/api/salute
4. Token dev: figlio `dev-token-figlio`, genitore `dev-token-genitore` — in produzione impostare `PACTUM_TOKEN_FIGLIO`, `PACTUM_TOKEN_GENITORE` e `PACTUM_DB` (percorso del database). Opzionale: `PACTUM_TIMEZONE` (fuso del patto per i bucket giorno/settimana, default `Europe/Rome`)
5. Test: `pytest` (dalla cartella `server/`)
