"""(v3.6) Minecraft nell'edizione Java sul computer (contratto-api.md, "Minecraft Java
sul computer"): il programma del computer lo conta come `exe:minecraft-java`, nome
leggibile "Minecraft (Java)". Per il server e' una chiave `exe:` come le altre: nelle
fotografie, nelle regole, nelle proposte e nei nomi della finestra."""

from aiuti_v3 import dispositivo_abbinato, eventi, regola
from conftest import FIGLIO, GENITORE

JAVA = "exe:minecraft-java"


def _limite(minuti, app=JAVA):
    return {"app_o_categoria": app, "minuti_al_giorno": minuti}


def test_minecraft_java_e_una_chiave_del_computer(client):
    pc, pc_id = dispositivo_abbinato(client, 1, "Computer", "computer")
    sul_pc = regola(client, pc, parametri=_limite(60))
    assert sul_pc["parametri"]["app_o_categoria"] == JAVA and sul_pc["dispositivo_id"] == pc_id
    # scritta dal telefono per il computer, anche
    assert client.post("/api/regole", json={"tipo": "limite_tempo", "parametri": _limite(30),
                                            "dispositivo_id": pc_id}, headers=FIGLIO).status_code == 201
    # ma non sul telefono, e solo minuscola
    assert client.post("/api/regole", json={"tipo": "limite_tempo", "parametri": _limite(30)},
                       headers=FIGLIO).status_code == 422
    assert client.post("/api/regole", json={"tipo": "limite_tempo", "parametri": _limite(30, "exe:Minecraft-Java")},
                       headers=pc).status_code == 422


def test_minecraft_java_nelle_fotografie_e_nella_finestra(client):
    pc, pc_id = dispositivo_abbinato(client, 1, "Computer", "computer")
    minecraft = regola(client, pc, parametri=_limite(60, "exe:minecraft.exe"))
    java = regola(client, pc, parametri=_limite(45))
    eventi(client, pc, {
        "id": "uso-pc-java", "tipo": "uso_giornaliero",
        "dettagli": {"giorno": "2026-07-14", "uso_minuti": {JAVA: 50, "exe:minecraft.exe": 10},
                     "nomi": {JAVA: "Minecraft (Java)", "exe:minecraft.exe": "Minecraft"},
                     "uso_categorie": {"categoria:giochi": 60}, "totale_minuti": 60},
    })
    finestra = client.get("/api/finestra", headers=GENITORE).json()
    nomi = {r["id"]: r.get("nome") for r in finestra["regole"]}
    assert nomi[java["id"]] == "Minecraft (Java)"
    (del_pc,) = [d for d in finestra["dispositivi"] if d["id"] == pc_id]
    (oggi,) = [v for v in del_pc["uso_recente"] if v["giorno"] == "2026-07-14"]
    assert oggi["app"][0] == {"chiave": JAVA, "nome": "Minecraft (Java)", "minuti": 50, "limite": 45,
                              "regola_id": java["id"], "bonus": 0}
    # nel confronto delle proposte, coi nomi che leggono le persone
    p = client.post("/api/proposte", json={"regola_id": minecraft["id"], "parametri_proposti": _limite(60)},
                    headers=GENITORE)
    assert p.status_code == 200, p.text
    assert p.json()["confronto"] == "da Minecraft (60 min) a Minecraft (Java) (60 min) al giorno"
