"""(v3.7) Il telefono spento non e' un'interruzione (contratto-api.md, "v3.7 — telefono
spento o in stand-by"): anche il telefono manda `sospensione` {"motivo": "spegnimento"}
quando si spegne, e il server lo tratta come il computer.

- Dopo una sospensione, finche' non arriva un segno di vita (un battito o una
  `ripresa`), `stato_silenzio` dice `silente: false`, `spento: true`, `spento_dal`.
- Una sospensione consegnata in ritardo (alla riaccensione) vale dal suo `ts_device`,
  con le tutele delle sessioni: non nel futuro, non piu' di 48 ore prima dell'arrivo,
  e entro 2 minuti dall'arrivo vale l'arrivo.
- Sospensione e ripresa nello stesso pacco: vince quella successa dopo (per momento,
  e a parita' per ordine d'arrivo), in qualsiasi ordine arrivino.
- Il computer fa come prima."""

from datetime import datetime, timedelta

import pytest

from aiuti_v3 import dispositivo_abbinato, eventi
from conftest import FIGLIO, GENITORE, ORA_INIZIALE

ORA = "2026-07-14T10:00:00+00:00"


def _ms(quando: datetime) -> int:
    return int(quando.timestamp() * 1000)


def _iso(quando: datetime) -> str:
    return quando.isoformat(timespec="seconds")


def _sospensione(id_evento="spento", ts_device=None, motivo="spegnimento") -> dict:
    return {"id": id_evento, "tipo": "sospensione", "ts_device": ts_device, "dettagli": {"motivo": motivo}}


def _ripresa(id_evento="acceso", ts_device=None) -> dict:
    return {"id": id_evento, "tipo": "ripresa", "ts_device": ts_device, "dettagli": {"motivo": "avvio"}}


def _silenzio(client, dispositivo_id=1) -> dict:
    (voce,) = [d for d in client.get("/api/finestra", headers=GENITORE).json()["dispositivi"]
               if d["id"] == dispositivo_id]
    return voce["stato_silenzio"]


def _battito(client, headers=FIGLIO):
    assert client.post("/api/battito", json={"versione_app": "0.14.0"}, headers=headers).status_code == 200


def test_il_telefono_spento_non_e_silente(client, orologio):
    _battito(client)
    orologio.avanza(minutes=5)
    risposta = eventi(client, FIGLIO, _sospensione())
    assert risposta == {"ricevuti": 1, "nuovi": 1, "duplicati": 0}  # accettata dal telefono
    orologio.avanza(hours=9)
    acceso_alle = _iso(ORA_INIZIALE + timedelta(minutes=5))
    assert _silenzio(client) == {"ultimo_battito": ORA, "silente": False, "spento": True, "spento_dal": acceso_alle}
    # il primo livello della finestra (app del genitore 0.7) e la famiglia dicono lo stesso
    assert client.get("/api/finestra", headers=GENITORE).json()["stato_silenzio"]["spento"] is True
    (figlio,) = client.get("/api/famiglia", headers=GENITORE).json()["figli"]
    assert figlio["dispositivi"][0]["stato_silenzio"]["spento_dal"] == acceso_alle
    # niente interruzioni, niente avvisi, niente manomissioni
    finestra = client.get("/api/finestra", headers=GENITORE).json()
    assert finestra["riepilogo"]["interruzioni"] == 0 and finestra["manomissioni_recenti"] == []
    assert client.get("/api/notifiche", headers=GENITORE).json()["notifiche"] == []
    # si riaccende: il primo battito basta
    _battito(client)
    assert _silenzio(client) == {"ultimo_battito": _iso(ORA_INIZIALE + timedelta(hours=9, minutes=5)),
                                 "silente": False, "spento": False, "spento_dal": None}
    # e se poi tace senza sospensione, e' silente come sempre
    orologio.avanza(minutes=46)
    assert _silenzio(client)["silente"] is True


def test_basta_la_ripresa(client, orologio):
    eventi(client, FIGLIO, _sospensione())
    orologio.avanza(hours=1)
    assert _silenzio(client)["spento"] is True
    eventi(client, FIGLIO, _ripresa())
    assert _silenzio(client) == {"ultimo_battito": None, "silente": True, "spento": False, "spento_dal": None}


def test_sospensione_consegnata_alla_riaccensione(client, orologio):
    """Il telefono non ha fatto in tempo a mandarla: la manda alla riaccensione col
    ts_device dello spegnimento, insieme alla ripresa. Il telefono e' acceso; e prima
    che arrivi la ripresa, era spento da quando l'ha detto lui."""
    _battito(client)
    spento_alle = ORA_INIZIALE + timedelta(minutes=20)
    orologio.avanza(hours=8)  # nel frattempo, per il genitore, era silenzio
    assert _silenzio(client)["silente"] is True
    eventi(client, FIGLIO, _sospensione(ts_device=_ms(spento_alle)))
    assert _silenzio(client) == {"ultimo_battito": ORA, "silente": False, "spento": True,
                                 "spento_dal": _iso(spento_alle)}
    eventi(client, FIGLIO, _ripresa(ts_device=_ms(orologio.corrente)))
    assert _silenzio(client)["spento"] is False


@pytest.mark.parametrize("ordine", ["sospensione prima", "ripresa prima"])
def test_nello_stesso_pacco_vince_quella_successa_dopo(client, orologio, ordine):
    """Alla riaccensione la sospensione recuperata e la ripresa arrivano insieme: in
    qualsiasi ordine le metta il telefono, vince la ripresa, che e' successa dopo."""
    _battito(client)
    spento_alle = ORA_INIZIALE + timedelta(minutes=30)
    orologio.avanza(hours=7)
    pacco = [_sospensione(ts_device=_ms(spento_alle)), _ripresa(ts_device=_ms(orologio.corrente))]
    if ordine == "ripresa prima":
        pacco.reverse()
    eventi(client, FIGLIO, *pacco)
    assert _silenzio(client) == {"ultimo_battito": ORA, "silente": True, "spento": False, "spento_dal": None}
    _battito(client)
    assert _silenzio(client)["silente"] is False


def test_senza_ora_del_telefono_conta_l_ordine_d_arrivo(client, orologio):
    """Due fatti con lo stesso momento (nessun ts_device, o entro 2 minuti dall'arrivo):
    vale l'ordine in cui sono arrivati, come per il computer. Ripresa e poi
    sospensione = si e' appena spento."""
    eventi(client, FIGLIO, _ripresa(), _sospensione())
    assert _silenzio(client)["spento"] is True
    eventi(client, FIGLIO, _sospensione("s2"), _ripresa("r2", ts_device=_ms(orologio.corrente - timedelta(seconds=30))))
    assert _silenzio(client)["spento"] is False


@pytest.mark.parametrize("scarto, vale_l_arrivo", [
    (timedelta(minutes=-10), True),  # nel futuro: vale l'arrivo
    (timedelta(minutes=1), True),  # entro 2 minuti: una sospensione mandata con la rete
    (timedelta(minutes=3), False),  # consegnata in ritardo: vale la sua ora
    (timedelta(hours=48), False),
    (timedelta(hours=48, seconds=1), True),  # oltre 48 ore: vale l'arrivo
])
def test_le_tutele_sull_ora_del_telefono(client, orologio, scarto, vale_l_arrivo):
    orologio.avanza(days=3)
    adesso = orologio.corrente
    eventi(client, FIGLIO, _sospensione(ts_device=_ms(adesso - scarto)))
    atteso = adesso if vale_l_arrivo else adesso - scarto
    assert _silenzio(client)["spento_dal"] == _iso(atteso)


@pytest.mark.parametrize("ts_device", [None, -1, 99999999999999999, 0])
def test_un_ora_del_telefono_impossibile_vale_l_arrivo(client, ts_device):
    eventi(client, FIGLIO, _sospensione(ts_device=ts_device))
    assert _silenzio(client)["spento_dal"] == ORA


def test_un_battito_dopo_lo_spegnimento_dichiarato_e_un_segno_di_vita(client, orologio):
    """La sospensione arriva in ritardo, ma dopo l'ora che dichiara il telefono ha gia'
    mandato un battito: non e' spento (e se tace da piu' di 45 minuti, e' silente)."""
    orologio.avanza(hours=1)
    _battito(client)
    orologio.avanza(hours=2)
    eventi(client, FIGLIO, _sospensione(ts_device=_ms(orologio.corrente - timedelta(hours=3))))
    stato = _silenzio(client)
    assert (stato["spento"], stato["silente"]) == (False, True)


def test_una_sospensione_vecchia_non_conta_piu(client, orologio):
    eventi(client, FIGLIO, _sospensione())
    orologio.avanza(days=5)
    eventi(client, FIGLIO, _ripresa())
    orologio.avanza(days=5)
    assert _silenzio(client)["spento"] is False


def test_ogni_telefono_per_conto_suo(client, orologio):
    tablet, tablet_id = dispositivo_abbinato(client, 1, "Tablet", "telefono")
    _battito(client)
    _battito(client, tablet)
    eventi(client, tablet, _sospensione())
    orologio.avanza(hours=1)
    assert _silenzio(client, tablet_id)["spento"] is True
    assert _silenzio(client) == {"ultimo_battito": ORA, "silente": True, "spento": False, "spento_dal": None}


def test_il_computer_fa_come_il_telefono(client, orologio):
    """Il computer resta com'era (test_computer.py), e la sospensione consegnata in
    ritardo vale dalla sua ora anche per lui: telefoni e computer hanno le stesse regole."""
    pc, pc_id = dispositivo_abbinato(client, 1, "Computer", "computer")
    _battito(client, pc)
    sospeso_alle = ORA_INIZIALE + timedelta(minutes=15)
    orologio.avanza(hours=10)
    eventi(client, pc, _sospensione(ts_device=_ms(sospeso_alle), motivo="sospensione"))
    assert _silenzio(client, pc_id) == {"ultimo_battito": ORA, "silente": False, "spento": True,
                                        "spento_dal": _iso(sospeso_alle)}
    eventi(client, pc, {"id": "su", "tipo": "ripresa", "ts_device": _ms(orologio.corrente),
                        "dettagli": {"motivo": "riattivazione"}})
    assert _silenzio(client, pc_id)["spento"] is False


def test_la_sospensione_resta_nel_registro_ma_non_e_una_notifica(client, orologio):
    """Nel registro c'e' (come ogni evento), nessun avviso al genitore e nessun dato
    del giorno cambia."""
    eventi(client, FIGLIO, _sospensione(), _ripresa())
    assert client.get("/api/notifiche", headers=GENITORE).json()["notifiche"] == []
    patto = client.get("/api/patto", headers=FIGLIO).json()
    assert patto["riepilogo"]["interruzioni"] == 0
