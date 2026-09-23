from typing import Literal

from pydantic import BaseModel, Field, field_validator

ORARIO = r"^(?:[01]\d|2[0-3]):[0-5]\d$"

GiornoSettimana = Literal["lun", "mar", "mer", "gio", "ven", "sab", "dom"]


class ParametriLimiteTempo(BaseModel):
    app_o_categoria: str = Field(min_length=1)
    minuti_al_giorno: int = Field(ge=1, le=1440)


class ParametriFasciaOraria(BaseModel):
    dalle: str = Field(pattern=ORARIO)
    alle: str = Field(pattern=ORARIO)
    giorni: list[GiornoSettimana] = Field(min_length=1)

    @field_validator("giorni")
    @classmethod
    def senza_doppioni(cls, giorni: list[str]) -> list[str]:
        if len(set(giorni)) != len(giorni):
            raise ValueError("giorni duplicati")
        return giorni


class ParametriVitaReale(BaseModel):
    descrizione: str = Field(min_length=1)
    arbitro_nome: str = Field(min_length=1)
    frequenza: str = Field(min_length=1)


MODELLI_PARAMETRI = {
    "limite_tempo": ParametriLimiteTempo,
    "fascia_oraria": ParametriFasciaOraria,
    "vita_reale": ParametriVitaReale,
}


def valida_parametri(tipo: str, parametri: dict) -> dict:
    """Solleva pydantic.ValidationError se i parametri non rispettano il tipo di regola."""
    return MODELLI_PARAMETRI[tipo](**parametri).model_dump()


# (v3) app_o_categoria secondo il tipo del dispositivo della regola. Sui telefoni
# resta come prima (nome del pacchetto Android o categoria:*), ma le chiavi dei
# computer non valgono. Sui computer: exe:<nome del programma>, sito:<dominio
# registrabile> (entrambi minuscoli, senza percorsi) o categoria:*.
PREFISSI_SOLO_COMPUTER = ("exe:", "sito:")
CARATTERI_VIETATI_CHIAVE = "/\\:"


def chiave_adatta(tipo_dispositivo: str, chiave: str) -> bool:
    if tipo_dispositivo != "computer":
        return not chiave.startswith(PREFISSI_SOLO_COMPUTER)
    if chiave.startswith("categoria:"):
        return len(chiave) > len("categoria:")
    for prefisso in PREFISSI_SOLO_COMPUTER:
        if chiave.startswith(prefisso):
            nome = chiave[len(prefisso):]
            return (
                bool(nome)
                and nome == nome.lower()
                and not any(c.isspace() or c in CARATTERI_VIETATI_CHIAVE for c in nome)
            )
    return False


class RegolaCrea(BaseModel):
    tipo: Literal["limite_tempo", "fascia_oraria", "vita_reale"]
    parametri: dict
    # (v3) Su quale dispositivo del figlio: se manca, quello che chiama. Per le
    # vita_reale si ignora (sono del figlio).
    dispositivo_id: int | None = None


class RegolaPatch(BaseModel):
    parametri: dict
    proposta_id: int | None = None


class BattitoIn(BaseModel):
    # ts_device = epoch in millisecondi UTC, SOLO informativo (fa fede ts_server).
    ts_device: int | None = None
    versione_app: str | None = None
    # Millisecondi dall'ultimo avvio del telefono (euristiche su riavvii/orologio).
    elapsed_realtime: int | None = None
    batteria: int | None = Field(default=None, ge=0, le=100)


TipoEvento = Literal[
    "uso_giornaliero",
    # siti_giornalieri (v2.3): la fotografia dei SITI visitati, gemella di
    # uso_giornaliero. Non e' un'infrazione: non notifica e non tinge il semaforo.
    "siti_giornalieri",
    "riavvio",
    "manomissione",
    "sforamento",
    "bonus_usato",
    "dichiarazione",
    # (v3) Dei computer: Windows si spegne, va in sospensione o l'utente esce, e
    # poi torna. Il silenzio dopo una sospensione non e' un'interruzione.
    "sospensione",
    "ripresa",
]


class EventoIn(BaseModel):
    id: str = Field(min_length=1, max_length=128)
    tipo: TipoEvento
    ts_device: int | None = None  # epoch ms UTC, informativo
    dettagli: dict = Field(default_factory=dict)


class EventiIn(BaseModel):
    eventi: list[EventoIn]


class BonusIn(BaseModel):
    minuti: Literal[5, 15, 30]
    # regola_id obbligatorio (v2): il bonus allunga una regola limite_tempo attiva.
    regola_id: int
    motivo: str | None = None


class ProponiIn(BaseModel):
    regola_id: int
    # parametri per il tipo della regola OPPURE il marcatore {"azione": "elimina"}.
    parametri_proposti: dict
    motivazione: str | None = None
    # (v3) Facoltativo: il figlio lo dice gia' la regola; se c'e', deve combaciare.
    figlio_id: int | None = None


class RispostaPropostaIn(BaseModel):
    esito: Literal["accetta", "rifiuta"]
    motivazione: str | None = None


class DichiarazioneIn(BaseModel):
    regola_id: int
    esito: Literal["successo", "fallimento"]
    nota: str | None = None
    # YYYY-MM-DD; default: oggi nel fuso del patto (validato nella route).
    giorno: str | None = None


class VerdettoIn(BaseModel):
    verdetto: Literal["conferma", "conferma_per_conto", "ribalta"]
    nota: str | None = None
    # (v3) Facoltativo: il figlio lo dice gia' la dichiarazione; se c'e', deve combaciare.
    figlio_id: int | None = None


class SegnoIn(BaseModel):
    # (v3) Il segno va a un figlio; se manca, al figlio con l'id piu' basso (app 0.7).
    # Nessun altro campo: il testo non lo sceglie il genitore.
    figlio_id: int | None = None


LUNGHEZZA_MASSIMA_NOME = 40


class NomeIn(BaseModel):
    """(v3) Il nome di un figlio o di un dispositivo: 1-40 caratteri, spazi ai
    bordi tolti (un nome fatto di soli spazi non e' un nome)."""

    nome: str

    @field_validator("nome")
    @classmethod
    def nome_valido(cls, nome: str) -> str:
        nome = nome.strip()
        if not 1 <= len(nome) <= LUNGHEZZA_MASSIMA_NOME:
            raise ValueError(f"il nome va da 1 a {LUNGHEZZA_MASSIMA_NOME} caratteri")
        return nome


class DispositivoIn(NomeIn):
    tipo: Literal["telefono", "computer"]


class AbbinaIn(BaseModel):
    codice: str
    versione_app: str | None = None
    # (v3.1) Il tipo di chi si abbina (le app 0.8 lo mandano sempre): se il codice e'
    # di un dispositivo di un altro tipo, 409 tipo_non_corrispondente e il codice
    # resta valido. Facoltativo: senza, l'abbinamento va come prima.
    tipo: Literal["telefono", "computer"] | None = None
