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


class RegolaCrea(BaseModel):
    tipo: Literal["limite_tempo", "fascia_oraria", "vita_reale"]
    parametri: dict


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
    "uso_giornaliero", "riavvio", "manomissione", "sforamento", "bonus_usato", "dichiarazione"
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
    motivo: str | None = None
