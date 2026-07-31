"""Battito finto per la DEMO: tiene "in contatto" il patto quando l'app del
figlio non sta girando su un telefono vero. Un battito ogni 5 minuti al server
locale. Da usare SOLO in demo (token di sviluppo)."""
import json
import time
import urllib.request

URL = "http://127.0.0.1:8000/api/battito"
TOKEN = "dev-token-figlio"

print("Battitore demo attivo: un battito ogni 5 minuti. Non chiudere durante la demo.")
while True:
    try:
        req = urllib.request.Request(
            URL,
            data=json.dumps({"versione_app": "0.3.0"}).encode(),
            headers={"Authorization": f"Bearer {TOKEN}", "Content-Type": "application/json"},
            method="POST",
        )
        with urllib.request.urlopen(req, timeout=5) as r:
            print(time.strftime("%H:%M:%S"), "battito consegnato ->", r.status)
    except Exception as e:  # server giu' o non ancora partito: riprova al giro dopo
        print(time.strftime("%H:%M:%S"), "battito fallito:", e)
    time.sleep(300)
