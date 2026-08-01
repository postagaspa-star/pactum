"""Distribuzione (tappa 6): il postino pubblica le versioni e serve gli APK.

Tre superfici, tutte SENZA auth (metadata pubblico + download; la vera garanzia
d'integrita' e' la firma dell'APK, stessa chiave e versionCode crescente):

- GET /api/versione   -> l'ultima versione di ciascuna app (per l'auto-update).
- GET /scarica        -> pagina HTML italiana con i due APK e le istruzioni.
- GET /scarica/pactum-{ruolo}.apk -> il file firmato, o una 404 gentile se manca.

Le versioni pubblicizzate stanno in un JSON (config.versioni_path) aggiornabile
senza toccare il codice; gli APK in una cartella (config.apk_dir) dove la build
copia i release firmati (mai nel repo)."""

import json
from datetime import datetime
from pathlib import Path

from fastapi import APIRouter, Request
from fastapi.responses import FileResponse, HTMLResponse

# Fallback se il JSON manca o e' illeggibile: /api/versione non deve mai cadere,
# l'app ci si appoggia per decidere se aggiornarsi.
VERSIONI_DEFAULT = {
    "figlio": {
        "versione_code": 3,
        "versione_nome": "0.3.0",
        "url": "/scarica/pactum-figlio.apk",
        "note": None,
    },
    "genitore": {
        "versione_code": 3,
        "versione_nome": "0.3.0",
        "url": "/scarica/pactum-genitore.apk",
        "note": None,
    },
}

# I due APK serviti: nome del file su /scarica -> (nome nella cartella apk, ruolo).
APK = {
    "pactum-figlio.apk": "figlio",
    "pactum-genitore.apk": "genitore",
}

MEDIA_TYPE_APK = "application/vnd.android.package-archive"

versione_router = APIRouter()  # montato sotto /api
scarica_router = APIRouter()  # montato alla radice


def carica_versioni(path: str) -> dict:
    """Legge le versioni dal JSON a ogni richiesta (aggiornabile a caldo).
    Su file mancante o corrotto ricade sui default: il metadata resta servibile."""
    try:
        with open(path, encoding="utf-8") as f:
            dati = json.load(f)
    except (OSError, json.JSONDecodeError):
        return VERSIONI_DEFAULT
    return dati if isinstance(dati, dict) else VERSIONI_DEFAULT


@versione_router.get("/versione")
def versione(request: Request):
    return carica_versioni(request.app.state.settings.versioni_path)


# Il browser NON deve mai servire una copia in cache di questa pagina o di un
# APK: chi torna qui dopo un aggiornamento vedrebbe la versione vecchia e
# scaricherebbe il file vecchio, senza capire perche' "non cambia niente".
SENZA_CACHE = {
    "Cache-Control": "no-store, no-cache, must-revalidate, max-age=0",
    "Pragma": "no-cache",
    "Expires": "0",
}


@scarica_router.get("/scarica", response_class=HTMLResponse)
def pagina_scarica(request: Request):
    versioni = carica_versioni(request.app.state.settings.versioni_path)
    return HTMLResponse(
        _pagina_html(versioni, request.app.state.settings.apk_dir), headers=SENZA_CACHE
    )


@scarica_router.get("/scarica/{nome_file}")
def scarica_apk(nome_file: str, request: Request):
    ruolo = APK.get(nome_file)
    if ruolo is None:
        return HTMLResponse(_non_trovato_html(nome_file), status_code=404, headers=SENZA_CACHE)
    percorso = Path(request.app.state.settings.apk_dir) / nome_file
    if not percorso.is_file():
        return HTMLResponse(_non_trovato_html(nome_file), status_code=404, headers=SENZA_CACHE)
    return FileResponse(
        percorso, media_type=MEDIA_TYPE_APK, filename=nome_file, headers=SENZA_CACHE
    )


def _versione_nome(versioni: dict, ruolo: str) -> str:
    dato = versioni.get(ruolo) if isinstance(versioni, dict) else None
    if isinstance(dato, dict) and dato.get("versione_nome"):
        return str(dato["versione_nome"])
    return VERSIONI_DEFAULT[ruolo]["versione_nome"]


def _data_apk(apk_dir: str, nome_file: str) -> str:
    """Quando e' stato pubblicato quel file: un riscontro visivo immediato che
    la pagina non e' una copia vecchia rimasta nel browser."""
    try:
        ts = (Path(apk_dir) / nome_file).stat().st_mtime
        return datetime.fromtimestamp(ts).strftime("%d/%m alle %H:%M")
    except OSError:
        return "—"


def _pagina_html(versioni: dict, apk_dir: str = "") -> str:
    v_figlio = _versione_nome(versioni, "figlio")
    v_genitore = _versione_nome(versioni, "genitore")
    d_figlio = _data_apk(apk_dir, "pactum-figlio.apk")
    d_genitore = _data_apk(apk_dir, "pactum-genitore.apk")
    return f"""<!DOCTYPE html>
<html lang="it">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Pactum — Scarica le app</title>
<style>
  :root {{ color-scheme: light dark; }}
  * {{ box-sizing: border-box; }}
  body {{
    font-family: system-ui, -apple-system, Segoe UI, Roboto, sans-serif;
    line-height: 1.55; margin: 0; padding: 1.5rem 1rem 3rem;
    max-width: 44rem; margin-inline: auto; color: #1a1a1a; background: #fafafa;
  }}
  @media (prefers-color-scheme: dark) {{
    body {{ color: #e8e8e8; background: #161616; }}
    .card {{ background: #222 !important; border-color: #333 !important; }}
    .box {{ background: #1e1e1e !important; border-color: #333 !important; }}
    code {{ background: #333 !important; }}
  }}
  h1 {{ font-size: 1.6rem; margin-bottom: .2rem; }}
  .occhiello {{ color: #666; margin-top: 0; }}
  .card {{
    border: 1px solid #e0e0e0; border-radius: 12px; background: #fff;
    padding: 1.1rem 1.2rem; margin: 1rem 0;
  }}
  .card h2 {{ margin: 0 0 .3rem; font-size: 1.2rem; }}
  .chi {{ color: #666; margin: 0 0 .9rem; font-size: .95rem; }}
  .btn {{
    display: inline-block; background: #2e6df0; color: #fff;
    text-decoration: none; padding: .7rem 1.2rem; border-radius: 9px;
    font-weight: 600; font-size: 1.02rem;
  }}
  .btn:active {{ opacity: .85; }}
  .ver {{ color: #888; font-size: .85rem; margin-left: .6rem; }}
  .pubbl {{ color: #888; font-size: .85rem; margin: .55rem 0 0; }}
  .box {{
    border: 1px solid #e6e6e6; background: #f4f4f6; border-radius: 10px;
    padding: .9rem 1.1rem; margin: 1rem 0;
  }}
  .box h3 {{ margin: 0 0 .5rem; font-size: 1.05rem; }}
  ol {{ margin: .3rem 0 0; padding-left: 1.3rem; }}
  li {{ margin: .35rem 0; }}
  code {{ background: #ececec; padding: .1rem .35rem; border-radius: 5px; font-size: .92em; }}
  .nota {{ color: #666; font-size: .9rem; margin-top: 2rem; }}
</style>
</head>
<body>
  <h1>Pactum</h1>
  <p class="occhiello">Il patto digitale tra te e i tuoi. Scarica l'app giusta per ciascuno.</p>

  <div class="card">
    <h2>App del figlio</h2>
    <p class="chi">Va installata sul telefono del <strong>ragazzo</strong>. Misura l'uso,
       custodisce le regole del patto e registra sforamenti e bonus.</p>
    <a class="btn" href="/scarica/pactum-figlio.apk">Scarica app figlio<span class="ver">v{v_figlio}</span></a>
    <p class="pubbl">Pubblicata il {d_figlio}</p>
  </div>

  <div class="card">
    <h2>App del genitore</h2>
    <p class="chi">Va installata sul telefono del <strong>genitore</strong>. Mostra la finestra
       (regole, semaforo, sforamenti, silenzi) e permette proposte e conferme.</p>
    <a class="btn" href="/scarica/pactum-genitore.apk">Scarica app genitore<span class="ver">v{v_genitore}</span></a>
    <p class="pubbl">Pubblicata il {d_genitore}</p>
  </div>

  <div class="box">
    <h3>Come si installa (leggi prima)</h3>
    <ol>
      <li>Tocca il pulsante di download qui sopra: il file <code>.apk</code> finisce nei Download.
          Aprilo per installare.</li>
      <li><strong>Avviso Play Protect:</strong> Android potrebbe dire che l'app arriva da fuori dallo
          store e proporre di analizzarla. Scegli <strong>&ldquo;Installa comunque&rdquo;</strong> —
          Pactum non gira di nascosto, sai di averla.</li>
      <li><strong>Accesso ai dati di utilizzo (solo app figlio):</strong> serve per misurare il tempo
          delle app. Al primo tentativo Android lo blocca (&ldquo;impostazione non disponibile&rdquo;).
          Vai su <em>Info app &rsaquo; menu &#8942; &rsaquo;</em>
          <strong>&ldquo;Consenti impostazioni con limitazioni&rdquo;</strong> (chiede PIN o impronta),
          poi riattiva l'accesso. Il primo tentativo fallito e' necessario: prima, la voce di sblocco
          non compare.</li>
      <li>Concedi le notifiche e l'esenzione dal risparmio batteria quando l'app le chiede: servono a
          non perdere i battiti e gli avvisi.</li>
    </ol>
  </div>

  <p class="nota">Questa pagina e' l'indirizzo da cui aggiornare le app: se il postino ne pubblica una
     versione nuova, le app la scaricano da qui da sole. Tienila tra i preferiti.</p>
</body>
</html>
"""


def _non_trovato_html(nome_file: str) -> str:
    return f"""<!DOCTYPE html>
<html lang="it">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Pactum — File non disponibile</title>
<style>
  :root {{ color-scheme: light dark; }}
  body {{
    font-family: system-ui, -apple-system, Segoe UI, Roboto, sans-serif;
    line-height: 1.55; margin: 0; padding: 3rem 1.2rem; max-width: 34rem;
    margin-inline: auto; text-align: center; color: #1a1a1a; background: #fafafa;
  }}
  @media (prefers-color-scheme: dark) {{ body {{ color: #e8e8e8; background: #161616; }} }}
  h1 {{ font-size: 1.4rem; }}
  a {{ color: #2e6df0; }}
</style>
</head>
<body>
  <h1>Questo file non e' ancora disponibile</h1>
  <p>L'APK <code>{nome_file}</code> non e' stato ancora pubblicato sul postino,
     oppure l'indirizzo non e' corretto.</p>
  <p>Torna alla <a href="/scarica">pagina di download</a> e riprova tra poco.</p>
</body>
</html>
"""
