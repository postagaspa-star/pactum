"""Lock asimmetrico del patto: stringere una regola e' immediato, allentarla
richiede 4 giorni dall'ultima creazione/modifica (salvo proposta concordata).
Qui vive solo la classificazione allenta/stringe; il conteggio dei giorni
sta nella route delle regole."""


def _minuti(orario: str) -> int:
    ore, minuti = orario.split(":")
    return int(ore) * 60 + int(minuti)


def _copertura(dalle: str, alle: str) -> frozenset[int]:
    """Minuti del giorno coperti dalla fascia; gestisce il cavallo di mezzanotte.
    dalle == alle -> fascia vuota."""
    a, b = _minuti(dalle), _minuti(alle)
    if a == b:
        return frozenset()
    if a < b:
        return frozenset(range(a, b))
    return frozenset(range(a, 1440)) | frozenset(range(0, b))


def allenta(tipo: str, prima: dict, dopo: dict) -> bool:
    if tipo == "limite_tempo":
        if prima["app_o_categoria"] != dopo["app_o_categoria"]:
            return True  # cambiare bersaglio svuota la regola originale: conta come allentamento
        return dopo["minuti_al_giorno"] > prima["minuti_al_giorno"]
    if tipo == "fascia_oraria":
        # La fascia e' una restrizione: stringe solo se la nuova copre tutta la vecchia
        # (orari E giorni); qualsiasi riduzione o spostamento e' un allentamento.
        stringe = _copertura(dopo["dalle"], dopo["alle"]) >= _copertura(prima["dalle"], prima["alle"]) and set(
            dopo["giorni"]
        ) >= set(prima["giorni"])
        return not stringe
    return True  # vita_reale: scelta conservativa, ogni modifica conta come allentamento
