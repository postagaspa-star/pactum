"""Il confronto col valore attuale che il server calcola per ogni proposta
(compare nella notifica al figlio). La classificazione allenta/stringe e' quella
di lock.py: qui si aggiunge solo il testo in italiano semplice della differenza."""

from . import lock

MENO = "−"  # segno meno tipografico (U+2212), come nel contratto

ORDINE_GIORNI = ["lun", "mar", "mer", "gio", "ven", "sab", "dom"]


def _confronto_limite(prima: dict, dopo: dict) -> str:
    if prima["app_o_categoria"] != dopo["app_o_categoria"]:
        return (
            f"da {prima['app_o_categoria']} ({prima['minuti_al_giorno']} min) "
            f"a {dopo['app_o_categoria']} ({dopo['minuti_al_giorno']} min) al giorno"
        )
    delta = dopo["minuti_al_giorno"] - prima["minuti_al_giorno"]
    segno = "+" if delta >= 0 else MENO
    return f"{segno}{abs(delta)} min al giorno rispetto ad ora"


def _confronto_fascia(prima: dict, dopo: dict) -> str:
    parti = []
    if (prima["dalle"], prima["alle"]) != (dopo["dalle"], dopo["alle"]):
        parti.append(
            f"orario da {prima['dalle']}-{prima['alle']} a {dopo['dalle']}-{dopo['alle']}"
        )
    prima_g, dopo_g = set(prima["giorni"]), set(dopo["giorni"])
    if prima_g != dopo_g:
        # Elencare i giorni che entrano/escono: uno scambio di pari numero (es. esce
        # lun, entra dom) rendeva "giorni da 1 a 1", che non diceva niente.
        escono = [g for g in ORDINE_GIORNI if g in prima_g - dopo_g]
        entrano = [g for g in ORDINE_GIORNI if g in dopo_g - prima_g]
        frasi = []
        if escono:
            frasi.append("esce " + ", ".join(escono))
        if entrano:
            frasi.append("entra " + ", ".join(entrano))
        parti.append(", ".join(frasi))
    return "; ".join(parti) if parti else "nessuna modifica alla copertura"


def _confronto_vita(prima: dict, dopo: dict) -> str:
    parti = []
    if prima["descrizione"] != dopo["descrizione"]:
        parti.append(f"descrizione: \"{prima['descrizione']}\" -> \"{dopo['descrizione']}\"")
    if prima["frequenza"] != dopo["frequenza"]:
        parti.append(f"frequenza: {prima['frequenza']} -> {dopo['frequenza']}")
    if prima["arbitro_nome"] != dopo["arbitro_nome"]:
        parti.append(f"arbitro: {prima['arbitro_nome']} -> {dopo['arbitro_nome']}")
    return "; ".join(parti) if parti else "nessuna modifica"


def confronto_e_direzione(tipo: str, prima: dict, dopo: dict) -> tuple[str, str]:
    """Testo del confronto e direzione (allenta/stringe) di una proposta di MODIFICA.
    L'eliminazione (marcatore) la gestisce la route: confronto fisso, direzione 'elimina'."""
    direzione = "allenta" if lock.allenta(tipo, prima, dopo) else "stringe"
    if tipo == "limite_tempo":
        return _confronto_limite(prima, dopo), direzione
    if tipo == "fascia_oraria":
        return _confronto_fascia(prima, dopo), direzione
    return _confronto_vita(prima, dopo), direzione
