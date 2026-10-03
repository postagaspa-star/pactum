"""(v3.6) I genitori (contratto-api.md, "v3.6 — la famiglia con piu' genitori e le
faccende"). Qui vivono le letture condivise da piu' endpoint: chi e' un genitore come
lo citano le risposte ({id, nome}, col nome di adesso), chi ha deciso le righe di
prima della v3.6 (il genitore 1), e quali notifiche un genitore non ha ancora letto.

Tutti i genitori sono uguali: vedono tutto, ricevono gli avvisi, decidono. Il
genitore 1 e' quello del token d'ambiente, cioe' la riga con l'id piu' basso (i
genitori non si cancellano mai: revocato non vuol dire sparito)."""

import sqlite3

from fastapi import HTTPException

NON_TROVATO = "genitore non trovato"


def riferimento(riga: sqlite3.Row) -> dict:
    """Il genitore come lo citano proposte, decisioni e faccende: solo chi e'."""
    return {"id": riga["id"], "nome": riga["nome"]}


def descrizione(riga: sqlite3.Row) -> dict:
    return {
        **riferimento(riga),
        "abbinato": riga["abbinato_ts"] is not None,
        "revocato": riga["revocato_ts"] is not None,
        "creato_ts": riga["creato_ts"],
    }


def genitore_o_404(conn: sqlite3.Connection, genitore_id: int) -> sqlite3.Row:
    riga = conn.execute("SELECT * FROM genitori WHERE id = ?", (genitore_id,)).fetchone()
    if riga is None:
        raise HTTPException(status_code=404, detail=NON_TROVATO)
    return riga


def ancora_valido(conn: sqlite3.Connection, chi) -> None:
    """Dentro il lock (BEGIN IMMEDIATE) di ogni scrittura di un genitore: chi chiama non
    e' stato revocato nel frattempo. Il token si controlla quando la richiesta arriva;
    una revoca scritta tra quel momento e la scrittura la deve fermare lo stesso, con
    lo stesso 401 di un token revocato. Per un dispositivo non fa niente (la revoca dei
    dispositivi la guarda chi scrive per loro)."""
    if chi.ruolo != "genitore":
        return
    riga = conn.execute("SELECT revocato_ts FROM genitori WHERE id = ?", (chi.genitore_id,)).fetchone()
    if riga is None or riga["revocato_ts"] is not None:
        raise HTTPException(status_code=401, detail="genitore revocato")


def nome(conn: sqlite3.Connection, genitore_id: int) -> str:
    """Il nome di adesso del genitore: quello che il figlio legge nei messaggi
    ("Mamma ha approvato la sessione «Studio»")."""
    return genitore_o_404(conn, genitore_id)["nome"]


class Firme:
    """Chi ha deciso o scritto cosa, per una risposta: i genitori letti una volta sola
    (sono pochi), ciascuno come {id, nome} col nome di ADESSO, non quello di allora.

    Le righe scritte prima della v3.6 non dicono quale genitore: allora ce n'era uno
    solo, il genitore 1 (contratto, "Chi ha fatto cosa"). `di_o_primo` le attribuisce a
    lui; `di` lascia None dove non c'e' nessuno."""

    def __init__(self, conn: sqlite3.Connection):
        self._per_id = {
            r["id"]: riferimento(r)
            for r in conn.execute("SELECT id, nome FROM genitori ORDER BY id").fetchall()
        }

    def di(self, genitore_id: int | None) -> dict | None:
        if genitore_id is None:
            return None
        return self._per_id.get(genitore_id)

    def primo(self) -> dict | None:
        return self._per_id[min(self._per_id)] if self._per_id else None

    def di_o_primo(self, genitore_id: int | None) -> dict | None:
        return self.di(genitore_id) if genitore_id is not None else self.primo()


def non_letta(conn: sqlite3.Connection, genitore_id: int) -> tuple[str, tuple]:
    """La condizione SQL "notifica del genitore non ancora letta da QUESTO genitore"
    (contratto, "Le notifiche del genitore: lette da ciascuno"): non chiusa per tutti
    (`letta = 0`: v. db.TABELLE_GENITORI), nata dopo di lui (quelle di prima valgono
    come gia' lette) e che lui non ha segnato come letta."""
    riga = conn.execute(
        "SELECT notifiche_dopo_id FROM genitori WHERE id = ?", (genitore_id,)
    ).fetchone()
    dopo_id = riga["notifiche_dopo_id"] if riga is not None else 0
    return (
        "letta = 0 AND id > ? AND NOT EXISTS (SELECT 1 FROM notifiche_lette_genitori l"
        " WHERE l.notifica_id = notifiche.id AND l.genitore_id = ?)",
        (dopo_id, genitore_id),
    )
