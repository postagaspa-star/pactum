import re
import unicodedata
from datetime import date, datetime, timezone
from typing import Literal

from pydantic import BaseModel, Field, StrictBool, StrictInt, field_validator, model_validator

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

# (v3.3) Il limite sul totale del dispositivo, per telefoni e computer: tutto l'uso
# del giorno, lo stesso totale_minuti della fotografia uso_giornaliero. Non e' il
# nome di un'app: non combacia mai con una voce di uso_minuti o uso_categorie.
CHIAVE_TOTALE = "totale"


def chiave_adatta(tipo_dispositivo: str, chiave: str) -> bool:
    if chiave == CHIAVE_TOTALE:
        return True
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
    # (v3.4) Dal dispositivo si ignora: il figlio e' quello del token.
    figlio_id: int | None = None


class RispostaPropostaIn(BaseModel):
    esito: Literal["accetta", "rifiuta"]
    motivazione: str | None = None
    # (v3.4) Solo per il genitore, facoltativo come nel verdetto: il figlio lo dice
    # gia' la proposta; se c'e', deve esistere e combaciare (404 altrimenti). Dal
    # dispositivo si ignora.
    figlio_id: int | None = None


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
    # resta valido. Facoltativo: senza, l'abbinamento va come prima. (v3.6) Anche
    # "genitore", per il codice di un genitore: li' e' obbligatorio.
    tipo: Literal["telefono", "computer", "genitore"] | None = None


# (v3.5) Le Sessioni (contratto-api.md, "v3.5 — le Sessioni"). Le app di una sessione
# sono nomi di pacchetti Android, scelti sul telefono tra le app installate, oppure
# GRUPPO_APK = tutte le app installate fuori dal Play Store. Il nome di un pacchetto
# segue la regola di Android: almeno due pezzi separati da punti, ciascuno che comincia
# con una lettera e fatto di lettere, cifre e "_". Cosi' exe:, sito:, categoria:* e
# totale (le chiavi delle regole) non passano mai: 422.
GRUPPO_APK = "gruppo:apk"
PACCHETTO_ANDROID = re.compile(r"[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+")
APP_MASSIME_SESSIONE = 200
SESSIONI_MASSIME_PER_DISPOSITIVO = 20  # non eliminate; oltre: 409 troppe_sessioni
LUNGHEZZA_MASSIMA_CHIAVE = 255
LUNGHEZZA_MASSIMA_ETICHETTA = 100
LUNGHEZZA_MASSIMA_MOTIVAZIONE = 500
DURATA_MASSIMA_SESSIONE = 1440  # minuti: solo il limite tecnico di un giorno


# (v3.5) I caratteri che non si vedono o comandano la scrittura: di controllo (Cc, come
# a capo e tab), di formato (Cf: zero-width, i segni che girano il verso del testo) e i
# separatori di riga e paragrafo (Zl, Zp). Un nome che li contiene puo' sembrare un
# altro nome: si rifiuta. Da un'etichetta invece si tolgono e basta (v. _etichetta).
CATEGORIE_INVISIBILI = ("Cc", "Cf", "Zl", "Zp")


def _visibile(carattere: str) -> bool:
    return unicodedata.category(carattere) not in CATEGORIE_INVISIBILI


def _nome_sessione(nome: str) -> str:
    """Come il nome di un figlio o di un dispositivo: 1-40 caratteri, spazi ai bordi
    tolti. (v3.5) In forma NFC (la stessa parola scritta in due modi e' lo stesso
    nome) e senza caratteri invisibili o di controllo (422). L'unicita' (senza
    maiuscole, tra le sessioni del dispositivo) la controlla la route, dentro il lock."""
    nome = unicodedata.normalize("NFC", nome.strip())
    if not all(_visibile(c) for c in nome):
        raise ValueError("il nome non puo' avere caratteri invisibili o di controllo")
    if not 1 <= len(nome) <= LUNGHEZZA_MASSIMA_NOME:
        raise ValueError(f"il nome va da 1 a {LUNGHEZZA_MASSIMA_NOME} caratteri")
    return nome


def _etichetta(nome: str) -> str:
    """Un'etichetta come la mostrano le app: NFC, senza i caratteri invisibili o di
    controllo (si tolgono: un'app vera puo' averne nel nome, e per questo non si
    rifiuta la sessione), spazi ai bordi tolti."""
    nome = unicodedata.normalize("NFC", nome)
    return "".join(c for c in nome if _visibile(c)).strip()


def _app_sessione(app: list[str]) -> list[str]:
    """Ogni chiave e' un pacchetto Android o gruppo:apk; i doppioni si tolgono (resta
    il primo, nell'ordine mandato) e poi si contano: da 1 a 200."""
    for chiave in app:
        if chiave != GRUPPO_APK and not (
            len(chiave) <= LUNGHEZZA_MASSIMA_CHIAVE and PACCHETTO_ANDROID.fullmatch(chiave)
        ):
            raise ValueError(
                f"{chiave!r} non va bene: servono nomi di pacchetti Android o {GRUPPO_APK}"
            )
    senza_doppioni = list(dict.fromkeys(app))
    if not 1 <= len(senza_doppioni) <= APP_MASSIME_SESSIONE:
        raise ValueError(f"le app di una sessione vanno da 1 a {APP_MASSIME_SESSIONE}")
    return senza_doppioni


def _nomi_sessione(nomi: dict[str, str]) -> dict[str, str]:
    """Le etichette leggibili, risolte sul telefono come in uso_giornaliero: chiavi
    fino a 255 caratteri, etichette fino a 100 (ripulite da _etichetta). Quelle vuote
    si lasciano cadere qui; quelle di app che non sono nella lista, nella route."""
    puliti = {}
    for chiave, nome in nomi.items():
        if len(chiave) > LUNGHEZZA_MASSIMA_CHIAVE:
            raise ValueError(f"le chiavi di nomi arrivano a {LUNGHEZZA_MASSIMA_CHIAVE} caratteri")
        nome = _etichetta(nome)
        if len(nome) > LUNGHEZZA_MASSIMA_ETICHETTA:
            raise ValueError(f"i nomi arrivano a {LUNGHEZZA_MASSIMA_ETICHETTA} caratteri")
        if nome:
            puliti[chiave] = nome
    return puliti


class SessioneIn(BaseModel):
    nome: str
    app: list[str]
    nomi: dict[str, str] | None = None

    @field_validator("nome")
    @classmethod
    def _nome(cls, nome: str) -> str:
        return _nome_sessione(nome)

    @field_validator("app")
    @classmethod
    def _app(cls, app: list[str]) -> list[str]:
        return _app_sessione(app)

    @field_validator("nomi")
    @classmethod
    def _nomi(cls, nomi: dict[str, str] | None) -> dict[str, str] | None:
        return None if nomi is None else _nomi_sessione(nomi)


class SessionePatch(BaseModel):
    """(v3.5) Almeno un campo: quelli che mancano (o null) restano come sono."""

    nome: str | None = None
    app: list[str] | None = None
    nomi: dict[str, str] | None = None

    @field_validator("nome")
    @classmethod
    def _nome(cls, nome: str | None) -> str | None:
        return None if nome is None else _nome_sessione(nome)

    @field_validator("app")
    @classmethod
    def _app(cls, app: list[str] | None) -> list[str] | None:
        return None if app is None else _app_sessione(app)

    @field_validator("nomi")
    @classmethod
    def _nomi(cls, nomi: dict[str, str] | None) -> dict[str, str] | None:
        return None if nomi is None else _nomi_sessione(nomi)

    @model_validator(mode="after")
    def _almeno_un_campo(self):
        if self.nome is None and self.app is None and self.nomi is None:
            raise ValueError("serve almeno uno tra nome, app e nomi")
        return self


class AvviaSessioneIn(BaseModel):
    # (v3.5) StrictInt: true non e' un minuto, "30" e 30.0 neanche.
    durata_minuti: StrictInt = Field(ge=1, le=DURATA_MASSIMA_SESSIONE)


class TerminaSessioneIn(BaseModel):
    # epoch in millisecondi UTC: quando il figlio ha chiuso la sessione sul telefono,
    # magari senza rete (le regole per usarlo stanno nella route).
    ts_device: int | None = None
    # Facoltativo: l'id della sessione svolta che il telefono vuole chiudere. Se non e'
    # quella in corso, 404: una chiusura rimasta in coda e consegnata dopo l'avvio di
    # un'altra sessione non chiude quella nuova. StrictInt: true non e' l'id 1.
    svolta_id: StrictInt | None = None


class RispostaSessioneIn(BaseModel):
    esito: Literal["approva", "rifiuta"]
    # Obbligatoria: la versione della sessione che il genitore ha sullo schermo. Se
    # intanto il figlio l'ha cambiata, 409 richiesta_cambiata e niente si decide.
    # StrictInt: true non deve combaciare con la versione 1.
    versione: StrictInt
    motivazione: str | None = Field(default=None, max_length=LUNGHEZZA_MASSIMA_MOTIVAZIONE)
    # Facoltativo, come nel verdetto: se c'e', deve esistere ed essere il figlio della
    # sessione (404 altrimenti).
    figlio_id: int | None = None


# (v3.6) Le faccende (contratto-api.md, "La faccenda"). Il titolo segue le regole del
# nome di una sessione (NFC, niente caratteri invisibili o di controllo, spazi ai bordi
# tolti), fino a 80 caratteri; la nota le stesse fino a 300, ma con gli a capo.
LUNGHEZZA_MASSIMA_TITOLO = 80
LUNGHEZZA_MASSIMA_NOTA = 300
FACCENDE_PER_VOLTA = 10


def _titolo_faccenda(titolo: str) -> str:
    titolo = unicodedata.normalize("NFC", titolo.strip())
    if not all(_visibile(c) for c in titolo):
        raise ValueError("il titolo non puo' avere caratteri invisibili o di controllo")
    if not 1 <= len(titolo) <= LUNGHEZZA_MASSIMA_TITOLO:
        raise ValueError(f"il titolo va da 1 a {LUNGHEZZA_MASSIMA_TITOLO} caratteri")
    return titolo


def nota_faccenda(nota: str | None) -> str | None:
    """Una nota di una faccenda o di una bocciatura: come un titolo, ma gli a capo
    sono ammessi (CR LF e CR da soli diventano LF). Una nota vuota non c'e' (None)."""
    if nota is None:
        return None
    nota = unicodedata.normalize("NFC", nota.replace("\r\n", "\n").replace("\r", "\n").strip())
    if not all(c == "\n" or _visibile(c) for c in nota):
        raise ValueError("la nota non puo' avere caratteri invisibili o di controllo")
    if len(nota) > LUNGHEZZA_MASSIMA_NOTA:
        raise ValueError(f"la nota arriva a {LUNGHEZZA_MASSIMA_NOTA} caratteri")
    return nota or None


def _istante(testo: str) -> str:
    """Un istante con il fuso (ISO 8601, come i ts_server), portato in UTC a secondi
    interi. Un testo che non e' una data, o una data senza fuso, non va: "alle 16"
    senza fuso non dice quando."""
    try:
        quando = datetime.fromisoformat(testo)
    except ValueError:
        raise ValueError("non e' una data ISO 8601")
    if quando.tzinfo is None or quando.utcoffset() is None:
        raise ValueError("manca il fuso (per esempio +02:00 o Z)")
    try:
        return quando.astimezone(timezone.utc).isoformat(timespec="seconds")
    except (OverflowError, ValueError):
        raise ValueError("data fuori misura")


class FaccendaIn(BaseModel):
    titolo: str
    nota: str | None = None

    @field_validator("titolo")
    @classmethod
    def _titolo(cls, titolo: str) -> str:
        return _titolo_faccenda(titolo)

    @field_validator("nota")
    @classmethod
    def _nota(cls, nota: str | None) -> str | None:
        return nota_faccenda(nota)


class FaccendeIn(BaseModel):
    # Obbligatorio: qui non vale "il primo figlio" (dare faccende al figlio sbagliato
    # blocca il telefono sbagliato). StrictInt: true non e' il figlio 1.
    figlio_id: StrictInt
    faccende: list[FaccendaIn] = Field(min_length=1, max_length=FACCENDE_PER_VOLTA)
    # Da quando bloccano: assente o null = subito. Il controllo dei 7 giorni lo fa la
    # route, che conosce l'ora del server.
    blocco_da: str | None = None

    @field_validator("blocco_da")
    @classmethod
    def _blocco_da(cls, blocco_da: str | None) -> str | None:
        return None if blocco_da is None else _istante(blocco_da)


class BocciaIn(BaseModel):
    nota: str | None = None

    @field_validator("nota")
    @classmethod
    def _nota(cls, nota: str | None) -> str | None:
        return nota_faccenda(nota)


class ConfermaFaccendaIn(BaseModel):
    """(v3.9) Il corpo facoltativo di POST /api/faccende/{id}/conferma: il `foto_ts` della
    foto che il genitore ha guardato. Una data col fuso (422 altrimenti), tenuta com'e':
    la route la confronta per istante con quella della faccenda. null = come senza."""

    foto_ts: str | None = None

    @field_validator("foto_ts")
    @classmethod
    def _foto_ts(cls, foto_ts: str | None) -> str | None:
        if foto_ts is not None:
            _istante(foto_ts)  # solo il controllo: una data con fuso
        return foto_ts


class ModificaFaccendaIn(BaseModel):
    """(v3.9) PATCH /api/faccende/{id}: almeno uno dei tre campi. Un campo assente resta
    com'e' (assente e null si distinguono con model_fields_set): `titolo` con le regole
    della creazione e mai null; `nota` null o vuota toglie la nota; `blocco_da` una data
    col fuso, null = subito (i 7 giorni li controlla la route, che conosce l'ora)."""

    titolo: str | None = None
    nota: str | None = None
    blocco_da: str | None = None

    @field_validator("titolo")
    @classmethod
    def _titolo(cls, titolo: str | None) -> str:
        if titolo is None:
            raise ValueError("il titolo non si toglie")
        return _titolo_faccenda(titolo)

    @field_validator("nota")
    @classmethod
    def _nota(cls, nota: str | None) -> str | None:
        return nota_faccenda(nota)

    @field_validator("blocco_da")
    @classmethod
    def _blocco_da(cls, blocco_da: str | None) -> str | None:
        return None if blocco_da is None else _istante(blocco_da)

    @model_validator(mode="after")
    def _almeno_un_campo(self):
        if not self.model_fields_set & {"titolo", "nota", "blocco_da"}:
            raise ValueError("serve almeno uno tra titolo, nota e blocco_da")
        return self


# (v4.0) La Sessione Studio (contratto-api.md, "v4.0 — C. La Sessione Studio").
GIORNI_SETTIMANA = ("lun", "mar", "mer", "gio", "ven", "sab", "dom")
MINUTI_MINIMI_STUDIO = (10, 600)
VOCI_MASSIME_STUDIO = 200
LUNGHEZZA_MASSIMA_FIRMA = 200
LUNGHEZZA_CHIAVE_STUDIO = 64
DICHIARAZIONE_STUDIO = (10, 1000)
MOTIVO_CHIUSURA = (3, 300)
LUNGHEZZA_MASSIMA_PAROLA = 30
TRATTI_PER_VOLTA = 50
SECONDI_MASSIMI_TRATTO = 48 * 3600
CAMPI_CONFIG_STUDIO = ("giorni", "inizio", "chiusura_minima", "minuti_minimi", "telefono", "computer")


def _app_studio(app: list[str]) -> list[str]:
    """Come le app di una sessione (pacchetti Android o gruppo:apk), ma da 0 a 200: una
    lista vuota vuol dire "solo le app sempre usabili"."""
    for chiave in app:
        if chiave != GRUPPO_APK and not (
            len(chiave) <= LUNGHEZZA_MASSIMA_CHIAVE and PACCHETTO_ANDROID.fullmatch(chiave)
        ):
            raise ValueError(
                f"{chiave!r} non va bene: servono nomi di pacchetti Android o {GRUPPO_APK}"
            )
    senza_doppioni = list(dict.fromkeys(app))
    if len(senza_doppioni) > VOCI_MASSIME_STUDIO:
        raise ValueError(f"le app dello Studio arrivano a {VOCI_MASSIME_STUDIO}")
    return senza_doppioni


def _programmi_studio(programmi: list[str]) -> list[str]:
    """Le voci del computer: exe:<nome> o sito:<dominio>, con le regole delle chiavi del
    computer (v3: minuscole, senza spazi ne' percorsi). categoria:*, totale e i pacchetti
    Android non vanno. Da 0 a 200, senza doppioni. Che un exe: sia un browser lo controlla
    la route (422 browser_nella_lista)."""
    for chiave in programmi:
        if (
            len(chiave) > LUNGHEZZA_MASSIMA_CHIAVE
            or not chiave.startswith(PREFISSI_SOLO_COMPUTER)
            or not chiave_adatta("computer", chiave)
        ):
            raise ValueError(f"{chiave!r} non va bene: servono voci exe:<programma> o sito:<dominio>")
    senza_doppioni = list(dict.fromkeys(programmi))
    if len(senza_doppioni) > VOCI_MASSIME_STUDIO:
        raise ValueError(f"le voci del computer arrivano a {VOCI_MASSIME_STUDIO}")
    return senza_doppioni


def _firme_studio(firme: dict[str, str]) -> dict[str, str]:
    """Il soggetto del certificato di ogni programma firmato: da 1 a 200 caratteri
    (ripulito come un'etichetta). Le chiavi che non sono voci exe: della lista le lascia
    cadere la route, che conosce la lista."""
    pulite = {}
    for chiave, firma in firme.items():
        firma = _etichetta(firma)
        if not 1 <= len(firma) <= LUNGHEZZA_MASSIMA_FIRMA:
            raise ValueError(f"una firma va da 1 a {LUNGHEZZA_MASSIMA_FIRMA} caratteri")
        pulite[chiave] = firma
    return pulite


class ListaTelefonoStudio(BaseModel):
    app: list[str]
    nomi: dict[str, str] | None = None

    @field_validator("app")
    @classmethod
    def _app(cls, app: list[str]) -> list[str]:
        return _app_studio(app)

    @field_validator("nomi")
    @classmethod
    def _nomi(cls, nomi: dict[str, str] | None) -> dict[str, str] | None:
        return None if nomi is None else _nomi_sessione(nomi)


class ListaComputerStudio(BaseModel):
    programmi: list[str]
    nomi: dict[str, str] | None = None
    firme: dict[str, str] | None = None

    @field_validator("programmi")
    @classmethod
    def _programmi(cls, programmi: list[str]) -> list[str]:
        return _programmi_studio(programmi)

    @field_validator("nomi")
    @classmethod
    def _nomi(cls, nomi: dict[str, str] | None) -> dict[str, str] | None:
        return None if nomi is None else _nomi_sessione(nomi)

    @field_validator("firme")
    @classmethod
    def _firme(cls, firme: dict[str, str] | None) -> dict[str, str] | None:
        return None if firme is None else _firme_studio(firme)


class StudioConfigPatch(BaseModel):
    """PATCH /api/studio/config: almeno un campo. I campi che mancano si prendono dalla
    proposta in attesa, se c'e', altrimenti da quella approvata (o, alla prima proposta,
    dai valori di partenza): lo fa la route, dentro il lock. telefono e computer si
    sostituiscono interi."""

    giorni: list[GiornoSettimana] | None = None
    inizio: str | None = Field(default=None, pattern=ORARIO)
    chiusura_minima: str | None = Field(default=None, pattern=ORARIO)
    minuti_minimi: StrictInt | None = Field(
        default=None, ge=MINUTI_MINIMI_STUDIO[0], le=MINUTI_MINIMI_STUDIO[1]
    )
    telefono: ListaTelefonoStudio | None = None
    computer: ListaComputerStudio | None = None

    @field_validator("giorni")
    @classmethod
    def _giorni(cls, giorni: list[str] | None) -> list[str] | None:
        if giorni is None:
            return None
        presenti = set(giorni)
        if not presenti:
            raise ValueError("serve almeno un giorno: lo Studio parte senza eccezioni")
        return [g for g in GIORNI_SETTIMANA if g in presenti]  # senza doppioni, in ordine

    @model_validator(mode="after")
    def _almeno_un_campo(self):
        if all(getattr(self, campo) is None for campo in CAMPI_CONFIG_STUDIO):
            raise ValueError("serve almeno un campo della configurazione dello Studio")
        return self


class RispostaStudioIn(BaseModel):
    esito: Literal["approva", "rifiuta"]
    # La versione della configurazione che il genitore ha sullo schermo (un intero vero).
    versione: StrictInt
    motivazione: str | None = Field(default=None, max_length=LUNGHEZZA_MASSIMA_MOTIVAZIONE)
    figlio_id: StrictInt | None = None


def _parola_tratto(parola: str) -> str:
    """La parola di un tratto `altro` (es. "allenamento"): le regole dei nomi, 1-30."""
    parola = unicodedata.normalize("NFC", parola.strip())
    if not all(_visibile(c) for c in parola):
        raise ValueError("la parola non puo' avere caratteri invisibili o di controllo")
    if not 1 <= len(parola) <= LUNGHEZZA_MASSIMA_PAROLA:
        raise ValueError(f"la parola va da 1 a {LUNGHEZZA_MASSIMA_PAROLA} caratteri")
    return parola


def _ms_valido(ms: int) -> bool:
    if ms < 0:
        return False
    try:
        datetime.fromtimestamp(ms / 1000, tz=timezone.utc)
    except (OverflowError, OSError, ValueError):
        return False
    return True


class TrattoIn(BaseModel):
    """Un tratto del timer, come lo manda il telefono. `inizio` e `fine` sono ms (l'ora del
    server agganciata, o l'orologio del telefono con ora_agganciata false). Un tratto in
    corso ha `inizio` e niente `fine`; uno finito o interrotto ha `fine` e i `secondi`."""

    id: str = Field(min_length=1, max_length=128)
    tipo: Literal["compiti", "lavori_di_casa", "altro"]
    parola: str | None = None
    faccenda_id: StrictInt | None = None
    inizio: StrictInt | None = None
    fine: StrictInt | None = None
    ora_agganciata: StrictBool
    secondi: StrictInt = Field(ge=0, le=SECONDI_MASSIMI_TRATTO)
    esito: Literal["in_corso", "finito", "interrotto"]

    @field_validator("parola")
    @classmethod
    def _parola(cls, parola: str | None) -> str | None:
        return None if parola is None else _parola_tratto(parola)

    @model_validator(mode="after")
    def _coerente(self):
        if self.tipo == "altro" and self.parola is None:
            raise ValueError("un tratto 'altro' vuole la parola (per esempio allenamento)")
        if self.faccenda_id is not None and self.tipo != "lavori_di_casa":
            # (Correzione) faccenda_id va solo con lavori_di_casa: su un altro tipo vale
            # null, come per un lavoro che non e' del figlio (contratto, «se non e' un
            # lavoro del figlio vale null»). Un 422 rifiuterebbe tutto il pacco (fino a 50
            # tratti, o la chiusura col corpo) e bloccherebbe la coda del telefono.
            self.faccenda_id = None
        if self.esito == "in_corso":
            if self.inizio is None or not _ms_valido(self.inizio):
                raise ValueError("un tratto in corso vuole l'inizio")
            if self.fine is not None:
                raise ValueError("un tratto in corso non ha la fine")
        else:
            if self.fine is None or not _ms_valido(self.fine):
                raise ValueError("un tratto finito vuole la fine")
            if self.secondi < 1:
                raise ValueError("un tratto finito dura almeno un secondo")
        return self


class TrattiIn(BaseModel):
    tratti: list[TrattoIn] = Field(min_length=1, max_length=TRATTI_PER_VOLTA)


class AvviaStudioIn(BaseModel):
    chiave: str = Field(min_length=1, max_length=LUNGHEZZA_CHIAVE_STUDIO)
    ts_device: StrictInt | None = None


class QualeStudioIn(BaseModel):
    """Lo Studio di una chiusura senza id: quello che contiene la partenza di quel giorno,
    oppure quello avviato a mano con quella chiave. Uno dei due."""

    giorno: str | None = None
    chiave: str | None = Field(default=None, min_length=1, max_length=LUNGHEZZA_CHIAVE_STUDIO)

    @field_validator("giorno")
    @classmethod
    def _giorno(cls, giorno: str | None) -> str | None:
        if giorno is not None:
            try:
                valido = date.fromisoformat(giorno).isoformat() == giorno
            except ValueError:
                valido = False
            if not valido:
                raise ValueError("il giorno va scritto YYYY-MM-DD")
        return giorno

    @model_validator(mode="after")
    def _uno_dei_due(self):
        if (self.giorno is None) == (self.chiave is None):
            raise ValueError("serve il giorno oppure la chiave dello Studio")
        return self


def _testo_lungo(testo: str, minimo: int, massimo: int, cosa: str) -> str:
    """La dichiarazione del figlio o il motivo del genitore: le regole della nota di un
    lavoro (NFC, a capo ammessi, niente caratteri invisibili, spazi ai bordi tolti), con
    una lunghezza minima e massima."""
    testo = unicodedata.normalize("NFC", testo.replace("\r\n", "\n").replace("\r", "\n").strip())
    if not all(c == "\n" or _visibile(c) for c in testo):
        raise ValueError(f"{cosa} non puo' avere caratteri invisibili o di controllo")
    if not minimo <= len(testo) <= massimo:
        raise ValueError(f"{cosa} va da {minimo} a {massimo} caratteri")
    return testo


class ChiudiStudioIn(BaseModel):
    """La chiusura del figlio, dal telefono (anche consegnata dopo, senza rete)."""

    chiave: str = Field(min_length=1, max_length=LUNGHEZZA_CHIAVE_STUDIO)
    ts_device: StrictInt | None = None
    dichiarazione: str
    tratti: list[TrattoIn] | None = Field(default=None, max_length=TRATTI_PER_VOLTA)
    studio: QualeStudioIn | None = None

    @field_validator("dichiarazione")
    @classmethod
    def _dichiarazione(cls, testo: str) -> str:
        return _testo_lungo(testo, *DICHIARAZIONE_STUDIO, "la dichiarazione")


class ChiudiStudioGenitoreIn(BaseModel):
    """La chiusura del genitore: senza condizioni, ma con un motivo."""

    motivo: str
    figlio_id: StrictInt | None = None

    @field_validator("motivo")
    @classmethod
    def _motivo(cls, testo: str) -> str:
        return _testo_lungo(testo, *MOTIVO_CHIUSURA, "il motivo")
